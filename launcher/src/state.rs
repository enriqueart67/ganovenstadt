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
}

#[derive(Clone)]
pub struct Ui {
    s: Arc<Mutex<Shared>>,
    ctx: eframe::egui::Context,
}

impl Ui {
    pub fn new(ctx: eframe::egui::Context) -> Self {
        let shared = Shared { status: "Bereit.".into(), ..Default::default() };
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
        {
            let mut s = self.lock();
            s.log.push_back(t.into());
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
        self.log(format!("[Fehler] {e}"));
        let mut s = self.lock();
        s.status = "Fehler – siehe Log.".into();
        s.error = Some(e);
        drop(s);
        self.repaint();
    }
    pub fn set_busy(&self, b: bool) {
        self.lock().busy = b;
        self.repaint();
    }
}
