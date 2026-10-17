package com.sa2recomp.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import org.libsdl.app.Sa2VirtualPad;

/**
 * The on-screen controller, laid out like an Xbox pad: left stick and D-pad on the left, A B X Y
 * (green, red, blue, yellow) and the right stick on the right, LB RB and LT RT at the top corners,
 * Back and Start in the middle. It drives Sa2VirtualPad, which the game sees as a real controller.
 *
 * The top-centre button hides the controls; a real controller hides them until the screen is
 * touched again. A three-finger tap away from the controls opens the game menu. Every touch
 * stops here, so the player's own built-in overlay never shows.
 *
 * In edit mode (Touch layout) touches move the controls instead, and the editor changes the
 * selected control's size and whether it shows. Layouts are kept per profile (three slots).
 */
public class TouchControlsView extends View {

    static final int BUTTON = 0, STICK = 1, DPAD = 2, TRIGGER = 3, PILL = 4, HIDE = 5;

    static final class Ctl {
        final String id, label;
        final int kind, bit, color;
        final float defX, defY, defR;
        final boolean defShown;
        float fx, fy, fr;          // centre as fractions of width/height, radius as a fraction of height
        boolean shown;
        float cx, cy, r;
        Ctl(String id, String label, int kind, int bit, int color, float fx, float fy, float fr, boolean shown) {
            this.id = id; this.label = label; this.kind = kind; this.bit = bit; this.color = color;
            this.defX = fx; this.defY = fy; this.defR = fr; this.defShown = shown;
            reset();
        }
        void reset() {
            fx = defX; fy = defY; fr = defR; shown = defShown;
        }
        boolean canHide() {
            return kind != HIDE;
        }
    }

    private static final int GREEN = 0xFF3DBA3D, RED = 0xFFE5403B, BLUEX = 0xFF2F86E8, YELLOW = 0xFFF5C42C,
            WHITE = 0xFFFFFFFF;

    private final Ctl mStick = new Ctl("LS", "", STICK, Sa2VirtualPad.LSTICK, WHITE, 0.13f, 0.62f, 0.19f, true);
    private final Ctl mRStick = new Ctl("RS", "", STICK, Sa2VirtualPad.RSTICK, WHITE, 0.70f, 0.84f, 0.12f, true);
    private final Ctl mPad = new Ctl("DPad", "", DPAD, 0, WHITE, 0.31f, 0.84f, 0.13f, true);
    private final Ctl[] mButtons = {
        new Ctl("A", "A", BUTTON, Sa2VirtualPad.A, GREEN, 0.885f, 0.78f, 0.080f, true),
        new Ctl("B", "B", BUTTON, Sa2VirtualPad.B, RED, 0.955f, 0.62f, 0.080f, true),
        new Ctl("X", "X", BUTTON, Sa2VirtualPad.X, BLUEX, 0.815f, 0.62f, 0.080f, true),
        new Ctl("Y", "Y", BUTTON, Sa2VirtualPad.Y, YELLOW, 0.885f, 0.46f, 0.080f, true),
        new Ctl("LB", "LB", PILL, Sa2VirtualPad.LB, WHITE, 0.075f, 0.30f, 0.060f, true),
        new Ctl("RB", "RB", PILL, Sa2VirtualPad.RB, WHITE, 0.925f, 0.30f, 0.060f, true),
        new Ctl("LT", "LT", TRIGGER, 0, WHITE, 0.075f, 0.13f, 0.060f, true),
        new Ctl("RT", "RT", TRIGGER, 0, WHITE, 0.925f, 0.13f, 0.060f, true),
        new Ctl("Back", "Back", PILL, Sa2VirtualPad.BACK, WHITE, 0.39f, 0.09f, 0.050f, true),
        new Ctl("Start", "Start", PILL, Sa2VirtualPad.START, WHITE, 0.61f, 0.09f, 0.050f, true),
    };
    private final Ctl mHide = new Ctl("Hide", "", HIDE, 0, WHITE, 0.5f, 0.09f, 0.045f, true);

