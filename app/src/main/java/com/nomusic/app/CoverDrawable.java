package com.nomusic.app;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** Draws a proportional placeholder at its final size instead of scaling XML insets. */
final class CoverDrawable extends Drawable {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    @Override public void draw(Canvas canvas){Rect b=getBounds();float size=Math.min(b.width(),b.height()),x=b.exactCenterX(),y=b.exactCenterY();paint.setStyle(Paint.Style.FILL);paint.setShader(new LinearGradient(b.left,b.top,b.right,b.bottom,0xff524534,0xff27231f,Shader.TileMode.CLAMP));canvas.drawRect(b,paint);paint.setShader(null);paint.setColor(0xff302a22);canvas.drawCircle(x,y,size*.29f,paint);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(Math.max(1,size*.006f));paint.setColor(0xff8a7556);canvas.drawCircle(x,y,size*.29f,paint);paint.setStyle(Paint.Style.FILL);paint.setColor(Ui.ACCENT);canvas.drawCircle(x,y,size*.055f,paint);}
    @Override public void setAlpha(int value){paint.setAlpha(value);invalidateSelf();}
    @Override public void setColorFilter(ColorFilter value){paint.setColorFilter(value);invalidateSelf();}
    @Override public int getOpacity(){return PixelFormat.OPAQUE;}
}
