package com.nomusic.app;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/** Measures the player from actual available dp, including old-system navigation bars. */
final class PlayerStage extends ViewGroup {
    private final View top,art,lyrics,controls,viewToggle,quality,timer;
    private final TextView timerValue;
    private boolean lyricsOnly=true,split;
    private int bodyTop,bodyHeight,left,artWidth,lyricsLeft,dockLeft,dockTop,edge,topY;
    PlayerStage(Context c,View top,View art,View lyrics,View controls,View viewToggle,View quality,View timer,TextView timerValue) {
        super(c);this.top=top;this.art=art;this.lyrics=lyrics;this.controls=controls;this.viewToggle=viewToggle;this.quality=quality;this.timer=timer;
        this.timerValue=timerValue;
        addView(top);addView(art);addView(lyrics);addView(controls);
    }
    void toggleView(){lyricsOnly=!lyricsOnly;viewToggle.setContentDescription(lyricsOnly?"显示封面":"显示歌词");requestLayout();}
    private int dp(int value){return Ui.dp(getContext(),value);}
    private void exact(View v,int w,int h){v.measure(MeasureSpec.makeMeasureSpec(Math.max(0,w),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(Math.max(0,h),MeasureSpec.EXACTLY));}
    @Override protected void onMeasure(int ws,int hs) {
        int w=MeasureSpec.getSize(ws),h=MeasureSpec.getSize(hs);float density=getResources().getDisplayMetrics().density;
        float width=w/density,height=h/density;boolean wide=width>=1100&&height>=640;
        split=width>=800&&height>=480;viewToggle.setVisibility(split?GONE:VISIBLE);
        quality.setVisibility(width<640?GONE:VISIBLE);timer.setVisibility(width<360?GONE:VISIBLE);
        timerValue.setVisibility(width>=600&&timerValue.length()>0?VISIBLE:GONE);
        edge=Math.min(w/2,dp(width<800?16:24));
        int idealDockHeight=dp(PlaybackControls.TIMELINE_DP)+dp(PlaybackControls.ROW_DP)+dp(PlaybackControls.BOTTOM_DP);
        int bottom=Math.min(dp(height>=400?16:12),Math.max(0,h-idealDockHeight));
        int dockHeight=Math.min(idealDockHeight,h-bottom),dockWidth=Math.min(dp(920),w-2*edge);
        dockLeft=(w-dockWidth)/2;dockTop=h-bottom-dockHeight;
        topY=Math.min(dp(height>=400?16:8),Math.max(0,dockTop-dp(56)));
        int topHeight=Math.min(dp(56),dockTop-topY);
        bodyTop=Math.min(dockTop,Math.max(topY+topHeight,dp(height>=640?88:72)));
        bodyHeight=Math.max(0,dockTop-dp(12)-bodyTop);left=Math.min(w/2,dp(wide?72:24));
        int bodyWidth=Math.max(0,w-left-dp(wide?56:24)),gap=dp(wide?48:24);
        artWidth=split?Math.round((bodyWidth-gap)*.3913f):bodyWidth;lyricsLeft=split?left+artWidth+gap:left;
        exact(top,w-2*edge,topHeight);exact(controls,dockWidth,dockHeight);
        art.setVisibility(split||!lyricsOnly?VISIBLE:GONE);lyrics.setVisibility(split||lyricsOnly?VISIBLE:GONE);
        if(art.getVisibility()!=GONE)exact(art,artWidth,bodyHeight);
        if(lyrics.getVisibility()!=GONE)exact(lyrics,split?bodyWidth-artWidth-gap:bodyWidth,bodyHeight);
        setMeasuredDimension(w,h);
    }
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b) {
        top.layout(edge,topY,edge+top.getMeasuredWidth(),topY+top.getMeasuredHeight());
        if(art.getVisibility()!=GONE)art.layout(left,bodyTop,left+art.getMeasuredWidth(),bodyTop+bodyHeight);
        if(lyrics.getVisibility()!=GONE)lyrics.layout(lyricsLeft,bodyTop,lyricsLeft+lyrics.getMeasuredWidth(),bodyTop+bodyHeight);
        controls.layout(dockLeft,dockTop,dockLeft+controls.getMeasuredWidth(),dockTop+controls.getMeasuredHeight());
    }
}
