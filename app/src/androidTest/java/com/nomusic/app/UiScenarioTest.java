package com.nomusic.app;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ListView;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/** Runs real UI controls while preserving the device's entire saved account and queue. */
@SuppressWarnings("deprecation")
public final class UiScenarioTest extends InstrumentationTestCase {
    private Context context;
    private AppPrefs prefs;
    private Map<String,?> previous;
    private MainActivity activity;
    private void main(Runnable task){
        java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
        getInstrumentation().runOnMainSync(()->{try{task.run();}catch(Throwable error){failure.set(error);}});
        Throwable error=failure.get();
        if(error instanceof AssertionError)throw (AssertionError)error;
        if(error instanceof RuntimeException)throw (RuntimeException)error;
        if(error!=null)throw new AssertionError(error);
    }
    private Object field(String name) {
        try {Field field=MainActivity.class.getDeclaredField(name);field.setAccessible(true);return field.get(activity);}
        catch(Exception e){throw new AssertionError(e);}
    }
    private void call(String name) {
        try {Method method=MainActivity.class.getDeclaredMethod(name);method.setAccessible(true);method.invoke(activity);}
        catch(Exception e){throw new AssertionError(e);}
    }
    private void navigate(String name) {
        try {Method method=MainActivity.class.getDeclaredMethod("navigate",String.class);method.setAccessible(true);method.invoke(activity,name);}
        catch(Exception e){throw new AssertionError(e);}
    }
    private void invoke(String name,Class<?>[] types,Object... arguments) {
        try{Method method=MainActivity.class.getDeclaredMethod(name,types);method.setAccessible(true);method.invoke(activity,arguments);}
        catch(Exception e){throw new AssertionError(e);}
    }
    private void screenshot(String filename){screenshot(activity.getWindow().getDecorView(),filename);}
    private void screenshot(View root,String filename){
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(root.getWidth(),root.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);root.draw(new android.graphics.Canvas(bitmap));
        try{java.io.File dir=new java.io.File(context.getFilesDir(),"ui-review");dir.mkdirs();try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(dir,filename))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}}catch(Exception e){throw new AssertionError(e);}finally{bitmap.recycle();}
    }
    private void openFixturePlayer()throws Exception {
        main(()->navigate("songs"));boolean ready=waitFor(()->((java.util.List<?>)field("items")).size()>=100,10000);
        assertTrue("Songs did not load; section="+field("section")+", loading="+field("loading")+", status="+((android.widget.TextView)field("emptyText")).getText(),ready);
        main(()->{ListView songs=(ListView)field("list");songs.performItemClick(songs.getChildAt(0),0,0);});
        assertTrue(waitFor(()->"player".equals(field("section"))&&field("baseLyrics")!=null&&((View)field("shellRoot")).getVisibility()==View.GONE,10000));
    }
    private boolean waitFor(java.util.concurrent.Callable<Boolean> check,long timeout)throws Exception{
        long end=SystemClock.elapsedRealtime()+timeout;
        while(SystemClock.elapsedRealtime()<end) {
            java.util.concurrent.atomic.AtomicReference<Boolean> result=new java.util.concurrent.atomic.AtomicReference<>(false);
            main(()->{try {result.set(check.call());}catch(Exception e){throw new AssertionError(e);}});
            if(result.get())return true;SystemClock.sleep(150);
        }return false;
    }

    @Override protected void setUp() throws Exception {
        context=getInstrumentation().getTargetContext();prefs=new AppPrefs(context);previous=new HashMap<>(prefs.store.getAll());
        main(()->{
            prefs.store.edit().clear().commit();prefs.saveSession("http://10.0.2.2:18765","fixture-user","fixture","fixture-token");
            prefs.setBool("check_updates",false);prefs.setBool("original",true);
        });
        activity=(MainActivity)getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        assertTrue(waitFor(()->field("playback")!=null,5000));
    }

    @Override protected void tearDown() throws Exception {
        main(()->{
            PlaybackService service=(PlaybackService)field("playback");
            if(service!=null)service.stopPlayback(true);activity.finish();
        });
        getInstrumentation().waitForIdleSync();
        context.stopService(new Intent(context,PlaybackService.class));SystemClock.sleep(350);
        SharedPreferences.Editor restore=prefs.store.edit().clear();
        for(Map.Entry<String,?> entry:previous.entrySet()){
            Object value=entry.getValue();String key=entry.getKey();
            if(value instanceof String)restore.putString(key,(String)value);
            else if(value instanceof Long)restore.putLong(key,(Long)value);
            else if(value instanceof Integer)restore.putInt(key,(Integer)value);
            else if(value instanceof Boolean)restore.putBoolean(key,(Boolean)value);
            else if(value instanceof Float)restore.putFloat(key,(Float)value);
        }
        restore.commit();
    }

    public void testCachedCheckUpdatesVisibleRowsWithoutReloadingOrLosingScroll()throws Exception{
        main(()->navigate("songs"));assertTrue(waitFor(()->((java.util.List<?>)field("items")).size()>=100,10000));
        main(()->((ListView)field("list")).setSelectionFromTop(20,0));assertTrue(waitFor(()->((ListView)field("list")).getFirstVisiblePosition()==20,5000));
        java.util.concurrent.atomic.AtomicReference<View> first=new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<MediaItem> selected=new java.util.concurrent.atomic.AtomicReference<>();
        main(()->{ListView list=(ListView)field("list");first.set(list.getChildAt(0));selected.set((MediaItem)((java.util.List<?>)field("items")).get(20));});
        AudioCache cache=AudioCache.get(context);EmbyClient api=new EmbyClient(prefs);AudioCache.Local existing=cache.local(api,selected.get(),AudioQuality.ORIGINAL,true);if(existing!=null)cache.invalidate(existing);
        assertTrue(waitFor(()->!containsCachedText(first.get()),5000));cache.prefetch(api,selected.get(),AudioQuality.ORIGINAL,false,()->{});
        assertTrue(waitFor(()->containsCachedText(first.get()),5000));
        main(()->{
            ListView list=(ListView)field("list");View row=list.getChildAt(0);
            assertSame("Cache status should update the existing row",first.get(),row);assertEquals(20,list.getFirstVisiblePosition());
            assertEquals("The cache mark belongs only before the artist",1,cachedTextCount(row));assertTrue(hasText(row,"✓  Hi-Res  Fixture Artist"));
            if((Boolean)field("wide"))assertNotNull(findText(row,"Fixture Album"));
            screenshot("cached-list.png");navigate("settings");
        });
        main(()->{
            View start=activity.findViewById(R.id.library_cache);assertNotNull(start);assertTrue(start.performClick());
            assertTrue(CachePreloader.get(context).state(api).running);assertTrue(start.performClick());assertFalse(CachePreloader.get(context).state(api).running);
            assertTrue(((android.widget.TextView)activity.findViewById(R.id.library_cache_status)).getText().toString().contains("自动缓存"));
            start.requestFocus();
        });
        getInstrumentation().waitForIdleSync();main(()->{
            View start=activity.findViewById(R.id.library_cache);android.widget.ScrollView scroll=(android.widget.ScrollView)start.getParent().getParent();
            scroll.scrollTo(0,Math.max(0,start.getTop()-Ui.dp(context,300)));screenshot("cache-settings.png");
        });
    }
    private boolean containsCachedText(View view){return cachedTextCount(view)>0;}
    private int cachedTextCount(View view){
        if(view instanceof TextView)return ((TextView)view).getText().toString().startsWith("✓")?1:0;
        int count=0;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int at=0;at<group.getChildCount();at++)count+=cachedTextCount(group.getChildAt(at));}
        return count;
    }

    public void testLegacyBrowseAddsQualityToRestoredRowsWithoutMovingThem()throws Exception{
        EmbyClient api=new EmbyClient(prefs);api.invalidateBrowse();
        String query="Track 0",term=android.net.Uri.encode(query);
        String resource="/Users/fixture-user/Items?Fields=Artists%2CArtistItems%2CAlbum%2CAlbumId%2CAlbumPrimaryImageTag%2CRunTimeTicks%2CUserData%2CImageTags"
                +"&StartIndex=0&Limit=100&EnableTotalRecordCount=true&IncludeItemTypes=Audio&Recursive=true"
                +"&SortBy=SortName&SortOrder=Ascending&SearchTerm="+term;
        BrowseCache.get(context).page(api,resource,()->api.json("GET",resource,null));
        String countPath="/fixture/browse-count?user=fixture-user&term="+term,holdPath="/fixture/browse-hold?term="+term+"&hold=";
        int requests=api.json("GET",countPath,null).getInt("Count");
        api.json("GET",holdPath+"1",null);
        try {
            Class<?>[] navigation={String.class,String.class};
            main(()->invoke("navigate",navigation,"songs",query));
            assertTrue("Old rows should be available while the refresh is held",waitFor(()->!(Boolean)field("loading")&&((java.util.List<?>)field("items")).size()==100,5000));
            int top=-Ui.dp(context,8);
            main(()->((ListView)field("list")).setSelectionFromTop(20,top));
            assertTrue(waitFor(()->{ListView list=(ListView)field("list");return list.getFirstVisiblePosition()==20&&list.getChildAt(0).getTop()==top;},5000));
            main(()->navigate("albums"));assertTrue(waitFor(()->!(Boolean)field("loading"),5000));
            main(()->invoke("navigate",navigation,"songs",query));
            assertTrue(waitFor(()->{ListView list=(ListView)field("list");return list.getFirstVisiblePosition()==20&&list.getChildAt(0).getTop()==top;},5000));
            java.util.concurrent.atomic.AtomicReference<View> row=new java.util.concurrent.atomic.AtomicReference<>();
            main(()->{
                row.set(((ListView)field("list")).getChildAt(0));
                assertFalse(hasText(row.get(),"Hi-Res  Fixture Artist"));
            });
            api.json("GET",holdPath+"0",null);
            assertTrue("Refreshed quality must reach the rows already on screen",waitFor(()->hasText(row.get(),"Hi-Res  Fixture Artist"),10000));
            main(()->{
                ListView list=(ListView)field("list");
                assertSame(row.get(),list.getChildAt(0));assertEquals(20,list.getFirstVisiblePosition());assertEquals(top,list.getChildAt(0).getTop());
                if((Boolean)field("wide"))assertNotNull(findText(row.get(),"Fixture Album"));
            });
            assertEquals("One batch updates the entire page",requests+1,api.json("GET",countPath,null).getInt("Count"));
        }finally{api.json("GET",holdPath+"0",null);}
    }

    public void testLegacyQueueGetsOriginalBitrateOnce()throws Exception {
        EmbyClient api=new EmbyClient(prefs);
        MediaItem track=new MediaItem(api.json("GET","/Users/fixture-user/Items?StartIndex=103&Limit=1",null).getJSONArray("Items").getJSONObject(0));
        assertEquals(0,track.sourceAudio.bitrate);
        int before=detailRequests(api,track);
        main(()->{
            prefs.setBool("audio_cache",false);prefs.setAudioQuality(AudioQuality.SAVER);
            PlaybackService service=(PlaybackService)field("playback");service.playQueue(java.util.Collections.singletonList(track),0);service.pause();
            call("showLyrics");
        });
        assertTrue(waitFor(()->"320 kbps".contentEquals(((TextView)field("nowBitrate")).getText()),10000));
        main(()->activity.onBackPressed());
        assertTrue(waitFor(()->"home".equals(field("section")),5000));
        main(()->call("showLyrics"));
        assertTrue(waitFor(()->"320 kbps".contentEquals(((TextView)field("nowBitrate")).getText()),5000));
        assertEquals(before+1,detailRequests(api,track));
    }

    private int detailRequests(EmbyClient api,MediaItem track)throws Exception{
        return api.json("GET","/fixture/status",null).getJSONObject("DetailRequests").optInt(track.id);
    }

    public void testFullScreenTranslationQueueAndBrowserReturn() throws Exception {
        main(()->navigate("songs"));
        boolean ready=waitFor(()->((ListView)field("list")).getAdapter().getCount()>90,10000);
        assertTrue("Songs did not load; section="+field("section")+", loading="+field("loading")+", status="+((android.widget.TextView)field("emptyText")).getText(),ready);
        main(()->{
            ListView list=(ListView)field("list");
            list.performItemClick(list.getChildAt(0),0,0);
        });
        assertTrue(waitFor(()->"player".equals(field("section"))&&((View)field("shellRoot")).getVisibility()==View.GONE,5000));
        main(()->{
            assertTrue((activity.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_FULLSCREEN)!=0);
            assertEquals(View.GONE,((View)field("shellRoot")).getVisibility());
            LyricsView lyrics=(LyricsView)field("nowLyrics");
            lyrics.setResult(new LyricsRepository.Result("[00:01]Hello\n[00:01]你好\n[00:02]Goodbye\n[00:02]再见",""));
            View translation=((View)field("nowRoot")).findViewById(R.id.lyrics_translation);
            assertTrue(translation.isEnabled());assertTrue(translation.isSelected());assertTrue(translation.performClick());assertFalse(translation.isSelected());
            activity.onBackPressed();
        });
        assertTrue(waitFor(()->"songs".equals(field("section")),5000));
        main(()->{
            assertEquals("songs",field("section"));assertEquals(100,((java.util.List<?>)field("items")).size());
            assertEquals(0,activity.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_FULLSCREEN);
            navigate("queue");ListView queue=(ListView)field("list");
            assertEquals(100,queue.getAdapter().getCount()-queue.getFooterViewsCount());
            PlaybackService service=(PlaybackService)field("playback");
            service.enqueue(service.queue().get(1),true);
            assertEquals(101,service.queueSize());
            call("showLyrics");call("sleepMenu");
            assertTrue(((android.app.AlertDialog)field("activeDialog")).isShowing());
            ((android.app.AlertDialog)field("activeDialog")).dismiss();
        });
    }

    public void testDrawerEditingPreservesPlayerAndLyrics()throws Exception {
        openFixturePlayer();main(()->{
            PlaybackService service=(PlaybackService)field("playback");service.pause();service.seek(1000);
            LyricsView lyrics=(LyricsView)field("nowLyrics");lyrics.setResult(new LyricsRepository.Result("[00:00]First line\n[00:00]第一句\n[00:02]Second line\n[00:02]第二句", ""));
            call("openDrawer");assertNotNull(activity.findViewById(R.id.queue_panel));assertSame(lyrics,field("nowLyrics"));
            service.move(2,3);assertEquals(100,service.queueSize());assertEquals(0,service.index());assertFalse(service.wantsPlay());
            service.remove(4);assertEquals(99,service.queueSize());assertEquals(0,service.index());
        });getInstrumentation().waitForIdleSync();
        main(()->{screenshot("queue.png");activity.onBackPressed();assertEquals("player",field("section"));assertNotNull(activity.findViewById(R.id.lyrics_translation));});
        assertTrue(waitFor(()->activity.findViewById(R.id.queue_panel)==null,2000));
        main(()->screenshot("player.png"));
    }

    public void testLightLibraryDarkPlayerAndGridReturn()throws Exception {
        main(()->{prefs.setBool("light_theme",true);call("showShell");navigate("songs");});
        assertTrue(waitFor(()->((java.util.List<?>)field("items")).size()>=100,10000));
        main(()->{assertTrue(activity.uiColors().light);assertEquals(5,((ViewGroup)field("nav")).getChildCount());screenshot("library-light.png");ListView songs=(ListView)field("list");songs.performItemClick(songs.getChildAt(0),0,0);});
        assertTrue(waitFor(()->"player".equals(field("section")),5000));
        main(()->{assertFalse(activity.uiColors().light);activity.onBackPressed();});
        assertTrue(waitFor(()->"songs".equals(field("section")),5000));
        main(()->{assertTrue(activity.uiColors().light);assertEquals(100,((java.util.List<?>)field("items")).size());prefs.setBool("light_theme",false);call("showShell");navigate("albums");});
        assertTrue(waitFor(()->!((java.util.List<?>)field("items")).isEmpty()&&!(Boolean)field("loading"),10000));
        main(()->{assertTrue((Integer)field("gridColumns")>0);MediaItem album=(MediaItem)((java.util.List<?>)field("items")).get(0);invoke("openFolder",new Class<?>[]{MediaItem.class},album);});
        assertTrue(waitFor(()->!(Boolean)field("loading"),10000));
        main(()->{activity.onBackPressed();assertNull(field("parentId"));assertEquals("albums",field("section"));assertTrue((Integer)field("gridColumns")>0);navigate("songs");});
        assertTrue(waitFor(()->((java.util.List<?>)field("items")).size()>=100,10000));main(()->screenshot("library-dark.png"));
    }

    public void testCompactPlayerAndStableLyricRows()throws Exception {
        openFixturePlayer();main(()->{
            PlaybackService service=(PlaybackService)field("playback");service.pause();
            LyricsView lyrics=(LyricsView)field("nowLyrics");lyrics.setResult(new LyricsRepository.Result("[00:00]A long lyric line that may wrap to another line\n[00:00]这一句歌词可能会换行\n[00:04]Next\n[00:04]下一句", ""));
            ListView rows=(ListView)lyrics.findViewById(R.id.lyrics_list);lyrics.updatePosition(1000);View row=rows.getAdapter().getView(0,null,rows);int width=Ui.dp(context,320);row.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));int activeHeight=row.getMeasuredHeight();
            lyrics.updatePosition(5000);row=rows.getAdapter().getView(0,row,rows);row.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));assertEquals(activeHeight,row.getMeasuredHeight());
            View stage=(View)field("playerStage");stage.measure(View.MeasureSpec.makeMeasureSpec(Ui.dp(context,800),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(Ui.dp(context,480),View.MeasureSpec.EXACTLY));stage.layout(0,0,Ui.dp(context,800),Ui.dp(context,480));
            assertTrue(stage.findViewById(R.id.player_toggle).getWidth()>=Ui.dp(context,48));assertTrue(stage.findViewById(R.id.player_next).getRight()<=((View)stage.findViewById(R.id.player_next).getParent()).getWidth());
            for(int[] size:new int[][]{{1280,720},{1024,600},{800,480},{720,400},{480,270},{320,180}}){
                stage.measure(View.MeasureSpec.makeMeasureSpec(Ui.dp(context,size[0]),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(Ui.dp(context,size[1]),View.MeasureSpec.EXACTLY));stage.layout(0,0,Ui.dp(context,size[0]),Ui.dp(context,size[1]));
                View dock=(View)field("playerPane");android.view.ViewGroup transport=(android.view.ViewGroup)stage.findViewById(R.id.player_toggle).getParent();
                assertTrue(dock.getTop()>=0);assertTrue(dock.getBottom()<=stage.getHeight());
                assertTrue(dock.getHeight()<=Ui.dp(context,104));
                assertTrue("Player dock stays above the window edge",stage.getHeight()-dock.getBottom()>=Ui.dp(context,12));
                for(int i=0;i<transport.getChildCount();i++){
                    View control=transport.getChildAt(i);if(control.getVisibility()!=View.VISIBLE)continue;
                    assertTrue("Control left overflow at "+size[0],control.getLeft()>=0);assertTrue("Control right overflow at "+size[0],control.getRight()<=transport.getWidth());
                    assertTrue(control.getHeight()>=Ui.dp(context,48));assertTrue(control.getTop()>=0);assertTrue(control.getBottom()<=transport.getHeight());
                    assertTrue("Keep space below each target",dock.getHeight()-transport.getTop()-control.getBottom()>=Ui.dp(context,8));
                    for(int j=i+1;j<transport.getChildCount();j++){View other=transport.getChildAt(j);if(other.getVisibility()==View.VISIBLE)assertTrue("Controls overlap at "+size[0],control.getRight()<=other.getLeft()||other.getRight()<=control.getLeft());}
                }
            }
            stage.measure(View.MeasureSpec.makeMeasureSpec(Ui.dp(context,720),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(Ui.dp(context,400),View.MeasureSpec.EXACTLY));stage.layout(0,0,Ui.dp(context,720),Ui.dp(context,400));
            assertEquals(View.VISIBLE,lyrics.getVisibility());((PlayerStage)stage).toggleView();stage.measure(View.MeasureSpec.makeMeasureSpec(Ui.dp(context,720),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(Ui.dp(context,400),View.MeasureSpec.EXACTLY));stage.layout(0,0,Ui.dp(context,720),Ui.dp(context,400));assertEquals(View.GONE,lyrics.getVisibility());
            View artist=(View)field("nowArtist"),bitrate=stage.findViewById(R.id.player_bitrate),album=(View)field("nowAlbum");
            assertEquals("850 kbps",((TextView)bitrate).getText().toString());
            assertTrue(artist.getRight()<=bitrate.getLeft());assertTrue(bitrate.getRight()<=album.getLeft());
        });
    }

    public void testLargeFontPlayerAndLibraryRemainOperable()throws Exception {
        android.content.res.Resources resources=activity.getResources();
        android.content.res.Configuration original=new android.content.res.Configuration(resources.getConfiguration());
        try {
            main(()->{
                android.content.res.Configuration enlarged=new android.content.res.Configuration(original);enlarged.fontScale=1.3f;
                resources.updateConfiguration(enlarged,resources.getDisplayMetrics());call("showShell");
            });
            openFixturePlayer();getInstrumentation().waitForIdleSync();
            main(()->{
                ((PlaybackService)field("playback")).pause();
                LyricsView lyrics=(LyricsView)field("nowLyrics");
                lyrics.setResult(new LyricsRepository.Result("[00:00]A lyric at a larger system font size\n[00:00]大字号的双语歌词\n[00:04]Next line\n[00:04]下一句", ""));
            });SystemClock.sleep(150);getInstrumentation().waitForIdleSync();
            main(()->{
                assertEquals(1.3f,resources.getConfiguration().fontScale,.01f);
                android.widget.TextView title=(android.widget.TextView)field("nowTitle");
                assertTrue("Large-font resources must reach the actual title",title.getTextSize()>30*resources.getDisplayMetrics().density);
                assertNotNull(title.getLayout());assertTrue(title.getHeight()>=title.getLayout().getHeight());
                View dock=(View)field("playerPane");View lyrics=(View)field("nowLyrics");
                assertTrue(lyrics.getBottom()<=dock.getTop());
                View player=(View)field("nowRoot");
                for(int id:new int[]{R.id.player_previous,R.id.player_toggle,R.id.player_next,R.id.queue_open,R.id.lyrics_translation}) {
                    View control=player.findViewById(id);android.graphics.Rect visible=new android.graphics.Rect();
                    assertTrue(control.getGlobalVisibleRect(visible));assertEquals(control.getWidth(),visible.width());assertEquals(control.getHeight(),visible.height());
                }
                screenshot("player-fontscale13.png");activity.onBackPressed();
            });
            assertTrue(waitFor(()->"songs".equals(field("section")),5000));
            main(()->{
                assertEquals(100,((java.util.List<?>)field("items")).size());
                prefs.setBool("light_theme",true);call("showShell");navigate("songs");
            });
            assertTrue(waitFor(()->((java.util.List<?>)field("items")).size()>=100,10000));
            main(()->{ListView songs=(ListView)field("list");assertTrue(songs.getChildCount()>0);assertTrue(songs.getChildAt(0).getHeight()>=Ui.dp(context,56));android.widget.TextView metadata=(android.widget.TextView)field("playerArtist");assertNotNull(metadata.getLayout());assertTrue(metadata.getHeight()>=metadata.getLayout().getHeight());screenshot("library-fontscale13.png");});
        } finally {main(()->resources.updateConfiguration(original,resources.getDisplayMetrics()));}
    }

    public void testCoverColorsAndDialogHaveNoBlackFrame()throws Exception {
        openFixturePlayer();main(()->{
            PlaybackService service=(PlaybackService)field("playback");service.pause();service.seek(1000);
            LyricsView lyrics=(LyricsView)field("nowLyrics");Object cached=field("baseLyrics");
            android.graphics.Bitmap art=android.graphics.Bitmap.createBitmap(8,8,android.graphics.Bitmap.Config.ARGB_8888);
            try {
                art.eraseColor(0xffd03030);invoke("tintPlayer",new Class<?>[]{int.class},AmbientDrawable.sample(art));
                android.graphics.Bitmap button=android.graphics.Bitmap.createBitmap(Ui.dp(context,64),Ui.dp(context,64),android.graphics.Bitmap.Config.ARGB_8888);
                try {
                    ViewGroup play=((View)field("nowRoot")).findViewById(R.id.player_toggle);
                    android.graphics.drawable.Drawable background=play.getBackground();
                    background.setBounds(0,0,button.getWidth(),button.getHeight());background.setState(new int[]{});
                    background.draw(new android.graphics.Canvas(button));
                    assertEquals(0,android.graphics.Color.alpha(button.getPixel(button.getWidth()/2,button.getHeight()/2)));
                    background.setState(new int[]{android.R.attr.state_focused});background.draw(new android.graphics.Canvas(button));
                    assertTrue(android.graphics.Color.alpha(button.getPixel(1,button.getHeight()/2))>0);
                    assertEquals(0,android.graphics.Color.alpha(button.getPixel(button.getWidth()/2,button.getHeight()/2)));
                    View glyph=play.getChildAt(0);int x=glyph.getWidth()/2,y=glyph.getHeight()/2;
                    button.eraseColor(0);glyph.draw(new android.graphics.Canvas(button));
                    int red=button.getPixel(x,y);assertTrue(android.graphics.Color.red(red)>android.graphics.Color.blue(red));
                    art.eraseColor(0xff3050d0);invoke("tintPlayer",new Class<?>[]{int.class},AmbientDrawable.sample(art));
                    button.eraseColor(0);glyph.draw(new android.graphics.Canvas(button));
                    int blue=button.getPixel(x,y);assertTrue(android.graphics.Color.blue(blue)>android.graphics.Color.red(blue));assertTrue(red!=blue);
                } finally{button.recycle();}
            } finally{art.recycle();}
            assertSame(lyrics,field("nowLyrics"));assertSame(cached,field("baseLyrics"));assertFalse(service.wantsPlay());
            assertTrue(Math.abs(service.position()-1000)<100);call("sleepMenu");
        });SystemClock.sleep(150);getInstrumentation().waitForIdleSync();
        main(()->{
            android.app.AlertDialog dialog=(android.app.AlertDialog)field("activeDialog");View root=dialog.getWindow().getDecorView();
            android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(root.getWidth(),root.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);
            try {
                root.draw(new android.graphics.Canvas(bitmap));
                assertEquals("Rounded dialog corners must be transparent",0,android.graphics.Color.alpha(bitmap.getPixel(0,0)));
                java.io.File directory=new java.io.File(context.getFilesDir(),"ui-review");directory.mkdirs();
                try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(directory,"dialog.png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}
                catch(Exception e){throw new AssertionError(e);}
            } finally{bitmap.recycle();dialog.dismiss();}
        });
    }
    public void testLibraryNavigationReusesRowsAndBothCacheLimitsOfferTwentyGiB()throws Exception {
        openFixturePlayer();main(()->{((PlaybackService)field("playback")).pause();activity.onBackPressed();});
        assertTrue(waitFor(()->"songs".equals(field("section")),5000));
        main(()->((ListView)field("list")).setSelectionFromTop(24,0));
        getInstrumentation().waitForIdleSync();SystemClock.sleep(100);
        main(()->{assertEquals(24,((ListView)field("list")).getFirstVisiblePosition());navigate("albums");});
        assertTrue(waitFor(()->!(Boolean)field("loading"),10000));main(()->navigate("songs"));getInstrumentation().waitForIdleSync();SystemClock.sleep(100);
        main(()->{assertEquals(100,((java.util.List<?>)field("items")).size());assertEquals(24,((ListView)field("list")).getFirstVisiblePosition());assertFalse((Boolean)field("loading"));navigate("settings");});
        for(boolean offline:new boolean[]{false,true})main(()->{
            invoke("cacheSize",new Class<?>[]{boolean.class},offline);android.app.AlertDialog dialog=(android.app.AlertDialog)field("activeDialog");
            ListView choices=dialog.getListView();assertTrue(choices.getAdapter().getItem(6).toString().contains("20.0"));choices.performItemClick(null,6,choices.getAdapter().getItemId(6));
            assertEquals(AppPrefs.MAX_CACHE,prefs.cacheLimit(offline));
        });
    }

    public void testThemeColorChoicePersistsAndCoverOverrideIsOptional()throws Exception {
        main(()->{
            navigate("settings");assertTrue(activity.findViewById(R.id.theme_color).performClick());
            android.app.AlertDialog picker=(android.app.AlertDialog)field("activeDialog");
            assertNotNull(picker.findViewById(R.id.theme_color_picker));
            assertTrue(picker.findViewById(R.id.theme_color_picker).findViewWithTag("theme_choice_jade").performClick());
            assertFalse(picker.isShowing());assertEquals("jade",new AppPrefs(context).themeColor());
            assertTrue(android.graphics.Color.green(activity.uiColors().ACCENT)>android.graphics.Color.red(activity.uiColors().ACCENT));
            assertTrue(activity.findViewById(R.id.cover_colors).performClick());assertFalse(prefs.bool("cover_colors",true));
            // A new UI tree must retain the saved choice and the toggle.
            call("showShell");assertTrue(activity.findViewById(R.id.theme_color).getContentDescription().toString().contains("青玉"));
            assertFalse(prefs.bool("cover_colors",true));
        });
        openFixturePlayer();
        main(()->{
            PlaybackService service=(PlaybackService)field("playback");service.pause();
            Object lyrics=field("nowLyrics"),queue=service.current();
            invoke("tintPlayer",new Class<?>[]{int.class},0xff803030);
            int fixed=activity.uiColors().ACCENT;
            invoke("tintPlayer",new Class<?>[]{int.class},0xff303080);assertEquals(fixed,activity.uiColors().ACCENT);
            prefs.setBool("cover_colors",true);invoke("tintPlayer",new Class<?>[]{int.class},0xff303080);
            assertTrue(android.graphics.Color.blue(activity.uiColors().ACCENT)>android.graphics.Color.green(activity.uiColors().ACCENT));
            assertSame(lyrics,field("nowLyrics"));assertSame(queue,service.current());assertFalse(service.wantsPlay());
            activity.onBackPressed();navigate("settings");
            activity.findViewById(R.id.theme_toggle).performClick();
            android.app.AlertDialog theme=(android.app.AlertDialog)field("activeDialog");theme.getListView().performItemClick(null,1,1);
            assertTrue(activity.uiColors().light);assertEquals("jade",prefs.themeColor());
            assertTrue(android.graphics.Color.green(activity.uiColors().ACCENT)>android.graphics.Color.red(activity.uiColors().ACCENT));
            activity.findViewById(R.id.theme_color).performClick();
            android.app.AlertDialog picker=(android.app.AlertDialog)field("activeDialog");
            assertTrue(picker.findViewById(R.id.theme_color_picker).findViewWithTag("theme_choice_jade").isSelected());
            picker.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick();assertEquals("jade",prefs.themeColor());
        });
    }

    public void testHomeShortcutsWrapAndRemainUsableWithLargerFonts()throws Exception {
        android.content.res.Resources resources=activity.getResources();
        android.content.res.Configuration original=new android.content.res.Configuration(resources.getConfiguration());
        try {
            main(()->{
                android.content.res.Configuration enlarged=new android.content.res.Configuration(original);enlarged.fontScale=1.3f;
                resources.updateConfiguration(enlarged,resources.getDisplayMetrics());navigate("home");
                ViewGroup shortcuts=activity.findViewById(R.id.home_shortcuts);assertEquals(4,shortcuts.getChildCount());
                for(int width:new int[]{200,320,480,640,800,1040}){
                    shortcuts.measure(View.MeasureSpec.makeMeasureSpec(Ui.dp(context,width),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
                    shortcuts.layout(0,0,shortcuts.getMeasuredWidth(),shortcuts.getMeasuredHeight());
                    for(int i=0;i<shortcuts.getChildCount();i++){
                        View card=shortcuts.getChildAt(i);assertTrue(card.getLeft()>=0);assertTrue(card.getRight()<=shortcuts.getWidth());
                        assertTrue(card.getTop()>=0);assertTrue(card.getBottom()<=shortcuts.getHeight());assertTrue(card.getHeight()>=Ui.dp(context,56));
                        ViewGroup contents=(ViewGroup)card;android.widget.TextView label=(android.widget.TextView)contents.getChildAt(2);
                        assertTrue("Shortcut title clipped at "+width,label.getPaint().measureText(label.getText().toString())<=label.getMeasuredWidth());
                        for(int j=i+1;j<shortcuts.getChildCount();j++){
                            View other=shortcuts.getChildAt(j);assertFalse("Home actions overlap",android.graphics.Rect.intersects(new android.graphics.Rect(card.getLeft(),card.getTop(),card.getRight(),card.getBottom()),new android.graphics.Rect(other.getLeft(),other.getTop(),other.getRight(),other.getBottom())));
                        }
                    }
                }
                assertTrue(shortcuts.getChildAt(1).performClick());assertEquals("favorites",field("section"));navigate("home");
                ((ViewGroup)activity.findViewById(R.id.home_shortcuts)).getChildAt(2).performClick();assertEquals("recent",field("section"));navigate("home");
                ((ViewGroup)activity.findViewById(R.id.home_shortcuts)).getChildAt(3).performClick();assertEquals("downloads",field("section"));
            });
        } finally {main(()->resources.updateConfiguration(original,resources.getDisplayMetrics()));}
        main(()->{prefs.setThemeColor("gold");call("showShell");navigate("home");});
        assertTrue(waitFor(()->((ViewGroup)field("content")).getHeight()>0,3000));getInstrumentation().waitForIdleSync();
        main(()->screenshot("home-gold.png"));
        main(()->{prefs.setThemeColor("jade");call("showShell");navigate("home");});getInstrumentation().waitForIdleSync();
        main(()->screenshot("home-jade.png"));
        main(()->{navigate("settings");activity.findViewById(R.id.theme_color).performClick();});getInstrumentation().waitForIdleSync();
        main(()->{
            android.app.AlertDialog picker=(android.app.AlertDialog)field("activeDialog");View root=picker.getWindow().getDecorView();
            android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(root.getWidth(),root.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);
            try{
                root.draw(new android.graphics.Canvas(bitmap));assertEquals(0,android.graphics.Color.alpha(bitmap.getPixel(0,0)));
                java.io.File directory=new java.io.File(context.getFilesDir(),"ui-review");directory.mkdirs();
                try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(directory,"theme-colors.png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}
                catch(Exception error){throw new AssertionError(error);}
            } finally {bitmap.recycle();picker.dismiss();}
        });
    }

    public void testMotionCancelsSafelyOnFastNavigationAndDrawerReopen()throws Exception {
        openFixturePlayer();
        assertTrue(waitFor(()->activity.uiMotion().idle(),2000));
        main(()->{
            PlaybackService service=(PlaybackService)field("playback");service.pause();service.seek(1000);
            Object lyrics=field("nowLyrics"),track=service.current();
            call("openDrawer");View overlay=(View)field("queueOverlay");
            assertTrue(activity.uiMotion().running(overlay));assertTrue(overlay.getAlpha()<1);
            call("dismissDrawer");assertTrue((Boolean)field("drawerClosing"));
            call("openDrawer");assertSame(overlay,field("queueOverlay"));assertFalse((Boolean)field("drawerClosing"));
            assertSame(lyrics,field("nowLyrics"));assertSame(track,service.current());
            assertFalse(service.wantsPlay());assertTrue(Math.abs(service.position()-1000)<100);
        });
        assertTrue(waitFor(()->activity.uiMotion().idle(),2000));
        main(()->{
            assertNotNull(field("queueOverlay"));assertEquals(1f,((View)field("queueOverlay")).getAlpha(),.001f);
            assertEquals(0f,((View)field("drawerPanel")).getTranslationX(),.001f);
            call("dismissDrawer");navigate("albums");navigate("home");navigate("songs");
        });
        assertTrue(waitFor(()->!(Boolean)field("loading")&&activity.uiMotion().idle(),5000));
        main(()->{
            assertEquals("songs",field("section"));assertNull(activity.findViewById(R.id.queue_panel));
            View content=(View)field("content");assertEquals(1f,content.getAlpha(),.001f);assertEquals(0f,content.getTranslationY(),.001f);
            call("showLyrics");call("openDrawer");call("dismissDrawer");
            activity.onPause();assertNull(field("queueOverlay"));assertTrue(activity.uiMotion().idle());
            assertEquals(1f,((View)field("nowRoot")).getAlpha(),.001f);activity.onResume();
        });
    }

    public void testPressFeedbackKeepsClicksAndCancellationAndMotionSetting()throws Exception {
        main(()->navigate("home"));assertTrue(waitFor(()->activity.uiMotion().idle(),2000));
        final int[] clicks={0};final android.widget.TextView[] target={null};
        main(()->{
            target[0]=Ui.button(activity,"Feedback",false,v->clicks[0]++);
            ((ViewGroup)field("content")).addView(target[0],new ViewGroup.LayoutParams(Ui.dp(context,160),Ui.dp(context,56)));
        });getInstrumentation().waitForIdleSync();
        long now=SystemClock.uptimeMillis();
        main(()->sendTouch(target[0],now,android.view.MotionEvent.ACTION_DOWN));SystemClock.sleep(100);
        main(()->{
            assertTrue("Pressed button must have visible feedback",target[0].getScaleX()<1);
            sendTouch(target[0],now,android.view.MotionEvent.ACTION_UP);
        });
        assertTrue(waitFor(()->clicks[0]==1&&activity.uiMotion().idle(),2000));
        main(()->{
            assertEquals(1f,target[0].getScaleX(),.001f);assertEquals(1f,target[0].getScaleY(),.001f);
            long time=SystemClock.uptimeMillis();sendTouch(target[0],time,android.view.MotionEvent.ACTION_DOWN);sendTouch(target[0],time,android.view.MotionEvent.ACTION_CANCEL);
        });assertTrue(waitFor(()->activity.uiMotion().idle(),2000));
        main(()->{
            assertEquals(1,clicks[0]);assertEquals(1f,target[0].getScaleX(),.001f);
            navigate("settings");activity.findViewById(R.id.ui_motion).performClick();assertFalse(prefs.bool("ui_motion",true));assertFalse(activity.uiMotion().enabled());
            assertFalse(new AppPrefs(context).bool("ui_motion",true));
            navigate("home");ViewGroup shortcuts=activity.findViewById(R.id.home_shortcuts);
            for(int i=0;i<shortcuts.getChildCount();i++){View card=shortcuts.getChildAt(i);assertEquals(1f,card.getAlpha(),.001f);assertEquals(0f,card.getTranslationY(),.001f);}
            assertTrue(activity.uiMotion().idle());
            call("openDrawer");assertEquals(1f,((View)field("queueOverlay")).getAlpha(),.001f);call("dismissDrawer");assertNull(field("queueOverlay"));
            call("sleepMenu");assertEquals(1f,((android.app.AlertDialog)field("activeDialog")).getWindow().getDecorView().getAlpha(),.001f);
            ((android.app.AlertDialog)field("activeDialog")).dismiss();navigate("settings");activity.findViewById(R.id.ui_motion).performClick();assertTrue(activity.uiMotion().enabled());
        });
    }

    private void sendTouch(View view,long down,int action){
        android.view.MotionEvent event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,Ui.dp(context,24),Ui.dp(context,24),0);
        try{view.dispatchTouchEvent(event);}finally{event.recycle();}
    }

    public void testPlayerTransitionsRestoreLibraryAndHonorMotionSetting()throws Exception{
        main(()->navigate("songs"));assertTrue(waitFor(()->((java.util.List<?>)field("items")).size()>=100,10000));
        main(()->((ListView)field("list")).setSelectionFromTop(22,0));
        assertTrue(waitFor(()->((ListView)field("list")).getFirstVisiblePosition()==22,5000));
        main(()->{
            call("showLyrics");View page=(View)field("nowRoot"),stage=(View)field("playerStage");
            assertTrue(activity.uiMotion().running(page));assertTrue(page.getAlpha()<1);assertTrue(stage.getTranslationY()>0);
            activity.onBackPressed();assertTrue(activity.uiMotion().running(page));assertNotNull(field("pendingPlayerExit"));
            assertEquals(View.VISIBLE,((View)field("shellRoot")).getVisibility());
        });
        assertTrue(waitFor(()->"songs".equals(field("section"))&&((ListView)field("list")).getFirstVisiblePosition()==22,5000));
        main(()->{
            assertNull(field("nowRoot"));assertNull(field("pendingPlayerExit"));
            prefs.setBool("ui_motion",false);call("showLyrics");assertEquals(1f,((View)field("nowRoot")).getAlpha(),.01f);
            activity.onBackPressed();assertNull(field("nowRoot"));assertEquals("songs",field("section"));
        });
    }

    public void testAudioBadgesQualityPickerAndLiveEffects()throws Exception{
        BrowseCache.get(context).invalidate(new EmbyClient(prefs));
        main(()->{prefs.setBool("audio_cache",false);navigate("songs");});
        assertTrue(waitFor(()->((java.util.List<?>)field("items")).size()>=100&&((ListView)field("list")).getChildCount()>0,10000));
        main(()->{
            ListView list=(ListView)field("list");
            android.widget.ListAdapter adapter=list.getAdapter();
            assertTrue(hasText(adapter.getView(0,null,list),"SQ  Fixture Artist"));
            assertTrue(hasText(adapter.getView(1,null,list),"HQ  Fixture Artist"));
            assertTrue(hasText(adapter.getView(2,null,list),"Hi-Res  Fixture Artist"));
            assertTrue(hasText(adapter.getView(4,null,list),"Fixture Artist"));assertFalse(hasText(adapter.getView(4,null,list),"SQ "));
            screenshot("quality-list.png");navigate("settings");
            assertTrue(activity.findViewById(R.id.settings_quality).performClick());
            android.app.AlertDialog picker=(android.app.AlertDialog)field("activeDialog");ListView choices=picker.getListView();
            assertEquals(6,choices.getCount());assertTrue(choices.getAdapter().getItem(0).toString().contains("96 kbps"));
            choices.performItemClick(null,4,4);assertEquals(AudioQuality.MAX,prefs.audioQuality());
        });
        openFixturePlayer();
        assertTrue(waitFor(()->((PlaybackService)field("playback")).isPlaying(),12000));
        main(()->{
            assertEquals("850 kbps",((TextView)field("nowBitrate")).getText().toString());
            assertTrue(((TextView)field("nowQuality")).getText().toString().contains("320 kbps"));
            screenshot("player-compact.png");View page=(View)field("nowRoot");assertTrue(page.findViewById(R.id.player_effects).performClick());
            android.app.AlertDialog menu=(android.app.AlertDialog)field("activeDialog");View controls=menu.getWindow().getDecorView();
            assertTrue(findText(controls,"人声").performClick());assertEquals(AudioEffects.Mode.VOCAL,AudioEffects.Profile.read(prefs).mode);
            assertTrue(findText(controls,"自定义").performClick());
            View low=findDescription(controls,"60 Hz，0 dB");assertNotNull(low);
            low.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_RIGHT,new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_DPAD_RIGHT));
            assertEquals(1,AudioEffects.Profile.read(prefs).gain(0));
            View bass=findDescription(controls,"低音增强，0%");assertNotNull(bass);
            bass.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_RIGHT,new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_DPAD_RIGHT));
            assertEquals(10,AudioEffects.Profile.read(prefs).bass);
            PlaybackService service=(PlaybackService)field("playback");assertTrue(service.isPlaying());assertFalse("播放音乐后应用".equals(service.audioEffectsStatus(AudioEffects.Profile.read(prefs))));
        });
        assertTrue(waitFor(()->!activity.uiMotion().running(((android.app.AlertDialog)field("activeDialog")).getWindow().getDecorView()),3000));
        main(()->{
            android.app.AlertDialog menu=(android.app.AlertDialog)field("activeDialog");screenshot(menu.getWindow().getDecorView(),"audio-effects.png");menu.dismiss();
            PlaybackService service=(PlaybackService)field("playback");service.next(false);
        });
        assertTrue(waitFor(()->{PlaybackService service=(PlaybackService)field("playback");return service.isPlaying()&&"track-1".equals(service.current().id);},12000));
        main(()->{
            PlaybackService service=(PlaybackService)field("playback");assertFalse("播放音乐后应用".equals(service.audioEffectsStatus(AudioEffects.Profile.read(prefs))));
            call("audioEffectsMenu");android.app.AlertDialog menu=(android.app.AlertDialog)field("activeDialog");
            assertTrue(findText(menu.getWindow().getDecorView(),"关闭").performClick());
            assertEquals("音效已关闭",service.audioEffectsStatus(AudioEffects.Profile.read(prefs)));assertTrue(service.isPlaying());menu.dismiss();
        });
    }

    private View findDescription(View view,String label){
        if(label.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){
            View match=findDescription(((ViewGroup)view).getChildAt(i),label);if(match!=null)return match;
        }
        return null;
    }

    private boolean hasText(View view,String contains){
        if(view instanceof android.widget.TextView&&((android.widget.TextView)view).getText().toString().contains(contains))return true;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(hasText(((ViewGroup)view).getChildAt(i),contains))return true;
        return false;
    }

    private View findText(View view,String label){
        if(view instanceof android.widget.TextView&&label.contentEquals(((android.widget.TextView)view).getText()))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){
            View match=findText(((ViewGroup)view).getChildAt(i),label);if(match!=null)return match;
        }
        return null;
    }
}
