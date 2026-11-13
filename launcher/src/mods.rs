//! Mods von Modrinth und CurseForge suchen, installieren (inkl. Abhaengigkeiten) und verwalten.
use std::collections::HashSet;
use std::path::{Path, PathBuf};

use anyhow::{anyhow, bail, Context, Result};
use reqwest::Client;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha1::{Digest, Sha1};

const MODRINTH: &str = "https://api.modrinth.com/v2";
const CURSEFORGE: &str = "https://api.curseforge.com/v1";
const CF_MINECRAFT: u32 = 432;
const CF_MODS: u32 = 6;

#[derive(Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, Debug)]
pub enum Source {
    Modrinth,
    CurseForge,
}

impl Source {
    pub fn name(self) -> &'static str {
        match self {
            Source::Modrinth => "Modrinth",
            Source::CurseForge => "CurseForge",
        }
    }
}

/// Ein Suchergebnis.
#[derive(Clone, Debug)]
pub struct Hit {
    pub source: Source,
    pub id: String,
    pub title: String,
    pub author: String,
    pub description: String,
    pub downloads: u64,
    pub icon: Option<String>,
    pub url: String,
}

/// Ein installierter Mod in einem Profil.
#[derive(Clone, Serialize, Deserialize, Debug, PartialEq)]
pub struct InstalledMod {
    pub source: Source,
    pub project_id: String,
    pub file_id: String,
    pub title: String,
    pub filename: String,
    #[serde(default)]
    pub dependency: bool,
    #[serde(default)]
    pub icon: Option<String>,
}

fn modrinth_loaders(loader: &str) -> &'static [&'static str] {
    // Quilt kann auch Fabric-Mods laden
    if loader == "Quilt" {
        &["quilt", "fabric"]
    } else {
        &["fabric"]
    }
}

/// CurseForge-Loader-ID (4 = Fabric, 5 = Quilt). Fuer Quilt nehmen wir Fabric-Mods, davon gibt es viel mehr.
fn cf_loader(_loader: &str) -> u32 {
    4
}

async fn mr_get(client: &Client, url: &str) -> Result<Value> {
    let r = client.get(url).send().await?;
    let status = r.status();
    if !status.is_success() {
        bail!("Modrinth antwortet mit {status}");
    }
    Ok(r.json().await?)
}

async fn cf_get(client: &Client, key: &str, url: &str) -> Result<Value> {
    if key.trim().is_empty() {
        bail!("Für CurseForge brauchst du einen API-Key (Einstellungen › CurseForge).");
    }
    let r = client.get(url).header("x-api-key", key.trim()).header("Accept", "application/json").send().await?;
    let status = r.status();
    if status.as_u16() == 403 || status.as_u16() == 401 {
        bail!("CurseForge lehnt den API-Key ab ({status}). Prüfe ihn unter Einstellungen › CurseForge.");
    }
    if !status.is_success() {
        bail!("CurseForge antwortet mit {status}");
    }
    Ok(r.json().await?)
}

fn enc(s: &str) -> String {
    url::form_urlencoded::byte_serialize(s.as_bytes()).collect()
}

