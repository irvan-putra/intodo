package com.instinct.widget.merge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.Test;

/**
 * Property tests for the merge engine. The word "CRDT" may only be used about this
 * app while these pass: commutativity, associativity, idempotence, and convergence
 * under permuted + duplicated delivery, skewed clocks, and delete/edit races.
 */
public class MergeDocTest {

    private static final String[] FIELDS = {"title", "note", "due", "done"};
    private static final String[] DEVICES = {"phone-a", "phone-b", "instinct"};

    private static final class Op {
        final String item, field, value;
        final MergeDoc.Stamp stamp;

        Op(String item, String field, String value, long hlc, String device) {
            this.item = item;
            this.field = field;
            this.value = value;
            this.stamp = new MergeDoc.Stamp(hlc, device);
        }

        void applyTo(MergeDoc d) {
            d.apply(item, field, value, stamp);
        }
    }

    /** Random op stream over 3 devices whose physical clocks are skewed by up to 5 minutes. */
    private static List<Op> randomOps(long seed, int count) {
        Random rnd = new Random(seed);
        long[] skew = {0, 120_000, -300_000}; // device clock offsets in ms
        List<Op> ops = new ArrayList<>();
        long base = 1_700_000_000_000L;
        for (int i = 0; i < count; i++) {
            int dev = rnd.nextInt(DEVICES.length);
            String item = "item-" + rnd.nextInt(4);
            String field = rnd.nextInt(10) == 0 ? "deleted" : FIELDS[rnd.nextInt(FIELDS.length)];
            String value = field.equals("deleted") ? String.valueOf(rnd.nextBoolean())
                    : field.equals("done") ? String.valueOf(rnd.nextBoolean())
                    : "v" + rnd.nextInt(1000);
            long phys = base + rnd.nextInt(3_600_000);
            ops.add(new Op(item, field, value, phys + skew[dev], DEVICES[dev]));
        }
        return ops;
    }

    private static MergeDoc replicaFrom(List<Op> ops, long seed, boolean duplicate) {
        List<Op> shuffled = new ArrayList<>(ops);
        Collections.shuffle(shuffled, new Random(seed));
        if (duplicate) shuffled.addAll(shuffled.subList(0, shuffled.size() / 2)); // duplicated delivery
        MergeDoc d = new MergeDoc();
        for (Op op : shuffled) op.applyTo(d);
        return d;
    }

    @Test
    public void convergesAcrossOrderAndDuplicatesWithSkewedClocks() {
        for (long seed = 1; seed <= 50; seed++) {
            List<Op> ops = randomOps(seed, 300);
            MergeDoc a = replicaFrom(ops, seed * 3 + 1, true);
            MergeDoc b = replicaFrom(ops, seed * 3 + 2, true);
            MergeDoc c = replicaFrom(ops, seed * 3 + 3, false);
            assertEquals("seed " + seed, a, b);
            assertEquals("seed " + seed, b, c);
        }
    }

    @Test
    public void idempotent() {
        MergeDoc d = new MergeDoc();
        MergeDoc.Stamp s = new MergeDoc.Stamp(100, "phone-a");
        d.apply("i1", "title", "hello", s);
        MergeDoc once = d.copy();
        d.apply("i1", "title", "hello", s);
        assertEquals(once, d);
        assertTrue(once.history("i1").isEmpty());
    }

    @Test
    public void mergeIsCommutativeAndAssociative() {
        for (long seed = 100; seed <= 120; seed++) {
            List<Op> ops = randomOps(seed, 120);
            MergeDoc a = replicaFrom(ops.subList(0, 40), seed, false);
            MergeDoc b = replicaFrom(ops.subList(40, 80), seed + 1, false);
            MergeDoc c = replicaFrom(ops.subList(80, 120), seed + 2, false);
            MergeDoc ab = a.copy();
            ab.merge(b);
            MergeDoc ba = b.copy();
            ba.merge(a);
            assertEquals("commutative seed " + seed, ab, ba);
            MergeDoc abC = ab.copy();
            abC.merge(c);
            MergeDoc bc = b.copy();
            bc.merge(c);
            MergeDoc aBc = a.copy();
            aBc.merge(bc);
            assertEquals("associative seed " + seed, abC, aBc);
        }
    }

