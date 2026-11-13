//! Oberflaeche: rahmenloses Fenster, Seitenleiste, Startseite, Aussehen, Konsole, Einstellungen.
use std::time::{Duration, Instant};

use eframe::egui::{self, pos2, vec2, Align2, Color32, Id, Rect, RichText, Sense, Stroke, ViewportCommand};

use crate::config::{self, Config};
use crate::state::Ui as Handle;
use crate::theme::{self, alpha, font, icon, Icon, Look, Pal, BAD, GOOD, WARN};
use crate::{auth, client_mods, install, launch, mods, net, prism, profiles};

#[path = "gui_profiles.rs"]
mod profiles_ui;
#[path = "gui_client.rs"]
mod client_ui;
use profiles_ui::ProfilesUi;

#[derive(PartialEq, Clone, Copy)]
enum Page {
    Home,
    Profiles,
    Client,
    Look,
    Console,
    Settings,
}

enum Action {
    Play,
    Login,
    Logout,
    OpenPrism,
}

pub struct App {
    cfg: Config,
    saved: Config,
    h: Handle,
    rt: tokio::runtime::Runtime,
    client: reqwest::Client,
    page: Page,
    version_open: bool,
    just_opened: bool,
    repair: bool,
    started: Instant,
    applied: Option<Look>,
    bg_tex: Option<(String, egui::TextureHandle)>,
    bg_err: Option<(String, String)>,
    import_text: String,
    version_search: String,
    focus_search: u8,
    logo: egui::TextureHandle,
    prof: ProfilesUi,
    prism_check: Option<(Instant, String)>,
}

impl App {
    pub fn new(cc: &eframe::CreationContext) -> Self {
        let rt = tokio::runtime::Builder::new_multi_thread().enable_all().build().expect("Tokio");
        let h = Handle::new(cc.egui_ctx.clone());
        h.lock().account = config::load_account();
        let mut cfg = config::load_config();
        if cfg.look.design < theme::DESIGN {
            cfg.look = Look::default(); // einmalig auf das neue Peregrine-Design umstellen
        }
        theme::setup_fonts(&cc.egui_ctx, &cfg.look.font);
        let app = Self {
            saved: cfg.clone(),
            cfg,
            h,
            rt,
            client: net::client(),
            page: Page::Home,
            version_open: false,
            just_opened: false,
            repair: false,
            started: Instant::now(),
            applied: None,
            bg_tex: None,
            bg_err: None,
            import_text: String::new(),
            version_search: String::new(),
            focus_search: 0,
            logo: theme::load_logo(&cc.egui_ctx),
            prof: ProfilesUi::new(),
            prism_check: None,
        };
        app.load_versions();
        app.refresh_login();
        app
    }

    // -------------------------------------------------------------- Hintergrund-Aufgaben
    fn load_versions(&self) {
        let (h, client, gd) = (self.h.clone(), self.client.clone(), self.cfg.game_dir.clone());
        self.rt.spawn(async move {
            let list = match install::fetch_manifest(&client).await {
                Ok(v) => v,
                Err(e) => {
                    h.log(format!("[Launcher] Offline? Zeige installierte Versionen ({e:#})"));
                    install::installed(&gd)
                }
            };
            h.lock().versions = list;
            h.repaint();
        });
    }

    fn refresh_login(&self) {
        let Some(acc) = self.h.lock().account.clone() else { return };
        if self.cfg.client_id.is_empty() {
            return;
        }
        let (h, client, cid) = (self.h.clone(), self.client.clone(), self.cfg.client_id.clone());
        self.rt.spawn(async move {
            match auth::refresh(&client, &cid, &acc.refresh_token).await {
                Ok(a) => {
                    config::save_account(Some(&a));
                    h.log(format!("[Launcher] Sitzung erneuert für {}", a.name));
                    h.lock().account = Some(a);
                }
                Err(e) => h.log(format!("[Launcher] Sitzung konnte nicht erneuert werden ({e:#}) – ggf. neu anmelden.")),
            }
            h.repaint();
        });
    }

    /// Prism suchen und das dort angemeldete Konto lesen (alle paar Sekunden, nur kleine Dateien).
    fn refresh_prism(&mut self) {
        if let Some((t, path)) = &self.prism_check {
            if t.elapsed() < Duration::from_secs(3) && *path == self.cfg.prism_path {
                return;
            }
        }
        self.prism_check = Some((Instant::now(), self.cfg.prism_path.clone()));
        let found = prism::find(&self.cfg.prism_path);
        let acc = found.as_ref().and_then(prism::account_name);
        let mut s = self.h.lock();
        s.prism = found;
        s.prism_account = acc;
    }

    /// Prism nur zum Anmelden öffnen – fehlt Prism, lädt Peregrine es vorher selbst herunter.
    fn open_prism(&mut self) {
        self.prism_check = None;
        self.refresh_prism();
        if self.h.lock().busy {
            return;
        }
        let found = self.h.lock().prism.clone();
        if found.is_none() && !prism::can_install() {
            self.h.error(format!(
                "Die Anmeldung braucht auf diesem System eine Zusatzkomponente. Bitte von {} installieren (der Browser wird geöffnet).",
                prism::DOWNLOAD_URL
            ));
            let _ = open::that(prism::DOWNLOAD_URL);
            return;
        }
        let h = self.h.clone();
        h.set_busy(true);
        self.rt.spawn(async move {
            let res = async {
                let p = match found {
                    Some(p) => p,
                    None => {
                        let p = prism::install(&h).await?;
                        h.log("[Anmeldung] Bereit.");
                        p
                    }
                };
                h.status("Warte auf Anmeldung …");
                let hh = h.clone();
                prism::login(&p, h.clone(), move |name| {
                    hh.lock().prism_account = name.clone();
                    hh.status(if name.is_some() { "Bereit." } else { "Nicht angemeldet." });
                    hh.set_busy(false);
                })?;
                anyhow::Ok(())
            }
            .await;
            if let Err(e) = res {
                h.progress(0, 0);
                h.set_busy(false);
                h.error(format!("{e:#}"));
            }
        });
    }

    fn login(&mut self) {
        if self.cfg.client_id.is_empty() {
            if self.cfg.use_prism {
                self.open_prism();
            } else {
                self.h.error("Für den direkten Microsoft-Login brauchst du eine eigene Azure Client-ID (Einstellungen › Microsoft-Login). Oder schalte unter Einstellungen › Anmeldung „Einfache Anmeldung“ ein.");
                self.page = Page::Settings;
            }
            return;
        }
        let (h, client) = (self.h.clone(), self.client.clone());
        let (cid, redirect) = (self.cfg.client_id.clone(), self.cfg.redirect_uri.clone());
        h.log("[Launcher] Browser wird zur Microsoft-Anmeldung geöffnet …");
        self.rt.spawn(async move {
            match auth::login(&client, &cid, &redirect).await {
                Ok(a) => {
                    config::save_account(Some(&a));
                    h.log(format!("[Launcher] Angemeldet als {}", a.name));
                    h.lock().account = Some(a);
                    h.repaint();
                }
                Err(e) => h.error(format!("{e:#}")),
            }
        });
    }

    fn h_ctx(&self) -> egui::Context {
        self.h.ctx().clone()
    }

    /// Das auf der Startseite gewaehlte Profil (falls eins aktiv ist).
    fn active_profile(&self) -> Option<profiles::Profile> {
        let id = self.cfg.active_profile.as_ref()?;
        self.prof.list.iter().find(|p| &p.id == id).cloned()
    }

    fn play(&mut self) {
        match self.active_profile() {
            Some(p) => self.play_profile(p),
            None => {
                let (v, l) = (self.cfg.version.clone(), self.cfg.loader.clone());
                self.launch(v, l, None, None, None);
            }
        }
    }

    fn play_profile(&mut self, p: profiles::Profile) {
        if self.h.lock().busy {
            return;
        }
        let dir = profiles::dir(&self.cfg.game_dir, &p);
        self.h.log(format!("[Launcher] Profil „{}“ ({} {})", p.name, p.loader, p.version));
        self.launch(p.version, p.loader, p.loader_version, Some(dir), Some(p.id));
    }

