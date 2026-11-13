//! Installation: Versions-JSON, Client, Bibliotheken, Natives, Assets, Java, Fabric/Quilt.
use std::path::{Path, PathBuf};

use anyhow::{bail, Context, Result};
use reqwest::Client;
use serde::Deserialize;
use serde_json::{json, Value};

use crate::config::{base_dir, Config};
use crate::net::{get_json, run_jobs, Job};
use crate::state::Ui;

const MANIFEST: &str = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
const JAVA_ALL: &str = "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";
const RESOURCES: &str = "https://resources.download.minecraft.net";
const LIBRARIES: &str = "https://libraries.minecraft.net/";

pub const LOADERS: [&str; 3] = ["Vanilla", "Fabric", "Quilt"];

pub const OS_NAME: &str = if cfg!(windows) { "windows" } else if cfg!(target_os = "macos") { "osx" } else { "linux" };
const ARCH: &str = std::env::consts::ARCH;

#[derive(Deserialize, Clone)]
pub struct VersionEntry {
    pub id: String,
    #[serde(rename = "type")]
    pub kind: String,
    #[serde(default)]
    pub url: String,
    #[serde(default)]
    pub sha1: String,
}

pub async fn fetch_manifest(client: &Client) -> Result<Vec<VersionEntry>> {
    let m = get_json(client, MANIFEST).await?;
    Ok(serde_json::from_value(m["versions"].clone())?)
}

/// Lokal installierte Versionen (fuer den Offline-Betrieb).
pub fn installed(game_dir: &str) -> Vec<VersionEntry> {
    let mut out = Vec::new();
    if let Ok(rd) = std::fs::read_dir(Path::new(game_dir).join("versions")) {
        for e in rd.flatten() {
            let id = e.file_name().to_string_lossy().into_owned();
            if e.path().join(format!("{id}.json")).exists() {
                out.push(VersionEntry { id, kind: "release".into(), url: String::new(), sha1: String::new() });
            }
        }
    }
    out.sort_by(|a, b| b.id.cmp(&a.id));
    out
}

// ------------------------------------------------------------------ Regeln
pub struct Features {
    pub demo: bool,
    pub custom_res: bool,
}

fn rule_matches(r: &Value, f: &Features) -> bool {
    if let Some(os) = r.get("os") {
        if let Some(n) = os["name"].as_str() {
            if n != OS_NAME {
                return false;
            }
        }
        if let Some(a) = os["arch"].as_str() {
            let cur = match ARCH {
                "x86_64" => "x86_64",
                "aarch64" => "arm64",
                o => o,
            };
            if a != cur {
                return false;
            }
        }
    }
    if let Some(fs) = r["features"].as_object() {
        for (k, v) in fs {
            let have = match k.as_str() {
                "is_demo_user" => f.demo,
                "has_custom_resolution" => f.custom_res,
                _ => false,
            };
            if v.as_bool() != Some(have) {
                return false;
            }
        }
    }
    true
}

pub fn rules_ok(rules: Option<&Value>, f: &Features) -> bool {
    let Some(arr) = rules.and_then(|r| r.as_array()) else { return true };
    let mut ok = false;
    for r in arr {
        if rule_matches(r, f) {
            ok = r["action"].as_str() == Some("allow");
        }
    }
    ok
}

// ------------------------------------------------------------------ Versions-JSON
pub fn version_json_path(gd: &Path, id: &str) -> PathBuf {
    gd.join("versions").join(id).join(format!("{id}.json"))
}

fn read_json(p: &Path) -> Result<Value> {
    let data = std::fs::read(p).with_context(|| format!("{} fehlt", p.display()))?;
    Ok(serde_json::from_slice(&data)?)
}

fn lib_key(lib: &Value) -> String {
    let name = lib["name"].as_str().unwrap_or("");
    let p: Vec<&str> = name.split(['@', ':']).collect();
    format!("{}:{}:{}", p.first().unwrap_or(&""), p.get(1).unwrap_or(&""), p.get(3).unwrap_or(&""))
}

