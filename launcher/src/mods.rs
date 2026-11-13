//! Inhalte von Modrinth: Mods, Ressourcenpakete, Shader, Datenpakete und Modpacks
//! suchen, installieren (inkl. Abhaengigkeiten) und verwalten.
use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};

use anyhow::{bail, Context, Result};
use futures::{stream, StreamExt};
use reqwest::Client;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha1::{Digest, Sha1};

use crate::profiles::{self, Profile};
use crate::state::Ui;

const API: &str = "https://api.modrinth.com/v2";
/// Iris – wird fuer Shader gebraucht.
pub const IRIS: &str = "YL57xq9U";

/// Art des Inhalts (entspricht Modrinths `project_type`).
#[derive(Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, Debug, Default)]
#[serde(rename_all = "lowercase")]
pub enum Kind {
    #[default]
    Mod,
    ResourcePack,
    Shader,
    DataPack,
    ModPack,
}

impl Kind {
    pub const BROWSABLE: [Kind; 4] = [Kind::Mod, Kind::ResourcePack, Kind::Shader, Kind::DataPack];

    pub fn label(self) -> &'static str {
        match self {
            Kind::Mod => "Mods",
            Kind::ResourcePack => "Ressourcenpakete",
            Kind::Shader => "Shader",
            Kind::DataPack => "Datenpakete",
            Kind::ModPack => "Modpacks",
        }
    }
    fn api(self) -> &'static str {
        match self {
            Kind::Mod => "mod",
            Kind::ResourcePack => "resourcepack",
            Kind::Shader => "shader",
            Kind::DataPack => "datapack",
            Kind::ModPack => "modpack",
        }
    }
    fn from_api(s: &str) -> Kind {
        match s {
            "resourcepack" => Kind::ResourcePack,
            "shader" => Kind::Shader,
            "datapack" => Kind::DataPack,
            "modpack" => Kind::ModPack,
            _ => Kind::Mod,
        }
    }
    /// Ob diese Art einen Modloader (Fabric/Quilt) braucht.
    pub fn needs_loader(self) -> bool {
        matches!(self, Kind::Mod | Kind::Shader)
    }
    /// Loader-Namen, nach denen Modrinth die Versionen filtert.
    fn loaders(self, loader: &str) -> Vec<&'static str> {
        match self {
            Kind::Mod | Kind::ModPack if loader == "Quilt" => vec!["quilt", "fabric"],
            Kind::Mod | Kind::ModPack => vec!["fabric"],
            Kind::ResourcePack => vec!["minecraft"],
            Kind::Shader => vec!["iris", "optifine"],
            Kind::DataPack => vec!["datapack"],
        }
    }
}

/// Ein Suchergebnis.
#[derive(Clone, Debug)]
pub struct Hit {
    pub kind: Kind,
    pub id: String,
    pub title: String,
    pub author: String,
    pub description: String,
    pub downloads: u64,
    pub icon: Option<String>,
    pub url: String,
    pub versions: Vec<String>,
}

/// Ein installierter Inhalt in einem Profil.
#[derive(Clone, Serialize, Deserialize, Debug, PartialEq)]
pub struct InstalledMod {
    #[serde(default)]
    pub kind: Kind,
    pub project_id: String,
    pub file_id: String,
    pub title: String,
    pub filename: String,
    #[serde(default)]
    pub dependency: bool,
    #[serde(default)]
    pub icon: Option<String>,
    /// Nur Datenpakete: in welcher Welt.
    #[serde(default)]
    pub world: Option<String>,
    /// Kam mit einem Modpack.
    #[serde(default)]
    pub from_pack: bool,
}

impl InstalledMod {
    /// Ordner relativ zum Spielordner des Profils.
    pub fn dir(&self, root: &Path) -> PathBuf {
        match self.kind {
            Kind::Mod | Kind::ModPack => root.join("mods"),
            Kind::ResourcePack => root.join("resourcepacks"),
            Kind::Shader => root.join("shaderpacks"),
            Kind::DataPack => root.join("saves").join(self.world.as_deref().unwrap_or("_")).join("datapacks"),
        }
    }
}

async fn get(client: &Client, url: &str) -> Result<Value> {
    let r = client.get(url).send().await?;
    let status = r.status();
    if !status.is_success() {
        bail!("Modrinth antwortet mit {status}");
    }
    Ok(r.json().await?)
}