    private static final int NONE = -1, T_STICK = -2, T_RSTICK = -3, T_DPAD = -4;
    private final int[] mTarget = new int[16];
    private final float[] mPx = new float[16], mPy = new float[16];
    private final float[] mOx = new float[16], mOy = new float[16];   // where a stick finger landed
    private int mHeldBits;
    private boolean mLt, mRt;
    private float mLx, mLy, mRx, mRy;

    private boolean mHidden, mGamepadHidden, mDisabled;
    private float mOpacity = 0.7f;
    private Runnable mOnMenu;

    private static final long TAP_MS = 450;
    private boolean mGestureClean;
    private int mGestureMax;
    private long mGestureStart;
    private final float mGestureSlop;
    private final float[] mGx = new float[16], mGy = new float[16];

    private boolean mEditing;
    private Ctl mSelected;
    private float mDragDx, mDragDy;
    private Runnable mOnSelect;

    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SharedPreferences mPrefs;

    public TouchControlsView(Context ctx) {
        super(ctx);
        for (int i = 0; i < mTarget.length; i++) mTarget[i] = NONE;
        mStroke.setStyle(Paint.Style.STROKE);
        mText.setTextAlign(Paint.Align.CENTER);
        mText.setTypeface(Typeface.DEFAULT_BOLD);
        mPrefs = OpenSa2.prefs(ctx);
        mHidden = mPrefs.getBoolean("touch_hidden", false);
        mOpacity = mPrefs.getInt("touch_opacity", 70) / 100f;
        mGestureSlop = 24 * ctx.getResources().getDisplayMetrics().density;
        loadProfile(activeProfile(ctx));
    }

    // ---- settings ----

    static int activeProfile(Context c) {
        return Math.max(1, Math.min(3, OpenSa2.prefs(c).getInt("touch_profile", 1)));
    }

    static String layoutKey(int profile) {
        return "touch_layout_" + profile;
    }

    void setOnMenu(Runnable r) {
        mOnMenu = r;
    }

    void setEnabledControls(boolean on) {
        mDisabled = !on;
        releaseAll();
        invalidate();
    }

    void setOpacity(float o) {
        mOpacity = Math.max(0.15f, Math.min(1f, o));
        invalidate();
    }

    void hideForGamepad() {
        if (!mGamepadHidden && !mEditing) {
            mGamepadHidden = true;
            releaseAll();
            postInvalidate();
        }
    }

    private Ctl[] all() {
        Ctl[] out = new Ctl[mButtons.length + 4];
        System.arraycopy(mButtons, 0, out, 0, mButtons.length);
        out[mButtons.length] = mStick;
        out[mButtons.length + 1] = mRStick;
        out[mButtons.length + 2] = mPad;
        out[mButtons.length + 3] = mHide;
        return out;
    }

    /** "id,fx,fy,fr,shown;..." */
    String saveLayout() {
        StringBuilder sb = new StringBuilder();
        for (Ctl b : all()) {
            sb.append(b.id).append(',').append(b.fx).append(',').append(b.fy).append(',').append(b.fr)
              .append(',').append(b.shown ? 1 : 0).append(';');
        }
        return sb.toString();
    }

    private void loadLayout(String s) {
        for (Ctl b : all()) b.reset();
        if (s == null || s.isEmpty()) return;
        for (String entry : s.split(";")) {
            String[] f = entry.split(",");
            if (f.length < 4) continue;
            for (Ctl b : all()) {
                if (!b.id.equals(f[0])) continue;
                try {
                    b.fx = Float.parseFloat(f[1]);
                    b.fy = Float.parseFloat(f[2]);
                    b.fr = Float.parseFloat(f[3]);
                } catch (NumberFormatException e) {
                    b.reset();
                }
                if (f.length >= 5 && b.canHide()) b.shown = !"0".equals(f[4].trim());
            }
        }
    }

    void loadProfile(int profile) {
        loadLayout(mPrefs.getString(layoutKey(profile), ""));
        mSelected = null;
        relayout();
    }

    // ---- edit mode ----

    void setEditing(boolean editing, Runnable onSelect) {
        mEditing = editing;
        mOnSelect = onSelect;
        mSelected = null;
        mGamepadHidden = false;
        releaseAll();
        invalidate();
    }

