# bits2video

Encodes any file into a lossless video — a grid of pixels, each pixel's red,
green and blue values holding 3 bytes of the file — and decodes that video
back into the exact original file, byte for byte. Optionally encrypts it
with a password and/or key file first, with AES-256-GCM.

Two independent implementations in this folder, byte-for-byte interoperable
with each other and with the Bits2Video Playable Android app (a video made
on any one of them decodes correctly on any other):

- **`python/`** — a GUI (`b2v_gui.py`) and a command-line tool (`b2v_cli.py`),
  needing only Python and, for encryption, one pip package. **Start here**
  unless you have a reason to prefer Java.
- **`bits2video.jar`** — a command-line tool for Java 11+.

Both write plain AVI files, so they play in VLC and most other players.

## Python (GUI and command line)

### Requirements

**Python 3.8 or newer**, with `tkinter` for the GUI (not needed for the
command-line tool). Check what you have:

```
python3 --version
python3 -c "import tkinter"
```

- **Windows / macOS:** `tkinter` is normally already included with Python
  from [python.org](https://www.python.org/downloads/) — nothing extra to do.
- **Linux:** it's often a separate package. On Debian/Ubuntu:
  ```
  sudo apt install python3-tk
  ```
  If you have more than one Python version installed, make sure you install
  the `python3-tk` that matches the `python3` you actually run (`python3
  --version` on both) — installing it for the wrong version will look like
  it worked but `import tkinter` will still fail.

**For encryption (optional):** the `cryptography` package.
```
pip install cryptography
```
Plain, unencrypted files work with no extra packages at all.

### Running it

**GUI:**
```
python3 python/b2v_gui.py
```
Buttons for encoding and decoding, a password field with a show/hide
toggle, a key-file picker, and a progress bar — the same feature set as the
Bits2Video Android app.

**Command line**, for scripting:
```
python3 python/b2v_cli.py encode <input-file> <output.avi>
python3 python/b2v_cli.py decode <input.avi> <output-folder>
```
Add `--password`, `--keyfile`, and/or `--password-prompt` the same way as
the Java tool below — see **Encrypting**, which applies to both.

### What's in `python/`

- `b2vcore.py` — the encoder/decoder itself (no UI). Pure Python plus, for
  encryption, `cryptography`; no ffmpeg or other external tool needed.
- `b2v_gui.py` — the GUI, built on `tkinter`.
- `b2v_cli.py` — the command-line tool.

All three need to stay in the same folder (`b2v_gui.py`/`b2v_cli.py` import
`b2vcore.py` from alongside themselves).

## Java (command line)

### Requirements

**Java 11 or newer.** Check what you have:

```
java -version
```

If that fails or shows a version below 11, install a free JDK — for example
[Eclipse Temurin](https://adoptium.net/) (choose the JDK, not just the JRE).
Nothing else needs installing; `bits2video.jar` has no other dependencies.

### Usage

```
java -jar bits2video.jar encode <input-file> <output.avi>
java -jar bits2video.jar decode <input.avi> <output-folder>
```

The output folder is created if it doesn't exist, and the decoded file is
saved there under its original name.

**Examples:**
```
java -jar bits2video.jar encode photo.jpg photo.b2v.avi
java -jar bits2video.jar decode photo.b2v.avi .
```

On Windows, if a path has spaces, put it in quotes:
```
java -jar bits2video.jar encode "C:\Users\You\Documents\report.pdf" report.b2v.avi
```

Rebuilding it yourself needs nothing beyond a JDK:
```
javac --release 11 -d classes src/app/bits2video/*.java
jar --create --file bits2video.jar --main-class app.bits2video.App -C classes .
```

## Encrypting (Python and Java both)

Add a password and/or key file to either tool to protect the video. Leave
both out for a plain, unprotected video (the default).

```
java -jar bits2video.jar encode secrets.zip secrets.b2v3.avi --password-prompt
python3 python/b2v_cli.py decode secrets.b2v3.avi . --password-prompt
```
(Encoded with one tool, decoded with the other, on purpose — they're
interoperable.) The GUI has a password field and a "Choose key file" button
instead of these flags.

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
or `python/b2vcore.py` for the exact format if you want to check the details
yourself.

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

## Troubleshooting

- **"java: command not found" / "'java' is not recognized"** — Java isn't
  installed, or isn't on your PATH. Install a JDK (see Requirements) and
  restart your terminal afterward.
- **"UnsupportedClassVersionError"** — your Java is older than 11. Install a
  newer JDK.
- **`ModuleNotFoundError: No module named 'tkinter'`** — see the Linux note
  above; you need the `python3-tk` (or similarly named) OS package, matching
  your actual `python3` version.
- **`ModuleNotFoundError: No module named 'cryptography'`** — only needed
  for encryption: `pip install cryptography`. Plain encode/decode works
  without it.
- **"wrong key, or frame ... is corrupted or tampered with"** when
  decoding — either the password/key file doesn't match what it was
  encoded with, or the video file itself was altered or re-compressed
  after encoding (see above).
- **"not a bits2video file (unrecognized header)"** — the file you pointed
  at isn't a video this tool made (or it's been re-compressed, which
  changes its pixels and makes it unreadable as data).
