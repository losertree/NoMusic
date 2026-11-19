package com.nomusic.app;

import android.content.ComponentName;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.IBinder;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** End-to-end tests against tools/mock_emby.py; no real credentials are required. */
@SuppressWarnings("deprecation")
public final class NoMusicIntegrationTest extends InstrumentationTestCase {
    private static final String HOST="http://10.0.2.2:18765";
    private interface Check { boolean ok(); }
    @Override protected void setUp() throws Exception {
        super.setUp();TlsSocketFactory.initialize(getInstrumentation().getTargetContext());
    }
    private void main(Runnable task) { getInstrumentation().runOnMainSync(task); }
    private boolean waitFor(Check check,long timeout) {
        long deadline=SystemClock.elapsedRealtime()+timeout;
        while(SystemClock.elapsedRealtime()<deadline) {
            AtomicReference<Boolean> result=new AtomicReference<>(false);
            main(()->result.set(check.ok()));
            if(result.get())return true;
            SystemClock.sleep(100);
        }
        return false;
    }
    private EmbyClient client() { return new EmbyClient(HOST,"fixture-user","fixture-token","fixture-device"); }

    private MediaItem lyricItem(String prefix) throws Exception {
        return new MediaItem(new JSONObject().put("Id",prefix+java.util.UUID.randomUUID()).put("Type","Audio"));
    }
    private int lyricRequests(MediaItem item) throws Exception {
        return client().json("GET","/fixture/status",null).getJSONObject("LyricRequests").optInt(item.id);
    }
    private int detailRequests(MediaItem item) throws Exception {
        return client().json("GET","/fixture/status",null).getJSONObject("DetailRequests").optInt(item.id);
    }
    private LyricsRepository.Result lyrics(LyricsRepository cache,EmbyClient api,MediaItem item,boolean force) throws Exception {
        CountDownLatch ready=new CountDownLatch(1);AtomicReference<LyricsRepository.Result> result=new AtomicReference<>();
        cache.load(api,item,force,value->{result.set(value);ready.countDown();});
        assertTrue("Lyrics callback did not arrive",ready.await(10,TimeUnit.SECONDS));return result.get();
    }
    private void removeTestCache(File directory) {
        File[] files=directory.listFiles();if(files!=null)for(File file:files)file.delete();directory.delete();
    }

    public void testEmbeddedLyricsFallbackAndCacheVersion() throws Exception {
        File directory=new File(getInstrumentation().getTargetContext().getCacheDir(),"lyrics-test-"+java.util.UUID.randomUUID());
        LyricsRepository cache=new LyricsRepository(directory,System::currentTimeMillis);
        try {
            MediaItem item=lyricItem("lyric-embedded-root-");
            String text=lyrics(cache,client(),item,false).text;
            assertTrue("A 404 lyric endpoint must fall back to embedded lyrics",text.contains("内嵌歌词第一行"));
            assertFalse(text.contains("Not lyrics"));assertEquals(1,detailRequests(item));
            lyrics(cache,client(),item,false);assertEquals(1,lyricRequests(item));assertEquals(1,detailRequests(item));
            cache.close();cache=new LyricsRepository(directory,System::currentTimeMillis);
            assertEquals(text,lyrics(cache,client(),item,false).text);assertEquals(1,detailRequests(item));
            // Old "missing" entries must not suppress the newly supported embedded-lyrics path.
            cache.close();
            for(File file:directory.listFiles())try(FileOutputStream stream=new FileOutputStream(file)) {
                stream.write(new JSONObject().put("Version",1).put("Text","").put("Expires",Long.MAX_VALUE).toString().getBytes("UTF-8"));
            }
            cache=new LyricsRepository(directory,System::currentTimeMillis);
            assertEquals(text,lyrics(cache,client(),item,false).text);assertEquals(2,detailRequests(item));
            for(String prefix:new String[]{"lyric-embedded-source-","lyric-embedded-empty-api-","lyric-embedded-invalid-api-"}) {
                LyricsRepository.Result result=lyrics(cache,client(),lyricItem(prefix),false);
                assertTrue(result.error.isEmpty());assertEquals(text,result.text);
            }
            MediaItem flaky=lyricItem("lyric-embedded-flaky-");
            assertFalse(lyrics(cache,client(),flaky,false).error.isEmpty());
            assertEquals("A failed detail request must not become a persistent missing result",text,lyrics(cache,client(),flaky,true).text);
            assertEquals(4,detailRequests(flaky));
        } finally {cache.close();removeTestCache(directory);}
    }

