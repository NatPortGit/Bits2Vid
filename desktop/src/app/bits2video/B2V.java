package app.bits2video;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * bits2video format B2V2, AVI container (instead of the original app's Matroska
 * container): same 1 px cells, 8 bits per channel, lossless pixel data -- same size,
 * same density -- just packaged so ordinary players (VLC included) recognise it.
 *
 * AVI was chosen, rather than guessed at from memory, by generating a real raw-video
 * AVI with actual ffmpeg in the build environment and reading its exact byte layout;
 * this format matches that layout field for field (same BGR byte order and top-down
 * -biHeight convention the original Matroska writer already used, so toFrame/fromFrame
 * are unchanged from the original app -- only the container framing differs).
 *
 * Video:  1920x1080, uncompressed 24-bit BGR (BITMAPINFOHEADER, biCompression=BI_RGB,
 *         biHeight=-1080 i.e. top-down) in an AVI 1.0 container, 1 frame/second, with
 *         an idx1 index for seeking.
 * Grid/Data: identical to the original app's B2V2 -- see calibrationRow/toFrame/
 *         parseHeader below; this file only replaces the container (writeMkvHeader/
 *         writeCluster/FrameReader) with AVI equivalents.
 */
public final class B2V {
    public static final int W = 1920, H = 1080;
    public static final int FRAME_BYTES = W * H * 3;
    public static final int ROW_BYTES = W * 3;
    public static final int CAP = W * (H - 1) * 3;
    static final int FIXED_HDR = 30;
    static final byte[] MAGIC = {'B', '2', 'V', '2'};

    public interface Progress { void update(long done, long total); }

    public static final class Header {
        public int idx, total, payloadLen;
        public long totalBytes, payloadCrc;
        public String name;
        public int size;
    }

    // ---------------------------------------------------------------- encode

    public static void encode(InputStream in, long size, String name, OutputStream out, Progress p)
            throws IOException {
        byte[] nameBytes = truncUtf8(name == null ? "file" : name, 255);
        int hdrSize = FIXED_HDR + nameBytes.length + 4;
        int pay = CAP - hdrSize;
        long n = size == 0 ? 1 : (size + pay - 1) / pay;
        if (n > 0xFFFFFFFFL) throw new IOException("file too large");
        if (n * (24L + FRAME_BYTES) + 224 > 0xFFFFFFFFL)
            throw new IOException("file too large for the classic (non-OpenDML) AVI format used here");

        writeAviHeader(out, n);
        byte[] buf = new byte[CAP];
        byte[] frame = new byte[FRAME_BYTES];
        byte[] calib = calibrationRow();
        CRC32 crc = new CRC32();
        long done = 0;
        for (long i = 0; i < n; i++) {
            int len = (int) Math.min(pay, size - done);
            readFully(in, buf, hdrSize, len);
            java.util.Arrays.fill(buf, hdrSize + len, CAP, (byte) 0);
            crc.reset();
            crc.update(buf, hdrSize, len);
            ByteBuffer h = ByteBuffer.wrap(buf);
            h.put(MAGIC).putInt((int) i).putInt((int) n).putInt(len).putLong(size)
             .putInt((int) crc.getValue()).putShort((short) nameBytes.length).put(nameBytes);
            crc.reset();
            crc.update(buf, 0, hdrSize - 4);
            h.putInt((int) crc.getValue());
            toFrame(buf, calib, frame);
            writeAviFrame(out, frame);
            done += len;
            if (p != null) p.update(done, size);
        }
        writeAviIndex(out, n);
        if (in.read() != -1) throw new IOException("input is longer than its reported size");
        out.flush();
    }

    static byte[] calibrationRow() {
        byte[] row = new byte[ROW_BYTES];
        for (int i = 0; i < 256; i++) {
            byte v = (byte) i;
            row[3 * i] = v; row[3 * i + 1] = v; row[3 * i + 2] = v;
            row[3 * (256 + i)] = v;
            row[3 * (512 + i) + 1] = v;
            row[3 * (768 + i) + 2] = v;
        }
        return row;
    }

    /** RGB grid (calibration + data) -> BGR frame. Unchanged from the original app. */
    static void toFrame(byte[] data, byte[] calib, byte[] frame) {
        for (int r = 0; r < H; r++) {
            byte[] src = r == 0 ? calib : data;
            int s = r == 0 ? 0 : (r - 1) * ROW_BYTES;
            int d = r * ROW_BYTES;
            for (int x = 0; x < ROW_BYTES; x += 3) {
                frame[d + x] = src[s + x + 2];
                frame[d + x + 1] = src[s + x + 1];
                frame[d + x + 2] = src[s + x];
            }
        }
    }

