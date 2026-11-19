package com.nomusic.app;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.IBinder;
import android.os.PowerManager;

/** Keeps an explicitly requested library cache job alive with a visible, cancellable notification. */
public final class CacheService extends Service {
    private static final String PAUSE="com.nomusic.app.PAUSE_LIBRARY_CACHE";
    private PowerManager.WakeLock wake;
    private CachePreloader preloader;
    private EmbyClient client;
    private String lastLabel="";
    private final BroadcastReceiver changes=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            if(AppPrefs.SESSION_CHANGED.equals(intent.getAction())){client=new EmbyClient(new AppPrefs(context));preloader.cancelStale();}
            if(ConnectivityManager.CONNECTIVITY_ACTION.equals(intent.getAction()))network();
            update();
        }
    };
    @Override public void onCreate(){
        super.onCreate();preloader=CachePreloader.get(this);client=new EmbyClient(new AppPrefs(this));
        wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"NoMusic:library-cache");
        IntentFilter events=new IntentFilter(AudioCache.CHANGED);events.addAction(AppPrefs.SESSION_CHANGED);events.addAction(ConnectivityManager.CONNECTIVITY_ACTION);registerReceiver(changes,events);
        network();update();
    }
    private void network(){
        ConnectivityManager manager=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo active=manager==null?null:manager.getActiveNetworkInfo();preloader.network(active!=null&&active.isConnected());
    }
    private void update(){
        CachePreloader.State state=preloader.state(client);
        if(!state.running){stopSelf();return;}
        if(state.waiting&&wake.isHeld())wake.release();else if(!state.waiting&&!wake.isHeld())wake.acquire();
        String label=state.label();if(label.equals(lastLabel))return;lastLabel=label;
        PendingIntent open=PendingIntent.getActivity(this,51,new Intent(this,MainActivity.class).putExtra("cache_settings",true),PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent pause=PendingIntent.getService(this,52,new Intent(this,CacheService.class).setAction(PAUSE),PendingIntent.FLAG_UPDATE_CURRENT);
        startForeground(51,new Notification.Builder(this).setSmallIcon(R.drawable.ic_notification).setContentTitle("缓存曲库")
                .setContentText(label).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setProgress(Math.max(1,state.total),state.next,state.total==0)
                .addAction(android.R.drawable.ic_media_pause,"暂停",pause).build());
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){if(intent!=null&&PAUSE.equals(intent.getAction())){preloader.pause();stopSelf();}else update();return START_NOT_STICKY;}
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){unregisterReceiver(changes);if(wake!=null&&wake.isHeld())wake.release();stopForeground(true);super.onDestroy();}
}
