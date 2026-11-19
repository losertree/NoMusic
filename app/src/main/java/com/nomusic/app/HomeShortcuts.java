package com.nomusic.app;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Local, density-aware icons and text; wrap the four actions before their labels become cramped. */
final class HomeShortcuts extends ViewGroup {
    interface Action { void open(int index,View source); }
    private int columns,rowHeight;

    HomeShortcuts(Context context,Ui.Palette colors,Action action){
        super(context);setId(R.id.home_shortcuts);
        String[][] entries={{"随心播放","shuffle","发现下一首好歌"},{"我喜欢","heart_filled","收藏的好音乐"},
                {"最近播放","history","重温听过的旋律"},{"离线歌曲","download","随时随地听"}};
        for(int i=0;i<entries.length;i++){
            final int index=i;
            LinearLayout card=Ui.column(context);Ui.pad(card,16,16,16,16);
            card.setBackground(Ui.background(context,colors.SURFACE,colors.SOFT,colors));
            LinearLayout top=Ui.row(context);
            IconView icon=new IconView(context,entries[i][1],i==0?colors.DARK:colors.ACCENT);
            Ui.pad(icon,8,8,8,8);icon.setBackground(Ui.shape(i==0?colors.ACCENT:colors.SOFT,12,context));
            top.addView(icon,new LinearLayout.LayoutParams(Ui.dp(context,44),Ui.dp(context,44)));
            top.addView(new View(context),new LinearLayout.LayoutParams(0,1,1));
            IconView arrow=new IconView(context,"forward",colors.MUTED);top.addView(arrow,new LinearLayout.LayoutParams(Ui.dp(context,18),Ui.dp(context,18)));
            card.addView(top,new LinearLayout.LayoutParams(-1,Ui.dp(context,44)));Ui.gap(card,12);
            TextView title=Ui.text(context,entries[i][0],20,colors.TEXT,true);
            title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);card.addView(title);Ui.gap(card,6);
            TextView subtitle=Ui.text(context,entries[i][2],14,colors.MUTED,false);
            subtitle.setSingleLine(true);subtitle.setEllipsize(TextUtils.TruncateAt.END);card.addView(subtitle);
            card.setGravity(Gravity.TOP);card.setFocusable(true);card.setClickable(true);
            card.setContentDescription(entries[i][0]+"，"+entries[i][2]);card.setOnClickListener(v->action.open(index,card));
            Ui.press(card);
            addView(card);
        }
    }
    @Override protected void onAttachedToWindow(){
        super.onAttachedToWindow();UiMotion motion=Ui.motion(getContext());
        if(motion!=null)for(int i=0;i<getChildCount();i++)motion.card(getChildAt(i),i);
    }
    @Override protected void onDetachedFromWindow(){
        UiMotion motion=Ui.motion(getContext());if(motion!=null)motion.finishTree(this);super.onDetachedFromWindow();
    }

    @Override protected void onMeasure(int widthSpec,int heightSpec){
        int width=MeasureSpec.getSize(widthSpec),gap=Ui.dp(getContext(),12);
        float fontScale=Math.max(1,getResources().getConfiguration().fontScale);
        int minimum=Ui.dp(getContext(),32+Math.max(112,100*fontScale));
        columns=width>=minimum*4+gap*3?4:width>=minimum*2+gap?2:1;
        int cellWidth=Math.max(0,(width-gap*(columns-1))/columns);rowHeight=0;
        for(int i=0;i<getChildCount();i++){
            View card=getChildAt(i);card.measure(MeasureSpec.makeMeasureSpec(cellWidth,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));
            rowHeight=Math.max(rowHeight,card.getMeasuredHeight());
        }
        for(int i=0;i<getChildCount();i++)getChildAt(i).measure(MeasureSpec.makeMeasureSpec(cellWidth,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(rowHeight,MeasureSpec.EXACTLY));
        int rows=(getChildCount()+columns-1)/columns;
        setMeasuredDimension(resolveSize(width,widthSpec),resolveSize(rows*rowHeight+Math.max(0,rows-1)*gap,heightSpec));
    }

    @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom){
        int gap=Ui.dp(getContext(),12);
        for(int i=0;i<getChildCount();i++){
            View card=getChildAt(i);int x=(i%columns)*(card.getMeasuredWidth()+gap),y=(i/columns)*(rowHeight+gap);
            card.layout(x,y,x+card.getMeasuredWidth(),y+rowHeight);
        }
    }
}