    fn launch(&mut self, version: String, loader: String, loader_version: Option<String>, run_dir: Option<std::path::PathBuf>, profile_id: Option<String>) {
        config::save_config(&self.cfg);
        self.saved = self.cfg.clone();
        let (h, client, cfg, repair) = (self.h.clone(), self.client.clone(), self.cfg.clone(), self.repair);
        self.repair = false;
        let account = h.lock().account.clone();
        let shared = self.prof.shared.clone();
        if account.is_none() && cfg.use_prism {
            self.prism_check = None;
            self.refresh_prism();
            let (found, acc) = {
                let s = h.lock();
                (s.prism.clone(), s.prism_account.clone())
            };
            let (Some(pr), true) = (found, acc.is_some()) else {
                h.log("[Launcher] Noch nicht angemeldet – Anmeldefenster wird geöffnet. Danach noch einmal auf Spielen klicken.");
                self.open_prism();
                return;
            };
            let label = match &profile_id {
                Some(pid) => self.prof.list.iter().find(|p| &p.id == pid).map(|p| p.name.clone()).unwrap_or_default(),
                None => String::new(),
            };
            if loader != "Vanilla" && loader != "Fabric" && loader != "Quilt" {
                h.error(format!("{loader} wird nicht unterstützt."));
                return;
            }
            h.set_busy(true);
            self.rt.spawn(async move {
                let res = async {
                    let java = install::prepare_java(&h, &client, &cfg, &version).await?;
                    let lv = if loader == "Vanilla" {
                        None
                    } else {
                        h.status(format!("Suche {loader}-Version …"));
                        Some(install::loader_version(&client, &version, &loader, loader_version.as_deref()).await?.1)
                    };
                    let game = run_dir.clone().unwrap_or_else(|| std::path::PathBuf::from(&cfg.game_dir));
                    setup_client_mods(&h, &client, &game, &version, &loader, &cfg.client, profile_id.as_deref(), &shared).await;
                    let (key, name) = match &profile_id {
                        Some(pid) => (format!("profil-{pid}"), format!("Peregrine · {label}")),
                        None => (format!("{loader}-{version}"), format!("Peregrine · {loader} {version}")),
                    };
                    let id = prism::instance_id(&key);
                    let pack = prism::pack_json(&version, &loader, lv.as_deref())?;
                    prism::write_instance(&pr, &id, &name, &game, &pack, &cfg, java.as_deref())?;
                    h.log(format!("[Launcher] Starte {} (Konto: {})", name.trim_start_matches("Peregrine · "), acc.unwrap_or_default()));
                    h.log("[Launcher] Beim ersten Start einer Version werden die Spieldateien geladen – das dauert einmalig etwas.");
                    prism::launch(&pr, &id, &game, h.clone())?;
                    h.status(format!("{version} läuft"));
                    anyhow::Ok(())
                }
                .await;
                match res {
                    Ok(()) if cfg.close_on_launch => h.lock().quit = true,
                    Ok(()) => {}
                    Err(e) => h.error(format!("{e:#}")),
                }
                h.progress(0, 0);
                h.set_busy(false);
            });
            return;
        }
        h.set_busy(true);
        self.rt.spawn(async move {
            let res = async {
                let id = install::prepare(&h, &client, &cfg, &version, &loader, loader_version.as_deref(), repair).await?;
                let game = run_dir.clone().unwrap_or_else(|| std::path::PathBuf::from(&cfg.game_dir));
                setup_client_mods(&h, &client, &game, &version, &loader, &cfg.client, profile_id.as_deref(), &shared).await;
                let (java, args) = launch::build(&cfg, &id, account.as_ref(), run_dir.as_deref())?;
                h.log(format!("[Launcher] Starte {id} mit {}", java.display()));
                h.status(format!("{id} läuft"));
                let dir = run_dir.clone().unwrap_or_else(|| std::path::PathBuf::from(&cfg.game_dir));
                launch::spawn(&java, &args, &dir, h.clone(), cfg.close_on_launch)?;
                anyhow::Ok(())
            }
            .await;
            match res {
                Ok(()) if cfg.close_on_launch => h.lock().quit = true,
                Ok(()) => {}
                Err(e) => h.error(format!("{e:#}")),
            }
            h.progress(0, 0);
            h.set_busy(false);
        });
    }

    fn bg_texture(&mut self, ctx: &egui::Context) -> Option<egui::TextureHandle> {
        let path = self.cfg.look.image_path.trim().to_string();
        if path.is_empty() {
            return None;
        }
        if let Some((p, t)) = &self.bg_tex {
            if *p == path {
                return Some(t.clone());
            }
        }
        if self.bg_err.as_ref().is_some_and(|(p, _)| *p == path) {
            return None;
        }
        match load_image(&path) {
            Ok(img) => {
                let t = ctx.load_texture("hintergrund", img, egui::TextureOptions::LINEAR);
                self.bg_tex = Some((path, t.clone()));
                self.bg_err = None;
                Some(t)
            }
            Err(e) => {
                self.bg_err = Some((path, format!("{e:#}")));
                None
            }
        }
    }

    // -------------------------------------------------------------- Rahmen
    /// Titelleiste mit Logo, Text-Navigation, Konto und Fenster-Knoepfen.
    fn title_bar(&mut self, ui: &mut egui::Ui, r: Rect, pal: &Pal, account: &Option<config::Account>, action: &mut Option<Action>) {
        let prism_acc = if account.is_none() && self.cfg.use_prism { self.h.lock().prism_account.clone() } else { None };
        let ctx = ui.ctx().clone();
        let bar = ui.interact(r, Id::new("titlebar"), Sense::click_and_drag());
        if bar.drag_started() {
            ctx.send_viewport_cmd(ViewportCommand::StartDrag);
        }
        if bar.double_clicked() {
            let max = ctx.input(|i| i.viewport().maximized.unwrap_or(false));
            ctx.send_viewport_cmd(ViewportCommand::Maximized(!max));
        }
        let p = ui.painter();
        p.line_segment([r.left_bottom(), r.right_bottom()], Stroke::new(1.0_f32, pal.border));
        let mut x = r.left() + 20.0;
        if self.cfg.look.show_logo || true {
            let logo = Rect::from_center_size(pos2(x + 11.0, r.center().y), vec2(22.0, 22.0));
            theme::logo(p, pal, logo, &self.logo);
            x += 32.0;
        }
        let end = p.text(pos2(x, r.center().y), Align2::LEFT_CENTER, self.cfg.look.title.to_uppercase(), font(12.5, "monom"), pal.text);
        if !self.cfg.look.subtitle.is_empty() {
            p.text(pos2(end.right() + 7.0, r.center().y), Align2::LEFT_CENTER, self.cfg.look.subtitle.to_uppercase(), font(12.5, "mono"), pal.muted);
        }

        // Navigation
        let mut nx = r.left() + 250.0;
        for (page, label) in [
            (Page::Home, "START"),
            (Page::Profiles, "PROFILE"),
            (Page::Client, "CLIENT-MODS"),
            (Page::Look, "AUSSEHEN"),
            (Page::Console, "KONSOLE"),
            (Page::Settings, "EINSTELLUNGEN"),
        ] {
            let g = ui.painter().layout_no_wrap(label.into(), font(11.5, "monom"), pal.text);
            let tr = Rect::from_min_size(pos2(nx, r.top()), vec2(g.size().x, r.height()));
            let resp = ui.interact(tr.expand2(vec2(8.0, 0.0)), Id::new(("nav", label)), Sense::click());
            let active = self.page == page;
            let col = if active || resp.hovered() { pal.text } else { pal.muted };
            ui.painter().text(pos2(nx, r.center().y), Align2::LEFT_CENTER, label, font(11.5, "monom"), col);
            if active {
                ui.painter().rect_filled(Rect::from_min_max(pos2(tr.left(), r.bottom() - 2.0), pos2(tr.right(), r.bottom())), 0.0, pal.accent);
            }
            if resp.clicked() {
                if self.page == Page::Settings && page != Page::Settings && self.cfg.game_dir != self.saved.game_dir {
                    self.load_versions();
                }
                self.page = page;
            }
            nx += g.size().x + 26.0;
        }

        // Fenster-Knoepfe
        let mut x = r.right();
        for (kind, cmd) in [(Icon::Close, 0), (Icon::Maximize, 1), (Icon::Minimize, 2)] {
            let br = Rect::from_min_max(pos2(x - 44.0, r.top()), pos2(x, r.bottom() - 1.0));
            x -= 44.0;
            let resp = ui.interact(br, Id::new(("winbtn", cmd)), Sense::click());
            let p = ui.painter();
            let hot = resp.hovered();
            if hot {
                p.rect_filled(br, 0.0, if cmd == 0 { BAD } else { pal.surface2 });
            }
            icon(p, br.center(), 15.0, if hot && cmd == 0 { Color32::WHITE } else if hot { pal.text } else { pal.muted }, kind);
            if resp.clicked() {
                match cmd {
                    0 => ctx.send_viewport_cmd(ViewportCommand::Close),
                    1 => {
                        let max = ctx.input(|i| i.viewport().maximized.unwrap_or(false));
                        ctx.send_viewport_cmd(ViewportCommand::Maximized(!max));
                    }
                    _ => ctx.send_viewport_cmd(ViewportCommand::Minimized(true)),
                }
            }
        }

        // Konto
        let label = match (account, &prism_acc) {
            (Some(a), _) => a.name.to_uppercase(),
            (None, Some(n)) => n.to_uppercase(),
            (None, None) => "ANMELDEN".to_string(),
        };
        let g = ui.painter().layout_no_wrap(label.clone(), font(11.5, "monom"), pal.text);
        let ar = Rect::from_min_size(pos2(x - 16.0 - g.size().x - 18.0, r.center().y - 12.0), vec2(g.size().x + 18.0, 24.0));
        let resp = ui.interact(ar, Id::new("acct"), Sense::click()).on_hover_text(if account.is_some() {
            "Klicken zum Abmelden"
        } else if prism_acc.is_some() {
            "Angemeldet – klicken, um das Konto zu wechseln"
        } else if self.cfg.use_prism && self.cfg.client_id.is_empty() {
            "Mit Microsoft anmelden (nur einmal nötig)"
        } else {
            "Mit Microsoft anmelden"
        });
        let p = ui.painter();
        p.rect_stroke(ar, pal.r, Stroke::new(1.0_f32, if resp.hovered() { pal.text } else { pal.border }));
        p.circle_filled(pos2(ar.left() + 9.0, ar.center().y), 2.5, if account.is_some() || prism_acc.is_some() { GOOD } else { pal.accent });
        p.text(pos2(ar.left() + 15.0, ar.center().y), Align2::LEFT_CENTER, label, font(11.5, "monom"), pal.text);
        if resp.clicked() {
            *action = Some(if account.is_some() {
                Action::Logout
            } else if prism_acc.is_some() {
                Action::OpenPrism
            } else {
                Action::Login
            });
        }
    }

