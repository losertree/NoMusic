package com.nomusic.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import org.json.JSONObject;
import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Actual Android disk/network checks, isolated from the user's account, queue and files. */
@SuppressWarnings("deprecation")
public final class AudioCachePolicyTest extends InstrumentationTestCase {
    private Context context;private File sandbox;private AppPrefs prefs;private EmbyClient api;
    private AudioCache cache;private CachePreloader preloader;private String scope;
    private interface Check{boolean ok()throws Exception;}
    private boolean waitFor(Check check,long millis)throws Exception{
        long end=SystemClock.elapsedRealtime()+millis;
        while(SystemClock.elapsedRealtime()<end){if(check.ok())return true;SystemClock.sleep(50);}return check.ok();
    }
    @Override protected void setUp()throws Exception{
        Context target=getInstrumentation().getTargetContext();scope="policy-"+UUID.randomUUID();sandbox=new File(target.getFilesDir(),scope);assertTrue(sandbox.mkdirs());
        context=new ContextWrapper(target){
            @Override public File getFilesDir(){return sandbox;}
            @Override public SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences(scope+"-"+name,mode);}
        };
        prefs=new AppPrefs(context);prefs.saveSession("http://10.0.2.2:18765",scope,"fixture","fixture-token");prefs.setBool("audio_cache",true);
        api=new EmbyClient(prefs);cache=new AudioCache(context);assertTrue(waitFor(cache::indexReady,5000));preloader=new CachePreloader(context,cache);
    }
    @Override protected void tearDown()throws Exception{
        preloader.suspend(true);cache.cancelAutomatic();prefs.logout();SystemClock.sleep(300);
        delete(sandbox);context.getSharedPreferences("nomusic",0).edit().clear().commit();context.getSharedPreferences("cache_jobs",0).edit().clear().commit();super.tearDown();
    }
    private static void delete(File file){File[] children=file.listFiles();if(children!=null)for(File child:children)delete(child);file.delete();}
    private MediaItem track(int number)throws Exception{return new MediaItem(new JSONObject().put("Id","track-"+number).put("Name","Track "+number).put("Type","Audio"));}
    private File auto(MediaItem item){return new File(sandbox,"offline-v1/"+StorageFiles.key(api.accountKey())+"/auto/"+StorageFiles.key(item.id)+".src");}
    private int streams(MediaItem item)throws Exception{return api.json("GET","/fixture/stream-count?id="+item.id,null).getInt("Count");}
    private void behavior(MediaItem item,String args)throws Exception{api.json("GET","/fixture/stream-behavior?id="+item.id+args,null);}
    private void failure(MediaItem item,int count,int status)throws Exception{api.json("GET","/fixture/stream-failure?id="+item.id+"&count="+count+"&status="+status,null);}

    public void testCompleteIndexSkipsExistingAndSurvivesRestart()throws Exception{
        MediaItem item=track(71);prefs.setAudioQuality(AudioQuality.ORIGINAL);int before=streams(item);assertFalse(cache.cached(api,item));
        assertTrue(cache.prefetch(api,item,AudioQuality.ORIGINAL,false,()->{}));assertTrue(cache.cached(api,item));assertEquals(before+1,streams(item));
        prefs.setAudioQuality(AudioQuality.BALANCED);assertTrue(cache.prefetch(api,item,AudioQuality.BALANCED,false,()->{}));
        assertTrue("The cached indicator must follow the chosen stream quality",cache.cached(api,item));assertEquals(before+2,streams(item));
        prefs.setAudioQuality(AudioQuality.MAX);assertFalse("A different quality must not show a false cache mark",cache.cached(api,item));
        prefs.setAudioQuality(AudioQuality.BALANCED);
        AudioCache restarted=new AudioCache(context);assertTrue(waitFor(restarted::indexReady,5000));assertTrue(restarted.cached(api,item));
        assertEquals(auto(item).length()+new File(auto(item).getPath().replace(".src",".mp3")).length(),restarted.usage(api)[1]);
        prefs.saveSession(api.server,scope+"-other","fixture","fixture-token");assertFalse(restarted.cached(new EmbyClient(prefs),item));
    }
    public void testTruncationNeverMarksCachedAndRetriesCanRecover()throws Exception{
        MediaItem item=track(72);behavior(item,"&truncate=3");int before=streams(item);
        try{cache.prefetch(api,item,AudioQuality.ORIGINAL,false,()->{});fail("Truncated audio was accepted");}catch(java.io.IOException expected){}
        assertFalse(cache.cached(api,item));assertFalse(auto(item).exists());assertEquals(before+3,streams(item));
        behavior(item,"&truncate=1");assertTrue(cache.prefetch(api,item,AudioQuality.ORIGINAL,false,()->{}));assertTrue(cache.cached(api,item));assertEquals(before+5,streams(item));
    }
    public void testLastPlaybackWinsOverLookupAndDownloadOrder()throws Exception{
        MediaItem first=track(73),last=track(74),incoming=track(75);
        cache.played(api,first);SystemClock.sleep(80);cache.played(api,last);SystemClock.sleep(80);
        // Reverse completion order must not reverse the actual listening order.
        cache.prefetch(api,last,AudioQuality.ORIGINAL,false,()->{});cache.prefetch(api,first,AudioQuality.ORIGINAL,false,()->{});
        long originalStamp=auto(first).lastModified();assertTrue(originalStamp<=auto(last).lastModified());
        for(int i=0;i<20;i++){cache.local(api,first,AudioQuality.ORIGINAL,true);cache.cached(api,first);}
        assertEquals(originalStamp,auto(first).lastModified());
        try(RandomAccessFile file=new RandomAccessFile(auto(first),"rw")){file.setLength(130*AppPrefs.MIB);}
        try(RandomAccessFile file=new RandomAccessFile(auto(last),"rw")){file.setLength(125*AppPrefs.MIB);}
        File history=new File(auto(first).getParentFile().getParentFile(),"last-played.json");assertTrue(waitFor(history::isFile,5000));
        assertTrue(auto(first).setLastModified(10_000));assertTrue(auto(last).setLastModified(10_000));
        cache=new AudioCache(context);assertTrue(waitFor(cache::indexReady,5000));
        assertTrue(cache.prefetch(api,incoming,AudioQuality.ORIGINAL,true,()->{}));
        assertFalse("The oldest listened track must be evicted",auto(first).exists());assertTrue(auto(last).isFile());assertTrue(cache.cached(api,incoming));
        assertTrue(cache.usage(api)[1]<=cache.limit(false));
    }
    public void testFullLibraryPausesWithoutEvictionAndResumesAfterSpaceReleased()throws Exception{
        File filler=auto(track(76));assertTrue(filler.getParentFile().mkdirs());try(RandomAccessFile file=new RandomAccessFile(filler,"rw")){file.setLength(256*AppPrefs.MIB);}
        cache=new AudioCache(context);assertTrue(waitFor(cache::indexReady,5000));preloader=new CachePreloader(context,cache);
        preloader.start(api);assertTrue(waitFor(()->preloader.state(api).full,10000));
        assertTrue(filler.isFile());int at=preloader.state(api).next;assertEquals(0,at);int before=streams(track(0));SystemClock.sleep(1200);assertEquals(before,streams(track(0)));
        cache.invalidate(cache.local(api,track(76),AudioQuality.ORIGINAL,true));preloader.start(api);
        assertTrue(waitFor(()->preloader.state(api).finished,150000));CachePreloader.State result=preloader.state(api);assertEquals(105,result.total);assertEquals(105,result.next);assertEquals(0,result.failed);
        assertTrue(cache.cached(api,track(104)));
    }
    public void testQueuePrefetchDefersDuringBufferingAndCancelsOldList()throws Exception{
        List<MediaItem> queue=new ArrayList<>();queue.add(track(77));queue.add(track(78));queue.add(track(79));
        preloader.playback(api,queue,0,1,true,true);SystemClock.sleep(6200);assertFalse(cache.cached(api,queue.get(1)));
        preloader.playback(api,queue,0,1,true,false);assertTrue(waitFor(()->cache.cached(api,queue.get(2)),15000));
        int before=streams(queue.get(1));preloader.playback(api,queue,0,2,true,false);SystemClock.sleep(6500);assertEquals(before,streams(queue.get(1)));
        List<MediaItem> old=new ArrayList<>();old.add(track(80));old.add(track(81));preloader.playback(api,old,0,3,true,false);
        preloader.playback(api,old,0,3,false,true);SystemClock.sleep(6500);assertFalse(cache.cached(api,old.get(1)));
    }
    public void testQueueRefreshesCacheForSelectedQuality()throws Exception{
        MediaItem item=track(84);List<MediaItem> queue=new ArrayList<>();queue.add(item);
        prefs.setAudioQuality(AudioQuality.ORIGINAL);int before=streams(item);
        assertTrue(cache.prefetch(api,item,AudioQuality.ORIGINAL,false,()->{}));
        assertNotNull(cache.local(api,item,AudioQuality.ORIGINAL,false));
        prefs.setAudioQuality(AudioQuality.MAX);
        preloader.playback(api,queue,0,4,true,false);
        assertTrue("Changing quality must queue a matching local copy",waitFor(()->cache.local(api,item,AudioQuality.MAX,false)!=null,20000));
        assertEquals(before+2,streams(item));
    }
    public void testPauseCancelsBlockedLibraryAndPreservesCheckpoint()throws Exception{
        MediaItem blocked=track(0);behavior(blocked,"&delay=5");int before=streams(blocked);
        try{
            preloader.start(api);assertTrue(waitFor(()->streams(blocked)>before,10000));
            long start=SystemClock.elapsedRealtime();preloader.pause();assertTrue(SystemClock.elapsedRealtime()-start<400);assertFalse(preloader.state(api).running);
            SystemClock.sleep(700);assertEquals(0,preloader.state(api).next);assertFalse(cache.cached(api,blocked));
            behavior(blocked,"&delay=0");preloader.start(api);assertTrue(waitFor(()->preloader.state(api).next>=2,15000));preloader.pause();
            CachePreloader restarted=new CachePreloader(context,cache);assertFalse(restarted.state(api).running);assertTrue(restarted.state(api).next>=2);
        }finally{behavior(blocked,"&delay=0");}
    }
    public void testReadRetriesAreBoundedAndPermissionFailuresAreNotRepeated()throws Exception{
        MediaItem item=track(82);int before=streams(item);failure(item,1,503);assertTrue(cache.prefetch(api,item,AudioQuality.ORIGINAL,false,()->{}));assertEquals(before+2,streams(item));
        cache.invalidate(cache.local(api,item,AudioQuality.ORIGINAL,true));failure(item,3,503);
        try{cache.prefetch(api,item,AudioQuality.ORIGINAL,false,()->{});fail();}catch(EmbyClient.ApiException expected){assertEquals(503,expected.status);}assertEquals(before+5,streams(item));
        failure(item,1,403);try{cache.prefetch(api,item,AudioQuality.ORIGINAL,false,()->{});fail();}catch(EmbyClient.ApiException expected){assertEquals(403,expected.status);}assertEquals(before+6,streams(item));assertTrue(prefs.loggedIn());
    }
    public void testSocketTimeoutExhaustionStopsBulkWithoutBusyLoop()throws Exception{
        MediaItem item=track(0);int before=streams(item);behavior(item,"&delay=9");
        try{
            preloader.start(api);assertTrue(waitFor(()->!preloader.state(api).running,40000));assertEquals(before+3,streams(item));
            SystemClock.sleep(600);assertEquals(before+3,streams(item));assertEquals(0,preloader.state(api).next);
        }finally{behavior(item,"&delay=0");}
    }
    public void testBulkWaitsForNetworkAndRestartsAutomaticallyWhenOnline()throws Exception{
        MediaItem item=track(0);int before=streams(item);
        preloader.network(false);preloader.start(api);SystemClock.sleep(350);
        assertTrue(preloader.state(api).running);assertTrue(preloader.state(api).waiting);assertEquals(before,streams(item));
        preloader.network(true);assertTrue(waitFor(()->preloader.state(api).next>=1,12000));assertTrue(cache.cached(api,item));
        preloader.pause();
    }
    public void testManualDownloadIsPreservedAndUpdatesIndexOnMoveAndDelete()throws Exception{
        MediaItem item=track(83);cache.prefetch(api,item,AudioQuality.ORIGINAL,false,()->{});CountDownLatch finished=new CountDownLatch(1);AtomicReference<String> problem=new AtomicReference<>();
        cache.download(api,item,AudioQuality.ORIGINAL,error->{problem.set(error);finished.countDown();});assertTrue(finished.await(10000,TimeUnit.MILLISECONDS));assertEquals("",problem.get());
        assertFalse(auto(item).exists());assertTrue(cache.cached(api,item));assertTrue(cache.saved(api,item));assertEquals(0L,cache.usage(api)[1]);
        cache.clearAutomatic(api);SystemClock.sleep(300);assertTrue(cache.saved(api,item));assertTrue(cache.usage(api)[0]>1000);
        cache.remove(api,item);assertTrue(waitFor(()->!cache.cached(api,item),5000));assertEquals(0L,cache.usage(api)[0]);
    }
}
