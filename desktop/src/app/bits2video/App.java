package app.bits2video;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Console;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Locale;

/** Desktop command-line entry point for both the plain (B2V2) and encrypted (B2V3) formats. */
public final class App {
    static final int BUF = 1 << 20;

    public static void main(String[] args) throws Exception {
        if (args.length < 3) { usage(); return; }
        String cmd = args[0], in = args[1], out = args[2];
        String password = null;
        File keyFile = null;
        for (int i = 3; i < args.length; i++) {
            if (args[i].equals("--password") && i + 1 < args.length) password = args[++i];
            else if (args[i].equals("--password-prompt")) password = promptPassword();
            else if (args[i].equals("--keyfile") && i + 1 < args.length) keyFile = new File(args[++i]);
            else { System.err.println("Unrecognized option: " + args[i]); usage(); System.exit(2); return; }
        }
        byte[] keyFileHash = null;
        if (keyFile != null) {
            try (InputStream kin = new BufferedInputStream(new FileInputStream(keyFile))) {
                keyFileHash = B2VSecure.hashKeyFile(kin);
            }
        }
        boolean encrypted = password != null || keyFileHash != null;

        switch (cmd) {
            case "encode": {
                File f = new File(in);
                if (!f.isFile()) { System.err.println("No such file: " + in); System.exit(1); return; }
                long start = System.currentTimeMillis();
                try (InputStream is = new BufferedInputStream(new FileInputStream(f), BUF);
                     OutputStream os = new BufferedOutputStream(new FileOutputStream(out), BUF)) {
                    Progress p = new Progress("Encoding", f.length());
                    if (encrypted) B2VSecure.encode(is, f.length(), f.getName(), new B2VSecure.Key(password, keyFileHash), os, p);
                    else B2V.encode(is, f.length(), f.getName(), os, p);
                    p.done();
                }
                System.out.println((encrypted ? "Encrypted and encoded " : "Encoded ") + in + " -> " + out
                        + "  (" + mb(f.length()) + ", " + secs(start) + ")");
                break;
            }
            case "decode": {
                File outDir = new File(out);
                outDir.mkdirs();
                byte[] magic;
                try (InputStream is = new BufferedInputStream(new FileInputStream(in), BUF)) {
                    magic = B2V.peekMagic(is);
                }
                long start = System.currentTimeMillis();
                if (java.util.Arrays.equals(magic, B2VSecure.MAGIC)) {
                    if (!encrypted) {
                        System.err.println("This video is encrypted. Pass --password and/or --keyfile "
                                + "with the same password/key file it was encoded with.");
                        System.exit(1); return;
                    }
                    File tmp = new File(outDir, ".b2v_decoding");
                    B2VSecure.Result r;
                    try (InputStream is = new BufferedInputStream(new FileInputStream(in), BUF);
                         OutputStream os = new BufferedOutputStream(new FileOutputStream(tmp), BUF)) {
                        Progress p = new Progress("Decoding", -1);
                        r = B2VSecure.decode(is, new B2VSecure.Key(password, keyFileHash), os, p);
                        p.done();
                    } catch (Exception e) {
                        tmp.delete();
                        throw e;
                    }
                    File dest = new File(outDir, safeName(r.name));
                    if (!tmp.renameTo(dest)) { Files.copy(tmp.toPath(), dest.toPath()); tmp.delete(); }
                    System.out.println("Decoded -> " + dest + "  (" + mb(r.totalBytes) + ", " + secs(start)
                            + ")\nThe key checked out and every frame passed its AES-GCM authentication tag.");
                } else if (java.util.Arrays.equals(magic, B2V.MAGIC)) {
                    B2V.Header peeked;
                    try (InputStream is = new BufferedInputStream(new FileInputStream(in), BUF)) {
                        peeked = B2V.peek(is);
                    }
                    File tmp = new File(outDir, ".b2v_decoding");
                    B2V.Header h;
                    try (InputStream is = new BufferedInputStream(new FileInputStream(in), BUF);
                         OutputStream os = new BufferedOutputStream(new FileOutputStream(tmp), BUF)) {
                        Progress p = new Progress("Decoding", peeked.totalBytes);
                        h = B2V.decode(is, os, p);
                        p.done();
                    } catch (Exception e) {
                        tmp.delete();
                        throw e;
                    }
                    File dest = new File(outDir, safeName(h.name));
                    if (!tmp.renameTo(dest)) { Files.copy(tmp.toPath(), dest.toPath()); tmp.delete(); }
                    System.out.println("Decoded -> " + dest + "  (" + mb(h.totalBytes) + ", " + secs(start)
                            + ")\nAll checksums OK.");
                } else {
                    System.err.println("Not a bits2video file (unrecognized header): " + in);
                    System.exit(1);
                }
                break;
            }
            default:
                usage();
                System.exit(2);
        }
    }

    static String safeName(String name) {
        String n = name.replace('/', '_').replace('\\', '_').trim();
        return n.isEmpty() ? "decoded" : n;
    }

    static String promptPassword() {
        Console c = System.console();
        if (c == null) { System.err.print("Password: "); return new java.util.Scanner(System.in).nextLine(); }
        char[] pw = c.readPassword("Password: ");
        return new String(pw);
    }

    static String mb(long b) { return String.format(Locale.US, "%.1f MB", b / 1e6); }

    static String secs(long startMs) {
        return String.format(Locale.US, "%.1fs", (System.currentTimeMillis() - startMs) / 1000.0);
    }

    static final class Progress implements B2V.Progress {
        final String label; final long total; long lastPrint;
        Progress(String label, long total) { this.label = label; this.total = total; }
        public void update(long done, long total) {
            long now = System.currentTimeMillis();
            if (now - lastPrint < 200 && done < total) return;
            lastPrint = now;
            if (total > 0) System.out.printf(Locale.US, "\r%s... %s of %s (%.0f%%)   ", label, mb(done), mb(total), 100.0 * done / total);
            else System.out.printf(Locale.US, "\r%s... %s   ", label, mb(done));
        }
        void done() { System.out.println(); }
    }

    static void usage() {
        System.out.println(
                "bits2video - encode any file into a lossless video, and decode it back.\n\n" +
                "  java -jar bits2video.jar encode <input-file> <output.avi> [--password PW] [--keyfile FILE] [--password-prompt]\n" +
                "  java -jar bits2video.jar decode <input.avi> <output-folder> [--password PW] [--keyfile FILE] [--password-prompt]\n\n" +
                "Examples:\n" +
                "  java -jar bits2video.jar encode photo.jpg photo.b2v.avi\n" +
                "  java -jar bits2video.jar decode photo.b2v.avi .\n" +
                "  java -jar bits2video.jar encode secrets.zip secrets.b2v3.avi --password-prompt\n" +
                "  java -jar bits2video.jar decode secrets.b2v3.avi . --password-prompt --keyfile mykey.bin\n\n" +
                "Leave --password and --keyfile out to make a plain, unprotected video. Set either or both\n" +
                "to encrypt with AES-256-GCM; decoding then requires the same password and/or key file.\n" +
                "--password-prompt reads the password from the terminal instead of the command line, so it\n" +
                "doesn't end up in your shell history.");
    }
}
