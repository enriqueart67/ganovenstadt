//! Pages "Servers" (ping + join) and "Screenshots" (gallery of every profile's screenshots).
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::{Instant, SystemTime};

use eframe::egui::{self, vec2, Color32, Rect, RichText, Sense, Stroke};

use super::{card, header, App};
use crate::servers::{self, ServerEntry, Status};
use crate::theme::{font, Pal, BAD, GOOD};

#[derive(Clone)]
enum Ping {
    Pending,
    Ok(Status),
    Offline,
}

struct Shot {
    path: PathBuf,
    modified: SystemTime,
    profile: String,
}

#[derive(Default)]
pub struct ExtraUi {
    /// Server to join with the next launch (taken by `App::launch`).
    pub join: Option<String>,
    name: String,
    address: String,
    pings: Arc<Mutex<HashMap<String, Ping>>>,
    pinged_at: Option<Instant>,
    // screenshots
    shots: Vec<Shot>,
    shots_scanned: Option<Instant>,
    thumbs: HashMap<PathBuf, Option<egui::TextureHandle>>,
    decoded: Arc<Mutex<Vec<(PathBuf, Option<egui::ColorImage>)>>>,
    confirm_delete: Option<PathBuf>,
}

fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

fn link(ui: &mut egui::Ui, pal: &Pal, label: &str) -> egui::Response {
    let g = ui.fonts(|f| f.layout_no_wrap(label.to_uppercase(), font(10.5, "monom"), pal.muted));
    let (r, resp) = ui.allocate_exact_size(g.size() + vec2(4.0, 8.0), Sense::click());
    let col = if resp.hovered() { pal.accent } else { pal.muted };
    ui.painter().text(r.left_center(), egui::Align2::LEFT_CENTER, label.to_uppercase(), font(10.5, "monom"), col);
    resp
}

fn thousands(n: u64) -> String {
    let s = n.to_string();
    let mut out = String::new();
    for (i, c) in s.chars().enumerate() {
        if i > 0 && (s.len() - i) % 3 == 0 {
            out.push(',');
        }
        out.push(c);
    }
    out
}

impl App {
    // ------------------------------------------------------------------ servers
    fn ping_all(&mut self) {
        self.extra.pinged_at = Some(Instant::now());
        for s in &self.cfg.servers {
            let addr = s.address.clone();
            lock(&self.extra.pings).insert(addr.clone(), Ping::Pending);
            let (pings, h) = (self.extra.pings.clone(), self.h.clone());
            self.rt.spawn_blocking(move || {
                let res = match servers::ping(&addr) {
                    Ok(st) => Ping::Ok(st),
                    Err(_) => Ping::Offline,
                };
                lock(&pings).insert(addr, res);
                h.repaint();
            });
        }
    }

