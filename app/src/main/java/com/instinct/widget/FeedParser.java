package com.instinct.widget;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Parses the decrypted feed JSON. Uses org.json (provided by the Android framework). */
public class FeedParser {
    public static class Item {
        public String id, title, note, status;
    }
    public static class Section {
        public String id, title;
        public List<Item> items = new ArrayList<>();
    }
    public static class Feed {
        public String generatedAt;
        public List<Section> sections = new ArrayList<>();
    }

    public static Feed parse(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        Feed feed = new Feed();
        feed.generatedAt = root.optString("generated_at", "");
        JSONArray secs = root.optJSONArray("sections");
        if (secs != null) for (int i = 0; i < secs.length(); i++) {
            JSONObject s = secs.getJSONObject(i);
            Section sec = new Section();
            sec.id = s.optString("id", "");
            sec.title = s.optString("title", "");
            JSONArray arr = s.optJSONArray("items");
            if (arr != null) for (int j = 0; j < arr.length(); j++) {
                JSONObject o = arr.getJSONObject(j);
                Item it = new Item();
                it.id = o.optString("id", "");
                it.title = o.optString("title", "");
                it.note = o.optString("note", "");
                it.status = o.optString("status", "");
                sec.items.add(it);
            }
            feed.sections.add(sec);
        }
        return feed;
    }
}