    Ctl selected() {
        return mSelected;
    }

    void setSelectedSize(float fr) {
        if (mSelected == null) return;
        mSelected.fr = fr;
        relayout();
    }

    void setSelectedShown(boolean shown) {
        if (mSelected == null || !mSelected.canHide()) return;
        mSelected.shown = shown;
        invalidate();
    }

    void resetLayout() {
        for (Ctl b : all()) b.reset();
        mSelected = null;
        relayout();
        if (mOnSelect != null) mOnSelect.run();
    }

    private void relayout() {
        onSizeChanged(getWidth(), getHeight(), 0, 0);
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (w == 0 || h == 0) return;
        for (Ctl b : all()) {
            b.cx = b.fx * w;
            b.cy = b.fy * h;
            b.r = b.fr * h;
        }
        mStroke.setStrokeWidth(Math.max(2f, 0.006f * h));
    }

    // ---- drawing ----

    private int a(int alpha) {
        return Math.round(alpha * mOpacity);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (mEditing) {
            drawAll(c);
            return;
        }
        if (mGamepadHidden || mDisabled) return;
        drawHide(c, !mHidden);
        if (!mHidden) drawAll(c);
    }

    private void drawHide(Canvas c, boolean visible) {
        Ctl b = mHide;
        mFill.setColor(Color.argb(visible ? a(150) : 70, 0, 0, 0));
        c.drawCircle(b.cx, b.cy, b.r, mFill);
        mStroke.setColor(Color.argb(visible ? a(170) : 90, 255, 255, 255));
        c.drawCircle(b.cx, b.cy, b.r, mStroke);
        c.drawLine(b.cx - b.r * 0.5f, b.cy, b.cx + b.r * 0.5f, b.cy, mStroke);
        if (!visible) c.drawLine(b.cx, b.cy - b.r * 0.5f, b.cx, b.cy + b.r * 0.5f, mStroke);
    }

    private void drawAll(Canvas c) {
        if (mEditing) drawHide(c, true);
        drawStick(c, mStick, mLx, mLy);
        drawStick(c, mRStick, mRx, mRy);
        drawDpad(c);
        for (Ctl b : mButtons) drawButton(c, b);
        if (mEditing && mSelected != null) {
            Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(mStroke.getStrokeWidth() * 2.5f);
            ring.setColor(OpenSa2.GOLD);
            c.drawCircle(mSelected.cx, mSelected.cy, mSelected.r + ring.getStrokeWidth() * 2, ring);
        }
    }

    private boolean visibleInGame(Ctl b) {
        return b.shown || mEditing;
    }

    private void outline(Canvas c, Ctl b, int color, Runnable shape) {
        if (b.shown) {
            mStroke.setColor(color);
            shape.run();
        } else {
            Paint dash = new Paint(mStroke);
            dash.setPathEffect(new DashPathEffect(new float[] {b.r * 0.25f, b.r * 0.2f}, 0));
            dash.setColor(Color.argb(110, 255, 255, 255));
            Paint keep = new Paint(mStroke);
            mStroke.set(dash);
            shape.run();
            mStroke.set(keep);
        }
    }

    private void drawStick(Canvas c, Ctl b, float x, float y) {
        if (!visibleInGame(b)) return;
        mFill.setColor(Color.argb(a(110), 0, 0, 0));
        c.drawCircle(b.cx, b.cy, b.r, mFill);
        outline(c, b, Color.argb(a(160), 255, 255, 255), () -> c.drawCircle(b.cx, b.cy, b.r, mStroke));
        float kx = b.cx + x * b.r * 0.6f, ky = b.cy + y * b.r * 0.6f;
        boolean held = (b == mStick && (x != 0 || y != 0)) || (b == mRStick && (x != 0 || y != 0));
        mFill.setColor(Color.argb(a(held ? 210 : 150), 70, 70, 80));
        c.drawCircle(kx, ky, b.r * 0.45f, mFill);
        mStroke.setColor(Color.argb(a(200), 255, 255, 255));
        c.drawCircle(kx, ky, b.r * 0.45f, mStroke);
        mText.setColor(Color.argb(a(200), 255, 255, 255));
        mText.setTextSize(b.r * 0.32f);
        c.drawText(b == mStick ? "L" : "R", kx, ky - (mText.descent() + mText.ascent()) / 2, mText);
    }