// ------------------------------------------------------------------ Suche
pub async fn search(client: &Client, source: Source, query: &str, mc: &str, loader: &str, cf_key: &str) -> Result<Vec<Hit>> {
    match source {
        Source::Modrinth => {
            let cats: Vec<String> = modrinth_loaders(loader).iter().map(|l| format!("\"categories:{l}\"")).collect();
            let facets = format!("[[{}],[\"versions:{mc}\"],[\"project_type:mod\"]]", cats.join(","));
            let index = if query.trim().is_empty() { "downloads" } else { "relevance" };
            let url = format!("{MODRINTH}/search?query={}&facets={}&index={index}&limit=40", enc(query.trim()), enc(&facets));
            let v = mr_get(client, &url).await?;
            Ok(v["hits"]
                .as_array()
                .into_iter()
                .flatten()
                .map(|h| Hit {
                    source,
                    id: h["project_id"].as_str().unwrap_or("").into(),
                    title: h["title"].as_str().unwrap_or("?").into(),
                    author: h["author"].as_str().unwrap_or("").into(),
                    description: h["description"].as_str().unwrap_or("").into(),
                    downloads: h["downloads"].as_u64().unwrap_or(0),
                    icon: h["icon_url"].as_str().filter(|s| !s.is_empty()).map(String::from),
                    url: format!("https://modrinth.com/mod/{}", h["slug"].as_str().unwrap_or("")),
                })
                .collect())
        }
        Source::CurseForge => {
            let url = format!(
                "{CURSEFORGE}/mods/search?gameId={CF_MINECRAFT}&classId={CF_MODS}&searchFilter={}&gameVersion={}&modLoaderType={}&sortField=2&sortOrder=desc&pageSize=40",
                enc(query.trim()),
                enc(mc),
                cf_loader(loader)
            );
            let v = cf_get(client, cf_key, &url).await?;
            Ok(v["data"]
                .as_array()
                .into_iter()
                .flatten()
                .map(|m| Hit {
                    source,
                    id: m["id"].as_u64().map(|i| i.to_string()).unwrap_or_default(),
                    title: m["name"].as_str().unwrap_or("?").into(),
                    author: m["authors"][0]["name"].as_str().unwrap_or("").into(),
                    description: m["summary"].as_str().unwrap_or("").into(),
                    downloads: m["downloadCount"].as_f64().unwrap_or(0.0) as u64,
                    icon: m["logo"]["thumbnailUrl"].as_str().filter(|s| !s.is_empty()).map(String::from),
                    url: m["links"]["websiteUrl"].as_str().unwrap_or("https://www.curseforge.com/minecraft").into(),
                })
                .collect())
        }
    }
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

async fn pick_modrinth(client: &Client, id: &str, mc: &str, loader: &str) -> Result<Option<Pick>> {
    let loaders: Vec<String> = modrinth_loaders(loader).iter().map(|l| format!("\"{l}\"")).collect();
    let url = format!("{MODRINTH}/project/{id}/version?loaders={}&game_versions={}", enc(&format!("[{}]", loaders.join(","))), enc(&format!("[\"{mc}\"]")));
    let versions = mr_get(client, &url).await?;
    let list = versions.as_array().cloned().unwrap_or_default();
    let Some(v) = list.iter().find(|v| v["version_type"] == "release").or(list.first()) else { return Ok(None) };
    let files = v["files"].as_array().cloned().unwrap_or_default();
    let f = files.iter().find(|f| f["primary"] == true).or(files.first()).context("Version ohne Datei")?;
    let project = mr_get(client, &format!("{MODRINTH}/project/{id}")).await?;
    let mut deps = Vec::new();
    for d in v["dependencies"].as_array().into_iter().flatten() {
        if d["dependency_type"] != "required" {
            continue;
        }
        if let Some(pid) = d["project_id"].as_str() {
            deps.push(pid.to_string());
        } else if let Some(vid) = d["version_id"].as_str() {
            if let Ok(dv) = mr_get(client, &format!("{MODRINTH}/version/{vid}")).await {
                if let Some(pid) = dv["project_id"].as_str() {
                    deps.push(pid.to_string());
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

async fn pick_curseforge(client: &Client, key: &str, id: &str, mc: &str, loader: &str) -> Result<Option<Pick>> {
    let url = format!("{CURSEFORGE}/mods/{id}/files?gameVersion={}&modLoaderType={}&pageSize=50", enc(mc), cf_loader(loader));
    let v = cf_get(client, key, &url).await?;
    let mut files = v["data"].as_array().cloned().unwrap_or_default();
    files.sort_by(|a, b| b["fileDate"].as_str().unwrap_or("").cmp(a["fileDate"].as_str().unwrap_or("")));
    let Some(f) = files.iter().find(|f| f["releaseType"] == 1).or(files.first()).cloned() else { return Ok(None) };
    let m = cf_get(client, key, &format!("{CURSEFORGE}/mods/{id}")).await?;
    let title = m["data"]["name"].as_str().unwrap_or(id).to_string();
    let Some(dl) = f["downloadUrl"].as_str().filter(|s| !s.is_empty()) else {
        bail!("Der Autor von „{title}“ erlaubt keinen Download über andere Launcher. Lade den Mod auf curseforge.com herunter und lege ihn in den Mods-Ordner.");
    };
    let sha1 = f["hashes"].as_array().into_iter().flatten().find(|h| h["algo"] == 1).and_then(|h| h["value"].as_str()).map(String::from);
    let deps = f["dependencies"]
        .as_array()
        .into_iter()
        .flatten()
        .filter(|d| d["relationType"] == 3)
        .filter_map(|d| d["modId"].as_u64().map(|i| i.to_string()))
        .collect();
    Ok(Some(Pick {
        file_id: f["id"].as_u64().map(|i| i.to_string()).unwrap_or_default(),
        title,
        icon: m["data"]["logo"]["thumbnailUrl"].as_str().filter(|s| !s.is_empty()).map(String::from),
        url: dl.into(),
        filename: f["fileName"].as_str().unwrap_or("").into(),
        sha1,
        deps,
    }))
}

async fn download(client: &Client, url: &str, dest: &Path, sha1: Option<&str>) -> Result<()> {
    let bytes = client.get(url).send().await?.error_for_status()?.bytes().await?;
    if let Some(h) = sha1 {
        if !hex::encode(Sha1::digest(&bytes)).eq_ignore_ascii_case(h) {
            bail!("Prüfsumme von {} stimmt nicht", dest.display());
        }
    }
    if let Some(d) = dest.parent() {
        tokio::fs::create_dir_all(d).await?;
    }
    let tmp = PathBuf::from(format!("{}.part", dest.display()));
    tokio::fs::write(&tmp, &bytes).await?;
    tokio::fs::rename(&tmp, dest).await?;
    Ok(())
}

/// Installiert einen Mod samt aller benoetigten Abhaengigkeiten in `mods_dir`.
/// `log` meldet Fortschritt; zurueck kommen die neu installierten Mods.
pub async fn install(
    client: &Client,
    source: Source,
    project_id: &str,
    mc: &str,
    loader: &str,
    cf_key: &str,
    mods_dir: &Path,
    already: &[InstalledMod],
    log: impl Fn(String),
) -> Result<Vec<InstalledMod>> {
    let mut have: HashSet<(Source, String)> = already.iter().map(|m| (m.source, m.project_id.clone())).collect();
    let mut queue = vec![(project_id.to_string(), false)];
    let mut out = Vec::new();
    while let Some((id, is_dep)) = queue.pop() {
        if !have.insert((source, id.clone())) {
            continue;
        }
        let pick = match source {
            Source::Modrinth => pick_modrinth(client, &id, mc, loader).await,
            Source::CurseForge => pick_curseforge(client, cf_key, &id, mc, loader).await,
        };
        let pick = match pick {
            Ok(Some(p)) => p,
            Ok(None) if is_dep => {
                log(format!("[Mods] Abhängigkeit {id} hat keine Version für {loader} {mc} – übersprungen"));
                continue;
            }
            Ok(None) => bail!("Für {loader} {mc} gibt es keine passende Version dieses Mods."),
            Err(e) if is_dep => {
                log(format!("[Mods] Abhängigkeit {id} konnte nicht geladen werden: {e:#}"));
                continue;
            }
            Err(e) => return Err(e),
        };
        if pick.filename.is_empty() || pick.filename.contains(['/', '\\']) {
            return Err(anyhow!("Ungültiger Dateiname für {}", pick.title));
        }
        log(format!("[Mods] Lade {} ({})", pick.title, pick.filename));
        download(client, &pick.url, &mods_dir.join(&pick.filename), pick.sha1.as_deref()).await?;
        for d in &pick.deps {
            queue.push((d.clone(), true));
        }
        out.push(InstalledMod {
            source,
            project_id: id,
            file_id: pick.file_id,
            title: pick.title,
            filename: pick.filename,
            dependency: is_dep,
            icon: pick.icon,
        });
    }
    Ok(out)
}

// ------------------------------------------------------------------ Verwalten
pub fn is_enabled(mods_dir: &Path, m: &InstalledMod) -> bool {
    !mods_dir.join(format!("{}.disabled", m.filename)).exists()
}

pub fn set_enabled(mods_dir: &Path, m: &InstalledMod, on: bool) -> Result<()> {
    let (a, b) = (mods_dir.join(&m.filename), mods_dir.join(format!("{}.disabled", m.filename)));
    if on && b.exists() {
        std::fs::rename(b, a)?;
    } else if !on && a.exists() {
        std::fs::rename(a, b)?;
    }
    Ok(())
}

pub fn remove(mods_dir: &Path, m: &InstalledMod) {
    let _ = std::fs::remove_file(mods_dir.join(&m.filename));
    let _ = std::fs::remove_file(mods_dir.join(format!("{}.disabled", m.filename)));
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

    /// Echter Test gegen die APIs: `cargo test --release live -- --ignored --nocapture`
    #[test]
    #[ignore]
    fn live_modrinth_and_curseforge() {
        let rt = tokio::runtime::Runtime::new().unwrap();
        let client = crate::net::client();
        let dir = std::env::temp_dir().join(format!("pc-mods-{}", std::process::id()));
        rt.block_on(async {
            let hits = search(&client, Source::Modrinth, "sodium extra", "1.21.4", "Fabric", "").await.unwrap();
            println!("Modrinth: {} Treffer, erster: {} ({})", hits.len(), hits[0].title, mods_dl(hits[0].downloads));
            let se = hits.iter().find(|h| h.title.contains("Sodium Extra")).expect("Sodium Extra");
            let got = install(&client, Source::Modrinth, &se.id, "1.21.4", "Fabric", "", &dir, &[], |l| println!("  {l}")).await.unwrap();
            for m in &got {
                println!("  -> {} [{}] dep={} icon={}", m.title, m.filename, m.dependency, m.icon.is_some());
                assert!(dir.join(&m.filename).exists());
            }
            assert!(got.iter().any(|m| m.title == "Sodium" && m.dependency), "Sodium als Abhaengigkeit");
            let pop = search(&client, Source::Modrinth, "", "1.21.4", "Quilt", "").await.unwrap();
            println!("Modrinth beliebteste (Quilt): {:?}", pop.iter().take(5).map(|h| &h.title).collect::<Vec<_>>());
            let key = std::env::var("CF_KEY").unwrap_or_default();
            if !key.is_empty() {
                let cf = search(&client, Source::CurseForge, "jei", "1.21.4", "Fabric", &key).await;
                match cf {
                    Ok(cf) => {
                        println!("CurseForge: {} Treffer: {:?}", cf.len(), cf.iter().take(3).map(|h| &h.title).collect::<Vec<_>>());
                        if let Some(h) = cf.first() {
                            let got = install(&client, Source::CurseForge, &h.id, "1.21.4", "Fabric", &key, &dir, &[], |l| println!("  {l}")).await;
                            println!("  CurseForge-Installation: {:?}", got.map(|g| g.iter().map(|m| m.filename.clone()).collect::<Vec<_>>()));
                        }
                    }
                    Err(e) => println!("CurseForge-Fehler: {e:#}"),
                }
            }
        });
        let _ = std::fs::remove_dir_all(dir);
    }

    fn mods_dl(n: u64) -> String {
        format_downloads(n)
    }

    #[test]
    fn downloads_text() {
        assert_eq!(format_downloads(950), "950");
        assert_eq!(format_downloads(12_340), "12,3 Tsd.");
        assert_eq!(format_downloads(98_700_000), "98,7 Mio.");
    }
}
