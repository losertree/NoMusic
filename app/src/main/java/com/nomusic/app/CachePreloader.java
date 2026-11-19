package com.nomusic.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Single speculative transfer lane. Playing audio always takes priority over bulk caching. */
final class CachePreloader {
    private static CachePreloader instance;
    static synchronized CachePreloader get(Context context){if(instance==null)instance=new CachePreloader(context.getApplicationContext(),AudioCache.get(context));return instance;}
    private final Context context;
    private final AudioCache cache;
    private final AppPrefs prefs;
    private final SharedPreferences checkpoints;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final List<MediaItem> tracks=new ArrayList<>();
    private final Set<String> skipped=new HashSet<>();
    private EmbyClient listClient,libraryClient;
    private int cursor,revision=-1,epoch;
    private String currentId="";
    private AudioQuality quality;
    private long readyAt,fullBytes,fullLimit,retryAt,lastCheckpoint;
    private int consecutiveFailures;
    private boolean playing,buffering,draining,listFull,connected=true;
    private RetryPolicy.Token flight;
    private State library;
    private EmbyClient.Page page;
    private int pageStart=-1;
    private final Runnable wake=()->{synchronized(this){kick();}};

    static final class State {
        String account="",message="尚未开始";
        int next,total,completed,failed;
        boolean running,finished,full,waiting;
        State copy(){State value=new State();value.account=account;value.message=message;value.next=next;value.total=total;value.completed=completed;value.failed=failed;value.running=running;value.finished=finished;value.full=full;return value;}
        String label(){return message+(total>0?" · "+next+" / "+total+" 首":"")+(failed>0?" · "+failed+" 首未完成":"");}
    }
    CachePreloader(Context context,AudioCache cache){this.context=context;this.cache=cache;prefs=new AppPrefs(context);quality=prefs.audioQuality();checkpoints=context.getSharedPreferences("cache_jobs",Context.MODE_PRIVATE);}
    synchronized State state(EmbyClient client) {
        String account=client.cacheAccount();
        if(library!=null&&library.account.equals(account)){
            State value=library.copy();
            if(value.running&&!connected){value.waiting=true;value.message="等待网络恢复";}
            else if(value.running&&buffering){value.waiting=true;value.message="播放缓冲中，稍后继续缓存";}
            else if(value.running&&playing&&SystemClock.elapsedRealtime()<retryAt){value.message="连接暂时中断，稍后重试";}
            return value;
        }
        State value=new State();value.account=account;
        try {
            JSONObject saved=new JSONObject(checkpoints.getString(value.account,"{}"));
            value.next=saved.optInt("next");value.total=saved.optInt("total");value.failed=saved.optInt("failed");value.completed=saved.optInt("completed");
            value.finished=saved.optBoolean("finished");value.full=saved.optBoolean("full");
            value.message=value.finished?"已完成":value.full?"缓存已满，可提高上限后继续":value.next>0?"已暂停，可继续":"尚未开始";
        }catch(Exception ignored){}
        return value;
    }
    void start(EmbyClient client) {
        synchronized(this) {
            if(!client.authenticated()||!client.currentSession()||library!=null&&library.running)return;
            cancelFlight();library=state(client);libraryClient=client;page=null;pageStart=-1;lastCheckpoint=0;
            if(library.finished){library.next=library.completed=library.failed=library.total=0;library.finished=false;}
            library.running=true;library.full=false;library.message="正在读取曲库";
            prefs.setBool("audio_cache",true);save();kick();
        }
        context.startService(new Intent(context,CacheService.class));cache.changed();
    }
    void pause() {
        synchronized(this){cancelFlight();if(library!=null){library.running=false;library.message="已暂停，可继续";save();}kick();}
        cache.changed();
    }
    /** Clearing or disabling caching must not immediately refill the files the user removed. */
    void suspend(boolean resetLibrary) {
        synchronized(this){cancelFlight();tracks.clear();cursor=0;resetFailures();
            libraryClient=new EmbyClient(prefs);library=state(libraryClient);library.running=false;
            if(resetLibrary){library.next=library.completed=library.failed=library.total=0;library.finished=library.full=false;library.message="尚未开始";page=null;}
            else if(!library.finished)library.message="已暂停，可继续";
            save();}
        cache.cancelAutomatic();cache.changed();
    }
    void cancelStale() {
        synchronized(this) {
            if(listClient!=null&&!listClient.currentSession()){cancelFlight();tracks.clear();listClient=null;revision=-1;resetFailures();}
            if(libraryClient!=null&&!libraryClient.currentSession()&&library!=null&&library.running){cancelFlight();library.running=false;library.message="请重新登录后继续";save();}
            kick();
        }
        cache.changed();
    }
    private void cancelFlight(){epoch++;if(flight!=null)flight.cancel();}
    private void resetFailures(){skipped.clear();retryAt=0;consecutiveFailures=0;main.removeCallbacks(wake);}
    void playback(EmbyClient client,List<MediaItem> queue,int index,int queueRevision,boolean active,boolean stalled) {
        synchronized(this) {
            String id=index>=0&&index<queue.size()?queue.get(index).id:"";
            AudioQuality selectedQuality=prefs.audioQuality();
            if(quality!=selectedQuality){quality=selectedQuality;cancelFlight();cursor=0;listFull=false;resetFailures();}
            boolean changed=listClient==null||!listClient.currentSession()||!listClient.sameAccount(client)||revision!=queueRevision||!currentId.equals(id);
            if(changed||playing!=active||!buffering&&stalled)cancelFlight();
            if(!currentId.equals(id)||!playing&&active)readyAt=SystemClock.elapsedRealtime()+6000;
            boolean stallChanged=buffering!=stalled;
            if(changed) {
                listClient=client;revision=queueRevision;currentId=id;tracks.clear();cursor=0;listFull=false;resetFailures();
                if(index>=0&&index<queue.size()) {
                    tracks.add(queue.get(index));
                    for(int at=index+1;at<queue.size();at++)if(queue.get(at).isAudio())tracks.add(queue.get(at));
                    for(int at=0;at<index;at++)if(queue.get(at).isAudio())tracks.add(queue.get(at));
                }
            }
            playing=active;buffering=stalled;
            kick();
            if(stallChanged&&library!=null&&library.running)cache.changed();
        }
    }
    void capacityChanged(){synchronized(this){if(listFull&&(cache.automaticBytes()<fullBytes||cache.limit(false)>fullLimit)){listFull=false;cursor=0;kick();}}}
    void network(boolean available){
        synchronized(this){if(connected==available)return;connected=available;
            if(!available)cancelFlight();else{retryAt=0;consecutiveFailures=0;kick();}}
        cache.changed();
    }
    synchronized void resumeQueue(){revision=-1;}
    private synchronized void kick(){if(!draining&&prefs.bool("audio_cache",true)&&(listReady()||libraryReady())){draining=true;worker.execute(this::drain);}}
    private void alive(int ticket,EmbyClient client,RetryPolicy.Token token)throws IOException {
        token.run();
        synchronized(this){if(ticket!=epoch||!client.currentSession()||!client.authenticated()||!prefs.bool("audio_cache",true))throw new InterruptedIOException("Cancelled");}
    }
    private void waitPlayback(int ticket,EmbyClient client,RetryPolicy.Token token)throws IOException {
        alive(ticket,client,token);
        while(true){synchronized(this){if(!buffering&&(!playing||SystemClock.elapsedRealtime()>=readyAt))return;}
            RetryPolicy.waitFor(200,()->alive(ticket,client,token));}
    }
    private boolean listReady(){return connected&&playing&&!buffering&&!listFull&&SystemClock.elapsedRealtime()>=retryAt&&listClient!=null&&listClient.currentSession()&&listClient.authenticated()&&cursor<tracks.size();}
    private boolean libraryReady(){return connected&&library!=null&&library.running&&libraryClient!=null&&libraryClient.currentSession()&&!buffering&&(!playing||SystemClock.elapsedRealtime()>=retryAt);}
    private void drain() {
        try {
            while(true) {
                EmbyClient selected;MediaItem next;int ticket;boolean bulk,current;RetryPolicy.Token token;
                synchronized(this) {
                    if(!prefs.bool("audio_cache",true))return;
                    while(cursor<tracks.size()&&listClient!=null) {
                        MediaItem item=tracks.get(cursor);
                        if(!cache.cached(listClient,item)&&!skipped.contains(item.id))break;
                        cursor++;
                    }
                    if(listReady()){selected=listClient;next=tracks.get(cursor);bulk=false;current=next.id.equals(currentId);}
                    else if(libraryReady()){selected=libraryClient;next=null;bulk=true;current=false;}
                    else return;
                    ticket=epoch;token=new RetryPolicy.Token();flight=token;
                }
                boolean advance=true;
                try {
                    waitPlayback(ticket,selected,token);
                    if(bulk)libraryStep(selected,ticket,token);
                    else {cache.prefetch(selected,next,prefs.audioQuality(),current,()->waitPlayback(ticket,selected,token),token);
                        synchronized(this){if(ticket==epoch)consecutiveFailures=0;}}
                }catch(Exception problem){
                    synchronized(this) {
                        if(ticket!=epoch||token.cancelled()||!selected.currentSession()){advance=false;continue;}
                        if(problem instanceof AudioCache.Full) {
                            if(bulk){library.running=false;library.full=true;library.message="缓存已满，可提高上限或清理后继续";save();}
                            else{listFull=true;fullBytes=cache.automaticBytes();fullLimit=cache.limit(false);}
                        }else if(bulk) {
                            library.running=false;library.message=EmbyClient.message(problem)+"，可继续重试";save();
                        }else if(RetryPolicy.transientFailure(problem)) {
                            long delay=consecutiveFailures++==0?30000:60000;retryAt=SystemClock.elapsedRealtime()+delay;advance=false;
                            main.removeCallbacks(wake);main.postDelayed(wake,delay);
                        }else if(problem instanceof EmbyClient.ApiException&&((EmbyClient.ApiException)problem).status==403){
                            cursor=tracks.size();
                        }else{
                            skipped.add(next.id);
                        }
                    }
                    cache.changed();
                }finally{synchronized(this){if(flight==token)flight=null;}}
                synchronized(this){if(!bulk&&advance&&ticket==epoch)cursor++;}
            }
        }finally {
            synchronized(this){draining=false;kick();}
        }
    }
    private void libraryStep(EmbyClient client,int ticket,RetryPolicy.Token token)throws Exception {
        int at;EmbyClient.Page selectedPage;int start;
        synchronized(this){alive(ticket,client,token);at=library.next;selectedPage=page;start=pageStart;}
        if(selectedPage==null||start>at||at>=start+selectedPage.received){selectedPage=client.browse(BrowseSpec.songs(),at,100,token);start=at;}
        synchronized(this){alive(ticket,client,token);page=selectedPage;pageStart=start;library.total=selectedPage.total;library.message="正在缓存曲库";}
        if(selectedPage.received==0){finishLibrary(ticket,client,token);return;}
        int offset=at-start;
        if(offset>=selectedPage.items.size()){
            synchronized(this){alive(ticket,client,token);library.next=start+selectedPage.received;save();}return;
        }
        MediaItem item=selectedPage.items.get(offset);boolean success=false;
        try {
            if(item.isAudio())cache.prefetch(client,item,prefs.audioQuality(),false,()->waitPlayback(ticket,client,token),token);
            success=true;
        }catch(EmbyClient.ApiException failure){if(failure.status!=404&&failure.status!=415&&failure.status!=400)throw failure;}
        synchronized(this){alive(ticket,client,token);library.next=at+1;if(success)library.completed++;else library.failed++;
            if(SystemClock.elapsedRealtime()-lastCheckpoint>=2000){lastCheckpoint=SystemClock.elapsedRealtime();save();}
            if(library.next>=library.total)finishLibrary(ticket,client,token);}
        cache.changed();
    }
    private synchronized void finishLibrary(int ticket,EmbyClient client,RetryPolicy.Token token)throws IOException {
        alive(ticket,client,token);library.running=false;library.finished=true;library.message="已完成";save();cache.changed();
    }
    private void save(){
        if(library==null)return;
        try{JSONObject data=new JSONObject().put("next",library.next).put("total",library.total).put("completed",library.completed).put("failed",library.failed).put("finished",library.finished).put("full",library.full);
            checkpoints.edit().putString(library.account,data.toString()).apply();
        }catch(Exception ignored){}
    }
}