    /** BGR frame -> data bytes (rows 1..1079, RGB). Unchanged from the original app. */
    static void fromFrame(byte[] frame, byte[] data) {
        for (int r = 1; r < H; r++) {
            int s = r * ROW_BYTES;
            int d = (r - 1) * ROW_BYTES;
            for (int x = 0; x < ROW_BYTES; x += 3) {
                data[d + x] = frame[s + x + 2];
                data[d + x + 1] = frame[s + x + 1];
                data[d + x + 2] = frame[s + x];
            }
        }
    }

    // ------------------------------------------------------------------ avi

    static final byte[] T_RIFF = tag("RIFF"), T_AVI = tag("AVI "), T_LIST = tag("LIST"),
            T_hdrl = tag("hdrl"), T_avih = tag("avih"), T_strl = tag("strl"), T_strh = tag("strh"),
            T_vids = tag("vids"), T_strf = tag("strf"), T_movi = tag("movi"), T_00dc = tag("00dc"),
            T_idx1 = tag("idx1");

    static final int AVIH_SIZE = 56, STRH_SIZE = 56, STRF_SIZE = 40;
    static final int STRL_CONTENT = 4 + (8 + STRH_SIZE) + (8 + STRF_SIZE);      // 'strl'+strh+strf
    static final int HDRL_CONTENT = (8 + AVIH_SIZE) + (8 + STRL_CONTENT);       // avih + LIST strl
    static final long MOVI_CONTENT(long n) { return 4 + n * (8L + FRAME_BYTES); }

    static byte[] tag(String s) { return s.getBytes(StandardCharsets.US_ASCII); }

    static void writeAviHeader(OutputStream out, long n) throws IOException {
        long riffSize = 4 /* 'AVI ' */
                + (8 + 4 + HDRL_CONTENT)                 // LIST 'hdrl'
                + (8 + MOVI_CONTENT(n))                  // LIST 'movi'
                + (8 + n * 16);                          // 'idx1'
        out.write(T_RIFF); le32(out, riffSize); out.write(T_AVI);

        out.write(T_LIST); le32(out, 4 + HDRL_CONTENT); out.write(T_hdrl);
        out.write(T_avih); le32(out, AVIH_SIZE);
        le32(out, 1_000_000);              // dwMicroSecPerFrame (1 fps)
        le32(out, FRAME_BYTES);            // dwMaxBytesPerSec
        le32(out, 0);                      // dwPaddingGranularity
        le32(out, 0x910);                  // dwFlags: HASINDEX|ISINTERLEAVED|TRUSTCKTYPE
        le32(out, n);                      // dwTotalFrames
        le32(out, 0);                      // dwInitialFrames
        le32(out, 1);                      // dwStreams
        le32(out, FRAME_BYTES);            // dwSuggestedBufferSize
        le32(out, W); le32(out, H);        // dwWidth, dwHeight
        le32(out, 0); le32(out, 0); le32(out, 0); le32(out, 0);   // dwReserved[4]

        out.write(T_LIST); le32(out, STRL_CONTENT); out.write(T_strl);
        out.write(T_strh); le32(out, STRH_SIZE);
        out.write(T_vids); le32(out, 0);   // fccHandler = 0 (uncompressed)
        le32(out, 0);                      // dwFlags
        le16(out, 0); le16(out, 0);        // wPriority, wLanguage
        le32(out, 0);                      // dwInitialFrames
        le32(out, 1); le32(out, 1);        // dwScale, dwRate (1/1 = 1 fps)
        le32(out, 0);                      // dwStart
        le32(out, n);                      // dwLength
        le32(out, FRAME_BYTES);            // dwSuggestedBufferSize
        le32(out, 0xFFFFFFFF);             // dwQuality (unspecified)
        le32(out, 0);                      // dwSampleSize
        le16(out, 0); le16(out, 0); le16(out, W); le16(out, H);   // rcFrame

        out.write(T_strf); le32(out, STRF_SIZE);
        le32(out, STRF_SIZE);              // biSize
        le32(out, W); le32(out, -H);       // biWidth, biHeight (negative = top-down)
        le16(out, 1); le16(out, 24);       // biPlanes, biBitCount
        le32(out, 0);                      // biCompression = BI_RGB
        le32(out, FRAME_BYTES);            // biSizeImage
        le32(out, 0); le32(out, 0);        // biX/YPelsPerMeter
        le32(out, 0); le32(out, 0);        // biClrUsed, biClrImportant

        out.write(T_LIST); le32(out, MOVI_CONTENT(n)); out.write(T_movi);
    }

