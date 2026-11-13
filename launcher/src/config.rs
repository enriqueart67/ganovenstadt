//! Einstellungen und gespeicherter Account.
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

pub const LAUNCHER_NAME: &str = "Peregrine Client";
pub const LAUNCHER_VERSION: &str = env!("CARGO_PKG_VERSION");

pub fn base_dir() -> PathBuf {
    dirs::home_dir().unwrap_or_else(|| PathBuf::from(".")).join(".peregrine")
}

#[derive(Serialize, Deserialize, Clone, PartialEq)]
#[serde(default)]
pub struct Config {
    pub game_dir: String,
    pub version: String,
    pub loader: String,
    pub show_snapshots: bool,
    pub ram_mb: u32,
    pub java_path: String,
    pub width: u32,
    pub height: u32,
    pub extra_jvm_args: String,
    pub client_id: String,
    pub redirect_uri: String,
    pub close_on_launch: bool,
    pub look: crate::theme::Look,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            game_dir: base_dir().join("minecraft").to_string_lossy().into_owned(),
            version: String::new(),
            loader: "Vanilla".into(),
            show_snapshots: false,
            ram_mb: 4096,
            java_path: String::new(),
            width: 1280,
            height: 720,
            extra_jvm_args: String::new(),
            client_id: String::new(),
            redirect_uri: "http://localhost:8765/callback".into(),
            close_on_launch: false,
            look: Default::default(),
        }
    }
}

#[derive(Serialize, Deserialize, Clone)]
pub struct Account {
    pub name: String,
    pub id: String,
    pub access_token: String,
    pub refresh_token: String,
}

fn read<T: for<'a> Deserialize<'a>>(p: &Path) -> Option<T> {
    serde_json::from_slice(&std::fs::read(p).ok()?).ok()
}

fn write<T: Serialize>(p: &Path, v: &T) {
    let _ = std::fs::create_dir_all(base_dir());
    let tmp = p.with_extension("tmp");
    if let Ok(data) = serde_json::to_vec_pretty(v) {
        if std::fs::write(&tmp, data).is_ok() {
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                let _ = std::fs::set_permissions(&tmp, std::fs::Permissions::from_mode(0o600));
            }
            let _ = std::fs::rename(&tmp, p);
        }
    }
}

pub fn load_config() -> Config {
    read(&base_dir().join("config.json")).unwrap_or_default()
}
pub fn save_config(c: &Config) {
    write(&base_dir().join("config.json"), c);
}
pub fn load_account() -> Option<Account> {
    read(&base_dir().join("account.json"))
}
pub fn save_account(a: Option<&Account>) {
    let p = base_dir().join("account.json");
    match a {
        Some(a) => write(&p, a),
        None => {
            let _ = std::fs::remove_file(p);
        }
    }
}
