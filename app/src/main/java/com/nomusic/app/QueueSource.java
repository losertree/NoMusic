package com.nomusic.app;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.Random;

/** A saved cursor, not a copy of the whole library. Random windows visit every page once. */
final class QueueSource {
    final BrowseSpec spec;
    final int total,firstWindow,stride;
    int cursor;

    QueueSource(BrowseSpec spec,int offset,int total) { this(spec,offset,total,0,0); }

    private QueueSource(BrowseSpec spec,int cursor,int total,int firstWindow,int stride) {
        this.spec=spec;this.cursor=Math.max(0,cursor);this.total=Math.max(0,total);this.firstWindow=firstWindow;this.stride=stride;
    }

    static QueueSource random(int total,Random random) {
        int windows=windows(total),first=total>0?random.nextInt(total)/EmbyClient.PAGE_SIZE:0;
        int stride=1;
        if(windows>1)do { stride=1+random.nextInt(windows-1); } while(gcd(stride,windows)!=1);
        return new QueueSource(BrowseSpec.songs(),0,total,first,stride);
    }

    QueueSource(JSONObject json) throws JSONException {
        this(new BrowseSpec(json.getJSONObject("spec")),json.getInt("cursor"),json.getInt("total"),
                json.optInt("first"),json.optInt("stride"));
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject().put("spec",spec.toJson()).put("cursor",cursor).put("total",total)
                .put("first",firstWindow).put("stride",stride);
    }

    private static int gcd(int a,int b) { return b==0?a:gcd(b,a%b); }
    private static int windows(int total) { return (int)((total+EmbyClient.PAGE_SIZE-1L)/EmbyClient.PAGE_SIZE); }
    boolean random() { return stride>0; }
    boolean more() { return cursor<(random()?windows(total):total); }
    int offset() {
        if(!random())return cursor;
        return (int)((firstWindow+cursor*(long)stride)%windows(total))*EmbyClient.PAGE_SIZE;
    }
    void advance(EmbyClient.Page page) {
        if(random())cursor++;
        else if(page.received==0)cursor=total;
        else cursor=Math.min(Math.min(total,page.total),cursor+page.received);
    }
}