fn merge(mut parent: Value, child: &Value) -> Value {
    let Some(obj) = child.as_object() else { return parent };
    for (k, v) in obj {
        match k.as_str() {
            "libraries" => {
                let mut libs = v.as_array().cloned().unwrap_or_default();
                let keys: std::collections::HashSet<String> = libs.iter().map(lib_key).collect();
                for l in parent["libraries"].as_array().cloned().unwrap_or_default() {
                    if !keys.contains(&lib_key(&l)) {
                        libs.push(l);
                    }
                }
                parent["libraries"] = Value::Array(libs);
            }
            "arguments" => {
                for kind in ["game", "jvm"] {
                    if let Some(extra) = v[kind].as_array() {
                        let mut base = parent["arguments"][kind].as_array().cloned().unwrap_or_default();
                        base.extend(extra.iter().cloned());
                        parent["arguments"][kind] = Value::Array(base);
                    }
                }
            }
            "inheritsFrom" | "__jar" => {}
            _ => parent[k.as_str()] = v.clone(),
        }
    }
    parent
}

/// Laedt eine Version inkl. Eltern-Version (Fabric/Quilt erben von Vanilla).
pub fn load_merged(gd: &Path, id: &str) -> Result<Value> {
    let mut j = read_json(&version_json_path(gd, id))?;
    if let Some(parent_id) = j["inheritsFrom"].as_str().map(String::from) {
        let parent = load_merged(gd, &parent_id)?;
        let jar = parent["__jar"].as_str().unwrap_or(&parent_id).to_string();
        j = merge(parent, &j);
        j["__jar"] = json!(jar);
    } else {
        j["__jar"] = json!(id);
    }
    Ok(j)
}

pub fn jar_path(gd: &Path, json: &Value) -> PathBuf {
    let jar = json["__jar"].as_str().unwrap_or("");
    gd.join("versions").join(jar).join(format!("{jar}.jar"))
}

// ------------------------------------------------------------------ Bibliotheken
pub struct Lib {
    pub path: PathBuf,
    pub url: String,
    pub sha1: Option<String>,
    pub size: Option<u64>,
    pub classpath: bool,
    pub extract: Option<Vec<String>>,
}

fn maven_path(name: &str) -> Option<String> {
    let (main, ext) = name.split_once('@').unwrap_or((name, "jar"));
    let p: Vec<&str> = main.split(':').collect();
    if p.len() < 3 {
        return None;
    }
    let file = match p.get(3) {
        Some(c) => format!("{}-{}-{}.{}", p[1], p[2], c, ext),
        None => format!("{}-{}.{}", p[1], p[2], ext),
    };
    Some(format!("{}/{}/{}/{}", p[0].replace('.', "/"), p[1], p[2], file))
}

fn wrong_arch(classifier: &str) -> bool {
    let bad: &[&str] = match ARCH {
        "x86_64" => &["-arm64", "-arm32", "-x86"],
        "aarch64" => &["-arm32", "-x86"],
        "x86" => &["-arm64", "-arm32"],
        _ => &[],
    };
    classifier.starts_with("natives-") && bad.iter().any(|b| classifier.ends_with(b))
}

fn artifact(d: &Value, libdir: &Path, fallback: &str) -> (PathBuf, String, Option<String>, Option<u64>) {
    let rel = d["path"].as_str().map(String::from).unwrap_or_else(|| fallback.to_string());
    (
        libdir.join(&rel),
        d["url"].as_str().unwrap_or("").to_string(),
        d["sha1"].as_str().map(String::from),
        d["size"].as_u64(),
    )
}