    /// Optionales Bild/Landschaft im Rahmen (wenn unter Aussehen ein Hintergrund gewaehlt ist).
    fn visual(&mut self, ui: &mut egui::Ui, rect: Rect, t: f32, pal: &Pal) {
        let look = self.cfg.look.clone();
        let ctx = ui.ctx().clone();
        let tex = if look.background == "Bild" { self.bg_texture(&ctx) } else { None };
        let p = ui.painter().with_clip_rect(rect);
        match look.background.as_str() {
            "Bild" => match &tex {
                Some(tex) => {
                    let [w, h] = tex.size();
                    let (ia, ra) = (w as f32 / h as f32, rect.width() / rect.height());
                    let uv = if ia > ra {
                        let s = ra / ia;
                        Rect::from_min_max(pos2((1.0 - s) / 2.0, 0.0), pos2((1.0 + s) / 2.0, 1.0))
                    } else {
                        let s = ia / ra;
                        Rect::from_min_max(pos2(0.0, (1.0 - s) / 2.0), pos2(1.0, (1.0 + s) / 2.0))
                    };
                    p.image(tex.id(), rect, uv, Color32::WHITE);
                }
                None => {
                    p.rect_filled(rect, 0.0, pal.surface2);
                    let msg = match &self.bg_err {
                        Some((_, e)) => format!("Bild nicht ladbar: {e}"),
                        None => "Bild ins Fenster ziehen".into(),
                    };
                    p.text(rect.center(), Align2::CENTER_CENTER, msg, font(11.0, "mono"), pal.muted);
                }
            },
            "Verlauf" => theme::vertical_gradient(&p, rect, &[(0.0, theme::lerp_color(pal.bg, pal.accent, 0.55)), (1.0, theme::lerp_color(pal.bg, pal.accent, 0.08))]),
            "Einfarbig" => {
                p.rect_filled(rect, 0.0, pal.accent);
            }
            _ => theme::paint_landscape(&p, rect, t, &look.scene),
        }
        if matches!(look.background.as_str(), "Landschaft" | "Bild") && look.dim > 0.0 {
            p.rect_filled(rect, 0.0, alpha(Color32::BLACK, (look.dim.clamp(0.0, 0.9) * 255.0) as u8));
        }
        ui.painter().rect_stroke(rect, 0.0, Stroke::new(1.0_f32, pal.border));
    }

