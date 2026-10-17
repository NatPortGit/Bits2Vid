#!/usr/bin/env python3
"""bits2video command-line tool (Python). Same format as b2v_gui.py and the
Java desktop/Android tools in this project -- videos are interoperable
across all of them.

  python3 b2v_cli.py encode <input-file> <output.avi> [--password PW] [--keyfile FILE] [--password-prompt]
  python3 b2v_cli.py decode <input.avi> <output-folder> [--password PW] [--keyfile FILE] [--password-prompt]
"""
import argparse
import getpass
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import b2vcore as core


def human(n):
    return f"{n / 1e6:.1f} MB"


def progress_printer(label, start):
    last = [0.0]

    def cb(done, total):
        now = time.time()
        if now - last[0] < 0.2 and done < total:
            return
        last[0] = now
        secs = max(0.001, now - start)
        pct = 100.0 * done / total if total else 100.0
        print(f"\r{label}... {human(done)} of {human(total)} ({pct:.0f}%)   ", end="", flush=True)
    return cb


def main():
    ap = argparse.ArgumentParser(description="bits2video: encode any file into a lossless video, and decode it back.")
    ap.add_argument("cmd", choices=["encode", "decode"])
    ap.add_argument("input")
    ap.add_argument("output")
    ap.add_argument("--password")
    ap.add_argument("--keyfile")
    ap.add_argument("--password-prompt", action="store_true")
    a = ap.parse_args()

    password = a.password
    if a.password_prompt:
        password = getpass.getpass("Password: ")
    key_hash = core.hash_key_file(a.keyfile) if a.keyfile else None
    encrypted = bool(password) or key_hash is not None

    start = time.time()
    try:
        if a.cmd == "encode":
            if not os.path.isfile(a.input):
                print(f"No such file: {a.input}", file=sys.stderr)
                sys.exit(1)
            size = os.path.getsize(a.input)
            cb = progress_printer("Encoding", start)
            if encrypted:
                core.encode_encrypted(a.input, a.output, password, key_hash, progress=cb)
            else:
                core.encode_plain(a.input, a.output, progress=cb)
            print()
            kind = "Encrypted and encoded" if encrypted else "Encoded"
            print(f"{kind} {a.input} -> {a.output}  ({human(size)}, {time.time() - start:.1f}s)")
        else:
            os.makedirs(a.output, exist_ok=True)
            magic = core.peek_magic(a.input)
            cb = progress_printer("Decoding", start)
            if magic == core.MAGIC3:
                if not encrypted:
                    print("This video is encrypted. Pass --password and/or --keyfile with the "
                          "same password/key file it was encoded with.", file=sys.stderr)
                    sys.exit(1)
                name, n = core.decode_encrypted(a.input, a.output, password, key_hash, progress=cb)
                print()
                print(f"Decoded -> {os.path.join(a.output, name)}  ({human(n)}, {time.time() - start:.1f}s)")
                print("The key checked out and every frame passed its AES-GCM authentication tag.")
            elif magic == core.MAGIC2:
                name, n = core.decode_plain(a.input, a.output, progress=cb)
                print()
                print(f"Decoded -> {os.path.join(a.output, name)}  ({human(n)}, {time.time() - start:.1f}s)")
                print("All checksums OK.")
            else:
                print(f"Not a bits2video file (unrecognized header): {a.input}", file=sys.stderr)
                sys.exit(1)
    except core.B2VError as e:
        print()
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
