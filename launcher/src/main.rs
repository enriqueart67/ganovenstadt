#![cfg_attr(all(windows, not(debug_assertions)), windows_subsystem = "windows")]
//! Ganovenstadt-Launcher – ein schneller Minecraft-Launcher in Rust.
mod auth;
mod config;
mod install;
mod launch;
mod net;
mod state;

use eframe::egui::{self, Color32, RichText};

use config::{Config, LAUNCHER_NAME};
use state::Ui;

const GREEN: Color32 = Color32::from_rgb(59, 165, 93);

enum Action {
    Play,
    Login,
    Logout,
}

struct App {
    cfg: Config,
    ui: Ui,
    rt: tokio::runtime::Runtime,
    client: reqwest::Client,
    settings: Option<Config>,
    repair: bool,
}

impl App {
    fn new(cc: &eframe::CreationContext) -> Self {
        cc.egui_ctx.set_visuals(egui::Visuals::dark());
        let rt = tokio::runtime::Builder::new_multi_thread().enable_all().build().expect("Tokio");
        let ui = Ui::new(cc.egui_ctx.clone());
        ui.lock().account = config::load_account();
        let app = Self { cfg: config::load_config(), ui, rt, client: net::client(), settings: None, repair: false };
        app.load_versions();
        app.refresh_login();
        app
    }

    fn load_versions(&self) {
        let (ui, client, gd) = (self.ui.clone(), self.client.clone(), self.cfg.game_dir.clone());
        self.rt.spawn(async move {
            let list = match install::fetch_manifest(&client).await {
                Ok(v) => v,
                Err(e) => {
                    ui.log(format!("[Launcher] Offline? Zeige installierte Versionen ({e:#})"));
                    install::installed(&gd)
                }
            };
            ui.lock().versions = list;
            ui.repaint();
        });
    }

    fn refresh_login(&self) {
        let Some(acc) = self.ui.lock().account.clone() else { return };
        if self.cfg.client_id.is_empty() {
            return;
        }
        let (ui, client, cid) = (self.ui.clone(), self.client.clone(), self.cfg.client_id.clone());
        self.rt.spawn(async move {
            match auth::refresh(&client, &cid, &acc.refresh_token).await {
                Ok(a) => {
                    config::save_account(Some(&a));
                    ui.log(format!("[Launcher] Sitzung erneuert für {}", a.name));
                    ui.lock().account = Some(a);
                }
                Err(e) => ui.log(format!("[Launcher] Sitzung konnte nicht erneuert werden ({e:#}) – ggf. neu anmelden.")),
            }
            ui.repaint();
        });
    }

    fn login(&self) {
        let (ui, client) = (self.ui.clone(), self.client.clone());
        let (cid, redirect) = (self.cfg.client_id.clone(), self.cfg.redirect_uri.clone());
        ui.log("[Launcher] Browser wird zur Microsoft-Anmeldung geöffnet …");
        self.rt.spawn(async move {
            match auth::login(&client, &cid, &redirect).await {
                Ok(a) => {
                    config::save_account(Some(&a));
                    ui.log(format!("[Launcher] Angemeldet als {}", a.name));
                    ui.lock().account = Some(a);
                    ui.repaint();
                }
                Err(e) => ui.error(format!("{e:#}")),
            }
        });
    }

    fn play(&mut self) {
        config::save_config(&self.cfg);
        let (ui, client, cfg, repair) = (self.ui.clone(), self.client.clone(), self.cfg.clone(), self.repair);
        self.repair = false;
        let account = ui.lock().account.clone();
        ui.set_busy(true);
        self.rt.spawn(async move {
            let res = async {
                let id = install::prepare(&ui, &client, &cfg, &cfg.version, &cfg.loader, repair).await?;
                let (java, args) = launch::build(&cfg, &id, account.as_ref())?;
                ui.log(format!("[Launcher] Starte {id} mit {}", java.display()));
                ui.status(format!("{id} läuft."));
                launch::spawn(&java, &args, &cfg.game_dir, ui.clone(), cfg.close_on_launch)?;
                anyhow::Ok(())
            }
            .await;
            match res {
                Ok(()) if cfg.close_on_launch => ui.lock().quit = true,
                Ok(()) => {}
                Err(e) => ui.error(format!("{e:#}")),
            }
            ui.set_busy(false);
        });
    }
}

