package com.nomusic.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Captures real property-animation frames on the paused saved queue, without editing any account data. */
@SuppressWarnings("deprecation")
public final class MotionCaptureTest extends InstrumentationTestCase {
    private MainActivity activity;
    private void main(Runnable action){
        AtomicReference<Throwable> failure=new AtomicReference<>();
        getInstrumentation().runOnMainSync(()->{try{action.run();}catch(Throwable error){failure.set(error);}});
        if(failure.get()!=null)throw new AssertionError(failure.get());
    }
    private Object field(String name)throws Exception{Field field=MainActivity.class.getDeclaredField(name);field.setAccessible(true);return field.get(activity);}
    public void testDrawerMotionFramesAndPausedQueue()throws Exception{
        Context context=getInstrumentation().getTargetContext();AppPrefs prefs=new AppPrefs(context);
        String queue=prefs.store.getString("queue","[]");int index=prefs.store.getInt("queue_index",-1);long position=prefs.store.getLong("position",0);
        File directory=new File(context.getFilesDir(),"ui-review/motion-frames");assertTrue(directory.isDirectory()||directory.mkdirs());
        activity=(MainActivity)getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            long deadline=SystemClock.elapsedRealtime()+5000;
            while(SystemClock.elapsedRealtime()<deadline&&(field("playback")==null||!"player".equals(field("section"))))SystemClock.sleep(50);
            PlaybackService service=(PlaybackService)field("playback");assertNotNull(service);
            main(()->{assertFalse(service.wantsPlay());assertTrue(activity.uiMotion().enabled());});
            SystemClock.sleep(400);JSONArray timing=new JSONArray();long start=SystemClock.elapsedRealtime();
            for(int frame=0;frame<22;frame++){
                if(frame==2||frame==13)main(()->assertTrue(activity.findViewById(R.id.queue_open).performClick()));
                final Bitmap[] bitmap={null};
                main(()->{
                    View root=activity.getWindow().getDecorView();bitmap[0]=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);
                    root.draw(new Canvas(bitmap[0]));
                });
                String filename=String.format(java.util.Locale.US,"motion-%02d.png",frame);
                timing.put(new JSONObject().put("file",filename).put("time",SystemClock.elapsedRealtime()-start));
                try(FileOutputStream out=new FileOutputStream(new File(directory,filename))){bitmap[0].compress(Bitmap.CompressFormat.PNG,100,out);}finally{bitmap[0].recycle();}
                SystemClock.sleep(30);
            }
            StorageFiles.writeJson(new File(directory,"timing.json"),new JSONObject().put("frames",timing));
            SystemClock.sleep(300);
            main(()->{
                assertNull(activity.findViewById(R.id.queue_panel));assertTrue(activity.uiMotion().idle());assertFalse(service.wantsPlay());
                assertEquals(index,service.index());assertEquals(position,service.position());
            });
            assertEquals(queue,prefs.store.getString("queue","[]"));
        }finally{
            main(()->activity.finish());getInstrumentation().waitForIdleSync();context.stopService(new Intent(context,PlaybackService.class));
        }
    }
}
