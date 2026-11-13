//! Oberflaeche: rahmenloses Fenster, Seitenleiste, Startseite, Konsole, Einstellungen.
use std::time::{Duration, Instant};

use eframe::egui::{self, pos2, vec2, Align2, Color32, Id, Rect, Sense, Stroke, ViewportCommand};

use crate::config::{self, Config};
use crate::state::Ui as Handle;
use crate::theme::{self, alpha, font, icon, Icon, ACCENT_A, ACCENT_B, BAD, BG, BORDER, GOOD, MUTED, SURFACE, SURFACE2, TEXT, WARN};
use crate::{auth, install, launch, net};

#[derive(PartialEq, Clone, Copy)]
enum Page {
    Home,
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
}

impl App {
    pub fn new(cc: &eframe::CreationContext) -> Self {
        theme::setup(&cc.egui_ctx);
        let rt = tokio::runtime::Builder::new_multi_thread().enable_all().build().expect("Tokio");
        let h = Handle::new(cc.egui_ctx.clone());
        h.lock().account = config::load_account();
        let cfg = config::load_config();
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

    // -------------------------------------------------------------- Bausteine
    fn title_bar(&mut self, ui: &mut egui::Ui, r: Rect) {
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
        let logo = Rect::from_center_size(pos2(r.left() + 38.0, r.center().y), vec2(24.0, 24.0));
        theme::gradient_rrect(p, logo, 7.0, ACCENT_A, ACCENT_B);
        p.text(logo.center(), Align2::CENTER_CENTER, "S", font(14.0, "bold"), Color32::WHITE);
        let end = p.text(pos2(r.left() + 62.0, r.center().y), Align2::LEFT_CENTER, "SKYRUSH", font(13.0, "bold"), TEXT);
        p.text(pos2(end.right() + 6.0, r.center().y), Align2::LEFT_CENTER, "LAUNCHER", font(13.0, "medium"), MUTED);

        let mut x = r.right();
        for (kind, cmd) in [(Icon::Close, 0), (Icon::Maximize, 1), (Icon::Minimize, 2)] {
            let br = Rect::from_min_max(pos2(x - 46.0, r.top()), pos2(x, r.bottom()));
            x -= 46.0;
            let resp = ui.interact(br, Id::new(("winbtn", cmd)), Sense::click());
            let p = ui.painter();
            if resp.hovered() {
                p.rect_filled(br, 0.0, if cmd == 0 { BAD } else { SURFACE2 });
            }
            icon(p, br.center(), 16.0, if resp.hovered() { Color32::WHITE } else { MUTED }, kind);
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

    fn sidebar(&mut self, ui: &mut egui::Ui, r: Rect, account: &Option<config::Account>, action: &mut Option<Action>) {
        let mut y = r.top() + 14.0;
        for (page, kind, tip) in [
            (Page::Home, Icon::Home, "Start"),
            (Page::Console, Icon::Console, "Konsole"),
            (Page::Settings, Icon::Settings, "Einstellungen"),
        ] {
            let br = Rect::from_center_size(pos2(r.center().x, y + 24.0), vec2(48.0, 48.0));
            y += 60.0;
            let resp = ui.interact(br, Id::new(("nav", tip)), Sense::click()).on_hover_text(tip);
            let active = self.page == page;
            let p = ui.painter();
            if active {
                theme::glow(p, br, 14.0, ACCENT_A, 0.8);
                theme::gradient_rrect(p, br, 14.0, ACCENT_A, ACCENT_B);
            } else if resp.hovered() {
                p.rect_filled(br, 14.0, SURFACE2);
            }
            let c = if active || resp.hovered() { Color32::WHITE } else { MUTED };
            icon(p, br.center(), 22.0, c, kind);
            if resp.clicked() {
                if self.page == Page::Settings && page != Page::Settings && self.cfg.game_dir != self.saved.game_dir {
                    self.load_versions();
                }
                self.page = page;
            }
        }
        // Avatar unten
        let ar = Rect::from_center_size(pos2(r.center().x, r.bottom() - 34.0), vec2(40.0, 40.0));
        let tip = match account {
            Some(a) => format!("{} – klicken zum Abmelden", a.name),
            None => "Mit Microsoft anmelden".into(),
        };
        let resp = ui.interact(ar, Id::new("avatar"), Sense::click()).on_hover_text(tip);
        avatar(ui.painter(), ar, account.as_ref().map(|a| a.name.as_str()), resp.hovered());
        if resp.clicked() {
            *action = Some(if account.is_some() { Action::Logout } else { Action::Login });
        }
    }

    fn home(&mut self, ui: &mut egui::Ui, hero: Rect, t: f32, account: &Option<config::Account>, action: &mut Option<Action>) {
        let p = ui.painter().with_clip_rect(hero);
        theme::paint_landscape(&p, hero, t);
        // runde Ecken
        p.rect_stroke(hero.expand(5.0), 16.0 + 5.0, Stroke::new(10.0_f32, BG));
        let p = ui.painter();

        // Kopfbereich
        let latest = self.h.lock().versions.iter().find(|v| v.kind == "release").map(|v| v.id.clone());
        let mut y = hero.top() + 44.0;
        if let Some(l) = latest {
            let txt = format!("NEUESTE VERSION  ·  {l}");
            let g = p.layout_no_wrap(txt, font(11.0, "semibold"), Color32::WHITE);
            let pill = Rect::from_min_size(pos2(hero.left() + 44.0, y), g.size() + vec2(28.0, 12.0));
            p.rect_filled(pill, 20.0, alpha(Color32::BLACK, 90));
            p.rect_stroke(pill, 20.0, Stroke::new(1.0_f32, alpha(Color32::WHITE, 40)));
            p.circle_filled(pos2(pill.left() + 12.0, pill.center().y), 3.5, GOOD);
            p.galley(pos2(pill.left() + 20.0, pill.top() + 6.0), g, Color32::WHITE);
            y = pill.bottom() + 14.0;
        }
        let tr = p.text(pos2(hero.left() + 42.0, y), Align2::LEFT_TOP, "SKYRUSH", font(60.0, "bold"), Color32::WHITE);
        p.text(pos2(hero.left() + 46.0, tr.bottom() - 6.0), Align2::LEFT_TOP, "MINECRAFT  LAUNCHER", font(15.0, "semibold"), Color32::from_rgb(255, 170, 210));
        p.text(
            pos2(hero.left() + 46.0, tr.bottom() + 22.0),
            Align2::LEFT_TOP,
            "Blitzschnell starten  ·  Fabric & Quilt  ·  Java inklusive",
            font(14.0, "regular"),
            alpha(Color32::WHITE, 190),
        );

        // Account-Chip oben rechts
        let chip = Rect::from_min_size(pos2(hero.right() - 284.0, hero.top() + 24.0), vec2(260.0, 56.0));
        p.rect_filled(chip, 16.0, alpha(Color32::BLACK, 110));
        p.rect_stroke(chip, 16.0, Stroke::new(1.0_f32, alpha(Color32::WHITE, 30)));
        let av = Rect::from_center_size(pos2(chip.left() + 30.0, chip.center().y), vec2(36.0, 36.0));
        avatar(p, av, account.as_ref().map(|a| a.name.as_str()), false);
        let (name, sub, sub_c) = match account {
            Some(a) => (a.name.clone(), "Microsoft-Konto", GOOD),
            None => ("Gast".to_string(), "Demo-Modus", WARN),
        };
        p.text(pos2(av.right() + 10.0, chip.center().y - 9.0), Align2::LEFT_CENTER, name, font(14.0, "semibold"), Color32::WHITE);
        p.text(pos2(av.right() + 10.0, chip.center().y + 10.0), Align2::LEFT_CENTER, sub, font(11.5, "medium"), sub_c);
        let btn = Rect::from_min_size(pos2(chip.right() - 92.0, chip.center().y - 15.0), vec2(80.0, 30.0));
        let resp = ui.interact(btn, Id::new("chipbtn"), Sense::click());
        let label = if account.is_some() { "Abmelden" } else { "Anmelden" };
        let p = ui.painter();
        if account.is_some() {
            p.rect_filled(btn, 10.0, if resp.hovered() { SURFACE2 } else { alpha(Color32::WHITE, 20) });
        } else {
            if resp.hovered() {
                theme::glow(p, btn, 10.0, ACCENT_B, 0.6);
            }
            theme::gradient_rrect(p, btn, 10.0, ACCENT_A, ACCENT_B);
        }
        p.text(btn.center(), Align2::CENTER_CENTER, label, font(12.0, "semibold"), Color32::WHITE);
        if resp.clicked() {
            *action = Some(if account.is_some() { Action::Logout } else { Action::Login });
        }

        // Unteres Glas-Panel
        let panel = Rect::from_min_max(pos2(hero.left() + 24.0, hero.bottom() - 118.0), pos2(hero.right() - 24.0, hero.bottom() - 24.0));
        let p = ui.painter();
        p.rect_filled(panel, 20.0, Color32::from_rgba_unmultiplied(16, 13, 26, 225));
        p.rect_stroke(panel, 20.0, Stroke::new(1.0_f32, alpha(Color32::WHITE, 22)));

        // Version
        let vc = Rect::from_min_size(pos2(panel.left() + 16.0, panel.top() + 16.0), vec2(210.0, panel.height() - 32.0));
        let vresp = ui.interact(vc, Id::new("vercard"), Sense::click());
        let p = ui.painter();
        p.rect_filled(vc, 14.0, if vresp.hovered() || self.version_open { SURFACE2 } else { SURFACE });
        p.rect_stroke(vc, 14.0, Stroke::new(1.0_f32, if self.version_open { ACCENT_A } else { BORDER }));
        p.text(pos2(vc.left() + 16.0, vc.top() + 18.0), Align2::LEFT_CENTER, "VERSION", font(10.5, "semibold"), MUTED);
        let shown = if self.cfg.version.is_empty() { "Lädt …".to_string() } else { self.cfg.version.clone() };
        p.text(pos2(vc.left() + 16.0, vc.top() + 44.0), Align2::LEFT_CENTER, shown, font(21.0, "bold"), Color32::WHITE);
        icon(p, pos2(vc.right() - 22.0, vc.center().y), 16.0, MUTED, Icon::Chevron);
        if vresp.clicked() {
            self.version_open = !self.version_open;
            self.just_opened = true;
        }

        // Modloader
        let lx = vc.right() + 16.0;
        p.text(pos2(lx + 2.0, vc.top() + 18.0), Align2::LEFT_CENTER, "MODLOADER", font(10.5, "semibold"), MUTED);
        let seg = Rect::from_min_size(pos2(lx, vc.top() + 30.0), vec2(258.0, 32.0));
        p.rect_filled(seg, 10.0, SURFACE);
        let sw = seg.width() / install::LOADERS.len() as f32;
        for (i, l) in install::LOADERS.iter().enumerate() {
            let sr = Rect::from_min_size(pos2(seg.left() + i as f32 * sw, seg.top()), vec2(sw, seg.height())).shrink(3.0);
            let resp = ui.interact(sr, Id::new(("loader", i)), Sense::click());
            let p = ui.painter();
            let sel = self.cfg.loader == *l;
            if sel {
                theme::gradient_rrect(p, sr, 8.0, ACCENT_A, ACCENT_B);
            } else if resp.hovered() {
                p.rect_filled(sr, 8.0, SURFACE2);
            }
            p.text(sr.center(), Align2::CENTER_CENTER, *l, font(12.5, "semibold"), if sel { Color32::WHITE } else { MUTED });
            if resp.clicked() {
                self.cfg.loader = l.to_string();
            }
        }

        // Status / Fortschritt
        let (busy, status, done, total) = {
            let s = self.h.lock();
            (s.busy, s.status.clone(), s.done, s.total)
        };
        let play = Rect::from_min_size(pos2(panel.right() - 16.0 - 236.0, panel.top() + 14.0), vec2(236.0, panel.height() - 28.0));
        let sx = seg.right() + 24.0;
        let sr = Rect::from_min_max(pos2(sx, vc.top()), pos2(play.left() - 24.0, vc.bottom()));
        if sr.width() > 80.0 {
            let p = ui.painter().with_clip_rect(sr);
            p.text(pos2(sr.left(), vc.top() + 18.0), Align2::LEFT_CENTER, "STATUS", font(10.5, "semibold"), MUTED);
            let (line, col) = if busy {
                (status.clone(), Color32::WHITE)
            } else if status.starts_with("Fehler") {
                (status.clone(), BAD)
            } else if account.is_none() {
                ("Demo-Modus aktiv".to_string(), alpha(Color32::WHITE, 200))
            } else {
                (if status == "Bereit." { "Bereit zum Spielen".to_string() } else { status.clone() }, alpha(Color32::WHITE, 200))
            };
            p.text(pos2(sr.left(), vc.top() + 42.0), Align2::LEFT_CENTER, line, font(13.0, "medium"), col);
            if busy && total > 0 {
                let bar = Rect::from_min_size(pos2(sr.left(), vc.top() + 58.0), vec2(sr.width(), 5.0));
                p.rect_filled(bar, 3.0, SURFACE2);
                let f = done as f32 / total as f32;
                let fr = Rect::from_min_size(bar.min, vec2((bar.width() * f).max(6.0), 5.0));
                theme::gradient_rrect(&p, fr, 3.0, ACCENT_A, ACCENT_B);
            }
        }

        // SPIELEN
        let enabled = !busy && !self.cfg.version.is_empty();
        let resp = ui.interact(play, Id::new("play"), if enabled { Sense::click() } else { Sense::hover() });
        let hover = ui.ctx().animate_bool_with_time(Id::new("playhover"), resp.hovered() && enabled, 0.15);
        let p = ui.painter();
        if busy {
            p.rect_filled(play, 16.0, SURFACE2);
            let f = if total > 0 { done as f32 / total as f32 } else { 0.0 };
            if f > 0.0 {
                let fr = Rect::from_min_size(play.min, vec2((play.width() * f).max(32.0), play.height()));
                theme::gradient_rrect(p, fr, 16.0, alpha(ACCENT_A, 200), alpha(ACCENT_B, 200));
            }
            let txt = if total > 0 { format!("{:.0} %", f * 100.0) } else { "Startet …".into() };
            p.text(play.center(), Align2::CENTER_CENTER, txt, font(20.0, "bold"), Color32::WHITE);
        } else {
            let lift = hover * 2.0;
            let pr = play.translate(vec2(0.0, -lift));
            theme::glow(p, pr, 16.0, ACCENT_B, 0.25 + hover * 0.45);
            theme::gradient_rrect(p, pr, 16.0, ACCENT_A, ACCENT_B);
            icon(p, pos2(pr.left() + 40.0, pr.center().y), 26.0, Color32::WHITE, Icon::Play);
            p.text(pos2(pr.left() + 64.0, pr.center().y - 9.0), Align2::LEFT_CENTER, "SPIELEN", font(22.0, "bold"), Color32::WHITE);
            let sub = format!("{} · {}", if self.cfg.version.is_empty() { "–" } else { &self.cfg.version }, self.cfg.loader);
            p.text(pos2(pr.left() + 65.0, pr.center().y + 15.0), Align2::LEFT_CENTER, sub, font(11.5, "medium"), alpha(Color32::WHITE, 210));
        }
        if resp.clicked() {
            *action = Some(Action::Play);
        }

        // Versions-Auswahl (Popup)
        if self.version_open {
            let popup_h = 360.0_f32.min(vc.top() - hero.top() - 20.0);
            let area = egui::Area::new(Id::new("verpopup"))
                .order(egui::Order::Foreground)
                .fixed_pos(pos2(vc.left(), vc.top() - popup_h - 10.0))
                .show(ui.ctx(), |ui| {
                    egui::Frame::none()
                        .fill(SURFACE)
                        .stroke(Stroke::new(1.0_f32, BORDER))
                        .rounding(16.0)
                        .shadow(egui::epaint::Shadow { offset: vec2(0.0, 10.0), blur: 30.0, spread: 0.0, color: alpha(Color32::BLACK, 160) })
                        .inner_margin(12.0)
                        .show(ui, |ui| {
                            ui.set_width(250.0);
                            ui.set_height(popup_h - 24.0);
                            ui.horizontal(|ui| {
                                ui.label(egui::RichText::new("Versionen").font(font(15.0, "bold")));
                                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                                    theme::toggle(ui, &mut self.cfg.show_snapshots);
                                    ui.label(egui::RichText::new("Snapshots").color(MUTED).size(12.0));
                                });
                            });
                            let versions = self.h.lock().versions.clone();
                            egui::ScrollArea::vertical().auto_shrink([false; 2]).show(ui, |ui| {
                                ui.spacing_mut().item_spacing.y = 4.0;
                                for v in versions.iter().filter(|v| self.cfg.show_snapshots || v.kind == "release") {
                                    let (rr, resp) = ui.allocate_exact_size(vec2(ui.available_width(), 36.0), Sense::click());
                                    let sel = v.id == self.cfg.version;
                                    let p = ui.painter();
                                    if sel {
                                        theme::gradient_rrect(p, rr, 10.0, alpha(ACCENT_A, 90), alpha(ACCENT_B, 60));
                                    } else if resp.hovered() {
                                        p.rect_filled(rr, 10.0, SURFACE2);
                                    }
                                    p.text(pos2(rr.left() + 12.0, rr.center().y), Align2::LEFT_CENTER, &v.id, font(13.5, "semibold"), Color32::WHITE);
                                    let (tag, tc) = if v.kind == "release" { ("Release", GOOD) } else { ("Snapshot", WARN) };
                                    let g = p.layout_no_wrap(tag.into(), font(10.0, "semibold"), tc);
                                    let tr = Rect::from_min_size(pos2(rr.right() - g.size().x - 22.0, rr.center().y - 10.0), g.size() + vec2(14.0, 6.0));
                                    p.rect_filled(tr, 8.0, alpha(tc, 30));
                                    p.galley(tr.min + vec2(7.0, 3.0), g, tc);
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
        self.just_opened = false;
    }

    fn console(&mut self, ui: &mut egui::Ui, r: Rect) {
        ui.allocate_new_ui(egui::UiBuilder::new().max_rect(r.shrink2(vec2(24.0, 20.0))), |ui| {
            ui.horizontal(|ui| {
                ui.vertical(|ui| {
                    ui.label(egui::RichText::new("Konsole").font(font(26.0, "bold")));
                    ui.label(egui::RichText::new("Ausgabe des Launchers und des Spiels").color(MUTED));
                });
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
            egui::Frame::none().fill(Color32::from_rgb(9, 8, 14)).stroke(Stroke::new(1.0_f32, BORDER)).rounding(14.0).inner_margin(14.0).show(ui, |ui| {
                let s = self.h.lock();
                let row_h = ui.text_style_height(&egui::TextStyle::Monospace) + 3.0;
                egui::ScrollArea::vertical().stick_to_bottom(true).auto_shrink([false; 2]).show_rows(ui, row_h, s.log.len(), |ui, range| {
                    ui.spacing_mut().item_spacing.y = 3.0;
                    for line in s.log.range(range) {
                        let c = if line.starts_with("[Fehler]") || line.contains("ERROR") {
                            BAD
                        } else if line.starts_with("[Launcher]") {
                            Color32::from_rgb(196, 170, 255)
                        } else if line.contains("WARN") {
                            WARN
                        } else {
                            Color32::from_rgb(180, 176, 198)
                        };
                        ui.label(egui::RichText::new(line).monospace().color(c));
                    }
                    if s.log.is_empty() {
                        ui.label(egui::RichText::new("Noch keine Ausgabe.").color(MUTED));
                    }
                });
            });
        });
    }

    fn settings(&mut self, ui: &mut egui::Ui, r: Rect) {
        ui.allocate_new_ui(egui::UiBuilder::new().max_rect(r.shrink2(vec2(24.0, 20.0))), |ui| {
            ui.label(egui::RichText::new("Einstellungen").font(font(26.0, "bold")));
            ui.label(egui::RichText::new("Änderungen werden automatisch gespeichert").color(MUTED));
            ui.add_space(4.0);
            egui::ScrollArea::vertical().auto_shrink([false; 2]).show(ui, |ui| {
                let c = &mut self.cfg;
                card(ui, "Leistung", "Arbeitsspeicher für Minecraft. 4–8 GB reichen meistens, mit vielen Mods mehr.", |ui| {
                    ui.horizontal(|ui| {
                        ui.add(egui::Slider::new(&mut c.ram_mb, 1024..=32768).step_by(512.0).show_value(false));
                        ui.label(egui::RichText::new(format!("{:.1} GB", c.ram_mb as f32 / 1024.0)).font(font(18.0, "bold")).color(Color32::WHITE));
                    });
                    field(ui, "Zusätzliche JVM-Argumente", &mut c.extra_jvm_args, "z. B. -XX:+UseZGC");
                });
                card(ui, "Spiel", "Wo Minecraft gespeichert wird und wie das Fenster startet.", |ui| {
                    ui.horizontal(|ui| {
                        field(ui, "Spielordner", &mut c.game_dir, "");
                        if ui.button("Öffnen").clicked() {
                            let _ = std::fs::create_dir_all(&c.game_dir);
                            let _ = open::that(&c.game_dir);
                        }
                    });
                    ui.horizontal(|ui| {
                        ui.label(egui::RichText::new("Fenstergröße").color(MUTED));
                        ui.add(egui::DragValue::new(&mut c.width).range(320..=7680).suffix(" px"));
                        ui.label("×");
                        ui.add(egui::DragValue::new(&mut c.height).range(240..=4320).suffix(" px"));
                    });
                    ui.horizontal(|ui| {
                        theme::toggle(ui, &mut c.close_on_launch);
                        ui.label("Launcher nach dem Spielstart schließen");
                    });
                });
                card(ui, "Java", "Leer lassen: Die passende Java-Version wird automatisch geladen.", |ui| {
                    field(ui, "Java-Pfad", &mut c.java_path, "automatisch");
                });
                card(ui, "Microsoft-Login", "Eigene Azure-App für die Anmeldung (Anleitung in README.md).", |ui| {
                    field(ui, "Azure Client-ID", &mut c.client_id, "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx");
                    field(ui, "Redirect-URI", &mut c.redirect_uri, "");
                });
                let repair = &mut self.repair;
                card(ui, "Wartung", "Startet das Spiel nicht oder fehlen Texturen? Dann alle Dateien prüfen lassen.", |ui| {
                    ui.horizontal(|ui| {
                        theme::toggle(ui, repair);
                        ui.label("Beim nächsten Start alle Dateien prüfen und reparieren");
                    });
                });
            });
        });
        self.cfg.client_id = self.cfg.client_id.trim().to_string();
    }
}

fn avatar(p: &egui::Painter, r: Rect, name: Option<&str>, hover: bool) {
    match name {
        Some(n) => {
            theme::gradient_rrect(p, r, r.width() / 2.0, ACCENT_A, ACCENT_B);
            let ch = n.chars().next().unwrap_or('?').to_uppercase().to_string();
            p.text(r.center(), Align2::CENTER_CENTER, ch, font(r.width() * 0.45, "bold"), Color32::WHITE);
        }
        None => {
            p.circle_filled(r.center(), r.width() / 2.0, if hover { SURFACE2 } else { SURFACE });
            p.circle_stroke(r.center(), r.width() / 2.0, Stroke::new(1.0_f32, BORDER));
            let c = r.center();
            let s = r.width();
            p.circle_filled(pos2(c.x, c.y - s * 0.1), s * 0.14, MUTED);
            p.add(egui::Shape::convex_polygon(
                vec![pos2(c.x - s * 0.24, c.y + s * 0.26), pos2(c.x - s * 0.14, c.y + s * 0.1), pos2(c.x + s * 0.14, c.y + s * 0.1), pos2(c.x + s * 0.24, c.y + s * 0.26)],
                MUTED,
                Stroke::NONE,
            ));
        }
    }
}

fn card(ui: &mut egui::Ui, title: &str, sub: &str, body: impl FnOnce(&mut egui::Ui)) {
    egui::Frame::none().fill(SURFACE).stroke(Stroke::new(1.0_f32, BORDER)).rounding(16.0).inner_margin(18.0).show(ui, |ui| {
        ui.set_width(ui.available_width());
        ui.label(egui::RichText::new(title).font(font(16.0, "bold")).color(Color32::WHITE));
        ui.label(egui::RichText::new(sub).color(MUTED).size(12.5));
        ui.add_space(4.0);
        body(ui);
    });
    ui.add_space(6.0);
}

fn field(ui: &mut egui::Ui, label: &str, value: &mut String, hint: &str) {
    ui.vertical(|ui| {
        ui.spacing_mut().item_spacing.y = 4.0;
        ui.label(egui::RichText::new(label).color(MUTED).size(12.0));
        ui.add(egui::TextEdit::singleline(value).hint_text(hint).desired_width(460.0).margin(vec2(10.0, 7.0)));
    });
}

impl eframe::App for App {
    fn clear_color(&self, _v: &egui::Visuals) -> [f32; 4] {
        egui::Rgba::from(BG).to_array()
    }

    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        let t = self.started.elapsed().as_secs_f32();
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

        egui::CentralPanel::default().frame(egui::Frame::none().fill(BG)).show(ctx, |ui| {
            let full = ui.max_rect();
            let title = Rect::from_min_size(full.min, vec2(full.width(), 40.0));
            let side = Rect::from_min_max(pos2(full.left(), title.bottom()), pos2(full.left() + 76.0, full.bottom()));
            let content = Rect::from_min_max(pos2(side.right(), title.bottom()), pos2(full.right() - 12.0, full.bottom() - 12.0));
            match self.page {
                Page::Home => self.home(ui, content, t, &account, &mut action),
                Page::Console => self.console(ui, content),
                Page::Settings => self.settings(ui, content),
            }
            self.title_bar(ui, title);
            self.sidebar(ui, side, &account, &mut action);
            // Groesse aendern (unten rechts)
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
                ui.painter().rect_filled(screen, 0.0, alpha(Color32::BLACK, 150));
                ui.allocate_rect(screen, Sense::click());
            });
            egui::Area::new(Id::new("errorbox")).order(egui::Order::Tooltip).anchor(Align2::CENTER_CENTER, vec2(0.0, 0.0)).show(ctx, |ui| {
                egui::Frame::none().fill(SURFACE).stroke(Stroke::new(1.0_f32, BORDER)).rounding(18.0).inner_margin(24.0).show(ui, |ui| {
                    ui.set_max_width(420.0);
                    ui.horizontal(|ui| {
                        let (r, _) = ui.allocate_exact_size(vec2(36.0, 36.0), Sense::hover());
                        ui.painter().circle_filled(r.center(), 18.0, alpha(BAD, 40));
                        ui.painter().text(r.center(), Align2::CENTER_CENTER, "!", font(20.0, "bold"), BAD);
                        ui.label(egui::RichText::new("Etwas ist schiefgelaufen").font(font(18.0, "bold")).color(Color32::WHITE));
                    });
                    ui.label(egui::RichText::new(err).color(Color32::from_rgb(200, 196, 216)));
                    ui.add_space(6.0);
                    let (r, resp) = ui.allocate_exact_size(vec2(110.0, 38.0), Sense::click());
                    theme::gradient_rrect(ui.painter(), r, 12.0, ACCENT_A, ACCENT_B);
                    ui.painter().text(r.center(), Align2::CENTER_CENTER, "OK", font(14.0, "bold"), Color32::WHITE);
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
        if self.page == Page::Home || busy {
            ctx.request_repaint_after(Duration::from_millis(33));
        }
    }

    fn on_exit(&mut self, _gl: Option<&eframe::glow::Context>) {
        config::save_config(&self.cfg);
    }
}
