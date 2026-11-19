package com.nomusic.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.os.IBinder;
import android.test.InstrumentationTestCase;
import org.json.JSONArray;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Final smoke check on the actual signed release without changing the user's playback state. */
@SuppressWarnings("deprecation")
public final class InstalledReleaseTest extends InstrumentationTestCase {
    public void testInstalledReleaseRetainsSessionQueueAndIsCurrent()throws Exception{
        Context context=getInstrumentation().getTargetContext();AppPrefs prefs=new AppPrefs(context);
        assertEquals(0,context.getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE);
        assertEquals(BuildConfig.VERSION_CODE,context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionCode);
        assertTrue("Saved account was not retained",prefs.hasAccount());
        assertTrue("Saved session was not retained",prefs.loggedIn());
        JSONArray queue=new JSONArray(prefs.store.getString("queue","[]"));assertTrue(queue.length()>0);
        int index=prefs.store.getInt("queue_index",-1);long position=prefs.store.getLong("position",-1);
        CountDownLatch bound=new CountDownLatch(1);AtomicReference<PlaybackService> service=new AtomicReference<>();
        ServiceConnection connection=new ServiceConnection(){
            @Override public void onServiceConnected(ComponentName name,IBinder binder){service.set(((PlaybackService.LocalBinder)binder).service());bound.countDown();}
            @Override public void onServiceDisconnected(ComponentName name){}
        };
        boolean connected=context.bindService(new Intent(context,PlaybackService.class),connection,Context.BIND_AUTO_CREATE);
        try {
            assertTrue(bound.await(5,TimeUnit.SECONDS));
            getInstrumentation().runOnMainSync(()->{
                assertEquals(queue.length(),service.get().queueSize());assertEquals(index,service.get().index());
                assertEquals(position,service.get().position());assertFalse(service.get().wantsPlay());assertFalse(service.get().isPlaying());
            });
        } finally {if(connected)context.unbindService(connection);}
        CountDownLatch checked=new CountDownLatch(1);AtomicReference<String> error=new AtomicReference<>();AtomicReference<UpdateManager.Release> update=new AtomicReference<>();
        UpdateManager.check(context,(value,problem)->{update.set(value);error.set(problem);checked.countDown();});
        assertTrue(checked.await(25,TimeUnit.SECONDS));assertEquals("",error.get());assertNull(update.get());
    }
}
