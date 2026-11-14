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
pub const MODULES: [(&str, &str, &str); 43] = [
    ("HUD", "FPS", "Frames per second"),
    ("HUD", "CPS", "Clicks per second (left | right)"),
    ("HUD", "Coordinates", "X, Y, Z and facing"),
    ("HUD", "Keystrokes", "W A S D, mouse buttons with CPS, space"),
    ("HUD", "Armor", "Armor and held item with durability"),
    ("HUD", "Potion effects", "Active effects and time left"),
    ("HUD", "Clock", "Real-world time"),
    ("HUD", "Ping", "Latency in ms"),
    ("HUD", "Server address", "The server you are on"),
    ("HUD", "Speed", "Blocks per second"),
    ("HUD", "Day counter", "Minecraft days passed"),
    ("HUD", "Memory", "Memory usage"),
    ("HUD", "Reach", "Distance of your last hit"),
    ("HUD", "Combo counter", "Hits in a row"),
    ("HUD", "Saturation", "Hidden saturation of your hunger bar"),
    ("HUD", "Arrow counter", "Arrows in your inventory"),
    ("HUD", "Totem counter", "Totems of Undying in your inventory"),
    ("HUD", "Biome", "Current biome"),
    ("HUD", "Session time", "Time spent in this session"),
    ("HUD", "Resource packs", "Active resource packs"),
    ("PvP", "Freelook", "Look around without turning (hold Left Alt)"),
    ("HUD", "Minimap", "Top-down map of your surroundings with waypoints"),
    ("World", "Waypoints", "Save places and find them again (M)"),
    ("World", "Time", "Choose the time of day you see"),
    ("PvP", "Low fire", "Fire overlay moved down so you can see"),
    ("Visuals", "Block outline", "Colored outline of the targeted block"),
    ("Misc", "Mention highlight", "Highlights chat messages with your name"),
    ("Misc", "Main menu", "Peregrine's own title screen"),
    ("Misc", "Chat timestamps", "Time in front of chat messages"),
    ("Misc", "Auto-GG", "Sends “gg” when a round ends"),
    ("PvP", "Crosshair", "Custom shape, size and color"),
    ("PvP", "No hurt cam", "No camera shake when hit"),
    ("Visuals", "Zoom", "Hold C, adjustable strength, smooth"),
    ("Visuals", "Fullbright", "Full brightness (G)"),
    ("World", "Weather", "Choose the weather you see"),
    ("Movement", "Auto-Sprint", "Always sprint (V)"),
    ("Movement", "Toggle sneak", "Press once instead of holding"),
    ("Cosmetics", "Cape", "Built-in designs or your own photo, also on the elytra"),
    ("Cosmetics", "Wings", "3D dragon, angel, demon or butterfly wings"),
    ("Cosmetics", "Headwear", "Top hat, crown, glowing halo, horns or cat ears"),
    ("Cosmetics", "Pet", "Parrot, bee, allay, fox, axolotl, frog or copper golem next to you"),
    ("Cosmetics", "Emotes", "16 emotes – hold B, your figure previews the one you aim at"),
    ("Misc", "HUD style", "Colors and background of all HUD elements"),
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
    ("sodium", "Sodium", "modern renderer – usually 2–4× the FPS"),
    ("lithium", "Lithium", "faster game logic (physics, AI, ticks)"),
    ("ferrite-core", "FerriteCore", "lower memory use"),
    ("immediatelyfast", "ImmediatelyFast", "faster HUD, text and item rendering"),
    ("entityculling", "Entity Culling", "skips mobs and chests you cannot see"),
];
/// Tasten der Client-Mod: (Aktion, Anzeigename, Standard-Taste).
pub const KEYS: [(&str, &str, &str); 8] = [
    ("key.peregrine.menu", "Open mod menu", "key.keyboard.right.shift"),
    ("key.peregrine.zoom", "Zoom (hold)", "key.keyboard.c"),
    ("key.peregrine.fullbright", "Toggle fullbright", "key.keyboard.g"),
    ("key.peregrine.sprint", "Toggle auto-sprint", "key.keyboard.v"),
    ("key.peregrine.emote", "Emote wheel", "key.keyboard.b"),
    ("key.peregrine.freelook", "Freelook (hold)", "key.keyboard.left.alt"),
    ("key.peregrine.waypoints", "Waypoints", "key.keyboard.m"),
    ("key.peregrine.assistant", "AI assistant", "key.keyboard.k"),
];

/// Tasten, die man nicht „drücken“ kann, um sie zu erfassen (Umschalt, Strg, Alt, Maustasten).
pub const SPECIAL_KEYS: [(&str, &str); 10] = [
    ("key.keyboard.right.shift", "Right Shift"),
    ("key.keyboard.left.shift", "Left Shift"),
    ("key.keyboard.right.control", "Right Ctrl"),
    ("key.keyboard.left.control", "Left Ctrl"),
    ("key.keyboard.left.alt", "Left Alt"),
    ("key.keyboard.right.alt", "Alt Gr"),
    ("key.mouse.middle", "Middle mouse"),
    ("key.mouse.4", "Mouse 4"),
    ("key.mouse.5", "Mouse 5"),
    ("key.keyboard.caps.lock", "Caps Lock"),
];

