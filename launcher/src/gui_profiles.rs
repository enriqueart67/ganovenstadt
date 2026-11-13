//! Seite „Profile“: Profile anlegen/bearbeiten, Mods verwalten und ueber Modrinth/CurseForge installieren.
use std::collections::{HashMap, HashSet};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use eframe::egui::{self, pos2, vec2, Align2, Color32, Id, Rect, RichText, Sense, Stroke};

use super::{card, field, header, segmented, App, Page};
use crate::install;
use crate::mods::{self, Hit, InstalledMod, Source};
use crate::profiles::{self, Profile};
use crate::theme::{self, alpha, font, Pal, BAD, WARN};

const MODRINTH_GREEN: Color32 = Color32::from_rgb(27, 217, 106);
const CURSEFORGE_ORANGE: Color32 = Color32::from_rgb(241, 100, 54);

/// Zustand, den Hintergrund-Aufgaben (Suche, Installation, Bilder) fuellen.
#[derive(Default)]
pub struct ModsShared {
    pub results: Vec<Hit>,
    pub searching: bool,
    pub error: Option<String>,
    pub seq: u64,
    pub installing: HashSet<String>,
    pub finished: Vec<(String, Vec<InstalledMod>)>,
}

pub enum Img {
    Loading,
    Ready(egui::ColorImage),
    Failed,
}

/// Formular fuer „Neues Profil“ bzw. „Bearbeiten“.
pub struct ProfileForm {
    pub editing: Option<String>,
    pub name: String,
    pub version: String,
    pub loader: String,
}

pub struct ProfilesUi {
    pub list: Vec<Profile>,
    pub selected: Option<String>,
    pub tab: String,
    pub source: Source,
    pub query: String,
    pub query_changed: Option<Instant>,
    pub searched_key: String,
    pub form: Option<ProfileForm>,
    pub confirm_delete: bool,
    pub shared: Arc<Mutex<ModsShared>>,
    pub images: Arc<Mutex<HashMap<String, Img>>>,
    pub textures: HashMap<String, egui::TextureHandle>,
}

impl ProfilesUi {
    pub fn new() -> Self {
        Self {
            list: profiles::load(),
            selected: None,
            tab: "Installiert".into(),
            source: Source::Modrinth,
            query: String::new(),
            query_changed: None,
            searched_key: String::new(),
            form: None,
            confirm_delete: false,
            shared: Default::default(),
            images: Default::default(),
            textures: HashMap::new(),
        }
    }
    pub fn busy(&self) -> bool {
        let s = self.shared.lock().unwrap();
        s.searching || !s.installing.is_empty()
    }
}

fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

impl App {
    /// Fertige Installationen uebernehmen (wird jedes Frame aufgerufen).
    pub(super) fn collect_installs(&mut self) {
        let done: Vec<_> = lock(&self.prof.shared).finished.drain(..).collect();
        if done.is_empty() {
            return;
        }
        for (pid, new) in done {
            if let Some(p) = self.prof.list.iter_mut().find(|p| p.id == pid) {
                for m in new {
                    p.mods.retain(|x| !(x.source == m.source && x.project_id == m.project_id));
                    p.mods.push(m);
                }
            }
        }
        profiles::save(&self.prof.list);
    }

    fn icon_tex(&mut self, url: &str) -> Option<egui::TextureHandle> {
        if let Some(t) = self.prof.textures.get(url) {
            return Some(t.clone());
        }
        let mut imgs = lock(&self.prof.images);
        match imgs.get(url) {
            Some(Img::Ready(_)) => {
                let Some(Img::Ready(ci)) = imgs.remove(url) else { return None };
                let t = self.h_ctx().load_texture(url, ci, egui::TextureOptions::LINEAR);
                self.prof.textures.insert(url.to_string(), t.clone());
                Some(t)
            }
            Some(_) => None,
            None => {
                imgs.insert(url.to_string(), Img::Loading);
                let (images, client, h, url) = (self.prof.images.clone(), self.client.clone(), self.h.clone(), url.to_string());
                self.rt.spawn(async move {
                    let img = async {
                        let bytes = client.get(&url).send().await?.error_for_status()?.bytes().await?;
                        let im = image::load_from_memory(&bytes)?.thumbnail(64, 64).to_rgba8();
                        anyhow::Ok(egui::ColorImage::from_rgba_unmultiplied([im.width() as usize, im.height() as usize], im.as_raw()))
                    }
                    .await;
                    lock(&images).insert(url, img.map(Img::Ready).unwrap_or(Img::Failed));
                    h.repaint();
                });
                None
            }
        }
    }