    private void drawDpad(Canvas c) {
        Ctl b = mPad;
        if (!visibleInGame(b)) return;
        float w = b.r * 0.36f, r = b.r;
        Path p = new Path();
        p.addRect(b.cx - w, b.cy - r, b.cx + w, b.cy + r, Path.Direction.CW);
        p.addRect(b.cx - r, b.cy - w, b.cx + r, b.cy + w, Path.Direction.CW);
        p.setFillType(Path.FillType.WINDING);
        mFill.setColor(Color.argb(a(140), 0, 0, 0));
        Path cross = new Path();
        cross.op(rectPath(b.cx - w, b.cy - r, b.cx + w, b.cy + r), rectPath(b.cx - r, b.cy - w, b.cx + r, b.cy + w),
                 Path.Op.UNION);
        c.drawPath(cross, mFill);
        outline(c, b, Color.argb(a(170), 255, 255, 255), () -> c.drawPath(cross, mStroke));
        drawArrow(c, Sa2VirtualPad.LEFT, -1, 0);
        drawArrow(c, Sa2VirtualPad.RIGHT, 1, 0);
        drawArrow(c, Sa2VirtualPad.UP, 0, -1);
        drawArrow(c, Sa2VirtualPad.DOWN, 0, 1);
    }

    private static Path rectPath(float l, float t, float r, float b) {
        Path p = new Path();
        p.addRoundRect(new RectF(l, t, r, b), (r - l) * 0.15f, (b - t) * 0.15f, Path.Direction.CW);
        return p;
    }

    private void drawArrow(Canvas c, int bit, int dx, int dy) {
        Ctl b = mPad;
        float d = b.r * 0.66f, s = b.r * 0.17f;
        float x = b.cx + dx * d, y = b.cy + dy * d;
        boolean on = (mHeldBits & bit) != 0;
        mFill.setColor(on ? Color.argb(255, 255, 194, 26) : Color.argb(a(220), 255, 255, 255));
        Path p = new Path();
        p.moveTo(x + dx * s, y + dy * s);
        p.lineTo(x - dx * s + dy * s, y - dy * s + dx * s);
        p.lineTo(x - dx * s - dy * s, y - dy * s - dx * s);
        p.close();
        c.drawPath(p, mFill);
    }

    private boolean held(Ctl b) {
        if (b.kind == TRIGGER) return b.id.equals("LT") ? mLt : mRt;
        return (mHeldBits & b.bit) != 0;
    }

    private void drawButton(Canvas c, Ctl b) {
        if (!visibleInGame(b)) return;
        boolean down = held(b);
        if (b.kind == BUTTON) {
            mFill.setColor(down ? (b.color & 0x00FFFFFF) | (a(230) << 24) : Color.argb(a(150), 20, 20, 24));
            c.drawCircle(b.cx, b.cy, b.r, mFill);
            outline(c, b, (b.color & 0x00FFFFFF) | (a(230) << 24), () -> c.drawCircle(b.cx, b.cy, b.r, mStroke));
            mText.setColor(down ? Color.argb(255, 255, 255, 255) : (b.color & 0x00FFFFFF) | (a(255) << 24));
            mText.setTextSize(b.r * 0.95f);
        } else {
            // Bumpers, triggers, Back and Start: rounded pills.
            float hw = b.r * (b.kind == TRIGGER ? 1.35f : 1.55f), hh = b.r * (b.kind == TRIGGER ? 0.95f : 0.62f);
            RectF rr = new RectF(b.cx - hw, b.cy - hh, b.cx + hw, b.cy + hh);
            mFill.setColor(down ? Color.argb(a(230), 255, 194, 26) : Color.argb(a(150), 20, 20, 24));
            c.drawRoundRect(rr, hh, hh, mFill);
            outline(c, b, Color.argb(a(190), 255, 255, 255), () -> c.drawRoundRect(rr, hh, hh, mStroke));
            mText.setColor(down ? Color.argb(255, 16, 24, 48) : Color.argb(a(240), 255, 255, 255));
            mText.setTextSize(Math.min(hh * 1.0f, b.r * 0.75f));
        }
        c.drawText(b.label, b.cx, b.cy - (mText.descent() + mText.ascent()) / 2, mText);
    }

