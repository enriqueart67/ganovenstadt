//! Oberflaeche: rahmenloses Fenster, Seitenleiste, Startseite, Aussehen, Konsole, Einstellungen.
use std::time::{Duration, Instant};

use eframe::egui::{self, pos2, vec2, Align2, Color32, Id, Rect, RichText, Sense, Stroke, ViewportCommand};

use crate::config::{self, Config};
use crate::state::Ui as Handle;
use crate::theme::{self, alpha, font, icon, Icon, Look, Pal, BAD, GOOD, WARN};
use crate::{auth, install, launch, net};

#[derive(PartialEq, Clone, Copy)]
enum Page {
    Home,
    Look,
    Console,
    Settings,
}

enum Action {
    Play,
    Login,
    Logout,
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
}

impl App {
    pub fn new(cc: &eframe::CreationContext) -> Self {
        let rt = tokio::runtime::Builder::new_multi_thread().enable_all().build().expect("Tokio");
        let h = Handle::new(cc.egui_ctx.clone());
        h.lock().account = config::load_account();
        let cfg = config::load_config();
        theme::setup_fonts(&cc.egui_ctx, cfg.look.font != "Standard");
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

    fn login(&mut self) {
        if self.cfg.client_id.is_empty() {
            self.h.error("Für den Microsoft-Login brauchst du eine eigene Azure Client-ID. Trage sie unter Einstellungen › Microsoft-Login ein (Anleitung in README.md).");
            self.page = Page::Settings;
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

    fn play(&mut self) {
        config::save_config(&self.cfg);
        self.saved = self.cfg.clone();
        let (h, client, cfg, repair) = (self.h.clone(), self.client.clone(), self.cfg.clone(), self.repair);
        self.repair = false;
        let account = h.lock().account.clone();
        h.set_busy(true);
        self.rt.spawn(async move {
            let res = async {
                let id = install::prepare(&h, &client, &cfg, &cfg.version, &cfg.loader, repair).await?;
                let (java, args) = launch::build(&cfg, &id, account.as_ref())?;
                h.log(format!("[Launcher] Starte {id} mit {}", java.display()));
                h.status(format!("{id} läuft"));
                launch::spawn(&java, &args, &cfg.game_dir, h.clone(), cfg.close_on_launch)?;
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
    fn title_bar(&mut self, ui: &mut egui::Ui, r: Rect, pal: &Pal) {
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
        let logo = Rect::from_center_size(pos2(r.left() + 36.0, r.center().y), vec2(26.0, 26.0));
        theme::logo(p, pal, logo, &self.cfg.look.title);
        let end = p.text(pos2(r.left() + 60.0, r.center().y), Align2::LEFT_CENTER, &self.cfg.look.title, font(13.0, "bold"), pal.text);
        p.text(pos2(end.right() + 6.0, r.center().y), Align2::LEFT_CENTER, &self.cfg.look.subtitle, font(13.0, "medium"), pal.muted);

        let mut x = r.right();
        for (kind, cmd) in [(Icon::Close, 0), (Icon::Maximize, 1), (Icon::Minimize, 2)] {
            let br = Rect::from_min_max(pos2(x - 46.0, r.top()), pos2(x, r.bottom() - 1.0));
            x -= 46.0;
            let resp = ui.interact(br, Id::new(("winbtn", cmd)), Sense::click());
            let p = ui.painter();
            let hot = resp.hovered();
            if hot {
                p.rect_filled(br, 0.0, if cmd == 0 { BAD } else { pal.surface2 });
            }
            icon(p, br.center(), 16.0, if hot && cmd == 0 { Color32::WHITE } else if hot { pal.text } else { pal.muted }, kind);
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
    }

    fn sidebar(&mut self, ui: &mut egui::Ui, r: Rect, pal: &Pal, account: &Option<config::Account>, action: &mut Option<Action>) {
        ui.painter().line_segment([r.right_top(), r.right_bottom()], Stroke::new(1.0_f32, pal.border));
        let mut y = r.top() + 12.0;
        for (page, kind, tip) in [
            (Page::Home, Icon::Home, "Start"),
            (Page::Look, Icon::Brush, "Aussehen"),
            (Page::Console, Icon::Console, "Konsole"),
            (Page::Settings, Icon::Settings, "Einstellungen"),
        ] {
            let br = Rect::from_center_size(pos2(r.center().x, y + 22.0), vec2(44.0, 44.0));
            y += 54.0;
            let resp = ui.interact(br, Id::new(("nav", tip)), Sense::click()).on_hover_text(tip);
            let active = self.page == page;
            let p = ui.painter();
            if active {
                p.rect_filled(br, pal.r, pal.surface2);
                pal.fill(p, Rect::from_center_size(pos2(r.left() + 3.0, br.center().y), vec2(3.0, 22.0)), 2.0);
            } else if resp.hovered() {
                p.rect_filled(br, pal.r, alpha(pal.surface2, 160));
            }
            let c = if active { pal.accent } else if resp.hovered() { pal.text } else { pal.muted };
            icon(p, br.center(), 22.0, c, kind);
            if resp.clicked() {
                if self.page == Page::Settings && page != Page::Settings && self.cfg.game_dir != self.saved.game_dir {
                    self.load_versions();
                }
                self.page = page;
            }
        }
        let ar = Rect::from_center_size(pos2(r.center().x, r.bottom() - 32.0), vec2(36.0, 36.0));
        let tip = match account {
            Some(a) => format!("{} – klicken zum Abmelden", a.name),
            None => "Mit Microsoft anmelden".into(),
        };
        let resp = ui.interact(ar, Id::new("avatar"), Sense::click()).on_hover_text(tip);
        avatar(ui.painter(), pal, ar, account.as_ref().map(|a| a.name.as_str()), resp.hovered());
        if resp.clicked() {
            *action = Some(if account.is_some() { Action::Logout } else { Action::Login });
        }
    }

    // -------------------------------------------------------------- Startseite
    fn home(&mut self, ui: &mut egui::Ui, hero: Rect, t: f32, pal: &Pal, account: &Option<config::Account>, action: &mut Option<Action>) {
        let look = self.cfg.look.clone();
        let ctx = ui.ctx().clone();
        let tex = if look.background == "Bild" { self.bg_texture(&ctx) } else { None };
        let p = ui.painter().with_clip_rect(hero);
        let mut on_image = true;
        match look.background.as_str() {
            "Bild" => match &tex {
                Some(tex) => {
                    let [w, h] = tex.size();
                    let (ia, ra) = (w as f32 / h as f32, hero.width() / hero.height());
                    let uv = if ia > ra {
                        let s = ra / ia;
                        Rect::from_min_max(pos2((1.0 - s) / 2.0, 0.0), pos2((1.0 + s) / 2.0, 1.0))
                    } else {
                        let s = ia / ra;
                        Rect::from_min_max(pos2(0.0, (1.0 - s) / 2.0), pos2(1.0, (1.0 + s) / 2.0))
                    };
                    p.image(tex.id(), hero, uv, Color32::WHITE);
                }
                None => {
                    on_image = false;
                    p.rect_filled(hero, 0.0, pal.surface);
                    let msg = match &self.bg_err {
                        Some((_, e)) => format!("Bild konnte nicht geladen werden: {e}"),
                        None => "Kein Bild gewählt – zieh ein Bild ins Fenster oder wähle eins unter Aussehen.".into(),
                    };
                    p.text(hero.center() - vec2(0.0, 40.0), Align2::CENTER_CENTER, msg, font(13.0, "regular"), pal.muted);
                }
            },
            "Verlauf" => {
                on_image = false;
                theme::vertical_gradient(&p, hero, &[
                    (0.0, theme::lerp_color(pal.bg, pal.accent, 0.35)),
                    (0.7, theme::lerp_color(pal.bg, pal.accent, 0.08)),
                    (1.0, pal.bg),
                ]);
            }
            "Einfarbig" => {
                on_image = false;
                p.rect_filled(hero, 0.0, pal.surface);
            }
            _ => theme::paint_landscape(&p, hero, t, &look.scene),
        }
        if on_image {
            p.rect_filled(hero, 0.0, alpha(Color32::BLACK, (look.dim.clamp(0.0, 0.9) * 255.0) as u8));
        }
        theme::vertical_gradient(&p, hero, &[(0.0, alpha(pal.bg, 0)), (0.6, alpha(pal.bg, 0)), (1.0, alpha(pal.bg, 230))]);
        p.rect_stroke(hero.expand(5.0), pal.r + 4.0 + 5.0, Stroke::new(10.0_f32, pal.bg));

        let (ht, hm) = if on_image { (Color32::WHITE, alpha(Color32::WHITE, 200)) } else { (pal.text, pal.muted) };
        let p = ui.painter();

        // Kopfbereich
        let mut y = hero.top() + 40.0;
        let latest = self.h.lock().versions.iter().find(|v| v.kind == "release").map(|v| v.id.clone());
        if let (true, Some(l)) = (look.show_badge, latest) {
            let g = p.layout_no_wrap(format!("Neueste Version  {l}"), font(11.5, "medium"), ht);
            let pill = Rect::from_min_size(pos2(hero.left() + 40.0, y), g.size() + vec2(30.0, 10.0));
            p.rect_filled(pill, pal.r.min(pill.height() / 2.0), if on_image { alpha(Color32::BLACK, 90) } else { pal.surface2 });
            p.circle_filled(pos2(pill.left() + 12.0, pill.center().y), 3.0, GOOD);
            p.galley(pos2(pill.left() + 21.0, pill.top() + 5.0), g, ht);
            y = pill.bottom() + 16.0;
        }
        // Text mit dezentem Schatten, damit er auf jedem Hintergrund lesbar bleibt
        let txt = |pos: egui::Pos2, s: &str, f: egui::FontId, col: Color32| -> Rect {
            if on_image {
                p.text(pos + vec2(0.0, 2.0), Align2::LEFT_TOP, s, f.clone(), alpha(Color32::BLACK, 70));
            }
            p.text(pos, Align2::LEFT_TOP, s, f, col)
        };
        let mut tx = hero.left() + 38.0;
        if look.show_logo {
            let lr = Rect::from_min_size(pos2(tx + 2.0, y + 6.0), vec2(96.0, 96.0));
            if on_image {
                p.rect_filled(lr.translate(vec2(0.0, 3.0)), lr.width() * 0.22, alpha(Color32::BLACK, 60));
            }
            theme::logo(p, pal, lr, &look.title);
            tx = lr.right() + 22.0;
        }
        let tr = txt(pos2(tx, y), &look.title, font(56.0, "bold"), ht);
        let mut ty = tr.bottom() - 4.0;
        if !look.subtitle.is_empty() {
            let sub_c = if on_image { theme::lerp_color(pal.accent, Color32::WHITE, 0.3) } else { pal.accent };
            let r = txt(pos2(tx + 3.0, ty), &look.subtitle, font(15.0, "semibold"), sub_c);
            ty = r.bottom() + 6.0;
        }
        if look.show_tagline && !look.tagline.is_empty() {
            txt(pos2(tx + 3.0, ty), &look.tagline, font(13.5, "regular"), hm);
        }

        // Account oben rechts
        let chip = Rect::from_min_size(pos2(hero.right() - 270.0, hero.top() + 22.0), vec2(248.0, 52.0));
        p.rect_filled(chip, pal.r + 2.0, if on_image { alpha(Color32::BLACK, 110) } else { pal.surface });
        if !on_image {
            p.rect_stroke(chip, pal.r + 2.0, Stroke::new(1.0_f32, pal.border));
        }
        let av = Rect::from_center_size(pos2(chip.left() + 28.0, chip.center().y), vec2(32.0, 32.0));
        avatar(p, pal, av, account.as_ref().map(|a| a.name.as_str()), false);
        let (name, sub, sub_c) = match account {
            Some(a) => (a.name.clone(), "Microsoft-Konto", GOOD),
            None => ("Gast".to_string(), "Demo-Modus", WARN),
        };
        p.text(pos2(av.right() + 10.0, chip.center().y - 8.0), Align2::LEFT_CENTER, name, font(13.5, "semibold"), ht);
        p.text(pos2(av.right() + 10.0, chip.center().y + 10.0), Align2::LEFT_CENTER, sub, font(11.0, "medium"), sub_c);
        let btn = Rect::from_min_size(pos2(chip.right() - 90.0, chip.center().y - 14.0), vec2(78.0, 28.0));
        let resp = ui.interact(btn, Id::new("chipbtn"), Sense::click());
        let p = ui.painter();
        if account.is_some() {
            p.rect_filled(btn, pal.r, if resp.hovered() { pal.surface2 } else { alpha(pal.surface2, 160) });
            p.text(btn.center(), Align2::CENTER_CENTER, "Abmelden", font(12.0, "semibold"), pal.text);
        } else {
            pal.fill(p, btn, pal.r);
            if resp.hovered() {
                p.rect_filled(btn, pal.r, alpha(Color32::WHITE, 25));
            }
            p.text(btn.center(), Align2::CENTER_CENTER, "Anmelden", font(12.0, "semibold"), pal.on_accent);
        }
        if resp.clicked() {
            *action = Some(if account.is_some() { Action::Logout } else { Action::Login });
        }

        // Unteres Panel
        let panel = Rect::from_min_max(pos2(hero.left() + 20.0, hero.bottom() - 108.0), pos2(hero.right() - 20.0, hero.bottom() - 20.0));
        let p = ui.painter();
        p.rect_filled(panel, pal.r + 4.0, alpha(pal.surface, 240));
        p.rect_stroke(panel, pal.r + 4.0, Stroke::new(1.0_f32, pal.border));

        let vc = Rect::from_min_size(pos2(panel.left() + 14.0, panel.top() + 14.0), vec2(200.0, panel.height() - 28.0));
        let vresp = ui.interact(vc, Id::new("vercard"), Sense::click());
        let p = ui.painter();
        p.rect_filled(vc, pal.r, if vresp.hovered() || self.version_open { pal.surface2 } else { alpha(pal.surface2, 140) });
        p.rect_stroke(vc, pal.r, Stroke::new(1.0_f32, if self.version_open { pal.accent } else { pal.border }));
        p.text(pos2(vc.left() + 14.0, vc.top() + 17.0), Align2::LEFT_CENTER, "Version", font(11.5, "medium"), pal.muted);
        let shown = if self.cfg.version.is_empty() { "Lädt …".to_string() } else { self.cfg.version.clone() };
        p.text(pos2(vc.left() + 14.0, vc.top() + 41.0), Align2::LEFT_CENTER, shown, font(19.0, "semibold"), pal.text);
        icon(p, pos2(vc.right() - 20.0, vc.center().y), 15.0, pal.muted, Icon::Chevron);
        if vresp.clicked() {
            self.version_open = !self.version_open;
            self.just_opened = true;
        }

        let lx = vc.right() + 14.0;
        p.text(pos2(lx + 2.0, vc.top() + 17.0), Align2::LEFT_CENTER, "Modloader", font(11.5, "medium"), pal.muted);
        let seg = Rect::from_min_size(pos2(lx, vc.top() + 30.0), vec2(246.0, 30.0));
        let mut loader = self.cfg.loader.clone();
        segmented_at(ui, pal, seg, "loader", &install::LOADERS, &mut loader);
        self.cfg.loader = loader;

        let (busy, status, done, total) = {
            let s = self.h.lock();
            (s.busy, s.status.clone(), s.done, s.total)
        };
        let play = Rect::from_min_size(pos2(panel.right() - 14.0 - 220.0, panel.top() + 14.0), vec2(220.0, panel.height() - 28.0));
        let sr = Rect::from_min_max(pos2(seg.right() + 22.0, vc.top()), pos2(play.left() - 22.0, vc.bottom()));
        if sr.width() > 80.0 {
            let p = ui.painter().with_clip_rect(sr);
            p.text(pos2(sr.left(), vc.top() + 17.0), Align2::LEFT_CENTER, "Status", font(11.5, "medium"), pal.muted);
            let (line, col) = if busy {
                (status.clone(), pal.text)
            } else if status.starts_with("Fehler") {
                (status.clone(), BAD)
            } else if account.is_none() {
                ("Demo-Modus aktiv".to_string(), pal.text)
            } else {
                (if status == "Bereit." { "Bereit".to_string() } else { status.clone() }, pal.text)
            };
            p.text(pos2(sr.left(), vc.top() + 41.0), Align2::LEFT_CENTER, line, font(13.0, "medium"), col);
            if busy && total > 0 {
                let bar = Rect::from_min_size(pos2(sr.left(), vc.top() + 56.0), vec2(sr.width(), 4.0));
                p.rect_filled(bar, 2.0, pal.surface2);
                let fr = Rect::from_min_size(bar.min, vec2((bar.width() * done as f32 / total as f32).max(4.0), 4.0));
                pal.fill(&p, fr, 2.0);
            }
        }

        let enabled = !busy && !self.cfg.version.is_empty();
        let resp = ui.interact(play, Id::new("play"), if enabled { Sense::click() } else { Sense::hover() });
        let hover = ui.ctx().animate_bool_with_time(Id::new("playhover"), resp.hovered() && enabled, 0.12);
        let p = ui.painter();
        if busy {
            p.rect_filled(play, pal.r + 2.0, pal.surface2);
            let f = if total > 0 { done as f32 / total as f32 } else { 0.0 };
            if f > 0.0 {
                let fr = Rect::from_min_size(play.min, vec2((play.width() * f).max(pal.r * 2.0 + 4.0), play.height()));
                pal.fill(p, fr, pal.r + 2.0);
            }
            let txt = if total > 0 { format!("{:.0} %", f * 100.0) } else { "Startet …".into() };
            p.text(play.center(), Align2::CENTER_CENTER, txt, font(18.0, "bold"), if f > 0.5 { pal.on_accent } else { pal.text });
        } else {
            pal.shine(p, play, pal.r + 2.0, 0.3 + hover * 0.5);
            pal.fill(p, play, pal.r + 2.0);
            if hover > 0.0 {
                p.rect_filled(play, pal.r + 2.0, alpha(Color32::WHITE, (hover * 28.0) as u8));
            }
            icon(p, pos2(play.left() + 34.0, play.center().y), 22.0, pal.on_accent, Icon::Play);
            p.text(pos2(play.left() + 56.0, play.center().y - 8.0), Align2::LEFT_CENTER, "Spielen", font(19.0, "bold"), pal.on_accent);
            let sub = format!("{} · {}", if self.cfg.version.is_empty() { "–" } else { &self.cfg.version }, self.cfg.loader);
            p.text(pos2(play.left() + 57.0, play.center().y + 13.0), Align2::LEFT_CENTER, sub, font(11.0, "medium"), alpha(pal.on_accent, 200));
        }
        if resp.clicked() {
            *action = Some(Action::Play);
        }

        if self.version_open {
            self.version_popup(ui, pal, vc, hero);
        }
        self.just_opened = false;
    }

    fn version_popup(&mut self, ui: &mut egui::Ui, pal: &Pal, vc: Rect, hero: Rect) {
        let popup_h = 360.0_f32.min(vc.top() - hero.top() - 16.0);
        let area = egui::Area::new(Id::new("verpopup"))
            .order(egui::Order::Foreground)
            .fixed_pos(pos2(vc.left(), vc.top() - popup_h - 8.0))
            .show(ui.ctx(), |ui| {
                egui::Frame::none()
                    .fill(pal.surface)
                    .stroke(Stroke::new(1.0_f32, pal.border))
                    .rounding(pal.r + 4.0)
                    .shadow(egui::epaint::Shadow { offset: vec2(0.0, 8.0), blur: 24.0, spread: 0.0, color: alpha(Color32::BLACK, 120) })
                    .inner_margin(10.0)
                    .show(ui, |ui| {
                        ui.set_width(240.0);
                        ui.set_height(popup_h - 20.0);
                        ui.horizontal(|ui| {
                            ui.label(RichText::new("Versionen").font(font(14.0, "semibold")));
                            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                                theme::toggle(ui, pal, &mut self.cfg.show_snapshots);
                                ui.label(RichText::new("Snapshots").color(pal.muted).size(12.0));
                            });
                        });
                        let versions = self.h.lock().versions.clone();
                        egui::ScrollArea::vertical().auto_shrink([false; 2]).show(ui, |ui| {
                            ui.spacing_mut().item_spacing.y = 2.0;
                            for v in versions.iter().filter(|v| self.cfg.show_snapshots || v.kind == "release") {
                                let (rr, resp) = ui.allocate_exact_size(vec2(ui.available_width(), 32.0), Sense::click());
                                let sel = v.id == self.cfg.version;
                                let p = ui.painter();
                                if sel {
                                    p.rect_filled(rr, pal.r, alpha(pal.accent, 45));
                                } else if resp.hovered() {
                                    p.rect_filled(rr, pal.r, pal.surface2);
                                }
                                p.text(pos2(rr.left() + 10.0, rr.center().y), Align2::LEFT_CENTER, &v.id, font(13.0, "medium"), if sel { pal.accent } else { pal.text });
                                if v.kind != "release" {
                                    p.text(pos2(rr.right() - 10.0, rr.center().y), Align2::RIGHT_CENTER, "Snapshot", font(10.5, "medium"), WARN);
                                }
                                if resp.clicked() {
                                    self.cfg.version = v.id.clone();
                                    self.version_open = false;
                                }
                            }
                        });
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
                    ui.horizontal(|ui| {
                        theme::toggle(ui, pal, &mut l.glow);
                        ui.label("Leuchteffekt um den Spielen-Button");
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
    }
}

// ------------------------------------------------------------------ Widgets
fn load_image(path: &str) -> anyhow::Result<egui::ColorImage> {
    let img = image::open(path)?;
    let img = if img.width() > 2560 || img.height() > 2560 { img.resize(2560, 2560, image::imageops::FilterType::Triangle) } else { img };
    let rgba = img.to_rgba8();
    Ok(egui::ColorImage::from_rgba_unmultiplied([rgba.width() as usize, rgba.height() as usize], rgba.as_raw()))
}

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

fn header(ui: &mut egui::Ui, pal: &Pal, title: &str, sub: &str) {
    ui.vertical(|ui| {
        ui.spacing_mut().item_spacing.y = 2.0;
        ui.label(RichText::new(title).font(font(24.0, "bold")).color(pal.text));
        ui.label(RichText::new(sub).color(pal.muted).size(12.5));
    });
    ui.add_space(6.0);
}

fn card(ui: &mut egui::Ui, pal: &Pal, title: &str, body: impl FnOnce(&mut egui::Ui)) {
    egui::Frame::none().fill(pal.surface).stroke(Stroke::new(1.0_f32, pal.border)).rounding(pal.r + 2.0).inner_margin(16.0).show(ui, |ui| {
        ui.set_width(ui.available_width());
        ui.label(RichText::new(title).font(font(15.0, "semibold")).color(pal.text));
        ui.add_space(2.0);
        body(ui);
    });
    ui.add_space(4.0);
}

fn field(ui: &mut egui::Ui, pal: &Pal, label: &str, value: &mut String, hint: &str) {
    ui.vertical(|ui| {
        ui.spacing_mut().item_spacing.y = 4.0;
        ui.label(RichText::new(label).color(pal.muted).size(12.0));
        ui.add(egui::TextEdit::singleline(value).hint_text(hint).desired_width(440.0).margin(vec2(10.0, 6.0)));
    });
}

fn segmented_at(ui: &mut egui::Ui, pal: &Pal, rect: Rect, id: &str, options: &[&str], value: &mut String) {
    ui.painter().rect_filled(rect, pal.r, pal.surface2);
    let w = rect.width() / options.len() as f32;
    for (i, o) in options.iter().enumerate() {
        let r = Rect::from_min_size(pos2(rect.left() + i as f32 * w, rect.top()), vec2(w, rect.height())).shrink(3.0);
        let resp = ui.interact(r, Id::new((id, i)), Sense::click());
        let sel = value == o;
        let p = ui.painter();
        if sel {
            pal.fill(p, r, (pal.r - 2.0).max(0.0));
        } else if resp.hovered() {
            p.rect_filled(r, (pal.r - 2.0).max(0.0), theme::lerp_color(pal.surface2, pal.text, 0.08));
        }
        p.text(r.center(), Align2::CENTER_CENTER, *o, font(12.5, "medium"), if sel { pal.on_accent } else { pal.muted });
        if resp.clicked() {
            *value = o.to_string();
        }
    }
}

fn segmented(ui: &mut egui::Ui, pal: &Pal, id: &str, options: &[&str], value: &mut String) {
    let w = options.iter().map(|o| ui.fonts(|f| f.layout_no_wrap(o.to_string(), font(12.5, "medium"), Color32::WHITE).size().x)).fold(0.0_f32, f32::max) + 28.0;
    let (rect, _) = ui.allocate_exact_size(vec2(w * options.len() as f32 + 6.0, 32.0), Sense::hover());
    segmented_at(ui, pal, rect, id, options, value);
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
                theme::setup_fonts(ctx, l.font != "Standard");
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
        let mut action = None;

        egui::CentralPanel::default().frame(egui::Frame::none().fill(pal.bg)).show(ctx, |ui| {
            let full = ui.max_rect();
            let title = Rect::from_min_size(full.min, vec2(full.width(), 40.0));
            let side = Rect::from_min_max(pos2(full.left(), title.bottom()), pos2(full.left() + 68.0, full.bottom()));
            let content = Rect::from_min_max(pos2(side.right() + 12.0, title.bottom() + 12.0), pos2(full.right() - 12.0, full.bottom() - 12.0));
            match self.page {
                Page::Home => self.home(ui, content, t, &pal, &account, &mut action),
                Page::Look => self.look_page(ui, content, &pal),
                Page::Console => self.console(ui, content, &pal),
                Page::Settings => self.settings(ui, content, &pal),
            }
            self.title_bar(ui, title, &pal);
            self.sidebar(ui, side, &pal, &account, &mut action);
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
                egui::Frame::none().fill(pal.surface).stroke(Stroke::new(1.0_f32, pal.border)).rounding(pal.r + 4.0).inner_margin(22.0).show(ui, |ui| {
                    ui.set_max_width(420.0);
                    ui.label(RichText::new("Etwas ist schiefgelaufen").font(font(17.0, "bold")).color(pal.text));
                    ui.label(RichText::new(err).color(pal.muted));
                    ui.add_space(4.0);
                    let (r, resp) = ui.allocate_exact_size(vec2(96.0, 34.0), Sense::click());
                    pal.fill(ui.painter(), r, pal.r);
                    ui.painter().text(r.center(), Align2::CENTER_CENTER, "OK", font(13.5, "semibold"), pal.on_accent);
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
        if (self.page == Page::Home && self.cfg.look.animations && self.cfg.look.background == "Landschaft") || busy {
            ctx.request_repaint_after(Duration::from_millis(33));
        }
    }

    fn on_exit(&mut self, _gl: Option<&eframe::glow::Context>) {
        config::save_config(&self.cfg);
    }
}
