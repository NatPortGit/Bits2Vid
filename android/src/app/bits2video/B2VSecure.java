package app.bits2video;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * bits2video format B2V3: password/key-file encrypted variant of B2V2.
 *
 * Same 1920x1080 pixel grid and Matroska container as B2V2 (see B2V.java), but:
 *
 *  - Cluster 0 is a plaintext "envelope" frame, not data: 'B2V3' | salt[16] |
 *    nonceBase[8] | pbkdf2Iterations u32 | dataFrames u32, zero padded to CAP.
 *    Nothing confidential is stored unencrypted; it only lets a decoder derive
 *    the key and plan the read.
 *
 *  - Clusters 1..dataFrames each hold one AES-256-GCM encrypted chunk:
 *    idx u32 | total u32 | ciphertext[CAP-24] | tag[16]. The key is derived
 *    with PBKDF2WithHmacSHA256 (600,000 iterations, OWASP's current floor for
 *    PBKDF2-SHA256) from the password and/or a SHA-256 hash of a key file,
 *    plus the random salt. The 96-bit GCM IV is nonceBase + idx, unique per
 *    frame and per file since nonceBase and salt are freshly random each
 *    encode. idx/total are authenticated (GCM additional data) but not
 *    secret, so truncation, reordering or substitution is detected without
 *    needing them to be hidden.
 *
 *  - The encrypted logical stream is: nameLen u16 | name (UTF-8) |
 *    totalBytes u64 | file bytes, chunked into CAP-24-byte pieces (zero
 *    padded in the last chunk) before encryption. A wrong key or any
 *    tampering fails a chunk's authentication tag and aborts the decode
 *    before anything is written; there is no way to "partially" decode
 *    with the wrong key.
 */
public final class B2VSecure {
    static final byte[] MAGIC = {'B', '2', 'V', '3'};
    static final int ENVELOPE_SIZE = 4 + 16 + 8 + 4 + 4;   // magic+salt+nonceBase+iters+dataFrames
    static final int SALT_LEN = 16, NONCE_BASE_LEN = 8, GCM_TAG_LEN = 16, GCM_IV_LEN = 12;
    static final int ITERATIONS = 600_000;
    static final int FRAME_HDR = 8;                         // idx u32 + total u32
    static final int CIPHER_LEN = B2V.CAP - FRAME_HDR - GCM_TAG_LEN;   // plaintext bytes per frame

    /**
     * A password, a key file, or both. At least one is required. keyFileSha256 is the
     * SHA-256 digest of the key file's bytes (callers stream-hash the file rather than
     * holding an arbitrarily large key file in memory).
     */
    public static final class Key {
        public final String password;
        public final byte[] keyFileSha256;
        public Key(String password, byte[] keyFileSha256) { this.password = password; this.keyFileSha256 = keyFileSha256; }
    }

    public static final class Envelope {
        public byte[] salt, nonceBase;
        public int iterations, dataFrames;
    }

    // ---------------------------------------------------------------- encode

    public static void encode(InputStream in, long size, String name, Key key, OutputStream out, B2V.Progress p)
            throws IOException, GeneralSecurityException {
        byte[] nameBytes = B2V.truncUtf8(name == null ? "file" : name, 255);
        long logicalLen = 2 + nameBytes.length + 8 + size;
        long n = (logicalLen + CIPHER_LEN - 1) / CIPHER_LEN;
        if (n > 0xFFFFFFFFL) throw new IOException("file too large");

        SecureRandom rng = new SecureRandom();
        byte[] salt = new byte[SALT_LEN]; rng.nextBytes(salt);
        byte[] nonceBase = new byte[NONCE_BASE_LEN]; rng.nextBytes(nonceBase);
        SecretKeySpec aesKey = deriveKey(secretMaterial(key), salt, ITERATIONS);

        B2V.writeMkvHeader(out, n + 1);
        byte[] calib = B2V.calibrationRow();
        byte[] frame = new byte[B2V.FRAME_BYTES];

        byte[] envelope = new byte[B2V.CAP];
        ByteBuffer e = ByteBuffer.wrap(envelope);
        e.put(MAGIC).put(salt).put(nonceBase).putInt(ITERATIONS).putInt((int) n);
        B2V.toFrame(envelope, calib, frame);
        B2V.writeCluster(out, 0, frame);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] plain = new byte[CIPHER_LEN];
        ByteBuffer header = ByteBuffer.allocate(2 + nameBytes.length + 8);
        header.putShort((short) nameBytes.length).put(nameBytes).putLong(size);
        byte[] headerBytes = header.array();
        int headerPos = 0;
        long done = 0;

