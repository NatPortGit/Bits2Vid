# bits2vid

Encodes any file into a lossless video — a grid of pixels where each pixel's
red, green and blue values hold 3 bytes of the file — and decodes that video
back into the exact original file, byte for byte. Optionally encrypts the
file first with a password and/or key file, using AES-256-GCM.

Three builds, all sharing the same video format so a file encoded on one
decodes fine on any of the others:

- **[`desktop/`](desktop/)** — for Windows, Linux and macOS. See
  [`desktop/README.md`](desktop/README.md) for usage, including the
  encryption options. Two independent, interoperable implementations
  inside it: a Python GUI and CLI (`desktop/python/`, needs only Python,
  plus `pip install cryptography` for encryption), and a Java CLI
  (`desktop/bits2video.jar`, needs Java 11+). Both produce plain AVI files.
- **[`android-playable/`](android-playable/)** — the current Android app
  (7.0+), package `app.bits2video.playable`. Same AVI container as
  `desktop/`, so its videos play in VLC and most other players. Build with
  `android-playable/build.sh`.
- **[`android/`](android/)** — the original Android app, package
  `app.bits2video`. Same pixel data, but packaged in a hand-written
  Matroska container that most players (VLC included) can't open — kept
  here because it's a separate, side-by-side-installable app and still
  fully interoperable at the data level, not because it's recommended over
  `android-playable/`.

Both Android variants can be installed on the same device at once (distinct
package names and signing keys), which is what they're for — comparing them
side by side.

## Format

1920x1080 frames, uncompressed 24-bit RGB, 1 frame per second. Every pixel
after row 0 carries 3 bytes of file data; row 0 is a calibration strip
(unused by the decoder for this lossless format, kept for layout
compatibility). Two variants, independent of container:

- **B2V2** — plain, unencrypted.
- **B2V3** — encrypted. AES-256-GCM per frame, keyed by PBKDF2-HMAC-SHA256
  (600,000 iterations) from a password and/or a key file's SHA-256 hash,
  with a fresh random salt and nonce per file. A wrong key, or any
  tampering, fails a frame's authentication tag and aborts cleanly —
  there's no partial or garbled output, and no way to recover a file
  without the password/key file.

`desktop/` (both the Python and Java versions) and `android-playable/`
write/read this data inside a plain AVI container (BGR24, top-down,
`BI_RGB`); `android/` uses a hand-written Matroska container with the
identical pixel/data layout. The full byte-level spec is documented in the
header comments of each `B2V.java`/`B2VSecure.java` and `b2vcore.py`.

One cross-language detail worth knowing if you're reading the source: the
Java tools derive the encryption key via `PBEKeySpec(char[])`, and Java's
actual (tested, not just documented) behaviour there is to decode those
chars as ISO-8859-1 and then **re-encode that as UTF-8** before running
PBKDF2 -- not use the bytes as given. `b2vcore.py`'s `_java_kdf_bytes()`
replicates this exactly, verified by live cross-tool encrypt/decrypt tests
in both directions (password-only, key-file-only, and both combined).
Skipping that step would make Python- and Java-made encrypted videos
silently fail to decrypt on the other tool whenever the key material
contains a byte >= 0x80, which a key file's SHA-256 hash always will,
roughly half the time per byte.

## Important

The video must reach the decoder exactly as written — don't re-encode,
trim, resize, or upload it anywhere that recompresses video (many chat and
social apps do this automatically). It will also be noticeably larger than
the input file; this is a lossless, simple format, not a compression tool.