fn enc(s: &str) -> String {
    url::form_urlencoded::byte_serialize(s.as_bytes()).collect()
}

fn list(items: &[&str]) -> String {
    format!("[{}]", items.iter().map(|i| format!("\"{i}\"")).collect::<Vec<_>>().join(","))
}

// ------------------------------------------------------------------ Suche
pub async fn search(client: &Client, kind: Kind, query: &str, mc: &str, loader: &str) -> Result<Vec<Hit>> {
    let mut facets: Vec<String> = vec![format!("[\"project_type:{}\"]", kind.api())];
    match kind {
        Kind::Mod => {
            let cats: Vec<String> = kind.loaders(loader).iter().map(|l| format!("\"categories:{l}\"")).collect();
            facets.push(format!("[{}]", cats.join(",")));
        }
        Kind::ModPack => facets.push("[\"categories:fabric\",\"categories:quilt\"]".into()),
        Kind::Shader => facets.push("[\"categories:iris\",\"categories:optifine\"]".into()),
        _ => {}
    }
    if kind != Kind::ModPack && !mc.is_empty() {
        facets.push(format!("[\"versions:{mc}\"]"));
    }
    let index = if query.trim().is_empty() { "downloads" } else { "relevance" };
    let url = format!("{API}/search?query={}&facets={}&index={index}&limit=40", enc(query.trim()), enc(&format!("[{}]", facets.join(","))));
    let v = get(client, &url).await?;
    Ok(v["hits"]
        .as_array()
        .into_iter()
        .flatten()
        .map(|h| Hit {
            kind,
            id: h["project_id"].as_str().unwrap_or("").into(),
            title: h["title"].as_str().unwrap_or("?").into(),
            author: h["author"].as_str().unwrap_or("").into(),
            description: h["description"].as_str().unwrap_or("").into(),
            downloads: h["downloads"].as_u64().unwrap_or(0),
            icon: h["icon_url"].as_str().filter(|s| !s.is_empty()).map(String::from),
            url: format!("https://modrinth.com/{}/{}", kind.api(), h["slug"].as_str().unwrap_or("")),
            versions: h["versions"].as_array().into_iter().flatten().filter_map(|x| x.as_str().map(String::from)).collect(),
        })
        .collect())
}

// ------------------------------------------------------------------ Installation
struct Pick {
    file_id: String,
    title: String,
    icon: Option<String>,
    url: String,
    filename: String,
    sha1: Option<String>,
    deps: Vec<String>,
}

/// Neueste passende Version eines Projekts (Release bevorzugt).
async fn latest_version(client: &Client, id: &str, kind: Kind, mc: Option<&str>, loader: &str) -> Result<Option<Value>> {
    let mut url = format!("{API}/project/{id}/version?loaders={}", enc(&list(&kind.loaders(loader))));
    if let Some(mc) = mc {
        url.push_str(&format!("&game_versions={}", enc(&list(&[mc]))));
    }
    let versions = get(client, &url).await?;
    let all = versions.as_array().cloned().unwrap_or_default();
    Ok(all.iter().find(|v| v["version_type"] == "release").or(all.first()).cloned())
}

async fn pick(client: &Client, id: &str, kind: Kind, mc: &str, loader: &str) -> Result<Option<Pick>> {
    let Some(v) = latest_version(client, id, kind, Some(mc), loader).await? else { return Ok(None) };
    let files = v["files"].as_array().cloned().unwrap_or_default();
    let f = files.iter().find(|f| f["primary"] == true).or(files.first()).context("Version ohne Datei")?;
    let project = get(client, &format!("{API}/project/{id}")).await?;
    let mut deps = Vec::new();
    if kind == Kind::Mod {
        for d in v["dependencies"].as_array().into_iter().flatten() {
            if d["dependency_type"] != "required" {
                continue;
            }
            if let Some(pid) = d["project_id"].as_str() {
                deps.push(pid.to_string());
            } else if let Some(vid) = d["version_id"].as_str() {
                if let Ok(dv) = get(client, &format!("{API}/version/{vid}")).await {
                    if let Some(pid) = dv["project_id"].as_str() {
                        deps.push(pid.to_string());
                    }
                }
            }
        }
    }
    Ok(Some(Pick {
        file_id: v["id"].as_str().unwrap_or("").into(),
        title: project["title"].as_str().unwrap_or(id).into(),
        icon: project["icon_url"].as_str().filter(|s| !s.is_empty()).map(String::from),
        url: f["url"].as_str().unwrap_or("").into(),
        filename: f["filename"].as_str().unwrap_or("").into(),
        sha1: f["hashes"]["sha1"].as_str().map(String::from),
        deps,
    }))
}