    pub(super) fn servers_page(&mut self, ui: &mut egui::Ui, r: Rect, pal: &Pal) {
        // refresh when the page is opened and once a minute while it stays open
        if self.extra.pinged_at.is_none_or(|t| t.elapsed().as_secs() > 60) {
            self.ping_all();
        }
        let joins_with = match self.active_profile() {
            Some(p) => format!("{} ({} {})", p.name, p.loader, p.version),
            None => format!("{} {}", self.cfg.loader, self.cfg.version),
        };
        let busy = self.h.lock().busy;
        let (mut join, mut remove, mut refresh, mut add) = (None, None, false, false);
        ui.allocate_new_ui(egui::UiBuilder::new().max_rect(r.shrink2(vec2(24.0, 18.0))), |ui| {
            header(ui, pal, "Servers", "See who is online and join with one click");
            egui::ScrollArea::vertical().auto_shrink([false; 2]).show(ui, |ui| {
                card(ui, pal, "Your servers", |ui| {
                    ui.horizontal(|ui| {
                        ui.label(RichText::new(format!("Joins with {joins_with} – change it on the Home page.")).color(pal.muted).size(12.5));
                        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                            if link(ui, pal, "Refresh").clicked() {
                                refresh = true;
                            }
                        });
                    });
                    if self.cfg.servers.is_empty() {
                        ui.add_space(6.0);
                        ui.label(RichText::new("No servers yet. Add one below – for example a friend's server or your favourite network.").color(pal.muted));
                    }
                    let pings = lock(&self.extra.pings).clone();
                    for (i, s) in self.cfg.servers.iter().enumerate() {
                        let (row, _) = ui.allocate_exact_size(vec2(ui.available_width(), 58.0), Sense::hover());
                        let p = ui.painter();
                        p.line_segment([row.left_bottom(), row.right_bottom()], Stroke::new(1.0_f32, pal.border));
                        let (dot, line, sub) = match pings.get(&s.address) {
                            Some(Ping::Ok(st)) => (
                                GOOD,
                                format!("{} / {} online   ·   {} ms", thousands(st.online), thousands(st.max), st.ms),
                                if st.motd.is_empty() { st.version.clone() } else { format!("{}   ·   {}", st.version, st.motd) },
                            ),
                            Some(Ping::Offline) => (BAD, "Offline or unreachable".to_string(), String::new()),
                            _ => (pal.muted, "Pinging …".to_string(), String::new()),
                        };
                        p.circle_filled(row.left_center() + vec2(5.0, -9.0), 3.5, dot);
                        p.text(row.left_center() + vec2(18.0, -9.0), egui::Align2::LEFT_CENTER, &s.name, font(17.0, "semibold"), pal.text);
                        p.text(row.left_center() + vec2(18.0, 11.0), egui::Align2::LEFT_CENTER, s.address.to_uppercase(), font(10.5, "mono"), pal.muted);
                        let right = row.right() - 190.0;
                        p.text(egui::pos2(right, row.center().y - 9.0), egui::Align2::RIGHT_CENTER, line, font(14.0, "medium"), pal.text);
                        let sub: String = sub.chars().take(70).collect();
                        p.text(egui::pos2(right, row.center().y + 11.0), egui::Align2::RIGHT_CENTER, sub, font(11.5, "regular"), pal.muted);
                        let btns = Rect::from_min_max(egui::pos2(row.right() - 176.0, row.top()), row.right_bottom());
                        ui.allocate_new_ui(egui::UiBuilder::new().max_rect(btns).layout(egui::Layout::right_to_left(egui::Align::Center)), |ui| {
                            if link(ui, pal, "Remove").clicked() {
                                remove = Some(i);
                            }
                            ui.add_space(10.0);
                            ui.add_enabled_ui(!busy, |ui| {
                                if super::profiles_ui::accent_button(ui, pal, "Join").clicked() {
                                    join = Some(s.address.clone());
                                }
                            });
                        });
                    }
                });
                card(ui, pal, "Add a server", |ui| {
                    ui.horizontal(|ui| {
                        ui.vertical(|ui| {
                            ui.label(RichText::new("NAME").font(font(10.5, "mono")).color(pal.muted));
                            ui.add(egui::TextEdit::singleline(&mut self.extra.name).hint_text("e.g. Friends SMP").desired_width(220.0).margin(vec2(10.0, 7.0)));
                        });
                        ui.vertical(|ui| {
                            ui.label(RichText::new("ADDRESS").font(font(10.5, "mono")).color(pal.muted));
                            ui.add(egui::TextEdit::singleline(&mut self.extra.address).hint_text("play.example.net or 1.2.3.4:25565").desired_width(300.0).margin(vec2(10.0, 7.0)));
                        });
                        ui.vertical(|ui| {
                            ui.add_space(17.0);
                            if super::profiles_ui::accent_button(ui, pal, "Add server").clicked() {
                                add = true;
                            }
                        });
                    });
                });
            });
        });
        if add && !self.extra.address.trim().is_empty() {
            let address = self.extra.address.trim().to_string();
            let name = if self.extra.name.trim().is_empty() { address.clone() } else { self.extra.name.trim().to_string() };
            if !self.cfg.servers.iter().any(|s| s.address.eq_ignore_ascii_case(&address)) {
                self.cfg.servers.push(ServerEntry { name, address });
            }
            self.extra.name.clear();
            self.extra.address.clear();
            refresh = true;
        }
        if let Some(i) = remove {
            self.cfg.servers.remove(i);
        }
        if refresh {
            self.ping_all();
        }
        if let Some(addr) = join {
            self.h.log(format!("[Launcher] Joining {addr} after launch"));
            self.extra.join = Some(addr);
            self.play();
        }
    }

    // ------------------------------------------------------------------ screenshots
    fn scan_shots(&mut self) {
        self.extra.shots_scanned = Some(Instant::now());
        let gd = PathBuf::from(&self.cfg.game_dir);
        let mut dirs = vec![(gd.join("screenshots"), "Default".to_string())];
        for p in &self.prof.list {
            dirs.push((crate::profiles::dir(&self.cfg.game_dir, p).join("screenshots"), p.name.clone()));
        }
        let mut shots = Vec::new();
        for (dir, profile) in dirs {
            for e in std::fs::read_dir(&dir).into_iter().flatten().flatten() {
                let path = e.path();
                let is_png = path.extension().is_some_and(|x| x.eq_ignore_ascii_case("png"));
                if let (true, Ok(meta)) = (is_png, e.metadata()) {
                    shots.push(Shot { path, modified: meta.modified().unwrap_or(SystemTime::UNIX_EPOCH), profile: profile.clone() });
                }
            }
        }
        shots.sort_by(|a, b| b.modified.cmp(&a.modified));
        shots.truncate(60);
        // decode thumbnails off the UI thread, newest first
        let todo: Vec<PathBuf> = shots.iter().map(|s| s.path.clone()).filter(|p| !self.extra.thumbs.contains_key(p)).collect();
        for p in &todo {
            self.extra.thumbs.insert(p.clone(), None);
        }
        self.extra.shots = shots;
        if !todo.is_empty() {
            let (out, h) = (self.extra.decoded.clone(), self.h.clone());
            std::thread::spawn(move || {
                for path in todo {
                    let img = image::open(&path).ok().map(|i| {
                        let t = i.thumbnail(480, 270).to_rgba8();
                        egui::ColorImage::from_rgba_unmultiplied([t.width() as usize, t.height() as usize], t.as_raw())
                    });
                    lock(&out).push((path, img));
                    h.repaint();
                }
            });
        }
    }

    pub(super) fn shots_page(&mut self, ui: &mut egui::Ui, r: Rect, pal: &Pal) {
        if self.extra.shots_scanned.is_none_or(|t| t.elapsed().as_secs() > 5) {
            self.scan_shots();
        }
        for (path, img) in lock(&self.extra.decoded).drain(..).collect::<Vec<_>>() {
            let tex = img.map(|i| ui.ctx().load_texture(path.to_string_lossy(), i, egui::TextureOptions::LINEAR));
            self.extra.thumbs.insert(path, tex);
        }
        let mut delete = None;
        ui.allocate_new_ui(egui::UiBuilder::new().max_rect(r.shrink2(vec2(24.0, 18.0))), |ui| {
            header(ui, pal, "Screenshots", "Press F2 in game – every profile's screenshots show up here");
            egui::ScrollArea::vertical().auto_shrink([false; 2]).show(ui, |ui| {
                if self.extra.shots.is_empty() {
                    card(ui, pal, "Nothing here yet", |ui| {
                        ui.label(RichText::new("Take a screenshot in game with F2 and it appears on this page.").color(pal.muted));
                    });
                    return;
                }
                let gap = 14.0;
                let cols = ((ui.available_width() + gap) / (230.0 + gap)).floor().max(1.0) as usize;
                let w = (ui.available_width() - gap * (cols as f32 - 1.0)) / cols as f32;
                let h = w * 9.0 / 16.0;
                for row in self.extra.shots.chunks(cols) {
                    ui.horizontal(|ui| {
                        ui.spacing_mut().item_spacing.x = gap;
                        for s in row {
                            ui.vertical(|ui| {
                                ui.set_width(w);
                                let (rect, resp) = ui.allocate_exact_size(vec2(w, h), Sense::click());
                                let p = ui.painter();
                                p.rect_filled(rect, 0.0, pal.surface2);
                                if let Some(Some(tex)) = self.extra.thumbs.get(&s.path) {
                                    // cover: crop the texture to 16:9
                                    let ts = tex.size_vec2();
                                    let (tw, th) = (ts.x, ts.y);
                                    let want = 16.0 / 9.0;
                                    let uv = if tw / th > want {
                                        let k = (th * want) / tw;
                                        Rect::from_min_max(egui::pos2((1.0 - k) / 2.0, 0.0), egui::pos2((1.0 + k) / 2.0, 1.0))
                                    } else {
                                        let k = (tw / want) / th;
                                        Rect::from_min_max(egui::pos2(0.0, (1.0 - k) / 2.0), egui::pos2(1.0, (1.0 + k) / 2.0))
                                    };
                                    p.image(tex.id(), rect, uv, Color32::WHITE);
                                }
                                p.rect_stroke(rect, 0.0, Stroke::new(1.0_f32, if resp.hovered() { pal.accent } else { pal.border }));
                                if resp.clicked() {
                                    let _ = open::that(&s.path);
                                }
                                resp.on_hover_text("Open");
                                ui.horizontal(|ui| {
                                    let name = s.path.file_stem().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
                                    ui.label(RichText::new(format!("{}  ·  {}", name.replace('_', " "), s.profile).to_uppercase()).font(font(9.5, "mono")).color(pal.muted));
                                    ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                                        let confirming = self.extra.confirm_delete.as_ref() == Some(&s.path);
                                        if link(ui, pal, if confirming { "Really delete?" } else { "Delete" }).clicked() {
                                            if confirming {
                                                delete = Some(s.path.clone());
                                            } else {
                                                self.extra.confirm_delete = Some(s.path.clone());
                                            }
                                        }
                                    });
                                });
                            });
                        }
                    });
                    ui.add_space(gap);
                }
            });
        });
        if let Some(p) = delete {
            let _ = std::fs::remove_file(&p);
            self.extra.thumbs.remove(&p);
            self.extra.confirm_delete = None;
            self.extra.shots_scanned = None;
        }
    }
}
