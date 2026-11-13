//! Baut die Java-Kommandozeile und startet das Spiel.
use std::collections::HashMap;
use std::io::{BufRead, BufReader};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

use anyhow::{Context, Result};
use serde_json::Value;

use crate::config::{Account, Config, LAUNCHER_NAME, LAUNCHER_VERSION};
use crate::install::{self, Features, OS_NAME};
use crate::state::Ui;

fn push_args(out: &mut Vec<String>, arr: &[Value], f: &Features) {
    for a in arr {
        match a {
            Value::String(s) => out.push(s.clone()),
            Value::Object(_) if install::rules_ok(a.get("rules"), f) => match &a["value"] {
                Value::String(s) => out.push(s.clone()),
                Value::Array(v) => out.extend(v.iter().filter_map(|x| x.as_str().map(String::from))),
                _ => {}
            },
            _ => {}
        }
    }
}

/// `run_dir`: eigener Spielordner (Profil) – sonst der gemeinsame Spielordner.
pub fn build(cfg: &Config, launch_id: &str, account: Option<&Account>, run_dir: Option<&Path>) -> Result<(PathBuf, Vec<String>)> {
    let gd = PathBuf::from(&cfg.game_dir);
    let run = run_dir.map(Path::to_path_buf).unwrap_or_else(|| gd.clone());
    let json = install::load_merged(&gd, launch_id)?;
    let demo = account.is_none();
    let f = Features { demo, custom_res: true };
    let libdir = gd.join("libraries");
    let assets = gd.join("assets");
    let natives = gd.join("versions").join(launch_id).join("natives");

    let sep = if OS_NAME == "windows" { ";" } else { ":" };
    let mut cp: Vec<String> = Vec::new();
    for l in install::collect_libs(&json, &libdir, &f).into_iter().filter(|l| l.classpath) {
        let p = l.path.to_string_lossy().into_owned();
        if !cp.contains(&p) {
            cp.push(p);
        }
    }
    cp.push(install::jar_path(&gd, &json).to_string_lossy().into_owned());

    let ai_id = json["assetIndex"]["id"].as_str().unwrap_or("legacy").to_string();
    let index: Value = std::fs::read(assets.join("indexes").join(format!("{ai_id}.json")))
        .ok()
        .and_then(|d| serde_json::from_slice(&d).ok())
        .unwrap_or(Value::Null);
    let game_assets = if index["map_to_resources"].as_bool() == Some(true) {
        gd.join("resources")
    } else if index["virtual"].as_bool() == Some(true) {
        assets.join("virtual").join("legacy")
    } else {
        assets.clone()
    };

    let (name, uuid, token) = match account {
        Some(a) => (a.name.clone(), a.id.clone(), a.access_token.clone()),
        None => ("Demo".to_string(), hex::encode(rand::random::<[u8; 16]>()), "0".to_string()),
    };
    let s = |p: &Path| p.to_string_lossy().into_owned();
    let vars: HashMap<&str, String> = HashMap::from([
        ("auth_player_name", name),
        ("version_name", launch_id.to_string()),
        ("game_directory", s(&run)),
        ("assets_root", s(&assets)),
        ("game_assets", s(&game_assets)),
        ("assets_index_name", ai_id),
        ("auth_session", format!("token:{token}:{uuid}")),
        ("auth_uuid", uuid),
        ("auth_access_token", token),
        ("clientid", String::new()),
        ("auth_xuid", "0".into()),
        ("user_type", "msa".into()),
        ("user_properties", "{}".into()),
        ("version_type", json["type"].as_str().unwrap_or("release").into()),
        ("resolution_width", cfg.width.to_string()),
        ("resolution_height", cfg.height.to_string()),
        ("natives_directory", s(&natives)),
        ("launcher_name", LAUNCHER_NAME.into()),
        ("launcher_version", LAUNCHER_VERSION.into()),
        ("classpath", cp.join(sep)),
        ("classpath_separator", sep.into()),
        ("library_directory", s(&libdir)),
    ]);
    let subst = |a: String| -> String {
        if !a.contains("${") {
            return a;
        }
        vars.iter().fold(a, |acc, (k, v)| acc.replace(&format!("${{{k}}}"), v))
    };

    // JVM-Argumente
    let mut jvm = vec![format!("-Xmx{}M", cfg.ram_mb), format!("-Xms{}M", cfg.ram_mb.min(1024))];
    // Schnellere Ladezeiten / weniger Ruckler mit G1 (wie der offizielle Launcher)
    jvm.extend(
        ["-XX:+UnlockExperimentalVMOptions", "-XX:+UseG1GC", "-XX:G1NewSizePercent=20",
         "-XX:G1ReservePercent=20", "-XX:MaxGCPauseMillis=50", "-XX:G1HeapRegionSize=32M"]
            .map(String::from),
    );
    jvm.extend(cfg.extra_jvm_args.split_whitespace().map(String::from));
    let log_cfg = &json["logging"]["client"];
    if let (Some(arg), Some(id)) = (log_cfg["argument"].as_str(), log_cfg["file"]["id"].as_str()) {
        let p = assets.join("log_configs").join(id);
        if p.exists() {
            jvm.push(arg.replace("${path}", &s(&p)));
        }
    }
    let mut version_jvm = Vec::new();
    if let Some(arr) = json["arguments"]["jvm"].as_array() {
        push_args(&mut version_jvm, arr, &f);
    }
    if !version_jvm.iter().any(|a| a.contains("${classpath}")) {
        if OS_NAME == "osx" {
            jvm.push("-XstartOnFirstThread".into());
        }
        jvm.extend(["-Djava.library.path=${natives_directory}".into(), "-cp".into(), "${classpath}".into()]);
    }
    jvm.extend(version_jvm);

    // Spiel-Argumente
    let mut game = Vec::new();
    if let Some(old) = json["minecraftArguments"].as_str() {
        game.extend(old.split_whitespace().map(String::from));
        if demo {
            game.push("--demo".into());
        }
        game.extend(["--width".into(), cfg.width.to_string(), "--height".into(), cfg.height.to_string()]);
    }
    if let Some(arr) = json["arguments"]["game"].as_array() {
        push_args(&mut game, arr, &f);
    }

    let main = json["mainClass"].as_str().context("mainClass fehlt")?.to_string();
    let mut args: Vec<String> = jvm.into_iter().map(&subst).collect();
    args.push(main);
    args.extend(game.into_iter().map(&subst));
    Ok((install::java_path(cfg, &json), args))
}