/// Lesbarer Name einer Minecraft-Taste ("key.keyboard.c" -> "C").
pub fn key_label(k: &str) -> String {
    if let Some((_, l)) = SPECIAL_KEYS.iter().find(|(id, _)| *id == k) {
        return l.to_string();
    }
    let rest = k.trim_start_matches("key.keyboard.").trim_start_matches("key.mouse.");
    match rest {
        "space" => "Space".into(),
        "tab" => "Tab".into(),
        "enter" => "Enter".into(),
        "backspace" => "Backspace".into(),
        "up" => "Arrow up".into(),
        "down" => "Arrow down".into(),
        "left" => "Arrow left".into(),
        "right" => "Arrow right".into(),
        "unknown" => "none".into(),
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

/// Ordner der eigenen Umhänge (Fotos), wird von der Client-Mod gelesen.
pub fn capes_dir() -> std::path::PathBuf {
    shared_dir().join("capes")
}

/// Namen der hochgeladenen Umhänge.
pub fn custom_capes() -> Vec<String> {
    let mut v: Vec<String> = std::fs::read_dir(capes_dir())
        .into_iter()
        .flatten()
        .flatten()
        .filter_map(|e| {
            let n = e.file_name().to_string_lossy().into_owned();
            n.strip_suffix(".png").map(String::from)
        })
        .collect();
    v.sort_by_key(|n| n.to_lowercase());
    v
}

/// Bild so zuschneiden, dass es das Seitenverhältnis w:h hat (mittig), und skalieren.
fn fit(img: &image::RgbaImage, w: u32, h: u32) -> image::RgbaImage {
    let (iw, ih) = img.dimensions();
    let target = w as f32 / h as f32;
    let (cw, ch) = if iw as f32 / ih as f32 > target {
        (((ih as f32) * target).round().max(1.0) as u32, ih)
    } else {
        (iw, ((iw as f32) / target).round().max(1.0) as u32)
    };
    let crop = image::imageops::crop_imm(img, (iw - cw) / 2, (ih - ch) / 2, cw, ch).to_image();
    image::imageops::resize(&crop, w, h, image::imageops::FilterType::Lanczos3)
}

/// Macht aus einem Foto eine Umhang-Textur (HD, 1024×512 im Minecraft-Umhang-Layout inkl. Elytra)
/// und speichert sie unter capes/<name>.png. Gibt den Namen zurück.
pub fn import_cape(src: &Path) -> Result<String> {
    let img = image::open(src).map_err(|e| anyhow::anyhow!("Could not read the image: {e}"))?.to_rgba8();
    const S: u32 = 16; // 16 Pixel pro Minecraft-Texel
    let mut out = image::RgbaImage::new(64 * S, 32 * S);
    let face = fit(&img, 10 * S, 16 * S);
    // Durchschnittsfarbe für Ränder/Seiten
    let (mut r, mut g, mut b, mut n) = (0u64, 0u64, 0u64, 0u64);
    for p in face.pixels() {
        r += p[0] as u64;
        g += p[1] as u64;
        b += p[2] as u64;
        n += 1;
    }
    let avg = image::Rgba([(r / n.max(1)) as u8, (g / n.max(1)) as u8, (b / n.max(1)) as u8, 255]);
    let rect = |out: &mut image::RgbaImage, x: u32, y: u32, w: u32, h: u32, c: image::Rgba<u8>| {
        for yy in y..y + h {
            for xx in x..x + w {
                out.put_pixel(xx, yy, c);
            }
        }
    };
    // Umhang: Ober-/Unterkante, Seiten, Außen- und Innenseite
    rect(&mut out, S, 0, 20 * S, S, avg);
    rect(&mut out, 0, S, S, 16 * S, avg);
    rect(&mut out, 11 * S, S, S, 16 * S, avg);
    image::imageops::overlay(&mut out, &face, S as i64, S as i64);
    let inner = image::imageops::flip_horizontal(&face);
    image::imageops::overlay(&mut out, &inner, (12 * S) as i64, S as i64);
    // Elytra-Bereich (x 22..46, y 0..22)
    let ely = fit(&img, 24 * S, 22 * S);
    image::imageops::overlay(&mut out, &ely, (22 * S) as i64, 0);

    let stem = src.file_stem().map(|s| s.to_string_lossy().into_owned()).unwrap_or_else(|| "Cape".into());
    let mut base: String = stem.chars().filter(|c| c.is_alphanumeric() || matches!(c, ' ' | '-' | '_')).take(24).collect();
    base = base.trim().to_string();
    if base.is_empty() {
        base = "Cape".into();
    }
    let dir = capes_dir();
    std::fs::create_dir_all(&dir)?;
    let mut name = base.clone();
    let mut i = 2;
    while dir.join(format!("{name}.png")).exists() {
        name = format!("{base} {i}");
        i += 1;
    }
    out.save(dir.join(format!("{name}.png")))?;
    Ok(name)
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
        assert_eq!(key_label("key.keyboard.right.shift"), "Right Shift");
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
    fn cape_import() {
        let tmp = std::env::temp_dir().join(format!("pc-cape-{}", std::process::id()));
        std::fs::create_dir_all(&tmp).unwrap();
        let src = tmp.join("Mein Foto!.png");
        image::RgbaImage::from_pixel(300, 200, image::Rgba([10, 200, 30, 255])).save(&src).unwrap();
        let out = fit(&image::open(&src).unwrap().to_rgba8(), 160, 256);
        assert_eq!(out.dimensions(), (160, 256));
        assert_eq!(out.get_pixel(80, 128)[1], 200);
        let _ = std::fs::remove_dir_all(&tmp);
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
