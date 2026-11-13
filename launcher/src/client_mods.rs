//! Eingebaute Client-Mods (eigene Fabric-Mod „Peregrine Client“): FPS, CPS, Koordinaten, Keystrokes,
//! eigenes Fadenkreuz, Zoom, Fullbright, Auto-Sprint. Der Launcher legt die passende Mod-Datei vor dem
//! Start in den Mods-Ordner und schreibt die Einstellungen nach config/peregrine-client.json –
//! die Mod laedt Aenderungen auch waehrend des Spiels automatisch neu.
use std::path::Path;

use anyhow::Result;
use serde::{Deserialize, Serialize};

/// Mod-Dateien je Minecraft-Versionsreihe (gebaut mit client-mod/build-all.sh).
const JARS: [(&str, &[u8]); 3] = [
    ("26.1", include_bytes!("../assets/client-mod/peregrine-client-26.1.jar")),
    ("26.2", include_bytes!("../assets/client-mod/peregrine-client-26.2.jar")),
    ("26.3", include_bytes!("../assets/client-mod/peregrine-client-26.3.jar")),
];
const JAR_NAME: &str = "peregrine-client.jar";
pub const FABRIC_API: &str = "P7dR8mSH";

pub fn supported() -> Vec<&'static str> {
    JARS.iter().map(|(v, _)| *v).collect()
}

fn series(version: &str) -> String {
    version.split(['.', '-', ' ']).take(2).collect::<Vec<_>>().join(".")
}

pub fn jar_for(version: &str) -> Option<&'static [u8]> {
    let s = series(version);
    JARS.iter().find(|(v, _)| *v == s).map(|(_, b)| *b)
}

#[derive(Serialize, Deserialize, Clone, PartialEq, Debug)]
#[serde(default)]
pub struct Crosshair {
    pub enabled: bool,
    pub style: String,
    pub size: i32,
    pub gap: i32,
    pub thickness: i32,
    pub color: String,
    pub outline: bool,
    pub dot: bool,
}

impl Default for Crosshair {
    fn default() -> Self {
        Self { enabled: true, style: "cross".into(), size: 5, gap: 2, thickness: 1, color: "#FFFFFF".into(), outline: true, dot: false }
    }
}

#[derive(Serialize, Deserialize, Clone, PartialEq, Debug)]
#[serde(default)]
pub struct ClientMods {
    /// Nur fuer den Launcher: Client-Mods ueberhaupt installieren.
    pub enabled: bool,
    pub fps: bool,
    pub cps: bool,
    pub coords: bool,
    pub direction: bool,
    pub clock: bool,
    pub keystrokes: bool,
    pub background: bool,
    pub shadow: bool,
    pub hud_position: String,
    pub keys_position: String,
    pub text_color: String,
    pub accent_color: String,
    pub crosshair: Crosshair,
    pub zoom: bool,
    pub zoom_factor: f64,
    pub fullbright: bool,
    pub auto_sprint: bool,
}

impl Default for ClientMods {
    fn default() -> Self {
        Self {
            enabled: true,
            fps: true,
            cps: true,
            coords: true,
            direction: true,
            clock: false,
            keystrokes: true,
            background: true,
            shadow: true,
            hud_position: "top_left".into(),
            keys_position: "bottom_right".into(),
            text_color: "#FFFFFF".into(),
            accent_color: "#4F8CFF".into(),
            crosshair: Crosshair::default(),
            zoom: true,
            zoom_factor: 4.0,
            fullbright: false,
            auto_sprint: false,
        }
    }
}

/// Ergebnis der Vorbereitung vor dem Spielstart.
pub enum Prepared {
    /// Nichts zu tun (Vanilla oder ausgeschaltet).
    Off,
    /// Mod installiert; `needs_fabric_api` = Fabric API fehlt noch.
    Ready { needs_fabric_api: bool },
    /// Fuer diese Minecraft-Version gibt es (noch) keine Client-Mod.
    Unsupported,
}

fn write_if_changed(path: &Path, data: &[u8]) -> Result<()> {
    if std::fs::read(path).ok().as_deref() != Some(data) {
        if let Some(d) = path.parent() {
            std::fs::create_dir_all(d)?;
        }
        std::fs::write(path, data)?;
    }
    Ok(())
}

/// Einstellungen in den Spielordner schreiben (wirkt auch im laufenden Spiel).
pub fn write_config(run_dir: &Path, cm: &ClientMods) -> Result<()> {
    write_if_changed(&run_dir.join("config").join("peregrine-client.json"), &serde_json::to_vec_pretty(cm)?)
}

/// Mod-Datei passend zur Version in `run_dir/mods` legen (oder entfernen) und Einstellungen schreiben.
pub fn prepare(run_dir: &Path, version: &str, loader: &str, cm: &ClientMods) -> Result<Prepared> {
    let mods = run_dir.join("mods");
    let jar = mods.join(JAR_NAME);
    if loader == "Vanilla" {
        return Ok(Prepared::Off);
    }
    let bytes = jar_for(version);
    if !cm.enabled || bytes.is_none() {
        let _ = std::fs::remove_file(&jar);
        return Ok(if cm.enabled { Prepared::Unsupported } else { Prepared::Off });
    }
    write_if_changed(&jar, bytes.unwrap())?;
    write_config(run_dir, cm)?;
    let has_api = std::fs::read_dir(&mods)
        .into_iter()
        .flatten()
        .flatten()
        .any(|e| {
            let n = e.file_name().to_string_lossy().to_lowercase();
            n.starts_with("fabric-api") && n.ends_with(".jar")
        });
    Ok(Prepared::Ready { needs_fabric_api: !has_api })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn versions() {
        assert!(jar_for("26.1").is_some());
        assert!(jar_for("26.1.2").is_some());
        assert!(jar_for("26.3").is_some());
        assert!(jar_for("26.3-snapshot-2").is_some());
        assert!(jar_for("1.21.4").is_none());
    }

    #[test]
    fn json_matches_mod() {
        let v = serde_json::to_value(ClientMods::default()).unwrap();
        // Feldnamen muessen zur Mod passen (Gson, LOWER_CASE_WITH_UNDERSCORES)
        for k in ["fps", "hud_position", "keys_position", "accent_color", "zoom_factor", "auto_sprint", "crosshair"] {
            assert!(v.get(k).is_some(), "{k}");
        }
        assert!(v["crosshair"].get("thickness").is_some());
    }

    #[test]
    fn prepare_installs_and_removes() {
        let dir = std::env::temp_dir().join(format!("pc-cm-{}", std::process::id()));
        let mut cm = ClientMods::default();
        assert!(matches!(prepare(&dir, "26.2", "Fabric", &cm).unwrap(), Prepared::Ready { needs_fabric_api: true }));
        assert!(dir.join("mods").join(JAR_NAME).exists());
        assert!(dir.join("config/peregrine-client.json").exists());
        std::fs::write(dir.join("mods/fabric-api-0.161.0+26.2.jar"), b"x").unwrap();
        assert!(matches!(prepare(&dir, "26.2", "Fabric", &cm).unwrap(), Prepared::Ready { needs_fabric_api: false }));
        assert!(matches!(prepare(&dir, "1.21.4", "Fabric", &cm).unwrap(), Prepared::Unsupported));
        assert!(!dir.join("mods").join(JAR_NAME).exists());
        cm.enabled = false;
        prepare(&dir, "26.2", "Fabric", &cm).unwrap();
        assert!(!dir.join("mods").join(JAR_NAME).exists());
        let _ = std::fs::remove_dir_all(dir);
    }
}
