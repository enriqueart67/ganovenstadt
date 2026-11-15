//! Seiten im Stoop-Design: Einstellungen mit Kategorien (inkl. Grafik) und die Mod-Bibliothek
//! (echte An/Aus-Schalter fuer die Module der Peregrine-Mod, Suche, Kategorien, Sortierung, Karten/Liste).
use std::time::Instant;

use eframe::egui::{self, pos2, vec2, Align2, Id, Rect, RichText, Sense, Stroke};

use super::stoop_ui::{diamond, spaced};
use super::{card, field, header, swatch, App, Page};
use crate::theme::{self, alpha, font, Pal, BAD, GOOD, WARN};
use crate::{client_mods, prism};

#[derive(Clone)]
pub struct ModEntry {
    pub id: Option<String>,
    pub name: String,
    pub desc: String,
    pub cat: String,
    pub enabled: bool,
}

#[derive(Default)]
pub struct PagesState {
    pub settings_cat: u8,
    pub mods_tab: u8,
    pub mods_search: String,
    pub mods_cat: String,
    pub mods_sort: String,
    pub mods_list_view: bool,
    pub mods_cache: Option<(Instant, Vec<ModEntry>, bool)>,
}

const CATEGORIES: [&str; 6] = ["General", "Graphics", "Account", "Launcher", "Microsoft", "Maintenance"];

/// Schalter im Peregrine-Stil (zum Zeichnen in eigene Flaechen).
pub(super) fn switch_at(p: &egui::Painter, r: Rect, on: f32, hover: bool, pal: &Pal) {
    let rr = r.height() / 2.0;
    p.rect_filled(r, rr, theme::lerp_color(alpha(pal.text, 14), pal.accent, on));
    p.rect_stroke(r, rr, Stroke::new(1.0_f32, if hover { alpha(pal.text, 120) } else { alpha(pal.text, (40.0 * (1.0 - on)) as u8) }));
    let x = egui::lerp(r.left() + rr..=r.right() - rr, on);
    if on > 0.5 {
        super::stoop_ui::glow_at(p, pos2(x, r.center().y), 14.0, 10.0, pal.accent, 40.0 * on);
    }
    p.circle_filled(pos2(x, r.center().y), rr - 3.0, if on > 0.5 { pal.bg } else { pal.muted });
}

fn shared_file() -> std::path::PathBuf {
    client_mods::shared_dir().join("peregrine-client.json")
}

/// Module aus der Datei der Mod (live, schaltbar) – oder die feste Liste, solange das Spiel noch nie lief.
fn load_modules() -> (Vec<ModEntry>, bool) {
    if let Ok(text) = std::fs::read_to_string(shared_file()) {
        if let Ok(v) = serde_json::from_str::<serde_json::Value>(&text) {
            if let Some(m) = v.get("modules").and_then(|m| m.as_object()) {
                let list: Vec<ModEntry> = m
                    .iter()
                    .filter_map(|(id, o)| {
                        Some(ModEntry {
                            id: Some(id.clone()),
                            name: o.get("name")?.as_str()?.to_string(),
                            desc: o.get("description").and_then(|d| d.as_str()).unwrap_or("").to_string(),
                            cat: o.get("category").and_then(|d| d.as_str()).unwrap_or("Misc").to_string(),
                            enabled: o.get("enabled").and_then(|d| d.as_bool()).unwrap_or(false),
                        })
                    })
                    .collect();
                if !list.is_empty() {
                    return (list, true);
                }
            }
        }
    }
    let list = client_mods::MODULES.iter().map(|(c, n, d)| ModEntry { id: None, name: n.to_string(), desc: d.to_string(), cat: c.to_string(), enabled: false }).collect();
    (list, false)
}

fn set_module(id: &str, on: bool) -> anyhow::Result<()> {
    let path = shared_file();
    let mut v: serde_json::Value = serde_json::from_str(&std::fs::read_to_string(&path)?)?;
    v["modules"][id]["enabled"] = serde_json::Value::Bool(on);
    std::fs::write(&path, serde_json::to_vec_pretty(&v)?)?;
    Ok(())
}