    fn mod_icon(&mut self, ui: &mut egui::Ui, pal: &Pal, rect: Rect, url: Option<&str>, title: &str) {
        let tex = url.and_then(|u| self.icon_tex(u));
        match tex {
            Some(t) => {
                egui::Image::new(&t).rounding(pal.r.min(8.0)).paint_at(ui, rect);
            }
            None => {
                let p = ui.painter();
                p.rect_filled(rect, pal.r.min(8.0), pal.surface2);
                let ch = title.chars().next().unwrap_or('?').to_uppercase().to_string();
                p.text(rect.center(), Align2::CENTER_CENTER, ch, font(rect.height() * 0.45, "bold"), pal.muted);
            }
        }
    }

    fn start_search(&mut self, profile: &Profile) {
        let key = format!("{:?}|{}|{}|{}", self.prof.source, self.prof.query.trim(), profile.version, profile.loader);
        if key == self.prof.searched_key {
            return;
        }
        self.prof.searched_key = key;
        let seq = {
            let mut s = lock(&self.prof.shared);
            s.seq += 1;
            s.searching = true;
            s.error = None;
            s.seq
        };
        let (shared, client, h) = (self.prof.shared.clone(), self.client.clone(), self.h.clone());
        let (source, query, mc, loader, key) =
            (self.prof.source, self.prof.query.clone(), profile.version.clone(), profile.loader.clone(), self.cfg.cf_api_key.clone());
        self.rt.spawn(async move {
            let res = mods::search(&client, source, &query, &mc, &loader, &key).await;
            let mut s = lock(&shared);
            if s.seq == seq {
                s.searching = false;
                match res {
                    Ok(r) => s.results = r,
                    Err(e) => {
                        s.results.clear();
                        s.error = Some(format!("{e:#}"));
                    }
                }
            }
            drop(s);
            h.repaint();
        });
    }

    fn start_install(&mut self, profile: &Profile, hit: &Hit) {
        lock(&self.prof.shared).installing.insert(hit.id.clone());
        let (shared, client, h) = (self.prof.shared.clone(), self.client.clone(), self.h.clone());
        let dir = profiles::mods_dir(&self.cfg.game_dir, profile);
        let (pid, mc, loader, key, already) = (profile.id.clone(), profile.version.clone(), profile.loader.clone(), self.cfg.cf_api_key.clone(), profile.mods.clone());
        let (source, id, title) = (hit.source, hit.id.clone(), hit.title.clone());
        h.log(format!("[Mods] Installiere {title} ({}) …", source.name()));
        self.rt.spawn(async move {
            let hh = h.clone();
            let res = mods::install(&client, source, &id, &mc, &loader, &key, &dir, &already, move |l| hh.log(l)).await;
            let mut s = lock(&shared);
            s.installing.remove(&id);
            match res {
                Ok(list) => {
                    let n = list.len();
                    s.finished.push((pid, list));
                    drop(s);
                    h.log(format!("[Mods] {title} installiert ({n} Datei{})", if n == 1 { "" } else { "en" }));
                }
                Err(e) => {
                    drop(s);
                    h.error(format!("{title}: {e:#}"));
                }
            }
            h.repaint();
        });
    }

