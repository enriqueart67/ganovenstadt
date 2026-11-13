//! Anmeldung ueber den Prism Launcher uebernehmen: Man meldet sich einmal in Prism mit dem
//! Microsoft-Konto an, der Peregrine Client liest das Minecraft-Konto (Name, UUID, Token) aus
//! Prisms `accounts.json`. Vor jedem Start wird die Datei neu gelesen (Prism erneuert den Token selbst).
use std::path::{Path, PathBuf};

use anyhow::{bail, Context, Result};
use serde_json::Value;

use crate::config::Account;

/// Typische Speicherorte von Prisms accounts.json.
pub fn default_paths() -> Vec<PathBuf> {
    let mut v = Vec::new();
    if let Some(d) = dirs::data_dir() {
        // Windows: %APPDATA%\PrismLauncher, macOS: ~/Library/Application Support/PrismLauncher, Linux: ~/.local/share/PrismLauncher
        v.push(d.join("PrismLauncher").join("accounts.json"));
    }
    if let Some(c) = dirs::config_dir() {
        v.push(c.join("PrismLauncher").join("accounts.json"));
    }
    if let Some(h) = dirs::home_dir() {
        v.push(h.join(".var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/accounts.json")); // Flatpak
        v.push(h.join("scoop/persist/prismlauncher/accounts.json"));
    }
    v
}

/// accounts.json finden – eigener Pfad (Datei oder Prism-Ordner) hat Vorrang.
pub fn find(custom: &str) -> Option<PathBuf> {
    let custom = custom.trim();
    if !custom.is_empty() {
        let p = PathBuf::from(custom);
        let p = if p.is_dir() { p.join("accounts.json") } else { p };
        return p.exists().then_some(p);
    }
    default_paths().into_iter().find(|p| p.exists())
}

/// Ein Minecraft-Konto aus Prism.
#[derive(Debug, Clone, PartialEq)]
pub struct PrismAccount {
    pub name: String,
    pub id: String,
    pub token: String,
    pub active: bool,
}

/// Alle Microsoft-Konten mit Minecraft-Profil aus Prisms accounts.json.
pub fn read(path: &Path) -> Result<Vec<PrismAccount>> {
    let data = std::fs::read(path).with_context(|| format!("{} kann nicht gelesen werden", path.display()))?;
    let v: Value = serde_json::from_slice(&data).context("accounts.json ist kein gültiges JSON")?;
    let mut out = Vec::new();
    for a in v["accounts"].as_array().into_iter().flatten() {
        let ty = a["type"].as_str().unwrap_or("MSA");
        if !ty.eq_ignore_ascii_case("MSA") {
            continue; // Offline-Konten u. a. ueberspringen
        }
        let name = a["profile"]["name"].as_str().unwrap_or("");
        let id = a["profile"]["id"].as_str().unwrap_or("");
        let token = a["ygg"]["token"].as_str().unwrap_or("");
        if name.is_empty() || id.is_empty() || token.is_empty() {
            continue;
        }
        out.push(PrismAccount { name: name.into(), id: id.replace('-', ""), token: token.into(), active: a["active"].as_bool().unwrap_or(false) });
    }
    Ok(out)
}

/// Aktives Konto (oder das einzige/erste), optional bevorzugt mit bestimmter UUID.
pub fn pick(list: &[PrismAccount], prefer_id: Option<&str>) -> Option<PrismAccount> {
    if let Some(id) = prefer_id {
        if let Some(a) = list.iter().find(|a| a.id == id) {
            return Some(a.clone());
        }
    }
    list.iter().find(|a| a.active).or(list.first()).cloned()
}

pub fn to_account(p: &PrismAccount) -> Account {
    Account { name: p.name.clone(), id: p.id.clone(), access_token: p.token.clone(), refresh_token: String::new(), source: "prism".into() }
}

/// Konto aus Prism laden (fuer den Klick auf „Anmelden“ und vor jedem Start).
pub fn load(custom: &str, prefer_id: Option<&str>) -> Result<Account> {
    let Some(path) = find(custom) else {
        bail!("Prism Launcher nicht gefunden. Installiere Prism, melde dich dort einmal mit deinem Microsoft-Konto an – oder trage unter Einstellungen › Anmeldung über Prism den Prism-Ordner ein.");
    };
    let list = read(&path)?;
    let Some(acc) = pick(&list, prefer_id) else {
        bail!("In Prism ist noch kein Microsoft-Konto angemeldet. Öffne Prism einmal, melde dich an (Konten › Microsoft hinzufügen) und klicke dann hier erneut.");
    };
    Ok(to_account(&acc))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_prism_accounts() {
        let dir = std::env::temp_dir().join(format!("pc-prism-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let json = r#"{"formatVersion":3,"accounts":[
            {"type":"Offline","profile":{"id":"x","name":"Offline"},"ygg":{"token":"t0"}},
            {"type":"MSA","msa":{"refresh_token":"geheim"},"ygg":{"iat":1,"token":"tok-a"},"profile":{"id":"1111-2222","name":"Alex"}},
            {"type":"MSA","active":true,"ygg":{"token":"tok-s"},"profile":{"id":"3333","name":"Steve"}}
        ]}"#;
        std::fs::write(dir.join("accounts.json"), json).unwrap();
        let list = read(&dir.join("accounts.json")).unwrap();
        assert_eq!(list.len(), 2, "Offline-Konto wird uebersprungen");
        assert_eq!(list[0].id, "11112222", "UUID ohne Bindestriche");
        assert_eq!(pick(&list, None).unwrap().name, "Steve", "aktives Konto zuerst");
        assert_eq!(pick(&list, Some("11112222")).unwrap().name, "Alex");
        // Ordner statt Datei als eigener Pfad
        let acc = load(&dir.to_string_lossy(), None).unwrap();
        assert_eq!((acc.name.as_str(), acc.access_token.as_str(), acc.source.as_str()), ("Steve", "tok-s", "prism"));
        assert!(acc.refresh_token.is_empty(), "Prisms Microsoft-Token wird nicht uebernommen");
        let _ = std::fs::remove_dir_all(dir);
    }
}
