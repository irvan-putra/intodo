package com.instinct.widget.canvas;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Card content for the review canvas, verbatim from the itdweb page source
 * (itdweb/src/App.tsx). Pure data, no Android imports.
 *
 * A card body is a list of blocks: PARA (paragraph, optionally muted), LIST
 * (bullet items) or LINK (a standalone URL line). Paragraph text is a list of
 * segments; a segment is either plain text or an inline chip ("real" marks a
 * grounded fact, "mock" an illustrative entry - the page's honesty rule).
 */
public final class CanvasContent {
    private CanvasContent() {}

    public enum BlockKind { PARA, MUTED, LIST, LINK }
    public enum Chip { NONE, REAL, MOCK }

    public static final class Segment {
        public final String text;     // null when chip != NONE
        public final Chip chip;
        public final String chipLabel;
        private Segment(String text, Chip chip, String chipLabel) {
            this.text = text; this.chip = chip; this.chipLabel = chipLabel;
        }
        public static Segment text(String t) { return new Segment(t, Chip.NONE, null); }
        public static Segment real() { return new Segment(null, Chip.REAL, "real"); }
        public static Segment mock(String label) { return new Segment(null, Chip.MOCK, label); }
    }

    public static final class Block {
        public final BlockKind kind;
        public final List<Segment> segments;  // PARA / MUTED
        public final List<String> items;      // LIST
        public final String linkUrl;          // LINK
        public final String linkLabel;        // LINK
        private Block(BlockKind kind, List<Segment> segments, List<String> items, String url, String label) {
            this.kind = kind; this.segments = segments; this.items = items;
            this.linkUrl = url; this.linkLabel = label;
        }
        public static Block para(Segment... segs) { return new Block(BlockKind.PARA, Arrays.asList(segs), null, null, null); }
        public static Block muted(Segment... segs) { return new Block(BlockKind.MUTED, Arrays.asList(segs), null, null, null); }
        public static Block list(String... items) { return new Block(BlockKind.LIST, null, Arrays.asList(items), null, null); }
        public static Block link(String url, String label) { return new Block(BlockKind.LINK, null, null, url, label); }
    }

    public static final class Card {
        public final String id, tag, title, icon;
        public final boolean center;
        public final List<Block> blocks;
        Card(String id, String tag, String title, String icon, boolean center, List<Block> blocks) {
            this.id = id; this.tag = tag; this.title = title; this.icon = icon;
            this.center = center; this.blocks = blocks;
        }
    }

    private static List<Card> build() {
        List<Card> out = new ArrayList<>();
        out.add(new Card("change", "Change card - example", "v0.4: narrow widget layout", "\uD83D\uDCDD", true, Arrays.asList(
            Block.para(Segment.text("The widget can shrink to a narrow 2x1 cell and reminders stay readable."), Segment.real()),
            Block.para(Segment.text("v0.4 is confirmed working on your phone - you verified it with a screenshot on Nov 12."), Segment.real()),
            Block.muted(Segment.text("Reviewed by: no one yet. Proved: nothing."), Segment.mock("mock entries")))));
        out.add(new Card("permanence", "Permanence, honestly", "What persists here", "\uD83D\uDCBE", false, Collections.singletonList(
            Block.list(
                "Card positions and your view are designed to be remembered on this device and browser. Implemented, not yet verified in your signed-in view.",
                "Not synced across devices; clearing browser storage resets it.",
                "Long-term ownership (export, backup) is an open question, not a claim."))));
        out.add(new Card("identity", "Unison idea", "Exact code identity", "\uD83D\uDD11", false, Arrays.asList(
            Block.para(Segment.text("Each card points at its change by content hash."), Segment.mock("mock 9f3c...a21e")),
            Block.muted(Segment.text("A review attaches to that hash, never to \"the latest code\". Same hash means same code - it says nothing about whether the code is good.")))));
        out.add(new Card("reviews", "crev idea", "Reviews", "\uD83D\uDD0D", false, Arrays.asList(
            Block.para(Segment.text("No one has reviewed v0.4.")),
            Block.muted(Segment.text("Signed reviews from people you choose to trust would appear here, with what each one checked. A review is human judgment, not a scan.")))));
        out.add(new Card("source", "Today", "The real code", "\uD83D\uDCE6", false, Arrays.asList(
            Block.para(Segment.text("The project source is public - the wall of code this canvas is meant to replace for review.")),
            Block.link("https://github.com/irvan-putra/intodo", "irvan-putra/intodo"))));
        out.add(new Card("rule", "The honest rule", "No hidden gaps", "\uD83D\uDCCF", false, Collections.singletonList(
            Block.para(Segment.text("No badge ever reads \"app is secure\". Each badge says exactly what was checked, and what was not checked stays visible in the same size next to it.")))));
        out.add(new Card("gaps", "Always visible", "Not checked for v0.4", "\u26A0\uFE0F", false, Collections.singletonList(
            Block.list(
                "Independent check of the reported property tests (7 Java + 9 JS, run by the builders).",
                "Any proof - none exists.",
                "Real cross-client sync: v0.1 failed your DeltaChat / ArcaneChat test (Nov 12) - add, delete and done were dead because the page never loaded the webxdc API script. v0.2 fixed that load, and you confirmed real DeltaChat/ArcaneChat sync on-device Nov 12 (add, done, delete all synced). v0.2's remaining gap - vague chat notices - is fixed in v0.3, which passes a 24-check two-peer simulator test; the v0.3 on-device retest is still pending. The v0.2 fix source is public in the widget repo (commit 715c86b)."))));
        out.add(new Card("property", "Evidence - reported", "Property testing", "\uD83D\uDD01", false, Arrays.asList(
            Block.para(Segment.text("Random operation sequences; merge invariants must hold (convergence, no lost items).")),
            Block.muted(Segment.text("Reported run by the builders on the public merge source (commit 715c86b): 7 Java + 9 JS tests pass. Not independently verified here. Samples cases - weaker than a proof.")))));
        out.add(new Card("mutation", "Planned evidence - not run", "Mutation testing", "\uD83E\uDDEC", false, Arrays.asList(
            Block.para(Segment.text("Deliberately break the merge code; the tests must catch each break.")),
            Block.muted(Segment.text("Shows whether the tests are effective, not whether the code is secure. Status: not run."), Segment.mock("plan")))));
        out.add(new Card("fuzz", "Planned evidence - not run", "Fuzz testing", "\uD83C\uDFB2", false, Arrays.asList(
            Block.para(Segment.text("Random and malformed input at the parsers and the sync protocol boundary.")),
            Block.muted(Segment.text("Scope: crash and rejection behavior, not full protocol correctness. Status: not run."), Segment.mock("plan")))));
        out.add(new Card("load", "Planned evidence - not run", "Load testing", "\uD83D\uDCC8", false, Arrays.asList(
            Block.para(Segment.text("Realistic multi-device operation volume and resource use.")),
            Block.muted(Segment.text("Local and simulated only - no hammering of external services. Status: not run."), Segment.mock("plan")))));
        out.add(new Card("smoke", "Planned evidence - not run", "Smoke testing", "\uD83D\uDCA8", false, Arrays.asList(
            Block.para(Segment.text("A short \"does it basically work\" pass per build: app starts, list loads, one add, one sync.")),
            Block.muted(Segment.text("Fast signal, shallow. Status: not run."), Segment.mock("plan")))));
        return out;
    }

    public static final List<Card> CARDS = Collections.unmodifiableList(build());
}
