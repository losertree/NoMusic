package com.nomusic.app;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Keeps completed audio only. Automatic copies are disposable; explicit downloads are private files. */
final class AudioCache {
    static final String CHANGED="com.nomusic.app.DOWNLOADS_CHANGED";
    private static final long TRACK_LIMIT=1024L*1024*1024,FREE_RESERVE=64L*1024*1024;
    private static AudioCache instance;
    interface Result { void done(String error); }

    static final class Local {
        final File file;
        final AudioQuality quality;
        Local(File file,AudioQuality quality){this.file=file;this.quality=quality;}
    }

    private final Context context;
    private final AppPrefs prefs;
    private final File root;
    private final ExecutorService downloads=Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService disk=Executors.newSingleThreadScheduledExecutor();
    private final CountDownLatch indexed=new CountDownLatch(1);
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Map<String,Download> active=new HashMap<>();
    private final Map<String,Failure> failed=new HashMap<>();
    private static final class Failure {
        final EmbyClient client;final MediaItem item;final String error;
        Failure(EmbyClient client,MediaItem item,String error){this.client=client;this.item=item;this.error=error;}
    }
    private final Map<String,Capture> captures=new HashMap<>();
    private String protectedPath="";
    private static final class Entry {
        final File file;final String path,account,track,key;final long size;final boolean pinned;long played;
        Entry(File file){
            this.file=file;path=file.getPath();size=file.length();played=file.lastModified();
            File dir=file.getParentFile();pinned="saved".equals(dir.getName());account=dir.getParentFile().getName();
            track=AudioQuality.trackKey(file.getName());key=account+"/"+track;
        }
    }
    private final Map<String,Entry> complete=new HashMap<>();
    private final Map<String,Integer> available=new HashMap<>();
    private final Map<String,long[]> accountBytes=new HashMap<>();
    private final TreeSet<Entry> oldest=new TreeSet<>((a,b)->{int result=Long.compare(a.played,b.played);return result==0?a.path.compareTo(b.path):result;});
    private final android.util.LruCache<String,String> keys=new android.util.LruCache<>(1024);
    private long automaticBytes,pinnedBytes;
    private static final class History {
        final Map<String,Long> last=new HashMap<>();final Set<String> dirty=new HashSet<>();
        boolean loaded,scheduled;
    }
    private final Map<String,History> histories=new HashMap<>();
    private long lastPlaybackAt;
    private final AtomicBoolean announcementPending=new AtomicBoolean();
    private final AtomicInteger downloadRevision=new AtomicInteger();
    private volatile int automaticEpoch;

    static synchronized AudioCache get(Context context) {
        if(instance==null)instance=new AudioCache(context.getApplicationContext());
        return instance;
    }

    AudioCache(Context context) {
        this.context=context;
        prefs=new AppPrefs(context);
        root=new File(context.getFilesDir(),"offline-v1");
        disk.execute(() -> {
            try{cleanParts(root);scanIndex();synchronized(AudioCache.this){pruneAutomatic();}}
            finally{indexed.countDown();}
            changed();
        });
    }

