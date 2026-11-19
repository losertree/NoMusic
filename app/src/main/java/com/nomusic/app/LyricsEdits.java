package com.nomusic.app;

import android.content.Context;
import java.io.File;
import org.json.JSONObject;

/** Optional local overrides survive cache eviction and never change the Emby library. */
final class LyricsEdits {
    static final LyricsEdits EMPTY=new LyricsEdits("","",0);
    final String original,translation;
    final int offset;
    LyricsEdits(String original,String translation,int offset) {
        this.original=original;this.translation=translation;this.offset=Math.max(-10000,Math.min(10000,offset));
    }

    private static File file(Context context,EmbyClient client,MediaItem item) {
        return new File(context.getFilesDir(),"lyric-edits/"+StorageFiles.key(client.accountKey()+"\n"+item.id)+".json");
    }

    static LyricsEdits read(Context context,EmbyClient client,MediaItem item) {
        File file=file(context,client,item);
        if(file.isFile())try {
            JSONObject json=StorageFiles.readJson(file,2*1024*1024);
            return new LyricsEdits(json.optString("original"),json.optString("translation"),json.optInt("offset"));
        } catch(Exception ignored) { }
        return EMPTY;
    }

    void write(Context context,EmbyClient client,MediaItem item) throws Exception {
        if(!client.currentSession())return;
        File file=file(context,client,item);
        if(original.isEmpty()&&translation.isEmpty()&&offset==0){if(file.exists()&&!file.delete())throw new java.io.IOException();return;}
        if(original.length()>LyricsRepository.MAX_TEXT||translation.length()>LyricsRepository.MAX_TEXT)throw new java.io.IOException();
        StorageFiles.writeJson(file,new JSONObject().put("original",original).put("translation",translation).put("offset",offset));
    }

    LyricsRepository.Result apply(LyricsRepository.Result base) {
        String text=original.isEmpty()?base.text:original;
        if(!translation.isEmpty())text=LyricsDocument.merge(text,translation);
        return text.equals(base.text)?base:new LyricsRepository.Result(text,base.error);
    }
}