async fn fetch(client: &Client, url: &str, sha1: Option<&str>) -> Result<Vec<u8>> {
    let bytes = client.get(url).send().await?.error_for_status()?.bytes().await?.to_vec();
    if let Some(h) = sha1 {
        if !hex::encode(Sha1::digest(&bytes)).eq_ignore_ascii_case(h) {
            bail!("Prüfsumme stimmt nicht ({url})");
        }
    }
    Ok(bytes)
}

async fn save(dest: &Path, bytes: &[u8]) -> Result<()> {
    if let Some(d) = dest.parent() {
        tokio::fs::create_dir_all(d).await?;
    }
    let tmp = PathBuf::from(format!("{}.part", dest.display()));
    tokio::fs::write(&tmp, bytes).await?;
    tokio::fs::rename(&tmp, dest).await?;
    Ok(())
}

/// Neueste passende Datei eines Mods (ohne Abhängigkeiten): (Dateiname, URL, SHA1).
pub async fn pick_file(client: &Client, id: &str, mc: &str, loader: &str) -> Result<Option<(String, String, Option<String>)>> {
    let Some(v) = latest_version(client, id, Kind::Mod, Some(mc), loader).await? else { return Ok(None) };
    let files = v["files"].as_array().cloned().unwrap_or_default();
    let f = files.iter().find(|f| f["primary"] == true).or(files.first()).context("Version ohne Datei")?;
    let name = f["filename"].as_str().unwrap_or("").to_string();
    if !safe_name(&name) {
        bail!("Ungültiger Dateiname");
    }
    Ok(Some((name, f["url"].as_str().unwrap_or("").into(), f["hashes"]["sha1"].as_str().map(String::from))))
}

/// Datei laden (mit Prüfsumme) und speichern.
pub async fn download_to(client: &Client, url: &str, sha1: Option<&str>, dest: &Path) -> Result<()> {
    let bytes = fetch(client, url, sha1).await?;
    save(dest, &bytes).await
}

fn safe_name(name: &str) -> bool {
    !name.is_empty() && !name.contains(['/', '\\']) && name != ".." && name != "."
}

/// Installiert einen Inhalt in den Spielordner `root` eines Profils.
/// Mods bringen ihre Abhaengigkeiten mit, Shader bringen Iris mit (falls noch nicht da).
#[allow(clippy::too_many_arguments)]
pub async fn install(
    client: &Client,
    kind: Kind,
    project_id: &str,
    mc: &str,
    loader: &str,
    root: &Path,
    world: Option<&str>,
    already: &[InstalledMod],
    log: impl Fn(String),
) -> Result<Vec<InstalledMod>> {
    let mut have: HashSet<(Kind, String)> = already.iter().filter(|m| m.kind != Kind::DataPack).map(|m| (m.kind, m.project_id.clone())).collect();
    let mut queue = vec![(project_id.to_string(), kind, false)];
    if kind == Kind::Shader && loader != "Vanilla" {
        queue.insert(0, (IRIS.to_string(), Kind::Mod, true));
    }
    let mut out = Vec::new();
    while let Some((id, k, is_dep)) = queue.pop() {
        if k != Kind::DataPack && !have.insert((k, id.clone())) {
            continue;
        }
        let p = match pick(client, &id, k, mc, loader).await {
            Ok(Some(p)) => p,
            Ok(None) if is_dep => {
                log(format!("[Modrinth] Abhängigkeit {id} hat keine Version für {loader} {mc} – übersprungen"));
                continue;
            }
            Ok(None) => bail!("Für Minecraft {mc} gibt es keine passende Version davon."),
            Err(e) if is_dep => {
                log(format!("[Modrinth] Abhängigkeit {id} konnte nicht geladen werden: {e:#}"));
                continue;
            }
            Err(e) => return Err(e),
        };
        if !safe_name(&p.filename) {
            bail!("Ungültiger Dateiname für {}", p.title);
        }
        let entry = InstalledMod {
            kind: k,
            project_id: id,
            file_id: p.file_id.clone(),
            title: p.title.clone(),
            filename: p.filename.clone(),
            dependency: is_dep,
            icon: p.icon.clone(),
            world: if k == Kind::DataPack { world.map(String::from) } else { None },
            from_pack: false,
        };
        log(format!("[Modrinth] Lade {} ({})", p.title, p.filename));
        let bytes = fetch(client, &p.url, p.sha1.as_deref()).await?;
        save(&entry.dir(root).join(&p.filename), &bytes).await?;
        for d in p.deps {
            queue.push((d, Kind::Mod, true));
        }
        out.push(entry);
    }
    Ok(out)
}