    public void testLyricsMemoryDiskDedupCancellationAndAccountIsolation() throws Exception {
        File directory=new File(getInstrumentation().getTargetContext().getCacheDir(),"lyrics-test-"+java.util.UUID.randomUUID());
        LyricsRepository cache=new LyricsRepository(directory,System::currentTimeMillis);
        try {
            MediaItem item=lyricItem("lyric-delayed-");CountDownLatch ready=new CountDownLatch(2);AtomicInteger canceled=new AtomicInteger();
            LyricsRepository.Request abandoned=cache.load(client(),item,false,value->canceled.incrementAndGet());abandoned.cancel();
            cache.load(client(),item,false,value->ready.countDown());cache.load(client(),item,true,value->ready.countDown());
            assertTrue(ready.await(10,TimeUnit.SECONDS));assertEquals(0,canceled.get());
            assertEquals("Reopened pages must share a pending request",1,lyricRequests(item));
            assertTrue(cache.peek(client(),item).text.contains("Fixture lyric line 1"));
            assertTrue(lyrics(cache,client(),item,false).error.isEmpty());assertEquals(1,lyricRequests(item));
            cache.close();cache=new LyricsRepository(directory,System::currentTimeMillis);
            assertTrue("Disk cache must survive repository/activity recreation",lyrics(cache,client(),item,false).text.contains("Fixture lyric line 1"));
            assertEquals(1,lyricRequests(item));
            lyrics(cache,client(),item,true);assertEquals("Explicit refresh must fetch again",2,lyricRequests(item));
            lyrics(cache,new EmbyClient(HOST,"another-user","fixture-token","fixture-device"),item,false);assertEquals(3,lyricRequests(item));
            lyrics(cache,new EmbyClient(HOST+"/emby","fixture-user","fixture-token","fixture-device"),item,false);assertEquals(4,lyricRequests(item));
        } finally {cache.close();removeTestCache(directory);}
    }

    public void testLyricsMissingExpiryErrorCooldownAndManualRetry() throws Exception {
        File directory=new File(getInstrumentation().getTargetContext().getCacheDir(),"lyrics-test-"+java.util.UUID.randomUUID());
        AtomicLong clock=new AtomicLong(System.currentTimeMillis());LyricsRepository cache=new LyricsRepository(directory,clock::get);
        try {
            MediaItem empty=lyricItem("lyric-empty-");LyricsRepository.Result missing=lyrics(cache,client(),empty,false);
            assertTrue(missing.text.isEmpty());assertTrue(missing.error.isEmpty());lyrics(cache,client(),empty,false);assertEquals(1,lyricRequests(empty));
            cache.close();cache=new LyricsRepository(directory,clock::get);lyrics(cache,client(),empty,false);assertEquals(1,lyricRequests(empty));
            clock.addAndGet(LyricsRepository.MISSING_TTL+1);lyrics(cache,client(),empty,false);assertEquals(2,lyricRequests(empty));
            MediaItem notFound=lyricItem("lyric-404-");assertTrue(lyrics(cache,client(),notFound,false).error.isEmpty());
            lyrics(cache,client(),notFound,false);assertEquals("404 must not be fetched on every page open",1,lyricRequests(notFound));
            MediaItem flaky=lyricItem("lyric-flaky-");LyricsRepository.Result failure=lyrics(cache,client(),flaky,false);
            assertFalse(failure.error.isEmpty());assertTrue(failure.text.isEmpty());assertTrue(failure.display().contains("加载失败"));
            lyrics(cache,client(),flaky,false);assertEquals("Reopening after exhausted retries must respect the cooldown",3,lyricRequests(flaky));
            clock.addAndGet(LyricsRepository.ERROR_TTL+1);assertFalse(lyrics(cache,client(),flaky,false).text.isEmpty());assertEquals(4,lyricRequests(flaky));
            MediaItem retry=lyricItem("lyric-flaky-");assertFalse(lyrics(cache,client(),retry,false).error.isEmpty());
            assertFalse("Explicit refresh bypasses error cooldown",lyrics(cache,client(),retry,true).text.isEmpty());assertEquals(4,lyricRequests(retry));
            MediaItem restart=lyricItem("lyric-flaky-");assertFalse(lyrics(cache,client(),restart,false).error.isEmpty());
            cache.close();cache=new LyricsRepository(directory,clock::get);
            assertFalse("Transient failures must not be stored as missing lyrics on disk",lyrics(cache,client(),restart,false).text.isEmpty());assertEquals(4,lyricRequests(restart));
            MediaItem invalid=lyricItem("lyric-invalid-");assertFalse(lyrics(cache,client(),invalid,false).error.isEmpty());
            cache.close();cache=new LyricsRepository(directory,clock::get);assertFalse(lyrics(cache,client(),invalid,false).error.isEmpty());assertEquals(2,lyricRequests(invalid));
        } finally {cache.close();removeTestCache(directory);}
    }

