//! Minecraft server list ping (status request) – player count, version, message of the day, latency.
use std::io::{Read, Write};
use std::net::{TcpStream, ToSocketAddrs};
use std::time::{Duration, Instant};

use anyhow::{bail, Context, Result};
use serde::{Deserialize, Serialize};
use serde_json::Value;

#[derive(Serialize, Deserialize, Clone, PartialEq, Debug, Default)]
pub struct ServerEntry {
    pub name: String,
    pub address: String,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Status {
    pub online: u64,
    pub max: u64,
    pub version: String,
    pub motd: String,
    pub ms: u128,
}

/// "host" or "host:port" → (host, port), default port 25565.
pub fn split_address(addr: &str) -> (String, u16) {
    let a = addr.trim();
    match a.rsplit_once(':') {
        Some((h, p)) if !h.is_empty() && !h.contains(':') => match p.parse() {
            Ok(port) => (h.to_string(), port),
            Err(_) => (a.to_string(), 25565),
        },
        _ => (a.to_string(), 25565),
    }
}

fn write_varint(out: &mut Vec<u8>, mut v: u32) {
    loop {
        let b = (v & 0x7F) as u8;
        v >>= 7;
        if v == 0 {
            out.push(b);
            return;
        }
        out.push(b | 0x80);
    }
}

fn read_varint(r: &mut impl Read) -> Result<u32> {
    let (mut v, mut shift) = (0u32, 0);
    loop {
        let mut b = [0u8; 1];
        r.read_exact(&mut b)?;
        v |= ((b[0] & 0x7F) as u32) << shift;
        if b[0] & 0x80 == 0 {
            return Ok(v);
        }
        shift += 7;
        if shift > 28 {
            bail!("bad varint");
        }
    }
}

fn packet(id: u32, body: &[u8]) -> Vec<u8> {
    let mut inner = Vec::new();
    write_varint(&mut inner, id);
    inner.extend_from_slice(body);
    let mut out = Vec::new();
    write_varint(&mut out, inner.len() as u32);
    out.extend(inner);
    out
}

/// Flattens a chat component (string, or object with text/extra) and drops § colour codes.
pub fn motd_text(v: &Value) -> String {
    fn walk(v: &Value, out: &mut String) {
        match v {
            Value::String(s) => out.push_str(s),
            Value::Array(a) => a.iter().for_each(|x| walk(x, out)),
            Value::Object(o) => {
                if let Some(t) = o.get("text").and_then(Value::as_str) {
                    out.push_str(t);
                }
                if let Some(e) = o.get("extra") {
                    walk(e, out);
                }
            }
            _ => {}
        }
    }
    let mut raw = String::new();
    walk(v, &mut raw);
    let mut out = String::new();
    let mut chars = raw.chars();
    while let Some(c) = chars.next() {
        if c == '§' {
            chars.next();
        } else {
            out.push(c);
        }
    }
    out.lines().map(str::trim).filter(|l| !l.is_empty()).collect::<Vec<_>>().join("  ·  ")
}

/// Blocking status ping (run it off the UI thread).
pub fn ping(address: &str) -> Result<Status> {
    let (host, port) = split_address(address);
    let sock = (host.as_str(), port).to_socket_addrs().context("unknown host")?.next().context("unknown host")?;
    let timeout = Duration::from_secs(4);
    let t0 = Instant::now();
    let mut s = TcpStream::connect_timeout(&sock, timeout)?;
    s.set_read_timeout(Some(timeout))?;
    s.set_write_timeout(Some(timeout))?;
    let _ = s.set_nodelay(true);

    let mut hs = Vec::new();
    write_varint(&mut hs, 767); // any recent protocol number is fine for a status request
    write_varint(&mut hs, host.len() as u32);
    hs.extend_from_slice(host.as_bytes());
    hs.extend_from_slice(&port.to_be_bytes());
    write_varint(&mut hs, 1); // next state: status
    s.write_all(&packet(0, &hs))?;
    s.write_all(&packet(0, &[]))?;

    let _len = read_varint(&mut s)?;
    let id = read_varint(&mut s)?;
    if id != 0 {
        bail!("unexpected answer");
    }
    let n = read_varint(&mut s)? as usize;
    if n > 4 * 1024 * 1024 {
        bail!("answer too large");
    }
    let mut buf = vec![0u8; n];
    s.read_exact(&mut buf)?;
    let ms = t0.elapsed().as_millis();
    let v: Value = serde_json::from_slice(&buf)?;
    Ok(Status {
        online: v["players"]["online"].as_u64().unwrap_or(0),
        max: v["players"]["max"].as_u64().unwrap_or(0),
        version: v["version"]["name"].as_str().unwrap_or("").to_string(),
        motd: motd_text(&v["description"]),
        ms,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn addresses_and_motd() {
        assert_eq!(split_address("play.example.net"), ("play.example.net".into(), 25565));
        assert_eq!(split_address(" mc.example.net:25570 "), ("mc.example.net".into(), 25570));
        let v = serde_json::json!({"text": "§aHello ", "extra": [{"text": "§lworld"}, "\n  second line"]});
        assert_eq!(motd_text(&v), "Hello world  ·  second line");
        let mut b = Vec::new();
        write_varint(&mut b, 300);
        assert_eq!(b, vec![0xAC, 0x02]);
        assert_eq!(read_varint(&mut &b[..]).unwrap(), 300);
    }

    /// Talks to a local fake server that answers one status request.
    #[test]
    fn ping_roundtrip() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        std::thread::spawn(move || {
            let (mut c, _) = listener.accept().unwrap();
            let n = read_varint(&mut c).unwrap() as usize;
            let mut skip = vec![0u8; n];
            c.read_exact(&mut skip).unwrap();
            let n = read_varint(&mut c).unwrap() as usize;
            let mut skip = vec![0u8; n];
            c.read_exact(&mut skip).unwrap();
            let json = br#"{"version":{"name":"26.2"},"players":{"online":12,"max":100},"description":"Test"}"#;
            let mut body = Vec::new();
            write_varint(&mut body, json.len() as u32);
            body.extend_from_slice(json);
            c.write_all(&packet(0, &body)).unwrap();
        });
        let st = ping(&format!("127.0.0.1:{port}")).unwrap();
        assert_eq!((st.online, st.max, st.version.as_str(), st.motd.as_str()), (12, 100, "26.2", "Test"));
    }
}
