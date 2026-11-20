package com.vanipy.tindak;
/** Accidental real-feed protection, not authentication or permission to share a payload. */
public final class DemoFeed {
    private DemoFeed() {}
    /** Fixed fictional data only. No user, URL, key or network dependency. */
    public static final String BUILT_IN = "{\"generated_at\":\"\",\"sections\":[{\"id\":\"demo-reminders\",\"title\":\"DEMO - Reminders\",\"items\":[{\"id\":\"demo-water\",\"title\":\"DEMO: Water the imaginary plant\",\"note\":\"Fictional reminder. No alarm is scheduled.\"},{\"id\":\"demo-library\",\"title\":\"DEMO: Return a pretend library book\",\"note\":\"Sample only, not one of your real reminders.\"},{\"id\":\"demo-break\",\"title\":\"DEMO: Take a sample screen break\",\"note\":\"Bundled offline demonstration.\"}]},{\"id\":\"demo-tasks\",\"title\":\"DEMO - Tasks\",\"items\":[{\"id\":\"demo-picnic\",\"title\":\"DEMO: Plan a fictional picnic\",\"status\":\"in_progress\",\"note\":\"Synthetic task, kept separate from real work.\"},{\"id\":\"demo-bag\",\"title\":\"DEMO: Pack a make-believe bag\",\"note\":\"Demonstration item.\"},{\"id\":\"demo-map\",\"title\":\"DEMO: Draw an imaginary map\",\"note\":\"No feed setup or internet needed.\"}]}]}";

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