    public void testLyricsRefreshPersistenceStaleFallbackAndCacheRepair() throws Exception {
        File directory=new File(getInstrumentation().getTargetContext().getCacheDir(),"lyrics-test-"+java.util.UUID.randomUUID());
        AtomicLong clock=new AtomicLong(System.currentTimeMillis());LyricsRepository cache=new LyricsRepository(directory,clock::get);
        try {
            MediaItem refresh=lyricItem("lyric-refresh-");assertEquals("Updated lyric 1",lyrics(cache,client(),refresh,false).document.cues.get(0).original);
            assertEquals("Updated lyric 2",lyrics(cache,client(),refresh,true).document.cues.get(0).original);
            cache.close();cache=new LyricsRepository(directory,clock::get);assertEquals("Updated lyric 2",lyrics(cache,client(),refresh,false).document.cues.get(0).original);assertEquals(2,lyricRequests(refresh));
            clock.addAndGet(LyricsRepository.FOUND_TTL+1);assertEquals("Updated lyric 3",lyrics(cache,client(),refresh,false).document.cues.get(0).original);
            MediaItem stale=lyricItem("lyric-refresh-fails-");String original=lyrics(cache,client(),stale,false).text;
            LyricsRepository.Result failed=lyrics(cache,client(),stale,true);assertEquals(original,failed.text);assertFalse(failed.error.isEmpty());
            cache.close();cache=new LyricsRepository(directory,clock::get);assertEquals(original,lyrics(cache,client(),stale,false).text);assertEquals(4,lyricRequests(stale));
            clock.addAndGet(LyricsRepository.FOUND_TTL+1);LyricsRepository.Result offline=lyrics(cache,client(),stale,false);assertEquals(original,offline.text);assertFalse(offline.error.isEmpty());
            cache.close();cache=new LyricsRepository(directory,clock::get);
            for(File file:directory.listFiles())try(FileOutputStream stream=new FileOutputStream(file)){stream.write('{');}
            assertFalse("Corrupt cache entries should be refetched",lyrics(cache,client(),refresh,false).text.isEmpty());assertEquals(4,lyricRequests(refresh));
            assertTrue(lyrics(cache,client(),lyricItem("lyric-lines-"),false).text.contains("Fixture lyric line 2"));
            assertTrue(lyrics(cache,client(),lyricItem("lyric-lyriclines-"),false).text.contains("Fixture lyric line 1"));
            assertEquals("Plain lyric text",lyrics(cache,client(),lyricItem("lyric-text-"),false).text);
        } finally {cache.close();removeTestCache(directory);}
    }

