//! Start über den Prism Launcher.
//!
//! Peregrine bleibt die Oberfläche (Profile, Mods, Client-Mod, Design). Zum Spielen legt Peregrine
//! eine passende Prism-Instanz an, deren Spielordner auf den Peregrine-Spielordner zeigt, und
//! startet sie mit `prismlauncher --launch <id>`. Die Anmeldung übernimmt dabei das Microsoft-Konto,
//! mit dem sich der Nutzer einmal selbst in Prism angemeldet hat – Peregrine sieht keine Tokens.
use std::io::{BufRead, BufReader, Seek, SeekFrom};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::{Duration, Instant, SystemTime};

use anyhow::{bail, Context, Result};
use serde_json::{json, Value};

use crate::config::{base_dir, Config};
use crate::state::Ui;

pub const DOWNLOAD_URL: &str = "https://prismlauncher.org/download/";
const RELEASE_API: &str = "https://api.github.com/repos/PrismLauncher/PrismLauncher/releases/latest";
/// Fallback, falls die GitHub-API nicht erreichbar ist (Rate-Limit o. Ä.).
const FALLBACK_TAG: &str = "9.4";

/// Ordner, in den Peregrine Prism selbst installiert (portable, nur für Peregrine).
pub fn bundled_dir() -> PathBuf {
    base_dir().join("prism")
}

/// Kann Peregrine Prism auf diesem System selbst herunterladen?
pub fn can_install() -> bool {
    cfg!(windows)
}

/// Gefundene Prism-Installation.
#[derive(Clone, Debug, PartialEq)]
pub struct Prism {
    pub exe: PathBuf,
    pub data: PathBuf,
}

impl Prism {
    pub fn instances(&self) -> PathBuf {
        // In prismlauncher.cfg kann ein eigener Instanz-Ordner stehen (relativ oder absolut).
        let cfg = std::fs::read_to_string(self.data.join("prismlauncher.cfg")).unwrap_or_default();
        let dir = ini_value(&cfg, "InstanceDir").filter(|d| !d.is_empty()).unwrap_or_else(|| "instances".into());
        let p = PathBuf::from(&dir);
        if p.is_absolute() { p } else { self.data.join(p) }
    }
}

/// Wert aus einer einfachen INI-Datei (Qt-Format), Anführungszeichen werden entfernt.
fn ini_value(text: &str, key: &str) -> Option<String> {
    text.lines().find_map(|l| {
        let (k, v) = l.split_once('=')?;
        (k.trim() == key).then(|| v.trim().trim_matches('"').to_string())
    })
}

/// Sucht Prism: zuerst der Pfad aus den Einstellungen, dann die üblichen Installationsorte.
pub fn find(custom: &str) -> Option<Prism> {
    let custom = custom.trim().trim_matches('"');
    let mut exes: Vec<PathBuf> = Vec::new();
    if !custom.is_empty() {
        let p = PathBuf::from(custom);
        if p.is_dir() {
            exes.extend(exe_names().iter().map(|n| p.join(n)));
        } else {
            exes.push(p);
        }
    } else {
        let b = bundled_dir();
        exes.extend(exe_names().iter().map(|n| b.join(n)));
        exes.push(b.join("bin").join("prismlauncher"));
        // Unter Windows nur die eigene, versteckte Kopie benutzen (keine fremden Fenster/Einstellungen)
        if !can_install() {
            exes.extend(default_exes());
        }
    }
    let exe = exes.into_iter().find(|p| p.is_file())?;
    Some(Prism { data: data_dir(&exe), exe })
}

fn exe_names() -> &'static [&'static str] {
    if cfg!(windows) {
        &["prismlauncher.exe", "PrismLauncher.exe"]
    } else if cfg!(target_os = "macos") {
        &["Contents/MacOS/prismlauncher", "prismlauncher"]
    } else {
        &["prismlauncher", "PrismLauncher"]
    }
}