/// Gesamter Arbeitsspeicher des Rechners in MB (0 = unbekannt).
pub(super) fn total_ram_mb() -> u64 {
    #[cfg(windows)]
    unsafe {
        use windows_sys::Win32::System::SystemInformation::{GlobalMemoryStatusEx, MEMORYSTATUSEX};
        let mut m: MEMORYSTATUSEX = std::mem::zeroed();
        m.dwLength = std::mem::size_of::<MEMORYSTATUSEX>() as u32;
        if GlobalMemoryStatusEx(&mut m) != 0 {
            return m.ullTotalPhys / 1024 / 1024;
        }
        0
    }
    #[cfg(not(windows))]
    {
        std::fs::read_to_string("/proc/meminfo")
            .ok()
            .and_then(|t| t.lines().find(|l| l.starts_with("MemTotal:")).and_then(|l| l.split_whitespace().nth(1)).and_then(|k| k.parse::<u64>().ok()))
            .map(|kb| kb / 1024)
            .unwrap_or(0)
    }
}

impl App {
    // -------------------------------------------------------------- Einstellungen
    pub(super) fn settings_page(&mut self, ui: &mut egui::Ui, r: Rect, pal: &Pal) {
        let inner = r.shrink2(vec2(24.0, 18.0));
        let mut open_prism = false;
        ui.allocate_new_ui(egui::UiBuilder::new().max_rect(inner), |ui| {
            header(ui, pal, "Settings", "Saved the moment you change something");
        });
        // linke Kategorien-Spalte
        let top = inner.top() + 86.0;
        let col = Rect::from_min_max(pos2(inner.left(), top), pos2(inner.left() + 170.0, inner.bottom()));
        let p = ui.painter().clone();
        p.line_segment([pos2(col.right(), top), pos2(col.right(), inner.bottom())], Stroke::new(1.0_f32, alpha(pal.text, 16)));
        for (i, name) in CATEGORIES.iter().enumerate() {
            let y = top + 6.0 + i as f32 * 38.0;
            let row = Rect::from_min_size(pos2(col.left(), y), vec2(col.width() - 12.0, 32.0));
            let resp = ui.interact(row, Id::new(("setcat", i)), Sense::click());
            let hov = ui.ctx().animate_bool_with_time(Id::new(("setcath", i)), resp.hovered(), 0.15);
            let sel = self.pages.settings_cat as usize == i;
            if sel {
                p.rect_filled(row, 2.0, alpha(pal.text, 10));
                diamond(&p, pos2(row.left() + 10.0, row.center().y), 3.0, pal.accent, 0.0);
            }
            p.text(pos2(row.left() + 24.0 + hov * 3.0, row.center().y), Align2::LEFT_CENTER, *name, font(14.0, if sel { "semibold" } else { "regular" }), if sel { pal.text } else { theme::lerp_color(pal.muted, pal.text, hov) });
            if resp.clicked() {
                self.pages.settings_cat = i as u8;
            }
        }
        let content = Rect::from_min_max(pos2(col.right() + 28.0, top), inner.max);
        ui.allocate_new_ui(egui::UiBuilder::new().max_rect(content), |ui| {
            egui::ScrollArea::vertical().id_salt(("settings", self.pages.settings_cat)).auto_shrink([false; 2]).show(ui, |ui| match self.pages.settings_cat {
                0 => {
                    let c = &mut self.cfg;
                    card(ui, pal, "Game folder", |ui| {
                        ui.horizontal(|ui| {
                            field(ui, pal, "Where worlds, mods and screenshots live", &mut c.game_dir, "");
                            ui.vertical(|ui| {
                                ui.add_space(18.0);
                                if ui.button("Open").clicked() {
                                    let _ = std::fs::create_dir_all(&c.game_dir);
                                    let _ = open::that(&c.game_dir);
                                }
                            });
                        });
                    });
                    card(ui, pal, "Behaviour", |ui| {
                        ui.horizontal(|ui| {
                            theme::toggle(ui, pal, &mut c.close_on_launch);
                            ui.label("Close the launcher when the game starts");
                        });
                        ui.horizontal(|ui| {
                            theme::toggle(ui, pal, &mut c.show_snapshots);
                            ui.label("Show snapshots in the version list");
                        });
                    });
                }
                1 => self.graphics_settings(ui, pal),
                2 => {
                    let (prism_found, prism_acc) = {
                        let s = self.h.lock();
                        (s.prism.clone(), s.prism_account.clone())
                    };
                    card(ui, pal, "Sign-in", |ui| {
                        ui.horizontal(|ui| {
                            theme::toggle(ui, pal, &mut self.cfg.use_prism);
                            ui.label("Easy sign-in (recommended)");
                        });
                        let (txt, col) = match (&prism_found, &prism_acc) {
                            (None, _) if prism::can_install() => ("Not signed in yet".to_string(), WARN),
                            (None, _) => ("Extra component missing".to_string(), BAD),
                            (Some(_), None) => ("Not signed in yet".to_string(), WARN),
                            (Some(_), Some(n)) => (format!("Signed in as {n}"), GOOD),
                        };
                        ui.label(RichText::new(txt).color(col).font(font(14.0, "medium")));
                        if ui.button(if prism_acc.is_some() { "Switch account" } else { "Sign in now" }).clicked() {
                            open_prism = true;
                        }
                    });
                }
                3 => self.launcher_settings(ui, pal),
                4 => {
                    let c = &mut self.cfg;
                    card(ui, pal, "Own Azure app (optional)", |ui| {
                        ui.label(RichText::new("Only needed for direct sign-in without the easy sign-in component. Guide in README.md.").color(pal.muted).size(12.5));
                        field(ui, pal, "Azure client ID", &mut c.client_id, "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx");
                        field(ui, pal, "Redirect URI", &mut c.redirect_uri, "");
                    });
                }
                _ => {
                    let repair = &mut self.repair;
                    card(ui, pal, "Repair", |ui| {
                        ui.horizontal(|ui| {
                            theme::toggle(ui, pal, repair);
                            ui.label("Verify and repair all game files on the next launch");
                        });
                    });
                    card(ui, pal, "Folders & logs", |ui| {
                        ui.horizontal(|ui| {
                            if ui.button("Launcher folder").clicked() {
                                let _ = open::that(crate::config::base_dir());
                            }
                            if ui.button("Launcher log").clicked() {
                                let _ = open::that(crate::config::base_dir().join("launcher.log"));
                            }
                            if ui.button("Console").clicked() {
                                self.page = Page::Console;
                            }
                        });
                        ui.label(RichText::new(format!("Peregrine {}", crate::config::LAUNCHER_VERSION)).color(pal.muted).size(12.0));
                    });
                }
            });
        });
        self.cfg.client_id = self.cfg.client_id.trim().to_string();
        if open_prism {
            self.open_prism();
        }
    }

