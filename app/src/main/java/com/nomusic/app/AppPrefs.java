package com.nomusic.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.UUID;

public final class AppPrefs {
    public static final String SESSION_CHANGED="com.nomusic.app.SESSION_CHANGED";
    final SharedPreferences store;
    private final Context context;

    public AppPrefs(Context context) {
        this.context=context.getApplicationContext();
        store = context.getSharedPreferences("nomusic", Context.MODE_PRIVATE);
        synchronized(AppPrefs.class) {
            if (!store.contains("device_id")) store.edit().putString("device_id", UUID.randomUUID().toString()).commit();
        }
    }

    public String server() { return store.getString("server", ""); }
    public String userId() { return store.getString("user_id", ""); }
    public String username() { return store.getString("username", ""); }
    public String token() { return store.getString("token", ""); }
    public String revision() { return store.getString("session_revision", ""); }
    public String deviceId() { return store.getString("device_id", ""); }
    public boolean loggedIn() { return token().length() > 0 && userId().length() > 0; }
    public boolean hasAccount() { return !server().isEmpty() && !userId().isEmpty(); }
    public boolean bool(String key, boolean fallback) { return store.getBoolean(key, fallback); }
    public void setBool(String key, boolean value) { store.edit().putBoolean(key, value).apply(); }
    AudioQuality audioQuality(){return AudioQuality.read(store.getString("stream_quality",""),bool("original",false));}
    boolean hasChosenAudioQuality(){return store.contains("stream_quality");}
    void setAudioQuality(AudioQuality quality){store.edit().putString("stream_quality",quality.key).apply();}
    String themeColor() { return store.getString("theme_color", "gold"); }
    void setThemeColor(String value) { store.edit().putString("theme_color", value).commit(); }
    Context context(){return context;}
    static final long MIB=1024L*1024,MAX_CACHE=20L*1024*1024*1024;
    long cacheLimit(boolean offline){return Math.max(256*MIB,Math.min(MAX_CACHE,store.getLong(offline?"offline_limit":"audio_cache_limit",offline?2L*1024*1024*1024:256*MIB)));}
    void setCacheLimit(boolean offline,long bytes){store.edit().putLong(offline?"offline_limit":"audio_cache_limit",Math.max(256*MIB,Math.min(MAX_CACHE,bytes))).apply();}

    public void saveSession(String server, String id, String name, String token) {
        synchronized(AppPrefs.class) {
            SharedPreferences.Editor edit=store.edit();
            if(!server.equals(server())||!id.equals(userId())) clearQueue(edit);
            edit.putString("server", server).putString("user_id", id).putString("username", name)
                    .putString("token", token).putString("session_revision", UUID.randomUUID().toString()).commit();
        }
        notifySession();
    }

    public void logout() {
        synchronized(AppPrefs.class) {
            clearQueue(store.edit()).remove("token").remove("user_id")
                    .putString("session_revision",UUID.randomUUID().toString()).commit();
        }
        notifySession();
    }

    private static SharedPreferences.Editor clearQueue(SharedPreferences.Editor edit) {
        return edit.remove("queue").remove("queue_index").remove("position").remove("queue_source")
                .remove("queue_priority").remove("queue_trimmed");
    }

    boolean matches(String server,String user,String token,String revision) {
        synchronized(AppPrefs.class) {
            return server.equals(server())&&user.equals(userId())&&token.equals(token())&&revision.equals(revision());
        }
    }

    void expire(String server,String user,String token,String revision) {
        synchronized(AppPrefs.class) {
            if(token.isEmpty()||!matches(server,user,token,revision))return;
            store.edit().remove("token").putString("session_revision",UUID.randomUUID().toString()).commit();
        }
        notifySession();
    }

    private void notifySession() {
        context.sendBroadcast(new android.content.Intent(SESSION_CHANGED).setPackage(context.getPackageName()));
    }
}
