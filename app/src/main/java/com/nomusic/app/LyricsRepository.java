package com.nomusic.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Application-scoped lyrics cache. Disk and network work never run on the UI thread. */
final class LyricsRepository {
    static final long FOUND_TTL=30L*24*60*60*1000, MISSING_TTL=6L*60*60*1000, ERROR_TTL=30*1000;
    static final int MAX_TEXT=128*1024;
    private static final int MAX_FILE=512*1024, MAX_FILES=256;
    private static final long MAX_DISK=4L*1024*1024;
    private static LyricsRepository instance;
    interface Clock { long now(); }
    interface Callback { void ready(Result result); }

    static final class Result {
        final String text,error;
        final LyricsDocument document;
        Result(String text,String error) { this.text=text;this.error=error;document=LyricsDocument.parse(text); }
        String display() {
            if(!text.isEmpty())return text;
            if(!error.isEmpty())return "歌词加载失败\n"+error+"\n可点击顶部刷新重试。";
            return "暂无可用歌词\n服务器未提供此歌曲的歌词，可补充后点击顶部刷新。";
        }
    }

    static final class Request {
        private volatile Callback callback;
        Request(Callback callback) { this.callback=callback; }
        void cancel() { callback=null; }
        void deliver(Result result) { Callback target=callback;callback=null;if(target!=null)target.ready(result); }
    }

    private static final class Entry {
        final Result result;
        final long expires;
        Entry(Result result,long expires) { this.result=result;this.expires=expires; }
    }

    private final Object lock=new Object(),diskLock=new Object();
    private final File directory;
    private final Clock clock;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService workers=Executors.newFixedThreadPool(2);
    private final Map<String,List<Request>> pending=new HashMap<>();
    private final LruCache<String,Entry> memory=new LruCache<String,Entry>(512*1024) {
        @Override protected int sizeOf(String key,Entry entry) { return 128+2*(entry.result.text.length()+entry.result.error.length())+entry.result.document.sizeBytes; }
    };
    private boolean closed;

    static synchronized LyricsRepository get(Context context) {
        if(instance==null)instance=new LyricsRepository(new File(context.getApplicationContext().getCacheDir(),"lyrics-v2"),System::currentTimeMillis);
        return instance;
    }

    LyricsRepository(File directory,Clock clock) { this.directory=directory;this.clock=clock; }

    Result peek(EmbyClient client,MediaItem item) {
        synchronized(lock) {
            Entry cached=memory.get(key(client,item));
            return cached!=null&&(cached.expires>clock.now()||!cached.result.text.isEmpty())?cached.result:null;
        }
    }

    Request load(EmbyClient client,MediaItem item,boolean force,Callback callback) {
        String key=key(client,item);Request request=new Request(callback);
        synchronized(lock) {
            if(closed){request.cancel();return request;}
            List<Request> waiting=pending.get(key);
            // Reopening a page or pressing refresh while a request is pending shares that request.
            if(waiting!=null){waiting.add(request);return request;}
            Entry cached=memory.get(key);
            if(!force&&cached!=null&&cached.expires>clock.now()) {
                main.post(()->request.deliver(cached.result));return request;
            }
            waiting=new ArrayList<>();waiting.add(request);pending.put(key,waiting);
            workers.execute(()->fetch(key,client,item,force));
        }
        return request;
    }

    private void fetch(String key,EmbyClient client,MediaItem item,boolean force) {
        Entry previous;
        synchronized(lock){previous=memory.get(key);}
        if(previous==null||!previous.result.error.isEmpty()) {
            Entry disk=read(key);if(disk!=null)previous=disk;
        }
        if(!client.authenticated()&&previous!=null){finish(key,previous);return;}
        if(!force&&previous!=null&&previous.expires>clock.now()) { finish(key,previous);return; }
        Entry result;
        try {
            String text=retrieve(client,item);
            result=new Entry(new Result(text,""),clock.now()+(text.isEmpty()?MISSING_TTL:FOUND_TTL));
            write(key,result);
        } catch(Exception e) {
            // Do not turn a timeout, bad JSON, 401 or 5xx into a persistent "no lyrics" result.
            String retained=previous!=null?previous.result.text:"";
            result=new Entry(new Result(retained,EmbyClient.message(e)),clock.now()+ERROR_TTL);
        }
        finish(key,result);
    }

    private void finish(String key,Entry result) {
        List<Request> listeners;
        synchronized(lock) {
            if(closed)return;
            memory.put(key,result);listeners=pending.remove(key);
        }
        if(listeners!=null)for(Request request:listeners)main.post(()->request.deliver(result.result));
    }