    // -------------------------------------------------------------- Seite
    pub(super) fn profiles_page(&mut self, ui: &mut egui::Ui, r: Rect, pal: &Pal) {
        // Suche mit kurzer Verzoegerung nach dem Tippen ausloesen
        if let Some(t) = self.prof.query_changed {
            if t.elapsed() > Duration::from_millis(450) {
                self.prof.query_changed = None;
                if let Some(p) = self.selected_profile() {
                    self.start_search(&p);
                }
            } else {
                ui.ctx().request_repaint_after(Duration::from_millis(100));
            }
        }
        ui.allocate_new_ui(egui::UiBuilder::new().max_rect(r.shrink2(vec2(24.0, 18.0))), |ui| {
            if self.prof.form.is_some() {
                self.profile_form(ui, pal);
                return;
            }
            match self.selected_profile() {
                None => self.profile_list(ui, pal),
                Some(p) => self.profile_detail(ui, pal, p),
            }
        });
    }

    fn selected_profile(&self) -> Option<Profile> {
        let id = self.prof.selected.as_ref()?;
        self.prof.list.iter().find(|p| &p.id == id).cloned()
    }

    fn profile_list(&mut self, ui: &mut egui::Ui, pal: &Pal) {
        ui.horizontal(|ui| {
            header(ui, pal, "Profile", "Eigene Installationen mit Mods – Welten und Einstellungen getrennt");
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                if accent_button(ui, pal, "+  Neues Profil").clicked() {
                    let version = self.h.lock().versions.iter().find(|v| v.kind == "release").map(|v| v.id.clone()).unwrap_or_default();
                    self.prof.form = Some(ProfileForm { editing: None, name: String::new(), version, loader: "Fabric".into() });
                }
            });
        });
        if self.prof.list.is_empty() {
            card(ui, pal, "Noch keine Profile", |ui| {
                ui.label(RichText::new("Erstelle ein Profil mit Fabric oder Quilt, dann kannst du Mods von Modrinth und CurseForge mit einem Klick installieren.").color(pal.muted));
            });
            return;
        }
        let list = self.prof.list.clone();
        egui::ScrollArea::vertical().auto_shrink([false; 2]).show(ui, |ui| {
            for p in &list {
                let (rr, resp) = ui.allocate_exact_size(vec2(ui.available_width(), 72.0), Sense::click());
                let active = self.cfg.active_profile.as_deref() == Some(p.id.as_str());
                let painter = ui.painter();
                painter.rect_filled(rr, pal.r + 2.0, if resp.hovered() { pal.surface2 } else { pal.surface });
                painter.rect_stroke(rr, pal.r + 2.0, Stroke::new(1.0_f32, if active { pal.accent } else { pal.border }));
                let ic = Rect::from_min_size(rr.min + vec2(14.0, 14.0), vec2(44.0, 44.0));
                painter.rect_filled(ic, pal.r, alpha(pal.accent, 40));
                painter.text(ic.center(), Align2::CENTER_CENTER, p.name.chars().next().unwrap_or('?').to_uppercase().to_string(), font(20.0, "bold"), pal.accent);
                painter.text(pos2(ic.right() + 14.0, rr.top() + 25.0), Align2::LEFT_CENTER, &p.name, font(15.0, "semibold"), pal.text);
                let sub = format!("{} · {} · {} Mod{}", p.loader, p.version, p.mods.len(), if p.mods.len() == 1 { "" } else { "s" });
                painter.text(pos2(ic.right() + 14.0, rr.top() + 47.0), Align2::LEFT_CENTER, sub, font(12.0, "regular"), pal.muted);
                let play = Rect::from_min_size(pos2(rr.right() - 120.0, rr.center().y - 17.0), vec2(106.0, 34.0));
                let presp = ui.interact(play, Id::new(("pplay", &p.id)), Sense::click());
                let painter = ui.painter();
                pal.fill(painter, play, pal.r);
                if presp.hovered() {
                    painter.rect_filled(play, pal.r, alpha(Color32::WHITE, 25));
                }
                painter.text(play.center(), Align2::CENTER_CENTER, "Spielen", font(13.0, "semibold"), pal.on_accent);
                if presp.clicked() {
                    self.play_profile(p.clone());
                } else if resp.clicked() {
                    self.prof.selected = Some(p.id.clone());
                    self.prof.tab = "Installiert".into();
                    self.prof.confirm_delete = false;
                }
                ui.add_space(2.0);
            }
        });
    }

    fn profile_form(&mut self, ui: &mut egui::Ui, pal: &Pal) {
        let releases: Vec<String> = self.h.lock().versions.iter().filter(|v| v.kind == "release").map(|v| v.id.clone()).collect();
        let Some(form) = self.prof.form.as_mut() else { return };
        let editing = form.editing.clone();
        header(ui, pal, if editing.is_some() { "Profil bearbeiten" } else { "Neues Profil" }, "Name, Minecraft-Version und Modloader");
        let mut action = 0;
        card(ui, pal, "Profil", |ui| {
            field(ui, pal, "Name", &mut form.name, "z. B. Mein Modpack");
            ui.horizontal(|ui| {
                ui.label(RichText::new("Version").color(pal.muted));
                egui::ComboBox::from_id_salt("pversion").selected_text(&form.version).height(320.0).show_ui(ui, |ui| {
                    for v in &releases {
                        ui.selectable_value(&mut form.version, v.clone(), v);
                    }
                });
                ui.add_space(12.0);
                ui.label(RichText::new("Modloader").color(pal.muted));
                segmented(ui, pal, "ploader", &install::LOADERS, &mut form.loader);
            });
            if form.loader == "Vanilla" {
                ui.label(RichText::new("Hinweis: Mit Vanilla kannst du keine Mods nutzen – dafür Fabric oder Quilt wählen.").color(WARN).size(12.5));
            }
            if editing.is_some() {
                ui.label(RichText::new("Wenn du Version oder Loader änderst, passen installierte Mods evtl. nicht mehr.").color(pal.muted).size(12.5));
            }
            ui.add_space(4.0);
            ui.horizontal(|ui| {
                let ok = !form.name.trim().is_empty() && !form.version.is_empty();
                if ui.add_enabled(ok, egui::Button::new(if editing.is_some() { "Speichern" } else { "Profil erstellen" })).clicked() {
                    action = 1;
                }
                if ui.button("Abbrechen").clicked() {
                    action = 2;
                }
            });
        });
        match action {
            1 => {
                let form = self.prof.form.take().unwrap();
                match form.editing {
                    Some(id) => {
                        if let Some(p) = self.prof.list.iter_mut().find(|p| p.id == id) {
                            p.name = form.name.trim().into();
                            p.version = form.version;
                            p.loader = form.loader;
                        }
                    }
                    None => {
                        let id = profiles::new_id(&form.name, &self.prof.list);
                        self.prof.list.push(Profile { id: id.clone(), name: form.name.trim().into(), version: form.version, loader: form.loader, mods: vec![] });
                        self.prof.selected = Some(id);
                        self.prof.tab = "Mods hinzufügen".into();
                    }
                }
                self.prof.searched_key.clear();
                profiles::save(&self.prof.list);
            }
            2 => self.prof.form = None,
            _ => {}
        }
    }

    fn profile_detail(&mut self, ui: &mut egui::Ui, pal: &Pal, p: Profile) {
        let gd = self.cfg.game_dir.clone();
        let mdir = profiles::mods_dir(&gd, &p);
        ui.horizontal(|ui| {
            if ui.button("‹ Profile").clicked() {
                self.prof.selected = None;
            }
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                let del = if self.prof.confirm_delete { "Wirklich löschen?" } else { "Löschen" };
                if ui.add(egui::Button::new(RichText::new(del).color(BAD))).clicked() {
                    if self.prof.confirm_delete {
                        let _ = std::fs::remove_dir_all(profiles::dir(&gd, &p));
                        self.prof.list.retain(|x| x.id != p.id);
                        if self.cfg.active_profile.as_deref() == Some(p.id.as_str()) {
                            self.cfg.active_profile = None;
                        }
                        profiles::save(&self.prof.list);
                        self.prof.selected = None;
                        self.prof.confirm_delete = false;
                        return;
                    }
                    self.prof.confirm_delete = true;
                }
                if ui.button("Ordner öffnen").clicked() {
                    let d = profiles::dir(&gd, &p);
                    let _ = std::fs::create_dir_all(d.join("mods"));
                    let _ = open::that(d);
                }
                if ui.button("Bearbeiten").clicked() {
                    self.prof.form = Some(ProfileForm { editing: Some(p.id.clone()), name: p.name.clone(), version: p.version.clone(), loader: p.loader.clone() });
                }
                if accent_button(ui, pal, "▶  Spielen").clicked() {
                    self.play_profile(p.clone());
                }
            });
        });
        if self.prof.selected.is_none() {
            return;
        }
        header(ui, pal, &p.name, &format!("{} · Minecraft {} · eigener Spielordner", p.loader, p.version));
        let (busy, status, done, total) = {
            let s = self.h.lock();
            (s.busy, s.status.clone(), s.done, s.total)
        };
        if busy || status.ends_with("läuft") {
            ui.horizontal(|ui| {
                if busy {
                    ui.spinner();
                }
                ui.label(RichText::new(&status).color(pal.text));
                if busy && total > 0 {
                    let (r, _) = ui.allocate_exact_size(vec2(260.0, 6.0), Sense::hover());
                    ui.painter().rect_filled(r, 3.0, pal.surface2);
                    let f = done as f32 / total as f32;
                    pal.fill(ui.painter(), Rect::from_min_size(r.min, vec2((r.width() * f).max(6.0), 6.0)), 3.0);
                    ui.label(RichText::new(format!("{done}/{total}")).color(pal.muted).size(12.0));
                }
            });
        }
        let tabs = [format!("Installiert ({})", p.mods.len()), "Mods hinzufügen".to_string()];
        let mut tab = if self.prof.tab.starts_with("Installiert") { tabs[0].clone() } else { tabs[1].clone() };
        let refs: Vec<&str> = tabs.iter().map(String::as_str).collect();
        segmented(ui, pal, "ptab", &refs, &mut tab);
        self.prof.tab = if tab.starts_with("Installiert") { "Installiert".into() } else { "Mods hinzufügen".into() };
        ui.add_space(2.0);
        if p.loader == "Vanilla" {
            card(ui, pal, "Keine Mods mit Vanilla", |ui| {
                ui.label(RichText::new("Mods brauchen einen Modloader. Klicke auf „Bearbeiten“ und wähle Fabric oder Quilt.").color(pal.muted));
            });
            return;
        }
        if self.prof.tab == "Installiert" {
            self.installed_mods(ui, pal, &p, &mdir);
        } else {
            self.mod_browser(ui, pal, &p);
        }
    }

    fn installed_mods(&mut self, ui: &mut egui::Ui, pal: &Pal, p: &Profile, mdir: &std::path::Path) {
        if p.mods.is_empty() {
            card(ui, pal, "Noch keine Mods", |ui| {
                ui.label(RichText::new("Unter „Mods hinzufügen“ findest du tausende Mods von Modrinth und CurseForge.").color(pal.muted));
            });
            return;
        }
        let mut remove = None;
        egui::ScrollArea::vertical().auto_shrink([false; 2]).show(ui, |ui| {
            for m in &p.mods {
                let (rr, _) = ui.allocate_exact_size(vec2(ui.available_width(), 56.0), Sense::hover());
                ui.painter().rect_filled(rr, pal.r + 2.0, pal.surface);
                ui.painter().rect_stroke(rr, pal.r + 2.0, Stroke::new(1.0_f32, pal.border));
                let ic = Rect::from_min_size(rr.min + vec2(10.0, 10.0), vec2(36.0, 36.0));
                self.mod_icon(ui, pal, ic, m.icon.as_deref(), &m.title);
                let painter = ui.painter().with_clip_rect(Rect::from_min_max(rr.min, pos2(rr.right() - 200.0, rr.bottom())));
                let tr = painter.text(pos2(ic.right() + 12.0, rr.top() + 20.0), Align2::LEFT_CENTER, &m.title, font(14.0, "semibold"), pal.text);
                let (sc, sn) = match m.source {
                    Source::Modrinth => (MODRINTH_GREEN, "Modrinth"),
                    Source::CurseForge => (CURSEFORGE_ORANGE, "CurseForge"),
                };
                let mut x = tr.right() + 10.0;
                x = painter.text(pos2(x, rr.top() + 20.0), Align2::LEFT_CENTER, sn, font(10.5, "semibold"), sc).right() + 8.0;
                if m.dependency {
                    painter.text(pos2(x, rr.top() + 20.0), Align2::LEFT_CENTER, "Abhängigkeit", font(10.5, "medium"), pal.muted);
                }
                painter.text(pos2(ic.right() + 12.0, rr.top() + 39.0), Align2::LEFT_CENTER, &m.filename, font(11.5, "regular"), pal.muted);
                // Schalter + Entfernen
                let mut on = mods::is_enabled(mdir, m);
                let tr = Rect::from_min_size(pos2(rr.right() - 190.0, rr.center().y - 11.0), vec2(40.0, 22.0));
                let resp = ui.put(tr, |ui: &mut egui::Ui| theme::toggle(ui, pal, &mut on)).on_hover_text("Aktiv / Deaktiviert");
                if resp.changed() {
                    if let Err(e) = mods::set_enabled(mdir, m, on) {
                        self.h.error(format!("{e:#}"));
                    }
                }
                let br = Rect::from_min_size(pos2(rr.right() - 120.0, rr.center().y - 15.0), vec2(106.0, 30.0));
                if ui.put(br, egui::Button::new(RichText::new("Entfernen").color(BAD))).clicked() {
                    remove = Some(m.clone());
                }
                ui.add_space(2.0);
            }
        });
        if let Some(m) = remove {
            mods::remove(mdir, &m);
            if let Some(pp) = self.prof.list.iter_mut().find(|x| x.id == p.id) {
                pp.mods.retain(|x| x != &m);
            }
            profiles::save(&self.prof.list);
        }
    }

    fn mod_browser(&mut self, ui: &mut egui::Ui, pal: &Pal, p: &Profile) {
        ui.horizontal(|ui| {
            let mut src = self.prof.source.name().to_string();
            segmented(ui, pal, "psrc", &["Modrinth", "CurseForge"], &mut src);
            let new_src = if src == "CurseForge" { Source::CurseForge } else { Source::Modrinth };
            if new_src != self.prof.source {
                self.prof.source = new_src;
                self.prof.query_changed = Some(Instant::now() - Duration::from_secs(1));
            }
            let r = ui.add(egui::TextEdit::singleline(&mut self.prof.query).hint_text("Mods suchen … (leer = beliebteste)").desired_width(f32::INFINITY).margin(vec2(10.0, 7.0)));
            if r.changed() {
                self.prof.query_changed = Some(Instant::now());
            }
        });
        if self.prof.source == Source::CurseForge && self.cfg.cf_api_key.trim().is_empty() {
            card(ui, pal, "CurseForge braucht einen API-Key", |ui| {
                ui.label(RichText::new("CurseForge erlaubt die Suche nur mit einem eigenen, kostenlosen Schlüssel von console.curseforge.com. Trag ihn unter Einstellungen › CurseForge ein.").color(pal.muted));
                if ui.button("Zu den Einstellungen").clicked() {
                    self.page = Page::Settings;
                }
            });
            return;
        }
        self.start_search(p);
        let (results, searching, error, installing) = {
            let s = lock(&self.prof.shared);
            (s.results.clone(), s.searching, s.error.clone(), s.installing.clone())
        };
        if let Some(e) = error {
            ui.label(RichText::new(e).color(BAD));
        }
        if searching {
            ui.horizontal(|ui| {
                ui.spinner();
                ui.label(RichText::new("Suche …").color(pal.muted));
            });
        } else if results.is_empty() {
            ui.label(RichText::new(format!("Keine Mods für {} {} gefunden.", p.loader, p.version)).color(pal.muted));
        }
        let mut install = None;
        egui::ScrollArea::vertical().auto_shrink([false; 2]).show(ui, |ui| {
            for hit in &results {
                let (rr, _) = ui.allocate_exact_size(vec2(ui.available_width(), 72.0), Sense::hover());
                ui.painter().rect_filled(rr, pal.r + 2.0, pal.surface);
                ui.painter().rect_stroke(rr, pal.r + 2.0, Stroke::new(1.0_f32, pal.border));
                let ic = Rect::from_min_size(rr.min + vec2(12.0, 12.0), vec2(48.0, 48.0));
                self.mod_icon(ui, pal, ic, hit.icon.as_deref(), &hit.title);
                let text_clip = Rect::from_min_max(rr.min, pos2(rr.right() - 150.0, rr.bottom()));
                let painter = ui.painter().with_clip_rect(text_clip);
                let tr = painter.text(pos2(ic.right() + 14.0, rr.top() + 20.0), Align2::LEFT_CENTER, &hit.title, font(14.5, "semibold"), pal.text);
                let link = ui.interact(tr, Id::new(("link", &hit.id)), Sense::click()).on_hover_text(format!("Auf {} ansehen", hit.source.name()));
                if link.hovered() {
                    ui.ctx().set_cursor_icon(egui::CursorIcon::PointingHand);
                    ui.painter().line_segment([tr.left_bottom(), tr.right_bottom()], Stroke::new(1.0_f32, pal.text));
                }
                if link.clicked() {
                    let _ = open::that(&hit.url);
                }
                let painter = ui.painter().with_clip_rect(text_clip);
                if !hit.author.is_empty() {
                    painter.text(pos2(tr.right() + 8.0, rr.top() + 21.0), Align2::LEFT_CENTER, format!("von {}", hit.author), font(11.5, "regular"), pal.muted);
                }
                painter.text(pos2(ic.right() + 14.0, rr.top() + 40.0), Align2::LEFT_CENTER, &hit.description, font(12.0, "regular"), pal.muted);
                painter.text(pos2(ic.right() + 14.0, rr.top() + 58.0), Align2::LEFT_CENTER, format!("{} Downloads", mods::format_downloads(hit.downloads)), font(11.0, "medium"), alpha(pal.muted, 200));
                let br = Rect::from_min_size(pos2(rr.right() - 132.0, rr.center().y - 16.0), vec2(118.0, 32.0));
                let installed = p.mods.iter().any(|m| m.source == hit.source && m.project_id == hit.id);
                if installed {
                    ui.painter().text(br.center(), Align2::CENTER_CENTER, "✔ Installiert", font(12.5, "semibold"), theme::GOOD);
                } else if installing.contains(&hit.id) {
                    ui.put(br, |ui: &mut egui::Ui| {
                        ui.horizontal_centered(|ui| {
                            ui.spinner();
                            ui.label(RichText::new("Lädt …").color(pal.muted));
                        })
                        .response
                    });
                } else {
                    let resp = ui.interact(br, Id::new(("inst", &hit.id)), Sense::click());
                    let painter = ui.painter();
                    pal.fill(painter, br, pal.r);
                    if resp.hovered() {
                        painter.rect_filled(br, pal.r, alpha(Color32::WHITE, 25));
                    }
                    painter.text(br.center(), Align2::CENTER_CENTER, "Installieren", font(12.5, "semibold"), pal.on_accent);
                    if resp.clicked() {
                        install = Some(hit.clone());
                    }
                }
                ui.add_space(2.0);
            }
        });
        if let Some(hit) = install {
            self.start_install(p, &hit);
        }
    }
}

pub(super) fn accent_button(ui: &mut egui::Ui, pal: &Pal, label: &str) -> egui::Response {
    let g = ui.fonts(|f| f.layout_no_wrap(label.to_string(), font(13.0, "semibold"), Color32::WHITE));
    let (r, resp) = ui.allocate_exact_size(vec2(g.size().x + 28.0, 34.0), Sense::click());
    let p = ui.painter();
    pal.fill(p, r, pal.r);
    if resp.hovered() {
        p.rect_filled(r, pal.r, alpha(Color32::WHITE, 25));
    }
    p.text(r.center(), Align2::CENTER_CENTER, label, font(13.0, "semibold"), pal.on_accent);
    resp
}