    // ---- hit tests ----

    private static boolean inside(Ctl b, float x, float y, float scale) {
        float dx = x - b.cx, dy = y - b.cy;
        if (b.kind == PILL || b.kind == TRIGGER) {
            float hw = b.r * 1.55f * scale, hh = b.r * 1.0f * scale;
            return Math.abs(dx) <= hw && Math.abs(dy) <= hh;
        }
        float r = b.r * scale;
        return dx * dx + dy * dy <= r * r;
    }

    private int hitButton(float x, float y) {
        int best = NONE;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < mButtons.length; i++) {
            Ctl b = mButtons[i];
            if (!b.shown || !inside(b, x, y, 1.2f)) continue;
            float d = (x - b.cx) * (x - b.cx) + (y - b.cy) * (y - b.cy);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    private int hitAny(float x, float y) {
        if (mStick.shown && inside(mStick, x, y, 1.35f)) return T_STICK;
        if (mRStick.shown && inside(mRStick, x, y, 1.3f)) return T_RSTICK;
        if (mPad.shown && inside(mPad, x, y, 1.25f)) return T_DPAD;
        return hitButton(x, y);
    }

    private boolean onControl(float x, float y) {
        if (mDisabled || mGamepadHidden) return false;
        if (inside(mHide, x, y, 1.4f)) return true;
        if (mHidden) return false;
        return hitAny(x, y) != NONE;
    }

    // ---- touches ----

    private boolean trackMenuGesture(MotionEvent e) {
        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mGestureClean = true;
                mGestureMax = 1;
                mGestureStart = e.getEventTime();
                // fall through
            case MotionEvent.ACTION_POINTER_DOWN: {
                int id = e.getPointerId(idx);
                if (onControl(e.getX(idx), e.getY(idx))) mGestureClean = false;
                if (id < mGx.length) {
                    mGx[id] = e.getX(idx);
                    mGy[id] = e.getY(idx);
                }
                mGestureMax = Math.max(mGestureMax, e.getPointerCount());
                if (mGestureMax > 3) mGestureClean = false;
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int id = e.getPointerId(i);
                    if (id >= mGx.length) continue;
                    if (Math.abs(e.getX(i) - mGx[id]) > mGestureSlop || Math.abs(e.getY(i) - mGy[id]) > mGestureSlop) {
                        mGestureClean = false;
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
                boolean fire = mGestureClean && mGestureMax == 3 && e.getEventTime() - mGestureStart <= TAP_MS;
                mGestureClean = false;
                if (fire && mOnMenu != null) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    mOnMenu.run();
                    return true;
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                mGestureClean = false;
                break;
        }
        return false;
    }

    private boolean editTouch(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                Ctl hit = null;
                for (Ctl b : all()) if (inside(b, x, y, 1.1f)) hit = b;
                mSelected = hit;
                if (hit != null) {
                    mDragDx = hit.cx - x;
                    mDragDy = hit.cy - y;
                }
                if (mOnSelect != null) mOnSelect.run();
                invalidate();
                break;
            }
            case MotionEvent.ACTION_MOVE:
                if (mSelected != null && getWidth() > 0 && getHeight() > 0) {
                    mSelected.fx = Math.max(0, Math.min(getWidth(), x + mDragDx)) / getWidth();
                    mSelected.fy = Math.max(0, Math.min(getHeight(), y + mDragDy)) / getHeight();
                    relayout();
                }
                break;
        }
        return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (mEditing) return editTouch(e);
        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        if (trackMenuGesture(e)) {
            releaseAll();
            return true;
        }
        if (mDisabled) return true;
        if (mGamepadHidden) {
            if (action == MotionEvent.ACTION_UP && mGestureMax == 1) {
                mGamepadHidden = false;
                invalidate();
            }
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            float x = e.getX(idx), y = e.getY(idx);
            if (inside(mHide, x, y, 1.4f)) {
                mHidden = !mHidden;
                mPrefs.edit().putBoolean("touch_hidden", mHidden).apply();
                releaseAll();
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                invalidate();
                return true;
            }
        }
        if (mHidden) return true;

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int id = e.getPointerId(idx);
                if (id < mTarget.length) {
                    float x = e.getX(idx), y = e.getY(idx);
                    mTarget[id] = hitAny(x, y);
                    mPx[id] = mOx[id] = x;
                    mPy[id] = mOy[id] = y;
                }
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int id = e.getPointerId(i);
                    if (id >= mTarget.length) continue;
                    mPx[id] = e.getX(i);
                    mPy[id] = e.getY(i);
                    if (mTarget[id] >= 0 || mTarget[id] == NONE) {
                        int t = hitButton(mPx[id], mPy[id]);   // slide between buttons
                        if (t != NONE || mTarget[id] >= 0) mTarget[id] = t;
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int id = e.getPointerId(idx);
                if (id < mTarget.length) mTarget[id] = NONE;
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                for (int i = 0; i < mTarget.length; i++) mTarget[i] = NONE;
                break;
        }
        sync();
        return true;
    }

