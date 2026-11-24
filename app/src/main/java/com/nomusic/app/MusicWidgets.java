package com.nomusic.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.RemoteViews;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Widgets subscribe to the existing player clock; no extra playback service or wake-up timer. */
final class MusicWidgets {
    static final String REFRESH="com.nomusic.app.WIDGET_REFRESH";
    private static final Class<?>[] PROVIDERS={MusicWidgetProvider.Tall.class,MusicWidgetProvider.Card.class,MusicWidgetProvider.Compact.class};
    private final PlaybackService playback;
    private final AppPrefs prefs;
    private final Handler main=new Handler();
    private final ExecutorService disk=Executors.newSingleThreadExecutor();
    private final SharedPreferences.OnSharedPreferenceChangeListener settings=(store,key)->{
        if(key!=null&&(key.startsWith("pronunciation_language_")||key.equals("widget_pronunciation")||key.equals("lyrics_translation")||key.equals("theme_color")||key.equals("cover_colors")))refresh();
    };
    private final List<Integer> ids=new ArrayList<>();
    private LyricsRepository.Request request;
    private PronunciationRepository.Request readingRequest;
    private LyricsRepository.Result base;
    private LyricsEdits edits=LyricsEdits.EMPTY;
    private LyricsDocument document=LyricsDocument.EMPTY;
    private PronunciationRepository.Result readings;
    private String track="",lastSignature="";
    private int generation;
    private boolean closed;
    private Bitmap lastCover,thumbnail;
    private int tint=AmbientDrawable.DEFAULT;
    MusicWidgets(PlaybackService service){playback=service;prefs=new AppPrefs(service);prefs.store.registerOnSharedPreferenceChangeListener(settings);findIds();}
    private void findIds(){ids.clear();AppWidgetManager manager=AppWidgetManager.getInstance(playback);for(Class<?> provider:PROVIDERS)for(int id:manager.getAppWidgetIds(new ComponentName(playback,provider)))ids.add(id);}
    void refresh(){findIds();track="";lastSignature="";update(true);}

