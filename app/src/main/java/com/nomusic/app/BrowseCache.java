package com.nomusic.app;

import android.content.Context;
import android.util.LruCache;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Account/query/page isolated snapshots. Stale pages remain usable during a refresh. */
final class BrowseCache {
    interface Loader { JSONObject load() throws Exception; }
    interface Refreshed { void ready(JSONObject page); }
    static final class Result {
        final JSONObject page;
        final boolean legacy;
        private final Refresh refresh;
        Result(JSONObject page,boolean legacy,Refresh refresh){this.page=page;this.legacy=legacy;this.refresh=refresh;}
        void observe(Refreshed callback){if(refresh!=null)refresh.observe(callback);}
    }
    private static final class Refresh extends FutureTask<JSONObject> {
        private ArrayList<Refreshed> callbacks=new ArrayList<>();
        Refresh(Loader loader){super(loader::load);}
        void observe(Refreshed callback){
            synchronized(this){if(callbacks!=null){callbacks.add(callback);return;}}
            deliver(callback);
        }
        @Override protected void done(){
            ArrayList<Refreshed> watching;
            synchronized(this){watching=callbacks;callbacks=null;}
            for(Refreshed callback:watching)deliver(callback);
        }
        private void deliver(Refreshed callback){
            try{callback.ready(get());}
            catch(InterruptedException e){Thread.currentThread().interrupt();}
            catch(ExecutionException ignored){/* Keep the visible cached page on a refresh failure. */}
        }
    }
    private static BrowseCache instance;
    private static final long FRESH=24L*60*60*1000,MAX_AGE=30L*24*60*60*1000,DISK_LIMIT=64L*1024*1024;
    private final File root;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final LruCache<String,String> memory=new LruCache<String,String>(2*1024*1024){@Override protected int sizeOf(String k,String v){return v.length()*2;}};
    private final Map<String,Refresh> pending=new HashMap<>();
    private final Map<String,Long> cooldown=new HashMap<>();
    private final Map<String,Integer> generations=new HashMap<>();
    static synchronized BrowseCache get(Context context){if(instance==null)instance=new BrowseCache(context.getApplicationContext());return instance;}
    BrowseCache(Context context){root=new File(context.getFilesDir(),"browse-cache-v1");}
    private String account(EmbyClient api){return StorageFiles.key(api.accountKey());}
    private String key(EmbyClient api,String resource){return account(api)+"/"+StorageFiles.key(resource);}
    private File file(String key){return new File(root,key+".json");}
    private int generation(String account){Integer value=generations.get(account);return value==null?0:value;}

    JSONObject page(EmbyClient api,String resource,Loader loader)throws Exception {
        return result(api,resource,null,loader).page;
    }
    Result result(EmbyClient api,String resource,String legacy,Loader loader)throws Exception {
        String key=key(api,resource);JSONObject cached=read(key);long age=cached==null?Long.MAX_VALUE:System.currentTimeMillis()-cached.optLong("saved");
        if(cached!=null&&age>=0&&age<=MAX_AGE) {
            Refresh update=age>FRESH&&api.authenticated()?refresh(api,key,loader,true):null;
            return new Result(cached.getJSONObject("page"),false,update);
        }
        // Keep offline browse snapshots from before audio metadata was requested.
        JSONObject previous=legacy==null?null:read(key(api,legacy));
        if(previous!=null){
            long oldAge=System.currentTimeMillis()-previous.optLong("saved");
            if(oldAge>=0&&oldAge<=MAX_AGE){
                Refresh update=api.authenticated()?refresh(api,key,loader,true):null;
                return new Result(previous.getJSONObject("page"),true,update);
            }
        }
        try{return new Result(new JSONObject(refresh(api,key,loader,false).get().toString()),false,null);}
        catch(ExecutionException e){if(e.getCause() instanceof Exception)throw (Exception)e.getCause();throw new RuntimeException(e.getCause());}
    }
    private synchronized JSONObject read(String key){
        try {
            String value=memory.get(key);
            if(value==null){File file=file(key);if(!file.isFile())return null;value=StorageFiles.readJson(file,8*1024*1024).toString();memory.put(key,value);file.setLastModified(System.currentTimeMillis());}
            return new JSONObject(value);
        } catch(Exception e){memory.remove(key);file(key).delete();return null;}
    }
    private Refresh refresh(EmbyClient api,String key,Loader loader,boolean background) {
        Refresh task;boolean created=false;
        synchronized(this){
            task=pending.get(key);
            if(task==null){
                if(background&&cooldown.containsKey(key)&&System.currentTimeMillis()-cooldown.get(key)<60000)return null;
                String account=account(api);int epoch=generation(account);
                task=new Refresh(()->{
                    try {
                        JSONObject page=loader.load();
                        synchronized(BrowseCache.this){
                            if(api.currentSession()&&epoch==generation(account)) {
                                JSONObject entry=new JSONObject().put("saved",System.currentTimeMillis()).put("page",page);
                                memory.put(key,entry.toString());try{StorageFiles.writeJson(file(key),entry);prune();}catch(Exception ignored){}
                            }
                        }
                        return page;
                    } finally {synchronized(BrowseCache.this){if(epoch==generation(account)){pending.remove(key);cooldown.put(key,System.currentTimeMillis());}}}
                });pending.put(key,task);created=true;
            }
        }
        if(created){if(background)worker.execute(task);else task.run();}
        return task;
    }
    synchronized void invalidate(EmbyClient api){
        String account=account(api);generations.put(account,generation(account)+1);
        for(String key:new ArrayList<>(memory.snapshot().keySet()))if(key.startsWith(account+"/"))memory.remove(key);
        for(String key:new ArrayList<>(pending.keySet()))if(key.startsWith(account+"/"))pending.remove(key);
        for(String key:new ArrayList<>(cooldown.keySet()))if(key.startsWith(account+"/"))cooldown.remove(key);
        File[] files=new File(root,account).listFiles();if(files!=null)for(File file:files)if(file.isFile())file.delete();
    }
    synchronized void clearMemory(){memory.evictAll();}
    synchronized int revision(EmbyClient api){return generation(account(api));}
    private void prune(){
        ArrayList<File> entries=new ArrayList<>();File[] accounts=root.listFiles();long total=0;
        if(accounts!=null)for(File account:accounts){File[] files=account.listFiles((d,n)->n.endsWith(".json"));if(files!=null)for(File file:files){entries.add(file);total+=file.length();}}
        if(total<=DISK_LIMIT&&entries.size()<=512)return;
        File[] files=entries.toArray(new File[0]);Arrays.sort(files,(a,b)->Long.compare(a.lastModified(),b.lastModified()));int count=files.length;
        for(File file:files){if(total<=DISK_LIMIT&&count<=512)break;long size=file.length();if(file.delete()){total-=size;count--;}}
    }
}