pub fn collect_libs(json: &Value, libdir: &Path, f: &Features) -> Vec<Lib> {
    let mut out = Vec::new();
    for lib in json["libraries"].as_array().into_iter().flatten() {
        if !rules_ok(lib.get("rules"), f) {
            continue;
        }
        let name = lib["name"].as_str().unwrap_or("");
        // Alte Natives (bis 1.18): eigene Klassifizierer, werden entpackt
        if let Some(nat) = lib.get("natives") {
            if let Some(cls) = nat[OS_NAME].as_str() {
                let cls = cls.replace("${arch}", if ARCH == "x86" { "32" } else { "64" });
                let fallback = maven_path(&format!("{name}:{cls}")).unwrap_or_default();
                let d = &lib["downloads"]["classifiers"][cls.as_str()];
                let (path, mut url, sha1, size) = artifact(d, libdir, &fallback);
                if !d.is_object() {
                    url = format!("{LIBRARIES}{fallback}");
                }
                let excl = lib["extract"]["exclude"]
                    .as_array()
                    .map(|a| a.iter().filter_map(|x| x.as_str().map(String::from)).collect())
                    .unwrap_or_else(|| vec!["META-INF/".to_string()]);
                out.push(Lib { path, url, sha1, size, classpath: false, extract: Some(excl) });
            }
            let a = &lib["downloads"]["artifact"];
            if a.is_object() {
                let (path, url, sha1, size) = artifact(a, libdir, &maven_path(name).unwrap_or_default());
                out.push(Lib { path, url, sha1, size, classpath: true, extract: None });
            }
            continue;
        }
        if let Some(cls) = name.split('@').next().and_then(|n| n.split(':').nth(3)) {
            if wrong_arch(cls) {
                continue;
            }
        }
        let Some(rel) = maven_path(name) else { continue };
        let a = &lib["downloads"]["artifact"];
        let (path, url, sha1, size) = if a.is_object() {
            artifact(a, libdir, &rel)
        } else if lib["downloads"].is_object() {
            continue;
        } else {
            // Fabric/Quilt: Maven-Repository steht im Feld "url"
            let mut base = lib["url"].as_str().unwrap_or(LIBRARIES).to_string();
            if !base.ends_with('/') {
                base.push('/');
            }
            (libdir.join(&rel), format!("{base}{rel}"), lib["sha1"].as_str().map(String::from), lib["size"].as_u64())
        };
        out.push(Lib { path, url, sha1, size, classpath: true, extract: None });
    }
    out
}

// ------------------------------------------------------------------ Java
fn java_platform() -> &'static str {
    match (OS_NAME, ARCH) {
        ("windows", "aarch64") => "windows-arm64",
        ("windows", "x86") => "windows-x86",
        ("windows", _) => "windows-x64",
        ("osx", "aarch64") => "mac-os-arm64",
        ("osx", _) => "mac-os",
        ("linux", "x86") => "linux-i386",
        _ => "linux",
    }
}

pub fn java_component(json: &Value) -> String {
    json["javaVersion"]["component"].as_str().unwrap_or("jre-legacy").to_string()
}

pub fn java_path(cfg: &Config, json: &Value) -> PathBuf {
    if !cfg.java_path.trim().is_empty() {
        return PathBuf::from(cfg.java_path.trim());
    }
    let root = base_dir().join("runtime").join(java_component(json));
    let bin = match OS_NAME {
        "windows" => root.join("bin").join("javaw.exe"),
        "osx" => root.join("jre.bundle/Contents/Home/bin/java"),
        _ => root.join("bin").join("java"),
    };
    if bin.exists() {
        bin
    } else {
        PathBuf::from(if OS_NAME == "windows" { "javaw" } else { "java" })
    }
}

