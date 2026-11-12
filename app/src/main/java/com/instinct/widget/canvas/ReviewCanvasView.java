package com.instinct.widget.canvas;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Native review-canvas view for itdapk: a bounded, pannable/zoomable canvas of
 * review cards with a minimap - the Android port of the itdweb page canvas
 * (itdweb/src/App.tsx) and the itdxdc port (webxdc/js/canvas.js). Cards, world
 * size, gestures, zoom limits and on-device persistence semantics match; camera
 * math is shared verbatim through {@link CanvasMath}.
 *
 * Theme follows the app (dark, amber accent). All gesture math runs in dp
 * units (1 world unit = 1dp); pixels enter only at draw time via canvas scale.
 *
 * Persistence: card positions and camera are remembered on this device only
 * (SharedPreferences, same honesty as the page: not synced, cleared with app
 * data). Nothing here touches the widget, sync engine or pairing data.
 */
public class ReviewCanvasView extends View {
    private static final int BG = 0xFF101828;
    private static final int PANEL = 0xFF1D2939;
    private static final int HAIRLINE = 0xFF344054;
    private static final int INK = 0xFFF2F4F7;
    private static final int INK2 = 0xFF98A2B3;
    private static final int INK3 = 0xFF667085;
    private static final int ACCENT = 0xFFF59E0B;
    private static final int REAL = 0xFF12B76A;

    private static final float CARD_PAD_TOP = 14, CARD_PAD_SIDE = 16;
    private static final float CARD_RADIUS = 12;
    private static final float BLOCK_GAP = 8;
    private static final float SLOP_DP = 6;

    private final float density;
    private final SharedPreferences prefs;

    private CanvasMath.Cam cam = CanvasMath.defaultCam();
    private Map<String, CanvasMath.Pos> pos = CanvasMath.defaultPos();
    private final CanvasMath.Saved saved;
    private boolean fitted = false;
    private boolean viewTouched = false;

    // gesture state
    private final HashMap<Integer, PointF> pointers = new HashMap<>();
    private String mode; // null | "pan" | "card" | "pinch" | "minimap"
    private String cardId;
    private PointF start, last;
    private CanvasMath.Pos grabOff;
    private boolean moved;
    private double pinchD0;
    private CanvasMath.Cam pinchCam0;
    private String tapFocusCard;
    private RectF tapLinkRect;
    private String tapLinkUrl;
    private int tapControl; // 0 none, 1 zoom in, 2 zoom out, 3 fit

    // cached text layouts per card (world units)
    private static final class BlockLayout {
        StaticLayout layout;
        String linkUrl;
        RectF localRect; // card-local rect for link hit-testing
    }
    private static final class CardLayout {
        final List<BlockLayout> blocks = new ArrayList<>();
        float contentH;
        RectF focusRect; // card-local
    }
    private final Map<String, CardLayout> layouts = new HashMap<>();

    // screen-space (dp) hit regions, recomputed on size change
    private final RectF zoomInRect = new RectF();
    private final RectF zoomOutRect = new RectF();
    private final RectF fitRect = new RectF();
    private final RectF minimapRect = new RectF();

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tagPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint focusTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint focusStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chromeFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chromeStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chromeText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint miniCardStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint miniIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint miniViewStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint bodyPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    public ReviewCanvasView(Context context) { this(context, null); }

    public ReviewCanvasView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        prefs = context.getSharedPreferences("review_canvas", Context.MODE_PRIVATE);

