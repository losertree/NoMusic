package com.nomusic.app;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.RemoteControlClient;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** All player/queue state is confined to the main thread; I/O runs on bounded workers. */
@SuppressWarnings("deprecation")
public final class PlaybackService extends Service implements AudioManager.OnAudioFocusChangeListener {
    public static final String TOGGLE="nomusic.TOGGLE", PLAY="nomusic.PLAY", PAUSE="nomusic.PAUSE",
            NEXT="nomusic.NEXT", PREVIOUS="nomusic.PREVIOUS", STOP="nomusic.STOP", FORWARD="nomusic.FORWARD", REWIND="nomusic.REWIND";
    public static final String CHANGED="com.nomusic.app.PLAYBACK_CHANGED";
    private static final int NOTIFICATION_ID=11;
    public final class LocalBinder extends Binder { public PlaybackService service() { return PlaybackService.this; } }
    private final LocalBinder binder=new LocalBinder();
    private final Handler handler=new Handler();
    private final ExecutorService reports=Executors.newSingleThreadExecutor();
    private final ExecutorService artwork=Executors.newSingleThreadExecutor();
    private final ExecutorService pages=Executors.newSingleThreadExecutor();
    private final ArrayList<MediaItem> queue=new ArrayList<>();
    private final ArrayList<MediaItem> shuffleHistory=new ArrayList<>(),priority=new ArrayList<>();
    private final HashSet<MediaItem> shuffled=new HashSet<>();
    private final Random random=new Random();
    private AppPrefs prefs;
    private EmbyClient client;
    private AudioCache cache;
    private CachePreloader preloader;
    private AudioManager audio;
    private RemoteControlClient remote;
    private ComponentName mediaReceiver;
    private WifiManager.WifiLock wifiLock;
    private MediaPlayer player;
    private StreamProxy proxy;
    private AudioCache.Local local;
    private AudioQuality streamQuality=AudioQuality.BALANCED;
    private int index=-1, repeat=1, generation;
    private boolean shuffle, prepared, buffering, desiredPlay, transientResume, fallback, reported, registered, ducked;
    private long offset, savedPosition, lastPersist, lastReport;
    private long seekTarget=-1, stalledSince,lastMotion, retryAt,sleepAt, pageRetryAt;
    private int retryCount,upstream,pageFailures;
    private boolean waitingNetwork,waitingPage,pageLoading,sleepAfter,fadeSleep,lastOnline;
    private boolean sourceTrimmed;
    private float volume=1f;
    private QueueSource source;
    private int sourceGeneration,queueRevision;
    private String session="", error="";
    private Bitmap cover;
    private final SystemArtwork systemArtwork=new SystemArtwork();
    private final AudioEffects effects=new AudioEffects();