    // -------------------------------------------------------------- Startseite
    fn home(&mut self, ui: &mut egui::Ui, area: Rect, t: f32, pal: &Pal, account: &Option<config::Account>, action: &mut Option<Action>) {
        let look = self.cfg.look.clone();
        let active = self.active_profile();
        let (busy, status, done, total, latest, nprof) = {
            let s = self.h.lock();
            (s.busy, s.status.clone(), s.done, s.total, s.versions.iter().find(|v| v.kind == "release").map(|v| v.id.clone()), self.prof.list.len())
        };
        let play_h = 78.0;
        let play = Rect::from_min_max(pos2(area.left(), area.bottom() - play_h), area.max);
        let top = Rect::from_min_max(area.min, pos2(area.right(), play.top() - 44.0));
        let split = top.left() + top.width() * 0.6;
        let left = Rect::from_min_max(top.min, pos2(split - 30.0, top.bottom()));
        let right = Rect::from_min_max(pos2(split + 10.0, top.top()), top.max);
        ui.painter().line_segment([pos2(split - 10.0, top.top()), pos2(split - 10.0, top.bottom())], Stroke::new(1.0_f32, pal.border));

        // ---------- links: Typografie
        let p = ui.painter().with_clip_rect(left.expand(4.0));
        let mut y = left.top() + 4.0;
        if look.show_tagline && !look.tagline.is_empty() {
            p.text(pos2(left.left(), y), Align2::LEFT_TOP, look.tagline.to_uppercase(), font(11.0, "mono"), pal.muted);
        }
        y += 34.0;
        let (big, sub) = match &active {
            Some(ap) => {
                let n = ap.mods.len();
                let lv = ap.loader_version.as_deref().map(|v| format!(" {v}")).unwrap_or_default();
                (ap.name.clone(), format!("Profil  ·  {}{lv}  ·  Minecraft {}  ·  {n} Inhalt{}", ap.loader, ap.version, if n == 1 { "" } else { "e" }))
            }
            None => {
                let v = if self.cfg.version.is_empty() { "…".to_string() } else { self.cfg.version.clone() };
                let newest = latest.as_deref() == Some(self.cfg.version.as_str());
                (v, format!("Minecraft Java Edition  ·  {}{}", self.cfg.loader, if newest { "  ·  neueste Version" } else { "" }))
            }
        };
        // Riesige Zahl/Name – automatisch passend zur Breite
        let mut size = 150.0_f32;
        loop {
            let g = p.layout_no_wrap(big.clone(), font(size, "display"), pal.text);
            if g.size().x <= left.width() || size <= 44.0 {
                break;
            }
            size -= 6.0;
        }
        let tr = p.text(pos2(left.left() - size * 0.04, y), Align2::LEFT_TOP, &big, font(size, "display"), pal.text);
        // kleines Signal-Quadrat wie ein Instrumenten-Marker
        p.rect_filled(Rect::from_min_size(pos2(tr.right() + 10.0, tr.top() + size * 0.22), vec2(10.0, 10.0)), 0.0, pal.accent);
        y = tr.bottom() + 2.0;
        p.text(pos2(left.left(), y), Align2::LEFT_TOP, &sub, font(17.0, "regular"), pal.muted);
        y += 54.0;

        // Auswahl: Version / Profil
        let vc = Rect::from_min_size(pos2(left.left(), y), vec2(200.0, 52.0));
        let vresp = ui.interact(vc, Id::new("vercard"), Sense::click());
        let p = ui.painter();
        p.text(pos2(vc.left(), vc.top()), Align2::LEFT_TOP, if active.is_some() { "PROFIL" } else { "VERSION" }, font(10.5, "mono"), pal.muted);
        let shown = match &active {
            Some(ap) => ap.name.clone(),
            None => self.cfg.version.clone(),
        };
        let vg = p.layout_no_wrap(shown.clone(), font(20.0, "semibold"), pal.text);
        let w = (vg.size().x + 30.0).max(120.0).min(320.0);
        let vc = Rect::from_min_size(vc.min, vec2(w, 52.0));
        p.with_clip_rect(Rect::from_min_max(vc.min, pos2(vc.right() - 18.0, vc.bottom()))).text(pos2(vc.left(), vc.top() + 30.0), Align2::LEFT_CENTER, shown, font(20.0, "semibold"), pal.text);
        icon(p, pos2(vc.right() - 8.0, vc.top() + 31.0), 13.0, if vresp.hovered() || self.version_open { pal.text } else { pal.muted }, Icon::Chevron);
        p.line_segment([pos2(vc.left(), vc.bottom()), pos2(vc.right(), vc.bottom())], Stroke::new(if self.version_open || vresp.hovered() { 2.0_f32 } else { 1.0_f32 }, if self.version_open { pal.accent } else if vresp.hovered() { pal.text } else { pal.border }));
        if vresp.clicked() {
            self.version_open = !self.version_open;
            self.just_opened = true;
            self.focus_search = 3;
        }
        // Loader als Text-Auswahl
        let lx = vc.right() + 40.0;
        ui.painter().text(pos2(lx, vc.top()), Align2::LEFT_TOP, "MODLOADER", font(10.5, "mono"), pal.muted);
        if active.is_none() {
            let mut ox = lx;
            for l in install::LOADERS {
                let g = ui.painter().layout_no_wrap(l.to_string(), font(20.0, "semibold"), pal.text);
                let orr = Rect::from_min_size(pos2(ox, vc.top() + 16.0), vec2(g.size().x, 36.0));
                let resp = ui.interact(orr, Id::new(("ld", l)), Sense::click());
                let sel = self.cfg.loader == l;
                let col = if sel { pal.text } else if resp.hovered() { lerp(pal.muted, pal.text) } else { pal.muted };
                ui.painter().text(pos2(ox, vc.top() + 30.0), Align2::LEFT_CENTER, l, font(20.0, "semibold"), col);
                if sel {
                    ui.painter().rect_filled(Rect::from_min_max(pos2(ox, vc.bottom() - 1.0), pos2(ox + g.size().x, vc.bottom() + 1.0)), 0.0, pal.accent);
                }
                if resp.clicked() {
                    self.cfg.loader = l.to_string();
                }
                ox += g.size().x + 22.0;
            }
        } else {
            let resp = ui.interact(Rect::from_min_size(pos2(lx, vc.top() + 16.0), vec2(170.0, 36.0)), Id::new("leaveprof"), Sense::click());
            ui.painter().text(pos2(lx, vc.top() + 30.0), Align2::LEFT_CENTER, "aus Profil  ·  zurück", font(16.0, "regular"), if resp.hovered() { pal.text } else { pal.muted });
            if resp.clicked() {
                self.cfg.active_profile = None;
            }
        }

        // ---------- rechts: Daten
        let mut ry = right.top() + 4.0;
        if look.background != "Keine" {
            let vis = Rect::from_min_size(pos2(right.left(), ry), vec2(right.width(), (right.height() * 0.42).min(200.0)));
            self.visual(ui, vis, t, pal);
            ry = vis.bottom() + 18.0;
        }
        let p = ui.painter();
        p.text(pos2(right.left(), ry), Align2::LEFT_TOP, "ÜBERSICHT", font(11.0, "mono"), pal.muted);
        ry += 22.0;
        let java = if self.cfg.java_path.trim().is_empty() { "automatisch".to_string() } else { "eigener Pfad".into() };
        let prism_acc = if account.is_none() && self.cfg.use_prism { self.h.lock().prism_account.clone() } else { None };
        let konto = match (account, &prism_acc) {
            (Some(a), _) => (a.name.clone(), pal.text),
            (None, Some(n)) => (n.clone(), pal.text),
            (None, None) => ("Gast · Demo".into(), pal.accent),
        };
        let mut rows: Vec<(&str, String, Color32)> = vec![
            ("KONTO", konto.0, konto.1),
            ("ARBEITSSPEICHER", format!("{:.1} GB", self.cfg.ram_mb as f32 / 1024.0).replace('.', ","), pal.text),
            ("JAVA", java, pal.text),
            ("CLIENT-MODS", if self.cfg.client.enabled { "an  ·  Rechts-Shift".into() } else { "aus".into() }, pal.text),
            ("PROFILE", nprof.to_string(), pal.text),
        ];
        if look.show_badge {
            rows.push(("NEUESTE VERSION", latest.clone().unwrap_or_else(|| "–".into()), pal.text));
        }
        for (k, v, col) in rows {
            if ry + 30.0 > right.bottom() {
                break;
            }
            p.line_segment([pos2(right.left(), ry), pos2(right.right(), ry)], Stroke::new(1.0_f32, pal.border));
            p.text(pos2(right.left(), ry + 15.0), Align2::LEFT_CENTER, k, font(10.5, "mono"), pal.muted);
            p.text(pos2(right.right(), ry + 15.0), Align2::RIGHT_CENTER, v, font(14.5, "medium"), col);
            ry += 30.0;
        }
        p.line_segment([pos2(right.left(), ry), pos2(right.right(), ry)], Stroke::new(1.0_f32, pal.border));

        // Schnellstart: Profile direkt waehlen
        if !self.prof.list.is_empty() && ry + 70.0 < right.bottom() {
            ry += 26.0;
            ui.painter().text(pos2(right.left(), ry), Align2::LEFT_TOP, "SCHNELLSTART", font(11.0, "mono"), pal.muted);
            ry += 22.0;
            let list = self.prof.list.clone();
            for pr in list.iter() {
                if ry + 32.0 > right.bottom() {
                    break;
                }
                let rr = Rect::from_min_size(pos2(right.left(), ry), vec2(right.width(), 32.0));
                let resp = ui.interact(rr, Id::new(("qs", &pr.id)), Sense::click());
                let sel = self.cfg.active_profile.as_deref() == Some(pr.id.as_str());
                let p = ui.painter();
                if resp.hovered() {
                    p.rect_filled(rr, pal.r, pal.surface2);
                }
                if sel {
                    p.rect_filled(Rect::from_min_size(rr.min + vec2(0.0, 8.0), vec2(3.0, 16.0)), 0.0, pal.accent);
                }
                p.with_clip_rect(Rect::from_min_max(rr.min, pos2(rr.right() - 120.0, rr.bottom())))
                    .text(pos2(rr.left() + 12.0, rr.center().y), Align2::LEFT_CENTER, &pr.name, font(15.0, if sel { "semibold" } else { "regular" }), pal.text);
                p.text(pos2(rr.right() - 6.0, rr.center().y), Align2::RIGHT_CENTER, format!("{} {}", pr.loader, pr.version).to_uppercase(), font(10.5, "mono"), pal.muted);
                if resp.clicked() {
                    self.cfg.active_profile = if sel { None } else { Some(pr.id.clone()) };
                }
                ry += 32.0;
            }
        }

        // ---------- Statuszeile
        let sy = play.top() - 22.0;
        let p = ui.painter();
        p.text(pos2(area.left(), sy), Align2::LEFT_CENTER, "STATUS", font(10.5, "mono"), pal.muted);
        let (line, col) = if busy {
            (status.clone(), pal.text)
        } else if status.starts_with("Fehler") {
            (status.clone(), BAD)
        } else if status.ends_with("läuft") {
            (status.clone(), GOOD)
        } else {
            ("Bereit".to_string(), pal.text)
        };
        p.text(pos2(area.left() + 62.0, sy), Align2::LEFT_CENTER, line, font(13.0, "medium"), col);
        if account.is_none() && prism_acc.is_none() {
            let hint = if self.cfg.use_prism {
                "NOCH NICHT ANGEMELDET — OBEN RECHTS AUF ANMELDEN KLICKEN"
            } else {
                "DEMO-MODUS — FÜR DIE VOLLVERSION OBEN RECHTS ANMELDEN"
            };
            p.text(pos2(area.right(), sy), Align2::RIGHT_CENTER, hint, font(10.5, "mono"), pal.muted);
        }

        // ---------- Spielen-Leiste
        let enabled = !busy && (active.is_some() || !self.cfg.version.is_empty());
        let resp = ui.interact(play, Id::new("play"), if enabled { Sense::click() } else { Sense::hover() });
        let hov = ui.ctx().animate_bool_with_time(Id::new("playhover"), resp.hovered() && enabled, 0.15);
        let p = ui.painter();
        let ink = pal.text;
        let paper = pal.bg;
        if busy {
            p.rect_filled(play, pal.r, pal.surface2);
            let f = if total > 0 { done as f32 / total as f32 } else { 0.0 };
            if f > 0.0 {
                p.rect_filled(Rect::from_min_size(play.min, vec2(play.width() * f, play.height())), pal.r, pal.accent);
            }
            let txt = if total > 0 { format!("LÄDT  {:.0} %", f * 100.0) } else { "STARTET …".into() };
            p.text(pos2(play.left() + 28.0, play.center().y), Align2::LEFT_CENTER, txt, font(30.0, "display"), if f > 0.15 { Color32::WHITE } else { pal.text });
            p.text(pos2(play.right() - 28.0, play.center().y), Align2::RIGHT_CENTER, format!("{done} / {total}"), font(12.0, "mono"), if f > 0.9 { Color32::WHITE } else { pal.muted });
        } else {
            let fill = theme::lerp_color(ink, pal.accent, hov);
            p.rect_filled(play, pal.r, fill);
            let fg = if hov > 0.5 { Color32::WHITE } else { paper };
            p.text(pos2(play.left() + 28.0, play.center().y), Align2::LEFT_CENTER, "SPIELEN", font(34.0, "display"), fg);
            let what = match &active {
                Some(ap) => format!("{}  ·  {} {}", ap.name, ap.loader, ap.version).to_uppercase(),
                None => format!("{}  ·  {}", self.cfg.version, self.cfg.loader).to_uppercase(),
            };
            let ax = play.right() - 28.0 - hov * 6.0;
            p.text(pos2(ax, play.center().y), Align2::RIGHT_CENTER, "→", font(30.0, "regular"), fg);
            p.text(pos2(ax - 44.0, play.center().y), Align2::RIGHT_CENTER, what, font(12.0, "mono"), alpha(fg, 200));
        }
        if resp.clicked() {
            *action = Some(Action::Play);
        }

        if self.version_open {
            self.version_popup(ui, pal, vc, area);
        }
        self.just_opened = false;
    }