fn default_exes() -> Vec<PathBuf> {
    let mut v = Vec::new();
    let env = |k: &str| std::env::var_os(k).map(PathBuf::from);
    if cfg!(windows) {
        if let Some(l) = env("LOCALAPPDATA") {
            v.push(l.join("Programs").join("PrismLauncher").join("prismlauncher.exe"));
        }
        for k in ["ProgramFiles", "ProgramFiles(x86)"] {
            if let Some(p) = env(k) {
                v.push(p.join("PrismLauncher").join("prismlauncher.exe"));
                v.push(p.join("Prism Launcher").join("prismlauncher.exe"));
            }
        }
        if let Some(h) = dirs::home_dir() {
            v.push(h.join("scoop").join("apps").join("prismlauncher").join("current").join("prismlauncher.exe"));
        }
    } else if cfg!(target_os = "macos") {
        v.push(PathBuf::from("/Applications/Prism Launcher.app/Contents/MacOS/prismlauncher"));
        if let Some(h) = dirs::home_dir() {
            v.push(h.join("Applications/Prism Launcher.app/Contents/MacOS/prismlauncher"));
        }
    } else {
        for dir in std::env::var_os("PATH").map(|p| std::env::split_paths(&p).collect::<Vec<_>>()).unwrap_or_default() {
            v.push(dir.join("prismlauncher"));
        }
        v.push(PathBuf::from("/usr/bin/prismlauncher"));
        v.push(PathBuf::from("/var/lib/flatpak/exports/bin/org.prismlauncher.PrismLauncher"));
        if let Some(h) = dirs::home_dir() {
            v.push(h.join(".local/share/flatpak/exports/bin/org.prismlauncher.PrismLauncher"));
        }
    }
    v
}

/// Datenordner von Prism: portable (portable.txt neben der .exe) oder der Standardordner.
fn data_dir(exe: &Path) -> PathBuf {
    // Windows: portable.txt neben der .exe, Linux-Portable: eine Ebene höher (bin/..)
    for dir in exe.ancestors().skip(1).take(2) {
        if dir.join("portable.txt").exists() {
            return dir.to_path_buf();
        }
    }
    let flatpak = exe.to_string_lossy().contains("org.prismlauncher.PrismLauncher");
    if flatpak {
        if let Some(h) = dirs::home_dir() {
            return h.join(".var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher");
        }
    }
    // Windows: %APPDATA%\PrismLauncher, Linux: ~/.local/share/PrismLauncher, macOS: ~/Library/Application Support/PrismLauncher
    dirs::data_dir().unwrap_or_else(|| PathBuf::from(".")).join("PrismLauncher")
}

/// Name des aktiven Microsoft-Kontos in Prism (nur der Name wird gelesen, keine Tokens).
pub fn account_name(p: &Prism) -> Option<String> {
    let v: Value = serde_json::from_slice(&std::fs::read(p.data.join("accounts.json")).ok()?).ok()?;
    let accs = v["accounts"].as_array()?;
    let msa = |a: &&Value| a["type"].as_str().is_none_or(|t| t.eq_ignore_ascii_case("msa"));
    let pick = accs.iter().filter(msa).find(|a| a["active"].as_bool() == Some(true)).or_else(|| accs.iter().find(msa))?;
    pick["profile"]["name"].as_str().filter(|n| !n.is_empty()).map(String::from)
}

fn accounts_stamp(p: &Prism) -> Option<SystemTime> {
    std::fs::metadata(p.data.join("accounts.json")).and_then(|m| m.modified()).ok()
}

