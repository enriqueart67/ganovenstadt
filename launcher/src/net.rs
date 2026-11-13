//! Paralleler Downloader: laedt hunderte Dateien gleichzeitig ueber HTTP/2.
use std::collections::HashSet;
use std::path::PathBuf;
use std::time::Duration;

use anyhow::{anyhow, bail, Context, Result};
use futures::{stream, StreamExt};
use reqwest::Client;
use sha1::{Digest, Sha1};

use crate::state::Ui;

const PARALLEL: usize = 64;

pub fn client() -> Client {
    Client::builder()
        .user_agent(concat!("skyrush/", env!("CARGO_PKG_VERSION")))
        .connect_timeout(Duration::from_secs(15))
        .timeout(Duration::from_secs(300))
        .pool_max_idle_per_host(PARALLEL)
        .build()
        .expect("HTTP-Client")
}

#[derive(Clone, Debug)]
pub struct Job {
    pub url: String,
    pub path: PathBuf,
    pub sha1: Option<String>,
    pub size: Option<u64>,
    pub exec: bool,
}

pub async fn get_json(client: &Client, url: &str) -> Result<serde_json::Value> {
    Ok(client.get(url).send().await?.error_for_status()?.json().await?)
}

fn sha1_hex(data: &[u8]) -> String {
    hex::encode(Sha1::digest(data))
}

/// Laedt alle Jobs parallel. `verify` = jede vorhandene Datei per SHA1 pruefen
/// (sonst reicht die Dateigroesse – das ist der schnelle Weg).
pub async fn run_jobs(ui: &Ui, client: &Client, jobs: Vec<Job>, verify: bool, label: &str) -> Result<()> {
    let mut seen = HashSet::new();
    let jobs: Vec<Job> = jobs.into_iter().filter(|j| seen.insert(j.path.clone())).collect();
    if jobs.is_empty() {
        return Ok(());
    }
    ui.status(label);
    ui.progress(0, jobs.len());
    let errors: Vec<anyhow::Error> = stream::iter(jobs)
        .map(|j| {
            let client = client.clone();
            async move {
                ensure(&client, &j, verify)
                    .await
                    .with_context(|| format!("Download fehlgeschlagen: {}", j.url))
            }
        })
        .buffer_unordered(PARALLEL)
        .filter_map(|r| {
            ui.inc();
            async move { r.err() }
        })
        .collect()
        .await;
    match errors.into_iter().next() {
        Some(e) => Err(e),
        None => Ok(()),
    }
}

async fn ensure(client: &Client, j: &Job, verify: bool) -> Result<()> {
    if let Ok(meta) = tokio::fs::metadata(&j.path).await {
        let ok = if verify || j.size.is_none() {
            match &j.sha1 {
                Some(h) => sha1_hex(&tokio::fs::read(&j.path).await?).eq_ignore_ascii_case(h),
                None => true,
            }
        } else {
            Some(meta.len()) == j.size
        };
        if ok {
            return Ok(());
        }
    }
    if j.url.is_empty() {
        bail!("keine Download-Adresse für {}", j.path.display());
    }
    let mut last = anyhow!("unbekannter Fehler");
    for attempt in 0..3u64 {
        if attempt > 0 {
            tokio::time::sleep(Duration::from_millis(400 * attempt)).await;
        }
        let bytes = match fetch(client, &j.url).await {
            Ok(b) => b,
            Err(e) => {
                last = e;
                continue;
            }
        };
        if let Some(h) = &j.sha1 {
            if !sha1_hex(&bytes).eq_ignore_ascii_case(h) {
                last = anyhow!("Prüfsumme stimmt nicht");
                continue;
            }
        }
        if let Some(dir) = j.path.parent() {
            tokio::fs::create_dir_all(dir).await?;
        }
        let tmp = PathBuf::from(format!("{}.part", j.path.display()));
        tokio::fs::write(&tmp, &bytes).await?;
        tokio::fs::rename(&tmp, &j.path).await?;
        #[cfg(unix)]
        if j.exec {
            use std::os::unix::fs::PermissionsExt;
            tokio::fs::set_permissions(&j.path, std::fs::Permissions::from_mode(0o755)).await?;
        }
        return Ok(());
    }
    Err(last)
}

async fn fetch(client: &Client, url: &str) -> Result<Vec<u8>> {
    Ok(client.get(url).send().await?.error_for_status()?.bytes().await?.to_vec())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parallel_download() {
        let Ok(src) = std::env::var("GL_TEST_SRV") else { return };
        let rt = tokio::runtime::Runtime::new().unwrap();
        let ui = Ui::new(eframe::egui::Context::default());
        let out = std::env::temp_dir().join(format!("gl-dl-{}", std::process::id()));
        let client = client();
        let mk = |i: usize| {
            let data = std::fs::read(format!("{src}/f{i}")).unwrap();
            Job { url: format!("http://127.0.0.1:8799/f{i}"), path: out.join(format!("d/{i}")),
                  sha1: Some(sha1_hex(&data)), size: Some(data.len() as u64), exec: false }
        };
        let jobs: Vec<Job> = (1..=300).map(mk).collect();
        let t = std::time::Instant::now();
        rt.block_on(run_jobs(&ui, &client, jobs.clone(), false, "x")).unwrap();
        println!("300 Dateien: {:?}", t.elapsed());
        assert_eq!(ui.lock().done, 300);
        for j in &jobs {
            assert_eq!(std::fs::metadata(&j.path).unwrap().len(), j.size.unwrap());
        }
        // Zweiter Lauf: nichts wird geladen (schneller Weg)
        let t = std::time::Instant::now();
        rt.block_on(run_jobs(&ui, &client, jobs.clone(), false, "x")).unwrap();
        println!("Schnellpruefung: {:?}", t.elapsed());
        // Kaputte Pruefsumme -> Fehler
        let mut bad = mk(1);
        bad.path = out.join("bad");
        bad.sha1 = Some("00".into());
        assert!(rt.block_on(run_jobs(&ui, &client, vec![bad], false, "x")).is_err());
        let _ = std::fs::remove_dir_all(out);
    }
}
