"""Ganovenstadt-Launcher – grafische Oberflaeche (tkinter)."""
from __future__ import annotations

import queue
import shutil
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

import core

BG, PANEL, FG, ACCENT = "#1e1f22", "#2b2d31", "#e6e6e6", "#3ba55d"


class Launcher(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title(core.LAUNCHER_NAME)
        self.geometry("820x560")
        self.minsize(700, 480)
        self.configure(bg=BG)

        self.cfg = core.load_config()
        self.account = core.load_account()
        self.events: queue.Queue = queue.Queue()
        self.busy = False

        self._style()
        self._build()
        self._refresh_account_label()
        self.after(100, self._poll)
        self._run_bg(self._load_versions)
        if self.account and self.cfg["client_id"]:
            self._run_bg(self._refresh_login)

    # ------------------------------------------------------------ Aufbau
    def _style(self):
        s = ttk.Style(self)
        s.theme_use("clam")
        s.configure(".", background=BG, foreground=FG, fieldbackground=PANEL)
        s.configure("TFrame", background=BG)
        s.configure("Panel.TFrame", background=PANEL)
        s.configure("TLabel", background=BG, foreground=FG)
        s.configure("Panel.TLabel", background=PANEL)
        s.configure("Title.TLabel", font=("Segoe UI", 18, "bold"), background=PANEL)
        s.configure("TCheckbutton", background=BG, foreground=FG)
        s.configure("Play.TButton", font=("Segoe UI", 14, "bold"), padding=10,
                    background=ACCENT, foreground="white")
        s.map("Play.TButton", background=[("disabled", "#555"), ("active", "#2d8049")])
        s.configure("Horizontal.TProgressbar", background=ACCENT)

    def _build(self):
        top = ttk.Frame(self, style="Panel.TFrame", padding=12)
        top.pack(fill="x")
        ttk.Label(top, text="⛏  Ganovenstadt-Launcher", style="Title.TLabel").pack(side="left")
        ttk.Button(top, text="Einstellungen", command=self._open_settings).pack(side="right")
        self.login_btn = ttk.Button(top, text="Anmelden", command=self._login_clicked)
        self.login_btn.pack(side="right", padx=6)
        self.acc_label = ttk.Label(top, style="Panel.TLabel")
        self.acc_label.pack(side="right", padx=10)

        self.log = tk.Text(self, bg="#111214", fg="#c8c8c8", insertbackground=FG,
                           relief="flat", font=("Consolas", 9), state="disabled", wrap="word")
        self.log.pack(fill="both", expand=True, padx=12, pady=(12, 6))

        bottom = ttk.Frame(self, style="Panel.TFrame", padding=12)
        bottom.pack(fill="x")

        left = ttk.Frame(bottom, style="Panel.TFrame")
        left.pack(side="left", fill="x", expand=True)
        row = ttk.Frame(left, style="Panel.TFrame")
        row.pack(fill="x")
        ttk.Label(row, text="Version:", style="Panel.TLabel").pack(side="left")
        self.version_var = tk.StringVar(value=self.cfg["version"])
        self.version_box = ttk.Combobox(row, textvariable=self.version_var, width=18, state="readonly")
        self.version_box.pack(side="left", padx=6)
        ttk.Label(row, text="Loader:", style="Panel.TLabel").pack(side="left", padx=(10, 0))
        self.loader_var = tk.StringVar(value=self.cfg["loader"])
        ttk.Combobox(row, textvariable=self.loader_var, values=core.LOADERS, width=10,
                     state="readonly").pack(side="left", padx=6)
        self.snap_var = tk.BooleanVar(value=self.cfg["show_snapshots"])
        ttk.Checkbutton(row, text="Snapshots", variable=self.snap_var,
                        command=lambda: self._run_bg(self._load_versions)).pack(side="left", padx=6)

        self.status = ttk.Label(left, text="Bereit.", style="Panel.TLabel")
        self.status.pack(fill="x", pady=(8, 2))
        self.progress = ttk.Progressbar(left, mode="determinate")
        self.progress.pack(fill="x")

        self.play_btn = ttk.Button(bottom, text="▶  SPIELEN", style="Play.TButton", command=self._play)
        self.play_btn.pack(side="right", padx=(16, 0))

    # ------------------------------------------------------------ Thread-Helfer
    def _run_bg(self, fn, *args):
        def wrapper():
            try:
                fn(*args)
            except Exception as e:  # Fehler sichtbar machen statt verschlucken
                self.events.put(("error", str(e)))
        threading.Thread(target=wrapper, daemon=True).start()

    def _poll(self):
        try:
            while True:
                kind, val = self.events.get_nowait()
                if kind == "log":
                    self._append_log(val)
                elif kind == "status":
                    self.status.config(text=val)
                elif kind == "max":
                    self.progress.config(maximum=max(int(val), 1))
                elif kind == "progress":
                    self.progress.config(value=int(val))
                elif kind == "versions":
                    self.version_box.config(values=val)
                    if self.version_var.get() not in val and val:
                        self.version_var.set(val[0])
                elif kind == "account":
                    self._refresh_account_label()
                elif kind == "busy":
                    self.busy = val
                    self.play_btn.config(state="disabled" if val else "normal")
                elif kind == "error":
                    self._append_log(f"[Fehler] {val}")
                    self.status.config(text="Fehler – siehe Log.")
                    self.busy = False
                    self.play_btn.config(state="normal")
                    messagebox.showerror("Fehler", val)
                elif kind == "quit":
                    self.destroy()
                    return
        except queue.Empty:
            pass
        self.after(100, self._poll)

    def _append_log(self, text):
        self.log.config(state="normal")
        self.log.insert("end", text + "\n")
        if int(self.log.index("end-1c").split(".")[0]) > 3000:
            self.log.delete("1.0", "500.0")
        self.log.see("end")
        self.log.config(state="disabled")

    def _say(self, text):
        self.events.put(("log", text))

    # ------------------------------------------------------------ Aktionen
    def _load_versions(self):
        self.events.put(("status", "Lade Versionsliste …"))
        try:
            versions = core.get_versions(self.snap_var.get())
        except Exception as e:
            versions = core.get_installed_versions(self.cfg["game_dir"])
            self._say(f"[Launcher] Offline? Zeige nur installierte Versionen ({e})")
        self.events.put(("versions", versions))
        self.events.put(("status", "Bereit."))

    def _refresh_account_label(self):
        if self.account:
            self.acc_label.config(text=f"👤 {self.account['name']}")
            self.login_btn.config(text="Abmelden")
        else:
            self.acc_label.config(text="Nicht angemeldet (Demo-Modus)")
            self.login_btn.config(text="Mit Microsoft anmelden")

    def _login_clicked(self):
        if self.account:
            if messagebox.askyesno("Abmelden", f"{self.account['name']} abmelden?"):
                self.account = None
                core.save_account(None)
                self._refresh_account_label()
            return
        if not self.cfg["client_id"]:
            messagebox.showinfo(
                "Client-ID fehlt",
                "Für den Microsoft-Login brauchst du eine eigene Azure App-ID.\n"
                "Trage sie unter Einstellungen ein (Anleitung in README.md).")
            self._open_settings()
            return
        self._say("[Launcher] Browser wird zur Microsoft-Anmeldung geöffnet …")
        self._run_bg(self._do_login)

    def _do_login(self):
        acc = core.microsoft_login(self.cfg["client_id"], self.cfg["redirect_uri"])
        self.account = acc
        core.save_account(acc)
        self._say(f"[Launcher] Angemeldet als {acc['name']}")
        self.events.put(("account", None))

    def _refresh_login(self):
        try:
            self.account = core.refresh_account(self.cfg["client_id"], self.account)
            core.save_account(self.account)
            self._say(f"[Launcher] Sitzung erneuert für {self.account['name']}")
        except Exception as e:
            self._say(f"[Launcher] Sitzung konnte nicht erneuert werden – bitte neu anmelden ({e})")
            self.account = None
            core.save_account(None)
        self.events.put(("account", None))

    def _play(self):
        if self.busy:
            return
        version = self.version_var.get()
        if not version:
            messagebox.showwarning("Version", "Bitte eine Version auswählen.")
            return
        self.cfg.update(version=version, loader=self.loader_var.get(),
                        show_snapshots=self.snap_var.get())
        core.save_config(self.cfg)
        if not self.account and not messagebox.askyesno(
                "Demo-Modus", "Du bist nicht angemeldet. Im Demo-Modus starten?"):
            return
        self.events.put(("busy", True))
        self._run_bg(self._install_and_launch, version, self.loader_var.get())

    def _install_and_launch(self, version, loader):
        cb = {
            "setStatus": lambda t: self.events.put(("status", t)),
            "setProgress": lambda v: self.events.put(("progress", v)),
            "setMax": lambda v: self.events.put(("max", v)),
        }
        self._say(f"[Launcher] Installiere/prüfe {loader} {version} …")
        version_id = core.install(version, loader, self.cfg["game_dir"], cb)
        cmd = core.build_command(version_id, self.cfg, self.account)
        self._say(f"[Launcher] Starte {version_id} …")
        self.events.put(("status", f"{version_id} läuft."))
        core.launch(cmd, self.cfg["game_dir"], self._say)
        self.events.put(("busy", False))
        if self.cfg.get("close_on_launch"):
            self.events.put(("quit", None))

    # ------------------------------------------------------------ Einstellungen
    def _open_settings(self):
        win = tk.Toplevel(self)
        win.title("Einstellungen")
        win.configure(bg=BG)
        win.transient(self)
        win.grab_set()
        frm = ttk.Frame(win, padding=14)
        frm.pack(fill="both", expand=True)

        fields = {}

        def row(r, label, key, width=40, browse=None):
            ttk.Label(frm, text=label).grid(row=r, column=0, sticky="w", pady=3)
            var = tk.StringVar(value=str(self.cfg.get(key, "")))
            ttk.Entry(frm, textvariable=var, width=width).grid(row=r, column=1, sticky="we", pady=3)
            if browse:
                ttk.Button(frm, text="…", width=3,
                           command=lambda: (p := browse()) and var.set(p)).grid(row=r, column=2, padx=4)
            fields[key] = var

        row(0, "Spielordner", "game_dir", browse=filedialog.askdirectory)
        row(1, "Java-Pfad (leer = automatisch)", "java_path", browse=filedialog.askopenfilename)
        row(2, "Fensterbreite", "width", 8)
        row(3, "Fensterhöhe", "height", 8)
        row(4, "Zusätzliche JVM-Argumente", "extra_jvm_args")
        row(6, "Azure Client-ID", "client_id")
        row(7, "Redirect-URI", "redirect_uri")

        ttk.Label(frm, text="Arbeitsspeicher (MB)").grid(row=5, column=0, sticky="w", pady=3)
        ram = tk.IntVar(value=int(self.cfg["ram_mb"]))
        ram_lbl = ttk.Label(frm, text=f"{ram.get()} MB")
        scale = ttk.Scale(frm, from_=1024, to=16384, variable=ram,
                          command=lambda v: (ram.set(int(float(v)) // 512 * 512),
                                             ram_lbl.config(text=f"{ram.get()} MB")))
        scale.grid(row=5, column=1, sticky="we")
        ram_lbl.grid(row=5, column=2)

        close_var = tk.BooleanVar(value=self.cfg.get("close_on_launch", False))
        ttk.Checkbutton(frm, text="Launcher nach dem Start schließen",
                        variable=close_var).grid(row=8, column=1, sticky="w", pady=6)
        frm.columnconfigure(1, weight=1)

        def save():
            try:
                w, h = int(fields["width"].get()), int(fields["height"].get())
            except ValueError:
                messagebox.showerror("Fehler", "Breite/Höhe müssen Zahlen sein.", parent=win)
                return
            java = fields["java_path"].get().strip()
            if java and not shutil.which(java):
                messagebox.showerror("Fehler", "Java-Pfad nicht gefunden.", parent=win)
                return
            old_dir = self.cfg["game_dir"]
            self.cfg.update({k: v.get().strip() for k, v in fields.items()})
            self.cfg.update(width=w, height=h, ram_mb=ram.get(), close_on_launch=close_var.get())
            core.save_config(self.cfg)
            if self.cfg["game_dir"] != old_dir:
                self._run_bg(self._load_versions)
            win.destroy()

        btns = ttk.Frame(frm)
        btns.grid(row=9, column=0, columnspan=3, sticky="e", pady=(10, 0))
        ttk.Button(btns, text="Abbrechen", command=win.destroy).pack(side="right")
        ttk.Button(btns, text="Speichern", command=save).pack(side="right", padx=6)


if __name__ == "__main__":
    Launcher().mainloop()
