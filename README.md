# bits2vid

Encodes any file into a lossless video — a grid of pixels where each pixel's
red, green and blue values hold 3 bytes of the file — and decodes that video
back into the exact original file, byte for byte. Optionally encrypts the
file first with a password and/or key file, using AES-256-GCM.

Three builds, all sharing the same video format so a file encoded on one
decodes fine on any of the others:

- **[`desktop/`](desktop/)** — a command-line jar for Windows, Linux and
  macOS (needs Java 11+). See [`desktop/README.md`](desktop/README.md) for
  usage, including the encryption options. Produces plain AVI files.
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

`desktop/` and `android-playable/` write/read this data inside a plain AVI
container (BGR24, top-down, `BI_RGB`); `android/` uses a hand-written
Matroska container with the identical pixel/data layout. The full byte-level
spec is documented in the header comments of each `B2V.java`/`B2VSecure.java`.

## Important

The video must reach the decoder exactly as written — don't re-encode,
trim, resize, or upload it anywhere that recompresses video (many chat and
social apps do this automatically). It will also be noticeably larger than
the input file; this is a lossless, simple format, not a compression tool.
