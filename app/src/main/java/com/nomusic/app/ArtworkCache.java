package com.nomusic.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ExecutionException;

/** Shared by UI and playback metadata, with bounded disk copies and coalesced requests. */
final class ArtworkCache {
    private static ArtworkCache instance;
    private static final long DAY=24L*60*60*1000,DISK_LIMIT=256L*1024*1024;
    private final File root;
    private final LruCache<String,Bitmap> memory=new LruCache<String,Bitmap>((int)Math.min(12*1024*1024,Runtime.getRuntime().maxMemory()/10)){
        @Override protected int sizeOf(String k,Bitmap b){return b.getByteCount();}
    };
    private final Map<String,Long> missing=new HashMap<>();
    private final Map<String,FutureTask<Bitmap>> pending=new HashMap<>();
    static synchronized ArtworkCache get(Context context){if(instance==null)instance=new ArtworkCache(context.getApplicationContext());return instance;}
    ArtworkCache(Context context){root=new File(context.getFilesDir(),"artwork-cache-v1");}
    private String key(EmbyClient api,String id,String tag){return StorageFiles.key(api.accountKey()+"\n"+id+"\n"+tag);}
    Bitmap peek(EmbyClient api,MediaItem item){Bitmap image=memory.get(key(api,item.imageId,item.imageTag));if(image==null)image=memory.get(key(api,item.id,""));if(image==null&&!item.albumId.isEmpty())image=memory.get(key(api,item.albumId,""));return image;}
    Bitmap load(EmbyClient api,MediaItem item)throws Exception {
        Bitmap image=source(api,item.imageId,item.imageTag);
        if(image==null&&!item.id.equals(item.imageId))image=source(api,item.id,"");
        if(image==null&&item.isAudio()&&!item.albumId.isEmpty()&&!item.albumId.equals(item.imageId))image=source(api,item.albumId,"");
        if(image==null&&"MusicAlbum".equals(item.type)){
            EmbyClient.Page songs=api.browse(new BrowseSpec("albums",item.id,"MusicAlbum","","",false,""),0,5);
            for(MediaItem song:songs.items){image=source(api,song.id,song.id.equals(song.imageId)?song.imageTag:"");if(image!=null)break;}
        }
        return image;
    }
    private Bitmap source(EmbyClient api,String id,String tag)throws Exception {
        if(id.isEmpty())return null;
        String key=key(api,id,tag);Bitmap cached=memory.get(key);if(cached!=null)return cached;
        FutureTask<Bitmap> task;boolean created=false;
        synchronized(this){
            Long until=missing.get(key);if(until!=null&&System.currentTimeMillis()<until)return null;
            task=pending.get(key);
            if(task==null){task=new FutureTask<>(()->loadSource(api,id,tag,key));pending.put(key,task);created=true;}
        }
        if(created)task.run();
        try{return task.get();}
        catch(ExecutionException e){if(e.getCause() instanceof Exception)throw (Exception)e.getCause();throw new RuntimeException(e.getCause());}
        finally{if(created)synchronized(this){pending.remove(key);}}
    }
    private Bitmap loadSource(EmbyClient api,String id,String tag,String key)throws Exception {
        File data=new File(root,key+".img"),meta=new File(root,key+".json");Bitmap stale=null;
        try {
            JSONObject info=StorageFiles.readJson(meta,4096);long age=System.currentTimeMillis()-info.optLong("saved");
            if(info.optBoolean("missing")&&age>=0&&age<6*60*60*1000){synchronized(this){missing.put(key,System.currentTimeMillis()+6*60*60*1000-age);}return null;}
            if(data.isFile())try(java.io.FileInputStream input=new java.io.FileInputStream(data)){stale=decode(StorageFiles.read(input,8*1024*1024));}
            if(stale!=null&&age>=0&&age<(tag.isEmpty()?7*DAY:30*DAY)){memory.put(key,stale);data.setLastModified(System.currentTimeMillis());return stale;}
        } catch(Exception ignored){}
        byte[] bytes;
        try {bytes=api.imageBytes(id,tag);}
        catch(Exception e){if(e instanceof EmbyClient.ApiException&&(((EmbyClient.ApiException)e).status==401||((EmbyClient.ApiException)e).status==403))throw e;if(stale!=null){memory.put(key,stale);return stale;}throw e;}
        if(!api.currentSession())return null;
        if(bytes==null){synchronized(this){missing.put(key,System.currentTimeMillis()+6*60*60*1000);}try{StorageFiles.writeJson(meta,new JSONObject().put("missing",true).put("saved",System.currentTimeMillis()));data.delete();}catch(Exception ignored){}return null;}
        Bitmap image=decode(bytes);if(image==null)throw new IOException("Invalid artwork");
        memory.put(key,image);
        synchronized(this){
            File part=new File(root,key+".part");
            try{
                if(!root.isDirectory()&&!root.mkdirs())return image;
                try(FileOutputStream output=new FileOutputStream(part)){output.write(bytes);output.getFD().sync();}
                if(part.renameTo(data))StorageFiles.writeJson(meta,new JSONObject().put("saved",System.currentTimeMillis()));
                prune();
            }catch(Exception ignored){}finally{part.delete();}
        }
        return image;
    }
    private static Bitmap decode(byte[] bytes){
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
        if(bounds.outWidth<=0||bounds.outHeight<=0||bounds.outWidth>16000||bounds.outHeight>16000)return null;
        BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;
        while(Math.max(bounds.outWidth,bounds.outHeight)/options.inSampleSize>840)options.inSampleSize*=2;
        return BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
    }
    synchronized void clearMemory(){memory.evictAll();missing.clear();}
    private void prune(){
        File[] files=root.listFiles((d,n)->n.endsWith(".img")||n.endsWith(".json"));if(files==null)return;
        long total=0;for(File file:files)total+=file.length();if(total<=DISK_LIMIT&&files.length<=4096)return;
        Arrays.sort(files,(a,b)->Long.compare(a.lastModified(),b.lastModified()));int count=files.length;
        for(File file:files){if(total<=DISK_LIMIT&&count<=4096)break;long size=file.length();if(file.delete()){total-=size;count--;}}
    }
}
