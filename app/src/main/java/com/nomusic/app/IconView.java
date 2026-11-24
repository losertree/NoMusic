package com.nomusic.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Code-drawn icons render on API 16 without vector-drawable or font dependencies. */
public final class IconView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private String icon;
    private int color;
    public IconView(Context context,String icon,int color) { super(context); this.icon=icon; this.color=color; }
    public void setIcon(String icon,int color) {
        if(this.icon.equals(icon)&&this.color==color)return;
        boolean changed=!this.icon.equals(icon);
        boolean feedback=changed&&((("play".equals(this.icon)||"pause".equals(this.icon))&&("play".equals(icon)||"pause".equals(icon)))||
                icon.startsWith("heart")||icon.startsWith("toggle_")||icon.startsWith("translation")||icon.startsWith("repeat")||"shuffle".equals(icon));
        this.icon=icon;this.color=color;invalidate();
        UiMotion motion=Ui.motion(getContext());if(feedback&&motion!=null)motion.pulse(this);
    }
    @Override protected void onDraw(Canvas original) {
        super.onDraw(original);
        original.save();
        float width=getWidth()-getPaddingLeft()-getPaddingRight();
        float height=getHeight()-getPaddingTop()-getPaddingBottom();
        float size=Math.min(width,height);
        original.translate(getPaddingLeft()+(width-size)/2f,getPaddingTop()+(height-size)/2f); original.scale(size/32f,size/32f);
        Canvas c=original;
        paint.setColor(color); paint.setStrokeWidth(2); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND); paint.setStyle(Paint.Style.STROKE);
        Path p=new Path();
        switch(icon) {
            case "microphone":
                c.drawRoundRect(new RectF(12,3,20,20),4,4,paint);c.drawArc(new RectF(8,9,24,25),0,180,false,paint);c.drawLine(8,14,8,17,paint);c.drawLine(24,14,24,17,paint);c.drawLine(16,25,16,29,paint);c.drawLine(11,29,21,29,paint);break;
            case "pronunciation":
                c.drawLine(4,24,9,8,paint);c.drawLine(9,8,15,24,paint);c.drawLine(6,19,13,19,paint);c.drawArc(new RectF(17,9,24,23),-75,150,false,paint);c.drawArc(new RectF(19,4,30,28),-75,150,false,paint);break;
            case "toggle_on": case "toggle_off":
                c.drawRoundRect(new RectF(1,7,31,25),9,9,paint);paint.setStyle(Paint.Style.FILL);c.drawCircle("toggle_on".equals(icon)?23:9,16,6,paint);break;
            case "close": c.drawLine(7,7,25,25,paint);c.drawLine(25,7,7,25,paint);break;
            case "forward": p.moveTo(12,7);p.lineTo(21,16);p.lineTo(12,25);c.drawPath(p,paint);break;
            case "history": c.drawArc(new RectF(5,5,27,27),-135,315,false,paint);p.moveTo(4,4);p.lineTo(4,12);p.lineTo(12,12);c.drawPath(p,paint);c.drawLine(16,10,16,16,paint);c.drawLine(16,16,22,19,paint);break;
            case "expand": p.moveTo(12,5);p.lineTo(5,5);p.lineTo(5,12);p.moveTo(20,5);p.lineTo(27,5);p.lineTo(27,12);p.moveTo(5,20);p.lineTo(5,27);p.lineTo(12,27);p.moveTo(20,27);p.lineTo(27,27);p.lineTo(27,20);c.drawPath(p,paint);break;
            case "sort": c.drawLine(7,4,7,27,paint);p.moveTo(3,23);p.lineTo(7,27);p.lineTo(11,23);c.drawPath(p,paint);c.drawLine(16,7,28,7,paint);c.drawLine(16,15,25,15,paint);c.drawLine(16,23,21,23,paint);break;
            case "music": c.drawLine(12,23,12,7,paint);c.drawLine(12,7,27,4,paint);c.drawLine(27,4,27,20,paint);c.drawLine(12,12,27,9,paint);c.drawOval(new RectF(4,21,12,28),paint);c.drawOval(new RectF(19,18,27,25),paint);break;
            case "folder": p.moveTo(4,10);p.lineTo(4,6);p.lineTo(13,6);p.lineTo(17,10);p.lineTo(28,10);p.lineTo(28,26);p.lineTo(4,26);p.close();c.drawPath(p,paint);break;
            case "downloading": c.drawArc(new RectF(4,4,28,28),-90,265,false,paint);c.drawLine(16,8,16,21,paint);p.moveTo(11,16);p.lineTo(16,21);p.lineTo(21,16);c.drawPath(p,paint);break;
            case "home": p.moveTo(4,14);p.lineTo(16,4);p.lineTo(28,14);p.moveTo(7,12);p.lineTo(7,27);p.lineTo(13,27);p.lineTo(13,19);p.lineTo(20,19);p.lineTo(20,27);p.lineTo(25,27);p.lineTo(25,12);c.drawPath(p,paint);break;
            case "artist": c.drawCircle(16,10,5,paint);c.drawArc(new RectF(6,17,26,32),180,180,false,paint);c.drawLine(6,25,6,28,paint);c.drawLine(26,25,26,28,paint);break;
            case "lyrics": c.drawRoundRect(new RectF(5,4,27,28),3,3,paint);for(int y=10;y<=22;y+=6)c.drawLine(10,y,y==22?18:22,y,paint);break;
            case "translation": case "translation_on": case "translation_none":
                c.drawLine(4,8,18,8,paint);c.drawLine(11,5,11,8,paint);c.drawLine(16,9,13,15,paint);
                c.drawLine(13,15,5,22,paint);c.drawLine(7,12,10,16,paint);c.drawLine(10,16,15,19,paint);
                p.moveTo(17,27);p.lineTo(22,14);p.lineTo(28,27);c.drawPath(p,paint);c.drawLine(19,23,26,23,paint);
                if("translation_on".equals(icon)){p.reset();p.moveTo(22,5);p.lineTo(25,8);p.lineTo(30,2);c.drawPath(p,paint);}
                if("translation_none".equals(icon))c.drawLine(4,28,29,3,paint);break;
            case "more": paint.setStyle(Paint.Style.FILL);for(int x=7;x<=25;x+=9)c.drawCircle(x,16,1.8f,paint);break;
            case "filter": for(int y=8;y<=24;y+=8){c.drawLine(5,y,27,y,paint);int x=y==16?11:21;c.drawCircle(x,y,3,paint);}break;
            case "info": c.drawCircle(16,16,11,paint);c.drawCircle(16,10,0.7f,paint);c.drawLine(16,15,16,22,paint);break;
            case "download": c.drawLine(16,4,16,21,paint);p.moveTo(10,15);p.lineTo(16,21);p.lineTo(22,15);c.drawPath(p,paint);c.drawLine(7,27,25,27,paint);break;
            case "check": p.moveTo(6,16);p.lineTo(13,23);p.lineTo(26,9);c.drawPath(p,paint);break;
            case "down": p.moveTo(6,12);p.lineTo(16,21);p.lineTo(26,12);c.drawPath(p,paint);break;
            case "equalizer":
                for(int i=0;i<3;i++){
                    int x=6+10*i,y=i==0?12:i==1?21:9;
                    if(y-4>5)c.drawLine(x,5,x,y-4,paint);c.drawLine(x,y+4,x,27,paint);c.drawCircle(x,y,3,paint);
                }
                break;
            case "library": c.drawRoundRect(new RectF(5,5,12,27),2,2,paint);c.drawRoundRect(new RectF(16,5,27,27),2,2,paint);c.drawLine(20,10,23,10,paint);break;
            case "recent": c.drawCircle(16,16,11,paint);c.drawLine(16,9,16,16,paint);c.drawLine(16,16,22,19,paint);break;
            case "heart": case "heart_filled":
                p.moveTo(16,27);p.cubicTo(12,23,3,17,4,10);p.cubicTo(5,2,13,3,16,9);p.cubicTo(20,3,28,3,28,11);p.cubicTo(28,18,20,24,16,27);
                if("heart_filled".equals(icon)) paint.setStyle(Paint.Style.FILL); c.drawPath(p,paint);break;
            case "queue": for(int y=8;y<=24;y+=8){c.drawCircle(5,y,1,paint);c.drawLine(11,y,27,y,paint);}break;
            case "settings": c.drawCircle(16,16,5,paint);c.drawCircle(16,16,11,paint);for(int i=0;i<8;i++){c.save();c.rotate(i*45,16,16);c.drawLine(16,2,16,5,paint);c.restore();}break;
            case "search": c.drawCircle(13,13,8,paint);c.drawLine(19,19,27,27,paint);break;
            case "play": paint.setStyle(Paint.Style.FILL);p.moveTo(11,6);p.lineTo(27,16);p.lineTo(11,26);p.close();c.drawPath(p,paint);break;
            case "pause": paint.setStyle(Paint.Style.FILL);c.drawRoundRect(new RectF(8,6,13,26),1,1,paint);c.drawRoundRect(new RectF(20,6,25,26),1,1,paint);break;
            case "next": paint.setStyle(Paint.Style.FILL);p.moveTo(7,7);p.lineTo(22,16);p.lineTo(7,25);p.close();c.drawPath(p,paint);c.drawRect(24,7,27,25,paint);break;
            case "previous": paint.setStyle(Paint.Style.FILL);p.moveTo(25,7);p.lineTo(10,16);p.lineTo(25,25);p.close();c.drawPath(p,paint);c.drawRect(5,7,8,25,paint);break;
            case "shuffle": p.moveTo(4,8);p.lineTo(9,8);p.cubicTo(15,8,17,24,23,24);p.lineTo(28,24);c.drawPath(p,paint);p.reset();p.moveTo(4,24);p.lineTo(9,24);p.cubicTo(15,24,17,8,23,8);p.lineTo(28,8);c.drawPath(p,paint);c.drawLine(24,4,28,8,paint);c.drawLine(24,12,28,8,paint);c.drawLine(24,20,28,24,paint);c.drawLine(24,28,28,24,paint);break;
            case "repeat": c.drawArc(new RectF(5,7,27,25),190,160,false,paint);c.drawArc(new RectF(5,7,27,25),10,160,false,paint);c.drawLine(25,5,28,12,paint);c.drawLine(21,12,28,12,paint);c.drawLine(4,20,11,20,paint);c.drawLine(4,20,7,27,paint);break;
            case "repeat_one": c.drawArc(new RectF(5,7,27,25),190,160,false,paint);c.drawArc(new RectF(5,7,27,25),10,160,false,paint);c.drawLine(25,5,28,12,paint);c.drawLine(21,12,28,12,paint);c.drawLine(4,20,11,20,paint);c.drawLine(4,20,7,27,paint);c.drawLine(14,13,17,11,paint);c.drawLine(17,11,17,21,paint);break;
            case "back": p.moveTo(21,6);p.lineTo(11,16);p.lineTo(21,26);c.drawPath(p,paint);break;
            case "refresh": c.drawArc(new RectF(5,5,27,27),30,300,false,paint);c.drawLine(27,4,27,12,paint);c.drawLine(19,12,27,12,paint);break;
            default: c.drawCircle(16,16,12,paint);c.drawCircle(16,16,4,paint);c.drawLine(22,5,26,10,paint);
        }
        original.restore();
    }
}
