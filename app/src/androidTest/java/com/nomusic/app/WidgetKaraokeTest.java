package com.nomusic.app;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RemoteViews;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@SuppressWarnings("deprecation")
public final class WidgetKaraokeTest extends InstrumentationTestCase {
    private Context context;
    private AppPrefs prefs;
    private Map<String,?> saved;
    @Override protected void setUp()throws Exception{super.setUp();context=getInstrumentation().getTargetContext();prefs=new AppPrefs(context);saved=new HashMap<>(prefs.store.getAll());}
    @Override protected void tearDown()throws Exception{
        SharedPreferences.Editor restore=prefs.store.edit().clear();for(Map.Entry<String,?> entry:saved.entrySet()){
            Object value=entry.getValue();String key=entry.getKey();if(value instanceof String)restore.putString(key,(String)value);else if(value instanceof Integer)restore.putInt(key,(Integer)value);else if(value instanceof Long)restore.putLong(key,(Long)value);else if(value instanceof Boolean)restore.putBoolean(key,(Boolean)value);else if(value instanceof Float)restore.putFloat(key,(Float)value);
        }restore.commit();super.tearDown();
    }
    public void testCantoneseTraditionalSimplifiedAndWordReadings()throws Exception{
        PronunciationEngine engine=new PronunciationEngine(context);
        assertEquals("hoeng1 gong2",engine.read("香港",PronunciationEngine.Language.CANTONESE).text);
        assertEquals("oi3 nei5",engine.read("愛你",PronunciationEngine.Language.CANTONESE).text);
        assertEquals("oi3 nei5",engine.read("爱你",PronunciationEngine.Language.CANTONESE).text);
        assertEquals("jam1 ngok6",engine.read("音樂",PronunciationEngine.Language.CANTONESE).text);
        PronunciationEngine.Line line=engine.read("香港",PronunciationEngine.Language.CANTONESE);
        assertEquals(6,line.highlightedEnd(1));assertEquals(line.text.length(),line.highlightedEnd(2));
    }
    public void testJapaneseKanjiParticlesAndKana()throws Exception{
        PronunciationEngine engine=new PronunciationEngine(context);
        String reading=engine.read("君の名前を呼ぶ",PronunciationEngine.Language.JAPANESE).text;
        assertTrue(reading,reading.contains("namae"));assertTrue(reading,reading.endsWith("yobu"));assertTrue(reading,reading.contains(" o "));
        assertEquals("gakkou",PronunciationEngine.romaji("ガッコウ"));assertEquals("kyou",PronunciationEngine.romaji("キョウ"));
        assertEquals("paatii",PronunciationEngine.romaji("パーティー"));assertEquals("shin'you",PronunciationEngine.romaji("シンヨウ"));
        assertTrue(engine.read("私は学校へ",PronunciationEngine.Language.JAPANESE).text.endsWith(" e"));
    }
    public void testKoreanLiaisonAndAssimilation(){
        assertEquals("annyeonghaseyo",PronunciationEngine.korean("안녕하세요").text);
        assertEquals("hangugeo",PronunciationEngine.korean("한국어").text);
        assertEquals("saranghae",PronunciationEngine.korean("사랑해").text);
        assertEquals("gachi",PronunciationEngine.korean("같이").text);
        assertEquals("ilgeo",PronunciationEngine.korean("읽어").text);
        assertEquals("meongneun",PronunciationEngine.korean("먹는").text);
        assertEquals("silla",PronunciationEngine.korean("신라").text);
        assertEquals("seollal",PronunciationEngine.korean("설날").text);
    }
    public void testAutoDetectionDoesNotGuessCantonese(){
        assertEquals(PronunciationEngine.Language.AUTO,PronunciationEngine.detect(LyricsDocument.parse("[00:01]明日的歌")));
        assertEquals(PronunciationEngine.Language.JAPANESE,PronunciationEngine.detect(LyricsDocument.parse("[00:01]君の名前")));
        assertEquals(PronunciationEngine.Language.KOREAN,PronunciationEngine.detect(LyricsDocument.parse("[00:01]사랑해")));
    }
    public void testPronunciationCacheCancellationAndSourceInvalidation()throws Exception{
        File directory=new File(context.getCacheDir(),"test-readings-"+System.nanoTime());
        PronunciationRepository first=new PronunciationRepository(context,directory),second=null;
        try{
            String text="[00:01]사랑해\n[00:02]안녕하세요";
            CountDownLatch ready=new CountDownLatch(1);AtomicReference<PronunciationRepository.Result> result=new AtomicReference<>();AtomicInteger cancelled=new AtomicInteger();
            PronunciationRepository.Request old=first.load("fixture",text,PronunciationEngine.Language.KOREAN,value->cancelled.incrementAndGet());old.cancel();
            first.load("fixture",text,PronunciationEngine.Language.KOREAN,value->{result.set(value);ready.countDown();});
            assertTrue(ready.await(20,TimeUnit.SECONDS));getInstrumentation().waitForIdleSync();assertEquals(0,cancelled.get());assertEquals("saranghae",result.get().line(0).text);
            assertTrue(new File(directory,PronunciationRepository.cacheKey("fixture",text,PronunciationEngine.Language.KOREAN)+".json").isFile());
            first.close();second=new PronunciationRepository(context,directory);CountDownLatch disk=new CountDownLatch(1);AtomicReference<PronunciationRepository.Result> restored=new AtomicReference<>();
            second.load("fixture",text,PronunciationEngine.Language.KOREAN,value->{restored.set(value);disk.countDown();});assertTrue(disk.await(10,TimeUnit.SECONDS));assertEquals(result.get().line(1).text,restored.get().line(1).text);
            assertFalse(PronunciationRepository.cacheKey("fixture",text,PronunciationEngine.Language.KOREAN).equals(PronunciationRepository.cacheKey("fixture",text+"!",PronunciationEngine.Language.KOREAN)));
            assertFalse(PronunciationRepository.cacheKey("fixture",text,PronunciationEngine.Language.KOREAN).equals(PronunciationRepository.cacheKey("other-account",text,PronunciationEngine.Language.KOREAN)));
        }finally{first.close();if(second!=null)second.close();File[] files=directory.listFiles();if(files!=null)for(File file:files)file.delete();directory.delete();}
    }
    public void testWidgetTimelineKeepsBreaksAndOffset(){
        LyricsDocument document=LyricsDocument.parse("[00:01]第一句\n[00:02]\n[00:03]第三句");
        MusicWidgets.LyricLines before=MusicWidgets.lines(document,500);assertEquals("",before.current);assertEquals("第一句",before.next);
        MusicWidgets.LyricLines active=MusicWidgets.lines(document,1200);assertEquals("第一句",active.current);
        MusicWidgets.LyricLines gap=MusicWidgets.lines(document,2300);assertEquals("",gap.current);assertEquals("第一句",gap.previous);assertEquals("第三句",gap.next);
    }
    public void testPracticeRangeRespectsOffsetsAndTextLyrics(){
        LyricsDocument document=LyricsDocument.parse("[00:01]第一句\n[00:02]\n[00:03]第三句");LyricLoop loop=new LyricLoop();
        assertTrue(loop.set(document,0,100,5000));assertEquals(900L,loop.start);assertEquals(1900L,loop.end);assertFalse(loop.shouldSeek(1000));assertTrue(loop.shouldSeek(1900));
        assertTrue(loop.set(document,2,-200,5000));assertEquals(3200L,loop.start);assertEquals(5000L,loop.end);
        assertFalse(loop.set(LyricsDocument.parse("纯文本"),0,0,5000));assertFalse(loop.active());
    }
    public void testThreeProvidersAndRemoteViewsAtCarSizes()throws Exception{
        int count=0;for(AppWidgetProviderInfo provider:AppWidgetManager.getInstance(context).getInstalledProviders())if(provider.provider.getPackageName().equals(context.getPackageName()))count++;
        assertEquals(3,count);
        MediaItem item=new MediaItem(new JSONObject().put("Id","fixture").put("Name","一首非常长的歌曲标题，用来检查桌面布局边界").put("Artists",new org.json.JSONArray().put("NoMusic 测试歌手")));
        int[][] sizes={{420,640,R.layout.widget_tall},{280,320,R.layout.widget_tall},{400,300,R.layout.widget_card},{280,240,R.layout.widget_card},{420,80,R.layout.widget_compact},{280,64,R.layout.widget_compact}};
        for(int[] size:sizes)getInstrumentation().runOnMainSync(()->{
            RemoteViews remote=new RemoteViews(context.getPackageName(),size[2]);MusicWidgets.configure(context,0,remote,size[2]);
            MusicWidgets.bindText(remote,item,true,"让旋律陪你出发，让每段旅途都有歌","每段旅途都有歌","下一站继续聆听","Let the music take you home","joeng6 syun4 leot6 pui4 nei5 ceot1 faat3",Ui.ACCENT,400,4000,10000,"",size[2]);
            MusicWidgets.resize(context,remote,size[2],size[0],size[1]);
            FrameLayout host=new FrameLayout(context);View view=remote.apply(context,host);host.addView(view,new FrameLayout.LayoutParams(-1,-1));
            host.measure(View.MeasureSpec.makeMeasureSpec(Ui.dp(context,size[0]),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(Ui.dp(context,size[1]),View.MeasureSpec.EXACTLY));host.layout(0,0,host.getMeasuredWidth(),host.getMeasuredHeight());
            for(int id:new int[]{R.id.widget_previous,R.id.widget_toggle,R.id.widget_next}){
                View control=view.findViewById(id);if(control.getVisibility()!=View.VISIBLE)continue;android.graphics.Rect bounds=new android.graphics.Rect();control.getDrawingRect(bounds);host.offsetDescendantRectToMyCoords(control,bounds);
                assertTrue("Clipped widget controls at "+size[0]+"x"+size[1]+": "+bounds,bounds.left>=0&&bounds.right<=host.getWidth()&&bounds.top>=0&&bounds.bottom<=host.getHeight());assertTrue(control.getWidth()>=Ui.dp(context,48));
            }
            if(size[2]!=R.layout.widget_compact)for(int id:new int[]{R.id.widget_current_line,R.id.widget_reading,R.id.widget_translation}){
                View line=view.findViewById(id);if(line.getVisibility()==View.VISIBLE)assertTrue("Clipped widget text at "+size[0]+"x"+size[1],line.getBottom()<=((View)line.getParent()).getHeight()-((View)line.getParent()).getPaddingBottom());
            }
            android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(host.getWidth(),host.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);host.draw(new android.graphics.Canvas(bitmap));
            try{File directory=new File(context.getFilesDir(),"ui-review");directory.mkdirs();try(java.io.FileOutputStream output=new java.io.FileOutputStream(new File(directory,"widget-"+size[2]+"-"+size[0]+"x"+size[1]+"-fixture.png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);}}catch(Exception failure){throw new AssertionError(failure);}finally{bitmap.recycle();}
        });
    }
    public void testKaraokeReadingsAndTranslationCanBeToggledIndependently()throws Exception{
        prefs.setBool("lyrics_translation",true);prefs.setBool("lyrics_pronunciation",false);
        prefs.store.edit().putInt("lyrics_font",30).putInt("karaoke_font",44).apply();
        AtomicReference<LyricsView> reference=new AtomicReference<>();
        getInstrumentation().runOnMainSync(()->{
            LyricsView view=new LyricsView(context,prefs);reference.set(view);view.setKaraoke(true);view.setPronunciationScope("fixture-karaoke");view.setResult(new LyricsRepository.Result("[00:01]사랑해\n[00:01][tr:]我爱你\n[00:03]안녕하세요",""));view.updatePosition(1200);
        });
        try{
            long deadline=android.os.SystemClock.elapsedRealtime()+20000;AtomicReference<String> reading=new AtomicReference<>("");
            while(android.os.SystemClock.elapsedRealtime()<deadline){getInstrumentation().runOnMainSync(()->{ListView list=reference.get().findViewById(R.id.lyrics_list);LinearLayout row=(LinearLayout)list.getAdapter().getView(0,null,list);reading.set(((TextView)row.findViewById(R.id.lyrics_reading)).getText().toString());});if(reading.get().equals("saranghae"))break;android.os.SystemClock.sleep(100);}
            assertEquals("saranghae",reading.get());
            getInstrumentation().runOnMainSync(()->{
                LyricsView view=reference.get();view.findViewById(R.id.lyrics_translation).performClick();assertFalse(prefs.bool("lyrics_translation",true));
                ListView list=view.findViewById(R.id.lyrics_list);LinearLayout row=(LinearLayout)list.getAdapter().getView(0,null,list);assertEquals(View.VISIBLE,row.findViewById(R.id.lyrics_reading).getVisibility());assertEquals(View.GONE,row.findViewById(R.id.lyrics_translation_line).getVisibility());
                row.measure(View.MeasureSpec.makeMeasureSpec(Ui.dp(context,400),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));row.layout(0,0,row.getMeasuredWidth(),row.getMeasuredHeight());
                TextView original=row.findViewById(R.id.lyrics_original),readingView=row.findViewById(R.id.lyrics_reading);
                assertEquals(30*context.getResources().getDisplayMetrics().scaledDensity,original.getTextSize(),.01f);
                assertTrue("Pronunciation must be above the original lyrics",readingView.getBottom()<=original.getTop());assertEquals("사랑해",original.getText().toString());
                view.setKaraoke(false);row=(LinearLayout)list.getAdapter().getView(0,row,list);assertEquals(View.GONE,row.findViewById(R.id.lyrics_reading).getVisibility());
                assertEquals(original.getTextSize(),((TextView)row.findViewById(R.id.lyrics_original)).getTextSize(),.01f);
            });
        }finally{getInstrumentation().runOnMainSync(()->reference.get().dispose());}
    }
}
