package com.nomusic.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in live check: credentials are read from instrumentation arguments only. */
@SuppressWarnings("deprecation")
public final class LiveServerTest extends InstrumentationTestCase {
    @Override protected void setUp() throws Exception {
        super.setUp();TlsSocketFactory.initialize(getInstrumentation().getTargetContext());
    }
    public void testLiveLoginLibraryPlaybackAndSeek() throws Exception {
        Bundle args=((NoMusicTestRunner)getInstrumentation()).suppliedArguments();
        assertNotNull("Pass a server argument to run the live check",args.getString("server"));
        Context context=getInstrumentation().getTargetContext();
        AppPrefs prefs=new AppPrefs(context);
        String server=EmbyClient.normalizeServer(args.getString("server"));
        JSONObject login=EmbyClient.authenticate(server,args.getString("username"),args.getString("password"),prefs.deviceId());
        JSONObject user=login.getJSONObject("User");
        prefs.saveSession(login.getString("_Server"),user.getString("Id"),user.getString("Name"),login.getString("AccessToken"));
        EmbyClient client=new EmbyClient(prefs);
        EmbyClient.Page page=client.browse("songs",null,null,"",0);
        assertTrue("No music returned",page.total>0);assertTrue(page.items.size()>0);
        assertTrue("Album browse failed",client.browse("albums",null,null,"",0).total>0);
        assertTrue("Artist browse failed",client.browse("artists",null,null,"",0).total>0);
        client.browse("playlists",null,null,"",0);
        final AtomicReference<PlaybackService> reference=new AtomicReference<>();
        final CountDownLatch ready=new CountDownLatch(1);
        ServiceConnection connection=new ServiceConnection(){
            @Override public void onServiceConnected(ComponentName n,IBinder b){reference.set(((PlaybackService.LocalBinder)b).service());ready.countDown();}
            @Override public void onServiceDisconnected(ComponentName n){ }
        };
        context.startService(new Intent(context,PlaybackService.class));
        boolean bound=context.bindService(new Intent(context,PlaybackService.class),connection,Context.BIND_AUTO_CREATE);
        try {
            assertTrue(ready.await(10,TimeUnit.SECONDS));
            PlaybackService service=reference.get();
            ArrayList<MediaItem> tracks=new ArrayList<>(page.items);
            getInstrumentation().runOnMainSync(()->service.playQueue(tracks,0));
            assertTrue("Live stream did not start: "+service.error(),waitFor(service,40000,false));
            getInstrumentation().runOnMainSync(()->service.seek(30000));
            assertTrue("Live seek did not resume playback",waitFor(service,40000,true));
            getInstrumentation().runOnMainSync(service::pause);
            assertFalse(service.isPlaying());
            assertTrue(service.position()>=30000);
            getInstrumentation().runOnMainSync(()->service.stopPlayback(false));
        } finally {
            PlaybackService service=reference.get();if(service!=null)getInstrumentation().runOnMainSync(()->service.stopPlayback(false));
            if(bound)context.unbindService(connection);
        }
    }

    private boolean waitFor(PlaybackService service,long timeout,boolean seek) {
        long end=SystemClock.elapsedRealtime()+timeout;
        while(SystemClock.elapsedRealtime()<end) {
            AtomicReference<Boolean> playing=new AtomicReference<>(false);
            getInstrumentation().runOnMainSync(()->playing.set(service.isPlaying()&&service.position()>(seek?30300:300)));
            if(playing.get())return true;
            if(!service.error().isEmpty())return false;
            SystemClock.sleep(150);
        }
        return false;
    }
}