async fn java_jobs(ui: &Ui, client: &Client, component: &str) -> Result<(Vec<Job>, Vec<(PathBuf, String)>)> {
    let all = get_json(client, JAVA_ALL).await?;
    let Some(entry) = all[java_platform()][component].as_array().and_then(|a| a.first()) else {
        ui.log(format!("[Launcher] Kein Java '{component}' für diese Plattform – nutze installiertes Java."));
        return Ok((vec![], vec![]));
    };
    let url = entry["manifest"]["url"].as_str().context("Java-Manifest fehlt")?;
    let manifest = get_json(client, url).await?;
    let root = base_dir().join("runtime").join(component);
    let (mut jobs, mut links) = (Vec::new(), Vec::new());
    for (p, f) in manifest["files"].as_object().into_iter().flatten() {
        let dest = root.join(p);
        match f["type"].as_str() {
            Some("file") => {
                let raw = &f["downloads"]["raw"];
                jobs.push(Job {
                    url: raw["url"].as_str().unwrap_or("").into(),
                    path: dest,
                    sha1: raw["sha1"].as_str().map(String::from),
                    size: raw["size"].as_u64(),
                    exec: f["executable"].as_bool().unwrap_or(false),
                });
            }
            Some("link") => links.push((dest, f["target"].as_str().unwrap_or("").to_string())),
            Some("directory") => std::fs::create_dir_all(dest)?,
            _ => {}
        }
    }
    Ok((jobs, links))
}

// ------------------------------------------------------------------ Modloader
async fn install_loader(client: &Client, gd: &Path, mc: &str, loader: &str) -> Result<String> {
    let base = match loader {
        "Fabric" => "https://meta.fabricmc.net/v2",
        "Quilt" => "https://meta.quiltmc.org/v3",
        _ => bail!("Unbekannter Loader {loader}"),
    };
    let list = get_json(client, &format!("{base}/versions/loader/{mc}")).await
        .with_context(|| format!("{loader} unterstützt Minecraft {mc} nicht"))?;
    let arr = list.as_array().filter(|a| !a.is_empty())
        .with_context(|| format!("{loader} unterstützt Minecraft {mc} nicht"))?;
    let pick = arr.iter()
        .find(|e| e["loader"]["stable"].as_bool() == Some(true))
        .or_else(|| arr.iter().find(|e| !e["loader"]["version"].as_str().unwrap_or("").contains("beta")))
        .unwrap_or(&arr[0]);
    let lv = pick["loader"]["version"].as_str().context("Loader-Version fehlt")?;
    let profile = get_json(client, &format!("{base}/versions/loader/{mc}/{lv}/profile/json")).await?;
    let id = profile["id"].as_str().context("Profil-ID fehlt")?.to_string();
    let p = version_json_path(gd, &id);
    std::fs::create_dir_all(p.parent().unwrap())?;
    std::fs::write(&p, serde_json::to_vec_pretty(&profile)?)?;
    Ok(id)
}

// ------------------------------------------------------------------ Hauptablauf
fn marker(gd: &Path, version: &str, loader: &str) -> PathBuf {
    gd.join("versions").join(version).join(format!(".ready-{}", loader.to_lowercase()))
}