    private static String key(EmbyClient client,MediaItem item) {
        try {
            // Length prefixes prevent ambiguous delimiters; tokens and raw IDs never become filenames.
            String value=client.server.length()+":"+client.server+client.userId.length()+":"+client.userId+item.id.length()+":"+item.id;
            byte[] hash=MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8"));
            StringBuilder result=new StringBuilder();for(byte b:hash){result.append(Character.forDigit((b>>4)&15,16));result.append(Character.forDigit(b&15,16));}
            return result.toString();
        } catch(Exception e) { throw new IllegalStateException(e); }
    }

    private Entry read(String key) {
        synchronized(diskLock) {
            File file=new File(directory,key+".json");
            if(!file.isFile())return null;
            try {
                if(file.length()>MAX_FILE)throw new IOException("Oversized lyric cache");
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                try(FileInputStream input=new FileInputStream(file)) {
                    byte[] buffer=new byte[4096];int count;
                    while((count=input.read(buffer))!=-1){bytes.write(buffer,0,count);if(bytes.size()>MAX_FILE)throw new IOException("Oversized lyric cache");}
                }
                JSONObject json=new JSONObject(bytes.toString("UTF-8"));
                String text=json.getString("Text");long expires=json.getLong("Expires");
                int version=json.optInt("Version");
                if((version!=2&&version!=3)||text.length()>MAX_TEXT||expires<=0)throw new IOException("Invalid lyric cache");
                Result result=new Result(text,"");
                // Embedded LRC in v2 already has its complete timeline and remains reusable.
                // Old plain API rows lost their timestamps and need a one-time refresh.
                if(version==2&&!text.isEmpty()&&!result.document.timed)expires=Math.min(expires,clock.now()-1);
                file.setLastModified(clock.now());
                return new Entry(result,expires);
            } catch(Exception ignored) { file.delete();return null; }
        }
    }

    private void write(String key,Entry entry) {
        synchronized(diskLock) {
            File file=new File(directory,key+".json"),temporary=new File(directory,key+".tmp");
            try {
                if(!directory.isDirectory()&&!directory.mkdirs())return;
                byte[] bytes=new JSONObject().put("Version",3).put("Text",entry.result.text).put("Expires",entry.expires).toString().getBytes("UTF-8");
                if(bytes.length>MAX_FILE)return;
                try(FileOutputStream output=new FileOutputStream(temporary)){output.write(bytes);output.getFD().sync();}
                if(!temporary.renameTo(file))return;
                File[] files=directory.listFiles((dir,name)->name.endsWith(".json"));if(files==null)return;
                Arrays.sort(files,(left,right)->left.lastModified()<right.lastModified()?-1:left.lastModified()>right.lastModified()?1:0);
                long size=0;for(File cached:files)size+=cached.length();
                int count=files.length;
                for(File cached:files) {
                    if(size<=MAX_DISK&&count<=MAX_FILES)break;
                    long length=cached.length();if(cached.delete()){size-=length;count--;}
                }
            } catch(Exception ignored) { /* A full/unavailable cache must not hide successfully fetched lyrics. */ }
            finally { temporary.delete(); }
        }
    }

    private static String retrieve(EmbyClient client,MediaItem item) throws Exception {
        if(!client.authenticated())throw new IOException("Offline");
        String text="";Exception primaryFailure=null;
        try {text=parse(client.lyrics(item));}
        catch(EmbyClient.ApiException e) {
            if(e.status==401||e.status==403)throw e;
            if(e.status!=404)primaryFailure=e;
        } catch(Exception e) {primaryFailure=e;}
        if(!text.isEmpty())return text;
        // Emby 4.8 exposes embedded LRC/USLT lyrics as subtitle Extradata in item details.
        // A missing /Lyrics endpoint says nothing about the tags inside the audio file.
        JSONObject details=client.lyricDetails(item);
        List<JSONArray> streams=new ArrayList<>();
        streams.add(details.optJSONArray("MediaStreams"));
        JSONArray sources=details.optJSONArray("MediaSources");
        if(sources!=null)for(int i=0;i<sources.length();i++) {
            JSONObject source=sources.optJSONObject(i);if(source==null)continue;
            streams.add(source.optJSONArray("MediaStreams"));
        }
        text=embedded(streams);
        if(text.isEmpty()&&primaryFailure!=null)throw primaryFailure;
        return text;
    }