    fn version_popup(&mut self, ui: &mut egui::Ui, pal: &Pal, vc: Rect, hero: Rect) {
        let (above, below) = (vc.top() - hero.top() - 8.0, hero.bottom() - vc.bottom() - 8.0);
        let popup_h = 440.0_f32.min(above.max(below));
        let pos = if below >= above { pos2(vc.left(), vc.bottom() + 8.0) } else { pos2(vc.left(), vc.top() - popup_h - 8.0) };
        let area = egui::Area::new(Id::new("verpopup"))
            .order(egui::Order::Foreground)
            .fixed_pos(pos)
            .show(ui.ctx(), |ui| {
                egui::Frame::none()
                    .fill(pal.bg)
                    .stroke(Stroke::new(1.0_f32, pal.text))
                    .rounding(pal.r)
                    .shadow(egui::epaint::Shadow { offset: vec2(0.0, 10.0), blur: 30.0, spread: 0.0, color: alpha(Color32::BLACK, 60) })
                    .inner_margin(10.0)
                    .show(ui, |ui| {
                        ui.set_width(300.0);
                        ui.set_height(popup_h - 20.0);
                        ui.spacing_mut().item_spacing.y = 8.0;
                        let all = self.h.lock().versions.clone();
                        let query = self.version_search.trim().to_lowercase();
                        let shown: Vec<&install::VersionEntry> = all
                            .iter()
                            .filter(|v| {
                                if !query.is_empty() {
                                    return v.id.to_lowercase().contains(&query);
                                }
                                match v.kind.as_str() {
                                    "release" => self.cfg.show_releases,
                                    "snapshot" => self.cfg.show_snapshots,
                                    _ => self.cfg.show_old,
                                }
                            })
                            .collect();
                        ui.horizontal(|ui| {
                            ui.label(RichText::new("Versionen").font(font(14.0, "semibold")));
                            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                                ui.label(RichText::new(format!("{} von {}", shown.len(), all.len())).color(pal.muted).size(11.5));
                            });
                        });
                        let search = ui.add(
                            egui::TextEdit::singleline(&mut self.version_search)
                                .hint_text("Suchen … z. B. 26.1, 1.8.9, b1.7")
                                .desired_width(f32::INFINITY)
                                .margin(vec2(10.0, 6.0)),
                        );
                        if self.focus_search > 0 {
                            search.request_focus();
                            self.focus_search -= 1;
                        }
                        if search.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter)) {
                            if let Some(v) = shown.iter().find(|v| v.kind == "release").or(shown.first()) {
                                self.cfg.version = v.id.clone();
                                self.cfg.active_profile = None;
                                self.version_open = false;
                            }
                        }
                        ui.horizontal(|ui| {
                            ui.spacing_mut().item_spacing.x = 6.0;
                            chip(ui, pal, "Releases", &mut self.cfg.show_releases);
                            chip(ui, pal, "Snapshots", &mut self.cfg.show_snapshots);
                            chip(ui, pal, "Beta & Alpha", &mut self.cfg.show_old);
                        });
                        if !self.prof.list.is_empty() {
                            ui.horizontal_wrapped(|ui| {
                                ui.spacing_mut().item_spacing = vec2(6.0, 6.0);
                                ui.label(RichText::new("Profile").color(pal.muted).size(11.5));
                                for pr in self.prof.list.clone() {
                                    let mut on = self.cfg.active_profile.as_deref() == Some(pr.id.as_str());
                                    let before = on;
                                    chip(ui, pal, &pr.name, &mut on);
                                    if on != before {
                                        self.cfg.active_profile = if on { Some(pr.id.clone()) } else { None };
                                        self.version_open = false;
                                    }
                                }
                            });
                        }
                        let row_h = 30.0;
                        let mut picked = None;
                        egui::ScrollArea::vertical().auto_shrink([false; 2]).show_rows(ui, row_h, shown.len(), |ui, range| {
                            ui.spacing_mut().item_spacing.y = 0.0;
                            for v in &shown[range] {
                                let (rr, resp) = ui.allocate_exact_size(vec2(ui.available_width(), row_h), Sense::click());
                                let sel = self.cfg.active_profile.is_none() && v.id == self.cfg.version;
                                let p = ui.painter();
                                if resp.hovered() {
                                    p.rect_filled(rr, pal.r, pal.surface2);
                                }
                                if sel {
                                    p.rect_filled(Rect::from_min_size(rr.min + vec2(0.0, 7.0), vec2(3.0, rr.height() - 14.0)), 0.0, pal.accent);
                                }
                                p.text(pos2(rr.left() + 12.0, rr.center().y), Align2::LEFT_CENTER, &v.id, font(14.0, if sel { "semibold" } else { "regular" }), pal.text);
                                let tag = match v.kind.as_str() {
                                    "release" => None,
                                    "snapshot" => Some(("Snapshot", WARN)),
                                    "old_beta" => Some(("Beta", pal.muted)),
                                    "old_alpha" => Some(("Alpha", pal.muted)),
                                    _ => Some(("Sonstige", pal.muted)),
                                };
                                if let Some((t, c)) = tag {
                                    p.text(pos2(rr.right() - 10.0, rr.center().y), Align2::RIGHT_CENTER, t, font(10.5, "medium"), c);
                                }
                                if resp.clicked() {
                                    picked = Some(v.id.clone());
                                }
                            }
                        });
                        if shown.is_empty() {
                            ui.label(RichText::new(if all.is_empty() { "Versionsliste wird geladen …" } else { "Keine Version gefunden." }).color(pal.muted));
                        }
                        if let Some(id) = picked {
                            self.cfg.active_profile = None;
                            self.cfg.version = id;
                            self.version_open = false;
                        }
                    });
            });
        if area.response.clicked_elsewhere() && !self.just_opened {
            self.version_open = false;
        }
    }

    // -------------------------------------------------------------- Aussehen
    fn look_page(&mut self, ui: &mut egui::Ui, r: Rect, pal: &Pal) {
        let bg_err = self.bg_err.clone();
        ui.allocate_new_ui(egui::UiBuilder::new().max_rect(r.shrink2(vec2(24.0, 18.0))), |ui| {
            header(ui, pal, "Aussehen", "Alles hier wird sofort übernommen und gespeichert");
            egui::ScrollArea::vertical().auto_shrink([false; 2]).show(ui, |ui| {
                let l = &mut self.cfg.look;

                card(ui, pal, "Farbschema", |ui| {
                    ui.horizontal_wrapped(|ui| {
                        for name in theme::PRESETS {
                            let mut preview = l.clone();
                            preview.apply_preset(name);
                            if preset_tile(ui, pal, name, &preview, l.preset == name).clicked() {
                                l.apply_preset(name);
                            }
                        }
                    });
                    ui.add_space(4.0);
                    ui.label(RichText::new("Akzentfarbe").color(pal.muted).size(12.0));
                    ui.horizontal_wrapped(|ui| {
                        for (name, rgb) in theme::ACCENTS {
                            if swatch(ui, pal, rgb, l.accent == rgb).on_hover_text(name).clicked() {
                                l.accent = rgb;
                            }
                        }
                        ui.add_space(6.0);
                        egui::color_picker::color_edit_button_srgb(ui, &mut l.accent).on_hover_text("Eigene Farbe");
                    });
                    ui.horizontal(|ui| {
                        theme::toggle(ui, pal, &mut l.gradients);
                        ui.label("Farbverlauf auf Buttons");
                        if l.gradients {
                            ui.label(RichText::new("zweite Farbe").color(pal.muted).size(12.0));
                            egui::color_picker::color_edit_button_srgb(ui, &mut l.accent2);
                        }
                    });
                    egui::CollapsingHeader::new(RichText::new("Eigene Farben für alles").color(pal.text)).show(ui, |ui| {
                        egui::Grid::new("colors").num_columns(4).spacing([14.0, 8.0]).show(ui, |ui| {
                            let mut changed = false;
                            for (i, (label, field)) in [
                                ("Hintergrund", &mut l.bg),
                                ("Flächen", &mut l.surface),
                                ("Felder", &mut l.surface2),
                                ("Rahmen", &mut l.border),
                                ("Text", &mut l.text),
                                ("Text (blass)", &mut l.muted),
                            ]
                            .into_iter()
                            .enumerate()
                            {
                                changed |= egui::color_picker::color_edit_button_srgb(ui, field).changed();
                                ui.label(label);
                                if i % 2 == 1 {
                                    ui.end_row();
                                }
                            }
                            if changed {
                                l.preset = "Eigene".into();
                            }
                        });
                    });
                });

                card(ui, pal, "Hintergrund der Startseite", |ui| {
                    segmented(ui, pal, "bgmode", &theme::BACKGROUNDS, &mut l.background);
                    match l.background.as_str() {
                        "Landschaft" => {
                            ui.horizontal(|ui| {
                                ui.label(RichText::new("Tageszeit").color(pal.muted));
                                segmented(ui, pal, "scene", &theme::SCENES, &mut l.scene);
                            });
                        }
                        "Bild" => {
                            field(ui, pal, "Pfad zum Bild (PNG, JPG, WEBP, GIF, BMP)", &mut l.image_path, "oder einfach ein Bild ins Fenster ziehen");
                            if let Some((p, e)) = &bg_err {
                                if *p == l.image_path.trim() {
                                    ui.label(RichText::new(format!("Fehler: {e}")).color(BAD).size(12.0));
                                }
                            }
                        }
                        _ => {}
                    }
                    if matches!(l.background.as_str(), "Landschaft" | "Bild") {
                        ui.horizontal(|ui| {
                            ui.label(RichText::new("Abdunkeln").color(pal.muted));
                            ui.add(egui::Slider::new(&mut l.dim, 0.0..=0.9).show_value(false));
                            ui.label(format!("{:.0} %", l.dim * 100.0));
                        });
                    }
                    ui.horizontal(|ui| {
                        theme::toggle(ui, pal, &mut l.animations);
                        ui.label("Animationen (Wolken, Sterne, Berge)");
                    });
                });

                card(ui, pal, "Form & Größe", |ui| {
                    ui.horizontal(|ui| {
                        ui.label(RichText::new("Eckenrundung").color(pal.muted));
                        ui.add(egui::Slider::new(&mut l.rounding, 0.0..=20.0).step_by(1.0).show_value(false));
                        ui.label(format!("{:.0} px", l.rounding));
                    });
                    ui.horizontal(|ui| {
                        ui.label(RichText::new("Oberflächen-Größe").color(pal.muted));
                        ui.add(egui::Slider::new(&mut l.ui_scale, 0.8..=1.4).step_by(0.05).show_value(false));
                        ui.label(format!("{:.0} %", l.ui_scale * 100.0));
                    });
                    ui.horizontal(|ui| {
                        ui.label(RichText::new("Schriftart").color(pal.muted));
                        segmented(ui, pal, "font", &theme::FONTS, &mut l.font);
                    });
                });

                card(ui, pal, "Startseite", |ui| {
                    field(ui, pal, "Titel", &mut l.title, "PEREGRINE");
                    field(ui, pal, "Untertitel", &mut l.subtitle, "CLIENT");
                    field(ui, pal, "Slogan", &mut l.tagline, "");
                    ui.horizontal(|ui| {
                        theme::toggle(ui, pal, &mut l.show_badge);
                        ui.label("„Neueste Version“ anzeigen");
                        ui.add_space(16.0);
                        theme::toggle(ui, pal, &mut l.show_tagline);
                        ui.label("Slogan anzeigen");
                        ui.add_space(16.0);
                        theme::toggle(ui, pal, &mut l.show_logo);
                        ui.label("Logo anzeigen");
                    });
                });

                let import_text = &mut self.import_text;
                card(ui, pal, "Design teilen", |ui| {
                    ui.label(RichText::new("Exportiere dein Design als Code und schick es Freunden – oder füge einen Code ein.").color(pal.muted).size(12.5));
                    ui.horizontal(|ui| {
                        if ui.button("Design kopieren").clicked() {
                            ui.ctx().copy_text(serde_json::to_string(&*l).unwrap_or_default());
                        }
                        if ui.button("Alles zurücksetzen").clicked() {
                            *l = Look::default();
                        }
                    });
                    ui.horizontal(|ui| {
                        ui.add(egui::TextEdit::singleline(import_text).hint_text("Design-Code hier einfügen").desired_width(380.0).margin(vec2(10.0, 6.0)));
                        if ui.button("Übernehmen").clicked() {
                            match serde_json::from_str::<Look>(import_text.trim()) {
                                Ok(new) => {
                                    *l = new;
                                    import_text.clear();
                                }
                                Err(_) => {
                                    *import_text = "Ungültiger Code".into();
                                }
                            }
                        }
                    });
                });
            });
        });
    }

    // -------------------------------------------------------------- Konsole
    fn console(&mut self, ui: &mut egui::Ui, r: Rect, pal: &Pal) {
        ui.allocate_new_ui(egui::UiBuilder::new().max_rect(r.shrink2(vec2(24.0, 18.0))), |ui| {
            ui.horizontal(|ui| {
                header(ui, pal, "Konsole", "Ausgabe des Launchers und des Spiels");
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    if ui.button("Leeren").clicked() {
                        self.h.lock().log.clear();
                    }
                    if ui.button("Kopieren").clicked() {
                        let text = self.h.lock().log.iter().cloned().collect::<Vec<_>>().join("\n");
                        ui.ctx().copy_text(text);
                    }
                });
            });
            egui::Frame::none().fill(pal.surface).stroke(Stroke::new(1.0_f32, pal.border)).rounding(pal.r + 2.0).inner_margin(14.0).show(ui, |ui| {
                let s = self.h.lock();
                let row_h = ui.text_style_height(&egui::TextStyle::Monospace) + 3.0;
                egui::ScrollArea::vertical().stick_to_bottom(true).auto_shrink([false; 2]).show_rows(ui, row_h, s.log.len(), |ui, range| {
                    ui.spacing_mut().item_spacing.y = 3.0;
                    for line in s.log.range(range) {
                        let c = if line.starts_with("[Fehler]") || line.contains("ERROR") {
                            BAD
                        } else if line.starts_with("[Launcher]") {
                            pal.accent
                        } else if line.contains("WARN") {
                            WARN
                        } else {
                            pal.muted
                        };
                        ui.label(RichText::new(line).monospace().color(c));
                    }
                    if s.log.is_empty() {
                        ui.label(RichText::new("Noch keine Ausgabe.").color(pal.muted));
                    }
                });
            });
        });
    }

    // -------------------------------------------------------------- Einstellungen
    fn settings(&mut self, ui: &mut egui::Ui, r: Rect, pal: &Pal) {
        let mut open_prism = false;
        ui.allocate_new_ui(egui::UiBuilder::new().max_rect(r.shrink2(vec2(24.0, 18.0))), |ui| {
            header(ui, pal, "Einstellungen", "Änderungen werden automatisch gespeichert");
            egui::ScrollArea::vertical().auto_shrink([false; 2]).show(ui, |ui| {
                let c = &mut self.cfg;
                card(ui, pal, "Leistung", |ui| {
                    ui.label(RichText::new("Arbeitsspeicher für Minecraft. 4–8 GB reichen meistens, mit vielen Mods mehr.").color(pal.muted).size(12.5));
                    ui.horizontal(|ui| {
                        ui.add(egui::Slider::new(&mut c.ram_mb, 1024..=32768).step_by(512.0).show_value(false));
                        ui.label(RichText::new(format!("{:.1} GB", c.ram_mb as f32 / 1024.0)).font(font(16.0, "semibold")));
                    });
                    field(ui, pal, "Zusätzliche JVM-Argumente", &mut c.extra_jvm_args, "z. B. -XX:+UseZGC");
                });
                card(ui, pal, "Spiel", |ui| {
                    ui.horizontal(|ui| {
                        field(ui, pal, "Spielordner", &mut c.game_dir, "");
                        ui.vertical(|ui| {
                            ui.add_space(18.0);
                            if ui.button("Öffnen").clicked() {
                                let _ = std::fs::create_dir_all(&c.game_dir);
                                let _ = open::that(&c.game_dir);
                            }
                        });
                    });
                    ui.horizontal(|ui| {
                        ui.label(RichText::new("Fenstergröße").color(pal.muted));
                        ui.add(egui::DragValue::new(&mut c.width).range(320..=7680).suffix(" px"));
                        ui.label("×");
                        ui.add(egui::DragValue::new(&mut c.height).range(240..=4320).suffix(" px"));
                    });
                    ui.horizontal(|ui| {
                        theme::toggle(ui, pal, &mut c.close_on_launch);
                        ui.label("Launcher nach dem Spielstart schließen");
                    });
                });
                card(ui, pal, "Java", |ui| {
                    field(ui, pal, "Java-Pfad (leer = passende Version wird automatisch geladen)", &mut c.java_path, "automatisch");
                });
                let (prism_found, prism_acc) = {
                    let s = self.h.lock();
                    (s.prism.clone(), s.prism_account.clone())
                };
                card(ui, pal, "Anmeldung", |ui| {
                    ui.label(RichText::new("Du meldest dich nur ein einziges Mal mit deinem Microsoft-Konto an – danach startet Minecraft immer direkt mit deinem Konto.").color(pal.muted).size(12.5));
                    ui.horizontal(|ui| {
                        theme::toggle(ui, pal, &mut c.use_prism);
                        ui.label("Einfache Anmeldung");
                    });
                    let (txt, col) = match (&prism_found, &prism_acc) {
                        (None, _) if prism::can_install() => ("Noch nicht angemeldet".to_string(), WARN),
                        (None, _) => ("Zusatzkomponente fehlt".to_string(), BAD),
                        (Some(_), None) => ("Noch nicht angemeldet".to_string(), WARN),
                        (Some(_), Some(n)) => (format!("Angemeldet als {n}"), GOOD),
                    };
                    ui.label(RichText::new(txt).color(col).font(font(14.0, "medium")));
                    ui.horizontal(|ui| {
                        if prism_found.is_none() && !prism::can_install() {
                            if ui.button("Herunterladen").clicked() {
                                let _ = open::that(prism::DOWNLOAD_URL);
                            }
                        } else if ui.button(if prism_acc.is_some() { "Konto wechseln" } else { "Jetzt anmelden" }).clicked() {
                            open_prism = true;
                        }
                    });
                });
                card(ui, pal, "Microsoft-Login", |ui| {
                    field(ui, pal, "Azure Client-ID (Anleitung in README.md)", &mut c.client_id, "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx");
                    field(ui, pal, "Redirect-URI", &mut c.redirect_uri, "");
                });
                let repair = &mut self.repair;
                card(ui, pal, "Wartung", |ui| {
                    ui.horizontal(|ui| {
                        theme::toggle(ui, pal, repair);
                        ui.label("Beim nächsten Start alle Dateien prüfen und reparieren");
                    });
                });
            });
        });
        self.cfg.client_id = self.cfg.client_id.trim().to_string();
        if open_prism {
            self.open_prism();
        }
    }
}