    fn graphics_settings(&mut self, ui: &mut egui::Ui, pal: &Pal) {
        let o = &mut self.cfg.client.options;
        let mut manage = !o.is_empty();
        card(ui, pal, "Graphics", |ui| {
            ui.horizontal(|ui| {
                theme::toggle(ui, pal, &mut manage);
                ui.label("Set the game's graphics from Peregrine");
            });
            ui.label(RichText::new("Written into Minecraft's own settings before every launch – for every profile. Off = Minecraft keeps what you set in game.").color(pal.muted).size(12.5));
        });
        if !manage {
            o.clear();
            return;
        }
        let defaults = [("renderDistance", "12"), ("simulationDistance", "10"), ("maxFps", "120"), ("enableVsync", "false"), ("fullscreen", "false"), ("guiScale", "0"), ("entityShadows", "true"), ("renderClouds", "\"fast\"")];
        for (k, v) in defaults {
            o.entry(k.to_string()).or_insert_with(|| v.to_string());
        }
        let num = |o: &std::collections::BTreeMap<String, String>, k: &str| o.get(k).and_then(|v| v.parse::<i32>().ok()).unwrap_or(0);
        card(ui, pal, "View", |ui| {
            let mut rd = num(o, "renderDistance");
            ui.horizontal(|ui| {
                ui.label(RichText::new("Render distance").color(pal.muted));
                ui.add(egui::Slider::new(&mut rd, 2..=32).suffix(" chunks"));
            });
            o.insert("renderDistance".into(), rd.to_string());
            let mut sd = num(o, "simulationDistance");
            ui.horizontal(|ui| {
                ui.label(RichText::new("Simulation distance").color(pal.muted));
                ui.add(egui::Slider::new(&mut sd, 5..=32).suffix(" chunks"));
            });
            o.insert("simulationDistance".into(), sd.to_string());
            let mut gs = num(o, "guiScale");
            ui.horizontal(|ui| {
                ui.label(RichText::new("Interface size").color(pal.muted));
                egui::ComboBox::from_id_salt("guiscale").selected_text(if gs == 0 { "Auto".to_string() } else { format!("{gs}×") }).show_ui(ui, |ui| {
                    for v in 0..=4 {
                        ui.selectable_value(&mut gs, v, if v == 0 { "Auto".to_string() } else { format!("{v}×") });
                    }
                });
            });
            o.insert("guiScale".into(), gs.to_string());
            let mut clouds = o.get("renderClouds").cloned().unwrap_or_default();
            ui.horizontal(|ui| {
                ui.label(RichText::new("Clouds").color(pal.muted));
                let name = |v: &str| match v {
                    "\"true\"" => "Fancy",
                    "\"false\"" => "Off",
                    _ => "Fast",
                };
                egui::ComboBox::from_id_salt("clouds").selected_text(name(&clouds)).show_ui(ui, |ui| {
                    for v in ["\"true\"", "\"fast\"", "\"false\""] {
                        ui.selectable_value(&mut clouds, v.to_string(), name(v));
                    }
                });
            });
            o.insert("renderClouds".into(), clouds);
        });
        card(ui, pal, "Frame rate", |ui| {
            let mut fps = num(o, "maxFps");
            ui.horizontal(|ui| {
                ui.label(RichText::new("FPS limit").color(pal.muted));
                ui.add(egui::Slider::new(&mut fps, 30..=260).step_by(10.0).custom_formatter(|v, _| if v >= 260.0 { "Unlimited".into() } else { format!("{v:.0} FPS") }));
            });
            o.insert("maxFps".into(), fps.to_string());
            for (k, label) in [("enableVsync", "VSync – no tearing, a little more input delay"), ("fullscreen", "Start in fullscreen"), ("entityShadows", "Entity shadows")] {
                let mut b = o.get(k).map(|v| v == "true").unwrap_or(false);
                ui.horizontal(|ui| {
                    theme::toggle(ui, pal, &mut b);
                    ui.label(label);
                });
                o.insert(k.into(), b.to_string());
            }
        });
    }

