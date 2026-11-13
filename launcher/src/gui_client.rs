//! Seite „Client-Mods“: FPS, CPS, Koordinaten, Keystrokes, Fadenkreuz (mit Live-Vorschau), Zoom usw.
use eframe::egui::{self, pos2, vec2, Color32, Rect, RichText, Stroke};

use super::{card, segmented, App};
use crate::client_mods::{self, ClientMods};
use crate::theme::{self, alpha, Pal};

const POSITIONS: [(&str, &str); 4] = [("top_left", "Oben links"), ("top_right", "Oben rechts"), ("bottom_left", "Unten links"), ("bottom_right", "Unten rechts")];
const STYLES: [(&str, &str); 4] = [("cross", "Kreuz"), ("t", "T"), ("dot", "Punkt"), ("circle", "Kreis")];

fn hex_to_rgb(hex: &str) -> [u8; 3] {
    let h = hex.trim_start_matches('#');
    let v = u32::from_str_radix(&h[h.len().saturating_sub(6)..], 16).unwrap_or(0xFFFFFF);
    [(v >> 16) as u8, (v >> 8) as u8, v as u8]
}

fn rgb_to_hex(c: [u8; 3]) -> String {
    format!("#{:02X}{:02X}{:02X}", c[0], c[1], c[2])
}

fn color_edit(ui: &mut egui::Ui, hex: &mut String) {
    let mut c = hex_to_rgb(hex);
    if egui::color_picker::color_edit_button_srgb(ui, &mut c).changed() {
        *hex = rgb_to_hex(c);
    }
}

/// Auswahl mit internen Werten (z. B. "top_left") und deutschen Beschriftungen.
fn choice(ui: &mut egui::Ui, pal: &Pal, id: &str, options: &[(&str, &str)], value: &mut String) {
    let labels: Vec<&str> = options.iter().map(|(_, l)| *l).collect();
    let mut sel = options.iter().find(|(k, _)| k == value).map(|(_, l)| l.to_string()).unwrap_or_default();
    segmented(ui, pal, id, &labels, &mut sel);
    if let Some((k, _)) = options.iter().find(|(_, l)| *l == sel) {
        *value = k.to_string();
    }
}

fn row(ui: &mut egui::Ui, pal: &Pal, on: &mut bool, label: &str, hint: &str) {
    ui.horizontal(|ui| {
        theme::toggle(ui, pal, on);
        ui.label(label);
        if !hint.is_empty() {
            ui.label(RichText::new(hint).color(pal.muted).size(12.0));
        }
    });
}

/// Fadenkreuz wie im Spiel zeichnen (Pixel hochskaliert).
fn crosshair_preview(ui: &mut egui::Ui, cm: &ClientMods) {
    let (rect, _) = ui.allocate_exact_size(vec2(180.0, 180.0), egui::Sense::hover());
    let p = ui.painter();
    theme::vertical_gradient(p, rect, &[(0.0, Color32::from_rgb(110, 160, 220)), (0.55, Color32::from_rgb(160, 200, 240)), (0.56, Color32::from_rgb(90, 140, 70)), (1.0, Color32::from_rgb(70, 110, 55))]);
    let c = &cm.crosshair;
    let px = 5.0; // ein Minecraft-Pixel
    let center = rect.center();
    let col = {
        let [r, g, b] = hex_to_rgb(&c.color);
        Color32::from_rgb(r, g, b)
    };
    let (t, s, gap) = (c.thickness.max(1), c.size.max(1), c.gap.max(0));
    let (lo, hi) = (t / 2, t - t / 2);
    let mut rects: Vec<[i32; 4]> = Vec::new();
    match c.style.as_str() {
        "dot" => rects.push([-lo - s / 2, -lo - s / 2, hi + s / 2, hi + s / 2]),
        "circle" => {
            let r = (gap + s) as f32;
            for a in (0..360).step_by(4) {
                let rad = (a as f32).to_radians();
                let (x, y) = ((rad.cos() * r).round() as i32, (rad.sin() * r).round() as i32);
                rects.push([x - lo, y - lo, x + hi, y + hi]);
            }
        }
        style => {
            if style != "t" {
                rects.push([-lo, -gap - s, hi, -gap]);
            }
            rects.push([-lo, gap + (hi - lo), hi, gap + s + (hi - lo)]);
            rects.push([-gap - s, -lo, -gap, hi]);
            rects.push([gap + (hi - lo), -lo, gap + s + (hi - lo), hi]);
        }
    }
    if c.dot && c.style != "dot" {
        rects.push([-lo, -lo, hi, hi]);
    }
    let to = |r: &[i32; 4], e: f32| Rect::from_min_max(pos2(center.x + r[0] as f32 * px - e, center.y + r[1] as f32 * px - e), pos2(center.x + r[2] as f32 * px + e, center.y + r[3] as f32 * px + e));
    if c.outline {
        for r in &rects {
            p.rect_filled(to(r, px), 0.0, alpha(Color32::BLACK, 190));
        }
    }
    for r in &rects {
        p.rect_filled(to(r, 0.0), 0.0, col);
    }
    p.rect_stroke(rect, 6.0, Stroke::new(1.0_f32, alpha(Color32::BLACK, 60)));
}

