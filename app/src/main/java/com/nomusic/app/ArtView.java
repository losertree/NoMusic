package com.nomusic.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Build;
import android.widget.ImageView;

/** Rounded images on API 16 as well as API 19, without outline/elevation APIs. */
final class ArtView extends ImageView {
    private final Path clip=new Path();
    private final float radius;
    ArtView(Context context,int corner) {
        super(context);radius=Ui.dp(context,corner);setScaleType(ScaleType.CENTER_CROP);
        if(Build.VERSION.SDK_INT<18)setLayerType(LAYER_TYPE_SOFTWARE,null);
        setImageDrawable(new CoverDrawable());
    }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh) {
        super.onSizeChanged(w,h,oldw,oldh);clip.reset();clip.addRoundRect(new RectF(0,0,w,h),radius,radius,Path.Direction.CW);
    }
    @Override protected void onDraw(Canvas canvas) {int save=canvas.save();canvas.clipPath(clip);super.onDraw(canvas);canvas.restoreToCount(save);}
}
