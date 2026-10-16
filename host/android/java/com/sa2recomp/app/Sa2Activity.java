package com.sa2recomp.app;

import android.content.Intent;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.libsdl.app.SDLActivity;
import org.libsdl.app.Sa2VirtualPad;

/**
 * The play screen: SDL's activity running the native session (host/android/native,
 * sa2_android_main in libmain.so), which starts the game and runs host 2's player on SDL's
 * thread. Its own process (":play" in the manifest), ended when the activity is destroyed, so a
 * second session starts with fresh native state.
 *
 * Intent extras (from Sa2SetupActivity): sa2.stream (a recorded stream file to play with no
 * game: the renderer's check), sa2.max_frames, sa2.no_audio, sa2.no_pace.
 *
 * The session is also given the screen's size in pixels, landscape (--screen WxH), from which it
 * sizes the game's back buffer (SA2_PLAY_RES, runtime/sa2_widescreen.c), so the picture fills the
 * screen with no bars.
 */
public class Sa2Activity extends SDLActivity {
    public static final String EXTRA_STREAM = "sa2.stream";
    public static final String EXTRA_MAX_FRAMES = "sa2.max_frames";
    public static final String EXTRA_NO_AUDIO = "sa2.no_audio";
    public static final String EXTRA_NO_PACE = "sa2.no_pace";

    private static final long BACK_AGAIN_MS = 2500;
    private boolean mQuitSent;
    private long mLastBack;
    private TouchControlsView mTouch;
    private CodeEngine mCodes;
    private GameMenu mMenu;

    @Override
    protected String[] getLibraries() {
        return new String[] {"SDL3", "main"};
    }

    @Override
    protected String getMainFunction() {
        return "sa2_android_main";
    }

    @Override
    protected String[] getArguments() {
        ArrayList<String> a = new ArrayList<>();
        a.add("--files");
        a.add(getFilesDir().getAbsolutePath());
        a.add("--cache");
        a.add(getCacheDir().getAbsolutePath());
        a.add("--libdir");
        a.add(getApplicationInfo().nativeLibraryDir);
        String screen = screenSize();
        if (screen != null) {
            a.add("--screen");
            a.add(screen);
        }
        // OpenSA2: game.env.extra (resolution, HUD and debug settings) comes from the launcher's
        // settings, written to the app's env folder before this screen starts.
        File env = OpenSa2.envDir(this);
        env.mkdirs();
        a.add("--external");
        a.add(env.getAbsolutePath());
        Intent it = getIntent();
        if (it != null) {
            String stream = it.getStringExtra(EXTRA_STREAM);
            if (stream != null && !stream.isEmpty()) {
                a.add("--stream");
                a.add(stream);
                String frames = it.getStringExtra(EXTRA_MAX_FRAMES);
                if (frames != null && !frames.isEmpty()) {
                    a.add("--max-frames");
                    a.add(frames);
                }
            }
            if (it.getBooleanExtra(EXTRA_NO_AUDIO, false)) a.add("--no-audio");
            if (it.getBooleanExtra(EXTRA_NO_PACE, false)) a.add("--no-pace");
        }
        return a.toArray(new String[0]);
    }

