package com.nomusic.app;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** Standard RemoteViews widgets supported by API 16 and independent of overlay permission. */
public class MusicWidgetProvider extends AppWidgetProvider {
    public static final class Tall extends MusicWidgetProvider{}
    public static final class Card extends MusicWidgetProvider{}
    public static final class Compact extends MusicWidgetProvider{}
    @Override public void onUpdate(Context context,AppWidgetManager manager,int[] ids){refresh(context,ids);}
    @Override public void onAppWidgetOptionsChanged(Context context,AppWidgetManager manager,int id,Bundle options){refresh(context,new int[]{id});}
    @Override public void onDeleted(Context context,int[] ids){context.sendBroadcast(new Intent(MusicWidgets.REFRESH).setPackage(context.getPackageName()));}
    private void refresh(Context context,int[] ids){
        MusicWidgets.showSaved(context,ids);
        context.sendBroadcast(new Intent(MusicWidgets.REFRESH).setPackage(context.getPackageName()));
    }
}