/// Öffnet Prism nur zum Anmelden: sobald dort ein Konto hinzugefügt wurde, schließt Peregrine Prism
/// wieder. `on_done` bekommt den Kontonamen (oder `None`, wenn Prism ohne Anmeldung geschlossen wurde).
pub fn login(p: &Prism, ui: Ui, on_done: impl FnOnce(Option<String>) + Send + 'static) -> Result<()> {
    preset(p);
    let before = accounts_stamp(p);
    let had = account_name(p);
    let mut child = command(p).spawn().with_context(|| format!("Anmelde-Komponente konnte nicht gestartet werden: {}", p.exe.display()))?;
    ui.log("[Anmeldung] Anmeldefenster geöffnet: „Microsoft hinzufügen“ wählen und anmelden – danach schließt es sich von selbst.");
    let p = p.clone();
    std::thread::spawn(move || {
        let t0 = Instant::now();
        loop {
            std::thread::sleep(Duration::from_millis(800));
            if let Ok(Some(_)) = child.try_wait() {
                on_done(account_name(&p));
                return;
            }
            let changed = accounts_stamp(&p) != before;
            if changed {
                if let Some(name) = account_name(&p) {
                    // Beim Kontowechsel erst schließen, wenn ein anderes Konto aktiv ist
                    // (Prism schreibt die Datei beim Start auch zum Erneuern der Sitzung).
                    if had.as_deref() != Some(name.as_str()) {
                        // Prism kurz fertig speichern lassen, dann schließen
                        std::thread::sleep(Duration::from_millis(1500));
                        let _ = child.kill();
                        let _ = child.wait();
                        ui.log(format!("[Anmeldung] Angemeldet als {name}."));
                        on_done(Some(name));
                        return;
                    }
                }
            }
            if t0.elapsed() > Duration::from_secs(3600) {
                on_done(account_name(&p));
                return;
            }
        }
    });
    Ok(())
}

/// Grundeinstellungen der eigenen Kopie, damit keine Ersteinrichtung, Update-Abfrage o. Ä. erscheint.
pub fn preset(p: &Prism) {
    if !p.exe.starts_with(bundled_dir()) {
        return;
    }
    let path = p.data.join("prismlauncher.cfg");
    let mut text = std::fs::read_to_string(&path).unwrap_or_default();
    if !text.contains("[General]") {
        text = format!("[General]\n{text}");
    }
    let java = std::fs::read_dir(base_dir().join("runtime"))
        .ok()
        .and_then(|d| d.flatten().map(|e| e.path().join("bin").join(if cfg!(windows) { "javaw.exe" } else { "java" })).find(|p| p.exists()));
    let mut want: Vec<(&str, String)> = vec![
        ("Language", "de".into()),
        ("UseSystemLocale", "false".into()),
        ("ApplicationTheme", "system".into()),
        ("IconTheme", "pe_colored".into()),
        ("AutoUpdate", "false".into()),
        ("AutomaticJavaSwitch", "true".into()),
        ("AutomaticJavaDownload", "true".into()),
        ("PastebinType", "0".into()),
        ("PastebinURL", "https://0x0.st".into()),
        ("ShowConsoleOnError", "false".into()),
        ("ShowConsole", "false".into()),
        ("CloseAfterLaunch", "false".into()),
        ("MenuBarInsteadOfToolBar", "false".into()),
        ("Analytics", "false".into()),
    ];
    if let Some(j) = java {
        want.push(("JavaPath", format!("\"{}\"", j.to_string_lossy().replace('\\', "/"))));
    }
    for (k, v) in want {
        if ini_value(&text, k).is_none() {
            text = text.replacen("[General]\n", &format!("[General]\n{k}={v}\n"), 1);
        }
    }
    let _ = std::fs::write(path, text);
}

