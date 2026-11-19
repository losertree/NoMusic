package com.nomusic.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Parcel;
import android.test.InstrumentationTestCase;
import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@SuppressWarnings("deprecation")
public final class SystemArtworkTest extends InstrumentationTestCase {
    private Context context(){return getInstrumentation().getTargetContext();}
    private Notification notification(Bitmap icon,PendingIntent control) {
        Notification.Builder builder=new Notification.Builder(context()).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Artwork regression").setContentText("Playback controls")
                .setContentIntent(control).setOngoing(true)
                .addAction(android.R.drawable.ic_media_play,"Play",control);
        if(icon!=null)builder.setLargeIcon(icon);
        return builder.build();
    }
    private PendingIntent control() {
        return PendingIntent.getBroadcast(context(),943,new Intent("com.nomusic.app.ARTWORK_TEST").setPackage(context().getPackageName()),PendingIntent.FLAG_UPDATE_CURRENT);
    }
    private int parcelSize(Notification value) {
        Parcel parcel=Parcel.obtain();
        try {value.writeToParcel(parcel,0);return parcel.dataSize();}
        finally {parcel.recycle();}
    }

    public void testLargeSharedCoverStaysIntactAcrossRepeatedSystemDelivery() {
        Bitmap cover=Bitmap.createBitmap(1200,800,Bitmap.Config.ARGB_8888);cover.eraseColor(Color.RED);
        SystemArtwork artwork=new SystemArtwork();PendingIntent control=control();
        NotificationManager manager=(NotificationManager)context().getSystemService(Context.NOTIFICATION_SERVICE);
        AtomicReference<Bitmap> previous=new AtomicReference<>();
        int withoutArtworkSize=parcelSize(notification(null,control));
        int before=new File("/proc/self/fd").list().length;
        try {
            for(int i=0;i<80;i++)artwork.publish(cover,icon->notification(icon,control),value->{
                assertNotSame(cover,value.largeIcon);assertEquals(96,value.largeIcon.getWidth());assertEquals(64,value.largeIcon.getHeight());
                if(previous.get()!=null)assertSame("Unchanged cover should reuse its small system copy",previous.get(),value.largeIcon);
                previous.set(value.largeIcon);
                Parcel parcel=Parcel.obtain();
                try {
                    value.writeToParcel(parcel,0);
                    // Old RemoteViews parcels repeat the bitmap in normal and expanded layouts.
                    assertTrue("Artwork parcel grew too much: total="+parcel.dataSize()+", without artwork="+withoutArtworkSize,
                            parcel.dataSize()-withoutArtworkSize<128*1024);
                    parcel.setDataPosition(0);Notification received=Notification.CREATOR.createFromParcel(parcel);
                    assertEquals(control,received.contentIntent);assertEquals(96,received.largeIcon.getWidth());
                    received.largeIcon.recycle();
                } finally {parcel.recycle();}
                manager.notify(943,value);
            });
            assertFalse(cover.isRecycled());assertEquals(Color.RED,cover.getPixel(1199,799));
            assertTrue("Notification delivery leaked descriptors",new File("/proc/self/fd").list().length<=before+12);
        } finally {manager.cancel(943);control.cancel();cover.recycle();}
    }

    public void testBitmapParcelFailureRetriesFreshNotificationAndKeepsControls() {
        Bitmap cover=Bitmap.createBitmap(420,420,Bitmap.Config.ARGB_8888);
        SystemArtwork artwork=new SystemArtwork();PendingIntent control=control();
        AtomicInteger attempts=new AtomicInteger();AtomicReference<Notification> failed=new AtomicReference<>();
        try {
            artwork.publish(cover,icon->notification(icon,control),value->{
                if(attempts.incrementAndGet()==1) {
                    assertNotNull(value.largeIcon);failed.set(value);
                    throw new RuntimeException("Could not write bitmap to parcel blob.");
                }
                assertNotSame(failed.get(),value);assertNull(value.largeIcon);assertEquals(control,value.contentIntent);
                assertTrue((value.flags&Notification.FLAG_ONGOING_EVENT)!=0);assertNotNull(value.bigContentView);
            });
            assertEquals(2,attempts.get());
            artwork.publish(cover,icon->notification(icon,control),value->{assertNull(value.largeIcon);assertEquals(control,value.contentIntent);});
            assertFalse(cover.isRecycled());
        } finally {control.cancel();cover.recycle();}
    }

    public void testLockscreenOwnsSeparateBoundedCopies() {
        Bitmap cover=Bitmap.createBitmap(420,210,Bitmap.Config.ARGB_8888);cover.eraseColor(Color.BLUE);
        Bitmap first=SystemArtwork.copy(cover,256),second=SystemArtwork.copy(cover,256);
        try {
            assertEquals(256,first.getWidth());assertEquals(128,first.getHeight());assertNotSame(first,second);
            first.recycle();assertFalse(cover.isRecycled());assertFalse(second.isRecycled());
            assertEquals(Color.BLUE,second.getPixel(100,50));assertEquals(Color.BLUE,cover.getPixel(100,50));
        } finally {if(!first.isRecycled())first.recycle();second.recycle();cover.recycle();}
        Bitmap small=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888),copy=SystemArtwork.copy(small,256);
        try {assertNotSame("Metadata ownership must never reach the shared source",small,copy);assertEquals(8,copy.getWidth());}
        finally {copy.recycle();small.recycle();}
    }
}