// ------------------------------------------------------------------ Widgets
fn load_image(path: &str) -> anyhow::Result<egui::ColorImage> {
    let img = image::open(path)?;
    let img = if img.width() > 2560 || img.height() > 2560 { img.resize(2560, 2560, image::imageops::FilterType::Triangle) } else { img };
    let rgba = img.to_rgba8();
    Ok(egui::ColorImage::from_rgba_unmultiplied([rgba.width() as usize, rgba.height() as usize], rgba.as_raw()))
}

#[allow(dead_code)]
fn avatar(p: &egui::Painter, pal: &Pal, r: Rect, name: Option<&str>, hover: bool) {
    match name {
        Some(n) => {
            pal.fill(p, r, r.width() / 2.0);
            let ch = n.chars().next().unwrap_or('?').to_uppercase().to_string();
            p.text(r.center(), Align2::CENTER_CENTER, ch, font(r.width() * 0.45, "bold"), pal.on_accent);
        }
        None => {
            p.circle_filled(r.center(), r.width() / 2.0, if hover { pal.surface2 } else { pal.surface });
            p.circle_stroke(r.center(), r.width() / 2.0, Stroke::new(1.0_f32, pal.border));
            let (c, s) = (r.center(), r.width());
            p.circle_filled(pos2(c.x, c.y - s * 0.1), s * 0.14, pal.muted);
            p.add(egui::Shape::convex_polygon(
                vec![pos2(c.x - s * 0.24, c.y + s * 0.26), pos2(c.x - s * 0.14, c.y + s * 0.1), pos2(c.x + s * 0.14, c.y + s * 0.1), pos2(c.x + s * 0.24, c.y + s * 0.26)],
                pal.muted,
                Stroke::NONE,
            ));
        }
    }
}