    fn launcher_settings(&mut self, ui: &mut egui::Ui, pal: &Pal) {
        let mut go_look = false;
        let l = &mut self.cfg.look;
        card(ui, pal, "Style", |ui| {
            ui.horizontal(|ui| {
                ui.label(RichText::new("Theme").color(pal.muted));
                let mut preset = l.preset.clone();
                egui::ComboBox::from_id_salt("preset").selected_text(&preset).show_ui(ui, |ui| {
                    for pr in theme::PRESETS {
                        ui.selectable_value(&mut preset, pr.to_string(), pr);
                    }
                });
                if preset != l.preset {
                    l.apply_preset(&preset);
                }
                ui.add_space(20.0);
                ui.label(RichText::new("Background").color(pal.muted));
                egui::ComboBox::from_id_salt("bgsel").selected_text(&l.background).show_ui(ui, |ui| {
                    for b in theme::BACKGROUNDS {
                        ui.selectable_value(&mut l.background, b.to_string(), b);
                    }
                });
            });
            ui.horizontal(|ui| {
                ui.label(RichText::new("Accent").color(pal.muted));
                for (_, rgb) in theme::ACCENTS {
                    if swatch(ui, pal, rgb, l.accent == rgb).clicked() {
                        l.accent = rgb;
                    }
                }
            });
        });
        card(ui, pal, "Motion & size", |ui| {
            ui.horizontal(|ui| {
                theme::toggle(ui, pal, &mut l.animations);
                ui.label("Animations (background, figure, transitions)");
            });
            ui.horizontal(|ui| {
                ui.label(RichText::new("Interface size").color(pal.muted));
                ui.add(egui::Slider::new(&mut l.ui_scale, 0.8..=1.4).step_by(0.05).custom_formatter(|v, _| format!("{:.0} %", v * 100.0)));
            });
        });
        card(ui, pal, "Everything else", |ui| {
            if ui.button("Open the appearance studio").clicked() {
                go_look = true;
            }
        });
        if go_look {
            self.page = Page::Look;
        }
    }

