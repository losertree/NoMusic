package com.nomusic.app;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** Positions each touch target from the dock's measured width, without overlapping groups. */
final class PlaybackControls extends ViewGroup {
    static final int TIMELINE_DP=40,ROW_DP=56,BOTTOM_DP=8;
    private final View favorite,mode,previous,play,next,download,queue;
    private final View[] keys;
    private int gap,edge,side;
    PlaybackControls(Context c,View favorite,View mode,View previous,View play,View next,View download,View queue){
        super(c);this.favorite=favorite;this.mode=mode;this.previous=previous;this.play=play;this.next=next;this.download=download;this.queue=queue;
        keys=new View[]{favorite,mode,previous,play,next,download,queue};
        for(View view:keys)addView(view);
    }
    private int dp(int n){return Ui.dp(getContext(),n);}
    @Override protected void onMeasure(int ws,int hs){
        int w=MeasureSpec.getSize(ws),h=MeasureSpec.getSize(hs);edge=Math.min(w/2,dp(w>=dp(600)?20:w>=dp(360)?12:8));
        int space=w-2*edge;
        boolean all=space>=7*dp(48)+2*dp(8),hasMode=all||space>=5*dp(48);
        favorite.setVisibility(all?VISIBLE:GONE);download.setVisibility(all?VISIBLE:GONE);mode.setVisibility(hasMode?VISIBLE:GONE);
        int count=all?7:hasMode?5:4;
        side=Math.min(dp(48),space/count);
        gap=Math.min(dp(20),(space-count*side)/2);
        for(View view:keys)if(view.getVisibility()==VISIBLE)
            view.measure(MeasureSpec.makeMeasureSpec(side,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(Math.min(dp(48),h),MeasureSpec.EXACTLY));
        setMeasuredDimension(w,h);
    }
    private void place(View view,int x){if(view.getVisibility()!=GONE){int top=(getMeasuredHeight()-view.getMeasuredHeight())/2;view.layout(x,top,x+view.getMeasuredWidth(),top+view.getMeasuredHeight());}}
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b){
        int leftCount=(favorite.getVisibility()==VISIBLE?1:0)+(mode.getVisibility()==VISIBLE?1:0);
        int rightCount=download.getVisibility()==VISIBLE?2:1;
        int centerWidth=3*side+gap*2,center=(getWidth()-centerWidth)/2;
        center=Math.max(edge+leftCount*side,Math.min(center,getWidth()-edge-rightCount*side-centerWidth));
        place(previous,center);place(play,center+side+gap);place(next,center+2*(side+gap));
        place(favorite,edge);place(mode,edge+(favorite.getVisibility()==VISIBLE?side:0));
        place(queue,getWidth()-edge-side);place(download,getWidth()-edge-side*2);
    }
}