/// Stellt sicher, dass alles da ist, und liefert die startbare Versions-ID.
/// Wurde die Version schon komplett installiert, geht das sofort (keine Pruefung).
pub async fn prepare(ui: &Ui, client: &Client, cfg: &Config, version: &str, loader: &str, repair: bool) -> Result<String> {
    let gd = PathBuf::from(&cfg.game_dir);
    let mark = marker(&gd, version, loader);
    if !repair {
        if let Ok(id) = std::fs::read_to_string(&mark) {
            if version_json_path(&gd, id.trim()).exists() {
                ui.log("[Launcher] Schnellstart – Version ist bereits installiert.");
                return Ok(id.trim().to_string());
            }
        }
    }
    let t0 = std::time::Instant::now();
    ui.status("Lade Versionsinfos …");
    let manifest = fetch_manifest(client).await?;
    let entry = manifest.iter().find(|e| e.id == version)
        .with_context(|| format!("Version {version} nicht gefunden"))?;
    let vj = Job { url: entry.url.clone(), path: version_json_path(&gd, version), sha1: Some(entry.sha1.clone()), size: None, exec: false };
    run_jobs(ui, client, vec![vj], true, "Lade Versionsinfos …").await?;

    let launch_id = if loader == "Vanilla" {
        version.to_string()
    } else {
        ui.status(format!("Installiere {loader} …"));
        install_loader(client, &gd, version, loader).await?
    };
    let json = load_merged(&gd, &launch_id)?;
    let libdir = gd.join("libraries");
    let assets = gd.join("assets");
    let feats = Features { demo: false, custom_res: true };

    // Asset-Index zuerst (wird gebraucht, um die Asset-Liste zu kennen)
    let ai = &json["assetIndex"];
    let ai_id = ai["id"].as_str().context("assetIndex fehlt")?;
    let index_path = assets.join("indexes").join(format!("{ai_id}.json"));
    let ij = Job { url: ai["url"].as_str().unwrap_or("").into(), path: index_path.clone(), sha1: ai["sha1"].as_str().map(String::from), size: None, exec: false };
    run_jobs(ui, client, vec![ij], true, "Lade Asset-Index …").await?;
    let index = read_json(&index_path)?;

    let mut jobs = Vec::new();
    let client_dl = &json["downloads"]["client"];
    jobs.push(Job {
        url: client_dl["url"].as_str().unwrap_or("").into(),
        path: jar_path(&gd, &json),
        sha1: client_dl["sha1"].as_str().map(String::from),
        size: client_dl["size"].as_u64(),
        exec: false,
    });
    let libs = collect_libs(&json, &libdir, &feats);
    for l in &libs {
        jobs.push(Job { url: l.url.clone(), path: l.path.clone(), sha1: l.sha1.clone(), size: l.size, exec: false });
    }
    for o in index["objects"].as_object().into_iter().flatten().map(|(_, o)| o) {
        let Some(h) = o["hash"].as_str() else { continue };
        jobs.push(Job {
            url: format!("{RESOURCES}/{}/{h}", &h[..2]),
            path: assets.join("objects").join(&h[..2]).join(h),
            sha1: Some(h.into()),
            size: o["size"].as_u64(),
            exec: false,
        });
    }
    let log_file = &json["logging"]["client"]["file"];
    if let Some(id) = log_file["id"].as_str() {
        jobs.push(Job {
            url: log_file["url"].as_str().unwrap_or("").into(),
            path: assets.join("log_configs").join(id),
            sha1: log_file["sha1"].as_str().map(String::from),
            size: log_file["size"].as_u64(),
            exec: false,
        });
    }
    let mut links = Vec::new();
    if cfg.java_path.trim().is_empty() {
        ui.status("Prüfe Java …");
        let (jj, ll) = java_jobs(ui, client, &java_component(&json)).await?;
        jobs.extend(jj);
        links = ll;
    }
    let n = jobs.len();
    run_jobs(ui, client, jobs, repair, "Lade Spieldateien …").await?;

    #[cfg(unix)]
    for (dest, target) in links {
        if std::fs::symlink_metadata(&dest).is_err() {
            if let Some(d) = dest.parent() {
                std::fs::create_dir_all(d)?;
            }
            let _ = std::os::unix::fs::symlink(target, &dest);
        }
    }
    #[cfg(not(unix))]
    let _ = links;

    ui.status("Entpacke Natives …");
    let natives = gd.join("versions").join(&launch_id).join("natives");
    let _ = std::fs::remove_dir_all(&natives);
    std::fs::create_dir_all(&natives)?;
    for l in libs.iter().filter(|l| l.extract.is_some()) {
        extract_natives(&l.path, &natives, l.extract.as_deref().unwrap_or(&[]))?;
    }

    // Sehr alte Versionen erwarten die Assets unter echten Dateinamen
    let virt = index["virtual"].as_bool().unwrap_or(false);
    let res = index["map_to_resources"].as_bool().unwrap_or(false);
    if virt || res {
        ui.status("Bereite alte Assets vor …");
        let target = if res { gd.join("resources") } else { assets.join("virtual").join("legacy") };
        for (name, o) in index["objects"].as_object().into_iter().flatten() {
            let Some(h) = o["hash"].as_str() else { continue };
            let dest = target.join(name);
            if !dest.exists() {
                if let Some(d) = dest.parent() {
                    std::fs::create_dir_all(d)?;
                }
                std::fs::copy(assets.join("objects").join(&h[..2]).join(h), dest)?;
            }
        }
    }
    std::fs::write(&mark, &launch_id)?;
    ui.log(format!("[Launcher] {n} Dateien geprüft/geladen in {:.1} s", t0.elapsed().as_secs_f32()));
    Ok(launch_id)
}