        gridPaint.setColor(PANEL);
        gridPaint.setStyle(Paint.Style.FILL);
        edgePaint.setColor(HAIRLINE);
        edgePaint.setStrokeWidth(2);
        cardFill.setColor(PANEL);
        cardFill.setStyle(Paint.Style.FILL);
        cardStroke.setStyle(Paint.Style.STROKE);
        cardStroke.setStrokeWidth(1);
        shadowPaint.setColor(0x59000000);
        shadowPaint.setStyle(Paint.Style.FILL);
        tagPaint.setColor(INK2);
        tagPaint.setTextSize(11);
        tagPaint.setLetterSpacing(0.04f);
        titlePaint.setColor(INK);
        titlePaint.setTextSize(15);
        titlePaint.setTypeface(Typeface.DEFAULT_BOLD);
        iconPaint.setTextSize(20);
        focusTextPaint.setColor(INK2);
        focusTextPaint.setTextSize(12);
        focusStroke.setColor(HAIRLINE);
        focusStroke.setStyle(Paint.Style.STROKE);
        focusStroke.setStrokeWidth(1);
        chromeFill.setColor(PANEL);
        chromeFill.setStyle(Paint.Style.FILL);
        chromeStroke.setColor(HAIRLINE);
        chromeStroke.setStyle(Paint.Style.STROKE);
        chromeStroke.setStrokeWidth(1);
        chromeText.setColor(INK);
        chromeText.setTextSize(18);
        miniCardStroke.setColor(HAIRLINE);
        miniCardStroke.setStyle(Paint.Style.STROKE);
        miniCardStroke.setStrokeWidth(1);
        miniIconPaint.setTextSize(10);
        miniViewStroke.setColor(ACCENT);
        miniViewStroke.setStyle(Paint.Style.STROKE);
        miniViewStroke.setStrokeWidth(2f);
        bodyPaint.setColor(INK);
        bodyPaint.setTextSize(13);