    public void testOnlineTranslationSkipsEmptyCuesAndKeepsTimeline() throws Exception {
        StringBuilder original=new StringBuilder();
        for(int i=0;i<24;i++)original.append(LyricsDocument.timestamp(i*1000L)).append('\n');
        original.append("[00:24]Hello\n[00:25]\n[00:26]♪ Keep original symbol\n");
        LyricsDocument source=LyricsDocument.parse(original.toString());int[] calls={0};
        String translated=LyricTranslation.translate(source,lines->{
            calls[0]++;assertEquals(2,lines.length());assertEquals("Hello",lines.getString(0));assertEquals("♪ Keep original symbol",lines.getString(1));
            return new JSONArray().put("你好").put("♪ 保留原有符号");
        });
        assertEquals(1,calls[0]);
        LyricsDocument merged=LyricsDocument.parse(LyricsDocument.merge(original.toString(),translated));
        assertEquals(27,merged.cues.size());assertEquals("你好",merged.cues.get(24).translation);
        assertEquals("♪ Keep original symbol",merged.cues.get(26).original);assertEquals("♪ 保留原有符号",merged.cues.get(26).translation);
        for(int i=0;i<merged.cues.size();i++)if(i<24||i==25){
            assertEquals(i*1000L,merged.cues.get(i).timeMs);assertEquals("",merged.cues.get(i).original);assertEquals("",merged.cues.get(i).translation);
        }
        String blanks=LyricTranslation.translate(LyricsDocument.parse("[00:00]\n[00:04]"),lines->{fail("Empty cues must not be sent to the translation service");return null;});
        assertEquals(2,LyricsDocument.parse(blanks).cues.size());assertFalse(blanks.contains("♪"));
        try {LyricTranslation.translate(source,lines->new JSONArray().put("wrong line count"));fail("Mismatched translations must be rejected");}
        catch(java.io.IOException expected){assertEquals("翻译结果与歌词行数不符",expected.getMessage());}
    }

    public void testTimedLyricsSortingOffsetsTranslationsAndSeeking() {
        LyricsDocument document=LyricsDocument.parse("[ti:Hidden heading]\n[offset:100]\n[00:04.00]Ending\n[00:01.2][00:03:20]Hello\n[00:01.2]你好\n[00:03.20]你好\n[00:01.2]Hello\n[00:02.00]");
        assertTrue(document.timed);assertTrue(document.hasTranslation);assertEquals(4,document.cues.size());
        assertEquals(1100,document.cues.get(0).timeMs);assertEquals("Hello",document.cues.get(0).original);assertEquals("你好",document.cues.get(0).translation);
        assertEquals(3100,document.cues.get(2).timeMs);assertEquals("Hello",document.cues.get(2).original);
        assertEquals(-1,document.indexAt(1099));assertEquals(0,document.indexAt(1100));
        assertEquals(1,document.indexAt(2000));assertTrue(document.cues.get(1).original.isEmpty());
        assertEquals(3,document.indexAt(100000));assertEquals(0,document.indexAt(1200));
        LyricsDocument simultaneous=LyricsDocument.parse("[00:01.00]同声一\n[00:01.00]同声二");
        assertFalse(simultaneous.hasTranslation);assertEquals("同声一\n同声二",simultaneous.cues.get(0).original);
        LyricsDocument japanese=LyricsDocument.parse("[00:01.00]君のうた\n[00:01.00]你的歌\n[00:02.00]歩き続ける\n继续走下去");
        assertEquals("你的歌",japanese.cues.get(0).translation);assertEquals("继续走下去",japanese.cues.get(1).translation);
        assertEquals(1250,LyricsDocument.parse("[offset:-250]\n[00:01.000]Shifted").cues.get(0).timeMs);
        LyricsDocument plain=LyricsDocument.parse("[ar:Hidden artist]\nFirst line\nSecond line");
        assertFalse(plain.timed);assertEquals(-1,plain.indexAt(100000));assertEquals(2,plain.cues.size());
    }

