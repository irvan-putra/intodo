package com.instinct.widget.canvas;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Property tests for the review-canvas camera math - mirrors
 * webxdc/test/canvas-math.test.mjs: camera invariants under random gesture
 * sequences, fit/focus geometry, pinch anchor stability, minimap round-trip,
 * persistence sanitize/fuzz robustness. Pure JVM, no Android runtime needed.
 */
public class CanvasMathTest {
    private static final double EPS = 1e-9;
    private static final List<String> IDS = new ArrayList<>(CanvasMath.CARD_SIZES.keySet());

    private static boolean near(double a, double b) {
        return near(a, b, 1e-6);
    }

    private static boolean near(double a, double b, double eps) {
        return Math.abs(a - b) <= eps * Math.max(1, Math.max(Math.abs(a), Math.abs(b)));
    }

    private static void assertCamValid(CanvasMath.Cam cam) {
        assertTrue("finite " + cam, Double.isFinite(cam.x) && Double.isFinite(cam.y) && Double.isFinite(cam.z));
        assertTrue("zoom bounds " + cam, cam.z >= CanvasMath.ZMIN - EPS && cam.z <= CanvasMath.ZMAX + EPS);
        assertTrue("x clamped " + cam, cam.x >= -120 - EPS && cam.x <= CanvasMath.WORLD_W + 120 + EPS);
        assertTrue("y clamped " + cam, cam.y >= -120 - EPS && cam.y <= CanvasMath.WORLD_H + 120 + EPS);
    }

    @Test
    public void screenWorldRoundTrip() {
        Random rnd = new Random(1);
        for (int i = 0; i < 2000; i++) {
            CanvasMath.Cam cam = new CanvasMath.Cam(
                (rnd.nextDouble() - 0.5) * 3 * CanvasMath.WORLD_W,
                (rnd.nextDouble() - 0.5) * 3 * CanvasMath.WORLD_H,
                CanvasMath.ZMIN + rnd.nextDouble() * (CanvasMath.ZMAX - CanvasMath.ZMIN));
            double vpW = 100 + rnd.nextDouble() * 1200, vpH = 100 + rnd.nextDouble() * 900;
            CanvasMath.Pos p = new CanvasMath.Pos(rnd.nextDouble() * vpW, rnd.nextDouble() * vpH);
            CanvasMath.Pos back = CanvasMath.toScreen(CanvasMath.toWorld(p, cam, vpW, vpH), cam, vpW, vpH);
            assertTrue("round-trip " + i, near(back.x, p.x) && near(back.y, p.y));
        }
    }

    @Test
    public void fitShowsWholeWorldWhenUnclamped() {
        Random rnd = new Random(2);
        for (int i = 0; i < 500; i++) {
            double vpW = 80 + rnd.nextDouble() * 2000, vpH = 80 + rnd.nextDouble() * 1400;
            CanvasMath.Cam cam = CanvasMath.fitCam(vpW, vpH);
            assertCamValid(cam);
            double idealZ = Math.min(vpW / CanvasMath.WORLD_W, vpH / CanvasMath.WORLD_H) * 0.98;
            if (idealZ >= CanvasMath.ZMIN && idealZ <= CanvasMath.ZMAX) {
                double[][] corners = {{0, 0}, {CanvasMath.WORLD_W, 0}, {0, CanvasMath.WORLD_H}, {CanvasMath.WORLD_W, CanvasMath.WORLD_H}};
                for (double[] c : corners) {
                    CanvasMath.Pos s = CanvasMath.toScreen(new CanvasMath.Pos(c[0], c[1]), cam, vpW, vpH);
                    assertTrue("corner visible " + i,
                        s.x >= -EPS && s.x <= vpW + EPS && s.y >= -EPS && s.y <= vpH + EPS);
                }
            }
        }
    }

    @Test
    public void focusCentersCard() {
        Random rnd = new Random(3);
        for (int i = 0; i < 500; i++) {
            String id = IDS.get(rnd.nextInt(IDS.size()));
            Map<String, CanvasMath.Pos> pos = CanvasMath.defaultPos();
            pos.put(id, new CanvasMath.Pos(rnd.nextDouble() * CanvasMath.WORLD_W, rnd.nextDouble() * CanvasMath.WORLD_H));
            double vpW = 200 + rnd.nextDouble() * 1200, vpH = 200 + rnd.nextDouble() * 900;
            CanvasMath.Cam cam = CanvasMath.focusCam(id, pos, vpW);
            assertCamValid(cam);
            CanvasMath.Pos ctr = CanvasMath.center(id, pos);
            // exact centering holds when the card center is inside the camera clamp range
            if (ctr.x >= -120 && ctr.x <= CanvasMath.WORLD_W + 120 && ctr.y >= -120 && ctr.y <= CanvasMath.WORLD_H + 120) {
                CanvasMath.Pos s = CanvasMath.toScreen(ctr, cam, vpW, vpH);
                assertTrue("focus x " + id, near(s.x, vpW / 2));
                assertTrue("focus y " + id, near(s.y, vpH / 2));
            }
        }
    }

