package com.nomusic.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.widget.SeekBar;

/** Native SeekBar input/accessibility with an aligned, simple track on Holo-era Android. */
final class TimelineSeekBar extends SeekBar {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private Ui.Palette colors=Ui.NIGHT;
    TimelineSeekBar(Context context) {
        super(context);
        setPadding(Ui.dp(context,8),0,Ui.dp(context,8),0);
        android.graphics.drawable.GradientDrawable thumb=Ui.shape(Ui.TEXT,100,context);
        thumb.setSize(Ui.dp(context,12),Ui.dp(context,12));setThumb(thumb);setThumbOffset(Ui.dp(context,6));
    }
    void setPalette(Ui.Palette value){colors=value;invalidate();}
    @Override protected synchronized void onDraw(Canvas canvas) {
        float left=getPaddingLeft(),right=getWidth()-getPaddingRight(),y=getHeight()/2f;
        float position=left+(right-left)*getProgress()/Math.max(1f,getMax());
        paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeWidth(Ui.dp(getContext(),3));
        paint.setColor(colors.LINE);canvas.drawLine(left,y,right,y,paint);
        paint.setColor(isEnabled()?colors.ACCENT:colors.MUTED);canvas.drawLine(left,y,position,y,paint);
        paint.setColor(isEnabled()?colors.TEXT:colors.MUTED);canvas.drawCircle(position,y,Ui.dp(getContext(),isPressed()?8:6),paint);
        if(isFocused()) {paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(Ui.dp(getContext(),2));canvas.drawCircle(position,y,Ui.dp(getContext(),11),paint);paint.setStyle(Paint.Style.FILL);}
    }
}