        for (long i = 0; i < n; i++) {
            int pos = 0;
            if (headerPos < headerBytes.length) {
                int take = Math.min(headerBytes.length - headerPos, CIPHER_LEN);
                System.arraycopy(headerBytes, headerPos, plain, 0, take);
                headerPos += take; pos = take;
            }
            if (pos < CIPHER_LEN) {
                int take = (int) Math.min(CIPHER_LEN - pos, size - done);
                B2V.readFully(in, plain, pos, take);
                done += take; pos += take;
            }
            Arrays.fill(plain, pos, CIPHER_LEN, (byte) 0);

            byte[] iv = iv(nonceBase, i);
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_LEN * 8, iv));
            cipher.updateAAD(aad((int) i, (int) n));
            byte[] cipherText = cipher.doFinal(plain);         // CIPHER_LEN + GCM_TAG_LEN bytes

            byte[] dataBuf = new byte[B2V.CAP];
            ByteBuffer d = ByteBuffer.wrap(dataBuf);
            d.putInt((int) i).putInt((int) n).put(cipherText);
            B2V.toFrame(dataBuf, calib, frame);
            B2V.writeCluster(out, i + 1, frame);
            if (p != null) p.update(done, size);
        }
        if (in.read() != -1) throw new IOException("input is longer than its reported size");
        out.flush();
    }

    // ---------------------------------------------------------------- decode

    /** Reads only the envelope (cluster 0); does not need the key. */
    public static Envelope peekEnvelope(InputStream in) throws IOException {
        byte[] frame = new byte[B2V.FRAME_BYTES], data = new byte[B2V.CAP];
        if (!new B2V.FrameReader(in).next(frame)) throw new IOException("no video frames found");
        B2V.fromFrame(frame, data);
        return parseEnvelope(data);
    }

    static Envelope parseEnvelope(byte[] data) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(data);
        byte[] magic = new byte[4]; b.get(magic);
        if (!Arrays.equals(magic, MAGIC)) throw new IOException("not a B2V3 encrypted video (bad header)");
        Envelope e = new Envelope();
        e.salt = new byte[SALT_LEN]; b.get(e.salt);
        e.nonceBase = new byte[NONCE_BASE_LEN]; b.get(e.nonceBase);
        e.iterations = b.getInt();
        e.dataFrames = b.getInt();
        if (e.iterations <= 0 || e.dataFrames < 0) throw new IOException("B2V3 header damaged");
        return e;
    }

    /** Returns the decoded name and size once the key has checked out (after frame 1). */
    public static final class Result { public String name; public long totalBytes; }

    public static Result decode(InputStream in, Key key, OutputStream out, B2V.Progress p)
            throws IOException, GeneralSecurityException {
        B2V.FrameReader fr = new B2V.FrameReader(in);
        byte[] frame = new byte[B2V.FRAME_BYTES], data = new byte[B2V.CAP];
        if (!fr.next(frame)) throw new IOException("no video frames found");
        B2V.fromFrame(frame, data);
        Envelope env = parseEnvelope(data);
        SecretKeySpec aesKey = deriveKey(secretMaterial(key), env.salt, env.iterations);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");

        Result result = new Result();
        long written = 0;                 // bytes of real file content written so far
        long headerConsumed = 0;           // bytes of the nameLen+name+totalBytes header consumed
        byte[] headerBuf = null;           // filled once nameLen is known
        int headerPos = 0;
        boolean haveHeader = false;

        for (int i = 0; i < env.dataFrames; i++) {
            if (!fr.next(frame)) throw new IOException("video ends early: frame " + i + " of " + env.dataFrames + " missing");
            B2V.fromFrame(frame, data);
            ByteBuffer d = ByteBuffer.wrap(data);
            int idx = d.getInt(), total = d.getInt();
            if (idx != i || total != env.dataFrames)
                throw new IOException("frame " + i + ": index damaged or out of order");
            byte[] cipherText = Arrays.copyOfRange(data, FRAME_HDR, B2V.CAP);
            byte[] iv = iv(env.nonceBase, i);
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_LEN * 8, iv));
            cipher.updateAAD(aad(i, total));
            byte[] plain;
            try {
                plain = cipher.doFinal(cipherText);
            } catch (GeneralSecurityException e) {
                throw new IOException("wrong key, or frame " + i + " is corrupted or tampered with");
            }

            int pos = 0;
            if (!haveHeader) {
                if (headerBuf == null) {
                    if (plain.length < 2) throw new IOException("B2V3 stream too short");
                    int nameLen = ((plain[0] & 0xFF) << 8) | (plain[1] & 0xFF);
                    if (nameLen > 255) throw new IOException("B2V3 header damaged");
                    headerBuf = new byte[2 + nameLen + 8];
                }
                int take = Math.min(headerBuf.length - headerPos, plain.length);
                System.arraycopy(plain, 0, headerBuf, headerPos, take);
                headerPos += take; pos = take;
                if (headerPos == headerBuf.length) {
                    ByteBuffer h = ByteBuffer.wrap(headerBuf);
                    int nameLen = h.getShort() & 0xFFFF;
                    byte[] nameBytes = new byte[nameLen];
                    h.get(nameBytes);
                    result.name = new String(nameBytes, StandardCharsets.UTF_8);
                    result.totalBytes = h.getLong();
                    haveHeader = true;
                }
            }
            if (haveHeader && pos < plain.length) {
                int take = (int) Math.min(plain.length - pos, result.totalBytes - written);
                out.write(plain, pos, take);
                written += take;
                if (p != null) p.update(written, result.totalBytes);
            }
        }
        if (!haveHeader || written != result.totalBytes)
            throw new IOException("size mismatch after decrypting");
        out.flush();
        return result;
    }

    // ----------------------------------------------------------------- util

    static byte[] secretMaterial(Key key) {
        boolean hasPw = key.password != null && !key.password.isEmpty();
        boolean hasFile = key.keyFileSha256 != null;
        if (!hasPw && !hasFile) throw new IllegalArgumentException("provide a password, a key file, or both");
        if (hasFile && key.keyFileSha256.length != 32) throw new IllegalArgumentException("keyFileSha256 must be a SHA-256 digest");
        byte[] pw = hasPw ? key.password.getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] out = new byte[pw.length + (hasFile ? 32 : 0)];
        System.arraycopy(pw, 0, out, 0, pw.length);
        if (hasFile) System.arraycopy(key.keyFileSha256, 0, out, pw.length, 32);
        return out;
    }

    /** Streams a file through SHA-256 without holding it all in memory; for use as a key file. */
    public static byte[] hashKeyFile(InputStream in) throws IOException, GeneralSecurityException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        return md.digest();
    }

    static SecretKeySpec deriveKey(byte[] secretMaterial, byte[] salt, int iterations) throws GeneralSecurityException {
        char[] chars = new String(secretMaterial, StandardCharsets.ISO_8859_1).toCharArray();
        try {
            PBEKeySpec spec = new PBEKeySpec(chars, salt, iterations, 256);
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] keyBytes = f.generateSecret(spec).getEncoded();
            return new SecretKeySpec(keyBytes, "AES");
        } finally {
            Arrays.fill(chars, '\0');
            Arrays.fill(secretMaterial, (byte) 0);
        }
    }

    static byte[] iv(byte[] nonceBase, long idx) {
        ByteBuffer b = ByteBuffer.allocate(GCM_IV_LEN);
        b.put(nonceBase).putInt((int) idx);
        return b.array();
    }

    static byte[] aad(int idx, int total) {
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 4);
        b.put(MAGIC).putInt(idx).putInt(total);
        return b.array();
    }

    /** Desktop CLI: encode-enc / decode-enc, password from B2V_PASSWORD env var, optional key file arg 4. */
    public static void main(String[] a) throws Exception {
        String pw = System.getenv("B2V_PASSWORD");
        byte[] keyFileHash = null;
        if (a.length > 3) {
            try (InputStream kin = new java.io.BufferedInputStream(new java.io.FileInputStream(a[3]))) {
                keyFileHash = hashKeyFile(kin);
            }
        }
        Key key = new Key(pw, keyFileHash);
        if (a.length >= 3 && a[0].equals("encode-enc")) {
            java.io.File f = new java.io.File(a[1]);
            try (InputStream in = new java.io.BufferedInputStream(new java.io.FileInputStream(f), 1 << 20);
                 OutputStream out = new java.io.BufferedOutputStream(new java.io.FileOutputStream(a[2]), 1 << 20)) {
                encode(in, f.length(), f.getName(), key, out, null);
            }
        } else if (a.length >= 3 && a[0].equals("decode-enc")) {
            try (InputStream in = new java.io.BufferedInputStream(new java.io.FileInputStream(a[1]), 1 << 20)) {
                Envelope env = peekEnvelope(in);
                System.out.println("B2V3: " + env.dataFrames + " data frames, " + env.iterations + " PBKDF2 iterations");
            }
            java.io.File placeholder = new java.io.File(a[2], "decoded_placeholder");
            Result r;
            try (InputStream in = new java.io.BufferedInputStream(new java.io.FileInputStream(a[1]), 1 << 20);
                 OutputStream out = new java.io.BufferedOutputStream(new java.io.FileOutputStream(placeholder), 1 << 20)) {
                r = decode(in, key, out, null);
            } catch (Exception e) {
                placeholder.delete();
                throw e;
            }
            java.io.File outF = new java.io.File(a[2], r.name.isEmpty() ? "decoded" : r.name);
            placeholder.renameTo(outF);
            System.out.println("decoded -> " + outF + " (" + r.totalBytes + " bytes)");
        } else {
            System.err.println("usage: B2V_PASSWORD=pw B2VSecure encode-enc IN OUT.mkv [KEYFILE] | decode-enc IN.mkv OUTDIR [KEYFILE]");
            System.exit(2);
        }
    }
}
