# bits2vid

Encodes any file into a lossless video — a grid of pixels where each pixel's
red, green and blue values hold 3 bytes of the file — and decodes that video
back into the exact original file, byte for byte. Optionally encrypts the
file first with a password and/or key file, using AES-256-GCM.

Two ways to run it, sharing the same video format so a file encoded on one
decodes fine on the other:

- **[`android/`](android/)** — an Android app (7.0+). Pick a file to encode,
  or a video to decode, from the system file picker. Build with
  `android/build.sh` (needs an Android SDK: platform 36, build-tools 36.0.0,
  and a JDK).
- **[`desktop/`](desktop/)** — a command-line jar for Windows, Linux and
  macOS (needs Java 11+). See [`desktop/README.md`](desktop/README.md) for
  usage, including the encryption options.

## Format

1920x1080 frames, uncompressed 24-bit RGB in a Matroska (`.mkv`) container,
1 frame per second. Every pixel after row 0 carries 3 bytes of file data;
row 0 is a calibration strip. Two variants:

- **B2V2** — plain, unencrypted.
- **B2V3** — encrypted. AES-256-GCM per frame, keyed by PBKDF2-HMAC-SHA256
  (600,000 iterations) from a password and/or a key file's SHA-256 hash,
  with a fresh random salt and nonce per file. A wrong key, or any
  tampering, fails a frame's authentication tag and aborts cleanly —
  there's no partial or garbled output, and no way to recover a file
  without the password/key file.

The full byte-level spec is documented in the header comments of
`B2V.java` and `B2VSecure.java` (identical copies live under both
`android/src/` and `desktop/src/`, since the Android and desktop builds
compile independently).

## Important

The video must reach the decoder exactly as written — don't re-encode,
trim, resize, or upload it anywhere that recompresses video (many chat and
social apps do this automatically). It will also be noticeably larger than
the input file; this is a lossless, simple format, not a compression tool.
