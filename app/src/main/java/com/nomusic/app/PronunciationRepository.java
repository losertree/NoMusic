package com.nomusic.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Versioned, content-addressed pronunciation cache with request coalescing. No network access. */
final class PronunciationRepository {
    interface Callback{void ready(Result result);}
    static final class Request{
        private volatile Callback callback;
        Request(Callback callback){this.callback=callback;}
        void cancel(){callback=null;}
        void deliver(Result result){Callback target=callback;callback=null;if(target!=null)target.ready(result);}
    }
    static final class Result{
        final List<PronunciationEngine.Line> lines;
        final PronunciationEngine.Language language;
        final String error;
        Result(List<PronunciationEngine.Line> lines,PronunciationEngine.Language language,String error){this.lines=java.util.Collections.unmodifiableList(lines);this.language=language;this.error=error;}
        PronunciationEngine.Line line(int cue){return cue<0||cue>=lines.size()?PronunciationEngine.Line.EMPTY:lines.get(cue);}
    }
    private static PronunciationRepository instance;
    static synchronized PronunciationRepository get(Context context){if(instance==null)instance=new PronunciationRepository(context,new File(context.getCacheDir(),"pronunciation-v1"));return instance;}
    private final File directory;
    private final PronunciationEngine engine;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Map<String,List<Request>> pending=new HashMap<>();
    private final LruCache<String,Result> memory=new LruCache<String,Result>(768*1024){
        @Override protected int sizeOf(String key,Result result){int size=128;for(PronunciationEngine.Line line:result.lines)size+=64+line.text.length()*2+line.originalEnds.length*8;return size;}
    };
    PronunciationRepository(Context context,File directory){this.directory=directory;engine=new PronunciationEngine(context);}
    static String scope(EmbyClient client,MediaItem item){return StorageFiles.key(client.accountKey()+"\n"+item.id);}
    static PronunciationEngine.Language language(AppPrefs prefs,String scope){return PronunciationEngine.Language.read(prefs.store.getString("pronunciation_language_"+scope,"auto"));}
    static void setLanguage(AppPrefs prefs,String scope,PronunciationEngine.Language language){prefs.store.edit().putString("pronunciation_language_"+scope,language.key).apply();}
    static String cacheKey(String scope,String text,PronunciationEngine.Language language){return StorageFiles.key("readings-v1\n"+scope+"\n"+language.key+"\n"+text);}

    Request load(String scope,String text,PronunciationEngine.Language language,Callback callback){
        String key=cacheKey(scope,text,language);Request request=new Request(callback);
        synchronized(this){
            Result cached=memory.get(key);if(cached!=null){main.post(()->request.deliver(cached));return request;}
            List<Request> waiting=pending.get(key);if(waiting!=null){waiting.add(request);return request;}
            waiting=new ArrayList<>();waiting.add(request);pending.put(key,waiting);
        }
        worker.execute(()->{
            Result result=null;File file=new File(directory,key+".json");
            try{if(file.isFile()){result=decode(StorageFiles.readJson(file,2*1024*1024));file.setLastModified(System.currentTimeMillis());}}catch(Exception ignored){}
            if(result==null){
                try{
                    LyricsDocument document=LyricsDocument.parse(text);
                    PronunciationEngine.Language actual=language==PronunciationEngine.Language.AUTO?PronunciationEngine.detect(document):language;
                    List<PronunciationEngine.Line> lines=new ArrayList<>();
                    for(LyricsDocument.Cue cue:document.cues)lines.add(engine.read(cue.original,actual));
                    result=new Result(lines,actual,"");
                    try{StorageFiles.writeJson(file,encode(result));prune();}catch(Exception ignored){}
                }catch(Exception|LinkageError failure){result=new Result(new ArrayList<>(),language,"发音生成失败，可重试或切换语言");}
            }
            Result finished=result;List<Request> listeners;
            synchronized(this){if(finished.error.isEmpty())memory.put(key,finished);listeners=pending.remove(key);}
            if(listeners!=null)for(Request listener:listeners)main.post(()->listener.deliver(finished));
        });
        return request;
    }

    private static JSONObject encode(Result result)throws Exception{
        JSONArray lines=new JSONArray();for(PronunciationEngine.Line line:result.lines){JSONArray a=new JSONArray(),b=new JSONArray();for(int value:line.originalEnds)a.put(value);for(int value:line.readingEnds)b.put(value);lines.put(new JSONObject().put("text",line.text).put("original",a).put("reading",b));}
        return new JSONObject().put("version",1).put("language",result.language.key).put("lines",lines);
    }
    private static Result decode(JSONObject json)throws Exception{
        if(json.getInt("version")!=1)throw new java.io.IOException();
        List<PronunciationEngine.Line> lines=new ArrayList<>();JSONArray array=json.getJSONArray("lines");if(array.length()>10000)throw new java.io.IOException();
        for(int i=0;i<array.length();i++){
            JSONObject value=array.getJSONObject(i);JSONArray a=value.getJSONArray("original"),b=value.getJSONArray("reading");if(a.length()!=b.length()||a.length()>128*1024)throw new java.io.IOException();
            int[] originals=new int[a.length()],readings=new int[b.length()];String text=value.getString("text");int lastA=0,lastB=0;
            for(int j=0;j<a.length();j++){originals[j]=a.getInt(j);readings[j]=b.getInt(j);if(originals[j]<lastA||readings[j]<lastB||readings[j]>text.length())throw new java.io.IOException();lastA=originals[j];lastB=readings[j];}
            lines.add(new PronunciationEngine.Line(text,originals,readings));
        }
        return new Result(lines,PronunciationEngine.Language.read(json.getString("language")),"");
    }
    private void prune(){
        File[] files=directory.listFiles((dir,name)->name.endsWith(".json"));if(files==null)return;
        long bytes=0;for(File file:files)bytes+=file.length();if(bytes<=8*1024*1024&&files.length<=256)return;
        Arrays.sort(files,(a,b)->Long.compare(a.lastModified(),b.lastModified()));int count=files.length;
        for(File file:files){if(bytes<=8*1024*1024&&count<=256)break;long size=file.length();if(file.delete()){bytes-=size;count--;}}
    }
    void close(){synchronized(this){for(List<Request> requests:pending.values())for(Request request:requests)request.cancel();pending.clear();memory.evictAll();}worker.shutdownNow();}
}
