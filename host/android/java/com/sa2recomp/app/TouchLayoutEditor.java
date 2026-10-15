package com.sa2recomp.app;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Touch layout editing: drag a control to move it; the panel resizes the selected one or hides
 * it in game. Used from the launcher (TouchEditorActivity) and from the in-game menu.
 */
final class TouchLayoutEditor {
    private static final float MIN_R = 0.03f, MAX_R = 0.35f;

    interface Done {
        void onDone(boolean saved);
    }

    private final Activity mActivity;
    private final TouchControlsView mView;
    private final FrameLayout mOverlay;
    private final LinearLayout mPanel;
    private final TextView mWhat;
    private final SeekBar mSize;
    private final Switch mShown;
    private final Done mDone;
    private final Button[] mProfiles = new Button[3];
    private int mProfile;
    private int mPanelPos = 1;
    private boolean mUpdating;

    TouchLayoutEditor(Activity activity, ViewGroup parent, TouchControlsView view, Done done) {
        mActivity = activity;
        mView = view;
        mDone = done;
        int d8 = Ui.dp(activity, 8);
        mOverlay = new FrameLayout(activity);
        mPanel = new LinearLayout(activity);
        mPanel.setOrientation(LinearLayout.VERTICAL);
        mPanel.setPadding(d8 + 4, d8 + 4, d8 + 4, d8 + 4);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(OpenSa2.BLUE);
        bg.setCornerRadius(Ui.dp(activity, 16));
        bg.setStroke(Ui.dp(activity, 3), OpenSa2.GOLD);
        mPanel.setBackground(bg);
        mPanel.setClickable(true);

        mPanel.addView(text("Touch layout", 18, true));
        mPanel.addView(text("Drag a control to move it. Tap one to resize it or hide it in game (dashed = hidden). "
                            + "Save keeps the layout in the chosen profile and uses it.", 13, false));
        mProfile = TouchControlsView.activeProfile(activity);
        LinearLayout profiles = new LinearLayout(activity);
        for (int i = 0; i < 3; i++) {
            final int p = i + 1;
            mProfiles[i] = button("Profile " + p, v -> {
                mProfile = p;
                mView.loadProfile(p);
                refresh();
            });
            profiles.addView(mProfiles[i]);
        }
        mPanel.addView(profiles);

        mWhat = text("Nothing selected", 15, true);
        mWhat.setTextColor(OpenSa2.GOLD);
        mPanel.addView(mWhat);

        mSize = new SeekBar(activity);
        mSize.setMax(100);
        ColorStateList gold = ColorStateList.valueOf(OpenSa2.GOLD);
        mSize.setProgressTintList(gold);
        mSize.setThumbTintList(gold);
        mSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int v, boolean user) {
                if (user && !mUpdating) mView.setSelectedSize(MIN_R + (MAX_R - MIN_R) * v / 100f);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        mPanel.addView(mSize);

        mShown = new Switch(activity);
        mShown.setText("Show in game");
        mShown.setTextColor(Color.WHITE);
        mShown.setThumbTintList(gold);
        mShown.setOnCheckedChangeListener((b, on) -> {
            if (!mUpdating) mView.setSelectedShown(on);
        });
        mPanel.addView(mShown);

        LinearLayout row = new LinearLayout(activity);
        row.addView(button("Reset", v -> mView.resetLayout()));
        row.addView(button("Move panel", v -> {
            mPanelPos = (mPanelPos + 1) % 3;
            placePanel();
        }));
        row.addView(button("Cancel", v -> finish(false)));
        row.addView(button("Save", v -> finish(true)));
        mPanel.addView(row);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(activity, 420), ViewGroup.LayoutParams.WRAP_CONTENT);
        mOverlay.addView(mPanel, lp);
        parent.addView(mOverlay, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                            ViewGroup.LayoutParams.MATCH_PARENT));
        // The overlay itself passes touches through to the controls under it.
        mOverlay.setClickable(false);
        placePanel();
        mView.setEditing(true, this::refresh);
        refresh();
    }

    private void placePanel() {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mPanel.getLayoutParams();
        lp.gravity = Gravity.CENTER_HORIZONTAL | (mPanelPos == 0 ? Gravity.TOP : mPanelPos == 1 ? Gravity.CENTER_VERTICAL : Gravity.BOTTOM);
        mPanel.setLayoutParams(lp);
    }

    private void refresh() {
        mUpdating = true;
        for (int i = 0; i < 3; i++) mProfiles[i].setAlpha(mProfile == i + 1 ? 1f : 0.55f);
        TouchControlsView.Ctl s = mView.selected();
        if (s == null) {
            mWhat.setText("Nothing selected");
            mSize.setEnabled(false);
            mShown.setEnabled(false);
        } else {
            String name = s.label.isEmpty() ? (s.id.equals("LS") ? "Left stick" : s.id.equals("RS") ? "Right stick"
                    : s.id.equals("DPad") ? "D-pad" : "Hide button") : s.label;
            mWhat.setText(name);
            mSize.setEnabled(true);
            mSize.setProgress(Math.round((s.fr - MIN_R) / (MAX_R - MIN_R) * 100));
            mShown.setEnabled(s.canHide());
            mShown.setChecked(s.shown);
        }
        mUpdating = false;
    }

    private void finish(boolean save) {
        if (save) {
            OpenSa2.prefs(mActivity).edit()
                    .putString(TouchControlsView.layoutKey(mProfile), mView.saveLayout())
                    .putInt("touch_profile", mProfile)
                    .commit();
        } else {
            mView.loadProfile(TouchControlsView.activeProfile(mActivity));
        }
        mView.setEditing(false, null);
        ((ViewGroup) mOverlay.getParent()).removeView(mOverlay);
        if (mDone != null) mDone.onDone(save);
    }

    private TextView text(String s, float sp, boolean bold) {
        TextView t = new TextView(mActivity);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setPadding(0, Ui.dp(mActivity, 4), 0, Ui.dp(mActivity, 4));
        return t;
    }

    private Button button(String label, View.OnClickListener on) {
        Button b = Ui.button(mActivity, label, on);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setPadding(Ui.dp(mActivity, 4), Ui.dp(mActivity, 6), Ui.dp(mActivity, 4), Ui.dp(mActivity, 6));
        return b;
    }
}
