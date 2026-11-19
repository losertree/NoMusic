package com.nomusic.app;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** Shrinks artwork before titles or linked metadata can be clipped by large fonts. */
final class CoverPanel extends ViewGroup {
    private final View art,title,links,status;
    private int size,titleHeight,linkHeight,statusHeight,top,inset,artGap,linkGap,statusGap;
    CoverPanel(Context c,View art,View title,View links,View status){super(c);this.art=art;this.title=title;this.links=links;this.status=status;addView(art);addView(title);addView(links);addView(status);}
    private int dp(int v){return Ui.dp(getContext(),v);}
    private int fit(View view,int width,int available){
        view.measure(width,MeasureSpec.makeMeasureSpec(available,MeasureSpec.AT_MOST));
        return view.getMeasuredHeight();
    }
    @Override protected void onMeasure(int ws,int hs) {
        int w=MeasureSpec.getSize(ws),h=MeasureSpec.getSize(hs);inset=Math.min(dp(16),w/2);
        int tw=MeasureSpec.makeMeasureSpec(w-2*inset,MeasureSpec.EXACTLY);
        top=dp(h>dp(360)?16:0);
        int available=h-top;
        titleHeight=fit(title,tw,available);available-=titleHeight;
        linkHeight=fit(links,tw,available);available-=linkHeight;
        linkGap=Math.min(dp(8),available);available-=linkGap;
        statusHeight=status.getVisibility()==GONE?0:fit(status,tw,available);available-=statusHeight;
        statusGap=statusHeight==0?0:Math.min(dp(8),available);available-=statusGap;
        artGap=available>=dp(40)?dp(8):0;
        size=Math.max(0,Math.min(Math.min(dp(352),w-dp(48)),available-artGap));
        if(size==0)artGap=0;
        art.measure(MeasureSpec.makeMeasureSpec(size,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(size,MeasureSpec.EXACTLY));
        setMeasuredDimension(w,h);
    }
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b){
        int x=(getWidth()-size)/2,y=top;art.layout(x,y,x+size,y+size);y+=size+artGap;
        title.layout(inset,y,getWidth()-inset,y+titleHeight);y+=titleHeight+linkGap;
        links.layout(inset,y,getWidth()-inset,y+linkHeight);y+=linkHeight+statusGap;
        if(status.getVisibility()!=GONE)status.layout(inset,y,getWidth()-inset,y+statusHeight);
    }
}
