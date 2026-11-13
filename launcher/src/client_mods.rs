//! Eingebaute Client-Mods (eigene Fabric-Mod „Peregrine Client“) mit Mod-Menue im Spiel (Rechts-Shift).
//! Der Launcher legt nur die passende Mod-Datei vor dem Start in den Mods-Ordner (oder entfernt sie)
//! und installiert bei Bedarf die Fabric API. Alle Einstellungen macht man im Spiel – sie liegen in
//! config/peregrine-client.json des Profils.
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

/// Module der Mod (fuer die Uebersicht im Launcher): (Kategorie, Name, Beschreibung).
pub const MODULES: [(&str, &str, &str); 22] = [
    ("HUD", "FPS", "Bilder pro Sekunde"),
    ("HUD", "CPS", "Klicks pro Sekunde (links | rechts)"),
    ("HUD", "Koordinaten", "X, Y, Z und Himmelsrichtung"),
    ("HUD", "Keystrokes", "W A S D, Maustasten mit CPS, Leertaste"),
    ("HUD", "Rüstung", "Rüstung und Waffe mit Haltbarkeit"),
    ("HUD", "Trank-Effekte", "Aktive Effekte mit Restzeit"),
    ("HUD", "Uhrzeit", "Echte Uhrzeit"),
    ("HUD", "Ping", "Verbindung zum Server in ms"),
    ("HUD", "Server-Adresse", "Auf welchem Server du bist"),
    ("HUD", "Geschwindigkeit", "Blöcke pro Sekunde"),
    ("HUD", "Tageszähler", "Vergangene Minecraft-Tage"),
    ("HUD", "Arbeitsspeicher", "RAM-Verbrauch"),
    ("HUD", "Reichweite", "Abstand deines letzten Treffers"),
    ("HUD", "Combo-Zähler", "Treffer in Folge"),
    ("PvP", "Fadenkreuz", "Eigene Form, Größe, Farbe"),
    ("PvP", "Kein Schadens-Wackeln", "Kamera wackelt nicht bei Treffern"),
    ("Grafik", "Zoom", "Taste C, Stärke einstellbar, weich"),
    ("Grafik", "Fullbright", "Alles hell (Taste G)"),
    ("Grafik", "Kein Regen", "Regen/Gewitter nur bei dir aus"),
    ("Bewegung", "Auto-Sprint", "Automatisch sprinten (Taste V)"),
    ("Bewegung", "Schleichen umschalten", "Einmal drücken statt halten"),
    ("Sonstiges", "HUD-Stil", "Farben und Hintergrund aller Anzeigen"),
];

#[derive(Serialize, Deserialize, Clone, PartialEq, Debug)]
#[serde(default)]
pub struct ClientMods {
    /// Client-Mods ueberhaupt installieren.
    pub enabled: bool,
    /// Leistungs-Mods (Sodium & Co.) automatisch installieren.
    pub turbo: bool,
}

impl Default for ClientMods {
    fn default() -> Self {
        Self { enabled: true, turbo: true }
    }
}

/// Leistungs-Mods von Modrinth: (Slug, Name, Wirkung).
pub const TURBO: [(&str, &str, &str); 5] = [
    ("sodium", "Sodium", "neuer Grafik-Motor – meist 2–4× mehr FPS"),
    ("lithium", "Lithium", "schnellere Spiel-Logik (Physik, KI, Ticks)"),
    ("ferrite-core", "FerriteCore", "weniger Arbeitsspeicher"),
    ("immediatelyfast", "ImmediatelyFast", "schnelleres Zeichnen von HUD, Text und Items"),
    ("entityculling", "Entity Culling", "verdeckte Tiere/Truhen werden nicht gezeichnet"),
];
pub const TURBO_MARKER: &str = ".peregrine-turbo.json";

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

/// Mod-Datei passend zur Version in `run_dir/mods` legen (oder entfernen).
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
    fn prepare_installs_and_removes() {
        let dir = std::env::temp_dir().join(format!("pc-cm-{}", std::process::id()));
        let mut cm = ClientMods::default();
        assert!(matches!(prepare(&dir, "26.2", "Fabric", &cm).unwrap(), Prepared::Ready { needs_fabric_api: true }));
        assert!(dir.join("mods").join(JAR_NAME).exists());
        assert!(!dir.join("config/peregrine-client.json").exists(), "Launcher darf die Einstellungen nicht ueberschreiben");
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
