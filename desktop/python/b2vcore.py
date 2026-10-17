"""
b2vcore: pure-Python implementation of the bits2video B2V2/B2V3 format --
byte-for-byte compatible with the Java desktop/Android tools (same AVI
container, same pixel layout, same encryption), so a video made by any one
of them decodes correctly in any other.

No ffmpeg dependency: this reads/writes the AVI container directly (it's a
plain RIFF structure), matching exactly what B2V.java's writeAviHeader/
writeAviFrame/writeAviIndex produce -- verified by generating a reference
file with real ffmpeg and diffing the stream headers byte-for-byte, and
separately by a live round trip against the Java jar (encrypted and plain).

Needs the `cryptography` package for B2V3 (encrypted) files:
    pip install cryptography
Plain (unencrypted) B2V2 files need nothing beyond the standard library.

Format summary (see B2V.java / B2VSecure.java in this project's Java tools
for the authoritative spec comments):
  - 1920x1080 frames, 24-bit BGR, top-down, 1 fps, AVI container.
  - Row 0 of the pixel grid is an (unused by the decoder) calibration strip;
    rows 1..1079 hold 3 bytes of file data per pixel.
  - B2V2 (plain): per frame, a small header (magic/idx/total/payloadLen/
    totalBytes/payloadCrc/name) then payload, CRC32-checked.
  - B2V3 (encrypted): frame 0 is an unencrypted envelope (magic/salt/
    nonceBase/iterations/dataFrames); frames 1.. are each one AES-256-GCM
    chunk (idx/total in the clear, authenticated; ciphertext+tag). Key via
    PBKDF2-HMAC-SHA256 (600,000 iterations) from a password and/or a key
    file's SHA-256 hash.

    IMPORTANT, verified empirically rather than assumed: Java's
    PBEKeySpec/SecretKeyFactory PBKDF2WithHmacSHA256 does NOT use the
    password bytes directly. It takes a char[], and the actual (tested)
    behaviour is: the raw secret bytes are decoded as ISO-8859-1 to a
    string, which is then internally re-encoded as UTF-8 before PBKDF2 is
    computed. That re-encoding changes any byte >= 0x80 into two UTF-8
    bytes. This module reproduces that exact transformation (java_kdf_bytes
    below) so passwords and key files behave identically across tools --
    skipping it would make cross-tool files silently fail to decrypt
    whenever the secret material contains a byte >= 0x80, which a key
    file's SHA-256 hash always will, roughly half the time per byte.
"""
import struct
import hashlib
import os
import io

W, H = 1920, 1080
FRAME_BYTES = W * H * 3
ROW_BYTES = W * 3
CAP = W * (H - 1) * 3
FIXED_HDR = 30
MAGIC2 = b'B2V2'
MAGIC3 = b'B2V3'

SALT_LEN, NONCE_BASE_LEN, GCM_TAG_LEN, GCM_IV_LEN = 16, 8, 16, 12
ITERATIONS = 600_000
FRAME_HDR = 8
CIPHER_LEN = CAP - FRAME_HDR - GCM_TAG_LEN
MAX_NAME = 255


class B2VError(Exception):
    pass


# ------------------------------------------------------------- pixel grid

def calibration_row():
    row = bytearray(ROW_BYTES)
    for i in range(256):
        row[3 * i] = row[3 * i + 1] = row[3 * i + 2] = i
        row[3 * (256 + i)] = i
        row[3 * (512 + i) + 1] = i
        row[3 * (768 + i) + 2] = i
    return bytes(row)


_CALIB = calibration_row()


def to_frame(data):
    """RGB grid (calibration row + CAP data bytes) -> BGR frame bytes, matching
    B2V.toFrame exactly (per-pixel R/B channel swap, no row reordering)."""
    frame = bytearray(FRAME_BYTES)
    calib = _CALIB
    frame[0:ROW_BYTES] = _swap_rb(calib)
    frame[ROW_BYTES:FRAME_BYTES] = _swap_rb(data)
    return bytes(frame)


def from_frame(frame):
    """BGR frame bytes -> CAP data bytes (rows 1..H-1), matching B2V.fromFrame."""
    return _swap_rb(frame[ROW_BYTES:FRAME_BYTES])