fn extract_natives(jar: &Path, dest: &Path, exclude: &[String]) -> Result<()> {
    let file = std::fs::File::open(jar).with_context(|| format!("{} fehlt", jar.display()))?;
    let mut zip = zip::ZipArchive::new(file)?;
    for i in 0..zip.len() {
        let mut entry = zip.by_index(i)?;
        if entry.is_dir() {
            continue;
        }
        let Some(rel) = entry.enclosed_name() else { continue };
        let name = rel.to_string_lossy().replace('\\', "/");
        if exclude.iter().any(|e| name.starts_with(e.as_str())) {
            continue;
        }
        let out = dest.join(&rel);
        if let Some(d) = out.parent() {
            std::fs::create_dir_all(d)?;
        }
        let mut f = std::fs::File::create(out)?;
        std::io::copy(&mut entry, &mut f)?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn maven() {
        assert_eq!(maven_path("net.fabricmc:fabric-loader:0.16.9").unwrap(),
                   "net/fabricmc/fabric-loader/0.16.9/fabric-loader-0.16.9.jar");
        assert_eq!(maven_path("org.lwjgl:lwjgl:3.3.3:natives-linux").unwrap(),
                   "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-linux.jar");
        assert_eq!(maven_path("a.b:c:1@zip").unwrap(), "a/b/c/1/c-1.zip");
    }

    #[test]
    fn rules() {
        let f = Features { demo: true, custom_res: false };
        assert!(rules_ok(None, &f));
        let r = json!([{"action":"allow"},{"action":"disallow","os":{"name":OS_NAME}}]);
        assert!(!rules_ok(Some(&r), &f));
        let r = json!([{"action":"allow","features":{"is_demo_user":true}}]);
        assert!(rules_ok(Some(&r), &f));
        let r = json!([{"action":"allow","features":{"has_custom_resolution":true}}]);
        assert!(!rules_ok(Some(&r), &f));
    }

    #[test]
    fn fabric_merge_and_libs() {
        let dir = std::env::temp_dir().join(format!("gl-test-{}", std::process::id()));
        let gd = dir.as_path();
        let parent = json!({"id":"1.21","type":"release","mainClass":"net.minecraft.client.main.Main",
            "assetIndex":{"id":"17"},"javaVersion":{"component":"java-runtime-delta"},
            "arguments":{"game":["--username","${auth_player_name}"],"jvm":["-cp","${classpath}"]},
            "libraries":[
                {"name":"org.ow2.asm:asm:9.3","downloads":{"artifact":{"path":"org/ow2/asm/asm/9.3/asm-9.3.jar","url":"u","sha1":"x","size":1}}},
                {"name":"org.lwjgl:lwjgl:3.3.3:natives-linux","downloads":{"artifact":{"path":"l.jar","url":"u"}},"rules":[{"action":"allow","os":{"name":"linux"}}]},
                {"name":"org.lwjgl:lwjgl:3.3.3:natives-linux-arm64","downloads":{"artifact":{"path":"la.jar","url":"u"}},"rules":[{"action":"allow","os":{"name":"linux"}}]}
            ]});
        let child = json!({"id":"fabric-loader-0.16-1.21","inheritsFrom":"1.21","mainClass":"net.fabricmc.loader.impl.launch.knot.KnotClient",
            "arguments":{"game":[],"jvm":["-DFabricMcEmu= net.minecraft.client.main.Main "]},
            "libraries":[{"name":"org.ow2.asm:asm:9.7","url":"https://maven.fabricmc.net/","sha1":"abc"},
                         {"name":"net.fabricmc:fabric-loader:0.16","url":"https://maven.fabricmc.net"}]});
        for (id, j) in [("1.21", &parent), ("fabric-loader-0.16-1.21", &child)] {
            let p = version_json_path(gd, id);
            std::fs::create_dir_all(p.parent().unwrap()).unwrap();
            std::fs::write(p, j.to_string()).unwrap();
        }
        let m = load_merged(gd, "fabric-loader-0.16-1.21").unwrap();
        assert_eq!(m["mainClass"], "net.fabricmc.loader.impl.launch.knot.KnotClient");
        assert_eq!(m["__jar"], "1.21");
        assert_eq!(m["arguments"]["jvm"].as_array().unwrap().len(), 3);
        let libs = collect_libs(&m, &gd.join("libraries"), &Features { demo: false, custom_res: true });
        let names: Vec<String> = libs.iter().map(|l| l.url.clone()).collect();
        // Fabric-ASM ersetzt Vanilla-ASM
        assert!(names.contains(&"https://maven.fabricmc.net/org/ow2/asm/asm/9.7/asm-9.7.jar".to_string()));
        assert!(names.contains(&"https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.16/fabric-loader-0.16.jar".to_string()));
        assert!(!libs.iter().any(|l| l.path.ends_with("asm-9.3.jar")));
        if OS_NAME == "linux" && ARCH == "x86_64" {
            assert!(libs.iter().any(|l| l.path.ends_with("l.jar")));
            assert!(!libs.iter().any(|l| l.path.ends_with("la.jar")));
        }

        // Kommandozeile bauen (Demo-Modus)
        let cfg = Config { game_dir: gd.to_string_lossy().into(), ..Default::default() };
        let (_java, args) = crate::launch::build(&cfg, "fabric-loader-0.16-1.21", None).unwrap();
        let joined = args.join(" ");
        assert!(joined.contains("-Xmx4096M"));
        assert!(joined.contains("KnotClient --username Demo"));
        assert!(joined.contains("asm-9.7.jar"));
        assert!(joined.contains("1.21.jar"));
        assert!(!joined.contains("${"), "{joined}");
        println!("{joined}");
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn legacy_args() {
        let dir = std::env::temp_dir().join(format!("gl-legacy-{}", std::process::id()));
        let j = json!({"id":"1.8.9","type":"release","mainClass":"net.minecraft.client.main.Main","assetIndex":{"id":"1.8"},
            "minecraftArguments":"--username ${auth_player_name} --session ${auth_session} --assetsDir ${assets_root}",
            "libraries":[{"name":"org.lwjgl.lwjgl:lwjgl-platform:2.9.4","natives":{"linux":"natives-linux","windows":"natives-windows-${arch}","osx":"natives-osx"},
                          "downloads":{"classifiers":{"natives-linux":{"path":"n.jar","url":"u"},"natives-windows-64":{"path":"n.jar","url":"u"},"natives-osx":{"path":"n.jar","url":"u"}}}}]});
        let p = version_json_path(&dir, "1.8.9");
        std::fs::create_dir_all(p.parent().unwrap()).unwrap();
        std::fs::write(p, j.to_string()).unwrap();
        let m = load_merged(&dir, "1.8.9").unwrap();
        let libs = collect_libs(&m, &dir.join("libraries"), &Features { demo: true, custom_res: true });
        assert_eq!(libs.len(), 1);
        assert!(libs[0].extract.is_some() && !libs[0].classpath);
        let acc = crate::config::Account { name: "Steve".into(), id: "uuid".into(), access_token: "tok".into(), refresh_token: String::new() };
        let cfg = Config { game_dir: dir.to_string_lossy().into(), ..Default::default() };
        let (_j, args) = crate::launch::build(&cfg, "1.8.9", Some(&acc)).unwrap();
        let joined = args.join(" ");
        assert!(joined.contains("-Djava.library.path="));
        assert!(joined.contains("--username Steve --session token:tok:uuid"));
        assert!(!joined.contains("--demo"));
        let _ = std::fs::remove_dir_all(dir);
    }
}
