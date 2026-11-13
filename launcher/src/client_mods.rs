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
pub const MODULES: [(&str, &str, &str); 24] = [
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
    ("Cosmetics", "Umhang", "8 eigene Umhänge, auch auf der Elytra (F5 zum Ansehen)"),
    ("Cosmetics", "Emotes", "Winken, Dab, Klatschen, Tanzen u. a. – Rad mit Taste B"),
    ("Sonstiges", "HUD-Stil", "Farben und Hintergrund aller Anzeigen"),
];

#[derive(Serialize, Deserialize, Clone, PartialEq, Debug)]
#[serde(default)]
pub struct ClientMods {
    /// Client-Mods ueberhaupt installieren.
    pub enabled: bool,
    /// Leistungs-Mods (Sodium & Co.) automatisch installieren.
    pub turbo: bool,
    /// Einzelne Leistungs-Mods, die der Nutzer abgeschaltet hat (Slugs).
    pub turbo_off: Vec<String>,
    /// Im Launcher geänderte Tasten: Aktion (z. B. "key.peregrine.zoom") -> Minecraft-Tastenname.
    pub keys: std::collections::BTreeMap<String, String>,
}

impl Default for ClientMods {
    fn default() -> Self {
        Self { enabled: true, turbo: true, turbo_off: Vec::new(), keys: Default::default() }
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
/// Tasten der Client-Mod: (Aktion, Anzeigename, Standard-Taste).
pub const KEYS: [(&str, &str, &str); 5] = [
    ("key.peregrine.menu", "Mod-Menü öffnen", "key.keyboard.right.shift"),
    ("key.peregrine.zoom", "Zoom (halten)", "key.keyboard.c"),
    ("key.peregrine.fullbright", "Fullbright an/aus", "key.keyboard.g"),
    ("key.peregrine.sprint", "Auto-Sprint an/aus", "key.keyboard.v"),
    ("key.peregrine.emote", "Emote-Rad", "key.keyboard.b"),
];

/// Tasten, die man nicht „drücken“ kann, um sie zu erfassen (Umschalt, Strg, Alt, Maustasten).
pub const SPECIAL_KEYS: [(&str, &str); 10] = [
    ("key.keyboard.right.shift", "Rechts-Shift"),
    ("key.keyboard.left.shift", "Links-Shift"),
    ("key.keyboard.right.control", "Rechts-Strg"),
    ("key.keyboard.left.control", "Links-Strg"),
    ("key.keyboard.left.alt", "Links-Alt"),
    ("key.keyboard.right.alt", "Alt Gr"),
    ("key.mouse.middle", "Mittlere Maustaste"),
    ("key.mouse.4", "Maustaste 4"),
    ("key.mouse.5", "Maustaste 5"),
    ("key.keyboard.caps.lock", "Feststelltaste"),
];

/// Lesbarer Name einer Minecraft-Taste ("key.keyboard.c" -> "C").
pub fn key_label(k: &str) -> String {
    if let Some((_, l)) = SPECIAL_KEYS.iter().find(|(id, _)| *id == k) {
        return l.to_string();
    }
    let rest = k.trim_start_matches("key.keyboard.").trim_start_matches("key.mouse.");
    match rest {
        "space" => "Leertaste".into(),
        "tab" => "Tab".into(),
        "enter" => "Enter".into(),
        "backspace" => "Rücktaste".into(),
        "up" => "Pfeil hoch".into(),
        "down" => "Pfeil runter".into(),
        "left" => "Pfeil links".into(),
        "right" => "Pfeil rechts".into(),
        "unknown" => "keine".into(),
        r => r.replace('.', " ").to_uppercase(),
    }
}

/// Minecraft-Tastenname zu einer egui-Taste.
pub fn key_from_egui(name: &str) -> Option<String> {
    let n = name.to_lowercase();
    let mc = match n.as_str() {
        n if n.len() == 1 && n.chars().all(|c| c.is_ascii_alphanumeric()) => n.to_string(),
        n if n.starts_with('f') && n[1..].parse::<u8>().is_ok_and(|x| (1..=25).contains(&x)) => n.to_string(),
        n if n.starts_with("num") && n.len() == 4 => n[3..].to_string(),
        "space" => "space".into(),
        "tab" => "tab".into(),
        "enter" => "enter".into(),
        "backspace" => "backspace".into(),
        "insert" => "insert".into(),
        "delete" => "delete".into(),
        "home" => "home".into(),
        "end" => "end".into(),
        "pageup" => "page.up".into(),
        "pagedown" => "page.down".into(),
        "arrowup" => "up".into(),
        "arrowdown" => "down".into(),
        "arrowleft" => "left".into(),
        "arrowright" => "right".into(),
        "minus" => "minus".into(),
        "plus" | "equals" => "equal".into(),
        "comma" => "comma".into(),
        "period" => "period".into(),
        "slash" => "slash".into(),
        "backslash" => "backslash".into(),
        "semicolon" => "semicolon".into(),
        "openbracket" => "left.bracket".into(),
        "closebracket" => "right.bracket".into(),
        "backtick" => "grave.accent".into(),
        "quote" => "apostrophe".into(),
        _ => return None,
    };
    Some(format!("key.keyboard.{mc}"))
}

/// Im Launcher gewählte Tasten in options.txt des Spielordners eintragen (nur geänderte).
/// Fehlt options.txt (allererster Start), gelten bis zum nächsten Start die Standard-Tasten.
pub fn apply_keys(run_dir: &Path, keys: &std::collections::BTreeMap<String, String>) -> Result<bool> {
    let path = run_dir.join("options.txt");
    let Ok(text) = std::fs::read_to_string(&path) else { return Ok(false) };
    let mut lines: Vec<String> = text.lines().map(String::from).collect();
    let mut changed = false;
    for (action, key) in keys {
        let prefix = format!("key_{action}:");
        let want = format!("{prefix}{key}");
        match lines.iter_mut().find(|l| l.starts_with(&prefix)) {
            Some(l) if *l == want => {}
            Some(l) => {
                *l = want;
                changed = true;
            }
            None => {
                lines.push(want);
                changed = true;
            }
        }
    }
    if changed {
        std::fs::write(&path, lines.join("\n") + "\n")?;
    }
    Ok(true)
}

/// Gemeinsamer Ordner der Client-Mod (Einstellungen für alle Profile, Design aus dem Launcher).
pub fn shared_dir() -> std::path::PathBuf {
    crate::config::base_dir().join("client")
}

/// Design des Launchers für das Mod-Menü im Spiel (Farben als [r, g, b]).
pub fn write_theme(pal: &crate::theme::Pal) -> Result<()> {
    let c = |x: eframe::egui::Color32| [x.r(), x.g(), x.b()];
    let v = serde_json::json!({
        "bg": c(pal.bg), "surface": c(pal.surface), "surface2": c(pal.surface2), "border": c(pal.border),
        "text": c(pal.text), "muted": c(pal.muted), "accent": c(pal.accent), "onAccent": c(pal.on_accent),
        "light": pal.light,
    });
    let dir = shared_dir();
    std::fs::create_dir_all(&dir)?;
    write_if_changed(&dir.join("theme.json"), &serde_json::to_vec_pretty(&v)?)
}

/// JVM-Argument, mit dem die Client-Mod den gemeinsamen Ordner findet.
pub fn jvm_arg() -> String {
    format!("-Dperegrine.home={}", shared_dir().display())
}

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
    fn keys() {
        assert_eq!(key_from_egui("C").as_deref(), Some("key.keyboard.c"));
        assert_eq!(key_from_egui("F5").as_deref(), Some("key.keyboard.f5"));
        assert_eq!(key_from_egui("ArrowUp").as_deref(), Some("key.keyboard.up"));
        assert_eq!(key_label("key.keyboard.right.shift"), "Rechts-Shift");
        assert_eq!(key_label("key.keyboard.c"), "C");
        let dir = std::env::temp_dir().join(format!("pc-keys-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let mut m = std::collections::BTreeMap::new();
        m.insert("key.peregrine.zoom".to_string(), "key.keyboard.x".to_string());
        assert!(!apply_keys(&dir, &m).unwrap());
        std::fs::write(dir.join("options.txt"), "version:4000\nkey_key.peregrine.zoom:key.keyboard.c\nfov:0.0\n").unwrap();
        assert!(apply_keys(&dir, &m).unwrap());
        let t = std::fs::read_to_string(dir.join("options.txt")).unwrap();
        assert!(t.contains("key_key.peregrine.zoom:key.keyboard.x") && t.contains("fov:0.0") && !t.contains("keyboard.c"));
        let _ = std::fs::remove_dir_all(&dir);
    }

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