def _swap_rb(buf):
    a = bytearray(buf)
    a[0::3], a[2::3] = bytes(a[2::3]), bytes(a[0::3])
    return bytes(a)


# ------------------------------------------------------------------- utils

def _trunc_utf8(s, max_bytes):
    b = s.encode('utf-8')
    if len(b) <= max_bytes:
        return b
    end = max_bytes
    while end > 0 and (b[end] & 0xC0) == 0x80:
        end -= 1
    return b[:end]


def crc32(b):
    import zlib
    return zlib.crc32(b) & 0xFFFFFFFF


# -------------------------------------------------------------------- AVI

_T_RIFF, _T_AVI, _T_LIST = b'RIFF', b'AVI ', b'LIST'
_T_hdrl, _T_avih, _T_strl, _T_strh = b'hdrl', b'avih', b'strl', b'strh'
_T_vids, _T_strf, _T_movi, _T_00dc, _T_00db, _T_idx1 = b'vids', b'strf', b'movi', b'00dc', b'00db', b'idx1'

AVIH_SIZE, STRH_SIZE, STRF_SIZE = 56, 56, 40
STRL_CONTENT = 4 + (8 + STRH_SIZE) + (8 + STRF_SIZE)
HDRL_CONTENT = (8 + AVIH_SIZE) + (8 + STRL_CONTENT)


def _movi_content(n):
    return 4 + n * (8 + FRAME_BYTES)


def write_avi_header(out, n):
    riff_size = 4 + (8 + 4 + HDRL_CONTENT) + (8 + _movi_content(n)) + (8 + n * 16)
    out.write(_T_RIFF); out.write(struct.pack('<I', riff_size)); out.write(_T_AVI)

    out.write(_T_LIST); out.write(struct.pack('<I', 4 + HDRL_CONTENT)); out.write(_T_hdrl)
    out.write(_T_avih); out.write(struct.pack('<I', AVIH_SIZE))
    out.write(struct.pack('<IIIIIIIIIIIII',
        1_000_000, FRAME_BYTES, 0, 0x910, n, 0, 1, FRAME_BYTES, W, H, 0, 0, 0))
    out.write(struct.pack('<I', 0))   # dwReserved[4] 4th word (13 above + this = 14 u32 = 56 bytes)

    out.write(_T_LIST); out.write(struct.pack('<I', STRL_CONTENT)); out.write(_T_strl)
    out.write(_T_strh); out.write(struct.pack('<I', STRH_SIZE))
    out.write(_T_vids); out.write(struct.pack('<I', 0))
    out.write(struct.pack('<IHHI', 0, 0, 0, 0))             # dwFlags, wPriority, wLanguage, dwInitialFrames
    out.write(struct.pack('<IIII', 1, 1, 0, n))              # dwScale, dwRate, dwStart, dwLength
    out.write(struct.pack('<III', FRAME_BYTES, 0xFFFFFFFF, 0))  # suggestedBufferSize, quality, sampleSize
    out.write(struct.pack('<4h', 0, 0, W, H))                 # rcFrame

    out.write(_T_strf); out.write(struct.pack('<I', STRF_SIZE))
    out.write(struct.pack('<iiiHHIIiiII', STRF_SIZE, W, -H, 1, 24, 0, FRAME_BYTES, 0, 0, 0, 0))

    out.write(_T_LIST); out.write(struct.pack('<I', _movi_content(n))); out.write(_T_movi)


def write_avi_frame(out, frame):
    out.write(_T_00dc); out.write(struct.pack('<I', FRAME_BYTES)); out.write(frame)


def write_avi_index(out, n):
    out.write(_T_idx1); out.write(struct.pack('<I', n * 16))
    offset = 4
    for _ in range(n):
        out.write(_T_00dc); out.write(struct.pack('<III', 0x10, offset, FRAME_BYTES))
        offset += 8 + FRAME_BYTES