        saved = load();
        if (saved != null) {
            cam = saved.cam;
            pos = saved.pos;
        }
    }

    /** Fits the available screen height: capped at 600dp, at least 320dp when the parent allows. */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int cap = (int) (600 * density);
        int minH = (int) (320 * density);
        int h;
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            h = cap;
        } else {
            int avail = MeasureSpec.getSize(heightMeasureSpec);
            h = Math.min(cap, avail);
            if (h < minH) h = Math.min(minH, avail);
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutChrome(w / density, h / density);
        if (w <= 0 || h <= 0) return;
        if (!fitted) {
            fitted = true;
            if (saved == null) cam = CanvasMath.fitCam(w / density, h / density);
        } else if (!viewTouched && saved == null) {
            cam = CanvasMath.fitCam(w / density, h / density);
        }
    }

    // Chrome rects live in dp (CanvasMath owns the layout, JVM-tested); every
    // draw path must convert dp -> px exactly once. The first native build drew
    // dp values onto the px canvas, putting controls at 1/density of their
    // intended position (overlapping stack) and clipping the minimap.
    private void layoutChrome(float wDp, float hDp) {
        CanvasMath.Rect[] c = CanvasMath.controlRects(wDp, hDp);
        setRect(zoomInRect, c[0]);
        setRect(zoomOutRect, c[1]);
        setRect(fitRect, c[2]);
        setRect(minimapRect, CanvasMath.minimapRect(wDp, hDp));
    }

    private static void setRect(RectF out, CanvasMath.Rect r) {
        out.set((float) r.left, (float) r.top, (float) (r.left + r.width), (float) (r.top + r.height));
    }

    // ---- drawing ----

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(BG);
        drawGrid(canvas);
        float zd = (float) (cam.z * density);
        canvas.save();
        canvas.translate(getWidth() / 2f - (float) cam.x * zd, getHeight() / 2f - (float) cam.y * zd);
        canvas.scale(zd, zd);
        drawEdges(canvas);
        for (CanvasContent.Card card : CanvasContent.CARDS) drawCard(canvas, card);
        canvas.restore();
        drawMinimap(canvas);
        drawControls(canvas);
    }

    private void drawGrid(Canvas canvas) {
        float step = 28 * density;
        float r = density;
        for (float x = 0; x <= getWidth(); x += step)
            for (float y = 0; y <= getHeight(); y += step)
                canvas.drawCircle(x, y, r, gridPaint);
    }

    private void drawEdges(Canvas canvas) {
        for (String[] e : CanvasMath.EDGES) {
            CanvasMath.Pos a = CanvasMath.center(e[0], pos);
            CanvasMath.Pos b = CanvasMath.center(e[1], pos);
            canvas.drawLine((float) a.x, (float) a.y, (float) b.x, (float) b.y, edgePaint);
        }
    }

    private void drawCard(Canvas canvas, CanvasContent.Card card) {
        String id = card.id;
        double[] size = CanvasMath.CARD_SIZES.get(id);
        CanvasMath.Pos p = pos.get(id);
        CardLayout cl = cardLayout(card, (float) size[0]);
        float h = Math.max((float) size[1], cl.contentH);

        canvas.save();
        canvas.translate((float) p.x, (float) p.y);
        RectF r = new RectF(0, 0, (float) size[0], h);
        RectF shadow = new RectF(r);
        shadow.offset(0, 1);
        canvas.drawRoundRect(shadow, CARD_RADIUS, CARD_RADIUS, shadowPaint);
        canvas.drawRoundRect(r, CARD_RADIUS, CARD_RADIUS, cardFill);
        cardStroke.setColor(card.center ? ACCENT : HAIRLINE);
        canvas.drawRoundRect(r, CARD_RADIUS, CARD_RADIUS, cardStroke);

        // header: icon, tag + title, focus button
        float iconX = CARD_PAD_SIDE, headY = CARD_PAD_TOP;
        canvas.drawText(card.icon, iconX, headY + 17, iconPaint);
        float textX = iconX + 20 + 8;
        canvas.drawText(card.tag.toUpperCase(), textX, headY + 10, tagPaint);
        canvas.drawText(card.title, textX, headY + 10 + 2 + 15, titlePaint);
        if (cl.focusRect != null) {
            canvas.drawRoundRect(cl.focusRect, 4, 4, focusStroke);
            float tw = focusTextPaint.measureText("Focus");
            canvas.drawText("Focus", cl.focusRect.centerX() - tw / 2, cl.focusRect.centerY() + 4.5f, focusTextPaint);
        }

        // body blocks
        float y = Math.max(headY + 10 + 2 + 15 + 6, headY + 32) + BLOCK_GAP;
        for (BlockLayout bl : cl.blocks) {
            canvas.save();
            canvas.translate(CARD_PAD_SIDE, y);
            bl.layout.draw(canvas);
            canvas.restore();
            y += bl.layout.getHeight() + BLOCK_GAP;
        }
        canvas.restore();
    }

    private CardLayout cardLayout(CanvasContent.Card card, float cardW) {
        CardLayout cl = layouts.get(card.id);
        if (cl != null) return cl;
        cl = new CardLayout();
        float textW = cardW - 2 * CARD_PAD_SIDE;
        float y = Math.max(CARD_PAD_TOP + 10 + 2 + 15 + 6, CARD_PAD_TOP + 32) + BLOCK_GAP;
        for (CanvasContent.Block b : card.blocks) {
            BlockLayout bl = new BlockLayout();
            bl.layout = buildBlockLayout(b, (int) textW);
            if (b.kind == CanvasContent.BlockKind.LINK) {
                bl.linkUrl = b.linkUrl;
                bl.localRect = new RectF(CARD_PAD_SIDE, y, CARD_PAD_SIDE + textW, y + bl.layout.getHeight());
            }
            cl.blocks.add(bl);
            y += bl.layout.getHeight() + BLOCK_GAP;
        }
        cl.contentH = y - BLOCK_GAP + CARD_PAD_TOP;
        float focusW = Math.max(44, focusTextPaint.measureText("Focus") + 20);
        cl.focusRect = new RectF(cardW - CARD_PAD_SIDE - focusW, CARD_PAD_TOP, cardW - CARD_PAD_SIDE, CARD_PAD_TOP + 32);
        layouts.put(card.id, cl);
        return cl;
    }

    private StaticLayout buildBlockLayout(CanvasContent.Block b, int widthPx) {
        SpannableStringBuilder ssb = new SpannableStringBuilder();
        if (b.kind == CanvasContent.BlockKind.LIST) {
            for (int i = 0; i < b.items.size(); i++) {
                if (i > 0) ssb.append('\n');
                int start = ssb.length();
                ssb.append("\u2022  ").append(b.items.get(i));
                ssb.setSpan(new android.text.style.LeadingMarginSpan.Standard(0, 18), start, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        } else if (b.kind == CanvasContent.BlockKind.LINK) {
            int start = ssb.length();
            ssb.append(b.linkLabel);
            ssb.setSpan(new UnderlineSpan(), start, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            ssb.setSpan(new ForegroundColorSpan(ACCENT), start, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else {
            for (CanvasContent.Segment seg : b.segments) {
                if (seg.chip == CanvasContent.Chip.NONE) {
                    ssb.append(seg.text);
                } else {
                    int start = ssb.length();
                    ssb.append(' ').append(seg.chipLabel);
                    int color = seg.chip == CanvasContent.Chip.REAL ? REAL : INK2;
                    ssb.setSpan(new ForegroundColorSpan(color), start, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    ssb.setSpan(new AbsoluteSizeSpan(11), start, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    if (seg.chip == CanvasContent.Chip.REAL)
                        ssb.setSpan(new StyleSpan(Typeface.BOLD), start, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            if (b.kind == CanvasContent.BlockKind.MUTED)
                ssb.setSpan(new ForegroundColorSpan(INK2), 0, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        TextPaint paint = new TextPaint(bodyPaint);
        return StaticLayout.Builder.obtain(ssb, 0, ssb.length(), paint, Math.max(1, widthPx))
            .setLineSpacing(0f, 1.45f)
            .build();
    }

    private void drawMinimap(Canvas canvas) {
        RectF mr = minimapRect; // dp
        canvas.save();
        canvas.translate(mr.left * density, mr.top * density); // dp -> px, once
        canvas.scale(density, density);
        RectF local = new RectF(0, 0, mr.width(), mr.height());
        canvas.drawRoundRect(local, 8, 8, chromeFill);
        canvas.drawRoundRect(local, 8, 8, chromeStroke);
        canvas.clipRect(local); // overflow hidden, like the CSS minimap
        double ms = CanvasMath.miniScale();
        for (CanvasContent.Card card : CanvasContent.CARDS) {
            CanvasMath.Pos p = pos.get(card.id);
            double[] s = CanvasMath.CARD_SIZES.get(card.id);
            RectF r = new RectF(
                (float) (CanvasMath.MINI_PAD + p.x * ms),
                (float) (CanvasMath.MINI_PAD + p.y * ms),
                (float) (CanvasMath.MINI_PAD + p.x * ms + Math.max(14, s[0] * ms)),
                (float) (CanvasMath.MINI_PAD + p.y * ms + Math.max(13, s[1] * ms)));
            miniCardStroke.setColor(card.center ? ACCENT : HAIRLINE);
            canvas.drawRoundRect(r, 2, 2, miniCardStroke);
            canvas.drawText(card.icon, r.centerX() - 5, r.centerY() + 3.5f, miniIconPaint);
        }
        CanvasMath.Rect vr = CanvasMath.miniViewRect(cam, getWidth() / density, getHeight() / density);
        canvas.drawRect((float) vr.left, (float) vr.top,
            (float) (vr.left + vr.width), (float) (vr.top + vr.height), miniViewStroke);
        canvas.restore();
    }

    private void drawControls(Canvas canvas) {
        drawChromeButton(canvas, zoomInRect, "+", 18);
        drawChromeButton(canvas, zoomOutRect, "-", 18);
        drawChromeButton(canvas, fitRect, "Fit", 12);
    }

    private void drawChromeButton(Canvas canvas, RectF r, String label, float textSize) {
        canvas.save();
        canvas.translate(r.left * density, r.top * density); // dp -> px, once
        canvas.scale(density, density);
        RectF local = new RectF(0, 0, r.width(), r.height());
        canvas.drawRoundRect(local, 8, 8, chromeFill);
        canvas.drawRoundRect(local, 8, 8, chromeStroke);
        chromeText.setTextSize(textSize);
        chromeText.setTypeface(textSize <= 12 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        float tw = chromeText.measureText(label);
        canvas.drawText(label, (r.width() - tw) / 2, r.height() / 2 + textSize * 0.35f, chromeText);
        canvas.restore();
    }

    // ---- gestures (math in dp units; see CanvasMath) ----

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                pointers.clear();
                PointF p = pt(event, 0);
                pointers.put(event.getPointerId(0), p);
                moved = false;
                start = last = p;
                tapFocusCard = null; tapLinkRect = null; tapLinkUrl = null; tapControl = 0;
                float x = p.x / density, y = p.y / density;
                int ctl = hitControl(x, y);
                if (ctl != 0) {
                    tapControl = ctl;
                    mode = null;
                } else if (minimapRect.contains(x, y)) {
                    mode = "minimap";
                    viewTouched = true;
                    cam = CanvasMath.camFromMiniPoint(x - minimapRect.left, y - minimapRect.top, cam);
                    invalidate();
                } else {
                    String hit = hitCard(x, y);
                    if (hit != null) {
                        mode = "card";
                        cardId = hit;
                        CanvasMath.Pos w = toWorldDp(p);
                        CanvasMath.Pos cp = pos.get(hit);
                        grabOff = new CanvasMath.Pos(w.x - cp.x, w.y - cp.y);
                        CardLayout cl = layouts.get(hit);
                        if (cl != null) {
                            CanvasMath.Pos lp = new CanvasMath.Pos(w.x - cp.x, w.y - cp.y);
                            if (cl.focusRect != null && cl.focusRect.contains((float) lp.x, (float) lp.y)) tapFocusCard = hit;
                            for (BlockLayout bl : cl.blocks)
                                if (bl.localRect != null && bl.localRect.contains((float) lp.x, (float) lp.y)) {
                                    tapLinkRect = bl.localRect; tapLinkUrl = bl.linkUrl;
                                }
                        }
                    } else {
                        mode = "pan";
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                int idx = event.getActionIndex();
                pointers.put(event.getPointerId(idx), pt(event, idx));
                if (pointers.size() == 2) {
                    mode = "pinch";
                    PointF[] ab = twoPointers();
                    pinchD0 = dist(ab[0], ab[1]);
                    pinchCam0 = cam;
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < event.getPointerCount(); i++) {
                    PointF cur = pointers.get(event.getPointerId(i));
                    if (cur != null) cur.set(event.getX(i), event.getY(i));
                }
                PointF primary = pointers.get(event.getPointerId(0));
                if (primary == null) return true;
                if (!moved && mode != null && start != null && dist(primary, start) > SLOP_DP * density) moved = true;
                if ("pinch".equals(mode) && pointers.size() >= 2) {
                    PointF[] ab = twoPointers();
                    float d = dist(ab[0], ab[1]);
                    CanvasMath.Pos mid = new CanvasMath.Pos(
                        (ab[0].x + ab[1].x) / 2 / density, (ab[0].y + ab[1].y) / 2 / density);
                    cam = CanvasMath.pinchCam(pinchD0, pinchCam0, mid, d, getWidth() / density, getHeight() / density);
                    invalidate();
                } else if ("card".equals(mode) && cardId != null) {
                    pos.put(cardId, CanvasMath.dragPos(toWorldDp(primary), grabOff));
                    invalidate();
                } else if ("pan".equals(mode)) {
                    cam = CanvasMath.panCam(cam,
                        (primary.x - last.x) / density, (primary.y - last.y) / density);
                    last = primary;
                    invalidate();
                } else if ("minimap".equals(mode)) {
                    cam = CanvasMath.camFromMiniPoint(
                        primary.x / density - minimapRect.left, primary.y / density - minimapRect.top, cam);
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                pointers.remove(event.getPointerId(event.getActionIndex()));
                if ("pinch".equals(mode) && pointers.size() == 1) {
                    PointF remaining = pointers.values().iterator().next();
                    mode = "pan";
                    start = last = remaining;
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                PointF p = pt(event, 0);
                if (!moved) handleTap(p);
                if (mode != null) {
                    viewTouched = true;
                    persist();
                }
                mode = null; cardId = null;
                pointers.clear();
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                pointers.clear();
                mode = null; cardId = null;
                return true;
        }
        return super.onTouchEvent(event);
    }

    private void handleTap(PointF p) {
        float x = p.x / density, y = p.y / density;
        if (tapControl != 0 && hitControl(x, y) == tapControl) {
            viewTouched = true;
            if (tapControl == 1) cam = CanvasMath.zoomCam(cam, 1.35);
            else if (tapControl == 2) cam = CanvasMath.zoomCam(cam, 1 / 1.35);
            else cam = CanvasMath.fitCam(getWidth() / density, getHeight() / density);
            persist();
            invalidate();
            return;
        }
        if (tapFocusCard != null) {
            CanvasMath.Pos w = toWorldDp(p);
            CanvasMath.Pos cp = pos.get(tapFocusCard);
            CardLayout cl = layouts.get(tapFocusCard);
            if (cl != null && cl.focusRect != null
                && cl.focusRect.contains((float) (w.x - cp.x), (float) (w.y - cp.y))) {
                viewTouched = true;
                cam = CanvasMath.focusCam(tapFocusCard, pos, getWidth() / density);
                persist();
                invalidate();
                return;
            }
        }
        if (tapLinkRect != null && tapLinkUrl != null) {
            CanvasMath.Pos w = toWorldDp(p);
            CanvasMath.Pos cp = pos.get(cardId);
            if (cp != null && tapLinkRect.contains((float) (w.x - cp.x), (float) (w.y - cp.y))) {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(tapLinkUrl));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(i);
            }
        }
    }

    private int hitControl(float xDp, float yDp) {
        if (zoomInRect.contains(xDp, yDp)) return 1;
        if (zoomOutRect.contains(xDp, yDp)) return 2;
        if (fitRect.contains(xDp, yDp)) return 3;
        return 0;
    }

    /** Topmost card under a viewport point (dp), using measured content heights. */
    private String hitCard(float xDp, float yDp) {
        CanvasMath.Pos w = CanvasMath.toWorld(new CanvasMath.Pos(xDp, yDp), cam,
            getWidth() / density, getHeight() / density);
        List<CanvasContent.Card> cards = CanvasContent.CARDS;
        for (int i = cards.size() - 1; i >= 0; i--) {
            CanvasContent.Card card = cards.get(i);
            CanvasMath.Pos p = pos.get(card.id);
            double[] s = CanvasMath.CARD_SIZES.get(card.id);
            CardLayout cl = layouts.get(card.id);
            float h = Math.max((float) s[1], cl != null ? cl.contentH : (float) s[1]);
            if (w.x >= p.x && w.x <= p.x + s[0] && w.y >= p.y && w.y <= p.y + h) return card.id;
        }
        return null;
    }

    private CanvasMath.Pos toWorldDp(PointF pPx) {
        return CanvasMath.toWorld(new CanvasMath.Pos(pPx.x / density, pPx.y / density), cam,
            getWidth() / density, getHeight() / density);
    }

    private PointF pt(MotionEvent e, int idx) { return new PointF(e.getX(idx), e.getY(idx)); }

    private PointF[] twoPointers() {
        List<PointF> all = new ArrayList<>(pointers.values());
        return new PointF[]{all.get(0), all.get(1)};
    }

    private static float dist(PointF a, PointF b) {
        return (float) Math.hypot(a.x - b.x, a.y - b.y);
    }

    // ---- persistence (this device only; not synced) ----

    private void persist() {
        try {
            JSONObject c = new JSONObject();
            c.put("x", cam.x); c.put("y", cam.y); c.put("z", cam.z);
            JSONObject p = new JSONObject();
            for (Map.Entry<String, CanvasMath.Pos> e : pos.entrySet()) {
                JSONObject q = new JSONObject();
                q.put("x", e.getValue().x); q.put("y", e.getValue().y);
                p.put(e.getKey(), q);
            }
            JSONObject o = new JSONObject();
            o.put("cam", c); o.put("pos", p);
            prefs.edit().putString("layout", o.toString()).apply();
        } catch (Exception ignored) { /* persistence is best-effort */ }
    }

    private CanvasMath.Saved load() {
        String s = prefs.getString("layout", null);
        if (s == null) return null;
        try {
            JSONObject o = new JSONObject(s);
            JSONObject c = o.optJSONObject("cam");
            CanvasMath.Cam loadedCam = c == null ? null : new CanvasMath.Cam(
                c.optDouble("x", Double.NaN), c.optDouble("y", Double.NaN), c.optDouble("z", Double.NaN));
            Map<String, CanvasMath.Pos> rawPos = null;
            JSONObject p = o.optJSONObject("pos");
            if (p != null) {
                rawPos = new HashMap<>();
                Iterator<String> keys = p.keys();
                while (keys.hasNext()) {
                    String id = keys.next();
                    JSONObject q = p.optJSONObject(id);
                    rawPos.put(id, q == null ? null : new CanvasMath.Pos(
                        q.optDouble("x", Double.NaN), q.optDouble("y", Double.NaN)));
                }
            }
            return CanvasMath.sanitize(loadedCam, rawPos);
        } catch (Exception e) {
            return null;
        }
    }
}
