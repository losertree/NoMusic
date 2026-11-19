package com.nomusic.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/** Small, static illustrations drawn locally; no bitmap downloads or animation on old head units. */
final class FeatureArtView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String kind;
    FeatureArtView(Context context,String kind) { super(context);this.kind=kind; }
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);c.save();c.scale(getWidth()/160f,getHeight()/112f);
        paint.setStyle(Paint.Style.FILL);paint.setShader(null);
        if("shuffle".equals(kind)) {
            paint.setColor(0xff14131b);c.drawCircle(98,53,39,paint);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1);paint.setColor(0xff38343f);
            for(int r=17;r<38;r+=6)c.drawCircle(98,53,r,paint);
            paint.setStyle(Paint.Style.FILL);paint.setColor(0xffddc9ab);c.drawCircle(98,53,10,paint);
            paint.setShader(new LinearGradient(32,8,103,94,new int[]{0xffffc78b,0xffff704a,0xffb160c6},null,Shader.TileMode.CLAMP));
            c.save();c.rotate(-7,67,53);c.drawRoundRect(new RectF(28,12,104,94),9,9,paint);c.restore();paint.setShader(null);
            paint.setColor(0xaaffffff);paint.setStrokeWidth(5);paint.setStrokeCap(Paint.Cap.ROUND);c.drawLine(65,35,65,64,paint);c.drawLine(65,35,83,31,paint);c.drawOval(new RectF(53,59,67,71),paint);
        } else if("heart".equals(kind)) {
            paint.setColor(0x18ffffff);c.drawCircle(110,24,51,paint);
            paint.setColor(0xfffee2b8);Path heart=new Path();heart.moveTo(80,84);heart.cubicTo(60,67,44,57,49,40);heart.cubicTo(53,25,71,25,80,39);heart.cubicTo(91,25,108,26,112,41);heart.cubicTo(118,58,101,70,80,84);c.drawPath(heart,paint);
        } else if("recent".equals(kind)) {
            paint.setColor(0x14ffffff);c.drawCircle(132,0,75,paint);
            paint.setColor(0xffb6e3c8);c.drawCircle(80,53,30,paint);
            paint.setColor(0xff247250);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(5);paint.setStrokeCap(Paint.Cap.ROUND);
            c.drawLine(80,36,80,53,paint);c.drawLine(80,53,92,61,paint);
        } else {
            paint.setColor(0x24ffffff);c.drawRoundRect(new RectF(57,16,118,81),8,8,paint);
            paint.setColor(0xffd8cdef);c.drawRoundRect(new RectF(43,28,105,93),8,8,paint);
            paint.setColor(0xff645184);c.drawCircle(74,60,20,paint);paint.setColor(0xffd8cdef);c.drawCircle(74,60,6,paint);
        }
        paint.setStyle(Paint.Style.FILL);paint.setShader(null);paint.setColor(Color.TRANSPARENT);c.restore();
    }
}
