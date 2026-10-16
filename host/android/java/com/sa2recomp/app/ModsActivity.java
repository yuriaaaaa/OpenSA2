package com.sa2recomp.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The mod list: the sa2-mod-loader mods in Internal storage/OpenSA2/mods/ (a folder with a
 * mod.ini each), in load order. Lower in the list wins, as in the mod manager. A .zip or .7z can
 * be added from any file app, or a mod installed straight from GameBanana by its link, id, or a
 * one-click (sa2mm:) link the browser hands over (ModInstall, GameBanana). Play puts the enabled
 * mods in place.
 */
public class ModsActivity extends Activity {
    private static final int PICK_ZIP = 3;

    private LinearLayout mList;
    private final List<ModManager.Mod> mMods = new ArrayList<>();
    private final Set<String> mOn = new HashSet<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout col = Ui.screen(this, "Mods");
        col.addView(Ui.text(this, "Mods go in Internal storage/OpenSA2/mods/, one folder each with its mod.ini "
                + "(sa2-mod-loader / SA Mod Manager mods for the Steam game). Mods lower in the list win. "
                + "Changes apply at the next Play.", 13, 0xFFA8B4C8));
        col.addView(Ui.row(this,
                Ui.button(this, "Add mod from file", v -> pickZip()),
                Ui.button(this, "Browse GameBanana", v -> startActivity(new Intent(this, GbBrowseActivity.class)))));
        col.addView(Ui.row(this, Ui.button(this, "Rescan", v -> load())));
        mList = new LinearLayout(this);
        mList.setOrientation(LinearLayout.VERTICAL);
        col.addView(mList);
        load();
        handleOneClick(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleOneClick(intent);
    }

    /** A GameBanana one-click (sa2mm:) link, or a gamebanana.com link, the browser handed over. */
    private void handleOneClick(Intent it) {
        if (it == null || it.getData() == null) return;
        String uri = it.getData().toString();
        if (!uri.startsWith(GameBanana.SCHEME + ":") && !uri.contains("gamebanana.com")) return;
        installFromGameBanana(uri);
    }

    @Override
    protected void onPause() {
        super.onPause();
        save();
    }

    private void load() {
        OpenSa2.makeFolders();
        mMods.clear();
        mMods.addAll(ModManager.sorted(this));
        mOn.clear();
        mOn.addAll(ModManager.enabled(this));
        show();
    }

    private void save() {
        List<String> order = new ArrayList<>();
        Set<String> on = new HashSet<>();
        for (ModManager.Mod m : mMods) {
            order.add(m.folder);
            if (mOn.contains(m.folder)) on.add(m.folder);
        }
        ModManager.save(this, order, on);
    }

    private void show() {
        mList.removeAllViews();
        if (!OpenSa2.haveStorage()) {
            mList.addView(Ui.text(this, "Allow file access on the first screen first.", 15, 0xFFE0E6F0));
            return;
        }
        if (mMods.isEmpty()) {
            mList.addView(Ui.text(this, "No mods yet in " + OpenSa2.modsDir().getAbsolutePath(), 15, 0xFFE0E6F0));
            return;
        }
        for (int i = 0; i < mMods.size(); i++) {
            final int at = i;
            ModManager.Mod m = mMods.get(i);
            String label = (i + 1) + ". " + m.name + (m.version.isEmpty() ? "" : "  " + m.version)
                    + (m.author.isEmpty() ? "" : "  by " + m.author);
            CheckBox box = Ui.check(this, label, mOn.contains(m.folder), on -> {
                if (on) mOn.add(m.folder);
                else mOn.remove(m.folder);
                save();
            });
            mList.addView(box);
            List<String> warn = m.warnings();
            StringBuilder b = new StringBuilder();
            b.append(m.files).append(" game file(s)");
            if (!m.replaceFiles.isEmpty()) b.append(", ").append(m.replaceFiles.size()).append(" replaced");
            if (!m.codes.isEmpty()) b.append(", has codes");
            for (String n : m.notes()) b.append("\n").append(n);
            mList.addView(Ui.text(this, b.toString(), 12, 0xFFA8B4C8));
            if (!warn.isEmpty()) {
                StringBuilder w = new StringBuilder();
                for (String x : warn) w.append(w.length() == 0 ? "! " : "\n! ").append(x);
                mList.addView(Ui.text(this, w.toString(), 12, OpenSa2.GOLD));
            }
            Button up = Ui.button(this, "Up", v -> move(at, -1));
            Button down = Ui.button(this, "Down", v -> move(at, 1));
            Button info = Ui.button(this, "Info", v -> info(m));
            Button del = Ui.button(this, "Delete", v -> delete(m));
            up.setEnabled(at > 0);
            down.setEnabled(at < mMods.size() - 1);
            mList.addView(Ui.row(this, up, down, info, del));
        }
    }