    /** A stick's push, -1..1 each way, from its centre; a full push past the rim. */
    private static float[] stickValue(Ctl s, float x, float y) {
        float dx = (x - s.cx) / (s.r * 0.85f), dy = (y - s.cy) / (s.r * 0.85f);
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len > 1) {
            dx /= len;
            dy /= len;
        }
        if (len < 0.08f) dx = dy = 0;
        return new float[] {dx, dy};
    }

    private void sync() {
        int bits = 0;
        boolean lt = false, rt = false;
        float lx = 0, ly = 0, rx = 0, ry = 0;
        for (int id = 0; id < mTarget.length; id++) {
            int t = mTarget[id];
            if (t == T_STICK) {
                float[] v = stickValue(mStick, mPx[id], mPy[id]);
                lx = v[0];
                ly = v[1];
            } else if (t == T_RSTICK) {
                float[] v = stickValue(mRStick, mPx[id], mPy[id]);
                rx = v[0];
                ry = v[1];
            } else if (t == T_DPAD) {
                float dx = mPx[id] - mPad.cx, dy = mPy[id] - mPad.cy;
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len >= mPad.r * 0.2f) {
                    float nx = dx / len, ny = dy / len, k = 0.38f;   // 8 ways
                    if (nx > k) bits |= Sa2VirtualPad.RIGHT;
                    if (nx < -k) bits |= Sa2VirtualPad.LEFT;
                    if (ny > k) bits |= Sa2VirtualPad.DOWN;
                    if (ny < -k) bits |= Sa2VirtualPad.UP;
                }
            } else if (t >= 0) {
                Ctl b = mButtons[t];
                if (b.kind == TRIGGER) {
                    if (b.id.equals("LT")) lt = true;
                    else rt = true;
                } else {
                    bits |= b.bit;
                }
            }
        }
        boolean pressed = (bits & ~mHeldBits) != 0 || (lt && !mLt) || (rt && !mRt);
        boolean changed = bits != mHeldBits || lt != mLt || rt != mRt || lx != mLx || ly != mLy || rx != mRx || ry != mRy;
        mHeldBits = bits;
        mLt = lt;
        mRt = rt;
        mLx = lx;
        mLy = ly;
        mRx = rx;
        mRy = ry;
        Sa2VirtualPad.set(bits, lx, ly, rx, ry, lt ? 1 : 0, rt ? 1 : 0);
        if (pressed && mPrefs.getBoolean("touch_haptics", true)) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        if (changed) invalidate();
    }

    void releaseAll() {
        for (int i = 0; i < mTarget.length; i++) mTarget[i] = NONE;
        mHeldBits = 0;
        mLt = mRt = false;
        mLx = mLy = mRx = mRy = 0;
        Sa2VirtualPad.release();
    }
}