    public void testApiLyricTimestampsAndTranslationsArePreserved() throws Exception {
        JSONArray lines=new JSONArray()
                .put(new JSONObject().put("Text","First").put("StartTicks",12340000).put("Start",99))
                .put(new JSONObject().put("Text","Second").put("Start",2.5))
                .put(new JSONObject().put("Text","Third").put("Translation","第三行").put("StartTimeTicks",50000000));
        LyricsDocument document=LyricsDocument.parse(LyricsRepository.parse(new JSONObject().put("Lines",lines)));
        assertTrue(document.timed);assertEquals(3,document.cues.size());assertEquals(1234,document.cues.get(0).timeMs);
        assertEquals(2500,document.cues.get(1).timeMs);assertEquals(5000,document.cues.get(2).timeMs);assertEquals("第三行",document.cues.get(2).translation);
        JSONArray alreadyTimed=new JSONArray().put(new JSONObject().put("Text","[01:00.00]Original timestamp").put("StartTicks",0));
        assertEquals(60000,LyricsDocument.parse(LyricsRepository.parse(new JSONObject().put("Lyrics",alreadyTimed))).cues.get(0).timeMs);
        JSONArray latin=new JSONArray().put(new JSONObject().put("Text","Hello").put("Translation","Bonjour").put("Start",1));
        LyricsDocument explicit=LyricsDocument.parse(LyricsRepository.parse(new JSONObject().put("Lyrics",latin)));
        assertEquals("Hello",explicit.cues.get(0).original);assertEquals("Bonjour",explicit.cues.get(0).translation);
    }

    public void testTimedLyricsDiskUpgradeAndTranslationReuse() throws Exception {
        File directory=new File(getInstrumentation().getTargetContext().getCacheDir(),"lyrics-test-"+java.util.UUID.randomUUID());
        LyricsRepository cache=new LyricsRepository(directory,System::currentTimeMillis);
        try {
            MediaItem item=lyricItem("lyric-scroll-");LyricsRepository.Result result=lyrics(cache,client(),item,false);
            assertTrue(result.document.timed);assertTrue(result.document.hasTranslation);assertEquals(80,result.document.cues.size());
            assertEquals("翻译第25行",result.document.cues.get(25).translation);int fetched=lyricRequests(item);assertTrue(fetched>=1&&fetched<=3);cache.close();
            for(File file:directory.listFiles())try(FileOutputStream stream=new FileOutputStream(file)) {
                stream.write(new JSONObject().put("Version",2).put("Text",result.text).put("Expires",System.currentTimeMillis()+1000000).toString().getBytes("UTF-8"));
            }
            cache=new LyricsRepository(directory,System::currentTimeMillis);
            assertEquals("翻译第25行",lyrics(cache,client(),item,false).document.cues.get(25).translation);assertEquals("Existing embedded LRC caches must remain usable without another request",fetched,lyricRequests(item));
            cache.close();
            for(File file:directory.listFiles())try(FileOutputStream stream=new FileOutputStream(file)) {
                stream.write(new JSONObject().put("Version",2).put("Text","Previously flattened API lyric").put("Expires",System.currentTimeMillis()+1000000).toString().getBytes("UTF-8"));
            }
            cache=new LyricsRepository(directory,System::currentTimeMillis);
            assertTrue("Old API caches without a timeline need one refresh",lyrics(cache,client(),item,false).document.timed);int refreshed=lyricRequests(item);assertTrue(refreshed>fetched&&refreshed<=fetched+3);
            assertTrue(lyrics(cache,client(),item,false).document.timed);assertEquals(refreshed,lyricRequests(item));
        } finally {cache.close();removeTestCache(directory);}
    }

