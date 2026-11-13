//! Microsoft-Login (OAuth2 + PKCE) -> Xbox Live -> XSTS -> Minecraft.
use std::collections::HashMap;
use std::io::{Read, Write};
use std::net::TcpListener;
use std::time::{Duration, Instant};

use anyhow::{bail, Context, Result};
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use reqwest::Client;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use url::Url;

use crate::config::Account;

const AUTHORIZE: &str = "https://login.live.com/oauth20_authorize.srf";
const TOKEN: &str = "https://login.live.com/oauth20_token.srf";
const SCOPE: &str = "XboxLive.signin offline_access";

fn random_b64(n: usize) -> String {
    let bytes: Vec<u8> = (0..n).map(|_| rand::random::<u8>()).collect();
    URL_SAFE_NO_PAD.encode(bytes)
}

pub async fn login(client: &Client, client_id: &str, redirect: &str) -> Result<Account> {
    if client_id.trim().is_empty() {
        bail!("Keine Azure Client-ID eingetragen (Einstellungen).");
    }
    let red = Url::parse(redirect).context("Ungültige Redirect-URI")?;
    let host = red.host_str().unwrap_or("").to_string();
    if host != "localhost" && host != "127.0.0.1" {
        bail!("Die Redirect-URI muss auf http://localhost:<port>/… zeigen.");
    }
    let port = red.port_or_known_default().unwrap_or(80);
    let verifier = random_b64(48);
    let challenge = URL_SAFE_NO_PAD.encode(Sha256::digest(verifier.as_bytes()));
    let state = random_b64(16);
    let mut url = Url::parse(AUTHORIZE)?;
    url.query_pairs_mut()
        .append_pair("client_id", client_id)
        .append_pair("response_type", "code")
        .append_pair("redirect_uri", redirect)
        .append_pair("scope", SCOPE)
        .append_pair("state", &state)
        .append_pair("code_challenge", &challenge)
        .append_pair("code_challenge_method", "S256")
        .append_pair("prompt", "select_account");

    let listener = TcpListener::bind(("127.0.0.1", port))
        .with_context(|| format!("Port {port} ist belegt"))?;
    open::that(url.as_str()).context("Browser konnte nicht geöffnet werden")?;
    let code = tokio::task::spawn_blocking(move || wait_for_code(listener, &state)).await??;

    let tok: Value = client
        .post(TOKEN)
        .form(&[
            ("client_id", client_id),
            ("code", code.as_str()),
            ("grant_type", "authorization_code"),
            ("redirect_uri", redirect),
            ("code_verifier", verifier.as_str()),
            ("scope", SCOPE),
        ])
        .send()
        .await?
        .json()
        .await?;
    finish(client, tok).await
}

pub async fn refresh(client: &Client, client_id: &str, refresh_token: &str) -> Result<Account> {
    let tok: Value = client
        .post(TOKEN)
        .form(&[
            ("client_id", client_id),
            ("refresh_token", refresh_token),
            ("grant_type", "refresh_token"),
            ("scope", SCOPE),
        ])
        .send()
        .await?
        .json()
        .await?;
    finish(client, tok).await
}