    static void writeAviFrame(OutputStream out, byte[] frame) throws IOException {
        out.write(T_00dc); le32(out, FRAME_BYTES); out.write(frame);   // FRAME_BYTES is always even, no pad needed
    }

    static void writeAviIndex(OutputStream out, long n) throws IOException {
        out.write(T_idx1); le32(out, n * 16);
        long offset = 4;                    // relative to the first byte after 'movi's own fourCC
        for (long i = 0; i < n; i++) {
            out.write(T_00dc);
            le32(out, 0x10);                // AVIIF_KEYFRAME
            le32(out, offset);
            le32(out, FRAME_BYTES);
            offset += 8 + FRAME_BYTES;
        }
    }

    static void le16(OutputStream o, int v) throws IOException { o.write(v); o.write(v >>> 8); }
    static void le32(OutputStream o, long v) throws IOException {
        o.write((int) v); o.write((int) (v >>> 8)); o.write((int) (v >>> 16)); o.write((int) (v >>> 24));
    }

    // ---------------------------------------------------------------- decode

    /** Reads raw '00dc'/'00db' video frames from an AVI's 'movi' list, tolerating
     * other chunks (JUNK, odml, idx1, LIST hdrl, etc.) anywhere else in the file. */
    public static final class FrameReader {
        final InputStream in;
        boolean inMovi = false;
        public FrameReader(InputStream in) { this.in = in; }

        public boolean next(byte[] frame) throws IOException {
            while (true) {
                byte[] tag = new byte[4];
                int r = readSome(in, tag, 0, 4);
                if (r < 4) return false;              // clean EOF between chunks
                long size = readLe32(in);
                if (size < 0) throw new IOException("bad AVI chunk size");

                if (eq(tag, T_RIFF) || eq(tag, T_LIST)) {
                    byte[] listType = new byte[4];
                    readFully(in, listType, 0, 4);
                    inMovi = inMovi || eq(listType, T_movi);
                    continue;                          // descend: next loop reads what's inside
                }
                if (inMovi && (eq(tag, T_00dc) || eq(tag, tag("00db")))) {
                    if (size != FRAME_BYTES)
                        throw new IOException("not a 1920x1080 uncompressed B2V2 AVI video");
                    readFully(in, frame, 0, FRAME_BYTES);
                    if ((size & 1) != 0) in.read();     // RIFF chunks are word-aligned
                    return true;
                }
                skipFully(in, size);
                if ((size & 1) != 0) in.read();
            }
        }
    }

    static boolean eq(byte[] a, byte[] b) { return java.util.Arrays.equals(a, b); }

