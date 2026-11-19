package com.nomusic.app;

import android.app.Notification;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.Log;

/** Small, detached bitmaps for Binder; the UI artwork cache keeps its original. */
final class SystemArtwork {
    interface NotificationFactory { Notification build(Bitmap icon); }
    interface NotificationSink { void post(Notification notification); }
    private Bitmap source,notificationIcon;
    private boolean omitNotificationArtwork;

    static Bitmap copy(Bitmap source,int maxEdge) {
        if(source==null||source.isRecycled())return null;
        float scale=Math.min(1f,(float)maxEdge/Math.max(source.getWidth(),source.getHeight()));
        int width=Math.max(1,Math.round(source.getWidth()*scale));
        int height=Math.max(1,Math.round(source.getHeight()*scale));
        Bitmap result=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        new Canvas(result).drawBitmap(source,null,new Rect(0,0,width,height),new Paint(Paint.FILTER_BITMAP_FLAG));
        return result;
    }

    void publish(Bitmap cover,NotificationFactory factory,NotificationSink sink) {
        if(source!=cover) {
            source=cover;
            notificationIcon=omitNotificationArtwork?null:copy(cover,96);
        }
        try {sink.post(factory.build(notificationIcon));}
        catch(RuntimeException failure) {
            if(notificationIcon==null)throw failure;
            // Build a fresh Notification: an old RemoteViews can retain its bitmap cache.
            Log.w("NoMusicArtwork","Notification artwork could not be sent; retrying without artwork");
            omitNotificationArtwork=true;notificationIcon=null;source=null;
            sink.post(factory.build(null));
        }
    }
}
