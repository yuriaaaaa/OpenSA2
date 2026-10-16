package com.sa2recomp.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;

/**
 * Browse and search GameBanana's Sonic Adventure 2 mods and install one by tapping it, instead of
 * pasting a link. The one box searches by text, or, when what is typed is a mod link, a bare id,
 * or an sa2mm: one-click link, installs that directly (GameBanana). Results come from the public
 * API a page at a time; a tap downloads and unpacks the mod into Internal storage/OpenSA2/mods/.
 */
public class GbBrowseActivity extends Activity {
    private EditText mQuery;
    private LinearLayout mResults;
    private Button mMore;
    private TextView mStatus;
    private String mMode = "new";      // "new" (browse) or a search string
    private int mPage = 1;
    private boolean mBusy;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout col = Ui.screen(this, "GameBanana");
        col.addView(Ui.text(this, "Search Sonic Adventure 2 mods, or paste a mod link, its id, or a "
                + "1-click link. Tap a mod to download and install it. Only .zip and .7z mods can be unpacked "
                + "here.", 13, 0xFFA8B4C8));

        mQuery = new EditText(this);
        mQuery.setHint("Search mods, or paste a link / id");
        mQuery.setTextColor(Color.WHITE);
        mQuery.setHintTextColor(0xFF6B7690);
        mQuery.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        mQuery.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        mQuery.setOnEditorActionListener((v, id, e) -> {
            go();
            return true;
        });
        col.addView(mQuery);
        col.addView(Ui.row(this,
                Ui.button(this, "Search", v -> go()),
                Ui.button(this, "Newest", v -> {
                    mQuery.setText("");
                    mMode = "new";
                    mPage = 1;
                    fetch(true);
                })));

        mStatus = Ui.text(this, "", 13, OpenSa2.GOLD);
        col.addView(mStatus);
        mResults = new LinearLayout(this);
        mResults.setOrientation(LinearLayout.VERTICAL);
        col.addView(mResults);
        mMore = Ui.button(this, "Load more", v -> fetch(false));
        mMore.setVisibility(View.GONE);
        col.addView(Ui.row(this, mMore));