    private final BroadcastReceiver noisy=new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { transientResume=false; pause(); }
    };
    private final BroadcastReceiver network=new BroadcastReceiver() {
        @Override public void onReceive(Context context,Intent intent) {
            boolean now=online();
            if(now&&!lastOnline) { if(waitingNetwork)retryAt=0;if(pageFailures>0)pageRetryAt=0; }
            preloader.network(now);
            lastOnline=now;
        }
    };
    private final BroadcastReceiver sessions=new BroadcastReceiver() {
        @Override public void onReceive(Context context,Intent intent) {
            if(client.currentSession())return;
            EmbyClient previous=client;
            stopTrack(false);cancelSleepInternal();waitingPage=false;pageLoading=false;++sourceGeneration;
            transientResume=false;audio.abandonAudioFocus(PlaybackService.this);
            client=new EmbyClient(prefs);cache.cancelStale();preloader.cancelStale();
            if(!client.sameAccount(previous)) {
                queue.clear();priority.clear();shuffleHistory.clear();shuffled.clear();source=null;
                index=-1;savedPosition=0;cover=null;sourceTrimmed=false;restore();queueRevision++;
            }
            error=client.authenticated()||!prefs.hasAccount()?"":"Emby 登录已失效";
            if(!client.authenticated())stopForeground(true);
            updateMetadata();persist();changed();
        }
    };
    private final BroadcastReceiver cached=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){preloader.capacityChanged();}
    };

    private final Runnable ticker=new Runnable() {
        @Override public void run() {
            long now=SystemClock.elapsedRealtime();
            if(sleepAt!=0) {
                if(now>=sleepAt) { cancelSleepInternal();stopPlayback(false); }
                else if(fadeSleep)applyVolume(Math.max(0.05f,Math.min(1f,(sleepAt-now)/8000f)));
            } else if(sleepAfter&&fadeSleep&&duration()>0)applyVolume(Math.max(0.05f,Math.min(1f,(duration()-position())/8000f)));
            if(waitingNetwork&&desiredPlay&&now>=retryAt) {
                if(online()||localAvailable()) {
                    waitingNetwork=false;startTrack(savedPosition,true);
                } else retryAt=now+4000;
            }
            if(desiredPlay&&prepared&&player!=null&&local==null&&seekTarget<0) {
                long at=position();
                if(at>lastMotion+100) {lastMotion=at;stalledSince=now;}
                else if(now-stalledSince>=18000)streamFailed(at,true);
                if(prepared)savedPosition=Math.max(savedPosition,at);
            }
            if((desiredPlay||waitingPage)&&!waitingNetwork&&shouldPrefetch())loadMore();
            if (isPlaying() && now-lastPersist >= 5000) { persist(); lastPersist=now; }
            if (isPlaying() && now-lastReport >= 10000) { report("/Progress"); lastReport=now; }
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        prefs=new AppPrefs(this);
        client=new EmbyClient(prefs);
        cache=AudioCache.get(this);
        preloader=CachePreloader.get(this);
        audio=(AudioManager)getSystemService(AUDIO_SERVICE);
        mediaReceiver=new ComponentName(this, MediaButtonReceiver.class);
        Intent buttonIntent=new Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(mediaReceiver);
        PendingIntent button=PendingIntent.getBroadcast(this,0,buttonIntent,PendingIntent.FLAG_UPDATE_CURRENT);
        remote=new RemoteControlClient(button);
        remote.setTransportControlFlags(RemoteControlClient.FLAG_KEY_MEDIA_PLAY|RemoteControlClient.FLAG_KEY_MEDIA_PAUSE
                |RemoteControlClient.FLAG_KEY_MEDIA_PLAY_PAUSE|RemoteControlClient.FLAG_KEY_MEDIA_NEXT
                |RemoteControlClient.FLAG_KEY_MEDIA_PREVIOUS|RemoteControlClient.FLAG_KEY_MEDIA_STOP);
        WifiManager wifi=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifi != null) { wifiLock=wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"NoMusic:stream"); wifiLock.setReferenceCounted(false); }
        registerReceiver(noisy,new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        registerReceiver(network,new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        registerReceiver(sessions,new IntentFilter(AppPrefs.SESSION_CHANGED));
        registerReceiver(cached,new IntentFilter(AudioCache.CHANGED));
        lastOnline=online();
        preloader.network(lastOnline);
        restore();
        if (current()!=null) { registerRemote(); updateMetadata(); }
        handler.post(ticker);
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        String action=intent==null?null:intent.getAction();
        if (TOGGLE.equals(action)) toggle();
        else if (PLAY.equals(action)) play();
        else if (PAUSE.equals(action)) pause();
        else if (NEXT.equals(action)) next(false);
        else if (PREVIOUS.equals(action)) previous();
        else if (STOP.equals(action)) stopPlayback(false);
        else if (FORWARD.equals(action)) seek(position()+15000);
        else if (REWIND.equals(action)) seek(position()-15000);
        return START_STICKY;
    }

    public MediaItem current() { return index>=0 && index<queue.size()?queue.get(index):null; }
    public List<MediaItem> queue() { return new ArrayList<>(queue); }
    public int queueSize() { return queue.size(); }
    public int queueRevision() { return queueRevision; }
    public int index() { return index; }
    public boolean isPlaying() {
        try { return prepared && player!=null && player.isPlaying(); }
        catch(IllegalStateException ignored) { return false; }
    }
    public boolean isBuffering() { return buffering; }
    public boolean isOfflinePlayback() { return local!=null; }
    AudioQuality activeQuality(){return player!=null?streamQuality:null;}
    String audioEffectsStatus(AudioEffects.Profile profile){return effects.status(profile);}
    void audioEffectsChanged(AudioEffects.Profile profile){if(player!=null&&prepared)effects.apply(player.getAudioSessionId(),profile);}
    public boolean hasMore() { return source!=null&&source.more(); }
    public boolean allRandom() { return source!=null&&source.random(); }
    public boolean sleepAfterTrack() { return sleepAfter; }
    public long sleepRemaining() { return sleepAt==0?0:Math.max(0,sleepAt-SystemClock.elapsedRealtime()); }
    public boolean wantsPlay() { return desiredPlay; }
    public String error() { return error; }
    public boolean shuffle() { return shuffle; }
    public int repeat() { return repeat; }
    public Bitmap cover() { return cover; }
    public long duration() {
        if (current()!=null && current().durationMs>0) return current().durationMs;
        try { return prepared && player!=null?Math.max(0,player.getDuration())+offset:0; }
        catch(IllegalStateException ignored) { return 0; }
    }
    public long position() {
        if(seekTarget>=0)return seekTarget;
        try { return prepared && player!=null?Math.max(0,player.getCurrentPosition())+offset:savedPosition; }
        catch(IllegalStateException ignored) { return savedPosition; }
    }

    public void playQueue(List<MediaItem> items,int selected) {
        playQueue(items,selected,null);
    }

    void playQueue(List<MediaItem> items,int selected,QueueSource continuation) {
        if (selected<0 || selected>=items.size() || !items.get(selected).isAudio()) return;
        if(!prefs.hasAccount())return;
        stopTrack();
        client=new EmbyClient(prefs);
        queue.clear();index=-1;
        for(int i=0;i<items.size();i++)if(items.get(i).isAudio()) {
            if(i==selected)index=queue.size();
            queue.add(copy(items.get(i)));
        }
        source=continuation;sourceTrimmed=false;++sourceGeneration;pageLoading=false;pageFailures=0;pageRetryAt=0;
        shuffleHistory.clear();shuffled.clear();priority.clear();queueRevision++;
        if(allRandom())shuffle=false;
        savedPosition=0;cover=null;
        fallback=false;
        retryCount=0;waitingPage=false;
        persist();startTrack(0,true);
    }

    public void select(int selected) {
        if (selected<0 || selected>=queue.size()) return;
        MediaItem previous=current();
        stopTrack();waitingPage=false;pageRetryAt=0;
        index=selected;priority.remove(current());shuffled.add(current());queueRevision++;
        if(current()!=previous)cover=null;
        savedPosition=0;fallback=false;retryCount=0;persist();startTrack(0,true);
    }

    public void toggle() { if (desiredPlay || isPlaying()) pause(); else play(); }

    public void play() {
        if (current()==null || !prefs.hasAccount()) return;
        transientResume=false;
        if (!acquireFocus()) return;
        retryCount=0;
        if (prepared && player!=null && error.isEmpty()) {
            desiredPlay=true;player.start();cache.played(client,current());holdWifi(local==null);stalledSince=SystemClock.elapsedRealtime();report("/Progress");changed();
        } else if (player!=null && buffering && error.isEmpty()) {
            desiredPlay=true;holdWifi(local==null);changed();
        } else if(waitingPage) {
            desiredPlay=true;pageRetryAt=0;loadMore();
        } else { fallback=false;waitingNetwork=false;startTrack(savedPosition,true); }
    }

    public void pause() {
        transientResume=false;
        desiredPlay=false;waitingNetwork=false;waitingPage=false;buffering=!prepared&&player!=null;
        error="";
        if (isPlaying()) player.pause();
        savedPosition=position(); holdWifi(false); persist(); report("/Progress"); changed();
    }

    public void next(boolean completed) {
        if(queue.isEmpty()) return;
        if(completed&&sleepAfter) {
            cancelSleepInternal();stopTrack();savedPosition=0;stopPlayback(false);return;
        }
        if (completed && repeat==2) { select(index); return; }
        if(!priority.isEmpty()) {
            int target=queue.indexOf(priority.remove(0));
            if(target>=0) { if(shuffle)remember();select(target);return; }
        }
        int selected=index+1;
        if(shuffle && queue.size()>1) {
            shuffled.add(current());selected=pickUnplayed();
            if(selected<0&&!hasMore()&&repeat!=0) {shuffled.clear();shuffled.add(current());selected=pickUnplayed();}
            if(selected>=0)remember();
        }
        if(selected<0||selected>=queue.size()) {
            if(source!=null&&!source.more()&&repeat>0&&(source.random()||sourceTrimmed)) {
                source=source.random()?QueueSource.random(source.total,random):new QueueSource(source.spec,0,source.total);
                sourceTrimmed=true;++sourceGeneration;pageLoading=false;pageFailures=0;
            }
            if(hasMore()) {
                stopTrack();waitingPage=true;desiredPlay=true;buffering=true;
                pageRetryAt=0;error="正在补充播放队列…";persist();changed();loadMore();return;
            }
            if (repeat==0) { stopPlayback(false); return; }
            selected=0;
        }
        select(selected);
    }

    private int pickUnplayed() {
        int chosen=-1,count=0;
        for(int i=0;i<queue.size();i++)if(!shuffled.contains(queue.get(i))&&random.nextInt(++count)==0)chosen=i;
        return chosen;
    }

    private void remember() {
        shuffleHistory.add(current());
        if(shuffleHistory.size()>500)shuffleHistory.remove(0);
    }

    public void previous() {
        if(current()==null) return;
        if(position()>3000&&!waitingNetwork&&!waitingPage) { seek(0); return; }
        int selected;
        if(shuffle && !shuffleHistory.isEmpty()) selected=queue.indexOf(shuffleHistory.remove(shuffleHistory.size()-1));
        else selected=-1;
        if(selected<0)selected=index>0?index-1:repeat>0?queue.size()-1:0;
        select(selected);
    }

    private static MediaItem copy(MediaItem item) {
        try {return new MediaItem(item.toJson());}catch(Exception ignored){return item;}
    }

    public void enqueue(MediaItem item,boolean next) {
        if(!item.isAudio()||!prefs.hasAccount())return;
        MediaItem added=copy(item);
        if(index<0){queue.add(added);index=0;}
        else if(next){queue.add(index+1,added);priority.add(0,added);}
        else queue.add(added);
        queueRevision++;persist();updateMetadata();changed();
    }

    public void move(int from,int to) {
        if(from<0||from>=queue.size()||to<0||to>=queue.size()||from==to)return;
        MediaItem playing=current(),moved=queue.remove(from);queue.add(to,moved);
        // A manual move gives the row its new place in the queue.
        priority.remove(moved);index=queue.indexOf(playing);queueRevision++;persist();changed();
    }

    public void remove(int at) {
        if(at<0||at>=queue.size())return;
        MediaItem removed=queue.get(at),playing=current();
        boolean advance=removed==playing,resume=desiredPlay;
        if(advance)stopTrack();
        queue.remove(at);priority.remove(removed);shuffled.remove(removed);shuffleHistory.removeAll(Collections.singleton(removed));
        if(advance) {
            waitingPage=false;
            index=queue.isEmpty()?-1:Math.min(at,queue.size()-1);
            savedPosition=0;cover=null;retryCount=0;fallback=false;error="";
        } else index=queue.indexOf(playing);
        queueRevision++;persist();
        if(advance&&resume&&current()!=null)startTrack(0,true);
        else if(advance&&resume&&hasMore()){waitingPage=true;desiredPlay=true;buffering=true;loadMore();changed();}
        else {if(current()==null){stopForeground(true);audio.abandonAudioFocus(this);}updateMetadata();changed();}
    }

    private boolean shouldPrefetch() {
        return hasMore()&&(waitingPage||(shuffle?queue.size()-shuffled.size():queue.size()-index-1)<=3);
    }

    private void loadMore() {
        if(!hasMore()||pageLoading||SystemClock.elapsedRealtime()<pageRetryAt)return;
        if(!client.authenticated()) {
            if(waitingPage){waitingPage=false;buffering=false;desiredPlay=false;error="请重新登录以加载更多歌曲";changed();}
            return;
        }
        if(!online()) {
            pageRetryAt=SystemClock.elapsedRealtime()+4000;
            if(waitingPage&&!error.equals("等待网络恢复…")){error="等待网络恢复…";changed();}
            return;
        }
        if(waitingPage&&!buffering){buffering=true;changed();}
        QueueSource selected=source;int ticket=sourceGeneration,start=selected.offset();
        EmbyClient snapshot=client;
        pageLoading=true;
        pages.execute(()->{
            try {
                EmbyClient.Page page=snapshot.browse(selected.spec,start,EmbyClient.PAGE_SIZE);
                if(selected.random())Collections.shuffle(page.items);
                handler.post(()->{
                    if(ticket!=sourceGeneration||!snapshot.currentSession())return;
                    pageLoading=false;pageFailures=0;pageRetryAt=0;selected.advance(page);
                    int previousSize=queue.size();
                    for(MediaItem item:page.items)if(item.isAudio())queue.add(item);
                    queueRevision++;persist();
                    if(waitingPage&&desiredPlay) {
                        if(queue.size()>previousSize) {
                            waitingPage=false;
                            if(shuffle) {int next=pickUnplayed();if(next>=0){remember();select(next);}else select(previousSize);}
                            else select(previousSize);
                        } else if(!hasMore()) {
                            waitingPage=false;
                            if(current()==null)stopPlayback(false);else next(false);
                        }
                        else loadMore();
                    }
                    if(!waitingPage)trimHistory();
                    changed();
                });
            } catch(Exception e) {
                handler.post(()->{
                    if(ticket!=sourceGeneration||!snapshot.currentSession())return;
                    pageLoading=false;pageRetryAt=SystemClock.elapsedRealtime()+backoff(++pageFailures);
                    if(e instanceof EmbyClient.ApiException&&!RetryPolicy.transientFailure(e)) {
                        source=null;waitingPage=false;buffering=false;desiredPlay=isPlaying();persist();
                    }
                    if(!desiredPlay||waitingPage){error=EmbyClient.message(e)+(waitingPage?" · 将自动重试":"");changed();}
                });
            }
        });
    }

    private void trimHistory() {
        // Leave past entries available for Back without retaining every track in a large library.
        if(source==null||shuffle||queue.size()<600||index<100)return;
        int count=index-50;
        List<MediaItem> past=queue.subList(0,count);
        if(!Collections.disjoint(past,priority))return;
        shuffled.removeAll(past);shuffleHistory.removeAll(past);past.clear();index-=count;sourceTrimmed=true;queueRevision++;persist();
    }

    public void seek(long value) {
        if (current()==null) return;
        value=Math.max(0,Math.min(value,Math.max(0,duration()-500)));
        boolean resume=desiredPlay;
        waitingPage=false;
        if(player==null) {
            savedPosition=value;retryCount=0;if(waitingNetwork)retryAt=0;persist();changed();return;
        }
        if(prepared&&(local!=null||streamQuality.original())) {
            seekPlayer(player,value);persist();changed();
        }
        else {
            // Emby transcoded streams may lack byte-range seeking. Request a new
            // stream at the desired tick offset and keep the public timeline absolute.
            stopTrack();startTrack(value,resume);
        }
    }

    public void cycleRepeat() { repeat=(repeat+1)%3; persist(); changed(); }
    public void toggleShuffle() { shuffle=!shuffle;shuffleHistory.clear();shuffled.clear();persist();changed(); }
    public void refreshMetadata() { persist();updateMetadata();changed(); }

    void qualityChanged(){
        if(current()==null||player==null&&!waitingNetwork)return;
        long position=position();boolean resume=desiredPlay;
        stopTrack();fallback=false;retryCount=0;savedPosition=position;error="";
        if(resume)startTrack(position,true);else{persist();changed();}
    }

    void sleep(long milliseconds,boolean afterTrack,boolean fade) {
        cancelSleepInternal();sleepAfter=afterTrack;fadeSleep=fade;
        if(milliseconds>0)sleepAt=SystemClock.elapsedRealtime()+milliseconds;
        changed();
    }

    private void cancelSleepInternal() { sleepAt=0;sleepAfter=false;fadeSleep=false;applyVolume(1f); }

    private boolean acquireFocus() {
        int result=audio.requestAudioFocus(this,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN);
        if(result!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { error="暂时无法获得音频焦点，请稍后重试"; desiredPlay=false; changed(); return false; }
        registerRemote();
        return true;
    }

    private void registerRemote() {
        if(!registered) { audio.registerMediaButtonEventReceiver(mediaReceiver); audio.registerRemoteControlClient(remote); registered=true; }
    }

    private boolean online() {
        ConnectivityManager connectivity=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo active=connectivity==null?null:connectivity.getActiveNetworkInfo();
        return active!=null&&active.isConnected();
    }

    private boolean localAvailable() {
        return current()!=null&&cache.local(client,current(),requestedQuality(),true)!=null;
    }

    private AudioQuality requestedQuality(){return fallback?AudioQuality.BALANCED:prefs.audioQuality();}

    private void startTrack(long startPosition,boolean resume) {
        if(current()==null||!prefs.hasAccount())return;
        if(resume&&!acquireFocus())return;
        savedPosition=startPosition;
        AudioQuality requested=requestedQuality();boolean online=online();
        local=cache.localForPlayback(client,current(),requested,!prefs.hasChosenAudioQuality()||!client.authenticated()||!online||retryCount>0);
        if(local==null&&!client.authenticated()) {
            fail("请重新登录 Emby，或播放已下载歌曲");return;
        }
        if(local==null&&!online) {
            if(resume)scheduleRetry(startPosition);else changed();
            return;
        }
        final int ticket=++generation;
        session=UUID.randomUUID().toString();
        streamQuality=local==null?requested:local.quality;
        final boolean original=streamQuality.original();
        offset=original||local!=null?0:startPosition;
        desiredPlay=resume;prepared=false;buffering=true;error="";reported=false;upstream=0;seekTarget=-1;
        lastMotion=startPosition;stalledSince=SystemClock.elapsedRealtime();waitingNetwork=false;
        updateMetadata();
        holdWifi(resume&&local==null);
        changed();
        try {
            String dataSource;
            if(local==null) {
                String path=client.streamPath(current(),streamQuality,session);
                if(!original&&startPosition>0)path+="&StartTimeTicks="+(startPosition*10000L);
                AudioCache capture=(original||startPosition==0)&&prefs.bool("audio_cache",true)?cache:null;
                proxy=new StreamProxy(client,path,capture,current(),streamQuality,status->handler.post(()->{
                    if(ticket!=generation)return;
                    upstream=status;
                    if(status==401||status==403)fail(new EmbyClient.ApiException(status).getMessage());
                }));
                dataSource=proxy.url();
            } else dataSource=local.file.getAbsolutePath();
            player=new MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setWakeMode(this,PowerManager.PARTIAL_WAKE_LOCK);
            player.setOnPreparedListener(mp -> {
                if(ticket!=generation || player!=mp) return;
                prepared=true; buffering=false;
                stalledSince=SystemClock.elapsedRealtime();lastMotion=savedPosition;
                if((local!=null||original)&&savedPosition>0)seekPlayer(mp,savedPosition);
                audioEffectsChanged(AudioEffects.Profile.read(prefs));
                applyVolume(volume);
                if(desiredPlay){mp.start();cache.played(client,current());}else holdWifi(false);
                if(local==null){report("");reported=true;}
                persist();changed();
            });
            player.setOnSeekCompleteListener(mp->{
                if(ticket!=generation)return;
                seekTarget=-1;savedPosition=position();lastMotion=savedPosition;stalledSince=SystemClock.elapsedRealtime();
                report("/Progress");persist();changed();
            });
            player.setOnCompletionListener(mp -> {
                if(ticket!=generation||!desiredPlay)return;
                long last=Math.max(savedPosition,position());
                if(local==null&&duration()>0&&last+5000<duration())streamFailed(last,true);
                else next(true);
            });
            player.setOnErrorListener((mp,what,extra) -> {
                if(ticket==generation)streamFailed(Math.max(savedPosition,position()),false);
                return true;
            });
            player.setOnInfoListener((mp,what,extra) -> {
                if(ticket!=generation) return false;
                if(what==MediaPlayer.MEDIA_INFO_BUFFERING_START) { buffering=true; changed(); }
                if(what==MediaPlayer.MEDIA_INFO_BUFFERING_END) { buffering=false; changed(); }
                return false;
            });
            if(local==null)player.setDataSource(dataSource);
            else try(FileInputStream input=new FileInputStream(local.file)){player.setDataSource(input.getFD());}
            player.prepareAsync();
            final MediaItem item=current();
            final EmbyClient imageClient=client;
            if(client.authenticated()&&online()&&cover==null)artwork.execute(() -> {
                Bitmap bitmap=null;
                try { bitmap=imageClient.image(item); } catch(Exception ignored) { }
                final Bitmap result=bitmap;
                handler.post(() -> { if(ticket==generation) { cover=result; updateMetadata(); changed(); } });
            });
            handler.postDelayed(() -> { if(ticket==generation&&!prepared&&buffering)streamFailed(savedPosition,true); },26000);
        } catch(Exception e) { streamFailed(startPosition,false); }
    }

    private void seekPlayer(MediaPlayer mp,long value) {
        seekTarget=value;savedPosition=value;lastMotion=value;stalledSince=SystemClock.elapsedRealtime();
        mp.seekTo((int)Math.min(Integer.MAX_VALUE,value));
        int ticket=generation;
        handler.postDelayed(()->{if(ticket==generation&&seekTarget==value){seekTarget=-1;lastMotion=position();stalledSince=SystemClock.elapsedRealtime();}},4000);
    }

    private static long backoff(int attempts) { return Math.min(60000,2000L<<(Math.min(5,Math.max(0,attempts-1)))); }

    private void scheduleRetry(long last) {
        stopTrack(false);savedPosition=last;waitingNetwork=true;desiredPlay=true;buffering=true;
        retryAt=SystemClock.elapsedRealtime()+backoff(++retryCount);
        error=online()?"连接中断，正在等待重试…":"等待网络恢复…";
        persist();changed();
    }

    private void streamFailed(long last,boolean transientError) {
        boolean resume=desiredPlay;
        if(upstream==401||upstream==403) { fail(new EmbyClient.ApiException(upstream).getMessage());return; }
        if(local!=null) {
            AudioCache.Local bad=local;
            stopTrack(false);savedPosition=last;
            cache.invalidate(bad);
            if(bad.quality.original())fallback=true;
            if(client.authenticated()&&online()||localAvailable()){startTrack(last,resume);return;}
            fail("离线音频无法播放，请联网后重新下载");return;
        }
        if(!client.currentSession()){fail("Emby 登录已失效，请重新登录");return;}
        if(resume&&(!online()||transientError||upstream==0||upstream==408||upstream==429||upstream>=500)) {scheduleRetry(last);return;}
        if(streamQuality.original()&&!fallback) {stopTrack(false);fallback=true;startTrack(last,resume);return;}
        fail("播放失败，请检查网络、转码权限或切换原始音质后重试");
    }

    private void fail(String message) {
        stopTrack(false);waitingPage=false;desiredPlay=false;buffering=false;error=message;
        audio.abandonAudioFocus(this);persist();changed();
    }

    private void stopTrack() { stopTrack(true); }
    private void stopTrack(boolean notifyServer) {
        preloader.playback(client,queue,index,queueRevision,false,true);
        if(reported&&notifyServer)report("/Stopped");
        reported=false;
        savedPosition=position();
        ++generation;
        effects.release();
        if(player!=null) { player.release(); player=null; }
        if(proxy!=null) { proxy.close(); proxy=null; }
        local=null;cache.protect(null);seekTarget=-1;prepared=false;buffering=false;desiredPlay=false;waitingNetwork=false;holdWifi(false);
    }

    public void stopPlayback(boolean clearQueue) {
        transientResume=false;waitingPage=false;cancelSleepInternal();
        stopTrack(); error=""; audio.abandonAudioFocus(this);
        if(clearQueue) {
            queue.clear();index=-1;savedPosition=0;cover=null;shuffleHistory.clear();shuffled.clear();priority.clear();
            source=null;++sourceGeneration;pageLoading=false;queueRevision++;updateMetadata();
        }
        persist(); changed(); stopForeground(true);
        if(registered) { audio.unregisterMediaButtonEventReceiver(mediaReceiver); audio.unregisterRemoteControlClient(remote); registered=false; }
        stopSelf();
    }

    private void holdWifi(boolean hold) {
        if(wifiLock==null) return;
        if(hold && !wifiLock.isHeld()) wifiLock.acquire();
        if(!hold && wifiLock.isHeld()) wifiLock.release();
    }

    private void applyVolume(float value) {
        volume=value;
        if(player!=null&&prepared) {
            float level=volume*(ducked?0.2f:1f);
            player.setVolume(level,level);
        }
    }

    @Override public void onAudioFocusChange(int change) {
        if(change==AudioManager.AUDIOFOCUS_LOSS) { pause(); audio.abandonAudioFocus(this); }
        else if(change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            boolean resume=desiredPlay; pause(); transientResume=resume;
        } else if(change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            ducked=true;applyVolume(volume);
        } else if(change==AudioManager.AUDIOFOCUS_GAIN) {
            ducked=false;applyVolume(volume);
            if(transientResume) { transientResume=false; play(); }
        }
    }

    private PendingIntent command(String action,int code) {
        return PendingIntent.getService(this,code,new Intent(this,PlaybackService.class).setAction(action),PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void updateNotification() {
        MediaItem item=current();
        if(item==null) return;
        systemArtwork.publish(cover,icon->notification(item,icon),value->startForeground(NOTIFICATION_ID,value));
    }

    private Notification notification(MediaItem item,Bitmap icon) {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder=new Notification.Builder(this).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(item.name).setContentText(error.isEmpty()?item.artist + (buffering?" · 缓冲中":""):error)
                .setContentIntent(open).setOngoing(desiredPlay).setAutoCancel(false)
                .addAction(android.R.drawable.ic_media_previous,"上一首",command(PREVIOUS,1))
                .addAction(desiredPlay?android.R.drawable.ic_media_pause:android.R.drawable.ic_media_play,desiredPlay?"暂停":"播放",command(TOGGLE,2))
                .addAction(android.R.drawable.ic_media_next,"下一首",command(NEXT,3));
        if(icon!=null) builder.setLargeIcon(icon);
        return builder.build();
    }

    private void updateMetadata() {
        MediaItem item=current();
        RemoteControlClient.MetadataEditor editor=remote.editMetadata(true);
        if(item!=null) {
            editor.putString(MediaMetadataRetriever.METADATA_KEY_TITLE,item.name)
                    .putString(MediaMetadataRetriever.METADATA_KEY_ARTIST,item.artist)
                    .putString(MediaMetadataRetriever.METADATA_KEY_ALBUM,item.album)
                    .putLong(MediaMetadataRetriever.METADATA_KEY_DURATION,item.durationMs);
            if(cover!=null) editor.putBitmap(RemoteControlClient.MetadataEditor.BITMAP_KEY_ARTWORK,SystemArtwork.copy(cover,256));
        }
        editor.apply();
    }

    private void changed() {
        preloader.playback(client,queue,index,queueRevision,desiredPlay&&prepared,desiredPlay&&(!prepared||buffering||waitingNetwork||waitingPage));
        int state=buffering?RemoteControlClient.PLAYSTATE_BUFFERING:isPlaying()?RemoteControlClient.PLAYSTATE_PLAYING:
                current()==null?RemoteControlClient.PLAYSTATE_STOPPED:RemoteControlClient.PLAYSTATE_PAUSED;
        remote.setPlaybackState(state);
        if(current()!=null && (desiredPlay || player!=null || !error.isEmpty()&&client.authenticated())) updateNotification();
        sendBroadcast(new Intent(CHANGED).setPackage(getPackageName()));
        for(String prefix:new String[]{"com.android.music",getPackageName()}) {
            for(String event:new String[]{".metachanged",".playstatechanged"}) {
                Intent intent=new Intent(prefix+event);
                MediaItem item=current();
                intent.putExtra("id",item==null?-1L:((long)item.id.hashCode() & 0x7fffffffL))
                        .putExtra("media_id",item==null?"":item.id).putExtra("track",item==null?"":item.name)
                        .putExtra("artist",item==null?"":item.artist).putExtra("album",item==null?"":item.album)
                        .putExtra("playing",isPlaying()).putExtra("position",position()).putExtra("duration",duration())
                        .putExtra("package",getPackageName());
                sendBroadcast(intent);
            }
        }
    }

    private void report(String event) {
        if(current()==null||local!=null||!client.authenticated()||!online()||(!reported&&!event.isEmpty()))return;
        final MediaItem item=current();
        final String playSession=session;
        final long position=position();
        final boolean paused=!isPlaying(), source=streamQuality.original();
        final int mode=repeat;
        final EmbyClient snapshot=client;
        if(reports.isShutdown()) return;
        reports.execute(() -> { try { if(snapshot.currentSession())snapshot.report(event,item,playSession,position,paused,source,mode); } catch(Exception ignored) { } });
    }

    private void persist() {
        if(!prefs.hasAccount()||!client.currentSession())return;
        JSONArray items=new JSONArray(),upNext=new JSONArray();
        try {
            for(MediaItem item:queue)items.put(item.toJson());
            for(MediaItem item:priority)upNext.put(queue.indexOf(item));
            prefs.store.edit().putString("queue",items.toString()).putInt("queue_index",index).putString("queue_priority",upNext.toString())
                    .putLong("position",position()).putBoolean("shuffle",shuffle).putInt("repeat",repeat)
                    .putString("queue_source",source==null?"":source.toJson().toString()).putBoolean("queue_trimmed",sourceTrimmed).apply();
        } catch(Exception ignored) { }
    }

    private void restore() {
        shuffle=prefs.bool("shuffle",false); repeat=Math.max(0,Math.min(2,prefs.store.getInt("repeat",1)));
        if(!prefs.hasAccount()) return;
        try {
            JSONArray items=new JSONArray(prefs.store.getString("queue","[]"));
            for(int i=0;i<items.length();i++) {
                MediaItem item=new MediaItem(items.getJSONObject(i));
                if(item.isAudio()) queue.add(item);
            }
            index=prefs.store.getInt("queue_index",-1);
            if(index<0 || index>=queue.size()) index=queue.isEmpty()?-1:0;
            savedPosition=Math.max(0,prefs.store.getLong("position",0));
            JSONArray upNext=new JSONArray(prefs.store.getString("queue_priority","[]"));
            for(int i=0;i<upNext.length();i++){int row=upNext.optInt(i,-1);if(row>=0&&row<queue.size())priority.add(queue.get(row));}
            String savedSource=prefs.store.getString("queue_source","");
            if(!savedSource.isEmpty())source=new QueueSource(new JSONObject(savedSource));
            sourceTrimmed=prefs.bool("queue_trimmed",false);
        } catch(Exception ignored) { queue.clear(); index=-1; }
    }

    @Override public void onDestroy() {
        persist(); stopTrack();
        preloader.playback(client,queue,index,queueRevision,false,false);
        handler.removeCallbacksAndMessages(null);
        unregisterReceiver(noisy);
        unregisterReceiver(network);unregisterReceiver(sessions);unregisterReceiver(cached);
        if(registered) { audio.unregisterMediaButtonEventReceiver(mediaReceiver); audio.unregisterRemoteControlClient(remote); }
        audio.abandonAudioFocus(this);
        reports.shutdown();artwork.shutdownNow();pages.shutdownNow();stopForeground(true);
        super.onDestroy();
    }
}