class FrameReader:
    """Reads raw '00dc'/'00db' video frames from an AVI's 'movi' list, tolerant
    of other chunks (JUNK, odml, idx1, etc.) anywhere else in the file."""

    def __init__(self, f):
        self.f = f
        self.in_movi = False

    def next(self):
        while True:
            tag = self.f.read(4)
            if len(tag) < 4:
                return None
            size_b = self.f.read(4)
            if len(size_b) < 4:
                raise B2VError('truncated AVI chunk header')
            size = struct.unpack('<I', size_b)[0]

            if tag in (_T_RIFF, _T_LIST):
                list_type = self.f.read(4)
                if list_type == _T_movi:
                    self.in_movi = True
                continue

            if self.in_movi and tag in (_T_00dc, _T_00db):
                if size != FRAME_BYTES:
                    raise B2VError('not a 1920x1080 uncompressed B2V2 AVI video')
                frame = self.f.read(FRAME_BYTES)
                if len(frame) != FRAME_BYTES:
                    raise B2VError('unexpected end of video')
                if size & 1:
                    self.f.read(1)
                return frame

            self.f.seek(size + (size & 1), io.SEEK_CUR)


def peek_magic(path):
    with open(path, 'rb') as f:
        frame = FrameReader(f).next()
        if frame is None:
            raise B2VError('no video frames found')
        return from_frame(frame)[:4]


# ------------------------------------------------------------------- B2V2

def parse_header2(data):
    if data[:4] != MAGIC2:
        return None
    idx, total, payload_len = struct.unpack_from('>III', data, 4)
    total_bytes, = struct.unpack_from('>Q', data, 16)
    payload_crc, = struct.unpack_from('>I', data, 24)
    name_len, = struct.unpack_from('>H', data, 28)
    if name_len > 255:
        return None
    size = FIXED_HDR + name_len + 4
    if crc32(data[:size - 4]) != struct.unpack_from('>I', data, size - 4)[0]:
        return None
    if payload_len < 0 or payload_len > CAP - size:
        return None
    name = data[FIXED_HDR:FIXED_HDR + name_len].decode('utf-8')
    return dict(idx=idx, total=total, payload_len=payload_len, total_bytes=total_bytes,
                payload_crc=payload_crc, name=name, size=size)


