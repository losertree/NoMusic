package com.nomusic.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Verifies persistence and request counts, using fixture data and preserving the saved account. */
@SuppressWarnings("deprecation")
public final class CacheIntegrationTest extends InstrumentationTestCase {
    private static final String HOST="http://10.0.2.2:18765";
    private Context context;private AppPrefs prefs;private Map<String,?> saved;private String user;
    private EmbyClient api(){return new EmbyClient(prefs);}
    @Override protected void setUp()throws Exception{
        context=getInstrumentation().getTargetContext();prefs=new AppPrefs(context);saved=new HashMap<>(prefs.store.getAll());user="cache-"+UUID.randomUUID();
        prefs.saveSession(HOST,user,"fixture","fixture-token");
    }
    @Override protected void tearDown()throws Exception{
        SharedPreferences.Editor restore=prefs.store.edit().clear();for(Map.Entry<String,?> entry:saved.entrySet()){
            Object value=entry.getValue();String name=entry.getKey();if(value instanceof String)restore.putString(name,(String)value);else if(value instanceof Long)restore.putLong(name,(Long)value);else if(value instanceof Integer)restore.putInt(name,(Integer)value);else if(value instanceof Boolean)restore.putBoolean(name,(Boolean)value);else if(value instanceof Float)restore.putFloat(name,(Float)value);
        }restore.commit();super.tearDown();
    }
    private int requests(String id)throws Exception{return api().json("GET","/fixture/status",null).getJSONObject("ImageRequests").optInt(id);}
    private int browseRequests(String id)throws Exception{
        return api().json("GET","/fixture/browse-count?user="+id,null).getInt("Count");
    }
    public void testArtworkDiskCoalescingTagsFallbackAndRetry()throws Exception{
        String id="art-image-"+UUID.randomUUID();EmbyClient snapshot=api();ArtworkCache cache=new ArtworkCache(context);
        MediaItem item=new MediaItem(new JSONObject().put("Id",id).put("ImageTags",new JSONObject().put("Primary","v1")));
        ExecutorService threads=Executors.newFixedThreadPool(4);
        try{
            java.util.List<Future<Bitmap>> images=new java.util.ArrayList<>();for(int i=0;i<4;i++)images.add(threads.submit(()->cache.load(snapshot,item)));
            for(Future<Bitmap> image:images)assertEquals(8,image.get(10,TimeUnit.SECONDS).getWidth());
        }finally{threads.shutdownNow();}
        assertEquals(1,requests(id));
        ArtworkCache restarted=new ArtworkCache(context);assertNotNull(restarted.load(snapshot,item));assertEquals(1,requests(id));
        MediaItem changed=new MediaItem(new JSONObject().put("Id",id).put("ImageTags",new JSONObject().put("Primary","v2")));
        assertNotNull(restarted.load(snapshot,changed));assertEquals(2,requests(id));
        prefs.saveSession(HOST,user+"-other","fixture","fixture-token");assertNotNull(restarted.load(api(),item));assertEquals(3,requests(id));prefs.saveSession(HOST,user,"fixture","fixture-token");
        String suffix=UUID.randomUUID().toString(),albumId="art-album-"+suffix,songId="art-song-"+suffix;
        MediaItem album=new MediaItem(new JSONObject().put("Id",albumId).put("Type","MusicAlbum"));
        assertNotNull(restarted.load(api(),album));assertEquals(1,requests(albumId));assertEquals(1,requests(songId));
        assertNotNull(new ArtworkCache(context).load(api(),album));assertEquals(1,requests(albumId));assertEquals(1,requests(songId));
        MediaItem missingTrackArt=new MediaItem(new JSONObject().put("Id","art-absent-"+suffix).put("AlbumId",id).put("ImageTags",new JSONObject().put("Primary","missing")));
        assertNotNull(restarted.load(api(),missingTrackArt));
        String retry="art-retry-"+UUID.randomUUID();MediaItem transientImage=new MediaItem(new JSONObject().put("Id",retry));
        assertNotNull(restarted.load(api(),transientImage));assertEquals(2,requests(retry));
        String exhausted="art-exhaust-"+UUID.randomUUID();MediaItem unavailable=new MediaItem(new JSONObject().put("Id",exhausted));
        try{restarted.load(api(),unavailable);fail("Three failures must exhaust the retry budget");}catch(EmbyClient.ApiException expected){assertEquals(503,expected.status);}
        assertEquals(3,requests(exhausted));assertNotNull(restarted.load(api(),unavailable));assertEquals(4,requests(exhausted));
    }
    public void testBrowseDiskPagingQueryIsolationAndInvalidation()throws Exception{
        EmbyClient snapshot=api();int before=browseRequests(user);
        assertEquals(100,snapshot.browse(BrowseSpec.songs(),0,100).items.size());assertEquals(5,snapshot.browse(BrowseSpec.songs(),100,100).items.size());
        assertEquals(before+2,browseRequests(user));
        BrowseCache.get(context).clearMemory();assertEquals(100,new EmbyClient(new AppPrefs(context)).browse(BrowseSpec.songs(),0,100).items.size());assertEquals(before+2,browseRequests(user));
        snapshot.browse(new BrowseSpec("songs",null,null,"","SortName",true,""),0,100);assertEquals(before+3,browseRequests(user));
        prefs.saveSession(HOST,user+"-other","fixture","fixture-token");api().browse(BrowseSpec.songs(),0,100);assertEquals(1,browseRequests(user+"-other"));
        prefs.saveSession(HOST,user,"fixture","fixture-token");snapshot=api();snapshot.browse(BrowseSpec.songs(),0,100);assertEquals(before+3,browseRequests(user));
        snapshot.invalidateBrowse();snapshot.browse(BrowseSpec.songs(),0,100);assertEquals(before+4,browseRequests(user));
        MediaItem track=snapshot.browse(BrowseSpec.songs(),0,100).items.get(0);
        try{snapshot.favorite(track,true);assertEquals(1,snapshot.browse("favorites",null,null,"",0).items.size());}
        finally{snapshot.favorite(track,false);}
        assertEquals(0,snapshot.browse("favorites",null,null,"",0).items.size());
    }
    public void testStaleBrowseReturnsImmediatelyAndFailedRefreshKeepsSnapshot()throws Exception{
        EmbyClient snapshot=api();BrowseCache cache=new BrowseCache(context);String resource="stale-"+UUID.randomUUID();
        JSONObject page=new JSONObject().put("Items",new JSONArray()).put("TotalRecordCount",105);
        assertEquals(105,cache.page(snapshot,resource,()->page).getInt("TotalRecordCount"));
        File file=new File(context.getFilesDir(),"browse-cache-v1/"+StorageFiles.key(snapshot.accountKey())+"/"+StorageFiles.key(resource)+".json");
        JSONObject stored=StorageFiles.readJson(file,4096);stored.put("saved",System.currentTimeMillis()-2L*24*60*60*1000);StorageFiles.writeJson(file,stored);cache=new BrowseCache(context);
        CountDownLatch requested=new CountDownLatch(1),unblock=new CountDownLatch(1);
        long start=SystemClock.elapsedRealtime();JSONObject cached=cache.page(snapshot,resource,()->{requested.countDown();unblock.await(5,TimeUnit.SECONDS);throw new java.io.IOException("fixture unavailable");});
        assertEquals(105,cached.getInt("TotalRecordCount"));assertTrue(SystemClock.elapsedRealtime()-start<1000);assertTrue(requested.await(2,TimeUnit.SECONDS));unblock.countDown();
        SystemClock.sleep(100);assertEquals(105,StorageFiles.readJson(file,4096).getJSONObject("page").getInt("TotalRecordCount"));
        assertEquals(105,cache.page(snapshot,resource,()->{fail("A failed background refresh needs a cooldown");return null;}).getInt("TotalRecordCount"));
    }
    public void testTwentyGiBLimitsLruAndCompleteAudioWarmup()throws Exception{
        File sandbox=new File(context.getFilesDir(),"cache-test-"+UUID.randomUUID());assertTrue(sandbox.mkdirs());
        Context scoped=new ContextWrapper(context){@Override public File getFilesDir(){return sandbox;}};EmbyClient snapshot=api();
        File auto=new File(sandbox,"offline-v1/"+StorageFiles.key(snapshot.accountKey())+"/auto");assertTrue(auto.mkdirs());
        File old=new File(auto,"old.src"),recent=new File(auto,"recent.src");
        try{
            prefs.setCacheLimit(false,512*AppPrefs.MIB);
            try(RandomAccessFile data=new RandomAccessFile(old,"rw")){data.setLength(128*AppPrefs.MIB);}old.setLastModified(1000);
            try(RandomAccessFile data=new RandomAccessFile(recent,"rw")){data.setLength(129*AppPrefs.MIB);}recent.setLastModified(System.currentTimeMillis());
            AudioCache cache=new AudioCache(scoped);
            cache.setLimit(false,AppPrefs.MAX_CACHE);cache.setLimit(true,AppPrefs.MAX_CACHE);assertEquals(21474836480L,cache.limit(false));assertEquals(AppPrefs.MAX_CACHE,new AppPrefs(context).cacheLimit(true));
            cache.setLimit(false,AppPrefs.MAX_CACHE+1);assertEquals(AppPrefs.MAX_CACHE,cache.limit(false));cache.setLimit(false,1);assertEquals(256*AppPrefs.MIB,cache.limit(false));
            cache.setLimit(false,256*AppPrefs.MIB);long until=SystemClock.elapsedRealtime()+5000;while(old.exists()&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(50);
            assertFalse("The oldest automatic file should be evicted",old.exists());assertTrue(recent.isFile());
            MediaItem track=snapshot.browse(BrowseSpec.songs(),100,100).items.get(0);prefs.setBool("audio_cache",true);int streams=snapshot.json("GET","/fixture/stream-count?id="+track.id,null).getInt("Count");
            ExecutorService threads=Executors.newFixedThreadPool(2);
            try{
                Future<Boolean> first=threads.submit(()->cache.prefetch(snapshot,track,AudioQuality.ORIGINAL,true,()->{}));
                Future<Boolean> second=threads.submit(()->cache.prefetch(snapshot,track,AudioQuality.ORIGINAL,true,()->{}));
                assertTrue(first.get(20,TimeUnit.SECONDS)^second.get(20,TimeUnit.SECONDS));
            }finally{threads.shutdownNow();}
            AudioCache.Local local=cache.local(snapshot,track,AudioQuality.ORIGINAL,false);assertNotNull("A complete stream must become reusable audio",local);assertTrue(local.file.length()>1000);
            assertEquals(streams+1,snapshot.json("GET","/fixture/stream-count?id="+track.id,null).getInt("Count"));
            cache.protect(local);assertEquals(local.file,cache.local(snapshot,track,AudioQuality.ORIGINAL,false).file);cache.invalidate(local);
        }finally{old.delete();recent.delete();deleteTree(sandbox);}
    }
    private static void deleteTree(File directory){File[] files=directory.listFiles();if(files!=null)for(File file:files){if(file.isDirectory())deleteTree(file);else file.delete();}directory.delete();}
}
