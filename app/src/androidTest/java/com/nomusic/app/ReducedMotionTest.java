package com.nomusic.app;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.test.InstrumentationTestCase;
import android.view.View;
import java.util.concurrent.atomic.AtomicReference;

/** Run explicitly with the device's animator_duration_scale set to zero, restoring it afterwards. */
@SuppressWarnings("deprecation")
public final class ReducedMotionTest extends InstrumentationTestCase {
    public void testSystemScaleDisablesEffectsAndCompletesDrawerExit()throws Exception{
        Context context=getInstrumentation().getTargetContext();AppPrefs prefs=new AppPrefs(context);
        float scale=Build.VERSION.SDK_INT>=17?Settings.Global.getFloat(context.getContentResolver(),"animator_duration_scale",1):
                Settings.System.getFloat(context.getContentResolver(),"animator_duration_scale",1);
        assertEquals("Run with the system animator scale disabled",0f,scale,.001f);
        boolean existed=prefs.store.contains("ui_motion"),saved=prefs.bool("ui_motion",true);
        AtomicReference<Throwable> failure=new AtomicReference<>();
        try{
            prefs.setBool("ui_motion",true);
            getInstrumentation().runOnMainSync(()->{
                try{
                    UiMotion motion=new UiMotion(prefs);assertFalse(motion.enabled());
                    View overlay=new View(context),panel=new View(context);int[] closed={0};
                    int[] playerClosed={0};
                    motion.page(overlay);motion.playerOpen(overlay,panel,null);motion.playerClose(overlay,panel,()->playerClosed[0]++);
                    assertEquals(1,playerClosed[0]);assertEquals(0f,panel.getTranslationY(),.001f);
                    motion.card(panel,3);motion.dialog(overlay);
                    motion.drawerOpen(overlay,panel);motion.drawerClose(overlay,panel,()->closed[0]++);
                    assertTrue(motion.idle());assertEquals(1,closed[0]);assertEquals(1f,overlay.getAlpha(),.001f);
                    assertEquals(0f,panel.getTranslationX(),.001f);assertEquals(0f,panel.getTranslationY(),.001f);assertEquals(1f,overlay.getScaleX(),.001f);
                }catch(Throwable error){failure.set(error);}
            });
            if(failure.get()!=null)throw new AssertionError(failure.get());
        }finally{
            if(existed)prefs.store.edit().putBoolean("ui_motion",saved).commit();else prefs.store.edit().remove("ui_motion").commit();
        }
    }
}