def encode_plain(src_path, out_path, progress=None):
    name_bytes = _trunc_utf8(os.path.basename(src_path), 255)
    hdr_size = FIXED_HDR + len(name_bytes) + 4
    pay = CAP - hdr_size
    size = os.path.getsize(src_path)
    n = max(1, (size + pay - 1) // pay)

    with open(src_path, 'rb') as fin, open(out_path, 'wb') as fout:
        write_avi_header(fout, n)
        done = 0
        for i in range(n):
            chunk = fin.read(pay)
            if len(chunk) < pay:
                chunk = chunk + b'\0' * (pay - len(chunk))
            plen = min(pay, size - done)
            payload_crc = crc32(chunk[:plen])
            buf = bytearray(CAP)
            buf[0:4] = MAGIC2
            struct.pack_into('>III', buf, 4, i, n, plen)
            struct.pack_into('>Q', buf, 16, size)
            struct.pack_into('>I', buf, 24, payload_crc)
            struct.pack_into('>H', buf, 28, len(name_bytes))
            buf[FIXED_HDR:FIXED_HDR + len(name_bytes)] = name_bytes
            buf[hdr_size:hdr_size + plen] = chunk[:plen]
            struct.pack_into('>I', buf, hdr_size - 4, crc32(bytes(buf[:hdr_size - 4])))
            write_avi_frame(fout, to_frame(bytes(buf)))
            done += plen
            if progress:
                progress(done, size)
        write_avi_index(fout, n)
        if fin.read(1):
            raise B2VError('input changed size while reading')


def decode_plain(src_path, out_dir, progress=None):
    with open(src_path, 'rb') as f:
        reader = FrameReader(f)
        first = None
        got = {}
        expect = 0
        while True:
            frame = reader.next()
            if frame is None:
                break
            data = from_frame(frame)
            h = parse_header2(data)
            if h is None:
                raise B2VError(f'frame {expect}: header damaged')
            if first is None:
                first = h
            if h['idx'] < expect:
                continue
            if h['idx'] != expect:
                raise B2VError(f'frame {expect} missing')
            payload = data[h['size']:h['size'] + h['payload_len']]
            if crc32(payload) != h['payload_crc']:
                raise B2VError(f'frame {expect}: data damaged')
            got[expect] = payload
            expect += 1
            if progress:
                progress(sum(len(v) for v in got.values()), h['total_bytes'])
            if expect == h['total']:
                break
        if first is None:
            raise B2VError('no B2V2 frames found')
        if expect != first['total']:
            raise B2VError(f'video ends early: {expect} of {first["total"]} frames')
        out = b''.join(got[i] for i in range(expect))[:first['total_bytes']]
        if len(out) != first['total_bytes']:
            raise B2VError('size mismatch')
    name = first['name'].replace('/', '_').replace('\\', '_') or 'decoded'
    out_path = os.path.join(out_dir, name)
    with open(out_path, 'wb') as f:
        f.write(out)
    return name, len(out)


# ------------------------------------------------------------------- B2V3

def _secret_material(password, key_file_sha256):
    has_pw = bool(password)
    has_kf = key_file_sha256 is not None
    if not has_pw and not has_kf:
        raise ValueError('provide a password, a key file, or both')
    out = (password.encode('utf-8') if has_pw else b'') + (key_file_sha256 if has_kf else b'')
    return out


def _java_kdf_bytes(secret_material):
    """Reproduces Java's PBEKeySpec(char[]) behaviour exactly (verified against a
    live Java PBKDF2WithHmacSHA256 run, not assumed): the raw secret bytes are
    decoded as ISO-8859-1, then that string is re-encoded as UTF-8 before PBKDF2."""
    return secret_material.decode('iso-8859-1').encode('utf-8')


def derive_key(password, key_file_sha256, salt, iterations):
    from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
    from cryptography.hazmat.primitives import hashes
    material = _java_kdf_bytes(_secret_material(password, key_file_sha256))
    return PBKDF2HMAC(algorithm=hashes.SHA256(), length=32, salt=salt, iterations=iterations).derive(material)


def hash_key_file(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        while True:
            chunk = f.read(1 << 20)
            if not chunk:
                break
            h.update(chunk)
    return h.digest()


def _iv(nonce_base, idx):
    return nonce_base + struct.pack('>I', idx)


def _aad(magic, idx, total):
    return magic + struct.pack('>II', idx, total)


def peek_envelope(path):
    with open(path, 'rb') as f:
        frame = FrameReader(f).next()
        if frame is None:
            raise B2VError('no video frames found')
        data = from_frame(frame)
    if data[:4] != MAGIC3:
        raise B2VError('not a B2V3 encrypted video (bad header)')
    salt = data[4:4 + SALT_LEN]
    nonce_base = data[4 + SALT_LEN:4 + SALT_LEN + NONCE_BASE_LEN]
    off = 4 + SALT_LEN + NONCE_BASE_LEN
    iterations, data_frames = struct.unpack_from('>II', data, off)
    if iterations <= 0 or data_frames < 0:
        raise B2VError('B2V3 header damaged')
    return dict(salt=salt, nonce_base=nonce_base, iterations=iterations, data_frames=data_frames)


def encode_encrypted(src_path, out_path, password, key_file_sha256, progress=None):
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM

    name_bytes = _trunc_utf8(os.path.basename(src_path), MAX_NAME)
    size = os.path.getsize(src_path)
    logical_len = 2 + len(name_bytes) + 8 + size
    n = (logical_len + CIPHER_LEN - 1) // CIPHER_LEN

    salt = os.urandom(SALT_LEN)
    nonce_base = os.urandom(NONCE_BASE_LEN)
    key = derive_key(password, key_file_sha256, salt, ITERATIONS)
    aes = AESGCM(key)

    with open(src_path, 'rb') as fin, open(out_path, 'wb') as fout:
        write_avi_header(fout, n + 1)

        envelope = bytearray(CAP)
        envelope[0:4] = MAGIC3
        envelope[4:4 + SALT_LEN] = salt
        envelope[4 + SALT_LEN:4 + SALT_LEN + NONCE_BASE_LEN] = nonce_base
        struct.pack_into('>II', envelope, 4 + SALT_LEN + NONCE_BASE_LEN, ITERATIONS, n)
        write_avi_frame(fout, to_frame(bytes(envelope)))

        header_bytes = struct.pack('>H', len(name_bytes)) + name_bytes + struct.pack('>Q', size)
        header_pos = 0
        done = 0
        for i in range(n):
            plain = bytearray(CIPHER_LEN)
            pos = 0
            if header_pos < len(header_bytes):
                take = min(len(header_bytes) - header_pos, CIPHER_LEN)
                plain[0:take] = header_bytes[header_pos:header_pos + take]
                header_pos += take
                pos = take
            if pos < CIPHER_LEN:
                take = min(CIPHER_LEN - pos, size - done)
                chunk = fin.read(take)
                if len(chunk) != take:
                    raise B2VError('input changed size while reading')
                plain[pos:pos + take] = chunk
                done += take
                pos += take
            cipher_text = aes.encrypt(_iv(nonce_base, i), bytes(plain), _aad(MAGIC3, i, n))
            buf = bytearray(CAP)
            struct.pack_into('>II', buf, 0, i, n)
            buf[FRAME_HDR:FRAME_HDR + len(cipher_text)] = cipher_text
            write_avi_frame(fout, to_frame(bytes(buf)))
            if progress:
                progress(done, size)
        write_avi_index(fout, n + 1)
        if fin.read(1):
            raise B2VError('input changed size while reading')


def decode_encrypted(src_path, out_dir, password, key_file_sha256, progress=None):
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    from cryptography.exceptions import InvalidTag

    with open(src_path, 'rb') as f:
        reader = FrameReader(f)
        frame = reader.next()
        if frame is None:
            raise B2VError('no video frames found')
        data = from_frame(frame)
        if data[:4] != MAGIC3:
            raise B2VError('not a B2V3 encrypted video (bad header)')
        salt = data[4:4 + SALT_LEN]
        nonce_base = data[4 + SALT_LEN:4 + SALT_LEN + NONCE_BASE_LEN]
        off = 4 + SALT_LEN + NONCE_BASE_LEN
        iterations, data_frames = struct.unpack_from('>II', data, off)

        key = derive_key(password, key_file_sha256, salt, iterations)
        aes = AESGCM(key)

        name = None
        total_bytes = None
        header_buf = None
        header_pos = 0
        have_header = False
        written = 0
        out_chunks = []
        for i in range(data_frames):
            frame = reader.next()
            if frame is None:
                raise B2VError(f'video ends early: frame {i} of {data_frames} missing')
            data = from_frame(frame)
            idx, total = struct.unpack_from('>II', data, 0)
            if idx != i or total != data_frames:
                raise B2VError(f'frame {i}: index damaged or out of order')
            cipher_text = data[FRAME_HDR:CAP]
            try:
                plain = aes.decrypt(_iv(nonce_base, i), cipher_text, _aad(MAGIC3, i, total))
            except InvalidTag:
                raise B2VError(f'wrong key, or frame {i} is corrupted or tampered with')

            pos = 0
            if not have_header:
                if header_buf is None:
                    name_len = (plain[0] << 8) | plain[1]
                    if name_len > 255:
                        raise B2VError('B2V3 header damaged')
                    header_buf = bytearray(2 + name_len + 8)
                take = min(len(header_buf) - header_pos, len(plain))
                header_buf[header_pos:header_pos + take] = plain[0:take]
                header_pos += take
                pos = take
                if header_pos == len(header_buf):
                    name_len = (header_buf[0] << 8) | header_buf[1]
                    name = header_buf[2:2 + name_len].decode('utf-8')
                    total_bytes, = struct.unpack_from('>Q', header_buf, 2 + name_len)
                    have_header = True
            if have_header and pos < len(plain):
                take = min(len(plain) - pos, total_bytes - written)
                out_chunks.append(plain[pos:pos + take])
                written += take
                if progress:
                    progress(written, total_bytes)
        if not have_header or written != total_bytes:
            raise B2VError('size mismatch after decrypting')

    clean = name.replace('/', '_').replace('\\', '_') or 'decoded'
    out_path = os.path.join(out_dir, clean)
    with open(out_path, 'wb') as f:
        for c in out_chunks:
            f.write(c)
    return clean, written
