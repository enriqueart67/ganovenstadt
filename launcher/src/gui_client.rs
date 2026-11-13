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
                card(ui, pal, "So geht's", |ui| {
                    for (key, what) in [
                        ("Rechts-Shift", "Mod-Menü öffnen: Module an-/ausschalten, Einstellungen, Suche"),
                        ("HUD bearbeiten", "im Menü oben rechts: Anzeigen mit der Maus verschieben, Mausrad = Größe"),
                        ("C", "Zoom (gedrückt halten)"),
                        ("G", "Fullbright an/aus"),
                        ("V", "Auto-Sprint an/aus"),
                    ] {
                        ui.horizontal(|ui| {
                            let (kr, _) = ui.allocate_exact_size(vec2(110.0, 24.0), egui::Sense::hover());
                            ui.painter().rect_filled(kr, pal.r.min(6.0), pal.surface2);
                            ui.painter().text(kr.center(), egui::Align2::CENTER_CENTER, key, theme::font(12.5, "semibold"), pal.text);
                            ui.label(RichText::new(what).color(pal.muted));
                        });
                    }
                    ui.label(RichText::new("Alle Tasten lassen sich im Spiel unter Optionen › Steuerung › Peregrine Client ändern.").color(pal.muted).size(12.0));
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