    // -------------------------------------------------------------- Mods
    pub(super) fn mods_page(&mut self, ui: &mut egui::Ui, r: Rect, pal: &Pal) {
        let inner = r.shrink2(vec2(24.0, 18.0));
        ui.allocate_new_ui(egui::UiBuilder::new().max_rect(inner), |ui| {
            header(ui, pal, "Mods", "Peregrine's own modules – plus speed mods, capes and keys");
        });
        // Reiter mit gleitendem Strich
        let ty = inner.top() + 82.0;
        let p = ui.painter().clone();
        let mut x = inner.left();
        let mut sel = (x, 10.0);
        for (i, label) in ["LIBRARY", "SETUP"].iter().enumerate() {
            let g = p.layout_no_wrap(label.to_string(), font(11.0, "monom"), pal.text);
            let w = g.size().x + 2.6 * (label.len() as f32 - 1.0);
            let rr = Rect::from_min_size(pos2(x, ty - 12.0), vec2(w, 24.0));
            let resp = ui.interact(rr, Id::new(("modtab", i)), Sense::click());
            let on = self.pages.mods_tab as usize == i;
            spaced(&p, pos2(x, ty), Align2::LEFT_CENTER, label, font(11.0, "monom"), if on || resp.hovered() { pal.text } else { pal.muted }, 2.6);
            if on {
                sel = (x, w);
            }
            if resp.clicked() {
                self.pages.mods_tab = i as u8;
            }
            x += w + 32.0;
        }
        let ax = ui.ctx().animate_value_with_time(Id::new("modtabx"), sel.0, 0.25);
        let aw = ui.ctx().animate_value_with_time(Id::new("modtabw"), sel.1, 0.25);
        p.line_segment([pos2(ax, ty + 13.0), pos2(ax + aw, ty + 13.0)], Stroke::new(2.0_f32, pal.accent));
        p.line_segment([pos2(inner.left(), ty + 14.0), pos2(inner.right(), ty + 14.0)], Stroke::new(1.0_f32, alpha(pal.text, 16)));
        let below = Rect::from_min_max(pos2(r.left(), ty + 8.0), r.max);
        if self.pages.mods_tab == 1 {
            self.client_page(ui, below, pal, false);
            return;
        }
        // ---- Bibliothek
        let fresh = self.pages.mods_cache.as_ref().is_some_and(|c| c.0.elapsed().as_secs() < 3);
        if !fresh {
            let (list, live) = load_modules();
            self.pages.mods_cache = Some((Instant::now(), list, live));
        }
        let (_, list, live) = self.pages.mods_cache.clone().unwrap();
        let running = prism::game_running() || self.h.lock().status.to_lowercase().contains("running");
        let area = Rect::from_min_max(pos2(inner.left(), ty + 30.0), inner.max);
        let mut toggled: Option<(String, bool)> = None;
        ui.allocate_new_ui(egui::UiBuilder::new().max_rect(area), |ui| {
            ui.horizontal(|ui| {
                ui.add(egui::TextEdit::singleline(&mut self.pages.mods_search).hint_text("search modules").desired_width(200.0).margin(vec2(10.0, 6.0)));
                ui.add_space(8.0);
                let mut cats: Vec<String> = vec!["All".into()];
                for m in &list {
                    if !cats.contains(&m.cat) {
                        cats.push(m.cat.clone());
                    }
                }
                if self.pages.mods_cat.is_empty() {
                    self.pages.mods_cat = "All".into();
                }
                for c in &cats {
                    let mut on = &self.pages.mods_cat == c;
                    super::chip(ui, pal, c, &mut on);
                    if on && &self.pages.mods_cat != c {
                        self.pages.mods_cat = c.clone();
                    }
                }
            });
            ui.add_space(4.0);
            ui.horizontal(|ui| {
                if self.pages.mods_sort.is_empty() {
                    self.pages.mods_sort = "Name".into();
                }
                ui.label(RichText::new("SORT").font(font(10.0, "mono")).color(pal.muted));
                egui::ComboBox::from_id_salt("modsort").selected_text(&self.pages.mods_sort).show_ui(ui, |ui| {
                    for s in ["Name", "Category", "On first"] {
                        ui.selectable_value(&mut self.pages.mods_sort, s.to_string(), s);
                    }
                });
                ui.add_space(12.0);
                let mut cards = !self.pages.mods_list_view;
                super::chip(ui, pal, "Cards", &mut cards);
                let mut listv = self.pages.mods_list_view;
                super::chip(ui, pal, "List", &mut listv);
                if listv != self.pages.mods_list_view {
                    self.pages.mods_list_view = listv;
                } else if cards == self.pages.mods_list_view {
                    self.pages.mods_list_view = !cards;
                }
                let on = list.iter().filter(|m| m.enabled).count();
                ui.add_space(12.0);
                let note = if !live {
                    "Start the game once with Peregrine – then the switches work here".to_string()
                } else if running {
                    "Close Minecraft to switch modules here (in game: Right Shift)".to_string()
                } else {
                    format!("{on} of {} on", list.len())
                };
                ui.label(RichText::new(note.to_uppercase()).font(font(10.0, "mono")).color(if live && !running { pal.muted } else { WARN }));
            });
            ui.add_space(10.0);
            let q = self.pages.mods_search.trim().to_lowercase();
            let mut shown: Vec<ModEntry> = list
                .iter()
                .filter(|m| self.pages.mods_cat == "All" || m.cat == self.pages.mods_cat)
                .filter(|m| q.is_empty() || m.name.to_lowercase().contains(&q) || m.desc.to_lowercase().contains(&q))
                .cloned()
                .collect();
            match self.pages.mods_sort.as_str() {
                "Category" => shown.sort_by(|a, b| a.cat.cmp(&b.cat).then(a.name.cmp(&b.name))),
                "On first" => shown.sort_by(|a, b| b.enabled.cmp(&a.enabled).then(a.name.cmp(&b.name))),
                _ => shown.sort_by(|a, b| a.name.to_lowercase().cmp(&b.name.to_lowercase())),
            }
            let can_switch = live && !running;
            egui::ScrollArea::vertical().auto_shrink([false; 2]).show(ui, |ui| {
                let w = ui.available_width();
                if self.pages.mods_list_view {
                    for m in &shown {
                        let (rr, resp) = ui.allocate_exact_size(vec2(w, 46.0), if can_switch { Sense::click() } else { Sense::hover() });
                        let hov = ui.ctx().animate_bool_with_time(Id::new(("mh", &m.name)), resp.hovered(), 0.15);
                        let onv = ui.ctx().animate_bool_with_time(Id::new(("mo", &m.name)), m.enabled, 0.18);
                        let p = ui.painter();
                        p.line_segment([rr.left_top(), rr.right_top()], Stroke::new(1.0_f32, alpha(pal.text, 14)));
                        if hov > 0.0 {
                            p.rect_filled(rr.shrink(1.0), 2.0, alpha(pal.text, (hov * 8.0) as u8));
                        }
                        diamond(p, pos2(rr.left() + 10.0, rr.center().y), 2.8, if m.enabled { pal.accent } else { alpha(pal.muted, 120) }, 0.0);
                        p.text(pos2(rr.left() + 26.0 + hov * 3.0, rr.center().y - 7.0), Align2::LEFT_CENTER, &m.name, font(14.0, "medium"), pal.text);
                        p.text(pos2(rr.left() + 26.0 + hov * 3.0, rr.center().y + 9.0), Align2::LEFT_CENTER, &m.desc, font(11.5, "regular"), pal.muted);
                        p.text(pos2(rr.right() - 70.0, rr.center().y), Align2::RIGHT_CENTER, m.cat.to_uppercase(), font(9.0, "mono"), pal.muted);
                        switch_at(p, Rect::from_center_size(pos2(rr.right() - 30.0, rr.center().y), vec2(34.0, 18.0)), onv, resp.hovered() && can_switch, pal);
                        if resp.clicked() {
                            if let Some(id) = &m.id {
                                toggled = Some((id.clone(), !m.enabled));
                            }
                        }
                    }
                } else {
                    let cols = ((w / 260.0) as usize).max(1);
                    let cw = w / cols as f32;
                    for chunk in shown.chunks(cols) {
                        ui.horizontal(|ui| {
                            ui.spacing_mut().item_spacing.x = 0.0;
                            for m in chunk {
                                let (rr, resp) = ui.allocate_exact_size(vec2(cw, 104.0), if can_switch { Sense::click() } else { Sense::hover() });
                                let tile = rr.shrink2(vec2(5.0, 5.0));
                                let hov = ui.ctx().animate_bool_with_time(Id::new(("mh", &m.name)), resp.hovered(), 0.15);
                                let onv = ui.ctx().animate_bool_with_time(Id::new(("mo", &m.name)), m.enabled, 0.18);
                                let p = ui.painter();
                                p.rect_filled(tile.translate(vec2(0.0, -hov * 2.0)), 3.0, theme::lerp_color(pal.surface, pal.surface2, hov));
                                let t2 = tile.translate(vec2(0.0, -hov * 2.0));
                                p.rect_stroke(t2, 3.0, Stroke::new(1.0_f32, if hov > 0.01 { alpha(pal.accent, (hov * 110.0) as u8) } else { alpha(pal.text, 12) }));
                                if m.enabled {
                                    p.line_segment([pos2(t2.left(), t2.top() + 14.0), pos2(t2.left(), t2.bottom() - 14.0)], Stroke::new(2.0_f32, pal.accent));
                                }
                                spaced(p, pos2(t2.left() + 16.0, t2.top() + 18.0), Align2::LEFT_CENTER, &m.cat.to_uppercase(), font(8.5, "monom"), if m.enabled { pal.accent } else { pal.muted }, 2.0);
                                p.text(pos2(t2.left() + 16.0, t2.top() + 40.0), Align2::LEFT_CENTER, &m.name, font(15.5, "semibold"), pal.text);
                                let galley = p.layout(m.desc.clone(), font(11.5, "regular"), pal.muted, t2.width() - 32.0);
                                p.with_clip_rect(Rect::from_min_max(pos2(t2.left(), t2.top() + 54.0), pos2(t2.right(), t2.bottom() - 8.0))).galley(pos2(t2.left() + 16.0, t2.top() + 56.0), galley, pal.muted);
                                switch_at(p, Rect::from_min_size(pos2(t2.right() - 50.0, t2.top() + 10.0), vec2(34.0, 18.0)), onv, resp.hovered() && can_switch, pal);
                                if resp.clicked() {
                                    if let Some(id) = &m.id {
                                        toggled = Some((id.clone(), !m.enabled));
                                    }
                                }
                            }
                        });
                    }
                }
            });
        });
        if let Some((id, on)) = toggled {
            match set_module(&id, on) {
                Ok(()) => self.pages.mods_cache = None,
                Err(e) => self.h.log(format!("[Mods] Could not save the module switch: {e:#}")),
            }
        }
    }
}
