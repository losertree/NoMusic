package com.nomusic.app;

import org.json.JSONException;
import org.json.JSONObject;

/** The same immutable query drives visible pages and playback continuation. */
final class BrowseSpec {
    final String section,parentId,parentType,search,sort,genre;
    final boolean descending;

    BrowseSpec(String section,String parentId,String parentType,String search,String sort,boolean descending,String genre) {
        this.section=section;this.parentId=parentId;this.parentType=parentType;
        this.search=search;this.sort=sort;this.descending=descending;this.genre=genre;
    }

    BrowseSpec(JSONObject json) {
        this(json.optString("section","songs"),json.has("parent")?json.optString("parent"):null,
                json.has("type")?json.optString("type"):null,json.optString("search"),json.optString("sort"),
                json.optBoolean("descending"),json.optString("genre"));
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject().put("section",section).put("parent",parentId).put("type",parentType)
                .put("search",search).put("sort",sort).put("descending",descending).put("genre",genre);
    }

    static BrowseSpec songs() { return new BrowseSpec("songs",null,null,"","SortName",false,""); }
}
