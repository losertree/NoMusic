package com.nomusic.app;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Noninteractive lyric line shown while another app has focus. */
public final class FloatingLyricsService extends Service {
    static volatile boolean insideApp;
    private final Handler handler=new Handler();
    private final ExecutorService disk=Executors.newSingleThreadExecutor();
    private AppPrefs prefs;
    private PlaybackService playback;
    private WindowManager windows;
    private TextView line;
    private LyricsRepository.Request request;
    private LyricsRepository.Result base;
    private LyricsEdits edits=LyricsEdits.EMPTY;
    private LyricsDocument document=LyricsDocument.EMPTY;
    private boolean bound,destroyed;
    private String track="";
    private int generation;

    static boolean allowed(Context context) {return Build.VERSION.SDK_INT<23||Settings.canDrawOverlays(context);}

    private final ServiceConnection connection=new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name,IBinder binder){playback=((PlaybackService.LocalBinder)binder).service();}
        @Override public void onServiceDisconnected(ComponentName name){playback=null;}
    };

    @SuppressWarnings("deprecation")
    @Override public void onCreate() {
        super.onCreate();
        prefs=new AppPrefs(this);
        if(!allowed(this)){stopSelf();return;}
        windows=(WindowManager)getSystemService(WINDOW_SERVICE);
        line=Ui.text(this,"",21,Ui.TEXT,true);
        line.setGravity(Gravity.CENTER);line.setMaxLines(3);Ui.pad(line,18,8,18,8);
        line.setBackground(Ui.shape(0xee171b22,16,this));line.setVisibility(View.GONE);
        WindowManager.LayoutParams params=new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:
                WindowManager.LayoutParams.TYPE_PHONE,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,PixelFormat.TRANSLUCENT);
        params.gravity=Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL;params.y=Ui.dp(this,32);
        line.setMaxWidth(getResources().getDisplayMetrics().widthPixels*4/5);
        try {windows.addView(line,params);}
        catch(RuntimeException failure){line=null;stopSelf();return;}
        bound=bindService(new Intent(this,PlaybackService.class),connection,Context.BIND_AUTO_CREATE);
        handler.post(tick);
    }

    @Override public IBinder onBind(Intent intent){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){return START_NOT_STICKY;}

    private final Runnable tick=new Runnable() {
        @Override public void run() {
            MediaItem item=playback==null?null:playback.current();
            if(!prefs.hasAccount()){stopSelf();return;}
            boolean visible=!insideApp&&item!=null&&playback.isPlaying();
            if(visible) {
                EmbyClient client=new EmbyClient(prefs);
                String identity=client.accountKey()+"\n"+item.id;
                if(!identity.equals(track))load(client,item,identity);
                int selected=document.indexAt(playback.position()+edits.offset);
                if(selected>=0) {
                    LyricsDocument.Cue cue=document.cues.get(selected);
                    String text=cue.original+(prefs.bool("lyrics_translation",true)&&!cue.translation.isEmpty()?"\n"+cue.translation:"");
                    if(!text.contentEquals(line.getText()))line.setText(text);
                    line.setVisibility(View.VISIBLE);
                } else line.setVisibility(View.GONE);
            } else {
                line.setVisibility(View.GONE);
                // Reload local overrides when leaving the player after editing them.
                if(insideApp&&!track.isEmpty()){track="";++generation;}
            }
            handler.postDelayed(this,300);
        }
    };

    private void load(EmbyClient client,MediaItem item,String identity) {
        track=identity;int ticket=++generation;document=LyricsDocument.EMPTY;base=null;edits=LyricsEdits.EMPTY;
        if(request!=null)request.cancel();
        request=LyricsRepository.get(this).load(client,item,false,result->{
            if(ticket==generation){base=result;document=edits.apply(base).document;}
        });
        disk.execute(()->{
            LyricsEdits result=LyricsEdits.read(this,client,item);
            handler.post(()->{
                if(!destroyed&&ticket==generation){edits=result;if(base!=null)document=edits.apply(base).document;}
            });
        });
    }

    @Override public void onDestroy() {
        destroyed=true;handler.removeCallbacksAndMessages(null);
        if(request!=null)request.cancel();
        if(bound)unbindService(connection);
        if(line!=null)windows.removeView(line);
        disk.shutdownNow();super.onDestroy();
    }
}