    /** The largest window this activity can have, in pixels, longer side first ("2340x1080"): the
     * whole screen, cutout included (the activity lays out into it, LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,
     * and hides the system bars). API 30's maximum window metrics; on API 29 the display's real
     * size. Null when neither answers. */
    @SuppressWarnings("deprecation")
    private String screenSize() {
        int w = 0, h = 0;
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                Rect b = getWindowManager().getMaximumWindowMetrics().getBounds();
                w = b.width();
                h = b.height();
            } else {
                Point p = new Point();
                getWindowManager().getDefaultDisplay().getRealSize(p);
                w = p.x;
                h = p.y;
            }
        } catch (RuntimeException e) {
            return null;
        }
        if (w <= 0 || h <= 0) return null;
        return Math.max(w, h) + "x" + Math.min(w, h);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Native patches (code patches, character swaps): the game process inherits this one's
        // environment, so LD_PRELOAD and the plan are set before SDL's thread starts the session.
        NativePatch.arm(this);
        // The touch pad joins SDL's joysticks at SDL's first poll, before the player lists them.
        boolean touchOn = OpenSa2.prefs(this).getBoolean("touch_on", true);
        Sa2VirtualPad.setWanted(touchOn);
        Sa2VirtualPad.install(present -> runOnUiThread(() -> {
            if (mTouch != null && present) mTouch.hideForGamepad();
        }));
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (!mBrokenLibraries && mLayout != null) {
            mTouch = new TouchControlsView(this);
            mTouch.setEnabledControls(touchOn);
            mMenu = new GameMenu(this, mTouch);
            mTouch.setOnMenu(() -> mMenu.show());
            mLayout.addView(mTouch, new android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                                                 android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            if (Sa2VirtualPad.realControllerConnected()) mTouch.hideForGamepad();
            startCodes();
        }
        // The sustained-performance mode holds the clocks to what the device can keep up for
        // hours, below what a frame of the game needs: left off (the native session's header,
        // "Speed on a phone"), whatever else asked for it. The screen stays on.
        if (Build.VERSION.SDK_INT >= 24) getWindow().setSustainedPerformanceMode(false);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        hideSystemBars();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    /** Back (the system's, not a gamepad's: SDL takes a pad's) twice within BACK_AGAIN_MS asks the
     * session to end: the player's end request stops the game through its own code, then SDL
     * finishes this activity. Once the end was asked, Back leaves at once. SDL 3 would otherwise
     * hand Back to the player as a key (SDL_SCANCODE_AC_BACK) and never call onBackPressed. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && !fromGamepad(event)) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (mMenu != null) mMenu.show();
                else backRequest();
            }
            return true;
        }
        if (mTouch != null && fromGamepad(event) && Sa2VirtualPad.looksLikeController(event.getDevice())) {
            mTouch.hideForGamepad();
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        backRequest();
    }

    /** Runs the codes switched on (and the active mods' required ones) against the game. */
    private void startCodes() {
        List<String> errors = new ArrayList<>();
        List<CodeList.Code> run = ModManager.codesToRun(this, errors);
        File log = new File(getFilesDir(), "codes.log");
        log.delete();
        mCodes = new CodeEngine(log);
        mCodes.setNative(NativePatch.plannedCodes(this));
        mCodes.setCodes(run);
        for (String e : errors) android.util.Log.w("opensa2-codes", e);
        if (!run.isEmpty()) mCodes.start();
    }

    CodeEngine codes() {
        return mCodes;
    }

    /** The menu changed which codes are on: reload them (the engine starts if it had none). */
    void reloadCodes() {
        List<CodeList.Code> run = ModManager.codesToRun(this, new ArrayList<>());
        mCodes.setCodes(run);
        if (!run.isEmpty()) mCodes.start();
    }

    @Override
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent ev) {
        if (mTouch != null && Sa2VirtualPad.looksLikeController(ev.getDevice())) mTouch.hideForGamepad();
        return super.dispatchGenericMotionEvent(ev);
    }

    private static boolean fromGamepad(KeyEvent event) {
        int s = event.getSource();
        return (s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
               || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    /** Ends the session (the game menu's Exit game). */
    void endGame() {
        mLastBack = SystemClock.uptimeMillis();
        backRequest();
    }

    private void backRequest() {
        if (mBrokenLibraries || mQuitSent) {
            if (!isFinishing()) finish();
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (mLastBack != 0 && now - mLastBack <= BACK_AGAIN_MS) {
            mQuitSent = true;
            SDLActivity.nativeSendQuit();
            return;
        }
        mLastBack = now;
        Toast.makeText(this, "Press Back again to end the game", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        if (mCodes != null) mCodes.stop();
        super.onDestroy();
        // The native session's state (the player's statics, SDL's) ends with this process.
        System.exit(0);
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }
}