    @Test
    public void tieBreakByDeviceIdIsDeterministic() {
        MergeDoc d1 = new MergeDoc();
        d1.apply("i1", "done", "true", new MergeDoc.Stamp(50, "phone-a"));
        d1.apply("i1", "done", "false", new MergeDoc.Stamp(50, "phone-b"));
        MergeDoc d2 = new MergeDoc();
        d2.apply("i1", "done", "false", new MergeDoc.Stamp(50, "phone-b"));
        d2.apply("i1", "done", "true", new MergeDoc.Stamp(50, "phone-a"));
        assertEquals(d1, d2);
        assertEquals("false", d1.get("i1", "done")); // phone-b wins: lexical deviceId tie-break
    }

    @Test
    public void deleteIsTombstoneAndNewerEditRevives() {
        MergeDoc d = new MergeDoc();
        d.apply("i1", "title", "task", new MergeDoc.Stamp(10, "phone-a"));
        d.apply("i1", "deleted", "true", new MergeDoc.Stamp(20, "phone-b"));
        assertFalse(d.isVisible("i1"));
        // an older edit arriving late must NOT resurrect the item
        d.apply("i1", "title", "task edited late", new MergeDoc.Stamp(15, "phone-a"));
        assertFalse(d.isVisible("i1"));
        // the late write is still the newest title, kept for a possible later revival
        assertEquals("task edited late", d.get("i1", "title"));
        // a newer field write revives it (deleted is an LWW boolean: still true here,
        // revival is an explicit op, not a side effect)
        d.apply("i1", "deleted", "false", new MergeDoc.Stamp(30, "phone-a"));
        assertTrue(d.isVisible("i1"));
    }

    @Test
    public void conflictsSurfaceCrossDeviceNearSimultaneousLosers() {
        MergeDoc d = new MergeDoc();
        // concurrent cross-device edits inside the skew window: conflict surfaced
        d.apply("i1", "title", "from-a", new MergeDoc.Stamp(1000, "phone-a"));
        d.apply("i1", "title", "from-b", new MergeDoc.Stamp(1060, "phone-b"));
        assertEquals(1, d.conflicts("i1").size());
        assertEquals("from-a", d.conflicts("i1").get(0).value);
        // same-device correction: not a conflict
        MergeDoc solo = new MergeDoc();
        solo.apply("i1", "title", "typo", new MergeDoc.Stamp(1000, "phone-a"));
        solo.apply("i1", "title", "fixed", new MergeDoc.Stamp(1010, "phone-a"));
        assertEquals(0, solo.conflicts("i1").size());
        // far-apart sequential edits across devices: not a conflict
        MergeDoc seq = new MergeDoc();
        seq.apply("i1", "title", "from-a", new MergeDoc.Stamp(1000, "phone-a"));
        seq.apply("i1", "title", "from-b", new MergeDoc.Stamp(1000 + MergeDoc.SKEW_WINDOW_MS + 1, "phone-b"));
        assertEquals(0, seq.conflicts("i1").size());
    }

    @Test
    public void losersStayInHistoryAndConverge() {
        MergeDoc a = new MergeDoc();
        a.apply("i1", "title", "from-a", new MergeDoc.Stamp(10, "phone-a"));
        a.apply("i1", "title", "from-b", new MergeDoc.Stamp(20, "phone-b"));
        MergeDoc b = new MergeDoc();
        b.apply("i1", "title", "from-b", new MergeDoc.Stamp(20, "phone-b"));
        b.apply("i1", "title", "from-a", new MergeDoc.Stamp(10, "phone-a"));
        assertEquals(a, b);
        assertEquals("from-b", a.get("i1", "title"));
        assertEquals(1, a.history("i1").size());
        assertEquals("from-a", a.history("i1").get(0).value);
    }
}
