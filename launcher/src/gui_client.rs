//! Seite „Client-Mods“: an/aus, Erklaerung des Mod-Menues im Spiel und Liste aller Module.
use eframe::egui::{self, vec2, Rect, RichText};

use super::{card, App};
use crate::client_mods::{self, MODULES};
use crate::theme::{self, Pal};

impl App {
    pub(super) fn client_page(&mut self, ui: &mut egui::Ui, r: Rect, pal: &Pal) {
        ui.allocate_new_ui(egui::UiBuilder::new().max_rect(r.shrink2(vec2(24.0, 18.0))), |ui| {
            super::header(ui, pal, "Client-Mods", "Eingebaute Extras im Spiel – eingestellt wird alles im Mod-Menü im Spiel");
            egui::ScrollArea::vertical().auto_shrink([false; 2]).show(ui, |ui| {
                let cm = &mut self.cfg.client;
                card(ui, pal, "Peregrine Client-Mods", |ui| {
                    ui.horizontal(|ui| {
                        theme::toggle(ui, pal, &mut cm.enabled);
                        ui.label("Client-Mods im Spiel aktivieren");
                    });
                    ui.label(
                        RichText::new(format!(
                            "Funktioniert mit Fabric oder Quilt in Minecraft {}. Die Fabric API wird bei Bedarf automatisch installiert.",
                            client_mods::supported().join(", ")
                        ))
                        .color(pal.muted)
                        .size(12.5),
                    );
                });
                card(ui, pal, "Turbo – mehr FPS", |ui| {
                    ui.horizontal(|ui| {
                        theme::toggle(ui, pal, &mut cm.turbo);
                        ui.label("Leistungs-Mods automatisch installieren");
                    });
                    ui.label(RichText::new("Für Fabric und Quilt. Peregrine lädt die passenden Versionen von Modrinth und hält sie aktuell.").color(pal.muted).size(12.5));
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
                let capture = &mut self.key_capture;
                card(ui, pal, "Tasten", |ui| {
                    ui.label(RichText::new("Klicke auf eine Taste und drücke dann die neue. Gilt ab dem nächsten Spielstart für alle Profile. Im Menü: „HUD bearbeiten“ oben rechts, Anzeigen mit der Maus verschieben, Mausrad = Größe.").color(pal.muted).size(12.5));
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
                            let text = if waiting { "Taste drücken …".to_string() } else { client_mods::key_label(&current) };
                            let btn = egui::Button::new(RichText::new(text).font(theme::font(13.0, "semibold")).color(if waiting { pal.accent } else { pal.text }))
                                .min_size(vec2(150.0, 26.0));
                            if ui.add(btn).clicked() {
                                *capture = if waiting { None } else { Some(action.to_string()) };
                            }
                            if waiting {
                                egui::ComboBox::from_id_salt(("special", action))
                                    .selected_text("Sondertaste …")
                                    .show_ui(ui, |ui| {
                                        for (id, name) in client_mods::SPECIAL_KEYS {
                                            if ui.selectable_label(false, name).clicked() {
                                                cm.keys.insert(action.to_string(), id.to_string());
                                                *capture = None;
                                            }
                                        }
                                    });
                            }
                            if cm.keys.get(action).is_some_and(|k| k != default) && ui.small_button("Standard").clicked() {
                                cm.keys.insert(action.to_string(), default.to_string());
                            }
                        });
                    }
                });
                card(ui, pal, &format!("Alle Module ({})", MODULES.len()), |ui| {
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
    }
}