    private void move(int at, int by) {
        int to = at + by;
        if (to < 0 || to >= mMods.size()) return;
        ModManager.Mod m = mMods.remove(at);
        mMods.add(to, m);
        save();
        show();
    }

    private void info(ModManager.Mod m) {
        StringBuilder b = new StringBuilder();
        if (!m.author.isEmpty()) b.append("Author: ").append(m.author).append('\n');
        if (!m.version.isEmpty()) b.append("Version: ").append(m.version).append('\n');
        if (!m.category.isEmpty()) b.append("Category: ").append(m.category).append('\n');
        b.append("Folder: ").append(m.dir.getAbsolutePath()).append("\n\n");
        if (!m.description.isEmpty()) b.append(m.description).append("\n\n");
        for (String n : m.notes()) b.append(n).append('\n');
        for (String w : m.warnings()) b.append("! ").append(w).append('\n');
        AlertDialog d = Ui.dialog(this).setTitle(m.name).setMessage(b.toString())
                .setPositiveButton("OK", null).create();
        Ui.style(d);
        d.show();
    }

    private void delete(ModManager.Mod m) {
        AlertDialog d = Ui.dialog(this).setTitle("Delete " + m.name + "?")
                .setMessage("Deletes the folder " + m.dir.getAbsolutePath())
                .setPositiveButton("Delete", (x, w) -> {
                    OpenSa2.removeTree(m.dir);
                    mOn.remove(m.folder);
                    mMods.remove(m);
                    save();
                    show();
                })
                .setNegativeButton("Cancel", null).create();
        Ui.style(d);
        d.show();
    }

    // ------------------------------------------------------------ adding a ZIP

    private void pickZip() {
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("*/*");
        it.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"application/zip", "application/x-zip-compressed",
                                                           "application/x-7z-compressed", "application/octet-stream"});
        try {
            startActivityForResult(it, PICK_ZIP);
        } catch (Exception e) {
            Toast.makeText(this, "No file picker on this phone: unpack the mod into OpenSA2/mods yourself.",
                           Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != PICK_ZIP || result != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final String name = displayName(uri);
        Toast.makeText(this, "Unpacking...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String msg;
            try {
                File tmp = File.createTempFile("mod", ".archive", getCacheDir());
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(tmp)) {
                    if (in == null) throw new IOException("the file could not be opened");
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                try {
                    List<String> names = ModInstall.install(this, tmp, name, s -> {});
                    msg = "Added " + String.join(", ", names);
                } finally {
                    tmp.delete();
                }
            } catch (Exception e) {
                msg = "Could not add the mod: " + (e.getMessage() == null ? e.toString() : e.getMessage());
            }
            final String m = msg;
            runOnUiThread(() -> {
                Toast.makeText(this, m, Toast.LENGTH_LONG).show();
                load();
            });
        }).start();
    }

    // ------------------------------------------------------------ GameBanana

    private void installFromGameBanana(String input) {
        if (input == null || input.trim().isEmpty()) return;
        if (!OpenSa2.haveStorage()) {
            Toast.makeText(this, "Allow file access on the first screen first.", Toast.LENGTH_LONG).show();
            return;
        }
        final TextView log = Ui.text(this, "Starting...", 12, OpenSa2.GOLD);
        AlertDialog d = Ui.dialog(this).setTitle("GameBanana install").setView(log)
                .setNegativeButton("Close", null).create();
        Ui.style(d);
        d.show();
        new Thread(() -> {
            String msg;
            try {
                List<String> names = GameBanana.install(this, input,
                        line -> runOnUiThread(() -> log.append("\n" + line)));
                msg = "Installed " + String.join(", ", names) + " (switched on)";
            } catch (Exception e) {
                msg = "Could not install: " + (e.getMessage() == null ? e.toString() : e.getMessage());
            }
            final String m = msg;
            runOnUiThread(() -> {
                log.append("\n" + m);
                load();
            });
        }).start();
    }

    private String displayName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) {
        }
        return ModInstall.safeName(name);
    }
}
