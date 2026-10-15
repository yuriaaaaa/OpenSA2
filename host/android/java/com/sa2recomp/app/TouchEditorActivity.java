package com.sa2recomp.app;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;

/** The launcher's Touch layout: the in-game controls on a dark screen with the layout editor. */
public class TouchEditorActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(OpenSa2.NIGHT);
        setContentView(root);
        TouchControlsView touch = new TouchControlsView(this);
        touch.setEnabledControls(true);
        root.addView(touch, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                       ViewGroup.LayoutParams.MATCH_PARENT));
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
        root.post(() -> new TouchLayoutEditor(this, root, touch, saved -> finish()));
    }
}