pub fn spawn(java: &Path, args: &[String], game_dir: &Path, ui: Ui, detach: bool) -> Result<()> {
    std::fs::create_dir_all(game_dir)?;
    let mut cmd = Command::new(java);
    cmd.args(args).current_dir(game_dir).stdin(Stdio::null());
    if detach {
        cmd.stdout(Stdio::null()).stderr(Stdio::null());
    } else {
        cmd.stdout(Stdio::piped()).stderr(Stdio::piped());
    }
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
    }
    let mut child = cmd.spawn().with_context(|| format!("Java konnte nicht gestartet werden: {}", java.display()))?;
    if detach {
        return Ok(());
    }
    let readers: Vec<Box<dyn std::io::Read + Send>> = vec![
        Box::new(child.stdout.take().unwrap()),
        Box::new(child.stderr.take().unwrap()),
    ];
    for r in readers {
        let ui = ui.clone();
        std::thread::spawn(move || {
            for line in BufReader::new(r).lines().map_while(Result::ok) {
                ui.log(line);
            }
        });
    }
    std::thread::spawn(move || {
        let code = child.wait().map(|s| s.to_string()).unwrap_or_default();
        ui.log(format!("[Launcher] Spiel beendet ({code})"));
        ui.status("Bereit.");
    });
    Ok(())
}