    @Test
    public void pinchKeepsAnchorStable() {
        Random rnd = new Random(4);
        for (int i = 0; i < 2000; i++) {
            double vpW = 300 + rnd.nextDouble() * 1000, vpH = 300 + rnd.nextDouble() * 800;
            CanvasMath.Cam cam0 = new CanvasMath.Cam(
                CanvasMath.WORLD_W / 2, CanvasMath.WORLD_H / 2,
                CanvasMath.ZMIN + rnd.nextDouble() * (CanvasMath.ZMAX - CanvasMath.ZMIN));
            double d0 = 50 + rnd.nextDouble() * 300;
            CanvasMath.Pos mid = new CanvasMath.Pos(rnd.nextDouble() * vpW, rnd.nextDouble() * vpH);
            double d = 20 + rnd.nextDouble() * 500;
            CanvasMath.Cam cam1 = CanvasMath.pinchCam(d0, cam0, mid, d, vpW, vpH);
            assertCamValid(cam1);
            CanvasMath.Pos before = CanvasMath.toWorld(mid, cam0, vpW, vpH);
            CanvasMath.Pos after = CanvasMath.toWorld(mid, cam1, vpW, vpH);
            // guards precomputed from inputs, never from the function under test
            double expZ = Math.min(CanvasMath.ZMAX, Math.max(CanvasMath.ZMIN, cam0.z * (d / d0)));
            boolean zUnclamped = near(expZ, cam0.z * (d / d0));
            double ux = before.x - (mid.x - vpW / 2) / expZ;
            double uy = before.y - (mid.y - vpH / 2) / expZ;
            boolean xyUnclamped = ux >= -120 && ux <= CanvasMath.WORLD_W + 120 && uy >= -120 && uy <= CanvasMath.WORLD_H + 120;
            if (zUnclamped && xyUnclamped) {
                assertTrue("pinch zoom " + i, near(cam1.z, expZ));
                assertTrue("anchor stable " + i, near(before.x, after.x) && near(before.y, after.y));
            }
        }
    }

    @Test
    public void panMovesWorldByScreenDelta() {
        Random rnd = new Random(5);
        for (int i = 0; i < 1000; i++) {
            double vpW = 400, vpH = 600;
            CanvasMath.Cam cam = new CanvasMath.Cam(
                CanvasMath.WORLD_W / 2, CanvasMath.WORLD_H / 2,
                CanvasMath.ZMIN + rnd.nextDouble() * (CanvasMath.ZMAX - CanvasMath.ZMIN));
            double dx = (rnd.nextDouble() - 0.5) * 400, dy = (rnd.nextDouble() - 0.5) * 400;
            CanvasMath.Cam cam2 = CanvasMath.panCam(cam, dx, dy);
            CanvasMath.Pos w = new CanvasMath.Pos(rnd.nextDouble() * CanvasMath.WORLD_W, rnd.nextDouble() * CanvasMath.WORLD_H);
            CanvasMath.Pos s1 = CanvasMath.toScreen(w, cam, vpW, vpH);
            CanvasMath.Pos s2 = CanvasMath.toScreen(w, cam2, vpW, vpH);
            // interior case must be exact; guard precomputed from inputs
            double expX = cam.x - dx / cam.z, expY = cam.y - dy / cam.z;
            boolean interior = expX >= -120 && expX <= CanvasMath.WORLD_W + 120 && expY >= -120 && expY <= CanvasMath.WORLD_H + 120;
            if (interior) {
                assertTrue("pan cam " + i, near(cam2.x, expX) && near(cam2.y, expY));
                assertTrue("pan delta " + i, near(s2.x, s1.x + dx) && near(s2.y, s1.y + dy));
            }
        }
    }

