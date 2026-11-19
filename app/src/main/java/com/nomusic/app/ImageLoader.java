package com.nomusic.app;

import android.graphics.Bitmap;
import android.os.Handler;
import android.util.LruCache;
import android.widget.ImageView;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ImageLoader {
    interface Ready { void loaded(Bitmap bitmap,int tint); }
    private final Handler main=new Handler();
    private final ExecutorService workers=Executors.newFixedThreadPool(2);
    private final LruCache<String,Bitmap> cache=new LruCache<String,Bitmap>(4*1024*1024) {
        @Override protected int sizeOf(String key,Bitmap bitmap) { return bitmap.getByteCount(); }
    };
    private final Map<String,Long> failed=new HashMap<>();
    private final LruCache<String,Integer> tints=new LruCache<>(128);
    private final Map<String,List<Waiting>> waiting=new HashMap<>();
    private static final class Waiting {
        final java.lang.ref.WeakReference<ImageView> view;final Ready ready;
        Waiting(ImageView view,Ready ready){this.view=new java.lang.ref.WeakReference<>(view);this.ready=ready;}
    }
    private boolean closed;

    void load(EmbyClient client,MediaItem item,ImageView view) {
        load(client,item,view,null);
    }
    void load(EmbyClient client,MediaItem item,ImageView view,Ready ready) {
        if(closed||!client.currentSession())return;
        String key=client.server+":"+client.userId+":"+item.imageId+":"+item.imageTag;
        boolean same=key.equals(view.getTag());view.setTag(key);
        UiMotion motion=Ui.motion(view.getContext());if(!same&&motion!=null)motion.finish(view);
        Bitmap bitmap=cache.get(key);
        if(bitmap==null)bitmap=client.cachedImage(item);
        if(bitmap!=null) { cache.put(key,bitmap);Integer tint=tints.get(key);if(tint==null){tint=AmbientDrawable.sample(bitmap);tints.put(key,tint);}view.setImageBitmap(bitmap);if(ready!=null)ready.loaded(bitmap,tint); return; }
        if(!same)view.setImageDrawable(new CoverDrawable());
        if(failed.containsKey(key)&&System.currentTimeMillis()-failed.get(key)<30000 || closed) return;
        Waiting request=new Waiting(view,ready);
        List<Waiting> requests=waiting.get(key);
        if(requests!=null){requests.add(request);return;}
        requests=new ArrayList<>();requests.add(request);waiting.put(key,requests);
        workers.execute(() -> {
            Bitmap image=null;
            try { image=client.image(item); } catch(Exception ignored) { }
            final Bitmap result=image;
            final int tint=result==null?AmbientDrawable.DEFAULT:AmbientDrawable.sample(result);
            main.post(() -> {
                if(closed) return;
                List<Waiting> targets=waiting.remove(key);
                if(!client.currentSession())return;
                if(result!=null){cache.put(key,result);tints.put(key,tint);Set<ImageView> displayed=new HashSet<>();if(targets!=null)for(Waiting target:targets){ImageView imageView=target.view.get();if(imageView!=null&&key.equals(imageView.getTag())){imageView.setImageBitmap(result);UiMotion effect=Ui.motion(imageView.getContext());if(displayed.add(imageView)&&effect!=null)effect.image(imageView);if(target.ready!=null)target.ready.loaded(result,tint);}}}
                else failed.put(key,System.currentTimeMillis());
            });
        });
    }

    void close() { closed=true;workers.shutdownNow();main.removeCallbacksAndMessages(null);waiting.clear();cache.evictAll(); }
}
