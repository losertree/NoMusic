package com.nomusic.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.Locale;

/** Compact facts about the original file, independent of the chosen stream or download. */
final class SourceAudio {
    static final String FIELD="_SourceAudio";
    static final SourceAudio EMPTY=new SourceAudio("","",0,0,0,0);
    final String codec,container,badge;
    final int bitrate,rate,bits,channels;

    private SourceAudio(String codec,String container,int bitrate,int rate,int bits,int channels){
        this.codec=codec;this.container=container;this.bitrate=bitrate;this.rate=rate;this.bits=bits;this.channels=channels;
        String format=codec.isEmpty()?container:codec;
        boolean lossless=format.startsWith("pcm_")||oneOf(format,"flac","alac","ape","tta","wavpack","wv","wmalossless","truehd","mlp","aiff");
        if(format.startsWith("dsd")||oneOf(format,"dsf","dff"))badge="DSD";
        else if(lossless)badge=bits>=24&&rate>=48000?"Hi-Res":"SQ";
        else if(bitrate>0&&oneOf(format,"mp3","aac","mp2","opus","vorbis","wma","wmav2","ac3","eac3"))badge=bitrate>=256000?"HQ":"标准";
        else badge="";
    }

    String bitrateLabel(){return bitrate>0?Math.max(1,Math.round(bitrate/1000f))+" kbps":"";}

    SourceAudio withMissingFactsFrom(SourceAudio other){
        if(this==EMPTY)return other;
        if(other==EMPTY||!codec.isEmpty()&&!other.codec.isEmpty()&&!codec.equals(other.codec))return this;
        String c=codec.isEmpty()?other.codec:codec,format=container.isEmpty()?other.container:container;
        int b=bitrate==0?other.bitrate:bitrate,r=rate==0?other.rate:rate,
                depth=bits==0?other.bits:bits,n=channels==0?other.channels:channels;
        return c.equals(codec)&&format.equals(container)&&b==bitrate&&r==rate&&depth==bits&&n==channels?
                this:new SourceAudio(c,format,b,r,depth,n);
    }

    static SourceAudio read(JSONObject item){
        JSONObject compact=item.optJSONObject(FIELD);
        if(compact!=null)return new SourceAudio(value(compact,"codec"),value(compact,"container"),number(compact,"bitrate"),
                number(compact,"rate"),number(compact,"bits"),number(compact,"channels"));
        JSONArray sources=item.optJSONArray("MediaSources");
        JSONObject source=sources==null?null:sources.optJSONObject(0);
        JSONArray streams=source==null?null:source.optJSONArray("MediaStreams");
        JSONObject audio=audio(streams);
        if(audio==null){streams=item.optJSONArray("MediaStreams");audio=audio(streams);}
        String container=value(source,"Container");if(container.isEmpty())container=value(item,"Container");
        String codec=value(audio,"Codec");
        int bitrate=number(audio,"BitRate");
        if(bitrate==0&&singleAudio(streams)){bitrate=number(source,"Bitrate");if(bitrate==0)bitrate=number(item,"Bitrate");}
        int rate=number(audio,"SampleRate"),bits=number(audio,"BitDepth"),channels=number(audio,"Channels");
        return codec.isEmpty()&&container.isEmpty()&&bitrate==0&&rate==0&&bits==0&&channels==0?EMPTY:
                new SourceAudio(codec,container,bitrate,rate,bits,channels);
    }

    void write(JSONObject item)throws JSONException{
        if(this==EMPTY)return;
        item.put(FIELD,new JSONObject().put("codec",codec).put("container",container).put("bitrate",bitrate)
                .put("rate",rate).put("bits",bits).put("channels",channels));
    }

    private static JSONObject audio(JSONArray streams){
        JSONObject first=null;
        if(streams!=null)for(int i=0;i<streams.length();i++){
            JSONObject stream=streams.optJSONObject(i);
            if(stream!=null&&"audio".equals(value(stream,"Type"))){if(stream.optBoolean("IsDefault"))return stream;if(first==null)first=stream;}
        }
        return first;
    }
    private static boolean singleAudio(JSONArray streams){
        int count=0;
        if(streams!=null)for(int i=0;i<streams.length();i++){
            String type=value(streams.optJSONObject(i),"Type");
            if(("audio".equals(type)&&++count>1)||"video".equals(type))return false;
        }
        return true;
    }
    private static boolean oneOf(String format,String... values){for(String value:values)if(format.equals(value))return true;return false;}
    private static int number(JSONObject item,String key){return item==null?0:Math.max(0,item.optInt(key));}
    private static String value(JSONObject item,String key){return item==null||item.isNull(key)?"":item.optString(key).trim().toLowerCase(Locale.ROOT);}
}
