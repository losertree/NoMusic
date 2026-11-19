package com.nomusic.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Small, serializable model shared by the library and the persistent queue. */
public final class MediaItem {
    public final String id, name, type, artist, album, albumId, imageId, imageTag, playlistEntryId;
    public final JSONArray artistItems;
    public final long durationMs;
    volatile SourceAudio sourceAudio;
    public boolean favorite;

    public MediaItem(JSONObject json) {
        id = json.optString("Id");
        name = json.optString("Name", "未命名");
        type = json.optString("Type", "Audio");
        sourceAudio=isAudio()?SourceAudio.read(json):SourceAudio.EMPTY;
        JSONArray artists = json.optJSONArray("Artists");
        StringBuilder names = new StringBuilder();
        if (artists != null) {
            for (int i = 0; i < artists.length(); i++) {
                if (i > 0) names.append(" / ");
                names.append(artists.optString(i));
            }
        }
        artist = names.length() > 0 ? names.toString() : json.optString("AlbumArtist", "未知艺术家");
        album = json.optString("Album");
        albumId = json.optString("AlbumId");
        playlistEntryId = json.optString("PlaylistItemId");
        JSONArray links = json.optJSONArray("ArtistItems");
        artistItems = links == null ? new JSONArray() : links;
        durationMs = json.optLong("RunTimeTicks") / 10000L;
        JSONObject data = json.optJSONObject("UserData");
        favorite = data != null && data.optBoolean("IsFavorite");
        JSONObject tags = json.optJSONObject("ImageTags");
        String primaryTag = tags == null ? "" : tags.optString("Primary");
        imageId = json.optString("_ImageId", primaryTag.length() > 0 ? id : json.optString("AlbumId", id));
        imageTag = json.optString("_ImageTag", primaryTag.length() > 0 ? primaryTag : json.optString("AlbumPrimaryImageTag"));
    }

    public boolean isAudio() { return "Audio".equals(type); }

    synchronized boolean fillSourceAudio(SourceAudio audio) {
        if(audio==null||audio==SourceAudio.EMPTY||!isAudio())return false;
        SourceAudio merged=sourceAudio.withMissingFactsFrom(audio);
        if(merged==sourceAudio)return false;
        sourceAudio=merged;return true;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("Id", id).put("Name", name).put("Type", type);
        json.put("Artists", new JSONArray().put(artist)).put("Album", album);
        json.put("AlbumId",albumId).put("ArtistItems",artistItems).put("PlaylistItemId",playlistEntryId);
        json.put("RunTimeTicks", durationMs * 10000L);
        json.put("_ImageId", imageId).put("_ImageTag", imageTag);
        json.put("UserData", new JSONObject().put("IsFavorite", favorite));
        sourceAudio.write(json);
        return json;
    }

    public static String time(long ms) {
        long seconds = Math.max(0, ms / 1000L);
        return (seconds / 60) + ":" + (seconds % 60 < 10 ? "0" : "") + (seconds % 60);
    }
}