fn lerp(a: Color32, b: Color32) -> Color32 {
    theme::lerp_color(a, b, 0.5)
}

/// Seitentitel: grosse Display-Schrift, darunter eine Mono-Zeile.
fn header(ui: &mut egui::Ui, pal: &Pal, title: &str, sub: &str) {
    ui.vertical(|ui| {
        ui.spacing_mut().item_spacing.y = 4.0;
        ui.label(RichText::new(title).font(font(38.0, "display")).color(pal.text));
        ui.label(RichText::new(sub.to_uppercase()).font(font(11.0, "mono")).color(pal.muted));
    });
    ui.add_space(14.0);
}

/// Abschnitt: Haarlinie oben, Mono-Ueberschrift, Inhalt – keine Kaesten.
fn card(ui: &mut egui::Ui, pal: &Pal, title: &str, body: impl FnOnce(&mut egui::Ui)) {
    let (r, _) = ui.allocate_exact_size(vec2(ui.available_width(), 1.0), Sense::hover());
    ui.painter().rect_filled(r, 0.0, pal.border);
    ui.add_space(10.0);
    ui.horizontal(|ui| {
        let (m, _) = ui.allocate_exact_size(vec2(6.0, 6.0), Sense::hover());
        ui.painter().rect_filled(m, 0.0, pal.accent);
        ui.label(RichText::new(title.to_uppercase()).font(font(11.5, "monom")).color(pal.text));
    });
    ui.add_space(2.0);
    ui.vertical(|ui| {
        ui.set_width(ui.available_width());
        body(ui);
    });
    ui.add_space(18.0);
}

fn field(ui: &mut egui::Ui, pal: &Pal, label: &str, value: &mut String, hint: &str) {
    ui.vertical(|ui| {
        ui.spacing_mut().item_spacing.y = 4.0;
        ui.label(RichText::new(label.to_uppercase()).font(font(10.5, "mono")).color(pal.muted));
        ui.add(egui::TextEdit::singleline(value).hint_text(hint).desired_width(440.0).margin(vec2(10.0, 7.0)));
    });
}

/// Text-Auswahl: Optionen nebeneinander, gewaehlte unterstrichen (Signalfarbe).
fn segmented_at(ui: &mut egui::Ui, pal: &Pal, rect: Rect, id: &str, options: &[&str], value: &mut String) {
    let mut x = rect.left();
    for (i, o) in options.iter().enumerate() {
        let g = ui.painter().layout_no_wrap(o.to_string(), font(14.0, "medium"), pal.text);
        let r = Rect::from_min_size(pos2(x, rect.top()), vec2(g.size().x, rect.height()));
        let resp = ui.interact(r.expand2(vec2(6.0, 0.0)), Id::new((id, i)), Sense::click());
        let sel = value == o;
        let col = if sel { pal.text } else if resp.hovered() { lerp(pal.muted, pal.text) } else { pal.muted };
        ui.painter().text(pos2(x, r.center().y), Align2::LEFT_CENTER, *o, font(14.0, "medium"), col);
        if sel {
            ui.painter().rect_filled(Rect::from_min_max(pos2(x, r.bottom() - 2.0), pos2(r.right(), r.bottom())), 0.0, pal.accent);
        }
        if resp.clicked() {
            *value = o.to_string();
        }
        x += g.size().x + 20.0;
    }
}

fn segmented(ui: &mut egui::Ui, pal: &Pal, id: &str, options: &[&str], value: &mut String) {
    let w: f32 = options.iter().map(|o| ui.fonts(|f| f.layout_no_wrap(o.to_string(), font(14.0, "medium"), Color32::WHITE).size().x) + 20.0).sum();
    let (rect, _) = ui.allocate_exact_size(vec2(w, 30.0), Sense::hover());
    segmented_at(ui, pal, rect, id, options, value);
}

fn chip(ui: &mut egui::Ui, pal: &Pal, label: &str, on: &mut bool) {
    let g = ui.fonts(|f| f.layout_no_wrap(label.to_uppercase(), font(10.5, "monom"), Color32::WHITE));
    let (r, resp) = ui.allocate_exact_size(vec2(g.size().x + 18.0, 22.0), Sense::click());
    if resp.clicked() {
        *on = !*on;
    }
    let p = ui.painter();
    if *on {
        p.rect_filled(r, pal.r, pal.text);
    } else {
        p.rect_stroke(r, pal.r, Stroke::new(1.0_f32, if resp.hovered() { pal.text } else { pal.border }));
    }
    p.text(r.center(), Align2::CENTER_CENTER, label.to_uppercase(), font(10.5, "monom"), if *on { pal.bg } else { pal.muted });
}

fn swatch(ui: &mut egui::Ui, pal: &Pal, rgb: theme::Rgb, selected: bool) -> egui::Response {
    let (r, resp) = ui.allocate_exact_size(vec2(28.0, 28.0), Sense::click());
    let p = ui.painter();
    if selected {
        p.circle_stroke(r.center(), 13.0, Stroke::new(2.0_f32, pal.text));
    }
    p.circle_filled(r.center(), if selected { 9.5 } else if resp.hovered() { 11.5 } else { 11.0 }, theme::c(rgb));
    resp
}