impl App {
    pub(super) fn client_page(&mut self, ui: &mut egui::Ui, r: Rect, pal: &Pal) {
        ui.allocate_new_ui(egui::UiBuilder::new().max_rect(r.shrink2(vec2(24.0, 18.0))), |ui| {
            super::header(ui, pal, "Client-Mods", "Eingebaute Extras im Spiel – Änderungen wirken sofort, auch während du spielst");
            egui::ScrollArea::vertical().auto_shrink([false; 2]).show(ui, |ui| {
                let cm = &mut self.cfg.client;
                card(ui, pal, "Peregrine Client-Mods", |ui| {
                    row(ui, pal, &mut cm.enabled, "Client-Mods im Spiel aktivieren", "");
                    ui.label(
                        RichText::new(format!(
                            "Funktioniert mit Fabric oder Quilt in Minecraft {}. Die Fabric API wird bei Bedarf automatisch installiert.",
                            client_mods::supported().join(", ")
                        ))
                        .color(pal.muted)
                        .size(12.5),
                    );
                });
                if !cm.enabled {
                    return;
                }
                card(ui, pal, "Anzeigen", |ui| {
                    ui.horizontal_wrapped(|ui| {
                        for (on, label) in [(&mut cm.fps, "FPS"), (&mut cm.cps, "CPS"), (&mut cm.coords, "Koordinaten"), (&mut cm.direction, "Richtung"), (&mut cm.clock, "Uhrzeit")] {
                            theme::toggle(ui, pal, on);
                            ui.label(label);
                            ui.add_space(10.0);
                        }
                    });
                    ui.horizontal(|ui| {
                        ui.label(RichText::new("Position").color(pal.muted));
                        choice(ui, pal, "hudpos", &POSITIONS, &mut cm.hud_position);
                    });
                    ui.horizontal(|ui| {
                        ui.label(RichText::new("Beschriftung").color(pal.muted));
                        color_edit(ui, &mut cm.accent_color);
                        ui.add_space(10.0);
                        ui.label(RichText::new("Werte").color(pal.muted));
                        color_edit(ui, &mut cm.text_color);
                        ui.add_space(16.0);
                        theme::toggle(ui, pal, &mut cm.background);
                        ui.label("Hintergrund");
                        ui.add_space(10.0);
                        theme::toggle(ui, pal, &mut cm.shadow);
                        ui.label("Schatten");
                    });
                });
                card(ui, pal, "Keystrokes", |ui| {
                    row(ui, pal, &mut cm.keystrokes, "W A S D, Maustasten (mit CPS) und Leertaste anzeigen", "");
                    ui.horizontal(|ui| {
                        ui.label(RichText::new("Position").color(pal.muted));
                        choice(ui, pal, "keyspos", &POSITIONS, &mut cm.keys_position);
                    });
                });
                card(ui, pal, "Fadenkreuz", |ui| {
                    row(ui, pal, &mut cm.crosshair.enabled, "Eigenes Fadenkreuz", "");
                    ui.horizontal(|ui| {
                        ui.vertical(|ui| {
                            ui.horizontal(|ui| {
                                ui.label(RichText::new("Form").color(pal.muted));
                                choice(ui, pal, "chstyle", &STYLES, &mut cm.crosshair.style);
                            });
                            let x = &mut cm.crosshair;
                            ui.horizontal(|ui| {
                                ui.label(RichText::new("Größe   ").color(pal.muted));
                                ui.add(egui::Slider::new(&mut x.size, 1..=15));
                            });
                            ui.horizontal(|ui| {
                                ui.label(RichText::new("Abstand").color(pal.muted));
                                ui.add(egui::Slider::new(&mut x.gap, 0..=10));
                            });
                            ui.horizontal(|ui| {
                                ui.label(RichText::new("Dicke   ").color(pal.muted));
                                ui.add(egui::Slider::new(&mut x.thickness, 1..=4));
                            });
                            ui.horizontal(|ui| {
                                ui.label(RichText::new("Farbe").color(pal.muted));
                                color_edit(ui, &mut x.color);
                                ui.add_space(12.0);
                                theme::toggle(ui, pal, &mut x.outline);
                                ui.label("Umrandung");
                                ui.add_space(10.0);
                                theme::toggle(ui, pal, &mut x.dot);
                                ui.label("Punkt in der Mitte");
                            });
                        });
                        ui.add_space(12.0);
                        ui.vertical(|ui| {
                            ui.label(RichText::new("Vorschau").color(pal.muted).size(12.0));
                            crosshair_preview(ui, cm);
                        });
                    });
                });
                card(ui, pal, "Zoom", |ui| {
                    row(ui, pal, &mut cm.zoom, "Zoom mit Taste C (gedrückt halten)", "");
                    ui.horizontal(|ui| {
                        ui.label(RichText::new("Stärke").color(pal.muted));
                        ui.add(egui::Slider::new(&mut cm.zoom_factor, 2.0..=10.0).step_by(0.5).suffix("×"));
                    });
                });
                card(ui, pal, "Weitere", |ui| {
                    row(ui, pal, &mut cm.fullbright, "Fullbright", "– alles hell, auch in Höhlen (im Spiel: Taste G)");
                    row(ui, pal, &mut cm.auto_sprint, "Auto-Sprint", "– automatisch sprinten beim Laufen (im Spiel: Taste V)");
                    ui.label(RichText::new("Alle Tasten kannst du im Spiel unter Optionen › Steuerung › Peregrine Client ändern.").color(pal.muted).size(12.5));
                });
                ui.add_space(4.0);
                if ui.button("Standard wiederherstellen").clicked() {
                    *cm = ClientMods::default();
                }
            });
        });
    }
}