/// Lädt den Prism Launcher (portable) von GitHub und entpackt ihn nach `bundled_dir()`.
pub async fn install(ui: &Ui) -> Result<Prism> {
    if !can_install() {
        bail!("Bitte die Anmelde-Komponente von {DOWNLOAD_URL} installieren");
    }
    let client = reqwest::Client::builder()
        .user_agent(concat!("peregrine/", env!("CARGO_PKG_VERSION")))
        .connect_timeout(Duration::from_secs(15))
        .build()?;
    ui.status("Bereite Anmeldung vor …");
    let arm = std::env::consts::ARCH == "aarch64";
    let pick = |name: &str| {
        let n = name.to_lowercase();
        n.ends_with(".zip") && n.contains("portable") && n.contains("windows") && if arm { n.contains("arm64") } else { n.contains("mingw") && !n.contains("arm64") }
    };
    let mut url = None;
    if let Ok(r) = client.get(RELEASE_API).header("Accept", "application/vnd.github+json").send().await {
        if let Ok(v) = r.json::<Value>().await {
            url = v["assets"].as_array().into_iter().flatten().find(|a| pick(a["name"].as_str().unwrap_or(""))).and_then(|a| a["browser_download_url"].as_str()).map(String::from);
        }
    }
    let url = url.unwrap_or_else(|| {
        let kind = if arm { "MSVC-arm64" } else { "MinGW-w64" };
        format!("https://github.com/PrismLauncher/PrismLauncher/releases/download/{FALLBACK_TAG}/PrismLauncher-Windows-{kind}-Portable-{FALLBACK_TAG}.zip")
    });
    ui.log("[Anmeldung] Lade Anmelde-Komponente (einmalig) …");
    ui.status("Bereite Anmeldung vor …");
    let mut resp = client.get(&url).send().await?.error_for_status()?;
    let total = resp.content_length().unwrap_or(0) as usize;
    let mut data = Vec::with_capacity(total);
    ui.progress(0, total.max(1));
    while let Some(chunk) = resp.chunk().await? {
        data.extend_from_slice(&chunk);
        ui.progress(data.len().min(total.max(data.len())), total.max(data.len()));
    }
    ui.status("Bereite Anmeldung vor …");
    let dest = bundled_dir();
    let tmp = dest.with_extension("tmp");
    let _ = std::fs::remove_dir_all(&tmp);
    extract_zip(&data, &tmp)?;
    // Eine einzelne Ordnerebene im Archiv überspringen
    let mut root = tmp.clone();
    if !root.join("prismlauncher.exe").exists() {
        if let Some(sub) = std::fs::read_dir(&tmp)?.flatten().map(|e| e.path()).find(|p| p.join("prismlauncher.exe").exists()) {
            root = sub;
        }
    }
    std::fs::write(root.join("portable.txt"), "")?;
    // Daten (Konto, Instanzen) aus einer früheren Installation behalten
    if dest.exists() {
        for keep in ["accounts.json", "prismlauncher.cfg", "instances", "meta", "libraries", "assets", "java"] {
            let from = dest.join(keep);
            if from.exists() && !root.join(keep).exists() {
                let _ = std::fs::rename(&from, root.join(keep));
            }
        }
        std::fs::remove_dir_all(&dest)?;
    }
    std::fs::rename(&root, &dest)?;
    let _ = std::fs::remove_dir_all(&tmp);
    ui.progress(0, 0);
    find("").filter(|p| p.exe.starts_with(&dest)).context("Anmelde-Komponente unvollständig – bitte erneut versuchen")
}

fn extract_zip(data: &[u8], dest: &Path) -> Result<()> {
    let mut zip = zip::ZipArchive::new(std::io::Cursor::new(data)).context("Download beschädigt – bitte erneut versuchen")?;
    for i in 0..zip.len() {
        let mut e = zip.by_index(i)?;
        let Some(rel) = e.enclosed_name() else { continue };
        let out = dest.join(rel);
        if e.is_dir() {
            std::fs::create_dir_all(&out)?;
            continue;
        }
        if let Some(d) = out.parent() {
            std::fs::create_dir_all(d)?;
        }
        let mut f = std::fs::File::create(&out)?;
        std::io::copy(&mut e, &mut f)?;
    }
    Ok(())
}

fn command(p: &Prism) -> Command {
    let mut c = Command::new(&p.exe);
    c.stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null());
    if let Some(d) = p.exe.parent() {
        c.current_dir(d);
    }
    c
}

/// Eindeutige, dateinamen-sichere Instanz-ID.
pub fn instance_id(key: &str) -> String {
    let clean: String = key
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() || c == '.' || c == '-' { c.to_ascii_lowercase() } else { '-' })
        .collect();
    format!("peregrine-{}", clean.trim_matches('-'))
}

