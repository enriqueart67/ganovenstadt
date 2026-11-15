//! Gemeinsamer Zustand zwischen GUI und Hintergrund-Tasks.
use std::collections::VecDeque;
use std::sync::{Arc, Mutex, MutexGuard};

use crate::config::Account;
use crate::install::VersionEntry;

#[derive(Default)]
pub struct Shared {
    pub status: String,
    pub done: usize,
    pub total: usize,
    pub log: VecDeque<String>,
    pub busy: bool,
    pub versions: Vec<VersionEntry>,
    pub account: Option<Account>,
    /// Gefundene Prism-Installation und dort angemeldetes Konto.
    pub prism: Option<crate::prism::Prism>,
    pub prism_account: Option<String>,
    pub error: Option<String>,
    pub quit: bool,
    /// Where the game is in its start: 0 nothing yet, 1 process started, 2 window open, 3 fully loaded,
    /// 8 failed, 9 closed. Read from the real log lines – drives the launch screen.
    pub game_stage: u8,
}

#[derive(Clone)]
pub struct Ui {
    s: Arc<Mutex<Shared>>,
    ctx: eframe::egui::Context,
}

impl Ui {
    pub fn new(ctx: eframe::egui::Context) -> Self {
        let shared = Shared { status: "Ready.".into(), ..Default::default() };
        Self { s: Arc::new(Mutex::new(shared)), ctx }
    }
    pub fn lock(&self) -> MutexGuard<'_, Shared> {
        self.s.lock().unwrap_or_else(|e| e.into_inner())
    }
    pub fn ctx(&self) -> &eframe::egui::Context {
        &self.ctx
    }
    pub fn repaint(&self) {
        self.ctx.request_repaint();
    }
    pub fn log(&self, t: impl Into<String>) {
        let t = t.into();
        // launcher lines (not the game's own log) also go to ~/.peregrine/launcher.log for support
        if t.starts_with('[') && !t.starts_with("[main") && !t.starts_with("[Render") {
            use std::io::Write;
            static FILE: std::sync::OnceLock<Option<std::sync::Mutex<std::fs::File>>> = std::sync::OnceLock::new();
            let f = FILE.get_or_init(|| {
                let p = crate::config::base_dir().join("launcher.log");
                let _ = std::fs::create_dir_all(crate::config::base_dir());
                std::fs::File::create(p).ok().map(std::sync::Mutex::new)
            });
            if let Some(f) = f {
                let mut f = f.lock().unwrap_or_else(|e| e.into_inner());
                let _ = writeln!(f, "{t}");
            }
        }
        {
            let mut s = self.lock();
            if t.starts_with("[Launcher] Starting Minecraft") || (t.starts_with("[Launcher] Starting ") && t.contains(" with ")) {
                s.game_stage = s.game_stage.max(1);
            } else if t.starts_with("[Timing] Minecraft window open") {
                s.game_stage = s.game_stage.max(2);
            } else if t.starts_with("[Timing] Minecraft fully loaded") {
                s.game_stage = 3;
            } else if t.starts_with("[Launcher] Game closed") || t.starts_with("[Launcher] The game did not start") {
                s.game_stage = 9;
            }
            s.log.push_back(t);
            while s.log.len() > 5000 {
                s.log.pop_front();
            }
        }
        self.repaint();
    }
    pub fn status(&self, t: impl Into<String>) {
        self.lock().status = t.into();
        self.repaint();
    }
    pub fn progress(&self, done: usize, total: usize) {
        let mut s = self.lock();
        s.done = done;
        s.total = total;
        drop(s);
        self.repaint();
    }
    pub fn inc(&self) {
        self.lock().done += 1;
        self.repaint();
    }
    pub fn error(&self, e: impl Into<String>) {
        let e = e.into();
        self.log(format!("[Error] {e}"));
        let mut s = self.lock();
        s.status = "Error – see the console.".into();
        s.game_stage = 8;
        s.error = Some(e);
        drop(s);
        self.repaint();
    }
    pub fn set_busy(&self, b: bool) {
        self.lock().busy = b;
        self.repaint();
    }
}
