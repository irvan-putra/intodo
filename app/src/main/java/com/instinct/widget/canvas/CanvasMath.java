package com.instinct.widget.canvas;

import java.util.HashMap;
import java.util.Map;

/**
 * Pure camera/layout math for the itdapk review canvas. No Android imports, so it
 * runs in plain JVM unit tests (CanvasMathTest). Mirror of the itdxdc port
 * (webxdc/js/canvas-math.js) - divergence between the two is a bug.
 *
 * Camera model: cam = (x, y, z) is the world point at the viewport center plus
 * zoom. screen = (world - cam.xy) * z + vp/2; world = cam.xy + (screen - vp/2) / z.
 */
public final class CanvasMath {
    private CanvasMath() {}

    public static final double WORLD_W = 1760, WORLD_H = 1420;
    public static final double ZMIN = 0.15, ZMAX = 2.5;
    public static final int MINI_W = 132, MINI_PAD = 4;
    public static final String STORE_KEY = "itdxdc-review-canvas.v1";

    public static final class Pos {
        public final double x, y;
        public Pos(double x, double y) { this.x = x; this.y = y; }
        @Override public String toString() { return "(" + x + "," + y + ")"; }
    }

    public static final class Cam {
        public final double x, y, z;
        public Cam(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
        @Override public String toString() { return "(" + x + "," + y + "," + z + ")"; }
    }

    public static final class Rect {
        public final double left, top, width, height;
        public Rect(double left, double top, double width, double height) {
            this.left = left; this.top = top; this.width = width; this.height = height;
        }
    }

    /** Mutable saved layout; sanitize repairs it in place. */
    public static final class Saved {
        public Cam cam;
        public Map<String, Pos> pos;
    }

    /** {width, height} per card id. */
    public static final Map<String, double[]> CARD_SIZES = new HashMap<>();
    public static final Map<String, Pos> DEFAULT_POS = new HashMap<>();
    public static final String[][] EDGES = {
        {"permanence", "change"},
        {"identity", "change"},
        {"reviews", "change"},
        {"source", "change"},
        {"rule", "change"},
        {"change", "gaps"},
        {"change", "property"},
        {"change", "mutation"},
        {"change", "fuzz"},
        {"change", "load"},
        {"change", "smoke"},
    };

    static {
        CARD_SIZES.put("change", new double[]{380, 300});
        CARD_SIZES.put("permanence", new double[]{380, 210});
        CARD_SIZES.put("identity", new double[]{360, 210});
        CARD_SIZES.put("reviews", new double[]{360, 200});
        CARD_SIZES.put("rule", new double[]{360, 180});
        CARD_SIZES.put("source", new double[]{360, 170});
        CARD_SIZES.put("gaps", new double[]{380, 200});
        CARD_SIZES.put("property", new double[]{320, 210});
        CARD_SIZES.put("mutation", new double[]{320, 210});
        CARD_SIZES.put("fuzz", new double[]{320, 210});
        CARD_SIZES.put("load", new double[]{320, 210});
        CARD_SIZES.put("smoke", new double[]{320, 210});

        DEFAULT_POS.put("change", new Pos(690, 430));
        DEFAULT_POS.put("permanence", new Pos(690, 60));
        DEFAULT_POS.put("identity", new Pos(160, 250));
        DEFAULT_POS.put("reviews", new Pos(1240, 250));
        DEFAULT_POS.put("source", new Pos(160, 620));
        DEFAULT_POS.put("rule", new Pos(1240, 620));
        DEFAULT_POS.put("gaps", new Pos(690, 820));
        // bottom row sits below the gaps card's real rendered height (~343dp at the
        // fixed 380dp width - taller than its 200dp layout estimate), not the estimate
        DEFAULT_POS.put("property", new Pos(40, 1200));
        DEFAULT_POS.put("mutation", new Pos(400, 1200));
        DEFAULT_POS.put("fuzz", new Pos(760, 1200));
        DEFAULT_POS.put("load", new Pos(1120, 1200));
        DEFAULT_POS.put("smoke", new Pos(1420, 1200));
    }

    public static double clampZ(double z) { return Math.min(ZMAX, Math.max(ZMIN, z)); }

    public static Cam clampCam(Cam c) {
        return new Cam(
            Math.min(WORLD_W + 120, Math.max(-120, c.x)),
            Math.min(WORLD_H + 120, Math.max(-120, c.y)),
            c.z);
    }

    public static Pos center(String id, Map<String, Pos> pos) {
        double[] s = CARD_SIZES.get(id);
        Pos p = pos.get(id);
        return new Pos(p.x + s[0] / 2, p.y + s[1] / 2);
    }

    public static Pos toWorld(Pos p, Cam cam, double vpW, double vpH) {
        return new Pos(cam.x + (p.x - vpW / 2) / cam.z, cam.y + (p.y - vpH / 2) / cam.z);
    }

