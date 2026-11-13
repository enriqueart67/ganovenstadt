"""Kernlogik des Ganovenstadt-Launchers (ohne GUI, damit testbar)."""
from __future__ import annotations

import json
import os
import subprocess
import threading
import uuid
import webbrowser
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.parse import urlparse

import minecraft_launcher_lib as mll

LAUNCHER_NAME = "Ganovenstadt-Launcher"
LAUNCHER_VERSION = "1.0"

BASE_DIR = Path.home() / ".ganovenstadt-launcher"
CONFIG_FILE = BASE_DIR / "config.json"
ACCOUNT_FILE = BASE_DIR / "account.json"

LOADERS = ["Vanilla", "Fabric", "Quilt", "Forge", "NeoForge"]

DEFAULT_CONFIG = {
    "game_dir": str(BASE_DIR / "minecraft"),
    "version": "",
    "loader": "Vanilla",
    "show_snapshots": False,
    "ram_mb": 4096,
    "java_path": "",
    "width": 1280,
    "height": 720,
    "extra_jvm_args": "",
    # Microsoft/Azure-App fuer den Login (siehe README)
    "client_id": "",
    "redirect_uri": "http://localhost:8765/callback",
    "close_on_launch": False,
}


# ---------------------------------------------------------------- Einstellungen
def _read_json(path: Path, default):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return default


def _write_json(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, indent=2), encoding="utf-8")
    os.replace(tmp, path)
    try:
        os.chmod(path, 0o600)
    except OSError:
        pass


def load_config() -> dict:
    cfg = dict(DEFAULT_CONFIG)
    cfg.update(_read_json(CONFIG_FILE, {}))
    return cfg


def save_config(cfg: dict) -> None:
    _write_json(CONFIG_FILE, cfg)


def load_account() -> dict | None:
    acc = _read_json(ACCOUNT_FILE, None)
    return acc if isinstance(acc, dict) and acc.get("name") else None


def save_account(acc: dict | None) -> None:
    if acc is None:
        ACCOUNT_FILE.unlink(missing_ok=True)
    else:
        _write_json(ACCOUNT_FILE, acc)


# ---------------------------------------------------------------- Versionen
def get_versions(show_snapshots: bool) -> list[str]:
    allowed = {"release", "snapshot"} if show_snapshots else {"release"}
    return [v["id"] for v in mll.utils.get_version_list() if v["type"] in allowed]


def get_installed_versions(game_dir: str) -> list[str]:
    try:
        return [v["id"] for v in mll.utils.get_installed_versions(game_dir)]
    except OSError:
        return []


def install(version: str, loader: str, game_dir: str, callback: dict) -> str:
    """Installiert Minecraft (+ ggf. Modloader) und liefert die startbare Versions-ID."""
    Path(game_dir).mkdir(parents=True, exist_ok=True)
    if loader == "Vanilla":
        mll.install.install_minecraft_version(version, game_dir, callback=callback)
        return version
    ml = mll.mod_loader.get_mod_loader(loader.lower())
    if not ml.is_minecraft_version_supported(version):
        raise RuntimeError(f"{loader} unterstützt Minecraft {version} nicht.")
    return ml.install(version, game_dir, callback=callback)


def build_command(version_id: str, cfg: dict, account: dict | None) -> list[str]:
    jvm = [f"-Xmx{int(cfg['ram_mb'])}M", f"-Xms{min(int(cfg['ram_mb']), 1024)}M"]
    jvm += [a for a in cfg.get("extra_jvm_args", "").split() if a]
    options: dict = {
        "launcherName": LAUNCHER_NAME,
        "launcherVersion": LAUNCHER_VERSION,
        "gameDirectory": cfg["game_dir"],
        "jvmArguments": jvm,
        "customResolution": True,
        "resolutionWidth": str(cfg["width"]),
        "resolutionHeight": str(cfg["height"]),
    }
    if cfg.get("java_path"):
        options["executablePath"] = cfg["java_path"]
    if account:
        options.update(username=account["name"], uuid=account["id"], token=account["access_token"])
    else:
        # Ohne gekauften Account: offizieller Demo-Modus
        options.update(username="Demo", uuid=str(uuid.uuid4()), token="0", demo=True)
    return mll.command.get_minecraft_command(version_id, cfg["game_dir"], options)


def launch(command: list[str], game_dir: str, on_line) -> subprocess.Popen:
    flags = 0
    if os.name == "nt":
        flags = subprocess.CREATE_NO_WINDOW
    proc = subprocess.Popen(
        command, cwd=game_dir, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, encoding="utf-8", errors="replace", creationflags=flags,
    )

    def pump():
        assert proc.stdout
        for line in proc.stdout:
            on_line(line.rstrip())
        on_line(f"[Launcher] Spiel beendet (Code {proc.wait()})")

    threading.Thread(target=pump, daemon=True).start()
    return proc


# ---------------------------------------------------------------- Microsoft-Login
def _account_from_login(data: dict) -> dict:
    return {
        "name": data["name"],
        "id": data["id"],
        "access_token": data["access_token"],
        "refresh_token": data["refresh_token"],
    }


def microsoft_login(client_id: str, redirect_uri: str, timeout: int = 300) -> dict:
    """Oeffnet den Browser, faengt den Redirect auf localhost ab und meldet an."""
    if not client_id:
        raise RuntimeError("Keine Azure Client-ID eingetragen (Einstellungen → Microsoft-Login).")
    url, state, verifier = mll.microsoft_account.get_secure_login_data(client_id, redirect_uri)
    parsed = urlparse(redirect_uri)
    if parsed.hostname not in ("localhost", "127.0.0.1"):
        raise RuntimeError("Die Redirect-URI muss auf http://localhost:<port>/… zeigen.")

    result: dict = {}

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):  # noqa: N802
            full = f"{parsed.scheme}://{parsed.netloc}{self.path}"
            if mll.microsoft_account.url_contains_auth_code(full):
                result["url"] = full
                body = "<h2>Anmeldung erfolgreich – du kannst dieses Fenster schließen.</h2>"
            else:
                result["error"] = full
                body = "<h2>Anmeldung abgebrochen.</h2>"
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            self.wfile.write(body.encode("utf-8"))

        def log_message(self, *args):
            pass

    server = HTTPServer((parsed.hostname, parsed.port or 80), Handler)
    server.timeout = 1
    webbrowser.open(url)
    try:
        waited = 0
        while not result and waited < timeout:
            server.handle_request()
            waited += 1
    finally:
        server.server_close()

    if "url" not in result:
        raise RuntimeError("Anmeldung abgebrochen oder Zeit abgelaufen.")
    code = mll.microsoft_account.parse_auth_code_url(result["url"], state)
    data = mll.microsoft_account.complete_login(client_id, None, redirect_uri, code, verifier)
    return _account_from_login(data)


def refresh_account(client_id: str, account: dict) -> dict:
    data = mll.microsoft_account.complete_refresh(client_id, None, None, account["refresh_token"])
    return _account_from_login(data)