    private String hash(String value){String found=keys.get(value);if(found==null){found=StorageFiles.key(value);keys.put(value,found);}return found;}
    private File account(EmbyClient client){return new File(root,client.cacheAccount());}
    private File directory(EmbyClient client,boolean pinned){return new File(account(client),pinned?"saved":"auto");}
    private String name(MediaItem item){return hash(item.id);}
    private static File audio(File dir,String name,AudioQuality quality){return new File(dir,name+quality.suffix);}
    private void scanIndex() {
        File[] accounts=root.listFiles();if(accounts==null)return;
        for(File account:accounts)if(account.isDirectory()) {
            loadHistory(account);
            for(String kind:new String[]{"saved","auto"}){
                File[] files=new File(account,kind).listFiles((d,n)->!AudioQuality.trackKey(n).isEmpty());if(files==null)continue;
                for(File file:files){Entry entry=new Entry(file);if(entry.size>0&&(!entry.pinned||new File(file.getParentFile(),entry.track+".json").isFile())){
                    synchronized(this){if(!complete.containsKey(entry.path))register(entry);}
                }}
            }
        }
    }
    synchronized boolean cached(EmbyClient client,MediaItem item) {
        return item.isAudio()&&playableCached(client,item,prefs.audioQuality());
    }
    private synchronized boolean playableCached(EmbyClient client,MediaItem item,AudioQuality quality) {
        boolean allowOther=!prefs.hasChosenAudioQuality();
        if(local(client,item,quality,allowOther)!=null)return true;
        String key=name(item);
        if(!allowOther&&find(directory(client,true),key,quality,true)!=null)return true;
        return !allowOther&&quality.original()&&audioFile(directory(client,false),key,AudioQuality.BALANCED)!=null;
    }
    boolean indexReady(){return indexed.getCount()==0;}
    private void awaitIndex()throws IOException{
        try{indexed.await();}catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new java.io.InterruptedIOException("Cancelled");}
    }
    private synchronized void register(File file){
        if(complete.containsKey(file.getPath()))return;
        Entry entry=new Entry(file);if(entry.size>0)register(entry);
    }
    private void register(Entry entry){
        History history=histories.get(entry.account);Long time=history==null?null:history.last.get(entry.track);
        if(time!=null)entry.played=time;
        complete.put(entry.path,entry);if(!entry.pinned)oldest.add(entry);count(entry,1);
    }
    private void count(Entry entry,int direction){
        long[] bytes=accountBytes.get(entry.account);if(bytes==null){bytes=new long[2];accountBytes.put(entry.account,bytes);}
        bytes[entry.pinned?0:1]+=entry.size*direction;
        if(entry.pinned)pinnedBytes+=entry.size*direction;else automaticBytes+=entry.size*direction;
        Integer previous=available.get(entry.key);int files=(previous==null?0:previous)+direction;
        if(files==0)available.remove(entry.key);else available.put(entry.key,files);
    }
    private void forget(File file){Entry removed=complete.remove(file.getPath());if(removed!=null){oldest.remove(removed);count(removed,-1);}}
    private boolean erase(File file){if(!file.exists()||file.delete()){forget(file);return true;}return false;}
    private History history(String account){History result=histories.get(account);if(result==null){result=new History();histories.put(account,result);}return result;}
    private void loadHistory(File account) {
        History history;synchronized(this){history=history(account.getName());if(history.loaded)return;}
        Map<String,Long> loaded=new HashMap<>();
        try {
            JSONObject values=StorageFiles.readJson(new File(account,"last-played.json"),2*1024*1024);
            java.util.Iterator<String> keys=values.keys();while(keys.hasNext()){String key=keys.next();loaded.put(key,values.optLong(key));}
        }catch(Exception ignored){}
        synchronized(this){
            if(history.loaded)return;
            for(Map.Entry<String,Long> entry:loaded.entrySet())if(!history.last.containsKey(entry.getKey()))history.last.put(entry.getKey(),entry.getValue());
            history.loaded=true;
            // Playback can find and protect a file before the startup scan reaches it.
            for(Entry entry:complete.values())if(!entry.pinned&&entry.account.equals(account.getName())&&history.last.containsKey(entry.track))touch(entry,history.last.get(entry.track));
        }
    }
    void played(EmbyClient client,MediaItem item) {
        if(item==null)return;
        File account=account(client),dir=new File(account,"auto");String track=name(item);
        synchronized(this){
            long at=Math.max(System.currentTimeMillis(),lastPlaybackAt+1);lastPlaybackAt=at;
            History history=history(account.getName());history.last.put(track,at);history.dirty.add(track);
            for(AudioQuality quality:AudioQuality.ALL){Entry entry=complete.get(audio(dir,track,quality).getPath());if(entry!=null)touch(entry,at);}
            if(!history.scheduled){history.scheduled=true;disk.schedule(()->saveHistory(account,history),1200,TimeUnit.MILLISECONDS);}
        }
    }
    private void touch(Entry entry,long time){oldest.remove(entry);entry.played=time;oldest.add(entry);}
    private void saveHistory(File account,History history){
        loadHistory(account);Map<String,Long> saved;Map<String,Long> dirty=new HashMap<>();
        synchronized(this){
            history.scheduled=false;for(String track:history.dirty)dirty.put(track,history.last.get(track));history.dirty.clear();
            if(history.last.size()>16000){List<String> keys=new ArrayList<>(history.last.keySet());Collections.sort(keys,(a,b)->Long.compare(history.last.get(a),history.last.get(b)));for(int i=0;i<keys.size()-16000;i++)history.last.remove(keys.get(i));}
            saved=new HashMap<>(history.last);
        }
        File dir=new File(account,"auto");for(Map.Entry<String,Long> entry:dirty.entrySet())for(AudioQuality quality:AudioQuality.ALL)audio(dir,entry.getKey(),quality).setLastModified(entry.getValue());
        try{StorageFiles.writeJson(new File(account,"last-played.json"),new JSONObject(saved));}catch(Exception ignored){}
    }
    private void stamp(EmbyClient client,MediaItem item,File file) {
        Long at=history(account(client).getName()).last.get(name(item));file.setLastModified(at==null?1:Math.max(1,at));register(file);
    }
    long limit(boolean offline){return prefs.cacheLimit(offline);}
    void setLimit(boolean offline,long bytes){prefs.setCacheLimit(offline,bytes);disk.execute(()->{synchronized(this){pruneAutomatic();}changed();});}
    private long freeBytes(){return Math.max(0,context.getFilesDir().getUsableSpace()-FREE_RESERVE);}
    synchronized void protect(Local local){
        String next=local==null?"":local.file.getPath();boolean released=!protectedPath.isEmpty()&&!protectedPath.equals(next);protectedPath=next;
        if(released&&automaticBytes>limit(false))disk.execute(()->{synchronized(this){pruneAutomatic();}changed();});
    }
    synchronized Local localForPlayback(EmbyClient client,MediaItem item,AudioQuality quality,boolean allowOther){
        Local local=local(client,item,quality,allowOther);
        if(local==null&&!allowOther)local=find(directory(client,true),name(item),quality,true);
        if(local==null&&!allowOther&&quality.original())local=audioFile(directory(client,false),name(item),AudioQuality.BALANCED);
        protect(local);return local;
    }
    synchronized long automaticBytes(){return automaticBytes;}
    private final Object automaticTransfer=new Object();
    private volatile RetryPolicy.Token automaticToken;
    static final class Full extends IOException {Full(){super("Cache full");}}
    /** Runs off the UI thread. All automatic transfers share one lane. */
    boolean prefetch(EmbyClient client,MediaItem item,AudioQuality quality,boolean evict,RetryPolicy.Check check)throws Exception {
        return prefetch(client,item,quality,evict,check,new RetryPolicy.Token());
    }
    boolean prefetch(EmbyClient client,MediaItem item,AudioQuality quality,boolean evict,RetryPolicy.Check check,RetryPolicy.Token token)throws Exception {
        synchronized(automaticTransfer) {
            automaticToken=token;
            try {
            final int epoch=automaticEpoch;
            RetryPolicy.Check alive=()->{
                token.run();check.run();
                if(epoch!=automaticEpoch||!client.currentSession()||!client.authenticated()||!prefs.bool("audio_cache",true))throw new java.io.InterruptedIOException("Cancelled");
            };
            alive.run();
            awaitIndex();loadHistory(account(client));
            if(playableCached(client,item,quality))return false;
            try{return RetryPolicy.read(()->prefetchOnce(client,item,quality,evict,alive,token),alive);}
            catch(EmbyClient.ApiException problem){
                if(!quality.original()||!AudioQuality.sourceUnavailable(problem.status))throw problem;
                return RetryPolicy.read(()->prefetchOnce(client,item,AudioQuality.BALANCED,evict,alive,token),alive);
            }
            }finally{automaticToken=null;}
        }
    }
    private boolean prefetchOnce(EmbyClient client,MediaItem item,AudioQuality quality,boolean evict,RetryPolicy.Check alive,RetryPolicy.Token token)throws Exception {
        alive.run();
        if(playableCached(client,item,quality))return false;
        File dir=directory(client,false),target=audio(dir,name(item),quality);
        if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Cannot create cache directory");
        File part=new File(target.getPath()+".warm.part");
        HttpURLConnection connection=client.open(client.streamPath(item,quality,UUID.randomUUID().toString()));
        try {
            token.attach(connection);connection.setConnectTimeout(8000);connection.setReadTimeout(8000);
            connection.setRequestProperty("Accept-Encoding","identity");connection.setRequestProperty("Connection","close");
            int status=client.response(connection);if(status!=200)throw new EmbyClient.ApiException(status);
            long length=completeLength(connection,status);
            if(length==-2)throw new java.net.ProtocolException("Incomplete audio response");
            if(length>TRACK_LIMIT||length>limit(false))throw new Full();
            synchronized(this){if(!evict&&!makeRoom(Math.max(0,length),false)||length>freeBytes())throw new Full();}
            long written=0;
            try(InputStream input=connection.getInputStream();FileOutputStream output=new FileOutputStream(part)) {
                byte[] buffer=new byte[32768];int count;
                while((count=input.read(buffer))!=-1) {
                    alive.run();
                    if(written+count>Math.min(TRACK_LIMIT,limit(false))||written/1048576!=(written+count)/1048576&&freeBytes()<count)throw new Full();
                    output.write(buffer,0,count);written+=count;
                }
                if(written==0||length>=0&&written!=length)throw new java.io.EOFException("Incomplete audio response");
                output.getFD().sync();
            }
            alive.run();
            synchronized(this) {
                token.run();if(!client.currentSession()||!prefs.bool("audio_cache",true))throw new java.io.InterruptedIOException("Cancelled");
                if(playableCached(client,item,quality))return false;
                if(!makeRoom(written,evict))throw new Full();
                if(!part.renameTo(target))throw new IOException("Cannot save cached audio");
                stamp(client,item,target);pruneAutomatic();
            }
            changed();return true;
        } finally {token.detach(connection);part.delete();connection.disconnect();}
    }

    synchronized Local local(EmbyClient client,MediaItem item,AudioQuality quality,boolean allowOther) {
        File saved=directory(client,true),cached=directory(client,false);String key=name(item);
        Local value=find(saved,key,quality,false);
        if(value==null)value=find(cached,key,quality,false);
        if(value==null&&allowOther)value=find(saved,key,quality,true);
        if(value==null&&allowOther)value=find(cached,key,quality,true);
        return value;
    }

    private Local find(File dir,String key,AudioQuality quality,boolean allowOther) {
        if("saved".equals(dir.getName())&&!new File(dir,key+".json").isFile())return null;
        Local found=audioFile(dir,key,quality);
        if(found!=null)return found;
        if(allowOther)for(AudioQuality other:AudioQuality.OFFLINE_ORDER)if(other!=quality){
            found=audioFile(dir,key,other);if(found!=null)return found;
        }
        return null;
    }
    private Local audioFile(File dir,String key,AudioQuality quality){
        File file=audio(dir,key,quality);
        if(file.isFile()&&file.length()>0){register(file);return new Local(file,quality);}
        forget(file);return null;
    }

    synchronized boolean saved(EmbyClient client,MediaItem item){return find(directory(client,true),name(item),AudioQuality.BALANCED,true)!=null;}
    private boolean saved(EmbyClient client,MediaItem item,AudioQuality quality){return find(directory(client,true),name(item),quality,false)!=null;}

    synchronized void invalidate(Local local) { erase(local.file);if("saved".equals(local.file.getParentFile().getName()))downloadChanged();else changed(); }
    synchronized List<MediaItem> pending(EmbyClient client) {
        List<MediaItem> items=new ArrayList<>();
        for(Download job:active.values())if(!job.cancelled&&job.client.sameAccount(client))items.add(job.item);
        for(Failure failure:failed.values())if(failure.client.sameAccount(client)&&!active.containsKey(jobKey(client,failure.item)))items.add(failure.item);
        return items;
    }

    synchronized int progress(EmbyClient client,MediaItem item) {
        Download job=active.get(jobKey(client,item));
        return job==null||job.cancelled?-1:job.progress;
    }
    synchronized String failure(EmbyClient client,MediaItem item){Failure entry=failed.get(jobKey(client,item));return entry==null?"":entry.error;}

    List<MediaItem> savedItems(EmbyClient client) {
        List<MediaItem> items=new ArrayList<>();
        File dir=directory(client,true);
        File[] files=dir.listFiles((d,n)->n.endsWith(".json"));
        if(files!=null)for(File file:files)try {
            MediaItem item=new MediaItem(StorageFiles.readJson(file,64*1024));
            if(saved(client,item))items.add(item);
        } catch(Exception ignored) { }
        Collections.sort(items,(a,b)->a.name.compareToIgnoreCase(b.name));
        return items;
    }

    private String jobKey(EmbyClient client,MediaItem item){return client.cacheAccount()+"/"+name(item);}

    void download(EmbyClient client,MediaItem item,AudioQuality quality,Result callback) {
        synchronized(this) {
            String key=jobKey(client,item);
            failed.remove(key);
            Download existing=active.get(key);
            if(existing!=null&&!existing.cancelled){existing.callbacks.add(callback);return;}
            if(active.size()>=64){main.post(()->callback.done("下载任务已满，请稍后添加"));return;}
            Download job=new Download(client,item,quality,key,callback);active.put(key,job);
            downloads.execute(()->run(job));
        }
        downloadChanged();
    }

    void cancel(EmbyClient client,MediaItem item) {
        synchronized(this) {String key=jobKey(client,item);Download job=active.get(key);if(job!=null){job.cancelled=true;job.token.cancel();}failed.remove(key);}
        downloadChanged();
    }

    void cancelStale() {
        synchronized(this) { for(Download job:active.values())if(!job.client.currentSession()){job.cancelled=true;job.token.cancel();} }
        downloadChanged();
    }

    void remove(EmbyClient client,MediaItem item) {
        cancel(client,item);
        disk.execute(()->{
            synchronized(this) {
                File dir=directory(client,true);
                String key=name(item);
                for(AudioQuality quality:AudioQuality.ALL)erase(audio(dir,key,quality));new File(dir,key+".json").delete();
            }
            downloadChanged();
        });
    }

    void clearAutomatic(EmbyClient client) {
        cancelAutomatic();
        disk.execute(()->{
            synchronized(this) {
                File dir=directory(client,false);
                File[] files=dir.listFiles((d,n)->!n.endsWith(".part"));
                if(files!=null)for(File file:files)if(!protectedPath.equals(file.getPath()))erase(file);
                for(Capture capture:captures.values())if(dir.equals(capture.target.getParentFile()))capture.discard=true;
            }
            changed();
        });
    }

    void cancelAutomatic(){
        automaticEpoch++;RetryPolicy.Token token=automaticToken;if(token!=null)token.cancel();
        synchronized(this){for(Capture capture:captures.values())capture.discard=true;}
    }

    private void run(Download job) {
        String error="";
        try {
            awaitIndex();
            job.check();
            File dir=directory(job.client,true);
            if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("无法写入下载目录");
            if(!saved(job.client,job.item)) {
                String key=name(job.item);
                boolean copied=false;
                synchronized(this) {
                    Local copy=find(directory(job.client,false),key,job.quality,!prefs.hasChosenAudioQuality()||!job.client.authenticated());
                    if(copy!=null){
                        job.check();
                        if(pinBytes()+copy.file.length()>limit(true))throw new IOException("离线存储已满");
                        File dest=audio(dir,key,copy.quality);
                        if(copy.file.renameTo(dest)) {
                            forget(copy.file);
                            try { StorageFiles.writeJson(new File(dir,key+".json"),job.item.toJson()); }
                            catch(Exception e){dest.renameTo(copy.file);register(copy.file);throw e;}
                            register(dest);copied=true;
                        }
                    }
                }
                if(!copied) {
                    try { transferWithRetry(job,dir,key,job.quality); }
                    catch(EmbyClient.ApiException e) {
                        if(!job.quality.original()||!AudioQuality.sourceUnavailable(e.status))throw e;
                        transferWithRetry(job,dir,key,AudioQuality.BALANCED);
                    }
                }
            }
            if(job.client.currentSession())LyricsRepository.get(context).load(job.client,job.item,false,ignored->{});
        } catch(Exception e) {
            if(!job.cancelled&&job.client.currentSession())error=e instanceof IOException&&e.getMessage()!=null&&
                    (e.getMessage().startsWith("离线")||e.getMessage().startsWith("无法写入")||e.getMessage().startsWith("音频"))?
                    e.getMessage():EmbyClient.message(e);
        }
        final String outcome=error;
        final List<Result> listeners;
        synchronized(this) {
            if(active.get(job.key)==job)active.remove(job.key);
            if(!job.cancelled&&!outcome.isEmpty())failed.put(job.key,new Failure(job.client,job.item,outcome));
            listeners=new ArrayList<>(job.callbacks);
        }
        downloadChanged();
        if(!job.cancelled&&job.client.currentSession())main.post(()->{
            if(!job.cancelled&&job.client.currentSession())for(Result listener:listeners)listener.done(outcome);
        });
    }

    private void transferWithRetry(Download job,File dir,String key,AudioQuality quality) throws Exception {
        RetryPolicy.read(()->{job.progress=0;transfer(job,dir,key,quality);return null;},job::check);
    }

    private void transfer(Download job,File dir,String key,AudioQuality quality) throws Exception {
        if(!job.client.authenticated())throw new IOException("离线状态下无法下载新歌曲");
        File part=new File(dir,key+".download.part"),target=audio(dir,key,quality);
        HttpURLConnection connection=job.client.open(job.client.streamPath(job.item,quality,UUID.randomUUID().toString()));
        try {
            job.token.attach(connection);
            connection.setRequestProperty("Accept-Encoding","identity");
            connection.setRequestProperty("Connection","close");
            int status=job.client.response(connection);
            if(status!=200)throw new EmbyClient.ApiException(status);
            long length=completeLength(connection,status);
            if(length==-2)throw new IOException("音频响应不完整，无法保存");
            long available=Math.min(Math.min(TRACK_LIMIT,limit(true)-pinBytes()),freeBytes());
            if(available<=0||length>available)throw new IOException("离线存储已满或单曲超过大小限制");
            long written=0;
            try(InputStream input=connection.getInputStream();FileOutputStream output=new FileOutputStream(part)) {
                byte[] buffer=new byte[32768];int n;
                while(length<0||written<length) {
                    n=input.read(buffer,0,length<0?buffer.length:(int)Math.min(buffer.length,length-written));
                    if(n==-1)break;
                    job.check();written+=n;
                    if(written>available)throw new IOException("离线存储已满或单曲超过大小限制");
                    output.write(buffer,0,n);
                    int progress=length>0?(int)(written*100/length):0;
                    if(progress>=job.progress+10){job.progress=progress;downloadChanged();}
                }
                if(written==0||length>=0&&length!=written)throw new java.io.EOFException("音频下载中断，请重试");
                output.getFD().sync();
            }
            synchronized(this) {
                job.check();
                if(pinBytes()+written>limit(true))throw new IOException("离线存储已满");
                if(!part.renameTo(target))throw new IOException("无法写入离线音频");
                try { StorageFiles.writeJson(new File(dir,key+".json"),job.item.toJson()); }
                catch(Exception e){target.delete();throw e;}
                register(target);
            }
        } finally { job.token.detach(connection);part.delete();connection.disconnect(); }
    }

    /** -1 is a fully framed chunked response; -2 means completeness cannot be verified. */
    static long completeLength(HttpURLConnection connection,int status) {
        String mime=connection.getContentType();
        if(mime!=null&&!mime.startsWith("audio/")&&!mime.startsWith("application/octet-stream"))return -2;
        try {
            if(status==206) {
                String header=connection.getHeaderField("Content-Range");
                if(header==null||!header.startsWith("bytes 0-"))return -2;
                int slash=header.indexOf('/');
                long total=Long.parseLong(header.substring(slash+1));
                return Long.parseLong(header.substring(8,slash))==total-1&&total>0?total:-2;
            }
            if(status!=200)return -2;
            String length=connection.getHeaderField("Content-Length");
            if(length!=null){long size=Long.parseLong(length);return size>0?size:-2;}
            return "chunked".equalsIgnoreCase(connection.getHeaderField("Transfer-Encoding"))?-1:-2;
        } catch(RuntimeException e) { return -2; }
    }

    synchronized Capture capture(EmbyClient client,MediaItem item,AudioQuality quality,HttpURLConnection connection,int status) {
        long length=completeLength(connection,status);
        if(length==-2||length>Math.min(TRACK_LIMIT,limit(false))||length>freeBytes()||!client.currentSession())return null;
        File dir=directory(client,false),target=audio(dir,name(item),quality);
        if(!prefs.bool("audio_cache",true)||target.isFile()||saved(client,item,quality)||captures.containsKey(target.getPath()))return null;
        try {
            if(!dir.isDirectory()&&!dir.mkdirs())return null;
            Capture capture=new Capture(client,item,quality,target,length);captures.put(target.getPath(),capture);return capture;
        } catch(IOException e) { return null; }
    }

    final class Capture {
        final EmbyClient client;final MediaItem item;
        final AudioQuality quality;
        final File target,part;
        final long length;
        final FileOutputStream output;
        volatile boolean discard;
        long written;
        Capture(EmbyClient client,MediaItem item,AudioQuality quality,File target,long length) throws IOException {
            this.client=client;this.item=item;this.quality=quality;this.target=target;this.length=length;
            part=new File(target.getPath()+".stream.part");output=new FileOutputStream(part);
        }
        void write(byte[] bytes,int n) {
            if(discard)return;
            if(written+n>Math.min(TRACK_LIMIT,limit(false))||written/1048576!=(written+n)/1048576&&freeBytes()<n){discard=true;return;}
            try { output.write(bytes,0,n);written+=n; } catch(IOException e){discard=true;}
        }
        void close(boolean eof) {
            try { output.close(); } catch(IOException e){discard=true;}
            if(eof&&!discard)try{awaitIndex();loadHistory(account(client));}catch(IOException cancelled){discard=true;}
            synchronized(AudioCache.this) {
                if(eof&&!discard&&client.currentSession()&&prefs.bool("audio_cache",true)&&!target.exists()&&!saved(client,item,quality)&&written>0&&(length<0||written==length)&&makeRoom(written,true)&&part.renameTo(target)){stamp(client,item,target);pruneAutomatic();changed();}
                part.delete();captures.remove(target.getPath());
            }
        }
    }

    private synchronized long pinBytes() {return pinnedBytes;}

    synchronized long[] usage(EmbyClient client) {
        long[] bytes=accountBytes.get(account(client).getName());return bytes==null?new long[2]:bytes.clone();
    }

    private void pruneAutomatic() {
        makeRoom(0,true);
    }
    private boolean makeRoom(long incoming,boolean evict) {
        if(incoming>limit(false))return false;
        if(automaticBytes+incoming<=limit(false))return true;
        if(!evict)return false;
        List<Entry> skipped=new ArrayList<>();
        while(automaticBytes+incoming>limit(false)&&!oldest.isEmpty()) {
            Entry entry=oldest.pollFirst();
            if(protectedPath.equals(entry.path)||!erase(entry.file))skipped.add(entry);
        }
        oldest.addAll(skipped);
        changed();return automaticBytes+incoming<=limit(false);
    }

    private static void cleanParts(File directory) {
        File[] children=directory.listFiles();
        if(children!=null)for(File file:children) {
            if(file.isDirectory())cleanParts(file);
            else if(file.getName().endsWith(".part")&&System.currentTimeMillis()-file.lastModified()>3600000)file.delete();
        }
    }

    int downloadRevision(){return downloadRevision.get();}
    private void downloadChanged(){downloadRevision.incrementAndGet();changed();}
    void changed(){if(announcementPending.compareAndSet(false,true))main.postDelayed(this::broadcastChange,200);}
    private void broadcastChange(){announcementPending.set(false);context.sendBroadcast(new Intent(CHANGED).setPackage(context.getPackageName()));}

    private static final class Download {
        final EmbyClient client;final MediaItem item;final AudioQuality quality;final String key;
        final List<Result> callbacks=new ArrayList<>();
        final RetryPolicy.Token token=new RetryPolicy.Token();
        volatile boolean cancelled;
        volatile int progress;
        Download(EmbyClient client,MediaItem item,AudioQuality quality,String key,Result callback) {
            this.client=client;this.item=item;this.quality=quality;this.key=key;callbacks.add(callback);
        }
        void check() throws IOException {token.run();if(cancelled||!client.currentSession())throw new java.io.InterruptedIOException("Cancelled");}
    }
}