    @Test
    public void zoomScalesDistanceFromCenter() {
        Random rnd = new Random(6);
        for (int i = 0; i < 1000; i++) {
            double vpW = 390, vpH = 700;
            CanvasMath.Cam cam = new CanvasMath.Cam(
                500 + rnd.nextDouble() * 800, 400 + rnd.nextDouble() * 600,
                CanvasMath.ZMIN + rnd.nextDouble() * (CanvasMath.ZMAX - CanvasMath.ZMIN));
            double f = 0.5 + rnd.nextDouble() * 2;
            CanvasMath.Cam cam2 = CanvasMath.zoomCam(cam, f);
            assertCamValid(cam2);
            CanvasMath.Pos w = new CanvasMath.Pos(rnd.nextDouble() * CanvasMath.WORLD_W, rnd.nextDouble() * CanvasMath.WORLD_H);
            CanvasMath.Pos s1 = CanvasMath.toScreen(w, cam, vpW, vpH);
            CanvasMath.Pos s2 = CanvasMath.toScreen(w, cam2, vpW, vpH);
            double expZ = Math.min(CanvasMath.ZMAX, Math.max(CanvasMath.ZMIN, cam.z * f)); // guard from inputs
            if (near(expZ, cam.z * f)) {
                assertTrue("zoom applied " + i, near(cam2.z, expZ));
                assertTrue("zoom x " + i, near(s2.x - vpW / 2, (s1.x - vpW / 2) * f));
                assertTrue("zoom y " + i, near(s2.y - vpH / 2, (s1.y - vpH / 2) * f));
            }
        }
    }

    @Test
    public void minimapRoundTrip() {
        Random rnd = new Random(7);
        for (int i = 0; i < 1000; i++) {
            double vpW = 100 + rnd.nextDouble() * 1200, vpH = 100 + rnd.nextDouble() * 900;
            CanvasMath.Cam cam = new CanvasMath.Cam(
                rnd.nextDouble() * CanvasMath.WORLD_W, rnd.nextDouble() * CanvasMath.WORLD_H,
                CanvasMath.ZMIN + rnd.nextDouble() * (CanvasMath.ZMAX - CanvasMath.ZMIN));
            CanvasMath.Rect r = CanvasMath.miniViewRect(cam, vpW, vpH);
            CanvasMath.Cam back = CanvasMath.camFromMiniPoint(r.left + r.width / 2, r.top + r.height / 2, cam);
            assertTrue("minimap round-trip " + i, near(back.x, cam.x) && near(back.y, cam.y));
            assertTrue("minimap keeps zoom " + i, near(back.z, cam.z));
        }
    }

    @Test
    public void randomGestureSequencesKeepCameraValid() {
        Random rnd = new Random(8);
        for (int run = 0; run < 50; run++) {
            double vpW = 320 + rnd.nextDouble() * 900, vpH = 320 + rnd.nextDouble() * 700;
            CanvasMath.Cam cam = CanvasMath.fitCam(vpW, vpH);
            for (int step = 0; step < 200; step++) {
                int kind = rnd.nextInt(4);
                if (kind == 0) cam = CanvasMath.panCam(cam, (rnd.nextDouble() - 0.5) * 800, (rnd.nextDouble() - 0.5) * 800);
                else if (kind == 1) cam = CanvasMath.zoomCam(cam, 0.3 + rnd.nextDouble() * 3);
                else if (kind == 2) cam = CanvasMath.fitCam(vpW, vpH);
                else {
                    double d0 = 30 + rnd.nextDouble() * 200;
                    CanvasMath.Pos mid = new CanvasMath.Pos(rnd.nextDouble() * vpW, rnd.nextDouble() * vpH);
                    cam = CanvasMath.pinchCam(d0, cam, mid, 10 + rnd.nextDouble() * 400, vpW, vpH);
                }
                assertCamValid(cam);
            }
        }
    }

    @Test
    public void dragRoundsToIntegers() {
        Random rnd = new Random(9);
        for (int i = 0; i < 1000; i++) {
            CanvasMath.Pos off = new CanvasMath.Pos(rnd.nextDouble() * 50, rnd.nextDouble() * 50);
            CanvasMath.Pos w = new CanvasMath.Pos(
                rnd.nextDouble() * 2 * CanvasMath.WORLD_W - 200, rnd.nextDouble() * 2 * CanvasMath.WORLD_H - 200);
            CanvasMath.Pos out = CanvasMath.dragPos(w, off);
            assertEquals("rounded x", Math.floor(out.x), out.x, 0);
            assertEquals("rounded y", Math.floor(out.y), out.y, 0);
            assertTrue("x delta", near(out.x, Math.round(w.x - off.x)));
            assertTrue("y delta", near(out.y, Math.round(w.y - off.y)));
        }
    }

