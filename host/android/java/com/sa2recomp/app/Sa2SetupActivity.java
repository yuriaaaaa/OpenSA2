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
 * layout open their own screens. When a session ends, its report is shown, and with Debug logs on
 * its logs are copied into Internal storage/OpenSA2/logs/.
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
        if (mPlayLaunched) {
            mPlayLaunched = false;
            sessionEnded();
        }
        refresh();
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
                Ui.button(this, "Settings", v -> startActivity(new Intent(this, SettingsActivity.class))),
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
        new Thread(() -> {
            String fail = null;
            try {
                for (String l : OpenSa2.writeConfig(this, root)) say(l);
                File exe = OpenSa2.exeIn(root);
                String sha = OpenSa2.sha256(exe);
                if (!OpenSa2.STEAM_EXE_SHA256.equals(sha)) {
                    say("Note: " + exe.getName() + " is not the 2012 Steam build this port was made for "
                        + "(SHA-256 " + sha + "). If the game stops at once, copy the original Steam sonic2app.exe.");
                }
                ModManager.apply(this, root, this::say);
                OpenSa2.writeGameEnv(this);
            } catch (Exception e) {
                fail = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final String f = fail;
            runOnUiThread(() -> {
                mBusy = false;
                mPlay.setEnabled(true);
                if (f != null) {
                    say("Could not start: " + f);
                    return;
                }
                mPlayLaunched = true;
                startActivity(new Intent(this, Sa2Activity.class));
            });
        }).start();
    }

    private void say(String line) {
        runOnUiThread(() -> mLog.append(line + "\n"));
    }

    /** Shows the session's report; with Debug logs on, copies the session's logs to OpenSA2/logs. */
    private void sessionEnded() {
        String last = OpenSa2.readText(new File(getFilesDir(), "last-session.txt"));
        mLog.setText(last == null ? "" : "The last session:\n" + last);
        if (!OpenSa2.prefs(this).getBoolean("debug_logs", false) || !OpenSa2.haveStorage()) return;
        new Thread(() -> {
            try {
                File out = new File(OpenSa2.logsDir(), OpenSa2.stamp());
                out.mkdirs();
                File report = new File(getFilesDir(), "last-session.txt");
                if (report.isFile()) OpenSa2.copyFile(report, new File(out, "last-session.txt"));
                File codes = new File(getFilesDir(), "codes.log");
                if (codes.isFile()) OpenSa2.copyFile(codes, new File(out, "codes.log"));
                File[] sessions = new File(getFilesDir(), "sessions").listFiles();
                File newest = null;
                if (sessions != null) for (File s : sessions) if (newest == null || s.getName().compareTo(newest.getName()) > 0) newest = s;
                if (newest != null) copyLogs(newest, new File(out, newest.getName()));
                say("Logs copied to " + out.getAbsolutePath());
            } catch (Exception e) {
                say("Could not copy the logs: " + e.getMessage());
            }
        }).start();
    }

    private static void copyLogs(File from, File to) throws java.io.IOException {
        File[] kids = from.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) {
                copyLogs(k, new File(to, k.getName()));
            } else if (k.isFile() && k.length() < (64L << 20)) {
                // FIFOs and other special files are skipped by isFile(); huge records are left out.
                OpenSa2.copyFile(k, new File(to, k.getName()));
            }
        }
    }
}