impl eframe::App for App {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        let mut action = None;
        let ui_handle = self.ui.clone();
        let mut s = ui_handle.lock();
        if s.quit {
            ctx.send_viewport_cmd(egui::ViewportCommand::Close);
        }
        if self.cfg.version.is_empty() {
            if let Some(v) = s.versions.iter().find(|v| v.kind == "release") {
                self.cfg.version = v.id.clone();
            }
        }

        egui::TopBottomPanel::top("top").show(ctx, |ui| {
            ui.add_space(8.0);
            ui.horizontal(|ui| {
                ui.label(RichText::new("Ganovenstadt-Launcher").size(22.0).strong());
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    if ui.button("Einstellungen").clicked() {
                        self.settings = Some(self.cfg.clone());
                    }
                    match &s.account {
                        Some(a) => {
                            if ui.button("Abmelden").clicked() {
                                action = Some(Action::Logout);
                            }
                            ui.label(RichText::new(&a.name).strong().color(GREEN));
                        }
                        None => {
                            if ui.button("Mit Microsoft anmelden").clicked() {
                                action = Some(Action::Login);
                            }
                            ui.label("Nicht angemeldet (Demo-Modus)");
                        }
                    }
                });
            });
            ui.add_space(8.0);
        });

        egui::TopBottomPanel::bottom("bottom").show(ctx, |ui| {
            ui.add_space(8.0);
            ui.horizontal(|ui| {
                ui.vertical(|ui| {
                    ui.horizontal(|ui| {
                        ui.label("Version:");
                        egui::ComboBox::from_id_salt("version")
                            .selected_text(&self.cfg.version)
                            .height(400.0)
                            .show_ui(ui, |ui| {
                                for v in s.versions.iter().filter(|v| self.cfg.show_snapshots || v.kind == "release") {
                                    ui.selectable_value(&mut self.cfg.version, v.id.clone(), &v.id);
                                }
                            });
                        ui.label("Loader:");
                        egui::ComboBox::from_id_salt("loader")
                            .selected_text(&self.cfg.loader)
                            .show_ui(ui, |ui| {
                                for l in install::LOADERS {
                                    ui.selectable_value(&mut self.cfg.loader, l.to_string(), l);
                                }
                            });
                        ui.checkbox(&mut self.cfg.show_snapshots, "Snapshots");
                        ui.checkbox(&mut self.repair, "Reparieren")
                            .on_hover_text("Alle Dateien per Prüfsumme kontrollieren und fehlerhafte neu laden");
                    });
                    ui.label(&s.status);
                    let frac = if s.total > 0 { s.done as f32 / s.total as f32 } else { 0.0 };
                    let text = if s.total > 0 { format!("{}/{}", s.done, s.total) } else { String::new() };
                    ui.add(egui::ProgressBar::new(frac).text(text).desired_width(ui.available_width() - 210.0));
                });
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    let btn = egui::Button::new(RichText::new("▶  SPIELEN").size(22.0).strong().color(Color32::WHITE))
                        .fill(GREEN)
                        .min_size(egui::vec2(190.0, 58.0));
                    if ui.add_enabled(!s.busy && !self.cfg.version.is_empty(), btn).clicked() {
                        action = Some(Action::Play);
                    }
                });
            });
            ui.add_space(8.0);
        });

        egui::CentralPanel::default().show(ctx, |ui| {
            let row_h = ui.text_style_height(&egui::TextStyle::Monospace);
            egui::ScrollArea::vertical()
                .stick_to_bottom(true)
                .auto_shrink([false; 2])
                .show_rows(ui, row_h, s.log.len(), |ui, range| {
                    for line in s.log.range(range) {
                        ui.label(RichText::new(line).monospace().size(12.0));
                    }
                });
        });

        if let Some(err) = s.error.clone() {
            egui::Window::new("Fehler").collapsible(false).resizable(false)
                .anchor(egui::Align2::CENTER_CENTER, [0.0, 0.0])
                .show(ctx, |ui| {
                    ui.label(err);
                    if ui.button("OK").clicked() {
                        s.error = None;
                    }
                });
        }
        drop(s);

        if let Some(draft) = &mut self.settings {
            let (mut save, mut close) = (false, false);
            egui::Window::new("Einstellungen").collapsible(false).resizable(false)
                .anchor(egui::Align2::CENTER_CENTER, [0.0, 0.0])
                .show(ctx, |ui| {
                    egui::Grid::new("settings").num_columns(2).spacing([12.0, 6.0]).show(ui, |ui| {
                        ui.label("Spielordner");
                        ui.add(egui::TextEdit::singleline(&mut draft.game_dir).desired_width(320.0));
                        ui.end_row();
                        ui.label("Java-Pfad (leer = automatisch)");
                        ui.add(egui::TextEdit::singleline(&mut draft.java_path).desired_width(320.0));
                        ui.end_row();
                        ui.label("Arbeitsspeicher");
                        ui.add(egui::Slider::new(&mut draft.ram_mb, 1024..=32768).step_by(512.0).suffix(" MB"));
                        ui.end_row();
                        ui.label("Fenstergröße");
                        ui.horizontal(|ui| {
                            ui.add(egui::DragValue::new(&mut draft.width).range(320..=7680));
                            ui.label("×");
                            ui.add(egui::DragValue::new(&mut draft.height).range(240..=4320));
                        });
                        ui.end_row();
                        ui.label("Zusätzliche JVM-Argumente");
                        ui.add(egui::TextEdit::singleline(&mut draft.extra_jvm_args).desired_width(320.0));
                        ui.end_row();
                        ui.label("Azure Client-ID");
                        ui.add(egui::TextEdit::singleline(&mut draft.client_id).desired_width(320.0));
                        ui.end_row();
                        ui.label("Redirect-URI");
                        ui.add(egui::TextEdit::singleline(&mut draft.redirect_uri).desired_width(320.0));
                        ui.end_row();
                        ui.label("");
                        ui.checkbox(&mut draft.close_on_launch, "Launcher nach dem Start schließen");
                        ui.end_row();
                    });
                    ui.add_space(8.0);
                    ui.horizontal(|ui| {
                        if ui.button("Speichern").clicked() {
                            save = true;
                        }
                        if ui.button("Abbrechen").clicked() {
                            close = true;
                        }
                    });
                });
            if save {
                let dir_changed = draft.game_dir != self.cfg.game_dir;
                draft.client_id = draft.client_id.trim().to_string();
                self.cfg = draft.clone();
                config::save_config(&self.cfg);
                if dir_changed {
                    self.load_versions();
                }
            }
            if save || close {
                self.settings = None;
            }
        }

        match action {
            Some(Action::Play) => self.play(),
            Some(Action::Login) => {
                if self.cfg.client_id.is_empty() {
                    self.ui.error("Für den Microsoft-Login brauchst du eine eigene Azure Client-ID. Trage sie unter Einstellungen ein (Anleitung in README.md).");
                    self.settings = Some(self.cfg.clone());
                } else {
                    self.login();
                }
            }
            Some(Action::Logout) => {
                config::save_account(None);
                self.ui.lock().account = None;
            }
            None => {}
        }
    }

    fn on_exit(&mut self, _gl: Option<&eframe::glow::Context>) {
        config::save_config(&self.cfg);
    }
}

fn main() -> eframe::Result<()> {
    let opts = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_title(LAUNCHER_NAME)
            .with_inner_size([880.0, 580.0])
            .with_min_inner_size([720.0, 460.0]),
        ..Default::default()
    };
    eframe::run_native(LAUNCHER_NAME, opts, Box::new(|cc| Ok(Box::new(App::new(cc)))))
}
