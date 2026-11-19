package com.nomusic.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import org.json.JSONObject;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Exercise state transitions against a deterministic HTTP server on Android 4.x. */
@SuppressWarnings("deprecation")
public final class FeatureIntegrationTest extends InstrumentationTestCase {
    private static final String HOST="http://10.0.2.2:18765";
    private Context context;
    private AppPrefs prefs;
    private Map<String,?> saved;
    private PlaybackService service;
    private ServiceConnection connection;
    private boolean bound;
    private interface Check {boolean ok();}
    private void main(Runnable call){getInstrumentation().runOnMainSync(call);}
    private boolean waitFor(Check check,long timeout) {
        long end=SystemClock.elapsedRealtime()+timeout;
        while(SystemClock.elapsedRealtime()<end) {
            AtomicReference<Boolean> result=new AtomicReference<>(false);main(()->result.set(check.ok()));
            if(result.get())return true;SystemClock.sleep(100);
        }
        return false;
    }
    private EmbyClient client(){return new EmbyClient(prefs);}
    private int streamRequests(String id)throws Exception{return client().json("GET","/fixture/stream-count?id="+id,null).getInt("Count");}

    @Override protected void setUp() throws Exception {
        super.setUp();context=getInstrumentation().getTargetContext();TlsSocketFactory.initialize(context);
        prefs=new AppPrefs(context);saved=new HashMap<>(prefs.store.getAll());
        main(()->{
            prefs.store.edit().clear().commit();
            prefs.saveSession(HOST,"fixture-user","fixture","fixture-token");
            prefs.setBool("audio_cache",false);prefs.setBool("original",true);
            context.startService(new Intent(context,PlaybackService.class));
        });
        CountDownLatch ready=new CountDownLatch(1);
        connection=new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name,IBinder binder){service=((PlaybackService.LocalBinder)binder).service();ready.countDown();}
            @Override public void onServiceDisconnected(ComponentName name){}
        };
        bound=context.bindService(new Intent(context,PlaybackService.class),connection,Context.BIND_AUTO_CREATE);
        assertTrue(ready.await(8,TimeUnit.SECONDS));
        main(()->service.stopPlayback(true));
    }

    @Override protected void tearDown() throws Exception {
        if(service!=null)main(()->service.stopPlayback(true));
        if(bound)context.unbindService(connection);
        context.stopService(new Intent(context,PlaybackService.class));
        getInstrumentation().waitForIdleSync();SystemClock.sleep(250);
        SharedPreferences.Editor restore=prefs.store.edit().clear();
        for(Map.Entry<String,?> entry:saved.entrySet()) {
            Object value=entry.getValue();String key=entry.getKey();
            if(value instanceof String)restore.putString(key,(String)value);
            else if(value instanceof Integer)restore.putInt(key,(Integer)value);
            else if(value instanceof Long)restore.putLong(key,(Long)value);
            else if(value instanceof Boolean)restore.putBoolean(key,(Boolean)value);
            else if(value instanceof Float)restore.putFloat(key,(Float)value);
        }
        restore.commit();super.tearDown();
    }

    public void testQueueEditsKeepCurrentIdentityAndPosition() throws Exception {
        List<MediaItem> tracks=new ArrayList<>(client().browse("songs",null,null,"",0).items.subList(0,4));
        main(()->service.playQueue(tracks,1));
        assertTrue(waitFor(service::isPlaying,12000));
        main(()->{service.pause();service.seek(14000);});
        assertTrue(waitFor(()->service.position()>=13800,5000));
        main(()->{
            service.enqueue(tracks.get(3),true);
            assertEquals("track-3",service.queue().get(service.index()+1).id);
            service.move(service.index(),0);
            assertEquals("track-1",service.current().id);
            assertEquals(0,service.index());
            assertTrue(service.position()>=13800);assertFalse(service.wantsPlay());
            service.remove(2);
            assertEquals("track-1",service.current().id);
            assertTrue(service.position()>=13800);
        });
        main(()->service.enqueue(tracks.get(2),true));
        main(()->service.next(false));
        assertTrue(waitFor(()->service.isPlaying()&&"track-2".equals(service.current().id),10000));
        main(()->{service.pause();service.remove(service.index());});
        assertFalse(service.wantsPlay());assertFalse(service.isPlaying());
        assertEquals(service.queueSize(),new org.json.JSONArray(prefs.store.getString("queue","[]")).length());
    }

    public void testChangingStreamQualityKeepsPositionAndOriginalFallsBack()throws Exception{
        EmbyClient api=client();MediaItem track=qualityTrack();
        assertEquals(AudioQuality.ORIGINAL,prefs.audioQuality()); // Preference used before 0.6.
        prefs.setAudioQuality(AudioQuality.STANDARD);
        main(()->service.playQueue(java.util.Collections.singletonList(track),0));
        assertTrue(waitFor(()->service.isPlaying()&&service.activeQuality()==AudioQuality.STANDARD,12000));
        JSONObject first=api.json("GET","/fixture/stream-profile?id="+track.id,null);
        assertFalse(first.getBoolean("Original"));assertEquals(128000,first.getInt("Bitrate"));
        main(()->{service.seek(12000);prefs.setAudioQuality(AudioQuality.MAX);service.qualityChanged();});
        assertTrue(waitFor(()->service.isPlaying()&&service.activeQuality()==AudioQuality.MAX,12000));
        JSONObject high=api.json("GET","/fixture/stream-profile?id="+track.id,null);
        assertEquals(320000,high.getInt("Bitrate"));assertEquals(320000,high.getInt("MaxBitrate"));
        assertTrue(high.getLong("StartTicks")>=119000000L);assertTrue(service.position()>=11900);
        api.json("GET","/fixture/stream-behavior?id="+track.id+"&source_status=415",null);
        try{
            main(()->{prefs.setAudioQuality(AudioQuality.ORIGINAL);service.qualityChanged();});
            assertTrue("Original fallback failed: "+service.error(),waitFor(()->service.isPlaying()&&service.activeQuality()==AudioQuality.BALANCED,12000));
            assertTrue(service.position()>=11900);
            assertEquals(192000,api.json("GET","/fixture/stream-profile?id="+track.id,null).getInt("Bitrate"));
        }finally{api.json("GET","/fixture/stream-behavior?id="+track.id,null);}
    }

    public void testCachedBitratesStaySeparateAndRemainPlayableOffline()throws Exception{
        EmbyClient api=client();MediaItem track=qualityTrack();AudioCache cache=AudioCache.get(context);
        prefs.setBool("audio_cache",true);
        assertTrue(cache.prefetch(api,track,AudioQuality.BALANCED,false,()->{}));
        AudioCache.Local legacy=cache.local(api,track,AudioQuality.BALANCED,false);assertNotNull(legacy);
        assertTrue(legacy.file.getName().endsWith(StorageFiles.key(track.id)+".mp3"));
        assertNull(cache.local(api,track,AudioQuality.MAX,false));
        prefs.setBool("audio_cache",false);prefs.setAudioQuality(AudioQuality.MAX);
        CountDownLatch done=new CountDownLatch(1);AtomicReference<String> problem=new AtomicReference<>();
        cache.download(api,track,AudioQuality.MAX,error->{problem.set(error);done.countDown();});
        assertTrue(done.await(15,TimeUnit.SECONDS));assertEquals("",problem.get());
        AudioCache.Local high=cache.local(api,track,AudioQuality.MAX,false);assertNotNull(high);assertTrue(high.file.getName().endsWith(".320.mp3"));
        assertEquals(legacy.file,cache.local(api,track,AudioQuality.BALANCED,false).file);
        assertEquals(320000,api.json("GET","/fixture/stream-profile?id="+track.id,null).getInt("Bitrate"));
        main(()->{prefs.setAudioQuality(AudioQuality.SAVER);prefs.expire(api.server,api.userId,prefs.token(),prefs.revision());});
        main(()->service.playQueue(java.util.Collections.singletonList(track),0));
        assertTrue(waitFor(()->service.isPlaying()&&service.isOfflinePlayback(),12000));
        assertEquals(AudioQuality.MAX,service.activeQuality());
        main(()->service.stopPlayback(true));cache.invalidate(legacy);cache.remove(client(),track);
    }

    private MediaItem qualityTrack()throws Exception{
        return new MediaItem(new JSONObject().put("Id","quality-"+java.util.UUID.randomUUID()).put("Name","Quality probe").put("RunTimeTicks",900000000));
    }

    public void testContinuationAndRandomCoverAllWindows() throws Exception {
        List<MediaItem> first=client().browse("songs",null,null,"",0).items;
        main(()->{
            service.playQueue(first,99,new QueueSource(BrowseSpec.songs(),100,105));
            while(service.repeat()!=0)service.cycleRepeat();
        });
        assertTrue("Tail page was not loaded",waitFor(()->service.queueSize()==105,12000));
        assertFalse(service.hasMore());
        main(()->service.next(false));assertEquals("track-100",service.current().id);
        assertTrue(waitFor(service::isPlaying,10000));
        main(()->service.select(104));
        main(()->service.next(true));assertFalse(service.wantsPlay());
        for(int total:new int[]{1,99,100,101,105,1005,100003}) {
            QueueSource random=QueueSource.random(total,new Random(12));
            HashSet<Integer> offsets=new HashSet<>();int expected=(total+99)/100;
            while(random.more()) {
                int at=random.offset();assertTrue(at>=0&&at<total);assertTrue("Random repeated a window",offsets.add(at));
                int received=Math.min(100,total-at);
                random.advance(new EmbyClient.Page(new JSONObject().put("Items",new org.json.JSONArray()).put("TotalRecordCount",total)));
                assertTrue(received>0);
                random=new QueueSource(random.toJson());
            }
            assertEquals(expected,offsets.size());
        }
    }

    public void testCacheRejectsRangesAndOfflinePlaysCompletedDownload() throws Exception {
        EmbyClient snapshot=client();AudioCache cache=AudioCache.get(context);
        MediaItem item=snapshot.browse("songs",null,null,"Track 103",0).items.get(0);
        cache.remove(snapshot,item);cache.clearAutomatic(snapshot);SystemClock.sleep(300);
        HttpURLConnection range=snapshot.open(snapshot.streamPath(item,AudioQuality.ORIGINAL,"partial"));
        range.setRequestProperty("Range","bytes=0-43");
        try {
            assertEquals(206,snapshot.response(range));
            assertEquals(-2,AudioCache.completeLength(range,206));
            assertNull(cache.capture(snapshot,item,AudioQuality.ORIGINAL,range,206));
        } finally {range.disconnect();}
        CountDownLatch done=new CountDownLatch(1);AtomicReference<String> error=new AtomicReference<>();
        cache.download(snapshot,item,AudioQuality.ORIGINAL,value->{error.set(value);done.countDown();});
        assertTrue(done.await(15,TimeUnit.SECONDS));assertEquals("",error.get());assertTrue(cache.saved(snapshot,item));
        assertTrue(cache.local(snapshot,item,AudioQuality.BALANCED,true).file.length()>1000);
        cache.clearAutomatic(snapshot);SystemClock.sleep(250);assertTrue(cache.saved(snapshot,item));
        main(()->prefs.expire(snapshot.server,snapshot.userId,prefs.token(),prefs.revision()));
        assertTrue(waitFor(()->!prefs.loggedIn(),3000));
        main(()->service.playQueue(java.util.Collections.singletonList(item),0));
        assertTrue("Saved audio failed with expired token: "+service.error(),waitFor(service::isPlaying,10000));
        assertTrue(service.isOfflinePlayback());
        main(()->service.stopPlayback(true));cache.remove(client(),item);
    }

    public void testAutomaticCaptureAndInterruptedCaptureIsDiscarded() throws Exception {
        prefs.setBool("audio_cache",true);
        EmbyClient snapshot=client();AudioCache cache=AudioCache.get(context);
        MediaItem item=snapshot.browse("songs",null,null,"Track 102",0).items.get(0);
        cache.remove(snapshot,item);cache.clearAutomatic(snapshot);SystemClock.sleep(300);
        StreamProxy proxy=new StreamProxy(snapshot,snapshot.streamPath(item,AudioQuality.ORIGINAL,"capture"),cache,item,AudioQuality.ORIGINAL,null);
        try {
            HttpURLConnection connection=(HttpURLConnection)new URL(proxy.url()).openConnection();
            assertEquals(200,connection.getResponseCode());
            try(InputStream input=connection.getInputStream()) {byte[] bytes=new byte[16384];while(input.read(bytes)!=-1){}}
            connection.disconnect();
            assertTrue(waitFor(()->cache.local(snapshot,item,AudioQuality.ORIGINAL,false)!=null,5000));
        } finally {proxy.close();}
        cache.clearAutomatic(snapshot);SystemClock.sleep(300);
        HttpURLConnection upstream=snapshot.open(snapshot.streamPath(item,AudioQuality.ORIGINAL,"incomplete"));
        try {
            assertEquals(200,snapshot.response(upstream));
            AudioCache.Capture capture=cache.capture(snapshot,item,AudioQuality.ORIGINAL,upstream,200);
            assertNotNull(capture);capture.write(new byte[44],44);capture.close(false);
            assertNull(cache.local(snapshot,item,AudioQuality.ORIGINAL,false));
        } finally {upstream.disconnect();}
    }

    public void testDownloadFailureRemainsVisibleAndRetries()throws Exception {
        EmbyClient snapshot=client();AudioCache cache=AudioCache.get(context);MediaItem item=snapshot.browse("songs",null,null,"Track 101",0).items.get(0);
        cache.remove(snapshot,item);cache.clearAutomatic(snapshot);SystemClock.sleep(250);
        snapshot.json("GET","/fixture/stream-failure?id=track-101&count=3&status=503",null);
        CountDownLatch failed=new CountDownLatch(1);AtomicReference<String> problem=new AtomicReference<>();cache.download(snapshot,item,AudioQuality.ORIGINAL,error->{problem.set(error);failed.countDown();});
        assertTrue(failed.await(10,TimeUnit.SECONDS));assertFalse(problem.get().isEmpty());assertFalse(cache.saved(snapshot,item));assertFalse(cache.failure(snapshot,item).isEmpty());boolean visible=false;for(MediaItem track:cache.pending(snapshot))if(track.id.equals(item.id))visible=true;assertTrue(visible);
        CountDownLatch retried=new CountDownLatch(1);cache.download(snapshot,item,AudioQuality.ORIGINAL,error->{problem.set(error);retried.countDown();});assertTrue(retried.await(15,TimeUnit.SECONDS));assertEquals("",problem.get());assertEquals("",cache.failure(snapshot,item));assertTrue(cache.saved(snapshot,item));assertTrue(cache.usage(snapshot)[0]>1000);
        cache.remove(snapshot,item);
    }

    public void testPlaybackAutomaticallyPrefetchesItsQueueAndReusesAnyCompleteFormat()throws Exception{
        EmbyClient snapshot=client();AudioCache cache=AudioCache.get(context);
        List<MediaItem> tracks=snapshot.browse("songs",null,null,"",0).items.subList(41,44);
        cache.clearAutomatic(snapshot);SystemClock.sleep(300);prefs.setBool("audio_cache",true);
        main(()->service.playQueue(tracks,0));assertTrue(waitFor(service::isPlaying,10000));
        assertTrue("The playback queue was not cached",waitFor(()->cache.cached(snapshot,tracks.get(2)),25000));
        AudioCache.Local cached=cache.local(snapshot,tracks.get(2),AudioQuality.ORIGINAL,true);assertNotNull(cached);
        int beforeReplay=streamRequests(tracks.get(2).id);
        main(()->{prefs.setBool("original",false);service.select(2);});assertTrue(waitFor(service::isPlaying,10000));assertTrue("Complete original cache should also be reused while online",service.isOfflinePlayback());
        assertEquals("Replaying a cached track must not request its audio again",beforeReplay,streamRequests(tracks.get(2).id));
        assertTrue(waitFor(()->cached.file.lastModified()>1,4000));main(()->service.stopPlayback(true));
    }

    public void testUnsupportedOriginalReusesCachedFallbackOnReplay()throws Exception{
        EmbyClient snapshot=client();AudioCache cache=AudioCache.get(context);MediaItem track=qualityTrack();
        prefs.setBool("audio_cache",true);prefs.setAudioQuality(AudioQuality.ORIGINAL);
        snapshot.json("GET","/fixture/stream-behavior?id="+track.id+"&source_status=415",null);
        try{
            assertTrue(cache.prefetch(snapshot,track,AudioQuality.BALANCED,false,()->{}));
            int before=streamRequests(track.id);
            main(()->service.playQueue(java.util.Collections.singletonList(track),0));
            assertTrue("Cached fallback should play without another stream request",waitFor(()->service.isPlaying()&&service.activeQuality()==AudioQuality.BALANCED,12000));
            assertTrue(service.isOfflinePlayback());assertEquals(before,streamRequests(track.id));
        }finally{
            main(()->service.stopPlayback(true));snapshot.json("GET","/fixture/stream-behavior?id="+track.id,null);
            AudioCache.Local cached=cache.local(snapshot,track,AudioQuality.BALANCED,false);if(cached!=null)cache.invalidate(cached);
        }
    }

    public void testNetworkRetryResumesAndPauseCancelsIt() throws Exception {
        EmbyClient snapshot=client();
        List<MediaItem> tracks=snapshot.browse("songs",null,null,"",0).items.subList(88,90);
        snapshot.json("GET","/fixture/stream-failure?id=track-88&count=1&status=503",null);
        main(()->{service.playQueue(tracks,0);service.seek(12000);});
        assertTrue("Retry failed: "+service.error(),waitFor(service::isPlaying,15000));
        assertTrue("Retry lost position",service.position()>=11800);
        snapshot.json("GET","/fixture/stream-failure?id=track-88&count=99&status=503",null);
        main(()->service.select(0));
        assertTrue(waitFor(()->service.error().contains("等待")&&service.wantsPlay(),10000));
        main(service::pause);SystemClock.sleep(2800);assertFalse(service.wantsPlay());assertFalse(service.isPlaying());
        main(()->service.next(false));
        assertTrue(waitFor(()->"track-89".equals(service.current().id)&&service.isPlaying(),10000));
        SystemClock.sleep(2300);assertEquals("track-89",service.current().id);
        snapshot.json("GET","/fixture/stream-failure?id=track-88&count=0",null);
    }

    public void testSleepTimerAndEndOfTrackStop() throws Exception {
        List<MediaItem> tracks=client().browse("songs",null,null,"",0).items.subList(90,92);
        main(()->service.playQueue(tracks,0));assertTrue(waitFor(service::isPlaying,10000));
        main(()->service.sleep(1300,false,true));assertTrue(service.sleepRemaining()>0);
        assertTrue(waitFor(()->!service.wantsPlay(),5000));
        assertEquals(0L,service.sleepRemaining());
        main(()->{service.play();service.sleep(0,true,false);service.seek(89000);});
        assertTrue(waitFor(()->!service.wantsPlay()&&!service.sleepAfterTrack(),7000));
        assertEquals("track-90",service.current().id);
    }

    public void testSessionExpiryStaleResponsesAndForbidden() throws Exception {
        EmbyClient old=client();
        main(()->prefs.saveSession(HOST,"fixture-user","fixture","fixture-token"));
        HttpURLConnection stale=old.open("/fixture/401");
        try {assertEquals(401,old.response(stale));}finally{stale.disconnect();}
        assertTrue("Stale 401 cleared new login",prefs.loggedIn());
        EmbyClient fresh=client();
        try {fresh.json("GET","/fixture/403",null);fail();}
        catch(EmbyClient.ApiException e){assertEquals(403,e.status);}
        assertTrue(prefs.loggedIn());
        try {fresh.json("GET","/fixture/401",null);fail();}
        catch(EmbyClient.ApiException e){assertEquals(401,e.status);}
        assertFalse(prefs.loggedIn());assertTrue(prefs.hasAccount());
    }

    public void testPlaylistCrudFiltersSortSearchAndLinks() throws Exception {
        EmbyClient api=client();
        String id=api.createPlaylist("Feature test","track-3");
        try {
            api.renamePlaylist(id,"Renamed feature test");api.addToPlaylist(id,"track-4");api.addToPlaylist(id,"track-3");
            List<MediaItem> tracks=api.browse("playlists",id,"Playlist","",0).items;
            assertEquals(3,tracks.size());assertEquals("track-3",tracks.get(0).id);assertEquals("track-4",tracks.get(1).id);
            api.removeFromPlaylist(id,tracks.get(0).playlistEntryId);
            tracks=api.browse("playlists",id,"Playlist","",0).items;assertEquals(2,tracks.size());
            assertEquals("track-4",tracks.get(0).id);
        } finally {api.deletePlaylist(id);}
        EmbyClient.Page sorted=api.browse(new BrowseSpec("songs",null,null,"","SortName",true,"even"),0,100);
        assertEquals(53,sorted.total);assertEquals("track-104",sorted.items.get(0).id);
        EmbyClient.Page search=api.browse(new BrowseSpec("search",null,null,"Fixture","",false,""),0,200);
        HashSet<String> types=new HashSet<>();
        for(MediaItem item:search.items)types.add(item.type);
        assertTrue(types.contains("Audio"));assertTrue(types.contains("MusicAlbum"));assertTrue(types.contains("MusicArtist"));
        MediaItem root=api.browse("folders",null,null,"",0).items.get(0);
        assertEquals("music-root",root.id);
        assertEquals("Folder",api.browse("folders",root.id,root.type,"",0).items.get(0).type);
        MediaItem track=api.browse("songs",null,null,"Track 104",0).items.get(0);
        MediaItem restored=new MediaItem(track.toJson());assertEquals("album-1",restored.albumId);
        assertEquals("artist-1",restored.artistItems.getJSONObject(0).getString("Id"));
        assertNotNull(api.lyricDetails(track).optJSONArray("MediaSources"));
    }

    public void testEnhancedLyricsOffsetsWordsMergeAndEdits() throws Exception {
        String lrc="[00:01.00]<00:01.00>Hello <00:02.00>world\n[00:03.00]<00:03.00>Next <00:04.00>line";
        LyricsDocument parsed=LyricsDocument.parse(lrc);
        assertEquals("Hello world",parsed.cues.get(0).original);
        assertEquals(6,parsed.cues.get(0).wordEnd(1500));assertEquals(11,parsed.cues.get(0).wordEnd(2100));
        LyricsDocument merged=LyricsDocument.parse(LyricsDocument.merge(lrc,"[00:01.20]你好世界\n[00:03.00]下一行"));
        assertTrue(merged.hasTranslation);assertEquals("你好世界",merged.cues.get(0).translation);
        assertEquals(2,merged.cues.get(0).words.size());
        String plain="Hello\nGoodbye";
        assertTrue(LyricsDocument.parse(LyricsDocument.merge(plain,"你好\n再见")).hasTranslation);
        MediaItem item=new MediaItem(new JSONObject().put("Id","edit-test-"+java.util.UUID.randomUUID()));
        LyricsEdits edit=new LyricsEdits(lrc,"你好\n再见",700);EmbyClient api=client();
        try {
            edit.write(context,api,item);LyricsEdits restored=LyricsEdits.read(context,api,item);
            assertEquals(700,restored.offset);
            assertTrue(restored.apply(new LyricsRepository.Result("","")).document.hasTranslation);
        } finally {LyricsEdits.EMPTY.write(context,api,item);}
    }

    public void testLegacyTlsFallbackCannotDisableModernProtocols() throws Exception {
        try(javax.net.ssl.SSLSocket socket=(javax.net.ssl.SSLSocket)TlsSocketFactory.get().createSocket()) {
            socket.setEnabledProtocols(new String[]{"SSLv3"});
            assertTrue(java.util.Arrays.asList(socket.getEnabledProtocols()).contains("TLSv1.2"));
            assertFalse(java.util.Arrays.asList(socket.getEnabledProtocols()).contains("SSLv3"));
            socket.setEnabledProtocols(new String[]{"TLSv1.2"});
            assertEquals(1,socket.getEnabledProtocols().length);
        }
    }
}