    public void testLyricsViewTranslationFollowingAndPlainTextFallback() {
        Context context=getInstrumentation().getTargetContext();AppPrefs prefs=new AppPrefs(context);
        boolean hadPreference=prefs.store.contains("lyrics_translation"),previous=prefs.bool("lyrics_translation",true);
        try {
            main(()->{
                prefs.setBool("lyrics_translation",true);AtomicLong clock=new AtomicLong(10000);
                LyricsView view=new LyricsView(context,prefs,clock::get);
                view.setResult(new LyricsRepository.Result("[00:01]Hello\n[00:01]你好\n[00:05]Goodbye\n[00:05]再见",""));
                view.measure(View.MeasureSpec.makeMeasureSpec(640,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY));view.layout(0,0,640,400);
                ListView list=(ListView)view.findViewById(R.id.lyrics_list);View toggle=view.findViewById(R.id.lyrics_translation);
                view.updatePosition(1500);LinearLayout row=(LinearLayout)list.getAdapter().getView(0,null,list);
                assertTrue(row.isSelected());assertEquals(Ui.TEXT,((TextView)row.getChildAt(0)).getCurrentTextColor());assertEquals(View.VISIBLE,row.getChildAt(1).getVisibility());
                toggle.performClick();assertFalse(prefs.bool("lyrics_translation",true));row=(LinearLayout)list.getAdapter().getView(0,row,list);assertEquals(View.GONE,row.getChildAt(1).getVisibility());
                assertFalse(new LyricsView(context,prefs).findViewById(R.id.lyrics_translation).isSelected());
                view.updatePosition(6000);assertTrue(list.getAdapter().getView(1,null,list).isSelected());assertFalse(list.getAdapter().getView(0,null,list).isSelected());
                view.updatePosition(1500);assertTrue(list.getAdapter().getView(0,null,list).isSelected());
                long now=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,50,100,0),up=MotionEvent.obtain(now,now+20,MotionEvent.ACTION_UP,50,100,0);
                list.dispatchTouchEvent(down);list.dispatchTouchEvent(up);down.recycle();up.recycle();
                View follow=view.findViewById(R.id.lyrics_follow);assertEquals(View.VISIBLE,follow.getVisibility());
                clock.addAndGet(5001);view.updatePosition(6000);assertEquals(View.GONE,follow.getVisibility());
                view.setResult(new LyricsRepository.Result("First plain line\nSecond plain line",""));view.updatePosition(50000);
                assertFalse(toggle.isEnabled());assertTrue(((TextView)view.findViewById(R.id.lyrics_status)).getText().toString().contains("纯文本"));
                assertFalse(list.getAdapter().getView(0,null,list).isSelected());
                view.setResult(new LyricsRepository.Result("[00:00]原有歌词\n[00:04]\n[00:06]♪ 原文已有的音符", ""));
                view.updatePosition(4500);assertEquals(2,list.getAdapter().getCount());LinearLayout priorRow=(LinearLayout)list.getAdapter().getView(0,null,list);
                assertEquals("原有歌词",((TextView)priorRow.getChildAt(0)).getText().toString());assertTrue(priorRow.isSelected());assertEquals(6000L,view.seekPosition(1));
                assertEquals(View.GONE,view.findViewById(R.id.lyrics_status).getVisibility());
                LinearLayout authentic=(LinearLayout)list.getAdapter().getView(1,null,list);assertEquals("♪ 原文已有的音符",((TextView)authentic.getChildAt(0)).getText().toString());
                view.updatePosition(6500);assertTrue(list.getAdapter().getView(1,null,list).isSelected());
                view.setMessage("加载失败");assertEquals(0,list.getAdapter().getCount());
            });
        } finally {if(hadPreference)prefs.store.edit().putBoolean("lyrics_translation",previous).commit();else prefs.store.edit().remove("lyrics_translation").commit();}
    }

    public void testAddressNormalizationAndMediaKeys() throws Exception {
        assertEquals("https://example.com",EmbyClient.normalizeServer(" https://example.com/web/index.html#!/home.html "));
        assertEquals("http://192.168.1.2:8096",EmbyClient.normalizeServer("192.168.1.2:8096/"));
        assertEquals("https://example.com/music/emby",EmbyClient.normalizeServer("https://example.com/music/emby/"));
        for(String bad:new String[]{"ftp://example.com","https://user:secret@example.com","http://","https://example.com:70000"}) {
            try { EmbyClient.normalizeServer(bad);fail("Invalid URL was accepted"); }
            catch(java.io.IOException expected) { }
        }
        assertEquals(PlaybackService.NEXT,MediaButtonReceiver.commandForKey(KeyEvent.KEYCODE_MEDIA_NEXT));
        assertEquals(PlaybackService.TOGGLE,MediaButtonReceiver.commandForKey(KeyEvent.KEYCODE_HEADSETHOOK));
        assertNull(MediaButtonReceiver.commandForKey(KeyEvent.KEYCODE_VOLUME_UP));
    }

    public void testEmbyLoginPagingSearchCollectionsAndFavorites() throws Exception {
        JSONObject auth=EmbyClient.authenticate(HOST,"fixture","fixture","fixture-device");
        assertEquals("fixture-token",auth.getString("AccessToken"));
        try { EmbyClient.authenticate(HOST,"fixture","wrong","fixture-device");fail("Invalid login was accepted"); }
        catch(java.io.IOException expected) { assertEquals("用户名或密码不正确",expected.getMessage()); }
        EmbyClient api=client();
        assertTrue("Truncated GET was not retried",api.json("GET","/fixture/truncated?nonce="+java.util.UUID.randomUUID(),null).getBoolean("Retried"));
        EmbyClient.Page first=api.browse("songs",null,null,"",0),second=api.browse("songs",null,null,"",100);
        assertEquals(105,first.total);assertEquals(100,first.items.size());assertEquals(5,second.items.size());
        assertEquals("track-100",second.items.get(0).id);
        assertEquals(1,api.browse("songs",null,null,"Track 104",0).items.size());
        assertEquals("MusicAlbum",api.browse("albums",null,null,"",0).items.get(0).type);
        assertEquals("MusicArtist",api.browse("artists",null,null,"",0).items.get(0).type);
        assertEquals("Playlist",api.browse("playlists",null,null,"",0).items.get(0).type);
        assertEquals(2,api.browse("albums","album-1","MusicAlbum","",0).items.size());
        assertEquals(2,api.browse("artists","artist-1","MusicArtist","",0).items.size());
        assertEquals(2,api.browse("playlists","playlist-1","Playlist","",0).items.size());
        assertEquals(3,api.browse("recent",null,null,"",0).items.size());
        MediaItem item=first.items.get(0);
        try { api.favorite(item,true);assertEquals(1,api.browse("favorites",null,null,"",0).items.size()); }
        finally { api.favorite(item,false); }
        assertEquals(0,api.browse("favorites",null,null,"",0).items.size());
        assertEquals(2,api.lyrics(item).getJSONArray("Lyrics").length());
        assertNull(api.image(item));
    }

    public void testLoopbackProxyRangesAndPrivatePath() throws Exception {
        MediaItem item=client().browse("songs",null,null,"",0).items.get(0);
        StreamProxy proxy=new StreamProxy(client(),client().streamPath(item,AudioQuality.ORIGINAL,"test-session"));
        try {
            HttpURLConnection connection=(HttpURLConnection)new URL(proxy.url()).openConnection();
            connection.setRequestProperty("Range","bytes=0-43");
            try { assertEquals(206,connection.getResponseCode());assertEquals(44,connection.getContentLength());assertEquals('R',connection.getInputStream().read()); }
            finally { connection.disconnect(); }
            HttpURLConnection denied=(HttpURLConnection)new URL(proxy.url()+"wrong").openConnection();
            try { assertEquals(404,denied.getResponseCode()); } finally { denied.disconnect(); }
        } finally { proxy.close(); }
    }

    public void testPlaybackButtonsFocusSeekingAndQueuePersistence() throws Exception {
        final Context context=getInstrumentation().getTargetContext();
        final AppPrefs prefs=new AppPrefs(context);
        final Map<String,?> before=new HashMap<>(prefs.store.getAll());
        final AtomicReference<Intent> metadata=new AtomicReference<>();
        final BroadcastReceiver metadataReceiver=new BroadcastReceiver(){
            @Override public void onReceive(Context c,Intent intent){metadata.set(intent);}
        };
        context.registerReceiver(metadataReceiver,new IntentFilter("com.android.music.metachanged"));
        final AtomicReference<PlaybackService> reference=new AtomicReference<>();
        final CountDownLatch connected=new CountDownLatch(1);
        final ServiceConnection connection=new ServiceConnection(){
            @Override public void onServiceConnected(ComponentName name,IBinder binder){reference.set(((PlaybackService.LocalBinder)binder).service());connected.countDown();}
            @Override public void onServiceDisconnected(ComponentName name){ }
        };
        boolean bound=false;
        try {
            prefs.store.edit().clear().commit();
            new AppPrefs(context).saveSession(HOST,"fixture-user","fixture","fixture-token");
            main(()->context.startService(new Intent(context,PlaybackService.class)));
            bound=context.bindService(new Intent(context,PlaybackService.class),connection,Context.BIND_AUTO_CREATE);
            assertTrue(connected.await(10,TimeUnit.SECONDS));
            final PlaybackService service=reference.get();
            ArrayList<MediaItem> queue=new ArrayList<>(client().browse("songs",null,null,"",0).items.subList(0,2));
            main(()->{service.playQueue(queue,0);service.seek(10000);});
            assertTrue("Audio did not prepare: "+service.error(),waitFor(service::isPlaying,15000));
            assertTrue("Seek while preparing did not take effect",waitFor(()->service.position()>10300,5000));
            assertNotNull("Legacy metadata missing",metadata.get());
            assertEquals(queue.get(0).name,metadata.get().getStringExtra("track"));
            assertTrue("Legacy music ID must be numeric",metadata.get().getLongExtra("id",-1)>=0);
            assertTrue(metadata.get().getLongExtra("duration",0)>0);
            context.sendBroadcast(new Intent(context.getPackageName()+".musicservicecommand").putExtra("command","pause"));
            assertTrue("Legacy pause broadcast did not reach playback",waitFor(()->!service.wantsPlay()&&!service.isPlaying(),5000));
            context.sendBroadcast(new Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(new ComponentName(context,MediaButtonReceiver.class))
                    .putExtra(Intent.EXTRA_KEY_EVENT,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MEDIA_NEXT)));
            assertTrue("Background media-next did not change track",waitFor(()->service.current()!=null&&"track-1".equals(service.current().id)&&service.isPlaying(),10000));
            context.sendBroadcast(new Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(new ComponentName(context,MediaButtonReceiver.class))
                    .putExtra(Intent.EXTRA_KEY_EVENT,new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_MEDIA_NEXT)));
            SystemClock.sleep(300);assertEquals("track-1",service.current().id);
            main(()->service.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT));assertFalse(service.isPlaying());
            main(()->service.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN));assertTrue(waitFor(service::isPlaying,5000));
            main(()->service.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK));assertTrue(service.isPlaying());
            main(()->service.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN));
            main(service::pause);main(()->service.seek(20000));
            assertTrue("Seeking failed",waitFor(()->!service.isBuffering()&&service.position()>=20000,10000));assertFalse(service.isPlaying());
            main(service::play);assertTrue(waitFor(service::isPlaying,5000));
            main(()->service.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS));
            main(()->service.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN));assertFalse(service.isPlaying());
            assertTrue(prefs.store.getLong("position",0)>=20000);assertEquals(2,new org.json.JSONArray(prefs.store.getString("queue","[]")).length());
            main(service::toggleShuffle);main(()->service.next(false));
            assertTrue(waitFor(()->service.current()!=null&&"track-0".equals(service.current().id)&&service.isPlaying(),10000));
            main(()->service.stopPlayback(true));assertTrue(service.queue().isEmpty());assertFalse(service.isPlaying());
        } finally {
            context.unregisterReceiver(metadataReceiver);
            PlaybackService service=reference.get();if(service!=null)main(()->service.stopPlayback(true));
            if(bound)context.unbindService(connection);
            context.stopService(new Intent(context,PlaybackService.class));
            getInstrumentation().waitForIdleSync();SystemClock.sleep(300);
            SharedPreferences.Editor editor=prefs.store.edit().clear();
            for(Map.Entry<String,?> entry:before.entrySet()) {
                Object value=entry.getValue();String key=entry.getKey();
                if(value instanceof String)editor.putString(key,(String)value);
                else if(value instanceof Long)editor.putLong(key,(Long)value);
                else if(value instanceof Integer)editor.putInt(key,(Integer)value);
                else if(value instanceof Boolean)editor.putBoolean(key,(Boolean)value);
                else if(value instanceof Float)editor.putFloat(key,(Float)value);
            }
            editor.commit();
        }
    }
}