async fn finish(client: &Client, tok: Value) -> Result<Account> {
    if let Some(e) = tok["error"].as_str() {
        bail!("Microsoft: {}", tok["error_description"].as_str().unwrap_or(e));
    }
    let ms = tok["access_token"].as_str().context("Kein Microsoft-Token")?;
    let refresh_token = tok["refresh_token"].as_str().unwrap_or("").to_string();

    let xbl: Value = client
        .post("https://user.auth.xboxlive.com/user/authenticate")
        .json(&json!({
            "Properties": {"AuthMethod": "RPS", "SiteName": "user.auth.xboxlive.com", "RpsTicket": format!("d={ms}")},
            "RelyingParty": "http://auth.xboxlive.com", "TokenType": "JWT"
        }))
        .send().await?.error_for_status().context("Xbox-Live-Anmeldung fehlgeschlagen")?
        .json().await?;
    let xbl_token = xbl["Token"].as_str().context("Kein Xbox-Token")?;
    let uhs = xbl["DisplayClaims"]["xui"][0]["uhs"].as_str().context("Kein Xbox-Userhash")?;

    let resp = client
        .post("https://xsts.auth.xboxlive.com/xsts/authorize")
        .json(&json!({
            "Properties": {"SandboxId": "RETAIL", "UserTokens": [xbl_token]},
            "RelyingParty": "rp://api.minecraftservices.com/", "TokenType": "JWT"
        }))
        .send().await?;
    let status = resp.status();
    let xsts: Value = resp.json().await.unwrap_or(Value::Null);
    if !status.is_success() {
        let msg = match xsts["XErr"].as_u64() {
            Some(2148916233) => "Dieses Microsoft-Konto hat kein Xbox-Profil. Bitte einmal auf minecraft.net anmelden.",
            Some(2148916235) => "Xbox Live ist in deinem Land nicht verfügbar.",
            Some(2148916238) => "Kinderkonto: Ein Erwachsener muss das Konto zu einer Microsoft-Familie hinzufügen.",
            _ => "Xbox-Anmeldung (XSTS) fehlgeschlagen.",
        };
        bail!("{msg}");
    }
    let xsts_token = xsts["Token"].as_str().context("Kein XSTS-Token")?;

    let resp = client
        .post("https://api.minecraftservices.com/authentication/login_with_xbox")
        .json(&json!({"identityToken": format!("XBL3.0 x={uhs};{xsts_token}")}))
        .send().await?;
    if resp.status().as_u16() == 403 {
        bail!("Deine Azure-App ist noch nicht für Minecraft freigeschaltet (siehe README: aka.ms/mce-reviewappid).");
    }
    let mc: Value = resp.error_for_status().context("Minecraft-Anmeldung fehlgeschlagen")?.json().await?;
    let access = mc["access_token"].as_str().context("Kein Minecraft-Token")?.to_string();

    let resp = client
        .get("https://api.minecraftservices.com/minecraft/profile")
        .bearer_auth(&access)
        .send().await?;
    if resp.status().as_u16() == 404 {
        bail!("Dieses Microsoft-Konto besitzt Minecraft: Java Edition nicht.");
    }
    let prof: Value = resp.error_for_status()?.json().await?;
    Ok(Account {
        name: prof["name"].as_str().context("Kein Spielername")?.into(),
        id: prof["id"].as_str().context("Keine UUID")?.into(),
        access_token: access,
        refresh_token,
        source: "microsoft".into(),
    })
}

fn respond(s: &mut std::net::TcpStream, msg: &str) {
    let body = format!("<!doctype html><meta charset=utf-8><body style=\"font-family:sans-serif;background:#1e1f22;color:#eee;text-align:center;padding-top:80px\"><h2>{msg}</h2></body>");
    let _ = write!(s, "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len());
}

fn wait_for_code(l: TcpListener, state: &str) -> Result<String> {
    l.set_nonblocking(true)?;
    let start = Instant::now();
    loop {
        if start.elapsed() > Duration::from_secs(300) {
            bail!("Anmeldung: Zeit abgelaufen.");
        }
        match l.accept() {
            Ok((mut s, _)) => {
                s.set_nonblocking(false)?;
                s.set_read_timeout(Some(Duration::from_secs(5)))?;
                let mut buf = [0u8; 8192];
                let n = s.read(&mut buf).unwrap_or(0);
                let req = String::from_utf8_lossy(&buf[..n]);
                let path = req.split_whitespace().nth(1).unwrap_or("/");
                let Ok(u) = Url::parse(&format!("http://localhost{path}")) else { continue };
                let q: HashMap<String, String> = u.query_pairs().into_owned().collect();
                if let Some(code) = q.get("code") {
                    if q.get("state").map(String::as_str) != Some(state) {
                        respond(&mut s, "Ungültige Anfrage.");
                        bail!("Anmeldung: Sicherheitsprüfung (state) fehlgeschlagen.");
                    }
                    respond(&mut s, "Anmeldung erfolgreich – du kannst dieses Fenster schließen.");
                    return Ok(code.clone());
                }
                if let Some(e) = q.get("error") {
                    respond(&mut s, "Anmeldung abgebrochen.");
                    bail!("Anmeldung abgebrochen: {}", q.get("error_description").unwrap_or(e));
                }
                respond(&mut s, "…");
            }
            Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => std::thread::sleep(Duration::from_millis(100)),
            Err(e) => return Err(e.into()),
        }
    }
}

/// Prueft einen Minecraft-Token. Ok(false) = abgelaufen; Netzwerkfehler gelten als „unbekannt“ (Ok(true)).
pub async fn token_valid(client: &Client, token: &str) -> Result<bool> {
    match client.get("https://api.minecraftservices.com/minecraft/profile").bearer_auth(token).send().await {
        Ok(r) if r.status().as_u16() == 401 => Ok(false),
        Ok(_) => Ok(true),
        Err(_) => Ok(true),
    }
}