        fetch(true);        // the newest SA2 mods to start
    }

    /** The box: install a link/id/one-click directly, otherwise search by its text. */
    private void go() {
        String q = mQuery.getText().toString().trim();
        if (q.isEmpty()) {
            mMode = "new";
        } else if (q.toLowerCase(Locale.ROOT).startsWith(GameBanana.SCHEME + ":")
                || q.contains("gamebanana.com") || q.matches("\\d+")) {
            install(q, q);
            return;
        } else {
            mMode = q;
        }
        mPage = 1;
        fetch(true);
    }

    /** Loads a page of results; `reset` clears the list (a new search), else appends (Load more). */
    private void fetch(boolean reset) {
        if (mBusy) return;
        mBusy = true;
        if (reset) {
            mResults.removeAllViews();
            mPage = 1;
            mMore.setVisibility(View.GONE);
        }
        mStatus.setText(mMode.equals("new") ? "Loading the newest mods..." : "Searching “" + mMode + "”...");
        final int page = mPage;
        new Thread(() -> {
            String err = null;
            List<GameBanana.Item> items = null;
            try {
                items = mMode.equals("new") ? GameBanana.browse(page, "new") : GameBanana.search(mMode, page);
            } catch (Exception e) {
                err = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final String e = err;
            final List<GameBanana.Item> got = items;
            runOnUiThread(() -> {
                mBusy = false;
                if (e != null) {
                    mStatus.setText("Could not reach GameBanana: " + e);
                    return;
                }
                if (got.isEmpty() && mResults.getChildCount() == 0) {
                    mStatus.setText(mMode.equals("new") ? "No mods found." : "No mods match “" + mMode + "”.");
                    return;
                }
                mStatus.setText("");
                for (GameBanana.Item it : got) mResults.addView(row(it));
                mPage = page + 1;
                mMore.setVisibility(got.size() >= 15 ? View.VISIBLE : View.GONE);
            });
        }).start();
    }

    private View row(GameBanana.Item it) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Ui.dp(this, 8);
        r.setPadding(pad, pad, pad, pad);
        r.setClickable(true);
        r.setBackgroundColor(0xFF16203A);

        ImageView img = new ImageView(this);
        int side = Ui.dp(this, 56);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(side, side);
        ip.setMargins(0, 0, pad, 0);
        img.setLayoutParams(ip);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackgroundColor(0xFF0B1326);
        r.addView(img);
        if (it.thumbUrl != null) loadThumb(it.thumbUrl, img, side);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView name = new TextView(this);
        name.setText(it.name);
        name.setTextColor(Color.WHITE);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        text.addView(name);
        String sub = it.subtitle();
        if (!sub.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextColor(0xFFA8B4C8);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            text.addView(s);
        }
        r.addView(text);

        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.setMargins(0, Ui.dp(this, 3), 0, Ui.dp(this, 3));
        r.setLayoutParams(rp);
        r.setOnClickListener(v -> confirm(it));
        return r;
    }

    private void confirm(GameBanana.Item it) {
        String msg = (it.author.isEmpty() ? "" : "by " + it.author + "\n")
                + "gamebanana.com/mods/" + it.id + "\n\nDownload this mod and install it into "
                + "Internal storage/OpenSA2/mods/?"
                + (it.hasFiles ? "" : "\n\n(This submission lists no files to download.)");
        AlertDialog d = Ui.dialog(this).setTitle(it.name).setMessage(msg)
                .setPositiveButton("Install", (x, w) -> install(String.valueOf(it.id), it.name))
                .setNegativeButton("Cancel", null).create();
        Ui.style(d);
        d.show();
    }

    private void install(String input, String title) {
        if (!OpenSa2.haveStorage()) {
            Toast.makeText(this, "Allow file access on the first screen first.", Toast.LENGTH_LONG).show();
            return;
        }
        final TextView log = Ui.text(this, "Starting…", 12, OpenSa2.GOLD);
        log.setTypeface(Typeface.MONOSPACE);
        AlertDialog d = Ui.dialog(this).setTitle("Installing " + title).setView(wrapScroll(log))
                .setNegativeButton("Close", null).create();
        Ui.style(d);
        d.show();
        new Thread(() -> {
            String msg;
            try {
                List<String> names = GameBanana.install(this, input, line -> runOnUiThread(() -> log.append("\n" + line)));
                msg = "Installed " + String.join(", ", names) + " — switched on. It will apply at the next Play.";
            } catch (Exception e) {
                msg = "Could not install: " + (e.getMessage() == null ? e.toString() : e.getMessage());
            }
            final String m = msg;
            runOnUiThread(() -> log.append("\n\n" + m));
        }).start();
    }

    private View wrapScroll(View v) {
        android.widget.ScrollView s = new android.widget.ScrollView(this);
        int pad = Ui.dp(this, 14);
        v.setPadding(pad, pad, pad, pad);
        s.addView(v);
        return s;
    }

    /** A small preview image, loaded off the UI thread and downscaled to the row's size. */
    private void loadThumb(String url, ImageView img, int side) {
        final Object tag = new Object();
        img.setTag(tag);
        new Thread(() -> {
            Bitmap bmp = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(20000);
                c.setRequestProperty("User-Agent", "OpenSA2/1.0 (Android)");
                try (InputStream in = c.getInputStream()) {
                    byte[] data = OpenSa2.readAll(in, 4 << 20);
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(data, 0, data.length, o);
                    o.inSampleSize = Math.max(1, Math.min(o.outWidth, o.outHeight) / side);
                    o.inJustDecodeBounds = false;
                    bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o);
                } finally {
                    c.disconnect();
                }
            } catch (Exception ignored) {
            }
            final Bitmap out = bmp;
            if (out != null) runOnUiThread(() -> {
                if (img.getTag() == tag) img.setImageBitmap(out);
            });
        }).start();
    }
}