    void update(boolean force){
        if(closed||ids.isEmpty())return;
        MediaItem item=playback.current();EmbyClient client=new EmbyClient(prefs);
        String identity=item==null?"":PronunciationRepository.scope(client,item);
        if(!identity.equals(track))load(client,item,identity);
        Bitmap cover=playback.cover();
        if(cover!=lastCover){lastCover=cover;thumbnail=cover==null?null:SystemArtwork.copy(cover,192);tint=cover==null?AmbientDrawable.DEFAULT:AmbientDrawable.sample(cover);force=true;}
        long position=playback.position(),duration=playback.duration();
        LyricLines lines=lines(document,position+edits.offset);
        String reading="";if(prefs.bool("widget_pronunciation",true)&&readings!=null)reading=readings.line(lines.index).text;
        String translation=prefs.bool("lyrics_translation",true)?lines.translation:"";
        String status=playback.isBuffering()?"正在缓冲…":playback.error().isEmpty()?"":playback.error();
        String current=!lines.current.isEmpty()?lines.current:!document.timed&&!document.cues.isEmpty()?document.cues.get(0).original:item==null?"好音乐，在路上":base==null?"正在读取歌词…":base.text.isEmpty()?"轻触打开播放器":"等待歌词开始";
        int progress=duration>0?(int)(Math.min(position,duration)*1000/duration):0;
        String signature=track+"|"+playback.wantsPlay()+"|"+position/1000+"|"+current+"|"+reading+"|"+translation+"|"+status;
        if(!force&&signature.equals(lastSignature))return;lastSignature=signature;
        int accent=palette().ACCENT;
        for(int id:ids){
            int layout=layout(playback,id);RemoteViews view=new RemoteViews(playback.getPackageName(),layout);
            if(force){configure(playback,id,view,layout);view.setImageViewBitmap(R.id.widget_cover,thumbnail);if(thumbnail==null)view.setImageViewResource(R.id.widget_cover,R.drawable.ic_launcher);}
            bindText(view,item,playback.wantsPlay(),current,lines.previous,lines.next,translation,reading,accent,progress,position,duration,status,layout);
            int[] size=dimensions(AppWidgetManager.getInstance(playback).getAppWidgetOptions(id));resize(playback,view,layout,size[0],size[1]);
            try{if(force)AppWidgetManager.getInstance(playback).updateAppWidget(id,view);else AppWidgetManager.getInstance(playback).partiallyUpdateAppWidget(id,view);}
            catch(RuntimeException ignored){lastSignature="";}
        }
    }
    private Ui.Palette palette(){Ui.Palette base=Ui.themePalette(prefs,false);return prefs.bool("cover_colors",true)?Ui.coverPalette(base,tint):base;}
    private void load(EmbyClient client,MediaItem item,String identity){
        track=identity;int ticket=++generation;document=LyricsDocument.EMPTY;edits=LyricsEdits.EMPTY;base=null;readings=null;
        if(request!=null)request.cancel();if(readingRequest!=null)readingRequest.cancel();
        if(item==null)return;
        request=LyricsRepository.get(playback).load(client,item,false,result->{if(!closed&&ticket==generation){base=result;apply(ticket,identity);}});
        disk.execute(()->{
            LyricsEdits result=LyricsEdits.read(playback,client,item);
            main.post(()->{if(!closed&&ticket==generation){edits=result;apply(ticket,identity);}});
        });
    }
    private String readingSource="";
    private void apply(int ticket,String identity){
        if(base==null)return;LyricsRepository.Result result=edits.apply(base);document=result.document;
        if(prefs.bool("widget_pronunciation",true)&&!result.text.isEmpty()){
            PronunciationEngine.Language language=PronunciationRepository.language(prefs,identity);
            String key=PronunciationRepository.cacheKey(identity,result.text,language);
            if(!key.equals(readingSource)||readings==null){
                readingSource=key;if(readingRequest!=null)readingRequest.cancel();
                readingRequest=PronunciationRepository.get(playback).load(identity,result.text,language,value->{if(!closed&&ticket==generation&&key.equals(readingSource)){readings=value;update(true);}});
            }
        }
        update(true);
    }
    static final class LyricLines{
        final int index;final String previous,current,next,translation;
        LyricLines(int index,String previous,String current,String next,String translation){this.index=index;this.previous=previous;this.current=current;this.next=next;this.translation=translation;}
    }
    static LyricLines lines(LyricsDocument document,long position){
        int at=document.indexAt(position);String previous="",current="",next="",translation="";
        if(!document.timed)at=document.cues.isEmpty()?-1:0;
        // An empty timestamp is an intentional instrumental break, not a repeated previous line.
        if(at>=0){LyricsDocument.Cue cue=document.cues.get(at);current=cue.original;translation=cue.translation;}
        for(int i=at-1;i>=0;i--)if(!document.cues.get(i).original.trim().isEmpty()){previous=document.cues.get(i).original;break;}
        for(int i=at+1;i<document.cues.size();i++)if(!document.cues.get(i).original.trim().isEmpty()){next=document.cues.get(i).original;break;}
        return new LyricLines(at,previous,current,next,translation);
    }
    static int layout(Context context,int id){
        android.appwidget.AppWidgetProviderInfo info=AppWidgetManager.getInstance(context).getAppWidgetInfo(id);
        String name=info==null?"":info.provider.getClassName();
        return name.endsWith("$Compact")?R.layout.widget_compact:name.endsWith("$Card")?R.layout.widget_card:R.layout.widget_tall;
    }
    static int[] dimensions(Bundle options){return new int[]{Math.max(220,options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,360)),Math.max(64,options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,560))};}
    static void configure(Context context,int id,RemoteViews view,int layout){
        int[] size=dimensions(AppWidgetManager.getInstance(context).getAppWidgetOptions(id));
        PendingIntent open=PendingIntent.getActivity(context,100,new Intent(context,MainActivity.class).putExtra("open_player",true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),pendingFlags());
        view.setOnClickPendingIntent(R.id.widget_root,open);view.setOnClickPendingIntent(R.id.widget_cover,open);
        view.setOnClickPendingIntent(R.id.widget_toggle,command(context,PlaybackService.TOGGLE,101));
        view.setOnClickPendingIntent(R.id.widget_previous,command(context,PlaybackService.PREVIOUS,102));view.setOnClickPendingIntent(R.id.widget_next,command(context,PlaybackService.NEXT,103));
        if(layout!=R.layout.widget_compact){
            view.setOnClickPendingIntent(R.id.widget_karaoke,PendingIntent.getActivity(context,104,new Intent(context,MainActivity.class).putExtra("open_player",true).putExtra("karaoke",true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),pendingFlags()));
        }
        resize(context,view,layout,size[0],size[1]);
    }
    static void resize(Context context,RemoteViews view,int layout,int width,int height){
        if(layout==R.layout.widget_compact){view.setViewVisibility(R.id.widget_previous,width<320?View.GONE:View.VISIBLE);return;}
        view.setViewVisibility(R.id.widget_previous_line,height>=600&&layout==R.layout.widget_tall?View.VISIBLE:View.GONE);
        view.setViewVisibility(R.id.widget_next_line,height>=480?View.VISIBLE:View.GONE);
        view.setInt(R.id.widget_current_line,"setMaxLines",height<280?1:height<640?2:3);
        view.setInt(R.id.widget_reading,"setMaxLines",height<360?1:2);
        view.setInt(R.id.widget_translation,"setMaxLines",height<640?1:2);
        if(height<400)view.setViewVisibility(R.id.widget_translation,View.GONE);
        if(layout==R.layout.widget_card&&height<280)view.setViewVisibility(R.id.widget_reading,View.GONE);
        view.setTextViewTextSize(R.id.widget_current_line,android.util.TypedValue.COMPLEX_UNIT_SP,height<400?(layout==R.layout.widget_card?20:22):layout==R.layout.widget_card?22:25);
        if(layout==R.layout.widget_tall){
            view.setViewVisibility(R.id.widget_header,height<400?View.GONE:View.VISIBLE);
            view.setViewVisibility(R.id.widget_cover,height<560?View.GONE:View.VISIBLE);
            int cover=height<640?112:168;view.setInt(R.id.widget_cover,"setMaxHeight",Ui.dp(context,cover));view.setInt(R.id.widget_cover,"setMaxWidth",Ui.dp(context,cover));
        }
    }
    private static int pendingFlags(){return PendingIntent.FLAG_UPDATE_CURRENT|(android.os.Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE:0);}
    private static PendingIntent command(Context context,String action,int code){return PendingIntent.getService(context,code,new Intent(context,PlaybackService.class).setAction(action),pendingFlags());}
    static void bindText(RemoteViews view,MediaItem item,boolean playing,String current,String previous,String next,String translation,String reading,int accent,int progress,long position,long duration,String status,int layout){
        view.setTextViewText(R.id.widget_title,item==null?"NoMusic":item.name);view.setTextViewText(R.id.widget_artist,item==null?"轻触选择音乐":item.artist);
        view.setImageViewResource(R.id.widget_toggle,playing?R.drawable.widget_pause:R.drawable.widget_play);
        for(int button:new int[]{R.id.widget_previous,R.id.widget_toggle,R.id.widget_next})view.setInt(button,"setColorFilter",accent);
        view.setContentDescription(R.id.widget_toggle,playing?"暂停":"播放");
        if(layout==R.layout.widget_compact)return;
        view.setTextViewText(R.id.widget_current_line,current);view.setTextViewText(R.id.widget_previous_line,previous);view.setTextViewText(R.id.widget_next_line,next);view.setTextColor(R.id.widget_current_line,accent);
        view.setTextViewText(R.id.widget_translation,translation);view.setViewVisibility(R.id.widget_translation,translation.isEmpty()?View.GONE:View.VISIBLE);
        view.setTextViewText(R.id.widget_reading,reading);view.setViewVisibility(R.id.widget_reading,reading.isEmpty()?View.GONE:View.VISIBLE);
        view.setProgressBar(R.id.widget_progress,1000,progress,false);view.setTextViewText(R.id.widget_time,status.isEmpty()?MediaItem.time(position)+" / "+MediaItem.time(duration):status);
    }
    static void showSaved(Context context,int[] ids){
        AppPrefs prefs=new AppPrefs(context);MediaItem item=null;
        if(prefs.hasAccount())try{JSONArray queue=new JSONArray(prefs.store.getString("queue","[]"));int index=prefs.store.getInt("queue_index",-1);if(index>=0&&index<queue.length())item=new MediaItem(queue.getJSONObject(index));}catch(Exception ignored){}
        for(int id:ids)try{
            int layout=layout(context,id);RemoteViews view=new RemoteViews(context.getPackageName(),layout);configure(context,id,view,layout);
            bindText(view,item,false,item==null?"好音乐，在路上":"轻触播放，继续聆听","","","","",Ui.themePalette(prefs,false).ACCENT,0,prefs.store.getLong("position",0),item==null?0:item.durationMs,"",layout);
            int[] size=dimensions(AppWidgetManager.getInstance(context).getAppWidgetOptions(id));resize(context,view,layout,size[0],size[1]);
            AppWidgetManager.getInstance(context).updateAppWidget(id,view);
        }catch(RuntimeException ignored){}
    }
    void close(){closed=true;++generation;if(request!=null)request.cancel();if(readingRequest!=null)readingRequest.cancel();prefs.store.unregisterOnSharedPreferenceChangeListener(settings);main.removeCallbacksAndMessages(null);disk.shutdownNow();}
}