/// Inhalt von mmc-pack.json (Prism löst fehlende Abhängigkeiten wie Intermediary selbst auf).
pub fn pack_json(mc: &str, loader: &str, loader_version: Option<&str>) -> Result<Value> {
    let mut comps = vec![json!({"uid": "net.minecraft", "version": mc, "important": true})];
    let uid = match loader {
        "Vanilla" => None,
        "Fabric" => Some("net.fabricmc.fabric-loader"),
        "Quilt" => Some("org.quiltmc.quilt-loader"),
        other => bail!("Loader {other} wird nicht unterstützt"),
    };
    if let Some(uid) = uid {
        let lv = loader_version.context("Loader-Version fehlt")?;
        comps.push(json!({"uid": uid, "version": lv}));
    }
    Ok(json!({"formatVersion": 1, "components": comps}))
}

/// Inhalt von instance.cfg.
pub fn instance_cfg(name: &str, cfg: &Config, java: Option<&Path>) -> String {
    let esc = |s: &str| s.replace('\\', "\\\\").replace('"', "\\\"");
    let mut out = String::from("[General]\nConfigVersion=1.2\nInstanceType=OneSix\n");
    out += &format!("name=\"{}\"\niconKey=default\n", esc(name));
    match java {
        Some(j) => out += &format!("OverrideJavaLocation=true\nJavaPath=\"{}\"\nAutomaticJava=false\nIgnoreJavaCompatibility=false\n", esc(&j.to_string_lossy())),
        None => out += "OverrideJavaLocation=false\n",
    }
    let jvm = [
        "-XX:+UnlockExperimentalVMOptions", "-XX:+UseG1GC", "-XX:G1NewSizePercent=20",
        "-XX:G1ReservePercent=20", "-XX:MaxGCPauseMillis=50", "-XX:G1HeapRegionSize=32M",
    ]
    .join(" ");
    let jvm = format!("{jvm} {}", cfg.extra_jvm_args.trim());
    out += &format!(
        "OverrideMemory=true\nMaxMemAlloc={}\nMinMemAlloc={}\nOverrideJavaArgs=true\nJvmArgs=\"{}\"\n",
        cfg.ram_mb,
        cfg.ram_mb.min(1024),
        esc(jvm.trim())
    );
    out += "OverrideConsole=true\nShowConsole=false\nAutoCloseConsole=true\nShowConsoleOnError=false\n";
    out += &format!(
        "OverrideWindow=true\nLaunchMaximized=false\nMinecraftWinWidth={}\nMinecraftWinHeight={}\n",
        cfg.width, cfg.height
    );
    out
}

/// Verknüpft `<instanz>/.minecraft` mit dem Peregrine-Spielordner (Windows: Junction, sonst Symlink).
fn link_game_dir(inst: &Path, game: &Path) -> Result<()> {
    let link = inst.join(".minecraft");
    if let Ok(target) = std::fs::read_link(&link) {
        if same_path(&target, game) {
            return Ok(());
        }
    }
    if std::fs::symlink_metadata(&link).is_ok() {
        // Alte Verknüpfung (oder versehentlich ein echter Ordner): nur Verknüpfungen werden entfernt.
        let meta = std::fs::symlink_metadata(&link)?;
        if meta.file_type().is_symlink() || is_junction(&link) {
            remove_link(&link)?;
        } else {
            bail!("{} ist ein echter Ordner – bitte umbenennen oder löschen", link.display());
        }
    }
    std::fs::create_dir_all(game)?;
    #[cfg(windows)]
    junction::create(game, &link)
        .with_context(|| format!("Verknüpfung {} → {} konnte nicht erstellt werden", link.display(), game.display()))?;
    #[cfg(unix)]
    std::os::unix::fs::symlink(game, &link)?;
    Ok(())
}

