//! Profile: eigene Minecraft-Installationen mit Version, Modloader, Mods und eigenem Spielordner
//! (Welten, Einstellungen und Mods sind pro Profil getrennt).
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::config::base_dir;
use crate::mods::InstalledMod;

#[derive(Serialize, Deserialize, Clone, PartialEq, Debug)]
pub struct Profile {
    pub id: String,
    pub name: String,
    pub version: String,
    pub loader: String,
    #[serde(default)]
    pub mods: Vec<InstalledMod>,
}

fn file() -> PathBuf {
    base_dir().join("profiles.json")
}

pub fn load() -> Vec<Profile> {
    std::fs::read(file()).ok().and_then(|d| serde_json::from_slice(&d).ok()).unwrap_or_default()
}

pub fn save(list: &[Profile]) {
    let _ = std::fs::create_dir_all(base_dir());
    if let Ok(data) = serde_json::to_vec_pretty(list) {
        let tmp = file().with_extension("tmp");
        if std::fs::write(&tmp, data).is_ok() {
            let _ = std::fs::rename(tmp, file());
        }
    }
}

/// Eindeutige, ordnerfreundliche ID aus dem Namen.
pub fn new_id(name: &str, existing: &[Profile]) -> String {
    let mut slug: String = name
        .to_lowercase()
        .chars()
        .map(|c| match c {
            'a'..='z' | '0'..='9' => c.to_string(),
            'ä' => "ae".into(),
            'ö' => "oe".into(),
            'ü' => "ue".into(),
            'ß' => "ss".into(),
            _ => "-".into(),
        })
        .collect();
    slug = slug.split('-').filter(|s| !s.is_empty()).collect::<Vec<_>>().join("-");
    if slug.is_empty() {
        slug = "profil".into();
    }
    let mut id = slug.clone();
    let mut n = 2;
    while existing.iter().any(|p| p.id == id) {
        id = format!("{slug}-{n}");
        n += 1;
    }
    id
}

/// Spielordner des Profils (Welten, Einstellungen, Mods).
pub fn dir(game_dir: &str, p: &Profile) -> PathBuf {
    Path::new(game_dir).join("instances").join(&p.id)
}

pub fn mods_dir(game_dir: &str, p: &Profile) -> PathBuf {
    dir(game_dir, p).join("mods")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ids() {
        let p = |id: &str| Profile { id: id.into(), name: String::new(), version: String::new(), loader: String::new(), mods: vec![] };
        assert_eq!(new_id("Meine Mods!", &[]), "meine-mods");
        assert_eq!(new_id("Größte Welt", &[]), "groesste-welt");
        assert_eq!(new_id("Meine Mods", &[p("meine-mods")]), "meine-mods-2");
        assert_eq!(new_id("???", &[]), "profil");
    }
}
