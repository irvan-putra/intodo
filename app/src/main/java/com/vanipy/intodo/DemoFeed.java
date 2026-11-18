package com.vanipy.intodo;
/** Accidental real-feed protection, not authentication or permission to share a payload. */
public final class DemoFeed {
    private DemoFeed() {}
    public static boolean isSynthetic(String json) {
        if (json == null) return false;
        try {
            FeedParser.Feed feed = FeedParser.parse(json);
            if (feed.sections.isEmpty()) return false;
            for (FeedParser.Section section : feed.sections) {
                if (!section.title.startsWith("DEMO - ")) return false;
                for (FeedParser.Item item : section.items) if (!item.id.startsWith("demo-")) return false;
            }
            return true;
        } catch (Exception e) { return false; }
    }
}
