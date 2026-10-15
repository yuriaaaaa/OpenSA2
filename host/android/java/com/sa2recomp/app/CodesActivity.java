package com.sa2recomp.app;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Cheats and codes: the mod loader's code list (Codes.lst from X-Hax/sa2-mod-loader, downloaded to
 * Internal storage/OpenSA2/Codes.lst) and the active mods' own code files. The ones switched on
 * run against the game's memory while it plays (CodeEngine); the in-game menu switches them live.
 */
public class CodesActivity extends Activity {
    static final String CODES_URL = "https://raw.githubusercontent.com/X-Hax/sa2-mod-loader/master/data/Codes.lst";

    private LinearLayout mList;
    private TextView mStatus;
    private Set<String> mOn;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout col = Ui.screen(this, "Codes");
        col.addView(Ui.text(this, "The mod loader's cheats and patches. Codes run every frame while you play; "
                + "Patches run once at the start. Patches that change the game's program code cannot apply in "
                + "this port (it is recompiled), so a few do nothing: the debug info lists them. "
                + "Three-finger tap in game switches codes live.", 13, 0xFFA8B4C8));
        col.addView(Ui.row(this, Ui.button(this, "Download code list", v -> download())));
        mStatus = Ui.text(this, "", 13, OpenSa2.GOLD);
        col.addView(mStatus);
        mList = new LinearLayout(this);
        mList.setOrientation(LinearLayout.VERTICAL);
        col.addView(mList);
        load();
    }

    private void load() {
        mList.removeAllViews();
        if (!OpenSa2.haveStorage()) {
            mStatus.setText("Allow file access on the first screen first.");
            return;
        }
        mOn = ModManager.codesOn(this);
        List<String> errors = new ArrayList<>();
        List<CodeList.Code> all = ModManager.allCodes(this, errors);
        if (all.isEmpty()) {
            mStatus.setText("No code list yet: tap Download code list (or put a Codes.lst in Internal storage/OpenSA2/).");
        } else {
            mStatus.setText(all.size() + " codes." + (errors.isEmpty() ? "" : " " + errors.size() + " line(s) not understood."));
        }
        String lastCat = null;
        for (CodeList.Code c : all) {
            String cat = c.source.equals("Codes.lst") ? (c.category.isEmpty() ? "Other" : c.category) : c.source;
            if (!cat.equals(lastCat)) {
                mList.addView(Ui.heading(this, cat));
                lastCat = cat;
            }
            String key = ModManager.codeKey(c);
            boolean forced = !c.source.equals("Codes.lst") && c.required;
            String label = (c.patch ? "[Patch] " : "") + c.name + (forced ? "  (required by the mod)" : "");
            android.widget.CheckBox box = Ui.check(this, label, forced || mOn.contains(key), on -> {
                if (on) mOn.add(key);
                else mOn.remove(key);
                ModManager.saveCodesOn(this, mOn);
            });
            box.setEnabled(!forced);
            mList.addView(box);
            String about = c.description.isEmpty() ? "" : c.description;
            if (!c.author.isEmpty()) about += (about.isEmpty() ? "" : "  ") + "by " + c.author;
            if (!about.isEmpty()) mList.addView(Ui.text(this, about, 12, 0xFFA8B4C8));
        }
        for (int i = 0; i < Math.min(errors.size(), 10); i++) mList.addView(Ui.text(this, errors.get(i), 11, 0xFFFF8080));
    }

    private void download() {
        mStatus.setText("Downloading...");
        new Thread(() -> {
            String msg;
            try {
                byte[] body = fetch(CODES_URL);
                String text = new String(body, "UTF-8");
                if (!text.contains("Code") && !text.contains("Patch")) throw new IOException("that is not a code list");
                OpenSa2.makeFolders();
                OpenSa2.writeText(OpenSa2.codesFile(), text);
                msg = null;
            } catch (Exception e) {
                msg = "Could not download: " + (e.getMessage() == null ? e.toString() : e.getMessage());
            }
            final String m = msg;
            runOnUiThread(() -> {
                load();
                if (m != null) mStatus.setText(m);
            });
        }).start();
    }

    private static byte[] fetch(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[1 << 14];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }
}
