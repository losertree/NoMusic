package com.nomusic.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;

/** Optional LibreTranslate endpoint. Only lyric lines are sent when the user requests a translation. */
final class LyricTranslation {
    interface Translator { JSONArray translate(JSONArray lines) throws Exception; }

    static String run(String endpoint,String key,LyricsDocument document) throws Exception {
        URL url=new URL(endpoint);
        if(!"https".equals(url.getProtocol())||url.getHost().isEmpty()||url.getUserInfo()!=null||url.getRef()!=null)
            throw new IOException("请填写 HTTPS 翻译接口地址");
        return translate(document,input->request(url,key,input));
    }

    static String translate(LyricsDocument document,Translator translator) throws Exception {
        if(document.cues.isEmpty()||document.cues.size()>600)throw new IOException("歌词为空或超过翻译长度限制");
        StringBuilder result=new StringBuilder();
        for(int start=0;start<document.cues.size();start+=24) {
            JSONArray input=new JSONArray();
            int end=Math.min(document.cues.size(),start+24);
            for(int i=start;i<end;i++)if(!document.cues.get(i).original.isEmpty())input.put(document.cues.get(i).original);
            JSONArray translated=input.length()==0?new JSONArray():translator.translate(input);
            if(translated==null||translated.length()!=input.length())throw new IOException("翻译结果与歌词行数不符");
            int at=0;
            for(int i=start;i<end;i++) {
                LyricsDocument.Cue cue=document.cues.get(i);
                if(document.timed)result.append(LyricsDocument.timestamp(cue.timeMs));
                if(!cue.original.isEmpty())result.append(translated.getString(at++).replace('\r',' ').replace('\n',' '));
                result.append('\n');
            }
            if(result.length()>LyricsRepository.MAX_TEXT)throw new IOException("译文超过长度限制");
        }
        return result.toString();
    }

    private static JSONArray request(URL url,String key,JSONArray input) throws Exception {
        JSONObject body=new JSONObject().put("q",input).put("source","auto").put("target","zh").put("format","text");
        if(!key.isEmpty())body.put("api_key",key);
        HttpsURLConnection connection=(HttpsURLConnection)url.openConnection();
        try {
            connection.setSSLSocketFactory(TlsSocketFactory.get());connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10000);connection.setReadTimeout(20000);connection.setRequestMethod("POST");
            connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json; charset=utf-8");
            byte[] bytes=body.toString().getBytes("UTF-8");connection.setFixedLengthStreamingMode(bytes.length);
            try(OutputStream output=connection.getOutputStream()){output.write(bytes);}
            if(connection.getResponseCode()!=200)throw new IOException("翻译服务请求失败（HTTP "+connection.getResponseCode()+"）");
            JSONObject response;
            try(InputStream stream=connection.getInputStream()){response=new JSONObject(new String(StorageFiles.read(stream,256*1024),"UTF-8"));}
            return response.optJSONArray("translatedText");
        } finally {connection.disconnect();}
    }
}