// ------------------------------------------------------------------ Modpacks (.mrpack)
/// Laedt ein Modpack von Modrinth und legt daraus ein neues Profil an.
pub async fn install_modpack(client: &Client, ui: &Ui, project_id: &str, game_dir: &str, existing: Vec<Profile>) -> Result<Profile> {
    ui.status("Lade Modpack-Infos …");
    let project = get(client, &format!("{API}/project/{project_id}")).await?;
    let title = project["title"].as_str().unwrap_or("Modpack").to_string();
    let v = match latest_version(client, project_id, Kind::ModPack, None, "Fabric").await? {
        Some(v) => Some(v),
        None => latest_version(client, project_id, Kind::ModPack, None, "Quilt").await?,
    };
    let v = v.context("Dieses Modpack gibt es nur für Forge/NeoForge – der Launcher unterstützt Fabric und Quilt.")?;
    let files = v["files"].as_array().cloned().unwrap_or_default();
    let f = files.iter().find(|f| f["primary"] == true).or(files.first()).context("Modpack ohne Datei")?;
    ui.status(format!("Lade {title} …"));
    let pack = fetch(client, f["url"].as_str().unwrap_or(""), f["hashes"]["sha1"].as_str()).await?;

    let mut zip = zip::ZipArchive::new(std::io::Cursor::new(pack))?;
    let index: Value = {
        let mut e = zip.by_name("modrinth.index.json").context("Keine modrinth.index.json im Modpack")?;
        let mut s = String::new();
        std::io::Read::read_to_string(&mut e, &mut s)?;
        serde_json::from_str(&s)?
    };
    let deps = &index["dependencies"];
    let mc = deps["minecraft"].as_str().context("Modpack ohne Minecraft-Version")?.to_string();
    let (loader, loader_version) = if let Some(l) = deps["fabric-loader"].as_str() {
        ("Fabric", l.to_string())
    } else if let Some(l) = deps["quilt-loader"].as_str() {
        ("Quilt", l.to_string())
    } else {
        bail!("Dieses Modpack braucht Forge/NeoForge – der Launcher unterstützt Fabric und Quilt.");
    };

    let mut profile = Profile {
        id: profiles::new_id(&title, &existing),
        name: title.clone(),
        version: mc,
        loader: loader.into(),
        loader_version: Some(loader_version),
        mods: vec![],
        icon: project["icon_url"].as_str().filter(|s| !s.is_empty()).map(String::from),
    };
    let root = profiles::dir(game_dir, &profile);
    std::fs::create_dir_all(&root)?;

    struct F {
        path: String,
        url: String,
        sha1: Option<String>,
    }
    let mut files = Vec::new();
    for f in index["files"].as_array().into_iter().flatten() {
        if f["env"]["client"] == "unsupported" {
            continue;
        }
        let path = f["path"].as_str().unwrap_or("").replace('\\', "/");
        if path.is_empty() || path.starts_with('/') || path.split('/').any(|c| c == ".." || c.contains(':')) {
            continue;
        }
        let Some(url) = f["downloads"][0].as_str() else { continue };
        files.push(F { path, url: url.into(), sha1: f["hashes"]["sha1"].as_str().map(String::from) });
    }
    ui.status(format!("Lade {} Dateien von {title} …", files.len()));
    ui.progress(0, files.len());
    let jobs: Vec<(String, String, Option<String>)> = files.iter().map(|f| (f.path.clone(), f.url.clone(), f.sha1.clone())).collect();
    let results: Vec<Result<()>> = stream::iter(jobs)
        .map(|(path, url, sha1)| {
            let (root, client, ui) = (root.clone(), client.clone(), ui.clone());
            async move {
                let bytes = fetch(&client, &url, sha1.as_deref()).await?;
                save(&root.join(&path), &bytes).await?;
                ui.inc();
                Ok(())
            }
        })
        .buffer_unordered(16)
        .collect()
        .await;
    if let Some(e) = results.into_iter().find_map(Result::err) {
        return Err(e);
    }

    // Overrides (Konfigurationen usw.) entpacken
    for i in 0..zip.len() {
        let mut e = zip.by_index(i)?;
        if e.is_dir() {
            continue;
        }
        let Some(name) = e.enclosed_name() else { continue };
        let name = name.to_string_lossy().replace('\\', "/");
        let Some(rel) = name.strip_prefix("overrides/").or_else(|| name.strip_prefix("client-overrides/")) else { continue };
        let dest = root.join(rel);
        if let Some(d) = dest.parent() {
            std::fs::create_dir_all(d)?;
        }
        let mut out = std::fs::File::create(dest)?;
        std::io::copy(&mut e, &mut out)?;
    }

    // Titel/Icons der enthaltenen Inhalte ueber ihre Pruefsummen nachschlagen
    ui.status("Lese Modpack-Inhalt …");
    let hashes: Vec<&str> = files.iter().filter_map(|f| f.sha1.as_deref()).collect();
    let by_hash: HashMap<String, Value> = match client.post(format!("{API}/version_files")).json(&json!({"hashes": hashes, "algorithm": "sha1"})).send().await {
        Ok(r) if r.status().is_success() => r.json().await.unwrap_or_default(),
        _ => HashMap::new(),
    };
    let pids: Vec<String> = by_hash.values().filter_map(|v| v["project_id"].as_str().map(String::from)).collect::<HashSet<_>>().into_iter().collect();
    let mut projects: HashMap<String, Value> = HashMap::new();
    for chunk in pids.chunks(100) {
        let ids: Vec<&str> = chunk.iter().map(String::as_str).collect();
        if let Ok(v) = get(client, &format!("{API}/projects?ids={}", enc(&list(&ids)))).await {
            for p in v.as_array().into_iter().flatten() {
                if let Some(id) = p["id"].as_str() {
                    projects.insert(id.into(), p.clone());
                }
            }
        }
    }
    for f in &files {
        let (folder, filename) = f.path.rsplit_once('/').unwrap_or(("", &f.path));
        let kind = match folder {
            "mods" => Kind::Mod,
            "resourcepacks" => Kind::ResourcePack,
            "shaderpacks" => Kind::Shader,
            _ => continue,
        };
        let ver = f.sha1.as_ref().and_then(|h| by_hash.get(h));
        let pid = ver.and_then(|v| v["project_id"].as_str()).unwrap_or("").to_string();
        let proj = projects.get(&pid);
        let pkind = proj.and_then(|p| p["project_type"].as_str()).map(Kind::from_api);
        profile.mods.push(InstalledMod {
            kind: pkind.filter(|k| *k == kind).unwrap_or(kind),
            project_id: pid,
            file_id: ver.and_then(|v| v["id"].as_str()).unwrap_or("").into(),
            title: proj.and_then(|p| p["title"].as_str()).map(String::from).unwrap_or_else(|| filename.trim_end_matches(".jar").into()),
            filename: filename.into(),
            dependency: false,
            icon: proj.and_then(|p| p["icon_url"].as_str()).filter(|s| !s.is_empty()).map(String::from),
            world: None,
            from_pack: true,
        });
    }
    profile.mods.sort_by_key(|m| m.title.to_lowercase());
    ui.progress(0, 0);
    ui.status("Bereit.");
    Ok(profile)
}

