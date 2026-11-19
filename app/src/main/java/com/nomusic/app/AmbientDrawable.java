package com.nomusic.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** A static cover-colored background; sampling runs on ImageLoader's worker. */
final class AmbientDrawable extends Drawable {
    static final int DEFAULT=0xff433321;
    private final Paint paint=new Paint();
    private int tint=DEFAULT;
    void setTintColor(int value){if(tint!=value){tint=value;update();invalidateSelf();}}
    @Override protected void onBoundsChange(Rect bounds){update();}
    private void update(){Rect b=getBounds();paint.setShader(new LinearGradient(0,0,Math.max(1,b.width()),b.height()*.6f,new int[]{tint,0xff241e17,Ui.BG},new float[]{0,.5f,1},Shader.TileMode.CLAMP));}
    @Override public void draw(Canvas canvas){canvas.drawRect(getBounds(),paint);}
    @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
    @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
    @Override public int getOpacity(){return PixelFormat.OPAQUE;}
    static int sample(Bitmap bitmap) {
        long r=0,g=0,b=0,weight=0;
        for(int y=0;y<8;y++)for(int x=0;x<8;x++) {
            int color=bitmap.getPixel(Math.min(bitmap.getWidth()-1,x*bitmap.getWidth()/8),Math.min(bitmap.getHeight()-1,y*bitmap.getHeight()/8));
            int rr=Color.red(color),gg=Color.green(color),bb=Color.blue(color);
            int w=1+Math.max(rr,Math.max(gg,bb))-Math.min(rr,Math.min(gg,bb));
            r+=(long)rr*w;g+=(long)gg*w;b+=(long)bb*w;weight+=w;
        }
        float[] hsv=new float[3];Color.colorToHSV(Color.rgb((int)(r/weight),(int)(g/weight),(int)(b/weight)),hsv);
        hsv[1]=Math.max(.10f,Math.min(.35f,hsv[1]));hsv[2]=.26f;
        return Color.HSVToColor(hsv);
    }
}