    public static Pos toScreen(Pos w, Cam cam, double vpW, double vpH) {
        return new Pos((w.x - cam.x) * cam.z + vpW / 2, (w.y - cam.y) * cam.z + vpH / 2);
    }

    /** Fit the whole world into the viewport with a 2% margin. */
    public static Cam fitCam(double vpW, double vpH) {
        return new Cam(WORLD_W / 2, WORLD_H / 2,
            clampZ(Math.min(vpW / WORLD_W, vpH / WORLD_H) * 0.98));
    }

    /** Center a card and zoom so it (plus 80px slack) fills the width, capped at 1.2. */
    public static Cam focusCam(String id, Map<String, Pos> pos, double vpW) {
        Pos ctr = center(id, pos);
        return clampCam(new Cam(ctr.x, ctr.y, clampZ(Math.min(vpW / (CARD_SIZES.get(id)[0] + 80), 1.2))));
    }

    public static Cam zoomCam(Cam cam, double f) {
        return clampCam(new Cam(cam.x, cam.y, clampZ(cam.z * f)));
    }

    /** Pan by a screen-space delta (the world follows the pointer). */
    public static Cam panCam(Cam cam, double dxScreen, double dyScreen) {
        return clampCam(new Cam(cam.x - dxScreen / cam.z, cam.y - dyScreen / cam.z, cam.z));
    }

    /**
     * Anchored pinch zoom: the world point under the pinch midpoint at gesture start
     * (given d0 = start distance, z0/cam0 = start zoom/camera) stays under the current
     * midpoint.
     */
    public static Cam pinchCam(double d0, Cam cam0, Pos mid, double d, double vpW, double vpH) {
        double z = clampZ(cam0.z * (d / (d0 == 0 ? 1 : d0)));
        Pos mw = toWorld(mid, cam0, vpW, vpH);
        return clampCam(new Cam(mw.x - (mid.x - vpW / 2) / z, mw.y - (mid.y - vpH / 2) / z, z));
    }

    /** Drag a card: worldPt is the pointer in world space, off the grab offset. */
    public static Pos dragPos(Pos worldPt, Pos off) {
        return new Pos(Math.round(worldPt.x - off.x), Math.round(worldPt.y - off.y));
    }

    public static double miniScale() { return (MINI_W - MINI_PAD * 2.0) / WORLD_W; }
    public static double miniHeight() { return Math.round(WORLD_H * miniScale()) + MINI_PAD * 2.0; }

    public static Rect miniViewRect(Cam cam, double vpW, double vpH) {
        double ms = miniScale();
        return new Rect(
            MINI_PAD + (cam.x - vpW / (2 * cam.z)) * ms,
            MINI_PAD + (cam.y - vpH / (2 * cam.z)) * ms,
            (vpW / cam.z) * ms,
            (vpH / cam.z) * ms);
    }

    public static Cam camFromMiniPoint(double mx, double my, Cam cam) {
        double ms = miniScale();
        return clampCam(new Cam((mx - MINI_PAD) / ms, (my - MINI_PAD) / ms, cam.z));
    }

    public static Cam defaultCam() { return new Cam(WORLD_W / 2, WORLD_H / 2, 0.25); }

    public static Map<String, Pos> defaultPos() {
        Map<String, Pos> out = new HashMap<>();
        for (Map.Entry<String, Pos> e : DEFAULT_POS.entrySet()) out.put(e.getKey(), new Pos(e.getValue().x, e.getValue().y));
        return out;
    }

    private static boolean finite(Double d) { return d != null && Double.isFinite(d); }

    /**
     * Validate/repair a saved layout. Never throws: a structurally broken record
     * returns null (caller starts fresh); per-field problems are repaired - unknown
     * card ids dropped, missing ids restored to defaults, zoom and camera position
     * clamped. (The itdweb React original did not clamp the loaded camera; both
     * native ports do - stored state is untrusted input.)
     */
    public static Saved sanitize(Cam cam, Map<String, Pos> rawPos) {
        Saved out = new Saved();
        out.cam = defaultCam();
        if (cam != null) {
            if (!finite(cam.x) || !finite(cam.y) || !finite(cam.z)) return null;
            // clamp on load too: stored values are not under our control
            out.cam = clampCam(new Cam(cam.x, cam.y, clampZ(cam.z)));
        }
        out.pos = defaultPos();
        if (rawPos != null) {
            for (Map.Entry<String, Pos> e : rawPos.entrySet()) {
                if (!DEFAULT_POS.containsKey(e.getKey())) continue; // drop unknown ids
                Pos p = e.getValue();
                if (p != null && finite(p.x) && finite(p.y)) out.pos.put(e.getKey(), new Pos(p.x, p.y));
            }
        }
        return out;
    }
}
