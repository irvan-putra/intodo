package com.instinct.widget.merge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Merge engine for the local document. Dependency-free pure Java so it runs in
 * JVM unit tests and on Android unchanged.
 *
 * Model: every mutable field of an item is a last-writer-wins register ordered by
 * (hlc, deviceId); equal timestamps break ties by lexical deviceId - deterministic
 * on every replica. Deletes are an LWW tombstone register; restoring a trimmed item
 * is an explicit ordered op (deleted=false) or creates a NEW item id - both are
 * ordinary LWW writes, so there is no OR-set-style concurrent add/remove anomaly.
 * Non-destructive: every losing register value is kept in per-item history, and
 * conflicts() surfaces cross-device near-simultaneous losers so the user makes the
 * trim decision - convergence is automatic, conflict resolution is the user's.
 *
 * Required merge properties (asserted by MergeDocTest): commutative, associative,
 * idempotent, convergent under permuted and duplicated delivery with skewed clocks.
 */
public final class MergeDoc {

    /** Hybrid-logical-clock timestamp plus device id: the total order for registers. */
    public static final class Stamp implements Comparable<Stamp> {
        public final long hlc;
        public final String device;

        public Stamp(long hlc, String device) {
            this.hlc = hlc;
            this.device = device;
        }

        @Override
        public int compareTo(Stamp o) {
            int c = Long.compare(hlc, o.hlc);
            return c != 0 ? c : device.compareTo(o.device);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Stamp)) return false;
            Stamp s = (Stamp) o;
            return hlc == s.hlc && device.equals(s.device);
        }

        @Override
        public int hashCode() {
            return (int) (hlc ^ (hlc >>> 32)) * 31 + device.hashCode();
        }

        @Override
        public String toString() {
            return hlc + "@" + device;
        }

        public static Stamp parse(String s) {
            int at = s.lastIndexOf('@');
            return new Stamp(Long.parseLong(s.substring(0, at)), s.substring(at + 1));
        }
    }

    private static final class Reg {
        String value;
        Stamp stamp;
    }

    /** History entry: one losing (overwritten or late-arriving) register value. */
    public static final class HistoryEntry {
        public final String field;
        public final String value;
        public final Stamp stamp;

        HistoryEntry(String field, String value, Stamp stamp) {
            this.field = field;
            this.value = value;
            this.stamp = stamp;
        }

        String encode() {
            return field + "=" + value + "@" + stamp;
        }

        static HistoryEntry parse(String s) {
            int at = s.lastIndexOf('@');
            int eq = s.indexOf('=');
            int stampStart = s.lastIndexOf('@', at - 1); // value may contain '=', stamp never does
            String field = s.substring(0, eq);
            String value = s.substring(eq + 1, stampStart);
            // stamp is "hlc@device": hlc is numeric, so split from the last '@'
            String stampStr = s.substring(stampStart + 1);
            return new HistoryEntry(field, value, Stamp.parse(stampStr));
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof HistoryEntry)) return false;
            HistoryEntry h = (HistoryEntry) o;
            return field.equals(h.field) && value.equals(h.value) && stamp.equals(h.stamp);
        }

        @Override
        public int hashCode() {
            return field.hashCode() * 31 * 31 + value.hashCode() * 31 + stamp.hashCode();
        }
    }

    public static final class Item {
        public final String id;
        private final Map<String, Reg> fields = new HashMap<>();
        private Reg deleted; // tombstone register: value "true" or "false"
        private final Set<HistoryEntry> history = new LinkedHashSet<>();

        Item(String id) {
            this.id = id;
        }
    }

    public static final Set<String> FIELDS = new LinkedHashSet<>(java.util.Arrays.asList("title", "note", "due", "done"));
    public static final String DELETED = "deleted";

    private final Map<String, Item> items = new HashMap<>();

    /** Apply one op. Idempotent; older or duplicate stamps are recorded in history, never applied. */
    public void apply(String itemId, String field, String value, Stamp stamp) {
        if (!FIELDS.contains(field) && !DELETED.equals(field)) {
            throw new IllegalArgumentException("unknown field: " + field);
        }
        Item it = items.computeIfAbsent(itemId, Item::new);
        Reg cur = DELETED.equals(field) ? it.deleted : it.fields.get(field);
        if (cur != null) {
            int c = stamp.compareTo(cur.stamp);
            if (c == 0) return; // exact duplicate: no-op
            if (c < 0) {
                // late loser: keep it recoverable, change nothing
                it.history.add(new HistoryEntry(field, value, stamp));
                return;
            }
            // new winner: the old value is kept recoverable
            it.history.add(new HistoryEntry(field, cur.value, cur.stamp));
        }
        Reg next = new Reg();
        next.value = value;
        next.stamp = stamp;
        if (DELETED.equals(field)) it.deleted = next; else it.fields.put(field, next);
    }

    /** Merge every register and history entry of another replica into this one. */
    public void merge(MergeDoc other) {
        for (Item o : other.items.values()) {
            for (Map.Entry<String, Reg> e : o.fields.entrySet()) {
                apply(o.id, e.getKey(), e.getValue().value, e.getValue().stamp);
            }
            if (o.deleted != null) apply(o.id, DELETED, o.deleted.value, o.deleted.stamp);
            for (HistoryEntry h : o.history) apply(o.id, h.field, h.value, h.stamp);
        }
    }

    /** Deep copy (used by tests to build replicas). */
    public MergeDoc copy() {
        MergeDoc d = new MergeDoc();
        d.merge(this);
        return d;
    }

    public boolean isVisible(String itemId) {
        Item it = items.get(itemId);
        if (it == null) return false;
        return it.deleted == null || !"true".equals(it.deleted.value);
    }

    public String get(String itemId, String field) {
        Item it = items.get(itemId);
        if (it == null) return null;
        Reg r = it.fields.get(field);
        return r == null ? null : r.value;
    }

    public List<HistoryEntry> history(String itemId) {
        Item it = items.get(itemId);
        return it == null ? new ArrayList<>() : new ArrayList<>(it.history);
    }

    /**
     * Heuristic conflict surface for the UI: losing values written by a DIFFERENT
     * device than the current winner, within SKEW_WINDOW_MS of it. HLC stamps alone
     * cannot prove causality, so this approximates "concurrent versions" - exact
     * concurrency detection would need version vectors (future work, test-covered
     * when added). The conflict UI (multiverse trimming) offers the explicit
     * decision; convergence itself stays automatic via LWW.
     */
    public static final long SKEW_WINDOW_MS = 10 * 60 * 1000;

    public List<HistoryEntry> conflicts(String itemId) {
        Item it = items.get(itemId);
        List<HistoryEntry> out = new ArrayList<>();
        if (it == null) return out;
        for (HistoryEntry h : it.history) {
            Reg w = DELETED.equals(h.field) ? it.deleted : it.fields.get(h.field);
            if (w == null) continue;
            if (!w.stamp.device.equals(h.stamp.device)
                    && Math.abs(w.stamp.hlc - h.stamp.hlc) <= SKEW_WINDOW_MS) {
                out.add(h);
            }
        }
        return out;
    }

    /** Convergence equality: visible state, current registers, tombstones, and history sets. */
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof MergeDoc)) return false;
        MergeDoc d = (MergeDoc) o;
        if (!items.keySet().equals(d.items.keySet())) return false;
        for (Item it : items.values()) {
            Item ot = d.items.get(it.id);
            if (!regMapEquals(it.fields, ot.fields)) return false;
            if (!regEquals(it.deleted, ot.deleted)) return false;
            if (!it.history.equals(ot.history)) return false;
        }
        return true;
    }

    private static boolean regEquals(Reg a, Reg b) {
        if (a == null || b == null) return a == b;
        return a.value.equals(b.value) && a.stamp.equals(b.stamp);
    }

    private static boolean regMapEquals(Map<String, Reg> a, Map<String, Reg> b) {
        if (!a.keySet().equals(b.keySet())) return false;
        for (String k : a.keySet()) if (!regEquals(a.get(k), b.get(k))) return false;
        return true;
    }

    @Override
    public int hashCode() {
        return items.keySet().hashCode();
    }
}
