package com.nomusic.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import org.json.JSONArray;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Uses saved real lyric cache; preserves every preference and does not edit the server. */
@SuppressWarnings("deprecation")
public final class LiveUiCaptureTest extends InstrumentationTestCase {
    private MainActivity activity;
    private void main(Runnable call){
        java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
        getInstrumentation().runOnMainSync(()->{try{call.run();}catch(Throwable error){failure.set(error);}});
        Throwable error=failure.get();if(error instanceof AssertionError)throw (AssertionError)error;if(error instanceof RuntimeException)throw (RuntimeException)error;if(error!=null)throw new AssertionError(error);
    }
    private Object field(String name)throws Exception{Field field=MainActivity.class.getDeclaredField(name);field.setAccessible(true);return field.get(activity);}
    public void testSavedBilingualFullScreenForReview()throws Exception{
        captureSavedTrack(false,60000,"player-live.png");
    }
    public void testSavedAimeiDoesNotGainLyricSymbols()throws Exception{
        captureSavedTrack(true,10000,"player-aimei.png");
    }
    private void captureSavedTrack(boolean aimei,long position,String filename)throws Exception{
        Context context=getInstrumentation().getTargetContext();AppPrefs prefs=new AppPrefs(context);
        Map<String,?> saved=new HashMap<>(prefs.store.getAll());
        EmbyClient client=new EmbyClient(prefs);assertTrue(prefs.hasAccount());
        JSONArray queue=new JSONArray(prefs.store.getString("queue","[]"));
        MediaItem bilingual=null;
        Method key=LyricsRepository.class.getDeclaredMethod("key",EmbyClient.class,MediaItem.class);key.setAccessible(true);
        for(int i=0;i<queue.length();i++) {
            MediaItem item=new MediaItem(queue.getJSONObject(i));
            File file=new File(context.getCacheDir(),"lyrics-v2/"+key.invoke(null,client,item)+".json");
            if(!file.isFile())continue;
            String text=StorageFiles.readJson(file,512*1024).optString("Text");
            if(aimei?(item.name.contains("暧昧")||item.name.contains("曖昧")):LyricsDocument.parse(text).hasTranslation){bilingual=item;break;}
        }
        assertNotNull(aimei?"No cached Aimei track in the existing queue":"No real bilingual track in the existing queue cache",bilingual);
        final MediaItem selected=bilingual;
        try {
            main(()->prefs.setBool("check_updates",false));
            activity=(MainActivity)getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            long deadline=SystemClock.elapsedRealtime()+5000;
            while(SystemClock.elapsedRealtime()<deadline&&field("playback")==null)SystemClock.sleep(100);
            PlaybackService playback=(PlaybackService)field("playback");assertNotNull(playback);
            main(()->{
                playback.stopPlayback(true);playback.enqueue(selected,false);playback.seek(position);
                try {Method show=MainActivity.class.getDeclaredMethod("showLyrics");show.setAccessible(true);show.invoke(activity);}
                catch(Exception e){throw new AssertionError(e);}
            });
            SystemClock.sleep(1200);
            main(()->{
                if(!aimei)ViewCheck.check(activity);
                else try {
                    LyricsRepository.Result source=(LyricsRepository.Result)field("baseLyrics");assertNotNull(source);
                    android.widget.ListView rows=(android.widget.ListView)activity.findViewById(R.id.lyrics_list);
                    boolean blank=false;int displayed=0;
                    for(int i=0;i<source.document.cues.size();i++) {
                        LyricsDocument.Cue cue=source.document.cues.get(i);
                        if(cue.original.trim().isEmpty()&&cue.translation.trim().isEmpty()){blank=true;continue;}
                        android.widget.LinearLayout row=(android.widget.LinearLayout)rows.getAdapter().getView(displayed++,null,rows);
                        assertEquals(cue.original,((android.widget.TextView)row.getChildAt(0)).getText().toString());
                    }
                    assertTrue("The real track should exercise empty timestamp cues",blank);
                    assertEquals(displayed,rows.getAdapter().getCount());
                    assertEquals(android.view.View.GONE,activity.findViewById(R.id.lyrics_status).getVisibility());
                } catch(Exception e){throw new AssertionError(e);}
                android.view.View root=activity.getWindow().getDecorView();
                android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(root.getWidth(),root.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);
                root.draw(new android.graphics.Canvas(bitmap));
                try {
                    File directory=new File(context.getFilesDir(),"ui-review");directory.mkdirs();
                    try(java.io.FileOutputStream out=new java.io.FileOutputStream(new File(directory,filename))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}
                } catch(Exception e){throw new AssertionError(e);} finally{bitmap.recycle();}
            });
        } finally {
            if(activity!=null)main(()->{
                try {PlaybackService playback=(PlaybackService)field("playback");if(playback!=null)playback.stopPlayback(true);}
                catch(Exception e){throw new AssertionError(e);}
                activity.finish();
            });
            context.stopService(new Intent(context,PlaybackService.class));getInstrumentation().waitForIdleSync();SystemClock.sleep(300);
            SharedPreferences.Editor restore=prefs.store.edit().clear();
            for(Map.Entry<String,?> entry:saved.entrySet()){
                String name=entry.getKey();Object value=entry.getValue();
                if(value instanceof String)restore.putString(name,(String)value);
                else if(value instanceof Long)restore.putLong(name,(Long)value);
                else if(value instanceof Integer)restore.putInt(name,(Integer)value);
                else if(value instanceof Boolean)restore.putBoolean(name,(Boolean)value);
                else if(value instanceof Float)restore.putFloat(name,(Float)value);
            }
            restore.commit();
        }
    }
    private static final class ViewCheck {
        static void check(MainActivity activity){
            assertNotNull(activity.findViewById(R.id.lyrics_translation));
            assertTrue(activity.findViewById(R.id.lyrics_translation).isEnabled());
        }
    }
}
