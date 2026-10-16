package app.bits2video;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * bits2video format B2V2: 1 px cells, 8 bits per channel, lossless.
 *
 * Video:  1920x1080, uncompressed 24-bit RGB in Matroska (V_MS/VFW/FOURCC, BI_RGB,
 *         BGR byte order, rows stored top-down: biHeight = -1080), 1 frame per second.
 * Grid:   row 0 = calibration (cols 0-255 gray 0..255, 256-511 red, 512-767 green,
 *         768-1023 blue, rest black). Rows 1..1079 = data: each pixel's R,G,B values
 *         are 3 consecutive data bytes, left to right, top to bottom.
 * Data:   per frame, a header then payload, zero padded to CAP bytes. Big endian:
 *         'B2V2' | idx u32 | total u32 | payload_len u32 | total_bytes u64 |
 *         payload_crc32 u32 | name_len u16 | name (UTF-8) | header_crc32 u32
 */
public final class B2V {
    public static final int W = 1920, H = 1080;
    public static final int FRAME_BYTES = W * H * 3;
    public static final int ROW_BYTES = W * 3;
    public static final int CAP = W * (H - 1) * 3;           // data bytes per frame
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

        writeMkvHeader(out, n);
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
            writeCluster(out, i, frame);
            done += len;
            if (p != null) p.update(done, size);
        }
        if (in.read() != -1) throw new IOException("input is longer than its reported size");
        out.flush();
    }

    static byte[] calibrationRow() {
        byte[] row = new byte[ROW_BYTES];                       // RGB, top-down
        for (int i = 0; i < 256; i++) {
            byte v = (byte) i;
            row[3 * i] = v; row[3 * i + 1] = v; row[3 * i + 2] = v;
            row[3 * (256 + i)] = v;
            row[3 * (512 + i) + 1] = v;
            row[3 * (768 + i) + 2] = v;
        }
        return row;
    }

    /** RGB grid (calibration + data) -> BGR frame. */
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

    /** BGR frame -> data bytes (rows 1..1079, RGB). */
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

    // ------------------------------------------------------------- matroska

    static final int ID_EBML = 0x1A45DFA3, ID_SEGMENT = 0x18538067, ID_INFO = 0x1549A966,
            ID_TRACKS = 0x1654AE6B, ID_CLUSTER = 0x1F43B675, ID_SIMPLEBLOCK = 0xA3;
    static final int CLUSTER_CONTENT = 17 + 9 + 4 + FRAME_BYTES;  // Timestamp el + SimpleBlock el
    static final long CLUSTER_TOTAL = 4 + 8 + CLUSTER_CONTENT;

    static void writeMkvHeader(OutputStream out, long frames) throws IOException {
        ByteArrayOutputStream ebml = new ByteArrayOutputStream();
        uint(ebml, 0x4286, 1); uint(ebml, 0x42F7, 1); uint(ebml, 0x42F2, 4); uint(ebml, 0x42F3, 8);
        str(ebml, 0x4282, "matroska"); uint(ebml, 0x4287, 4); uint(ebml, 0x4285, 2);

        ByteArrayOutputStream info = new ByteArrayOutputStream();
        uint(info, 0x2AD7B1, 1_000_000);                          // timestamps in ms
        str(info, 0x4D80, "bits2video"); str(info, 0x5741, "bits2video");
        el(info, 0x4489, ByteBuffer.allocate(8).putDouble(frames * 1000.0).array());

        ByteBuffer bih = ByteBuffer.allocate(40).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        bih.putInt(40).putInt(W).putInt(-H).putShort((short) 1).putShort((short) 24).putInt(0)
           .putInt(FRAME_BYTES).putInt(0).putInt(0).putInt(0).putInt(0);
        ByteArrayOutputStream video = new ByteArrayOutputStream();
        uint(video, 0xB0, W); uint(video, 0xBA, H);
        ByteArrayOutputStream track = new ByteArrayOutputStream();
        uint(track, 0xD7, 1); uint(track, 0x73C5, 1); uint(track, 0x83, 1); uint(track, 0x9C, 0);
        str(track, 0x86, "V_MS/VFW/FOURCC"); el(track, 0x63A2, bih.array());
        uint(track, 0x23E383, 1_000_000_000L); el(track, 0xE0, video.toByteArray());
        ByteArrayOutputStream tracks = new ByteArrayOutputStream();
        el(tracks, 0xAE, track.toByteArray());

        el(out, ID_EBML, ebml.toByteArray());
        ByteArrayOutputStream infoEl = new ByteArrayOutputStream();
        el(infoEl, ID_INFO, info.toByteArray());
        el(infoEl, ID_TRACKS, tracks.toByteArray());
        id(out, ID_SEGMENT);
        size8(out, infoEl.size() + frames * CLUSTER_TOTAL);
        infoEl.writeTo(out);
    }

    static void writeCluster(OutputStream out, long i, byte[] frame) throws IOException {
        id(out, ID_CLUSTER); size8(out, CLUSTER_CONTENT);
        out.write(0xE7); size8(out, 8);
        out.write(ByteBuffer.allocate(8).putLong(i * 1000).array());
        out.write(ID_SIMPLEBLOCK); size8(out, 4 + FRAME_BYTES);
        out.write(new byte[]{(byte) 0x81, 0, 0, (byte) 0x80});   // track 1, rel. time 0, keyframe
        out.write(frame);
    }

    static void id(OutputStream o, int id) throws IOException {
        int n = id >= 0x1000000 ? 4 : id >= 0x10000 ? 3 : id >= 0x100 ? 2 : 1;
        for (int k = n - 1; k >= 0; k--) o.write(id >>> (8 * k));
    }

    static void size8(OutputStream o, long v) throws IOException {
        o.write(0x01);
        for (int k = 6; k >= 0; k--) o.write((int) (v >>> (8 * k)));
    }

    static void el(OutputStream o, int id, byte[] content) throws IOException {
        id(o, id); size8(o, content.length); o.write(content);
    }

    static void uint(OutputStream o, int id, long v) throws IOException {
        el(o, id, ByteBuffer.allocate(8).putLong(v).array());
    }

    static void str(OutputStream o, int id, String s) throws IOException {
        el(o, id, s.getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- decode

    /** Reads Matroska SimpleBlock frames one at a time from a stream. */
    public static final class FrameReader {
        final InputStream in;
        public FrameReader(InputStream in) { this.in = in; }

        /** Fills frame with the next video frame; false at end of stream. */
        public boolean next(byte[] frame) throws IOException {
            while (true) {
                int first = in.read();
                if (first < 0) return false;
                long id = readVint(first, true);
                long size = readSize();
                if (id == ID_SEGMENT || id == ID_CLUSTER) continue;  // descend into masters
                if (id == ID_SIMPLEBLOCK) {
                    if (size < 0) throw new IOException("bad block size");
                    int b = in.read();
                    int trackLen = Integer.numberOfLeadingZeros(b & 0xFF) - 23;
                    skipFully(in, trackLen - 1);
                    int t1 = in.read(), t2 = in.read(), flags = in.read();
                    if (b < 0 || flags < 0 || t1 < 0 || t2 < 0) throw new EOFException();
                    if ((flags & 0x06) != 0) throw new IOException("laced blocks not supported");
                    long dataLen = size - trackLen - 3;
                    if (dataLen != FRAME_BYTES)
                        throw new IOException("not a 1920x1080 uncompressed B2V2 video");
                    readFully(in, frame, 0, FRAME_BYTES);
                    return true;
                }
                if (size < 0) throw new IOException("unsupported unknown-size element");
                skipFully(in, size);
            }
        }

        long readVint(int first, boolean keepMarker) throws IOException {
            int len = Integer.numberOfLeadingZeros(first & 0xFF) - 23;
            if (len < 1 || len > 8) throw new IOException("not a Matroska file");
            long v = keepMarker ? first : first & (0xFF >> len);
            for (int k = 1; k < len; k++) {
                int b = in.read();
                if (b < 0) throw new EOFException();
                v = (v << 8) | b;
            }
            return v;
        }

        long readSize() throws IOException {
            int first = in.read();
            if (first < 0) throw new EOFException();
            int len = Integer.numberOfLeadingZeros(first & 0xFF) - 23;
            long v = readVint(first, false);
            return v == (1L << (7 * len)) - 1 ? -1 : v;               // all ones = unknown size
        }
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

    /** Reads only the first frame and returns its first 4 bytes, to tell B2V2 from B2V3 before committing to either. */
    public static byte[] peekMagic(InputStream in) throws IOException {
        byte[] frame = new byte[FRAME_BYTES], data = new byte[CAP];
        if (!new FrameReader(in).next(frame)) throw new IOException("no video frames found");
        fromFrame(frame, data);
        return java.util.Arrays.copyOf(data, 4);
    }

    /** Reads only the first frame's header (for the original name and size). */
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
            if (h.idx < expect) continue;                              // duplicate frame
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
        while (end > 0 && (b[end] & 0xC0) == 0x80) end--;              // don't split a character
        return java.util.Arrays.copyOf(b, end);
    }

    /** Desktop CLI: java -cp b2v.jar app.bits2video.B2V encode IN OUT.mkv | decode IN.mkv OUTDIR */
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
                name = new java.io.File(peek(in).name).getName();       // strip any path parts
            }
            java.io.File outF = new java.io.File(a[2], name.isEmpty() ? "decoded" : name);
            try (InputStream in = new java.io.BufferedInputStream(new java.io.FileInputStream(a[1]), 1 << 20);
                 OutputStream out = new java.io.BufferedOutputStream(new java.io.FileOutputStream(outF), 1 << 20)) {
                decode(in, out, null);
            }
            System.out.println("decoded -> " + outF);
        } else {
            System.err.println("usage: B2V encode IN OUT.mkv | B2V decode IN.mkv OUTDIR");
            System.exit(2);
        }
    }
}
