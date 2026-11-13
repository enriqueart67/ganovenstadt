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

use crate::config::Config;
use crate::state::Ui;

pub const DOWNLOAD_URL: &str = "https://prismlauncher.org/download/";

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
        exes.extend(default_exes());
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

/// Öffnet Prism normal (z. B. zum Anmelden).
pub fn open(p: &Prism) -> Result<()> {
    command(p).spawn().with_context(|| format!("Prism konnte nicht gestartet werden: {}", p.exe.display()))?;
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
        other => bail!("Loader {other} wird über Prism nicht unterstützt"),
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
    out += "OverrideConsole=true\nShowConsole=false\nAutoCloseConsole=true\nShowConsoleOnError=true\n";
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
    std::fs::create_dir_all(&inst).with_context(|| format!("Prism-Instanzordner {} nicht beschreibbar", inst.display()))?;
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
    let mut child = cmd.spawn().with_context(|| format!("Prism konnte nicht gestartet werden: {}", p.exe.display()))?;
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
                        ui.log("[Prism] Minecraft startet …");
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
                ui.log("[Prism] Kein Spielstart erkannt – Details im Prism-Fenster.");
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
