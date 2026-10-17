package app.bits2video.playable;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Locale;

public class MainActivity extends Activity {
    static final int PICK_ENCODE_IN = 1, PICK_ENCODE_OUT = 2, PICK_DECODE_IN = 3, PICK_DECODE_OUT = 4,
            PICK_KEY_FILE = 5;
    static final int BUF = 1 << 20;
    static final int FORMAT_B2V2 = 2, FORMAT_B2V3 = 3;

    Button encodeBtn, decodeBtn, cancelBtn, keyFileBtn, keyFileClearBtn;
    EditText passwordField;
    CheckBox showPasswordBox;
    TextView keyFileStatus;
    ProgressBar bar;
    TextView status;

    Uri pendingIn;
    String pendingName;
    long pendingSize;
    int pendingFormat;
    boolean pendingEncrypt;
    B2VSecure.Key pendingKey;
    byte[] keyFileHash;
    String keyFileName;

    volatile boolean cancelled;
    volatile Thread worker;

    static final class Cancelled extends RuntimeException {}

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = dp(20);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Bits2Video Playable");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);

        TextView info = new TextView(this);
        info.setText("Encode any file into a lossless video of 1920x1080 frames, where each pixel's "
                + "red, green and blue values hold 3 bytes of the file. Decode turns such a video back "
                + "into the exact original file, with its original name.\n\n"
                + "This version packages the same data as a plain AVI file (instead of Matroska), so it "
                + "plays in VLC and most other players -- same size as before, just playable. Don't "
                + "re-encode, trim or upload it to a site that recompresses video, or it can't be decoded.");
        info.setTextSize(15);
        info.setPadding(0, dp(8), 0, dp(20));
        col.addView(info);

        col.addView(sectionTitle("Password / key file (optional)"));

        TextView cryptoInfo = new TextView(this);
        cryptoInfo.setText("Leave both blank to make a plain, unprotected video. Set either or both to "
                + "encrypt it with AES-256-GCM: the resulting video decodes only with the same password "
                + "and/or key file entered here. There is no way to recover the file if you lose them.");
        cryptoInfo.setTextSize(14);
        cryptoInfo.setPadding(0, dp(4), 0, dp(10));
        col.addView(cryptoInfo);

        passwordField = new EditText(this);
        passwordField.setHint("Password (optional)");
        passwordField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordField.setTransformationMethod(PasswordTransformationMethod.getInstance());
        col.addView(passwordField, new LinearLayout.LayoutParams(-1, -2));

        showPasswordBox = new CheckBox(this);
        showPasswordBox.setText("Show password");
        showPasswordBox.setOnCheckedChangeListener((btn, checked) -> {
            passwordField.setTransformationMethod(checked
                    ? HideReturnsTransformationMethod.getInstance() : PasswordTransformationMethod.getInstance());
            passwordField.setSelection(passwordField.getText().length());
        });
        col.addView(showPasswordBox);

        LinearLayout keyRow = new LinearLayout(this);
        keyRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.topMargin = dp(8);
        keyFileBtn = new Button(this);
        keyFileBtn.setText("Choose key file");
        keyFileBtn.setAllCaps(false);
        keyFileBtn.setOnClickListener(v -> pick(PICK_KEY_FILE));
        keyRow.addView(keyFileBtn);
        keyFileClearBtn = new Button(this);
        keyFileClearBtn.setText("Clear");
        keyFileClearBtn.setAllCaps(false);
        keyFileClearBtn.setVisibility(View.GONE);
        keyFileClearBtn.setOnClickListener(v -> {
            keyFileHash = null; keyFileName = null;
            keyFileStatus.setText("No key file selected");
            keyFileClearBtn.setVisibility(View.GONE);
        });
        keyRow.addView(keyFileClearBtn);
        col.addView(keyRow, rowLp);

        keyFileStatus = new TextView(this);
        keyFileStatus.setText("No key file selected");
        keyFileStatus.setTextSize(14);
        keyFileStatus.setPadding(0, dp(4), 0, dp(20));
        col.addView(keyFileStatus);

