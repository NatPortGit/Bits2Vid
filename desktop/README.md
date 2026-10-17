# bits2video

Encodes any file into a lossless video — a grid of pixels, each pixel's red,
green and blue values holding 3 bytes of the file — and decodes that video
back into the exact original file, byte for byte. Optionally encrypts it
with a password and/or key file first, with AES-256-GCM.

This folder contains `bits2video.jar`, which runs on Windows, Linux and
macOS. It is the same encoder/decoder used by the Bits2Video Playable Android
app, so a video made on your phone decodes here, and vice versa (unencrypted
and encrypted videos both work either way). The video is a plain AVI file, so
it plays in VLC and most other players, same as the phone app.

## Requirements

**Java 11 or newer.** Check what you have:

```
java -version
```

If that fails or shows a version below 11, install a free JDK — for example
[Eclipse Temurin](https://adoptium.net/) (choose the JDK, not just the JRE,
for either Windows or Linux). Nothing else needs installing; `bits2video.jar`
has no other dependencies.

## Usage

Open a terminal (Command Prompt, PowerShell, or a Linux shell) in this
folder, or `cd` into it, then:

**Encode a file into a video:**
```
java -jar bits2video.jar encode <input-file> <output.avi>
```

**Decode a video back into the original file:**
```
java -jar bits2video.jar decode <input.avi> <output-folder>
```

The output folder is created if it doesn't exist, and the decoded file is
saved there under its original name.

### Examples

```
java -jar bits2video.jar encode photo.jpg photo.b2v.avi
java -jar bits2video.jar decode photo.b2v.avi .
```

On Windows, if a path has spaces, put it in quotes:
```
java -jar bits2video.jar encode "C:\Users\You\Documents\report.pdf" report.b2v.avi
```

### Encrypting

Add `--password` and/or `--keyfile` to either command to protect the video.
Leave both out for a plain, unprotected video (the default).

```
java -jar bits2video.jar encode secrets.zip secrets.b2v3.avi --password-prompt
java -jar bits2video.jar decode secrets.b2v3.avi . --password-prompt
```

- `--password-prompt` asks for the password interactively, so it never ends
  up in your shell history or on screen. Use this unless you're scripting.
- `--password "your password"` passes it directly on the command line
  (visible in your shell history — fine for scripts, less ideal by hand).
- `--keyfile path/to/file` uses any file as a key instead of, or together
  with, a password. The file's exact bytes matter, so don't edit it or
  re-save it (even re-saving a photo can change its bytes) between
  encoding and decoding — keep a backup copy you never touch.
- Decoding an encrypted video needs the *same* password and/or key file it
  was encoded with. A wrong key, or any corrupted/tampered data, fails
  cleanly with an error — it never produces a garbled or partial file.
- **There is no way to recover an encrypted file if you lose the password
  or key file.** There is no backdoor, and nobody — including whoever wrote
  this tool — can get it back for you.

Encrypted videos use AES-256-GCM, keyed by PBKDF2-HMAC-SHA256 (600,000
iterations, salted and randomized per file). See `src/app/bits2video/B2VSecure.java`
for the exact format if you want to check the details yourself.

## Important: don't re-compress the video

The video must reach the decoder exactly as it was written — don't
re-encode it, trim it, change its resolution, or upload it anywhere that
automatically re-compresses video (many messaging apps and social media
sites do this to photos and videos). Any of that will corrupt the data.
Copying the file as-is (a USB drive, a direct file transfer, cloud storage
that stores the file unchanged) is fine.

The video will also be noticeably larger than the input file — this trades
space for being simple, lossless, and actually watchable (if not very
interesting to look at) in a normal video player. It is not a compression tool.

## What's in this folder

- `bits2video.jar` — the program. Everything above runs through this.
- `src/app/bits2video/*.java` — the source code, in case you want to read it,
  modify it, or rebuild it yourself. No build tool is required beyond a JDK:

  ```
  javac --release 11 -d classes src/app/bits2video/*.java
  jar --create --file bits2video.jar --main-class app.bits2video.App -C classes .
  ```

## Troubleshooting

- **"java: command not found" / "'java' is not recognized"** — Java isn't
  installed, or isn't on your PATH. Install a JDK (see Requirements) and
  make sure to restart your terminal afterward.
- **"UnsupportedClassVersionError"** — your Java is older than 11. Install a
  newer JDK.
- **"wrong key, or frame ... is corrupted or tampered with"** when
  decoding — either the password/key file doesn't match what it was
  encoded with, or the video file itself was altered or re-compressed
  after encoding (see above).
- **"not a bits2video file (unrecognized header)"** — the file you pointed
  at isn't a video this tool made (or it's been re-compressed, which
  changes its pixels and makes it unreadable as data).
