#!/usr/bin/env python3
"""bits2video GUI -- same feature set as the Bits2Video Android app: encode any
file into a lossless, VLC-playable video, decode it back, with optional
password/key-file encryption (AES-256-GCM). Needs b2vcore.py alongside this
file, and `pip install cryptography` for the encryption features (plain,
unencrypted files need nothing beyond the standard library).

Videos made here are byte-for-byte interoperable with the Java desktop jar
and both Android apps in this project -- verified by live cross-tests, not
just a shared spec on paper.
"""
import os
import sys
import threading
import time
import traceback
import tkinter as tk
from tkinter import ttk, filedialog, messagebox

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import b2vcore as core


def human(n):
    return f"{n / 1e6:.1f} MB"


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("Bits2Video")
        self.geometry("560x620")
        self.minsize(480, 560)

        self.key_file_path = None
        self.key_file_hash = None
        self.cancelled = False
        self.worker = None

        pad = {"padx": 16, "pady": 4}

        tk.Label(self, text="Bits2Video", font=("TkDefaultFont", 18, "bold")).pack(anchor="w", **pad)

        info = (
            "Encode any file into a lossless video -- each pixel's red, green and blue\n"
            "values hold 3 bytes of the file -- and decode it back into the exact\n"
            "original file, with its original name. The video plays in VLC and most\n"
            "other players.\n\n"
            "Don't re-encode, trim or upload the video anywhere that recompresses\n"
            "video, or it can't be decoded. It will be noticeably larger than the\n"
            "input file -- this is not a compression tool."
        )
        tk.Label(self, text=info, justify="left", wraplength=520, fg="#444").pack(anchor="w", **pad)

        tk.Label(self, text="Password / key file (optional)", font=("TkDefaultFont", 11, "bold")) \
            .pack(anchor="w", padx=16, pady=(12, 0))
        tk.Label(self, text="Leave both blank to make a plain, unprotected video. Set either or\n"
                             "both to encrypt it with AES-256-GCM. There is no way to recover the\n"
                             "file if you lose the password/key file.",
                 justify="left", wraplength=520, fg="#444").pack(anchor="w", padx=16, pady=(0, 6))

        pw_row = tk.Frame(self)
        pw_row.pack(fill="x", padx=16, pady=2)
        tk.Label(pw_row, text="Password:").pack(side="left")
        self.password_var = tk.StringVar()
        self.password_entry = tk.Entry(pw_row, textvariable=self.password_var, show="*")
        self.password_entry.pack(side="left", fill="x", expand=True, padx=8)
        self.show_pw_var = tk.BooleanVar()
        tk.Checkbutton(pw_row, text="Show", variable=self.show_pw_var, command=self._toggle_pw) \
            .pack(side="left")

        kf_row = tk.Frame(self)
        kf_row.pack(fill="x", padx=16, pady=2)
        self.key_file_btn = tk.Button(kf_row, text="Choose key file...", command=self._choose_key_file)
        self.key_file_btn.pack(side="left")
        self.key_file_clear_btn = tk.Button(kf_row, text="Clear", command=self._clear_key_file, state="disabled")
        self.key_file_clear_btn.pack(side="left", padx=6)
        self.key_file_label = tk.Label(self, text="No key file selected", fg="#666")
        self.key_file_label.pack(anchor="w", padx=16, pady=(0, 10))

        btn_row = tk.Frame(self)
        btn_row.pack(fill="x", padx=16, pady=(8, 4))
        self.encode_btn = tk.Button(btn_row, text="Encode a file...", height=2, command=self._start_encode)
        self.encode_btn.pack(fill="x", pady=4)
        self.decode_btn = tk.Button(btn_row, text="Decode a video...", height=2, command=self._start_decode)
        self.decode_btn.pack(fill="x", pady=4)

        self.progress = ttk.Progressbar(self, mode="determinate", maximum=1000)
        self.progress.pack(fill="x", padx=16, pady=(16, 4))

        self.status_var = tk.StringVar(value="")
        tk.Label(self, textvariable=self.status_var, justify="left", wraplength=520, fg="#222") \
            .pack(anchor="w", padx=16, pady=4)

        self.cancel_btn = tk.Button(self, text="Cancel", command=self._cancel)
        self.protocol("WM_DELETE_WINDOW", self._on_close)

    # ------------------------------------------------------------- helpers

    def _toggle_pw(self):
        self.password_entry.config(show="" if self.show_pw_var.get() else "*")

    def _choose_key_file(self):
        path = filedialog.askopenfilename(title="Choose a key file")
        if not path:
            return
        self.status_var.set("Reading key file...")
        self.update_idletasks()
        try:
            self.key_file_hash = core.hash_key_file(path)
            self.key_file_path = path
            self.key_file_label.config(text=f"Key file: {os.path.basename(path)}")
            self.key_file_clear_btn.config(state="normal")
            self.status_var.set("")
        except Exception as e:
            messagebox.showerror("Couldn't read key file", str(e))
            self.status_var.set("")

    def _clear_key_file(self):
        self.key_file_path = None
        self.key_file_hash = None
        self.key_file_label.config(text="No key file selected")
        self.key_file_clear_btn.config(state="disabled")

    def _has_key(self):
        return bool(self.password_var.get()) or self.key_file_hash is not None

    def _set_busy(self, busy):
        state = "disabled" if busy else "normal"
        self.encode_btn.config(state=state)
        self.decode_btn.config(state=state)
        self.key_file_btn.config(state=state)
        if busy:
            self.cancel_btn.pack(padx=16, pady=4)
        else:
            self.cancel_btn.pack_forget()
        self.progress["value"] = 0

    def _cancel(self):
        self.cancelled = True
        self.status_var.set("Cancelling...")

    def _on_close(self):
        if self.worker and self.worker.is_alive():
            if not messagebox.askyesno("Still working", "A job is still running. Quit anyway?"):
                return
            self.cancelled = True
        self.destroy()

    # --------------------------------------------------------------- encode

    def _start_encode(self):
        src = filedialog.askopenfilename(title="Choose a file to encode")
        if not src:
            return
        encrypt = self._has_key()
        suggested = os.path.basename(src) + (".b2v3.avi" if encrypt else ".b2v.avi")
        out = filedialog.asksaveasfilename(title="Save video as", initialfile=suggested,
                                            defaultextension=".avi",
                                            filetypes=[("AVI video", "*.avi")])
        if not out:
            return
        password = self.password_var.get()
        key_hash = self.key_file_hash

        self._run_job(f"Encoding {os.path.basename(src)}", out, lambda prog: self._do_encode(
            src, out, encrypt, password, key_hash, prog))

    def _do_encode(self, src, out, encrypt, password, key_hash, prog):
        size = os.path.getsize(src)
        if encrypt:
            core.encode_encrypted(src, out, password or None, key_hash, progress=prog)
            return f"Encoded {os.path.basename(src)} ({human(size)}), encrypted with AES-256-GCM."
        else:
            core.encode_plain(src, out, progress=prog)
            return f"Encoded {os.path.basename(src)} ({human(size)})."

    # --------------------------------------------------------------- decode

    def _start_decode(self):
        src = filedialog.askopenfilename(title="Choose a video to decode",
                                          filetypes=[("AVI video", "*.avi"), ("All files", "*.*")])
        if not src:
            return
        self.status_var.set("Reading video header...")
        self._set_busy(True)
        self.update_idletasks()

        def probe():
            try:
                magic = core.peek_magic(src)
                if magic == core.MAGIC3:
                    if not self._has_key():
                        self.after(0, lambda: self._probe_failed(
                            "This video is encrypted. Enter the password and/or choose the key "
                            "file it was encoded with above, then decode it again."))
                        return
                    self.after(0, lambda: self._probe_done_encrypted(src))
                elif magic == core.MAGIC2:
                    with open(src, "rb") as f:
                        frame = core.FrameReader(f).next()
                    data = core.from_frame(frame)
                    h = core.parse_header2(data)
                    if h is None:
                        raise core.B2VError("not a B2V2 encoded video (bad header)")
                    name = h["name"].replace("/", "_").replace("\\", "_") or "decoded"
                    self.after(0, lambda: self._probe_done_plain(src, name))
                else:
                    raise core.B2VError("not a bits2video file (unrecognized header)")
            except Exception as e:
                msg = str(e)   # capture now: `e` is unbound by the time a deferred lambda would run
                self.after(0, lambda m=msg: self._probe_failed(f"Can't decode: {m}"))

        threading.Thread(target=probe, daemon=True).start()

    def _probe_failed(self, msg):
        self._set_busy(False)
        self.status_var.set(msg)

    def _probe_done_plain(self, src, suggested_name):
        self._set_busy(False)
        self.status_var.set("")
        out_dir = filedialog.askdirectory(title="Choose a folder to save the decoded file in")
        if not out_dir:
            return
        self._run_job("Decoding", None, lambda prog: self._do_decode_plain(src, out_dir, prog))

    def _probe_done_encrypted(self, src):
        self._set_busy(False)
        self.status_var.set("")
        out_dir = filedialog.askdirectory(title="Choose a folder to save the decoded file in")
        if not out_dir:
            return
        password = self.password_var.get()
        key_hash = self.key_file_hash
        self._run_job("Decoding", None,
                      lambda prog: self._do_decode_encrypted(src, out_dir, password, key_hash, prog))

    def _do_decode_plain(self, src, out_dir, prog):
        name, n = core.decode_plain(src, out_dir, progress=prog)
        return f"Decoded {name} ({human(n)}). All checksums OK."

    def _do_decode_encrypted(self, src, out_dir, password, key_hash, prog):
        name, n = core.decode_encrypted(src, out_dir, password or None, key_hash, progress=prog)
        return f"Decoded {name} ({human(n)}). The key checked out and every frame passed " \
               f"its AES-GCM authentication tag."

    # ----------------------------------------------------------- job runner

    def _run_job(self, label, output_path_on_fail, work):
        self.cancelled = False
        self._set_busy(True)
        self.status_var.set(label + "...")
        start = time.time()
        last_ui = [0.0]

        def prog(done, total):
            if self.cancelled:
                raise core.B2VError("__cancelled__")
            now = time.time()
            if now - last_ui[0] < 0.2 and done < total:
                return
            last_ui[0] = now
            secs = max(0.001, now - start)
            pct = int(done * 1000 / total) if total else 1000
            speed = done / 1e6 / secs
            msg = f"{label}... {human(done)} of {human(total)} ({speed:.1f} MB/s)"
            self.after(0, lambda: self._update_progress(pct, msg))

        def run():
            try:
                result = work(prog)
                self.after(0, lambda: self._job_done(result, None))
            except core.B2VError as e:
                # Python unbinds `e` when this except block exits, but self.after()
                # defers the lambda until later -- so the message must be captured
                # into a plain string now, not read from `e` inside the lambda.
                msg = str(e)
                if msg == "__cancelled__":
                    self.after(0, lambda: self._job_done(None, "Cancelled.", output_path_on_fail))
                else:
                    self.after(0, lambda m=msg: self._job_done(None, f"Failed: {m}", output_path_on_fail))
            except Exception as e:
                traceback.print_exc()
                msg = str(e)
                self.after(0, lambda m=msg: self._job_done(None, f"Failed: {m}", output_path_on_fail))

        self.worker = threading.Thread(target=run, daemon=True)
        self.worker.start()

    def _update_progress(self, pct, msg):
        self.progress["value"] = pct
        self.status_var.set(msg)

    def _job_done(self, result, error=None, output_path_on_fail=None):
        self._set_busy(False)
        if error:
            if output_path_on_fail and os.path.exists(output_path_on_fail):
                try:
                    os.remove(output_path_on_fail)
                except OSError:
                    pass
            self.status_var.set(error + ("\nThe partial output file was deleted."
                                          if output_path_on_fail else ""))
        else:
            self.status_var.set(result)


def main():
    try:
        import cryptography  # noqa: F401
    except ImportError:
        pass  # only needed for the encryption features; plain encode/decode still works
    app = App()
    app.mainloop()


if __name__ == "__main__":
    main()