fn same_path(a: &Path, b: &Path) -> bool {
    let norm = |p: &Path| {
        let s = std::fs::canonicalize(p).unwrap_or_else(|_| p.to_path_buf()).to_string_lossy().into_owned();
        let s = s.trim_start_matches(r"\\?\").trim_start_matches(r"\??\").trim_end_matches(['/', '\\']).to_string();
        if cfg!(windows) { s.to_lowercase() } else { s }
    };
    norm(a) == norm(b)
}

#[cfg(windows)]
fn is_junction(p: &Path) -> bool {
    use std::os::windows::fs::MetadataExt;
    std::fs::symlink_metadata(p).is_ok_and(|m| m.file_attributes() & 0x400 != 0) // REPARSE_POINT
}
#[cfg(not(windows))]
fn is_junction(_: &Path) -> bool {
    false
}

fn remove_link(p: &Path) -> Result<()> {
    // Junctions/Verzeichnis-Symlinks unter Windows sind Ordner-Einträge, unter Unix Dateien.
    if std::fs::remove_file(p).is_err() {
        std::fs::remove_dir(p)?;
    }
    Ok(())
}

/// Legt die Prism-Instanz an bzw. aktualisiert sie.
pub fn write_instance(p: &Prism, id: &str, name: &str, game: &Path, pack: &Value, cfg: &Config, java: Option<&Path>) -> Result<PathBuf> {
    let inst = p.instances().join(id);
    std::fs::create_dir_all(&inst).with_context(|| format!("Ordner {} nicht beschreibbar", inst.display()))?;
    std::fs::write(inst.join("instance.cfg"), instance_cfg(name, cfg, java))?;
    std::fs::write(inst.join("mmc-pack.json"), serde_json::to_vec_pretty(pack)?)?;
    link_game_dir(&inst, game)?;
    Ok(inst)
}

/// Startet die Instanz und liest das Spiel-Log mit, bis das Spiel beendet ist.
pub fn launch(p: &Prism, id: &str, game: &Path, ui: Ui) -> Result<()> {
    let started = SystemTime::now();
    let mut cmd = command(p);
    cmd.arg("--launch").arg(id);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x0800_0000);
    }
    let mut child = cmd.spawn().with_context(|| format!("Anmelde-Komponente konnte nicht gestartet werden: {}", p.exe.display()))?;
    let log = game.join("logs").join("latest.log");
    std::thread::spawn(move || {
        let t0 = Instant::now();
        let mut pos: Option<u64> = None;
        let mut prism_done = false;
        let mut last_data = Instant::now();
        loop {
            if !prism_done {
                if let Ok(Some(_)) = child.try_wait() {
                    prism_done = true;
                    // Lief Prism schon, gibt der neue Prozess den Auftrag sofort ab und beendet sich.
                    if t0.elapsed() > Duration::from_secs(15) && pos.is_some() {
                        break;
                    }
                }
            }
            // Neues latest.log (vom aktuellen Start) mitlesen
            if pos.is_none() {
                if let Ok(m) = std::fs::metadata(&log) {
                    if m.modified().is_ok_and(|t| t >= started) {
                        pos = Some(0);
                        ui.log("[Launcher] Minecraft startet …");
                    }
                }
            }
            if let Some(off) = pos.as_mut() {
                if let Ok(mut f) = std::fs::File::open(&log) {
                    let len = f.metadata().map(|m| m.len()).unwrap_or(0);
                    if len < *off {
                        *off = 0;
                    }
                    if len > *off && f.seek(SeekFrom::Start(*off)).is_ok() {
                        let mut r = BufReader::new(f);
                        let mut line = String::new();
                        while r.read_line(&mut line).unwrap_or(0) > 0 {
                            if !line.ends_with('\n') {
                                break; // unvollständige Zeile: beim nächsten Mal
                            }
                            *off += line.len() as u64;
                            let l = line.trim_end().to_string();
                            let stop = l.contains("Stopping!");
                            ui.log(l);
                            line.clear();
                            last_data = Instant::now();
                            if stop {
                                ui.log("[Launcher] Spiel beendet");
                                ui.status("Bereit.");
                                return;
                            }
                        }
                    }
                }
            }
            if prism_done && pos.is_none() && t0.elapsed() > Duration::from_secs(120) {
                ui.log("[Launcher] Kein Spielstart erkannt.");
                break;
            }
            if pos.is_some() && last_data.elapsed() > Duration::from_secs(6 * 3600) {
                break;
            }
            std::thread::sleep(Duration::from_millis(300));
        }
        ui.log("[Launcher] Spiel beendet");
        ui.status("Bereit.");
    });
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ids_and_pack() {
        assert_eq!(instance_id("Fabric 26.2 / Mein Profil"), "peregrine-fabric-26.2---mein-profil");
        let p = pack_json("26.2", "Fabric", Some("0.18.1")).unwrap();
        assert_eq!(p["components"][0]["uid"], "net.minecraft");
        assert_eq!(p["components"][1]["uid"], "net.fabricmc.fabric-loader");
        assert_eq!(pack_json("1.8.9", "Vanilla", None).unwrap()["components"].as_array().unwrap().len(), 1);
        assert!(pack_json("26.2", "Forge", Some("1")).is_err());
    }

    #[test]
    fn cfg_and_ini() {
        let cfg = Config { ram_mb: 6144, ..Default::default() };
        let s = instance_cfg("Peregrine · 26.2", &cfg, Some(Path::new("C:\\java\\bin\\javaw.exe")));
        assert!(s.contains("MaxMemAlloc=6144"));
        assert!(s.contains("JavaPath=\"C:\\\\java\\\\bin\\\\javaw.exe\""));
        assert!(s.contains("ShowConsole=false"));
        assert_eq!(ini_value("[General]\nInstanceDir=\"D:/mc\"\n", "InstanceDir").as_deref(), Some("D:/mc"));
    }

    #[test]
    fn zip_extract() {
        use std::io::Write;
        let mut buf = std::io::Cursor::new(Vec::new());
        {
            let mut z = zip::ZipWriter::new(&mut buf);
            let o = zip::write::SimpleFileOptions::default();
            z.start_file("prismlauncher.exe", o).unwrap();
            z.write_all(b"x").unwrap();
            z.start_file("platforms/qwindows.dll", o).unwrap();
            z.write_all(b"y").unwrap();
            z.finish().unwrap();
        }
        let tmp = std::env::temp_dir().join(format!("peregrine-zip-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&tmp);
        extract_zip(buf.get_ref(), &tmp).unwrap();
        assert!(tmp.join("prismlauncher.exe").exists() && tmp.join("platforms/qwindows.dll").exists());
        let _ = std::fs::remove_dir_all(&tmp);
    }

    #[test]
    fn instance_and_account() {
        let tmp = std::env::temp_dir().join(format!("peregrine-prism-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&tmp);
        let data = tmp.join("data");
        std::fs::create_dir_all(&data).unwrap();
        std::fs::write(
            data.join("accounts.json"),
            r#"{"formatVersion":3,"accounts":[{"type":"MSA","profile":{"name":"Falke","id":"x"},"active":true}]}"#,
        )
        .unwrap();
        let p = Prism { exe: tmp.join("prismlauncher"), data: data.clone() };
        assert_eq!(account_name(&p).as_deref(), Some("Falke"));
        let game = tmp.join("game");
        let pack = pack_json("26.2", "Fabric", Some("0.18.1")).unwrap();
        let cfg = Config::default();
        let inst = write_instance(&p, "peregrine-test", "Test", &game, &pack, &cfg, None).unwrap();
        assert!(inst.join("instance.cfg").exists());
        assert!(same_path(&std::fs::read_link(inst.join(".minecraft")).unwrap(), &game));
        // zweiter Aufruf mit anderem Ziel ersetzt die Verknüpfung
        let game2 = tmp.join("game2");
        write_instance(&p, "peregrine-test", "Test", &game2, &pack, &cfg, None).unwrap();
        assert!(same_path(&std::fs::read_link(inst.join(".minecraft")).unwrap(), &game2));
        let _ = std::fs::remove_dir_all(&tmp);
    }
}