    private static String embedded(List<JSONArray> groups) throws IOException {
        String original="",translation="";
        for(JSONArray streams:groups)if(streams!=null)for(int i=0;i<streams.length();i++) {
            JSONObject stream=streams.optJSONObject(i);if(stream==null)continue;
            String type=stream.optString("Type"),codec=stream.optString("Codec");
            String title=stream.optString("Title",stream.optString("DisplayTitle")).toLowerCase(java.util.Locale.ROOT);
            boolean translated=title.contains("translat")||title.contains("译")||title.contains("訳")||title.contains("번역");
            if(!"Lyric".equalsIgnoreCase(type)&&!("Subtitle".equalsIgnoreCase(type)
                    &&("text".equalsIgnoreCase(codec)||"lrc".equalsIgnoreCase(codec)||title.contains("lyric")||translated)))continue;
            String text=string(stream,"Extradata").trim();
            if(text.isEmpty())continue;
            if(text.length()>MAX_TEXT)throw new IOException("Lyrics are too large");
            if(translated) {
                if(translation.isEmpty())translation=text;
            } else if(original.isEmpty())original=text;
        }
        String merged=translation.isEmpty()?original:LyricsDocument.merge(original,translation);
        return merged.length()<=MAX_TEXT?merged:original;
    }

    static String parse(JSONObject json) throws IOException {
        StringBuilder result=new StringBuilder();
        for(String key:new String[]{"Lyrics","Lines","LyricLines"}) {
            JSONArray lines=json.optJSONArray(key);if(lines==null)continue;
            for(int i=0;i<lines.length();i++) {
                Object value=lines.opt(i);String text=value instanceof JSONObject?string((JSONObject)value,"Text"):value instanceof String?(String)value:"";
                JSONObject line=value instanceof JSONObject?(JSONObject)value:null;
                long time=startMillis(line);
                if(!text.trim().isEmpty()||time>=0) {
                    if(result.length()>0)result.append('\n');
                    if(time>=0&&!LyricsDocument.hasTimestamp(text)) {
                        String stamp=LyricsDocument.timestamp(time);
                        JSONArray words=line==null?null:line.optJSONArray("Words");
                        String enhanced=enhance(text,words,time);
                        result.append(stamp).append(enhanced.replace("\r","").replace("\n","\n"+stamp));
                        String translation=string(line,"Translation");if(translation.isEmpty())translation=string(line,"TranslatedText");
                        if(!translation.trim().isEmpty())result.append('\n').append(stamp).append(LyricsDocument.TRANSLATION).append(translation.replace("\r","").replace("\n","\n"+stamp+LyricsDocument.TRANSLATION));
                    } else result.append(text);
                }
                if(result.length()>MAX_TEXT)throw new IOException("Lyrics are too large");
            }
            if(result.length()>0)return result.toString();
        }
        for(String key:new String[]{"Lyrics","Text","Lyric"}) {
            Object value=json.opt(key);if(value instanceof String&&!((String)value).trim().isEmpty()) {
                String text=((String)value).trim();if(text.length()>MAX_TEXT)throw new IOException("Lyrics are too large");return text;
            }
        }
        return "";
    }

    private static long startMillis(JSONObject line) {
        if(line==null)return -1;
        for(String key:new String[]{"StartTicks","StartTimeTicks","Start"})if(line.has(key)&&!line.isNull(key)) {
            double value=line.optDouble(key,Double.NaN);double millis=key.equals("Start")?value*1000:value/10000;
            if(!Double.isNaN(millis)&&millis>=0&&millis<=7L*24*60*60*1000)return (long)millis;
        }
        return -1;
    }

    private static String enhance(String text,JSONArray words,long lineTime) {
        if(words==null)return text;
        StringBuilder result=new StringBuilder(text);int from=0,shift=0;long last=lineTime;
        for(int i=0;i<words.length();i++) {
            JSONObject word=words.optJSONObject(i);String value=string(word,"Text");
            long time=startMillis(word);if(value.isEmpty()||time<last)continue;
            int at=text.indexOf(value,from);if(at<0)continue;
            String stamp=LyricsDocument.wordTimestamp(time);result.insert(at+shift,stamp);
            from=at+value.length();shift+=stamp.length();last=time;
        }
        return result.toString();
    }

    private static String string(JSONObject json,String key) {Object value=json==null?null:json.opt(key);return value instanceof String?(String)value:"";}

    // Only standalone test instances are closed. The application singleton outlives activities.
    void close() {
        synchronized(lock) {closed=true;for(List<Request> requests:pending.values())for(Request request:requests)request.cancel();pending.clear();memory.evictAll();}
        workers.shutdownNow();main.removeCallbacksAndMessages(null);
    }
}