        encodeBtn = button("Encode a file", v -> pick(PICK_ENCODE_IN));
        decodeBtn = button("Decode a video", v -> pick(PICK_DECODE_IN));
        col.addView(encodeBtn);
        col.addView(decodeBtn);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setVisibility(View.GONE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(24);
        col.addView(bar, lp);

        status = new TextView(this);
        status.setTextSize(15);
        status.setPadding(0, dp(8), 0, dp(8));
        col.addView(status);

        cancelBtn = button("Cancel", v -> { cancelled = true; status.setText("Cancelling…"); });
        cancelBtn.setVisibility(View.GONE);
        col.addView(cancelBtn);

        ScrollView root = new ScrollView(this);
        root.addView(col);
        root.setOnApplyWindowInsetsListener((v, in) -> {          // targetSdk 35+ draws edge to edge
            int l, t, r, bt;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = in.getInsets(WindowInsets.Type.systemBars());
                l = i.left; t = i.top; r = i.right; bt = i.bottom;
            } else {
                l = in.getSystemWindowInsetLeft(); t = in.getSystemWindowInsetTop();
                r = in.getSystemWindowInsetRight(); bt = in.getSystemWindowInsetBottom();
            }
            v.setPadding(l, t, r, bt);
            return in;
        });
        setContentView(root);
    }

    TextView sectionTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(17);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    Button button(String text, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextSize(17);
        btn.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(56));
        lp.topMargin = dp(8);
        btn.setLayoutParams(lp);
        return btn;
    }

    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    void pick(int code) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, code);
    }

    void create(int code, String name, String mime) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime);
        i.putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(i, code);
    }

    boolean hasKey() {
        return !passwordField.getText().toString().isEmpty() || keyFileHash != null;
    }

    B2VSecure.Key snapshotKey() {
        return new B2VSecure.Key(passwordField.getText().toString(), keyFileHash);
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        switch (code) {
            case PICK_KEY_FILE: {
                String kfName = queryName(uri);
                setBusy(true);
                status.setText("Reading key file…");
                new Thread(() -> {
                    try (InputStream in = new BufferedInputStream(open(uri), BUF)) {
                        byte[] hash = B2VSecure.hashKeyFile(in);
                        runOnUiThread(() -> {
                            keyFileHash = hash; keyFileName = kfName;
                            keyFileStatus.setText("Key file: " + kfName);
                            keyFileClearBtn.setVisibility(View.VISIBLE);
                            setBusy(false); status.setText("");
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> { setBusy(false); status.setText("Couldn't read key file: " + msg(e)); });
                    }
                }).start();
                break;
            }
            case PICK_ENCODE_IN:
                pendingIn = uri;
                pendingName = queryName(uri);
                pendingSize = querySize(uri);
                if (pendingSize < 0) { status.setText("Couldn't read that file's size."); return; }
                pendingEncrypt = hasKey();
                if (pendingEncrypt) pendingKey = snapshotKey();
                create(PICK_ENCODE_OUT, pendingName + (pendingEncrypt ? ".b2v3.avi" : ".b2v.avi"), "video/x-msvideo");
                break;
            case PICK_ENCODE_OUT:
                runJob("Encoding " + pendingName, uri, (prog) -> {
                    try (InputStream in = new BufferedInputStream(open(pendingIn), BUF);
                         OutputStream out = new BufferedOutputStream(openOut(uri), BUF)) {
                        if (pendingEncrypt) B2VSecure.encode(in, pendingSize, pendingName, pendingKey, out, prog);
                        else B2V.encode(in, pendingSize, pendingName, out, prog);
                    }
                    return "Encoded " + pendingName + " (" + mb(pendingSize) + ")"
                            + (pendingEncrypt ? ", encrypted with AES-256-GCM." : ".");
                });
                break;
            case PICK_DECODE_IN:
                pendingIn = uri;
                setBusy(true);
                status.setText("Reading video header…");
                new Thread(() -> {
                    try {
                        byte[] magic;
                        try (InputStream in = new BufferedInputStream(open(uri), BUF)) {
                            magic = B2V.peekMagic(in);
                        }
                        if (Arrays.equals(magic, B2VSecure.MAGIC)) {
                            pendingFormat = FORMAT_B2V3;
                            if (!hasKey()) {
                                runOnUiThread(() -> { setBusy(false);
                                    status.setText("This video is encrypted. Enter the password and/or choose "
                                            + "the key file it was encoded with above, then decode it again."); });
                                return;
                            }
                            pendingKey = snapshotKey();
                            // The real name is only known once the key has been verified, during decode.
                            runOnUiThread(() -> { setBusy(false); status.setText("");
                                create(PICK_DECODE_OUT, "decoded", "application/octet-stream"); });
                        } else if (Arrays.equals(magic, B2V.MAGIC)) {
                            pendingFormat = FORMAT_B2V2;
                            B2V.Header h;
                            try (InputStream in = new BufferedInputStream(open(uri), BUF)) {
                                h = B2V.peek(in);
                            }
                            pendingSize = h.totalBytes;
                            String name = h.name.replace('/', '_').replace('\\', '_');
                            runOnUiThread(() -> { setBusy(false); status.setText("");
                                create(PICK_DECODE_OUT, name.isEmpty() ? "decoded" : name, "application/octet-stream"); });
                        } else {
                            throw new IOException("not a bits2video file (unrecognized header)");
                        }
                    } catch (Exception e) {
                        runOnUiThread(() -> { setBusy(false); status.setText("Can't decode: " + msg(e)); });
                    }
                }).start();
                break;
            case PICK_DECODE_OUT:
                runJob("Decoding", uri, (prog) -> {
                    if (pendingFormat == FORMAT_B2V3) {
                        B2VSecure.Result r;
                        try (InputStream in = new BufferedInputStream(open(pendingIn), BUF);
                             OutputStream out = new BufferedOutputStream(openOut(uri), BUF)) {
                            r = B2VSecure.decode(in, pendingKey, out, prog);
                        }
                        tryRename(uri, r.name);
                        return "Decoded " + r.name + " (" + mb(r.totalBytes) + "). "
                                + "The key checked out and every frame passed its AES-GCM authentication tag.";
                    } else {
                        B2V.Header h;
                        try (InputStream in = new BufferedInputStream(open(pendingIn), BUF);
                             OutputStream out = new BufferedOutputStream(openOut(uri), BUF)) {
                            h = B2V.decode(in, out, prog);
                        }
                        return "Decoded " + h.name + " (" + mb(h.totalBytes) + "). All checksums OK.";
                    }
                });
                break;
        }
    }

    void tryRename(Uri uri, String name) {
        String clean = name.replace('/', '_').replace('\\', '_');
        if (clean.isEmpty()) return;
        try { DocumentsContract.renameDocument(getContentResolver(), uri, clean); }
        catch (Exception ignored) { }                      // not every storage provider supports renaming
    }

    interface Job { String run(B2V.Progress p) throws Exception; }

    void runJob(String label, Uri output, Job job) {
        cancelled = false;
        setBusy(true);
        status.setText(label + "…");
        final long start = SystemClock.elapsedRealtime();
        final long[] lastUi = {0};
        B2V.Progress prog = (done, total) -> {
            if (cancelled) throw new Cancelled();
            long now = SystemClock.elapsedRealtime();
            if (now - lastUi[0] < 250 && done < total) return;
            lastUi[0] = now;
            double secs = Math.max(0.001, (now - start) / 1000.0);
            String s = String.format(Locale.US, "%s… %s of %s (%.1f MB/s)",
                    label, mb(done), mb(total), done / 1e6 / secs);
            int p = total > 0 ? (int) (done * 1000 / total) : 1000;
            runOnUiThread(() -> { bar.setProgress(p); status.setText(s); });
        };
        worker = new Thread(() -> {
            String result;
            boolean ok = false;
            try {
                result = job.run(prog);
                ok = true;
            } catch (Cancelled c) {
                result = "Cancelled. The partial output file was deleted.";
            } catch (Throwable e) {
                result = "Failed: " + msg(e) + "\nThe partial output file was deleted.";
            }
            if (!ok) {
                try { DocumentsContract.deleteDocument(getContentResolver(), output); }
                catch (Exception ignored) { }
            }
            String r = result;
            runOnUiThread(() -> { setBusy(false); status.setText(r); });
        });
        worker.start();
    }

    void setBusy(boolean busy) {
        encodeBtn.setEnabled(!busy);
        decodeBtn.setEnabled(!busy);
        keyFileBtn.setEnabled(!busy);
        cancelBtn.setVisibility(busy ? View.VISIBLE : View.GONE);
        bar.setVisibility(busy ? View.VISIBLE : View.GONE);
        bar.setProgress(0);
        if (busy) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    InputStream open(Uri u) throws FileNotFoundException {
        InputStream in = getContentResolver().openInputStream(u);
        if (in == null) throw new FileNotFoundException("can't open file");
        return in;
    }

    OutputStream openOut(Uri u) throws FileNotFoundException {
        ContentResolver cr = getContentResolver();
        OutputStream out;
        try { out = cr.openOutputStream(u, "wt"); }               // truncate if overwriting
        catch (Exception e) { out = cr.openOutputStream(u, "w"); }
        if (out == null) throw new FileNotFoundException("can't write output file");
        return out;
    }

    String queryName(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (Exception ignored) { }
        String s = u.getLastPathSegment();
        return s == null ? "file" : s;
    }

    long querySize(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Exception ignored) { }
        try (AssetFileDescriptor fd = getContentResolver().openAssetFileDescriptor(u, "r")) {
            if (fd != null && fd.getLength() >= 0) return fd.getLength();
        } catch (IOException ignored) { }
        return -1;
    }

    static String mb(long b) { return String.format(Locale.US, "%.1f MB", b / 1e6); }

    static String msg(Throwable e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }
}