// ------------------------------------------------------------------ Verwalten
pub fn is_enabled(root: &Path, m: &InstalledMod) -> bool {
    !m.dir(root).join(format!("{}.disabled", m.filename)).exists()
}

pub fn set_enabled(root: &Path, m: &InstalledMod, on: bool) -> Result<()> {
    let dir = m.dir(root);
    let (a, b) = (dir.join(&m.filename), dir.join(format!("{}.disabled", m.filename)));
    if on && b.exists() {
        std::fs::rename(b, a)?;
    } else if !on && a.exists() {
        std::fs::rename(a, b)?;
    }
    Ok(())
}

pub fn remove(root: &Path, m: &InstalledMod) {
    let dir = m.dir(root);
    let _ = std::fs::remove_file(dir.join(&m.filename));
    let _ = std::fs::remove_file(dir.join(format!("{}.disabled", m.filename)));
}

/// Welten eines Profils (fuer Datenpakete).
pub fn worlds(root: &Path) -> Vec<String> {
    let mut out: Vec<String> = std::fs::read_dir(root.join("saves"))
        .into_iter()
        .flatten()
        .flatten()
        .filter(|e| e.path().join("level.dat").exists())
        .map(|e| e.file_name().to_string_lossy().into_owned())
        .collect();
    out.sort();
    out
}