    static int readSome(InputStream in, byte[] b, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int r = in.read(b, off + total, len - total);
            if (r < 0) return total;
            total += r;
        }
        return total;
    }

    static long readLe32(InputStream in) throws IOException {
        int b0 = in.read(), b1 = in.read(), b2 = in.read(), b3 = in.read();
        if ((b0 | b1 | b2 | b3) < 0) throw new EOFException();
        return (b0 & 0xFFL) | ((b1 & 0xFFL) << 8) | ((b2 & 0xFFL) << 16) | ((b3 & 0xFFL) << 24);
    }

    /** Parses and validates the header of a decoded data frame; null if invalid. */
    public static Header parseHeader(byte[] data) {
        ByteBuffer h = ByteBuffer.wrap(data);
        for (byte m : MAGIC) if (h.get() != m) return null;
        Header r = new Header();
        r.idx = h.getInt(); r.total = h.getInt(); r.payloadLen = h.getInt();
        r.totalBytes = h.getLong(); r.payloadCrc = h.getInt() & 0xFFFFFFFFL;
        int nl = h.getShort() & 0xFFFF;
        if (nl > 255) return null;
        r.size = FIXED_HDR + nl + 4;
        CRC32 crc = new CRC32();
        crc.update(data, 0, r.size - 4);
        if ((int) crc.getValue() != ByteBuffer.wrap(data, r.size - 4, 4).getInt()) return null;
        if (r.payloadLen < 0 || r.payloadLen > CAP - r.size) return null;
        r.name = new String(data, FIXED_HDR, nl, StandardCharsets.UTF_8);
        return r;
    }

    public static byte[] peekMagic(InputStream in) throws IOException {
        byte[] frame = new byte[FRAME_BYTES], data = new byte[CAP];
        if (!new FrameReader(in).next(frame)) throw new IOException("no video frames found");
        fromFrame(frame, data);
        return java.util.Arrays.copyOf(data, 4);
    }

    public static Header peek(InputStream in) throws IOException {
        byte[] frame = new byte[FRAME_BYTES], data = new byte[CAP];
        if (!new FrameReader(in).next(frame)) throw new IOException("no video frames found");
        fromFrame(frame, data);
        Header h = parseHeader(data);
        if (h == null) throw new IOException("not a B2V2 encoded video (bad header)");
        return h;
    }

    public static Header decode(InputStream in, OutputStream out, Progress p) throws IOException {
        FrameReader fr = new FrameReader(in);
        byte[] frame = new byte[FRAME_BYTES], data = new byte[CAP];
        CRC32 crc = new CRC32();
        Header first = null;
        long written = 0;
        int expect = 0;
        while (fr.next(frame)) {
            fromFrame(frame, data);
            Header h = parseHeader(data);
            if (h == null) throw new IOException("frame " + expect + ": header damaged");
            if (first == null) first = h;
            if (h.idx < expect) continue;
            if (h.idx != expect) throw new IOException("frame " + expect + " missing");
            crc.reset();
            crc.update(data, h.size, h.payloadLen);
            if (crc.getValue() != h.payloadCrc) throw new IOException("frame " + expect + ": data damaged");
            out.write(data, h.size, h.payloadLen);
            written += h.payloadLen;
            expect++;
            if (p != null) p.update(written, h.totalBytes);
            if (expect == h.total) break;
        }
        if (first == null) throw new IOException("no B2V2 frames found");
        if (expect != first.total) throw new IOException("video ends early: " + expect + " of " + first.total + " frames");
        if (written != first.totalBytes) throw new IOException("size mismatch");
        out.flush();
        return first;
    }

    // ---------------------------------------------------------------- utils

    static void readFully(InputStream in, byte[] b, int off, int len) throws IOException {
        while (len > 0) {
            int r = in.read(b, off, len);
            if (r < 0) throw new EOFException("unexpected end of input");
            off += r; len -= r;
        }
    }

    static void skipFully(InputStream in, long n) throws IOException {
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) {
                if (in.read() < 0) throw new EOFException();
                s = 1;
            }
            n -= s;
        }
    }

    static byte[] truncUtf8(String s, int max) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length <= max) return b;
        int end = max;
        while (end > 0 && (b[end] & 0xC0) == 0x80) end--;
        return java.util.Arrays.copyOf(b, end);
    }

    /** Desktop CLI: java -cp b2v.jar app.bits2video.playable.B2V encode IN OUT.avi | decode IN.avi OUTDIR */
    public static void main(String[] a) throws IOException {
        if (a.length == 3 && a[0].equals("encode")) {
            java.io.File f = new java.io.File(a[1]);
            try (InputStream in = new java.io.BufferedInputStream(new java.io.FileInputStream(f), 1 << 20);
                 OutputStream out = new java.io.BufferedOutputStream(new java.io.FileOutputStream(a[2]), 1 << 20)) {
                encode(in, f.length(), f.getName(), out, null);
            }
        } else if (a.length == 3 && a[0].equals("decode")) {
            String name;
            try (InputStream in = new java.io.BufferedInputStream(new java.io.FileInputStream(a[1]), 1 << 20)) {
                name = new java.io.File(peek(in).name).getName();
            }
            java.io.File outF = new java.io.File(a[2], name.isEmpty() ? "decoded" : name);
            try (InputStream in = new java.io.BufferedInputStream(new java.io.FileInputStream(a[1]), 1 << 20);
                 OutputStream out = new java.io.BufferedOutputStream(new java.io.FileOutputStream(outF), 1 << 20)) {
                decode(in, out, null);
            }
            System.out.println("decoded -> " + outF);
        } else {
            System.err.println("usage: B2V encode IN OUT.avi | B2V decode IN.avi OUTDIR");
            System.exit(2);
        }
    }
}
