package com.nomusic.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.os.Build;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Short, finite property animations. One owner per activity; no bitmap snapshots or layout animation. */
final class UiMotion {
    private interface Frame { void draw(float fraction); }
    private static final class Track {
        final ValueAnimator animator;
        final Runnable settle;
        Track(ValueAnimator animator,Runnable settle){this.animator=animator;this.settle=settle;}
    }
    private final AppPrefs prefs;
    private final Map<View,Track> running=new IdentityHashMap<>();
    private final Map<View,Boolean> held=new WeakHashMap<>();
    private boolean foreground=true;

    UiMotion(AppPrefs prefs){this.prefs=prefs;}

    boolean enabled(){
        if(!foreground||!prefs.bool("ui_motion",true))return false;
        try{
            float scale=Build.VERSION.SDK_INT>=17?
                    Settings.Global.getFloat(prefs.context().getContentResolver(),"animator_duration_scale",1f):
                    Settings.System.getFloat(prefs.context().getContentResolver(),"animator_duration_scale",1f);
            return scale>0;
        }catch(SecurityException ignored){return true;}
    }

    void foreground(boolean value){foreground=value;if(!value)finishAll();}
    boolean idle(){return running.isEmpty()&&held.isEmpty();}
    boolean running(View view){return running.containsKey(view);}

    private void start(View view,long duration,long delay,Frame frame,Runnable settle,Runnable complete){
        finish(view);
        if(!enabled()){settle.run();if(complete!=null)complete.run();return;}
        ValueAnimator animator=ValueAnimator.ofFloat(0,1);Track track=new Track(animator,settle);
        running.put(view,track);animator.setDuration(duration);animator.setStartDelay(delay);
        animator.setInterpolator(new DecelerateInterpolator(1.5f));
        animator.addUpdateListener(value->frame.draw((Float)value.getAnimatedValue()));
        animator.addListener(new AnimatorListenerAdapter(){
            @Override public void onAnimationEnd(Animator ignored){
                if(running.get(view)!=track)return;
                running.remove(view);frame.draw(1);if(complete!=null)complete.run();
            }
        });
        frame.draw(0);animator.start();
    }

    void finish(View view){
        Track previous=running.remove(view);
        if(previous!=null){previous.animator.cancel();previous.settle.run();}
        if(held.remove(view)!=null){view.setScaleX(1);view.setScaleY(1);}
    }

    void finishTree(View view){
        if(view==null)return;finish(view);
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)finishTree(group.getChildAt(i));}
    }

    void finishAll(){
        for(View view:new ArrayList<>(running.keySet()))finish(view);
        for(View view:new ArrayList<>(held.keySet()))finish(view);
    }

    private static void neutral(View view){view.setAlpha(1);view.setTranslationX(0);view.setTranslationY(0);}

    void page(View view){enter(view,Ui.dp(view.getContext(),10),0,180);}
    void playerOpen(View page,View stage,Runnable complete){
        float distance=Ui.dp(page.getContext(),18);
        start(page,220,0,f->{page.setAlpha(.3f+.7f*f);stage.setTranslationY(distance*(1-f));},
                ()->{neutral(page);neutral(stage);},complete);
    }
    void playerClose(View page,View stage,Runnable complete){
        float fromAlpha=page.getAlpha(),fromY=stage.getTranslationY(),distance=Ui.dp(page.getContext(),24);
        start(page,180,0,f->{page.setAlpha(fromAlpha*(1-f));stage.setTranslationY(fromY+(distance-fromY)*f);},
                ()->{neutral(page);neutral(stage);},complete);
    }
    void card(View view,int index){enter(view,Ui.dp(view.getContext(),8),Math.min(index,3)*30L,180);}
    private void enter(View view,float distance,long delay,long duration){
        start(view,duration,delay,f->{view.setAlpha(.35f+.65f*f);view.setTranslationY(distance*(1-f));},()->neutral(view),null);
    }

    void image(View view){
        if(view.getWindowToken()==null||!view.isShown())return;
        start(view,140,0,f->view.setAlpha(.5f+.5f*f),()->view.setAlpha(1),null);
    }

    void dialog(View view){
        start(view,160,0,f->{view.setAlpha(.6f+.4f*f);view.setScaleX(.98f+.02f*f);view.setScaleY(.98f+.02f*f);},
                ()->{view.setAlpha(1);view.setScaleX(1);view.setScaleY(1);},null);
    }

    void pulse(View view){
        if(!view.isEnabled()||view.getWindowToken()==null||!view.isShown())return;
        start(view,150,0,f->{view.setScaleX(.82f+.18f*f);view.setScaleY(.82f+.18f*f);},
                ()->{view.setScaleX(1);view.setScaleY(1);},null);
    }

    void drawerOpen(View overlay,View panel){
        finishTree(overlay);float distance=Ui.dp(panel.getContext(),48);
        start(overlay,200,0,f->overlay.setAlpha(.45f+.55f*f),()->overlay.setAlpha(1),null);
        start(panel,220,0,f->panel.setTranslationX(distance*(1-f)),()->panel.setTranslationX(0),null);
    }

    void drawerClose(View overlay,View panel,Runnable complete){
        finishTree(overlay);float distance=Ui.dp(panel.getContext(),48);
        start(panel,160,0,f->panel.setTranslationX(distance*f),()->panel.setTranslationX(0),null);
        start(overlay,160,0,f->overlay.setAlpha(1-f),()->overlay.setAlpha(1),complete);
    }

    void bindPress(View view){
        int slop=ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
        view.setOnTouchListener((target,event)->{
            int action=event.getActionMasked();
            if(action==MotionEvent.ACTION_DOWN&&target.isEnabled())press(target,true);
            else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)press(target,false);
            else if(action==MotionEvent.ACTION_MOVE&&held.containsKey(target)&&
                    (event.getX()<-slop||event.getY()<-slop||event.getX()>target.getWidth()+slop||event.getY()>target.getHeight()+slop))press(target,false);
            return false; // Android keeps click, scrolling, long press and accessibility semantics.
        });
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
            @Override public void onViewAttachedToWindow(View ignored){}
            @Override public void onViewDetachedFromWindow(View target){finish(target);}
        });
    }

    private void press(View view,boolean down){
        if(!down&&!held.containsKey(view))return;
        float from=view.getScaleX();finish(view);
        float target=down&&enabled()?.96f:1;
        start(view,down?70:130,0,f->{float scale=from+(target-from)*f;view.setScaleX(scale);view.setScaleY(scale);},
                ()->{view.setScaleX(1);view.setScaleY(1);},null);
        if(down&&enabled())held.put(view,true);
    }
}
