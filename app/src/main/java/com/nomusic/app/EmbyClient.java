package com.nomusic.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;

/** Immutable account snapshot: queued requests cannot accidentally use another account's token. */
public final class EmbyClient {
    public final String server, userId, deviceId;
    private final String token,revision;
    private volatile String cacheAccount;
    private final AppPrefs prefs;
    public static final int PAGE_SIZE = 100;
    private static final String BROWSE_FIELDS="Artists,ArtistItems,Album,AlbumId,AlbumPrimaryImageTag,RunTimeTicks,UserData,ImageTags";

    public static final class ApiException extends IOException {
        public final int status;
        ApiException(int status) {
            super(status == 401 ? "Emby 登录已失效，请重新登录" : status == 403 ? "当前 Emby 账号没有此操作的权限" :
                    status == 404 ? "服务器未找到此接口或媒体" : "服务器请求失败（HTTP " + status + "）");
            this.status = status;
        }
    }

    public static final class Page {
        interface AudioRefresh { void ready(Page page); }
        public final List<MediaItem> items = new ArrayList<>();
        public final int total,received;
        private final BrowseCache.Result cached;
        Page(JSONObject json) {this(json,null);}
        private Page(JSONObject json,BrowseCache.Result cached) {
            this.cached=cached;
            JSONArray array = json.optJSONArray("Items");
            if (array != null) for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item != null && item.optString("Id").length() > 0) items.add(new MediaItem(item));
            }
            received=array==null?0:array.length();
            total = json.optInt("TotalRecordCount", received);
        }
        boolean legacyAudio(){return cached!=null&&cached.legacy;}
        void observeAudioRefresh(EmbyClient api,AudioRefresh callback) {
            if(legacyAudio())cached.observe(updated->{if(api.currentSession())callback.ready(new Page(updated));});
        }
        boolean fillSourceAudio(List<MediaItem> targets) {
            Map<String,SourceAudio> byId=new HashMap<>();
            for(MediaItem item:items)if(item.sourceAudio!=SourceAudio.EMPTY)byId.put(item.id,item.sourceAudio);
            boolean changed=false;
            for(MediaItem target:targets)if(target.fillSourceAudio(byId.get(target.id)))changed=true;
            return changed;
        }
    }

    public EmbyClient(AppPrefs prefs) {
        synchronized(AppPrefs.class) {
            server=prefs.server();userId=prefs.userId();token=prefs.token();deviceId=prefs.deviceId();
            revision=prefs.revision();
        }
        this.prefs=prefs;
    }
    EmbyClient(String server, String userId, String token, String deviceId) {
        this.server = server; this.userId = userId; this.token = token; this.deviceId = deviceId;
        prefs=null;revision="";
    }

    boolean authenticated() { return !token.isEmpty()&&!userId.isEmpty(); }
    boolean currentSession() { return prefs==null||prefs.matches(server,userId,token,revision); }
    boolean sameAccount(EmbyClient other) { return other!=null&&server.equals(other.server)&&userId.equals(other.userId); }
    String accountKey() { return server+"\n"+userId; }
    String cacheAccount(){String key=cacheAccount;if(key==null)cacheAccount=key=StorageFiles.key(accountKey());return key;}
    private JSONObject cachedPage(String resource,BrowseCache.Loader loader)throws Exception{return prefs==null?loader.load():BrowseCache.get(prefs.context()).page(this,resource,loader);}
    void invalidateBrowse(){if(prefs!=null)BrowseCache.get(prefs.context()).invalidate(this);}

    public static String normalizeServer(String input) throws IOException {
        String result = input.trim();
        if (!result.contains("://")) result = "http://" + result;
        try {
            URI uri = new URI(result);
            String scheme = uri.getScheme().toLowerCase(Locale.US);
            if ((!"https".equals(scheme) && !"http".equals(scheme)) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getPort() > 65535) throw new IOException("请输入有效的 HTTP / HTTPS 服务器地址");
            String path = uri.getPath() == null ? "" : uri.getPath();
            int web = path.indexOf("/web");
            if (web >= 0 && (web + 4 == path.length() || path.charAt(web + 4) == '/')) path = path.substring(0, web);
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            return new URI(scheme, null, uri.getHost(), uri.getPort(), path, null, null).toASCIIString();
        } catch (java.net.URISyntaxException e) { throw new IOException("服务器地址格式不正确"); }
    }

    public static JSONObject authenticate(String server, String username, String password, String device) throws Exception {
        EmbyClient client = new EmbyClient(server, "", "", device);
        JSONObject body = new JSONObject().put("Username", username).put("Pw", password);
        JSONObject result;
        try { result = client.json("POST", "/Users/AuthenticateByName", body); }
        catch (ApiException e) {
            if(e.status==401)throw new IOException("用户名或密码不正确",e);
            if (e.status != 404 || server.endsWith("/emby")) throw e;
            server += "/emby";
            try { result = new EmbyClient(server, "", "", device).json("POST", "/Users/AuthenticateByName", body); }
            catch(ApiException retry) { if(retry.status==401)throw new IOException("用户名或密码不正确",retry);throw retry; }
        }
        if (result.optString("AccessToken").length() == 0 || result.optJSONObject("User") == null
                || result.getJSONObject("User").optString("Id").length() == 0) throw new IOException("服务器没有返回有效登录信息");
        result.put("_Server", server);
        return result;
    }

    private String authorization() {
        return "MediaBrowser Client=\"NoMusic\", Device=\"Lynk CS11\", DeviceId=\"" + deviceId + "\", Version=\"" + BuildConfig.VERSION_NAME + "\"";
    }

    HttpURLConnection open(String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(server + path).openConnection();
        if (connection instanceof HttpsURLConnection) ((HttpsURLConnection) connection).setSSLSocketFactory(TlsSocketFactory.get());
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(18000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("X-Emby-Authorization", authorization());
        if (token.length() > 0) connection.setRequestProperty("X-Emby-Token", token);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "NoMusic/"+BuildConfig.VERSION_NAME+" (Android; CS11)");
        return connection;
    }

    int response(HttpURLConnection connection) throws IOException {
        int status=connection.getResponseCode();
        if(status==401&&prefs!=null)prefs.expire(server,userId,token,revision);
        return status;
    }

    public JSONObject json(String method, String path, JSONObject body) throws Exception {
        return json(method,path,body,null);
    }
    JSONObject json(String method,String path,JSONObject body,RetryPolicy.Token token)throws Exception {
        if(!"GET".equals(method))return jsonOnce(method,path,body,token);
        return RetryPolicy.read(()->jsonOnce(method,path,body,token),()->{if(token!=null)token.run();else RetryPolicy.interrupted();if(!currentSession())throw new java.io.InterruptedIOException("Session changed");});
    }

    private JSONObject jsonOnce(String method, String path, JSONObject body,RetryPolicy.Token token) throws Exception {
        HttpURLConnection connection = open(path);
        try {
            if(token!=null)token.attach(connection);
            connection.setRequestMethod(method);
            if (body != null) {
                byte[] bytes = body.toString().getBytes("UTF-8");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(bytes.length);
                OutputStream stream = connection.getOutputStream();
                try { stream.write(bytes); } finally { stream.close(); }
            }
            int status = response(connection);
            if (status < 200 || status >= 300) throw new ApiException(status);
            if (status == 204) return new JSONObject();
            InputStream stream = connection.getInputStream();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    bytes.write(buffer, 0, count);
                    if (bytes.size() > 8 * 1024 * 1024) throw new IOException("响应过大，请缩小查询范围");
                }
            } finally { stream.close(); }
            String text = bytes.toString("UTF-8");
            return text.trim().isEmpty() ? new JSONObject() : new JSONObject(text);
        } finally { if(token!=null)token.detach(connection);connection.disconnect(); }
    }

    public Page browse(String section, String parentId, String parentType, String search, int offset) throws Exception {
        return browse(new BrowseSpec(section,parentId,parentType,search,"",false,""),offset,PAGE_SIZE);
    }

    Page browse(BrowseSpec spec,int offset,int limit) throws Exception {
        return browse(spec,offset,limit,null);
    }
    Page browse(BrowseSpec spec,int offset,int limit,RetryPolicy.Token token) throws Exception {
        String section=spec.section,parentId=spec.parentId,parentType=spec.parentType,search=spec.search;
        String path = "/Users/" + Uri.encode(userId) + "/Items";
        boolean folders="folders".equals(section),playlist="Playlist".equals(parentType);
        if(folders&&parentId==null) {
            return new Page(cachedPage("music-views",()->{
            JSONArray views=json("GET","/Users/"+Uri.encode(userId)+"/Views",null).optJSONArray("Items"), filtered=new JSONArray();
            if(views!=null)for(int i=0;i<views.length();i++) {
                JSONObject view=views.optJSONObject(i);
                if(view!=null&&"music".equalsIgnoreCase(view.optString("CollectionType")))filtered.put(view);
            }
            return new JSONObject().put("Items",filtered);
            }));
        }
        Uri.Builder query = new Uri.Builder()
                .appendQueryParameter("Fields", BROWSE_FIELDS)
                .appendQueryParameter("StartIndex", String.valueOf(offset)).appendQueryParameter("Limit", String.valueOf(limit))
                .appendQueryParameter("EnableTotalRecordCount","true");
        String type = "Audio";
        if ("albums".equals(section)) type = "MusicAlbum";
        if ("search".equals(section)) type = "Audio,MusicAlbum,MusicArtist";
        boolean artistRoot = "artists".equals(section) && (parentId == null || parentId.isEmpty());
        if (artistRoot) { path = "/Artists"; query.appendQueryParameter("UserId", userId); }
        if ("playlists".equals(section)) { type = "Playlist"; query.appendQueryParameter("MediaTypes", "Audio"); }
        if ("favorites".equals(section)) query.appendQueryParameter("Filters", "IsFavorite");
        if ("recent".equals(section)) query.appendQueryParameter("Filters", "IsPlayed");
        if (!spec.genre.isEmpty()) query.appendQueryParameter("GenreIds",spec.genre);
        if (parentId != null && !parentId.isEmpty()) {
            type = "Audio";
            if ("MusicArtist".equals(parentType)) query.appendQueryParameter("ArtistIds", parentId);
            else if (playlist) { path = "/Playlists/" + Uri.encode(parentId) + "/Items"; query.appendQueryParameter("UserId", userId); }
            else query.appendQueryParameter("ParentId", parentId);
        }
        if(!playlist) {
            if(!folders&&!artistRoot)query.appendQueryParameter("IncludeItemTypes",type);
            query.appendQueryParameter("Recursive",String.valueOf(!folders));
            String sort=spec.sort.isEmpty()?("recent".equals(section)?"DatePlayed":
                    "MusicAlbum".equals(parentType)?"ParentIndexNumber,IndexNumber,SortName":"SortName"):spec.sort;
            query.appendQueryParameter("SortBy",sort).appendQueryParameter("SortOrder",
                    (spec.sort.isEmpty()&&"recent".equals(section)||spec.descending)?"Descending":"Ascending");
        }
        if (search != null && !search.trim().isEmpty()) query.appendQueryParameter("SearchTerm", search.trim());
        final String resource=path+"?"+query.build().getEncodedQuery();
        if(!artistRoot&&type.contains("Audio")){
            final String detailed=resource.replace("Fields="+Uri.encode(BROWSE_FIELDS),"Fields="+Uri.encode(BROWSE_FIELDS+",Container,MediaSources,MediaStreams"));
            BrowseCache.Loader loader=()->{
                JSONObject page;
                try{page=json("GET",detailed,null,token);}
                catch(ApiException error){if(error.status!=400&&error.status!=422)throw error;page=json("GET",resource,null,token);}
                JSONArray records=page.optJSONArray("Items");
                if(records!=null)for(int i=0;i<records.length();i++){
                    JSONObject item=records.optJSONObject(i);if(item==null)continue;
                    if("Audio".equals(item.optString("Type","Audio")))SourceAudio.read(item).write(item);
                    item.remove("MediaSources");item.remove("MediaStreams");
                }
                return page;
            };
            if(prefs==null)return new Page(loader.load());
            BrowseCache.Result result=BrowseCache.get(prefs.context()).result(this,detailed,resource,loader);
            return new Page(result.page,result);
        }
        return new Page(cachedPage(resource,()->json("GET",resource,null,token)));
    }

    Page genres(int offset) throws Exception {
        String resource="/MusicGenres?UserId="+Uri.encode(userId)+"&Recursive=true&IncludeItemTypes=Audio&SortBy=SortName&StartIndex="+offset+"&Limit="+PAGE_SIZE;
        return new Page(cachedPage(resource,()->json("GET",resource,null)));
    }

    String createPlaylist(String name,String itemId) throws Exception {
        Uri.Builder args=new Uri.Builder().appendQueryParameter("Name",name).appendQueryParameter("UserId",userId)
                .appendQueryParameter("MediaType","Audio");
        if(itemId!=null)args.appendQueryParameter("Ids",itemId);
        String id=json("POST","/Playlists?"+args.build().getEncodedQuery(),null).optString("Id");
        if(id.isEmpty())throw new IOException("服务器未返回新歌单 ID");
        invalidateBrowse();
        return id;
    }

    void addToPlaylist(String playlistId,String itemId) throws Exception {
        json("POST","/Playlists/"+Uri.encode(playlistId)+"/Items?Ids="+Uri.encode(itemId)+"&UserId="+Uri.encode(userId),null);
        invalidateBrowse();
    }

    void removeFromPlaylist(String playlistId,String entryId) throws Exception {
        if(entryId.isEmpty())throw new IOException("服务器没有提供歌单条目 ID");
        String path="/Playlists/"+Uri.encode(playlistId)+"/Items";
        String query="?EntryIds="+Uri.encode(entryId);
        try {json("DELETE",path+query,null);}
        catch(ApiException e){if(e.status!=405)throw e;json("POST",path+"/Delete"+query,null);}
        invalidateBrowse();
    }

    void renamePlaylist(String playlistId,String name) throws Exception {
        String id=Uri.encode(playlistId);
        // Emby stores playlists as library items; keep the rest of the metadata intact.
        JSONObject item=json("GET","/Users/"+Uri.encode(userId)+"/Items/"+id,null);
        json("POST","/Items/"+id,item.put("Name",name));
        invalidateBrowse();
    }

    void deletePlaylist(String playlistId) throws Exception {
        json("DELETE","/Items/"+Uri.encode(playlistId),null);
        invalidateBrowse();
    }

    public void favorite(MediaItem item, boolean value) throws Exception {
        json(value ? "POST" : "DELETE", "/Users/" + Uri.encode(userId) + "/FavoriteItems/" + Uri.encode(item.id), null);
        invalidateBrowse();
    }

    String streamPath(MediaItem item,AudioQuality quality,String session){
        Uri.Builder args=new Uri.Builder()
                .appendQueryParameter("UserId", userId).appendQueryParameter("DeviceId", deviceId)
                .appendQueryParameter("api_key", token).appendQueryParameter("Static",String.valueOf(quality.original()))
                .appendQueryParameter("PlaySessionId",session);
        if(!quality.original()){
            String bitrate=String.valueOf(quality.kbps*1000);
            args.appendQueryParameter("AudioCodec","mp3").appendQueryParameter("AudioBitRate",bitrate)
                    .appendQueryParameter("MaxStreamingBitrate",bitrate).appendQueryParameter("Container","mp3");
        }
        return "/Audio/"+Uri.encode(item.id)+(quality.original()?"/stream":"/stream.mp3")+"?"+args.build().getEncodedQuery();
    }

    public Bitmap image(MediaItem item) throws Exception {
        if(prefs!=null)return ArtworkCache.get(prefs.context()).load(this,item);
        byte[] bytes=imageBytes(item.imageId,item.imageTag);return bytes==null?null:BitmapFactory.decodeByteArray(bytes,0,bytes.length);
    }
    Bitmap cachedImage(MediaItem item){return prefs==null?null:ArtworkCache.get(prefs.context()).peek(this,item);}
    byte[] imageBytes(String id,String tag)throws Exception {
        return RetryPolicy.read(()->imageBytesOnce(id,tag),()->{RetryPolicy.interrupted();if(!currentSession())throw new java.io.InterruptedIOException("Session changed");});
    }
    private byte[] imageBytesOnce(String id,String tag)throws Exception {
        String path = "/Items/" + Uri.encode(id) + "/Images/Primary?MaxWidth=420&MaxHeight=420&Quality=85";
        if (!tag.isEmpty()) path += "&Tag=" + Uri.encode(tag);
        HttpURLConnection connection = open(path);
        try {
            int status=response(connection);if(status==404)return null;if(status!=200)throw new ApiException(status);
            InputStream stream = connection.getInputStream();
            try { return StorageFiles.read(stream,8*1024*1024); } finally { stream.close(); }
        } finally { connection.disconnect(); }
    }

    public void report(String event, MediaItem item, String session, long position, boolean paused, boolean original, int repeat) throws Exception {
        JSONObject body = new JSONObject().put("ItemId", item.id).put("UserId", userId)
                .put("PlaySessionId", session).put("PositionTicks", position * 10000L)
                .put("IsPaused", paused).put("CanSeek", true).put("PlayMethod", original ? "DirectStream" : "Transcode")
                .put("RepeatMode", repeat == 2 ? "RepeatOne" : repeat == 1 ? "RepeatAll" : "RepeatNone");
        json("POST", "/Sessions/Playing" + event, body);
    }

    public JSONObject lyrics(MediaItem item) throws Exception { return json("GET", "/Items/" + Uri.encode(item.id) + "/Lyrics", null); }

    public JSONObject lyricDetails(MediaItem item) throws Exception {
        return json("GET", "/Users/"+Uri.encode(userId)+"/Items/"+Uri.encode(item.id)+"?Fields=MediaSources,MediaStreams", null);
    }

    SourceAudio sourceAudio(MediaItem item) throws Exception {
        if(item.sourceAudio.bitrate>0)return item.sourceAudio;
        JSONObject facts=cachedPage("source-audio:"+item.id,()->{
            if(!authenticated())throw new IOException("Offline");
            JSONObject compact=new JSONObject();SourceAudio.read(lyricDetails(item)).write(compact);return compact;
        });
        return SourceAudio.read(facts);
    }

    public static String message(Throwable e) {
        if (e instanceof ApiException) return e.getMessage();
        if (e instanceof javax.net.ssl.SSLException) return "HTTPS 连接失败，请检查服务器证书和设备时间";
        if (e instanceof java.net.SocketTimeoutException) return "连接超时，请检查网络后重试";
        if (e instanceof java.net.UnknownHostException) return "无法解析服务器地址，请检查网络和地址";
        if (e instanceof JSONException) return "服务器响应格式不正确，请检查地址是否为 Emby 服务";
        if (e.getMessage()!=null && (e.getMessage().startsWith("用户名")||e.getMessage().startsWith("服务器未返回")||
                e.getMessage().startsWith("服务器没有提供")))return e.getMessage();
        return "无法连接服务器，请检查地址、网络及服务状态";
    }
}
