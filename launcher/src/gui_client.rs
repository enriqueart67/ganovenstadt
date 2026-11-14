//! Seite „Client-Mods“: an/aus, Erklaerung des Mod-Menues im Spiel und Liste aller Module.
use eframe::egui::{self, vec2, Rect, RichText};

use super::{card, App};
use crate::client_mods::{self, MODULES};
use crate::theme::{self, Pal};

impl App {
    pub(super) fn client_page(&mut self, ui: &mut egui::Ui, r: Rect, pal: &Pal) {
        let mut import: Option<std::path::PathBuf> = None;
        ui.allocate_new_ui(egui::UiBuilder::new().max_rect(r.shrink2(vec2(24.0, 18.0))), |ui| {
            super::header(ui, pal, "Mods", "Built into the game – everything is configured in the in-game menu (Right Shift)");
            egui::ScrollArea::vertical().auto_shrink([false; 2]).show(ui, |ui| {
                let cm = &mut self.cfg.client;
                card(ui, pal, "Peregrine client mods", |ui| {
                    ui.horizontal(|ui| {
                        theme::toggle(ui, pal, &mut cm.enabled);
                        ui.label("Enable the client mods in game");
                    });
                    ui.label(
                        RichText::new(format!(
                            "Works with Fabric or Quilt on Minecraft {}. Fabric API is installed automatically when needed.",
                            client_mods::supported().join(", ")
                        ))
                        .color(pal.muted)
                        .size(12.5),
                    );
                });
                card(ui, pal, "Turbo – more FPS", |ui| {
                    ui.horizontal(|ui| {
                        theme::toggle(ui, pal, &mut cm.turbo);
                        ui.label("Install performance mods automatically");
                    });
                    ui.label(RichText::new("For Fabric and Quilt. Peregrine downloads the matching versions from Modrinth and keeps them up to date.").color(pal.muted).size(12.5));
                    for (slug, name, what) in client_mods::TURBO {
                        ui.horizontal(|ui| {
                            let mut on = !cm.turbo_off.iter().any(|o| o == slug);
                            ui.add_enabled_ui(cm.turbo, |ui| theme::toggle(ui, pal, &mut on));
                            if on {
                                cm.turbo_off.retain(|o| o != slug);
                            } else if !cm.turbo_off.iter().any(|o| o == slug) {
                                cm.turbo_off.push(slug.to_string());
                            }
                            ui.label(RichText::new(name).color(pal.text));
                            ui.label(RichText::new(format!("– {what}")).color(pal.muted).size(12.5));
                        });
                    }
                });
                let (cape_path, cape_msg) = (&mut self.cape_path, &mut self.cape_msg);
                card(ui, pal, "Cosmetics – capes from your own photos", |ui| {
                    ui.label(RichText::new("Drop an image (PNG, JPG, WEBP …) on this window – or enter its path. Peregrine crops it and turns it into an HD cape (also for the elytra). In game: mod menu › Cosmetics › Cape › Design “Photo: …”.").color(pal.muted).size(12.5));
                    ui.horizontal(|ui| {
                        ui.add(egui::TextEdit::singleline(cape_path).hint_text("C:\\Users\\…\\image.png").desired_width(360.0));
                        if ui.button("Add").clicked() && !cape_path.trim().is_empty() {
                            import = Some(std::path::PathBuf::from(cape_path.trim().trim_matches('"')));
                            cape_path.clear();
                        }
                    });
                    if let Some(m) = cape_msg.as_ref() {
                        ui.label(RichText::new(m).color(theme::GOOD).size(12.5));
                    }
                    let list = client_mods::custom_capes();
                    if list.is_empty() {
                        ui.label(RichText::new("No capes of your own yet.").color(pal.muted).size(12.5));
                    }
                    for name in list {
                        ui.horizontal(|ui| {
                            ui.label(RichText::new(format!("⚑  {name}")).color(pal.text));
                            if ui.small_button("Remove").clicked() {
                                let _ = std::fs::remove_file(client_mods::capes_dir().join(format!("{name}.png")));
                                *cape_msg = None;
                            }
                        });
                    }
                    ui.label(RichText::new("Note: capes and emotes are only visible to you – showing them to others would need a cosmetics server.").color(pal.muted).size(11.5));
                });
                let capture = &mut self.key_capture;
                card(ui, pal, "Keys", |ui| {
                    ui.label(RichText::new("Click a key, then press the new one. Applies to every profile from the next launch.").color(pal.muted).size(12.5));
                    // Gedrückte Taste erfassen
                    if let Some(action) = capture.clone() {
                        let pressed = ui.input(|i| {
                            i.events.iter().find_map(|e| match e {
                                egui::Event::Key { key, pressed: true, .. } => Some(*key),
                                _ => None,
                            })
                        });
                        if let Some(k) = pressed {
                            if k == egui::Key::Escape {
                                *capture = None;
                            } else if let Some(mc) = client_mods::key_from_egui(k.name()) {
                                cm.keys.insert(action, mc);
                                *capture = None;
                            }
                        }
                    }
                    for (action, label, default) in client_mods::KEYS {
                        let current = cm.keys.get(action).cloned().unwrap_or_else(|| default.to_string());
                        ui.horizontal(|ui| {
                            let (lr, _) = ui.allocate_exact_size(vec2(170.0, 26.0), egui::Sense::hover());
                            ui.painter().text(lr.left_center(), egui::Align2::LEFT_CENTER, label, theme::font(14.0, "regular"), pal.text);
                            let waiting = capture.as_deref() == Some(action);
                            let text = if waiting { "Press a key …".to_string() } else { client_mods::key_label(&current) };
                            let btn = egui::Button::new(RichText::new(text).font(theme::font(13.0, "semibold")).color(if waiting { pal.accent } else { pal.text }))
                                .min_size(vec2(150.0, 26.0));
                            if ui.add(btn).clicked() {
                                *capture = if waiting { None } else { Some(action.to_string()) };
                            }
                            if waiting {
                                egui::ComboBox::from_id_salt(("special", action))
                                    .selected_text("Special key …")
                                    .show_ui(ui, |ui| {
                                        for (id, name) in client_mods::SPECIAL_KEYS {
                                            if ui.selectable_label(false, name).clicked() {
                                                cm.keys.insert(action.to_string(), id.to_string());
                                                *capture = None;
                                            }
                                        }
                                    });
                            }
                            if cm.keys.get(action).is_some_and(|k| k != default) && ui.small_button("Default").clicked() {
                                cm.keys.insert(action.to_string(), default.to_string());
                            }
                        });
                    }
                });
                card(ui, pal, &format!("All modules ({})", MODULES.len()), |ui| {
                    let mut last = "";
                    for (cat, name, desc) in MODULES {
                        if cat != last {
                            ui.add_space(4.0);
                            ui.label(RichText::new(cat).font(theme::font(13.0, "semibold")).color(pal.accent));
                            last = cat;
                        }
                        ui.horizontal(|ui| {
                            ui.label(RichText::new(name).color(pal.text));
                            ui.label(RichText::new(format!("– {desc}")).color(pal.muted).size(12.5));
                        });
                    }
                });
            });
        });
        if let Some(p) = import {
            self.import_cape(&p);
        }
    }
}