    @Test
    public void sanitizeRepairsAndRejectsGarbage() {
        Map<String, CanvasMath.Pos> pos = new HashMap<>();
        pos.put("change", new CanvasMath.Pos(42, 24));
        CanvasMath.Saved s = CanvasMath.sanitize(new CanvasMath.Cam(100, 200, 0.5), pos);
        assertTrue(near(s.cam.x, 100) && near(s.cam.z, 0.5));
        assertTrue(near(s.pos.get("change").x, 42) && near(s.pos.get("change").y, 24));
        assertEquals(CanvasMath.CARD_SIZES.size(), s.pos.size()); // missing ids restored

        // zoom clamped, unknown ids dropped, invalid entry restored to default
        Map<String, CanvasMath.Pos> raw = new HashMap<>();
        raw.put("change", new CanvasMath.Pos(1, 2));
        raw.put("bogus", new CanvasMath.Pos(0, 0));
        raw.put("fuzz", null);
        CanvasMath.Saved repaired = CanvasMath.sanitize(new CanvasMath.Cam(10, 20, 99), raw);
        assertEquals(CanvasMath.ZMAX, repaired.cam.z, EPS);
        assertFalse(repaired.pos.containsKey("bogus"));
        assertTrue(near(repaired.pos.get("fuzz").x, CanvasMath.DEFAULT_POS.get("fuzz").x));

        // invalid camera rejects the whole record; nulls are "start fresh"
        assertNull(CanvasMath.sanitize(new CanvasMath.Cam(Double.NaN, 0, 1), null));
        assertNull(CanvasMath.sanitize(new CanvasMath.Cam(0, Double.POSITIVE_INFINITY, 1), null));
        assertNull(CanvasMath.sanitize(new CanvasMath.Cam(0, 0, Double.NaN), null));
        CanvasMath.Saved fresh = CanvasMath.sanitize(null, null);
        assertTrue(near(fresh.cam.x, CanvasMath.WORLD_W / 2) && near(fresh.cam.z, 0.25));
        assertEquals(CanvasMath.CARD_SIZES.size(), fresh.pos.size());
    }

    @Test
    public void sanitizeFuzzNeverThrows() {
        Random rnd = new Random(10);
        for (int i = 0; i < 10000; i++) {
            double cx = fuzzNum(rnd), cy = fuzzNum(rnd), cz = fuzzNum(rnd);
            Map<String, CanvasMath.Pos> raw = null;
            if (rnd.nextBoolean()) {
                raw = new HashMap<>();
                int n = rnd.nextInt(15);
                for (int j = 0; j < n; j++) {
                    String id = rnd.nextBoolean() ? IDS.get(rnd.nextInt(IDS.size())) : "junk" + j;
                    raw.put(id, rnd.nextInt(4) == 0 ? null : new CanvasMath.Pos(fuzzNum(rnd), fuzzNum(rnd)));
                }
            }
            CanvasMath.Saved out = CanvasMath.sanitize(new CanvasMath.Cam(cx, cy, cz), raw);
            if (out != null) {
                assertCamValid(out.cam);
                assertEquals(CanvasMath.CARD_SIZES.size(), out.pos.size());
                for (CanvasMath.Pos p : out.pos.values())
                    assertTrue(Double.isFinite(p.x) && Double.isFinite(p.y));
            }
        }
    }

    private static boolean overlap(CanvasMath.Rect a, CanvasMath.Rect b) {
        return a.left < b.left + b.width && b.left < a.left + a.width
            && a.top < b.top + b.height && b.top < a.top + a.height;
    }

    @Test
    public void chromeRectsStayInBoundsAndDisjoint() {
        Random rnd = new Random(11);
        for (int i = 0; i < 1000; i++) {
            double w = 200 + rnd.nextDouble() * 1200, h = 320 + rnd.nextDouble() * 900;
            CanvasMath.Rect[] c = CanvasMath.controlRects(w, h);
            CanvasMath.Rect mm = CanvasMath.minimapRect(w, h);
            assertEquals(3, c.length);
            for (CanvasMath.Rect r : c) {
                assertEquals(44, r.width, EPS);
                assertEquals(44, r.height, EPS);
                assertTrue("control in bounds " + i, r.left >= -EPS && r.top >= -EPS
                    && r.left + r.width <= w + EPS && r.top + r.height <= h + EPS);
            }
            for (int a = 0; a < 3; a++)
                for (int b = a + 1; b < 3; b++)
                    assertFalse("controls disjoint " + i, overlap(c[a], c[b]));
            assertTrue("minimap in bounds " + i, mm.left >= -EPS && mm.top >= -EPS
                && mm.left + mm.width <= w + EPS && mm.top + mm.height <= h + EPS);
            assertEquals(CanvasMath.miniHeight(), mm.height, EPS);
            for (CanvasMath.Rect r : c) assertFalse("minimap vs controls disjoint " + i, overlap(r, mm));
        }
    }

    private static double fuzzNum(Random rnd) {
        switch (rnd.nextInt(6)) {
            case 0: return Double.NaN;
            case 1: return rnd.nextBoolean() ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
            case 2: return (rnd.nextDouble() - 0.5) * 1e9;
            case 3: return (rnd.nextDouble() - 0.5) * 100;
            case 4: return 0;
            default: return rnd.nextDouble() * 3000;
        }
    }
}
