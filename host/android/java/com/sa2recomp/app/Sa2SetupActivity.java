package com.sa2recomp.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.List;

/**
 * OpenSA2's launcher. The game folder is Internal storage/OpenSA2/game/ (the 2012 PC game folder
 * copied as is); Play writes the config without checking the files' hashes (OpenSa2.writeConfig),
 * puts the enabled mods in place (ModManager.apply), writes the settings the game reads
 * (game.env.extra) and starts the play screen (Sa2Activity). Mods, Codes, Settings and Touch
 * layout open their own screens. When a session ends, its report is shown with the reason a
 * failed one gives, its logs are copied to Internal storage/OpenSA2/logs/<session>/
 * (SessionLogs) and the game's saves to Internal storage/OpenSA2/saves/ (Saves). This also runs
 * when the launcher comes back after the system ended its process while the game ran.
 *
 * Intent extras: sa2.stream (and Sa2Activity's other extras) start the play screen's stream mode
 * at once, the renderer's check with no game.
 */
public class Sa2SetupActivity extends Activity {
    public static final String EXTRA_NO_AUTOPLAY = "sa2.no_autoplay";
    private static final int ASK_STORAGE = 7;

    private TextView mStatus, mLog;
    private Button mPlay, mAccess;
    private boolean mBusy, mPlayLaunched;
    private final StringBuilder mLaunchLog = new StringBuilder();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        if (state == null) takeIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        takeIntent(intent);
    }

    private void takeIntent(Intent it) {
        if (it == null) return;
        if (it.getStringExtra(Sa2Activity.EXTRA_STREAM) != null) {
            Intent play = new Intent(this, Sa2Activity.class);
            play.putExtras(it);
            mPlayLaunched = true;
            startActivity(play);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean justPlayed = mPlayLaunched;
        mPlayLaunched = false;
        refresh();
        if (!mBusy) sessionEnded(justPlayed);
    }

    // ------------------------------------------------------------ the screen

    private void buildUi() {
        LinearLayout col = Ui.screen(this, "OpenSA2");
        col.addView(Ui.text(this, "Sonic Adventure 2, recompiled for Android", 14, 0xFFA8B4C8));
        mStatus = Ui.text(this, "", 15, 0xFFE0E6F0);
        mStatus.setTextIsSelectable(true);
        col.addView(mStatus);
        mAccess = Ui.button(this, "Allow file access", v -> askStorage());
        col.addView(Ui.row(this, mAccess));
        mPlay = Ui.button(this, "Play", v -> play());
        mPlay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        col.addView(Ui.row(this, mPlay));
        col.addView(Ui.row(this,
                Ui.button(this, "Mods", v -> startActivity(new Intent(this, ModsActivity.class))),
                Ui.button(this, "Codes", v -> startActivity(new Intent(this, CodesActivity.class)))));
        col.addView(Ui.row(this,
                Ui.button(this, "Characters", v -> startActivity(new Intent(this, CharactersActivity.class))),
                Ui.button(this, "Settings", v -> startActivity(new Intent(this, SettingsActivity.class)))));
        col.addView(Ui.row(this,
                Ui.button(this, "Touch layout", v -> startActivity(new Intent(this, TouchEditorActivity.class)))));
        mLog = Ui.text(this, "", 11, 0xFFA8B4C8);
        mLog.setTypeface(Typeface.MONOSPACE);
        mLog.setTextIsSelectable(true);
        col.addView(mLog);
    }

    private void refresh() {
        if (mBusy) return;
        boolean access = OpenSa2.haveStorage();
        mAccess.setVisibility(access ? View.GONE : View.VISIBLE);
        if (!access) {
            mPlay.setEnabled(false);
            mStatus.setText("OpenSA2 keeps the game, mods and logs in Internal storage/OpenSA2/, where any file "
                            + "manager can copy to it. Tap Allow file access and switch on OpenSA2.");
            return;
        }
        OpenSa2.makeFolders();
        File root = OpenSa2.findGameRoot();
        if (root == null) {
            mPlay.setEnabled(false);
            mStatus.setText("This app needs your own copy of Sonic Adventure 2 (the 2012 PC version on Steam).\n\n"
                    + "1. On your computer, open the game's folder (Steam: right-click Sonic Adventure 2, "
                    + "Manage, Browse local files).\n"
                    + "2. Copy everything in that folder (sonic2app.exe, resource/ and the rest) into\n"
                    + "   Internal storage/OpenSA2/game/\n"
                    + "3. Come back here. Mods go in Internal storage/OpenSA2/mods/.\n\n"
                    + "No " + OpenSa2.GAME_EXE + " in " + OpenSa2.gameDir().getAbsolutePath() + " yet.");
            return;
        }
        mPlay.setEnabled(true);
        int mods = ModManager.active(this).size();
        mStatus.setText("Game folder: " + root.getAbsolutePath() + "\n"
                + mods + " mod(s) on. Resolution: " + OpenSa2.resName(OpenSa2.prefs(this).getString("res_mode", "screen"))
                + ".\nThree-finger tap in game opens the menu.");
    }

    private void askStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                         Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[] {Manifest.permission.READ_EXTERNAL_STORAGE,
                                             Manifest.permission.WRITE_EXTERNAL_STORAGE}, ASK_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        refresh();
    }

    // ------------------------------------------------------------ play

    private void play() {
        final File root = OpenSa2.findGameRoot();
        if (root == null || mBusy) return;
        mBusy = true;
        mPlay.setEnabled(false);
        mLog.setText("");
        synchronized (mLaunchLog) {
            mLaunchLog.setLength(0);
        }
        new Thread(() -> {
            String fail = null;
            try {
                say("OpenSA2 " + versionName() + ", Play at " + OpenSa2.nowIso());
                for (String l : OpenSa2.writeConfig(this, root)) say(l);
                File exe = OpenSa2.exeIn(root);
                String sha = OpenSa2.sha256(exe);
                if (!OpenSa2.STEAM_EXE_SHA256.equals(sha)) {
                    say("Note: " + exe.getName() + " is not the 2012 Steam build this port was made for "
                        + "(SHA-256 " + sha + "). If the game stops at once, copy the original Steam sonic2app.exe.");
                }
                try {
                    Saves.importInto(this, this::say);
                } catch (Exception e) {
                    say("Could not bring in saves from Internal storage/OpenSA2/saves: " + e.getMessage());
                }
                ModManager.apply(this, root, this::say);
                NativePatch.Plan np = NativePatch.prepare(this);
                for (String l : np.said) say(l);
                OpenSa2.writeGameEnv(this);
            } catch (Exception e) {
                fail = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            if (fail != null) say("Could not start: " + fail);
            writeLaunchLog();
            final String f = fail;
            runOnUiThread(() -> {
                mBusy = false;
                mPlay.setEnabled(true);
                if (f != null) return;
                mPlayLaunched = true;
                startActivity(new Intent(this, Sa2Activity.class));
            });
        }).start();
    }

    private void say(String line) {
        synchronized (mLaunchLog) {
            mLaunchLog.append(line).append('\n');
        }
        runOnUiThread(() -> mLog.append(line + "\n"));
    }

    /** The Play's lines, kept as files/launcher.log for the session's logs folder. */
    private void writeLaunchLog() {
        String text;
        synchronized (mLaunchLog) {
            text = mLaunchLog.toString();
        }
        try {
            OpenSa2.writeText(new File(getFilesDir(), "launcher.log"), text);
        } catch (Exception ignored) {
        }
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /**
     * After a session (and whenever the launcher comes back): the saves and logs copied out, and
     * the newest session's report shown once, with its private paths written as the public ones
     * and the reason a failed session gives.
     */
    private void sessionEnded(boolean justPlayed) {
        new Thread(() -> {
            List<String> extra = new java.util.ArrayList<>();
            if (OpenSa2.haveStorage()) {
                try {
                    int n = Saves.export(this);
                    if (n > 0) extra.add(n + " save(s) copied to Internal storage/OpenSA2/saves");
                } catch (Exception e) {
                    extra.add("Could not copy the saves to Internal storage/OpenSA2/saves: " + e.getMessage());
                }
            }
            File out = null;
            if (SessionLogs.wanted(this)) {
                try {
                    out = SessionLogs.export(this);
                } catch (Exception e) {
                    extra.add("Could not copy the logs: " + e.getMessage());
                }
            }
            File newest = SessionLogs.newest(this);
            String last = OpenSa2.readText(new File(getFilesDir(), "last-session.txt"));
            String shown = OpenSa2.prefs(this).getString("shown_session", "");
            boolean fresh = newest != null && !newest.getName().equals(shown);
            if (!justPlayed && !fresh) return;
            if (newest != null) OpenSa2.prefs(this).edit().putString("shown_session", newest.getName()).apply();
            StringBuilder b = new StringBuilder();
            if (last != null) {
                b.append("The last session:\n").append(SessionLogs.publicPaths(this, last));
                if (!last.endsWith("\n")) b.append('\n');
                for (String l : SessionLogs.why(newest, last)) b.append(l).append('\n');
            }
            if (newest != null) {
                for (String l : NativePatch.report(new File(newest, "game.log"))) b.append("Native patches: ").append(l).append('\n');
            }
            if (out != null) b.append("Logs saved to Internal storage/OpenSA2/logs/").append(out.getName()).append("/\n");
            else if (!SessionLogs.wanted(this)) b.append("Session logs are not being saved (Settings, Save session logs).\n");
            for (String l : extra) b.append(l).append('\n');
            final String text = b.toString();
            runOnUiThread(() -> mLog.setText(text));
        }).start();
    }
}
