package com.sa2recomp.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** The launcher screens' look: night background, electric blue buttons with gold text. */
final class Ui {
    private Ui() {}

    static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** A scrolling column as the activity's content; returns the column to add to. */
    static LinearLayout screen(Activity a, String title) {
        int pad = dp(a, 16);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);
        TextView t = new TextView(a);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(t);
        ScrollView scroll = new ScrollView(a);
        scroll.setBackgroundColor(OpenSa2.NIGHT);
        scroll.setFitsSystemWindows(true);
        scroll.addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                        ViewGroup.LayoutParams.WRAP_CONTENT));
        a.setContentView(scroll);
        return col;
    }

    static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setPadding(0, dp(c, 6), 0, dp(c, 6));
        return t;
    }

    static TextView heading(Context c, String s) {
        TextView t = text(c, s, 17, OpenSa2.GOLD);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(c, 14), 0, dp(c, 4));
        return t;
    }

    static Button button(Context c, String label, View.OnClickListener on) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(OpenSa2.GOLD);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(OpenSa2.BLUE);
        bg.setCornerRadius(dp(c, 10));
        bg.setStroke(dp(c, 2), OpenSa2.GOLD);
        b.setBackground(bg);
        b.setOnClickListener(on);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        int m = dp(c, 4);
        lp.setMargins(m, m, m, m);
        b.setLayoutParams(lp);
        b.setPadding(dp(c, 8), dp(c, 12), dp(c, 8), dp(c, 12));
        return b;
    }

    /** A row of equal buttons. */
    static LinearLayout row(Context c, View... views) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        for (View v : views) r.addView(v);
        return r;
    }

    static CheckBox check(Context c, String label, boolean on, CheckListener l) {
        CheckBox cb = new CheckBox(c);
        cb.setText(label);
        cb.setTextColor(Color.WHITE);
        cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        cb.setButtonTintList(android.content.res.ColorStateList.valueOf(OpenSa2.GOLD));
        cb.setChecked(on);
        cb.setOnCheckedChangeListener((v, isOn) -> l.changed(isOn));
        return cb;
    }

    interface CheckListener {
        void changed(boolean on);
    }

    static AlertDialog.Builder dialog(Context c) {
        return new AlertDialog.Builder(c, android.R.style.Theme_Material_Dialog_Alert);
    }

    /** A dialog with a blue body and gold title, for the in-game menu. */
    static void style(AlertDialog d) {
        if (d.getWindow() != null) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(OpenSa2.BLUE);
            bg.setCornerRadius(dp(d.getContext(), 14));
            bg.setStroke(dp(d.getContext(), 3), OpenSa2.GOLD);
            d.getWindow().setBackgroundDrawable(bg);
        }
    }
}