pub fn format_downloads(n: u64) -> String {
    match n {
        0..=999 => n.to_string(),
        1_000..=999_999 => format!("{} Tsd.", format!("{:.1}", n as f64 / 1e3).replace('.', ",")),
        _ => format!("{} Mio.", format!("{:.1}", n as f64 / 1e6).replace('.', ",")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn downloads_text() {
        assert_eq!(format_downloads(950), "950");
        assert_eq!(format_downloads(12_340), "12,3 Tsd.");
        assert_eq!(format_downloads(98_700_000), "98,7 Mio.");
    }

    #[test]
    fn folders() {
        let root = Path::new("/x");
        let m = |kind, world: Option<&str>| InstalledMod {
            kind,
            project_id: String::new(),
            file_id: String::new(),
            title: String::new(),
            filename: "a".into(),
            dependency: false,
            icon: None,
            world: world.map(String::from),
            from_pack: false,
        };
        assert_eq!(m(Kind::Mod, None).dir(root), Path::new("/x/mods"));
        assert_eq!(m(Kind::ResourcePack, None).dir(root), Path::new("/x/resourcepacks"));
        assert_eq!(m(Kind::Shader, None).dir(root), Path::new("/x/shaderpacks"));
        assert_eq!(m(Kind::DataPack, Some("Welt 1")).dir(root), Path::new("/x/saves/Welt 1/datapacks"));
        // alte profiles.json (mit "source": "Modrinth") laden weiterhin
        let old: InstalledMod = serde_json::from_str(r#"{"source":"Modrinth","project_id":"p","file_id":"f","title":"T","filename":"t.jar"}"#).unwrap();
        assert_eq!(old.kind, Kind::Mod);
    }

    /// Echter Test gegen Modrinth: `cargo test --release live -- --ignored --nocapture`
    #[test]
    #[ignore]
    fn live_everything() {
        let rt = tokio::runtime::Runtime::new().unwrap();
        let client = crate::net::client();
        let root = std::env::temp_dir().join(format!("pc-live-{}", std::process::id()));
        rt.block_on(async {
            for k in [Kind::Mod, Kind::ResourcePack, Kind::Shader, Kind::DataPack, Kind::ModPack] {
                let hits = search(&client, k, "", "26.3", "Fabric").await.unwrap();
                println!("{:<17} {:>2} Treffer: {:?}", k.label(), hits.len(), hits.iter().take(3).map(|h| &h.title).collect::<Vec<_>>());
                assert!(!hits.is_empty());
            }
            let sh = search(&client, Kind::Shader, "complementary reimagined", "26.3", "Fabric").await.unwrap();
            let got = install(&client, Kind::Shader, &sh[0].id, "26.3", "Fabric", &root, None, &[], |l| println!("  {l}")).await.unwrap();
            assert!(got.iter().any(|m| m.kind == Kind::Mod && m.project_id == IRIS), "Iris fuer Shader");
            assert!(got.iter().any(|m| m.kind == Kind::Shader && root.join("shaderpacks").join(&m.filename).exists()));
            let rp = search(&client, Kind::ResourcePack, "fresh animations", "26.3", "Fabric").await.unwrap();
            let got = install(&client, Kind::ResourcePack, &rp[0].id, "26.3", "Fabric", &root, None, &[], |l| println!("  {l}")).await.unwrap();
            assert!(root.join("resourcepacks").join(&got[0].filename).exists());
            let dp = search(&client, Kind::DataPack, "terralith", "26.3", "Fabric").await.unwrap();
            let got = install(&client, Kind::DataPack, &dp[0].id, "26.3", "Fabric", &root, Some("Testwelt"), &[], |l| println!("  {l}")).await.unwrap();
            assert!(root.join("saves/Testwelt/datapacks").join(&got[0].filename).exists());
            println!("Ordner: {:?}", std::fs::read_dir(&root).unwrap().map(|e| e.unwrap().file_name()).collect::<Vec<_>>());
        });
        let _ = std::fs::remove_dir_all(root);
    }
}