fn preset_tile(ui: &mut egui::Ui, pal: &Pal, name: &str, l: &Look, selected: bool) -> egui::Response {
    let (r, resp) = ui.allocate_exact_size(vec2(124.0, 82.0), Sense::click());
    let p = ui.painter();
    let prev = Rect::from_min_size(r.min, vec2(r.width(), 56.0));
    p.rect_filled(prev, pal.r, theme::c(l.bg));
    p.rect_filled(Rect::from_min_size(prev.min + vec2(8.0, 8.0), vec2(18.0, 40.0)), (pal.r - 3.0).max(0.0), theme::c(l.surface));
    p.rect_filled(Rect::from_min_size(prev.min + vec2(32.0, 8.0), vec2(84.0, 18.0)), (pal.r - 3.0).max(0.0), theme::c(l.surface));
    p.rect_filled(Rect::from_min_size(prev.min + vec2(32.0, 30.0), vec2(48.0, 18.0)), (pal.r - 3.0).max(0.0), theme::c(l.surface2));
    p.rect_filled(Rect::from_min_size(prev.min + vec2(84.0, 30.0), vec2(32.0, 18.0)), (pal.r - 3.0).max(0.0), pal.accent);
    let stroke = if selected { Stroke::new(2.0_f32, pal.accent) } else if resp.hovered() { Stroke::new(1.0_f32, pal.text) } else { Stroke::new(1.0_f32, pal.border) };
    p.rect_stroke(prev, pal.r, stroke);
    p.text(pos2(r.left() + 2.0, r.bottom() - 10.0), Align2::LEFT_CENTER, name, font(12.5, "medium"), if selected { pal.accent } else { pal.text });
    resp
}

impl eframe::App for App {
    fn clear_color(&self, _v: &egui::Visuals) -> [f32; 4] {
        egui::Rgba::from(theme::c(self.cfg.look.bg)).to_array()
    }

    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        // Design anwenden, wenn es sich geaendert hat
        if self.applied.as_ref() != Some(&self.cfg.look) {
            let l = &self.cfg.look;
            if self.applied.as_ref().map_or(true, |o| o.font != l.font) {
                theme::setup_fonts(ctx, &l.font);
            }
            if self.applied.as_ref().map_or(true, |o| o.ui_scale != l.ui_scale) {
                ctx.set_zoom_factor(l.ui_scale.clamp(0.6, 2.0));
            }
            theme::apply_style(ctx, &Pal::from(l));
            self.applied = Some(l.clone());
        }
        // Bild ins Fenster gezogen -> als Hintergrund verwenden
        if let Some(path) = ctx.input(|i| i.raw.dropped_files.first().and_then(|f| f.path.clone())) {
            self.cfg.look.background = "Bild".into();
            self.cfg.look.image_path = path.to_string_lossy().into_owned();
        }
        let pal = Pal::from(&self.cfg.look);
        let t = if self.cfg.look.animations { self.started.elapsed().as_secs_f32() } else { 0.0 };
        let (account, error, quit, busy) = {
            let s = self.h.lock();
            (s.account.clone(), s.error.clone(), s.quit, s.busy)
        };
        if quit {
            ctx.send_viewport_cmd(ViewportCommand::Close);
        }
        if self.cfg.version.is_empty() {
            if let Some(v) = self.h.lock().versions.iter().find(|v| v.kind == "release") {
                self.cfg.version = v.id.clone();
            }
        }
        self.collect_installs();
        self.refresh_prism();
        let mut action = None;

        egui::CentralPanel::default().frame(egui::Frame::none().fill(pal.bg)).show(ctx, |ui| {
            let full = ui.max_rect();
            let title = Rect::from_min_size(full.min, vec2(full.width(), 48.0));
            let content = Rect::from_min_max(pos2(full.left() + 40.0, title.bottom() + 30.0), pos2(full.right() - 40.0, full.bottom() - 28.0));
            match self.page {
                Page::Home => self.home(ui, content, t, &pal, &account, &mut action),
                Page::Profiles => self.profiles_page(ui, content, &pal),
                Page::Client => self.client_page(ui, content, &pal),
                Page::Look => self.look_page(ui, content, &pal),
                Page::Console => self.console(ui, content, &pal),
                Page::Settings => self.settings(ui, content, &pal),
            }
            self.title_bar(ui, title, &pal, &account, &mut action);
            let grip = Rect::from_min_size(full.max - vec2(14.0, 14.0), vec2(14.0, 14.0));
            let g = ui.interact(grip, Id::new("grip"), Sense::drag());
            if g.hovered() {
                ctx.set_cursor_icon(egui::CursorIcon::ResizeNwSe);
            }
            if g.drag_started() {
                ctx.send_viewport_cmd(ViewportCommand::BeginResize(egui::ResizeDirection::SouthEast));
            }
        });

        if let Some(err) = error {
            let screen = ctx.screen_rect();
            egui::Area::new(Id::new("dim")).order(egui::Order::Foreground).fixed_pos(screen.min).show(ctx, |ui| {
                ui.painter().rect_filled(screen, 0.0, alpha(Color32::BLACK, 140));
                ui.allocate_rect(screen, Sense::click());
            });
            egui::Area::new(Id::new("errorbox")).order(egui::Order::Tooltip).anchor(Align2::CENTER_CENTER, vec2(0.0, 0.0)).show(ctx, |ui| {
                egui::Frame::none().fill(pal.bg).stroke(Stroke::new(1.0_f32, pal.text)).rounding(pal.r).inner_margin(24.0).show(ui, |ui| {
                    ui.set_max_width(440.0);
                    ui.label(RichText::new("FEHLER").font(font(11.0, "mono")).color(BAD));
                    ui.label(RichText::new("Etwas ist schiefgelaufen").font(font(24.0, "display")).color(pal.text));
                    ui.label(RichText::new(err).color(pal.muted));
                    ui.add_space(4.0);
                    let (r, resp) = ui.allocate_exact_size(vec2(110.0, 36.0), Sense::click());
                    ui.painter().rect_filled(r, pal.r, if resp.hovered() { pal.accent } else { pal.text });
                    ui.painter().text(r.center(), Align2::CENTER_CENTER, "OK", font(13.0, "monom"), if resp.hovered() { Color32::WHITE } else { pal.bg });
                    if resp.clicked() {
                        let mut s = self.h.lock();
                        s.error = None;
                        if s.status.starts_with("Fehler") {
                            s.status = "Bereit.".into();
                        }
                    }
                });
            });
        }

        match action {
            Some(Action::Play) => self.play(),
            Some(Action::Login) => self.login(),
            Some(Action::OpenPrism) => self.open_prism(),
            Some(Action::Logout) => {
                config::save_account(None);
                self.h.lock().account = None;
            }
            None => {}
        }
        if self.cfg != self.saved {
            config::save_config(&self.cfg);
            self.saved = self.cfg.clone();
        }
        if (self.page == Page::Home && self.cfg.look.animations && self.cfg.look.background == "Landschaft") || busy || self.prof.busy() {
            ctx.request_repaint_after(Duration::from_millis(33));
        }
    }

    fn on_exit(&mut self, _gl: Option<&eframe::glow::Context>) {
        config::save_config(&self.cfg);
    }
}

/// Vor dem Start: Client-Mod einlegen (oder entfernen), Einstellungen schreiben, ggf. Fabric API nachinstallieren.
async fn setup_client_mods(
    h: &Handle,
    client: &reqwest::Client,
    game: &std::path::Path,
    version: &str,
    loader: &str,
    cm: &client_mods::ClientMods,
    profile_id: Option<&str>,
    shared: &std::sync::Arc<std::sync::Mutex<profiles_ui::ModsShared>>,
) {
    match client_mods::prepare(game, version, loader, cm) {
        Ok(client_mods::Prepared::Ready { needs_fabric_api }) => {
            if needs_fabric_api {
                h.status("Installiere Fabric API für die Client-Mods …");
                let hh = h.clone();
                match mods::install(client, mods::Kind::Mod, client_mods::FABRIC_API, version, loader, game, None, &[], move |l| hh.log(l)).await {
                    Ok(list) => {
                        if let Some(pid) = profile_id {
                            shared.lock().unwrap_or_else(|e| e.into_inner()).finished.push((pid.to_string(), list));
                        }
                    }
                    Err(e) => {
                        h.log(format!("[Client-Mods] Fabric API konnte nicht installiert werden ({e:#}) – Client-Mods diesmal aus."));
                        let _ = std::fs::remove_file(game.join("mods").join("peregrine-client.jar"));
                        return;
                    }
                }
            }
            h.log("[Client-Mods] aktiv – im Spiel mit Rechts-Shift das Mod-Menü öffnen");
        }
        Ok(client_mods::Prepared::Unsupported) => h.log(format!(
            "[Client-Mods] Für Minecraft {version} gibt es noch keine Client-Mods (unterstützt: {}).",
            client_mods::supported().join(", ")
        )),
        Ok(client_mods::Prepared::Off) => {}
        Err(e) => h.log(format!("[Client-Mods] Fehler: {e:#}")),
    }
}
