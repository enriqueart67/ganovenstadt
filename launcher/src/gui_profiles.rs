//! Seite „Profile & Inhalte“: Profile anlegen/bearbeiten und alles von Modrinth installieren –
//! Mods, Ressourcenpakete, Shader, Datenpakete und komplette Modpacks.
use std::collections::{HashMap, HashSet};
use std::path::Path;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use eframe::egui::{self, pos2, vec2, Align2, Color32, Id, Rect, RichText, Sense, Stroke};

use super::{card, field, header, segmented, App};
use crate::install;
use crate::mods::{self, Hit, InstalledMod, Kind};
use crate::profiles::{self, Profile};
use crate::theme::{self, alpha, font, Pal, BAD, WARN};

/// Zustand, den Hintergrund-Aufgaben (Suche, Installation, Bilder) fuellen.
#[derive(Default)]
pub struct ModsShared {
    pub results: Vec<Hit>,
    pub searching: bool,
    pub error: Option<String>,
    pub seq: u64,
    pub installing: HashSet<String>,
    pub finished: Vec<(String, Vec<InstalledMod>)>,
    pub new_profiles: Vec<Profile>,
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
    /// "Installiert" oder "Entdecken"
    pub tab: String,
    pub kind: Kind,
    pub query: String,
    pub query_changed: Option<Instant>,
    pub searched_key: String,
    pub world: Option<String>,
    pub modpacks: bool,
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
            kind: Kind::Mod,
            query: String::new(),
            query_changed: None,
            searched_key: String::new(),
            world: None,
            modpacks: false,
            form: None,
            confirm_delete: false,
            shared: Default::default(),
            images: Default::default(),
            textures: HashMap::new(),
        }
    }
    pub fn busy(&self) -> bool {
        let s = lock(&self.shared);
        s.searching || !s.installing.is_empty()
    }
}

fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

fn kind_singular(k: Kind) -> &'static str {
    match k {
        Kind::Mod => "Mod",
        Kind::ResourcePack => "Ressourcenpaket",
        Kind::Shader => "Shader",
        Kind::DataPack => "Datenpaket",
        Kind::ModPack => "Modpack",
    }
}

impl App {
    /// Fertige Installationen uebernehmen (wird jedes Frame aufgerufen).
    pub(super) fn collect_installs(&mut self) {
        let (done, new_profiles) = {
            let mut s = lock(&self.prof.shared);
            (s.finished.drain(..).collect::<Vec<_>>(), s.new_profiles.drain(..).collect::<Vec<_>>())
        };
        if done.is_empty() && new_profiles.is_empty() {
            return;
        }
        for (pid, new) in done {
            if let Some(p) = self.prof.list.iter_mut().find(|p| p.id == pid) {
                for m in new {
                    p.mods.retain(|x| !(x.kind == m.kind && x.project_id == m.project_id && x.world == m.world));
                    p.mods.push(m);
                }
            }
        }
        for p in new_profiles {
            self.prof.selected = Some(p.id.clone());
            self.prof.tab = "Installiert".into();
            self.prof.modpacks = false;
            self.prof.list.push(p);
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

    fn thumb(&mut self, ui: &mut egui::Ui, pal: &Pal, rect: Rect, url: Option<&str>, title: &str) {
        match url.and_then(|u| self.icon_tex(u)) {
            Some(t) => {
                egui::Image::new(&t).rounding(pal.r.min(8.0)).paint_at(ui, rect);
            }
            None => {
                let p = ui.painter();
                p.rect_filled(rect, pal.r.min(8.0), alpha(pal.accent, 40));
                let ch = title.chars().next().unwrap_or('?').to_uppercase().to_string();
                p.text(rect.center(), Align2::CENTER_CENTER, ch, font(rect.height() * 0.45, "bold"), pal.accent);
            }
        }
    }

    fn start_search(&mut self, kind: Kind, mc: &str, loader: &str) {
        let key = format!("{kind:?}|{}|{mc}|{loader}", self.prof.query.trim());
        if key == self.prof.searched_key {
            return;
        }
        self.prof.searched_key = key;
        let seq = {
            let mut s = lock(&self.prof.shared);
            s.seq += 1;
            s.searching = true;
            s.error = None;
            s.results.clear();
            s.seq
        };
        let (shared, client, h) = (self.prof.shared.clone(), self.client.clone(), self.h.clone());
        let (query, mc, loader) = (self.prof.query.clone(), mc.to_string(), loader.to_string());
        self.rt.spawn(async move {
            let res = mods::search(&client, kind, &query, &mc, &loader).await;
            let mut s = lock(&shared);
            if s.seq == seq {
                s.searching = false;
                match res {
                    Ok(r) => s.results = r,
                    Err(e) => s.error = Some(format!("{e:#}")),
                }
            }
            drop(s);
            h.repaint();
        });
    }

    fn start_install(&mut self, profile: &Profile, hit: &Hit, world: Option<String>) {
        lock(&self.prof.shared).installing.insert(hit.id.clone());
        let (shared, client, h) = (self.prof.shared.clone(), self.client.clone(), self.h.clone());
        let root = profiles::dir(&self.cfg.game_dir, profile);
        let (pid, mc, loader, already) = (profile.id.clone(), profile.version.clone(), profile.loader.clone(), profile.mods.clone());
        let (kind, id, title) = (hit.kind, hit.id.clone(), hit.title.clone());
        h.log(format!("[Modrinth] Installiere {title} …"));
        self.rt.spawn(async move {
            let hh = h.clone();
            let res = mods::install(&client, kind, &id, &mc, &loader, &root, world.as_deref(), &already, move |l| hh.log(l)).await;
            let mut s = lock(&shared);
            s.installing.remove(&id);
            match res {
                Ok(list) => {
                    let n = list.len();
                    s.finished.push((pid, list));
                    drop(s);
                    h.log(format!("[Modrinth] {title} installiert ({n} Datei{})", if n == 1 { "" } else { "en" }));
                }
                Err(e) => {
                    drop(s);
                    h.error(format!("{title}: {e:#}"));
                }
            }
            h.repaint();
        });
    }

    fn start_modpack(&mut self, hit: &Hit) {
        if self.h.lock().busy {
            return;
        }
        lock(&self.prof.shared).installing.insert(hit.id.clone());
        let (shared, client, h) = (self.prof.shared.clone(), self.client.clone(), self.h.clone());
        let (id, title, gd, existing) = (hit.id.clone(), hit.title.clone(), self.cfg.game_dir.clone(), self.prof.list.clone());
        h.set_busy(true);
        h.log(format!("[Modrinth] Installiere Modpack {title} …"));
        self.rt.spawn(async move {
            let res = mods::install_modpack(&client, &h, &id, &gd, existing).await;
            let mut s = lock(&shared);
            s.installing.remove(&id);
            match res {
                Ok(p) => {
                    let n = p.mods.len();
                    s.new_profiles.push(p);
                    drop(s);
                    h.log(format!("[Modrinth] Modpack {title} installiert ({n} Inhalte) – neues Profil angelegt"));
                }
                Err(e) => {
                    drop(s);
                    h.progress(0, 0);
                    h.status("Bereit.");
                    h.error(format!("{title}: {e:#}"));
                }
            }
            h.set_busy(false);
        });
    }

    // -------------------------------------------------------------- Seite
    pub(super) fn profiles_page(&mut self, ui: &mut egui::Ui, r: Rect, pal: &Pal) {
        if let Some(t) = self.prof.query_changed {
            if t.elapsed() > Duration::from_millis(450) {
                self.prof.query_changed = None;
            } else {
                ui.ctx().request_repaint_after(Duration::from_millis(100));
            }
        }
        ui.allocate_new_ui(egui::UiBuilder::new().max_rect(r.shrink2(vec2(24.0, 18.0))), |ui| {
            if self.prof.form.is_some() {
                self.profile_form(ui, pal);
            } else if self.prof.modpacks {
                self.modpack_browser(ui, pal);
            } else {
                match self.selected_profile() {
                    None => self.profile_list(ui, pal),
                    Some(p) => self.profile_detail(ui, pal, p),
                }
            }
        });
    }

    fn selected_profile(&self) -> Option<Profile> {
        let id = self.prof.selected.as_ref()?;
        self.prof.list.iter().find(|p| &p.id == id).cloned()
    }

    fn progress_line(&self, ui: &mut egui::Ui, pal: &Pal) {
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
    }

    fn profile_list(&mut self, ui: &mut egui::Ui, pal: &Pal) {
        ui.horizontal(|ui| {
            header(ui, pal, "Profile", "Eigene Installationen – Welten, Einstellungen und Inhalte getrennt");
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                if accent_button(ui, pal, "+  Neues Profil").clicked() {
                    let version = self.h.lock().versions.iter().find(|v| v.kind == "release").map(|v| v.id.clone()).unwrap_or_default();
                    self.prof.form = Some(ProfileForm { editing: None, name: String::new(), version, loader: "Fabric".into() });
                }
                if ui.button("Modpack installieren").clicked() {
                    self.prof.modpacks = true;
                    self.prof.query.clear();
                }
            });
        });
        self.progress_line(ui, pal);
        if self.prof.list.is_empty() {
            card(ui, pal, "Noch keine Profile", |ui| {
                ui.label(RichText::new("Erstelle ein Profil oder installiere ein fertiges Modpack. Danach findest du unter „Entdecken“ Mods, Ressourcenpakete, Shader und Datenpakete von Modrinth.").color(pal.muted));
            });
            return;
        }
        let list = self.prof.list.clone();
        egui::ScrollArea::vertical().auto_shrink([false; 2]).show(ui, |ui| {
            for p in &list {
                let (rr, resp) = ui.allocate_exact_size(vec2(ui.available_width(), 72.0), Sense::click());
                let active = self.cfg.active_profile.as_deref() == Some(p.id.as_str());
                ui.painter().rect_filled(rr, pal.r + 2.0, if resp.hovered() { pal.surface2 } else { pal.surface });
                ui.painter().rect_stroke(rr, pal.r + 2.0, Stroke::new(1.0_f32, if active { pal.accent } else { pal.border }));
                let ic = Rect::from_min_size(rr.min + vec2(14.0, 14.0), vec2(44.0, 44.0));
                self.thumb(ui, pal, ic, p.icon.as_deref(), &p.name);
                let painter = ui.painter();
                painter.text(pos2(ic.right() + 14.0, rr.top() + 25.0), Align2::LEFT_CENTER, &p.name, font(15.0, "semibold"), pal.text);
                let n = p.mods.len();
                let sub = format!("{} · {} · {} Inhalt{}", p.loader, p.version, n, if n == 1 { "" } else { "e" });
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
                ui.label(RichText::new("Mit Vanilla gehen Ressourcen- und Datenpakete, aber keine Mods und Shader – dafür Fabric oder Quilt wählen.").color(WARN).size(12.5));
            }
            if editing.is_some() {
                ui.label(RichText::new("Wenn du Version oder Loader änderst, passen installierte Inhalte evtl. nicht mehr.").color(pal.muted).size(12.5));
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
                            if p.version != form.version || p.loader != form.loader {
                                p.loader_version = None;
                            }
                            p.name = form.name.trim().into();
                            p.version = form.version;
                            p.loader = form.loader;
                        }
                    }
                    None => {
                        let id = profiles::new_id(&form.name, &self.prof.list);
                        self.prof.list.push(Profile {
                            id: id.clone(),
                            name: form.name.trim().into(),
                            version: form.version,
                            loader: form.loader,
                            loader_version: None,
                            mods: vec![],
                            icon: None,
                        });
                        self.prof.selected = Some(id);
                        self.prof.tab = "Entdecken".into();
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
        let root = profiles::dir(&gd, &p);
        let mut back = false;
        ui.horizontal(|ui| {
            if ui.button("‹ Profile").clicked() {
                back = true;
            }
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                let del = if self.prof.confirm_delete { "Wirklich löschen?" } else { "Löschen" };
                if ui.add(egui::Button::new(RichText::new(del).color(BAD))).clicked() {
                    if self.prof.confirm_delete {
                        let _ = std::fs::remove_dir_all(&root);
                        self.prof.list.retain(|x| x.id != p.id);
                        if self.cfg.active_profile.as_deref() == Some(p.id.as_str()) {
                            self.cfg.active_profile = None;
                        }
                        profiles::save(&self.prof.list);
                        back = true;
                    }
                    self.prof.confirm_delete = !self.prof.confirm_delete;
                }
                if ui.button("Ordner öffnen").clicked() {
                    let _ = std::fs::create_dir_all(&root);
                    let _ = open::that(&root);
                }
                if ui.button("Bearbeiten").clicked() {
                    self.prof.form = Some(ProfileForm { editing: Some(p.id.clone()), name: p.name.clone(), version: p.version.clone(), loader: p.loader.clone() });
                }
                if accent_button(ui, pal, "▶  Spielen").clicked() {
                    self.play_profile(p.clone());
                }
            });
        });
        if back {
            self.prof.selected = None;
            self.prof.confirm_delete = false;
            return;
        }
        let pinned = p.loader_version.as_deref().map(|v| format!(" {v}")).unwrap_or_default();
        header(ui, pal, &p.name, &format!("{}{pinned} · Minecraft {} · eigener Spielordner", p.loader, p.version));
        self.progress_line(ui, pal);
        let tabs = [format!("Installiert ({})", p.mods.len()), "Entdecken".to_string()];
        let mut tab = if self.prof.tab == "Installiert" { tabs[0].clone() } else { tabs[1].clone() };
        let refs: Vec<&str> = tabs.iter().map(String::as_str).collect();
        segmented(ui, pal, "ptab", &refs, &mut tab);
        self.prof.tab = if tab.starts_with("Installiert") { "Installiert".into() } else { "Entdecken".into() };
        ui.add_space(2.0);
        if self.prof.tab == "Installiert" {
            self.installed(ui, pal, &p, &root);
        } else {
            self.discover(ui, pal, &p, &root);
        }
    }

    fn installed(&mut self, ui: &mut egui::Ui, pal: &Pal, p: &Profile, root: &Path) {
        if p.mods.is_empty() {
            card(ui, pal, "Noch nichts installiert", |ui| {
                ui.label(RichText::new("Unter „Entdecken“ findest du Mods, Ressourcenpakete, Shader und Datenpakete von Modrinth.").color(pal.muted));
            });
            return;
        }
        let mut remove = None;
        egui::ScrollArea::vertical().auto_shrink([false; 2]).show(ui, |ui| {
            for kind in [Kind::Mod, Kind::ResourcePack, Kind::Shader, Kind::DataPack] {
                let items: Vec<&InstalledMod> = p.mods.iter().filter(|m| m.kind == kind).collect();
                if items.is_empty() {
                    continue;
                }
                ui.label(RichText::new(format!("{} ({})", kind.label(), items.len())).font(font(13.0, "semibold")).color(pal.muted));
                for m in items {
                    let (rr, _) = ui.allocate_exact_size(vec2(ui.available_width(), 54.0), Sense::hover());
                    ui.painter().rect_filled(rr, pal.r + 2.0, pal.surface);
                    ui.painter().rect_stroke(rr, pal.r + 2.0, Stroke::new(1.0_f32, pal.border));
                    let ic = Rect::from_min_size(rr.min + vec2(10.0, 9.0), vec2(36.0, 36.0));
                    self.thumb(ui, pal, ic, m.icon.as_deref(), &m.title);
                    let painter = ui.painter().with_clip_rect(Rect::from_min_max(rr.min, pos2(rr.right() - 200.0, rr.bottom())));
                    let tr = painter.text(pos2(ic.right() + 12.0, rr.top() + 19.0), Align2::LEFT_CENTER, &m.title, font(14.0, "semibold"), pal.text);
                    let tag = if m.dependency {
                        Some("Abhängigkeit".to_string())
                    } else if m.from_pack {
                        Some("aus Modpack".to_string())
                    } else {
                        m.world.as_ref().map(|w| format!("Welt: {w}"))
                    };
                    if let Some(t) = tag {
                        painter.text(pos2(tr.right() + 10.0, rr.top() + 19.0), Align2::LEFT_CENTER, t, font(10.5, "medium"), pal.muted);
                    }
                    painter.text(pos2(ic.right() + 12.0, rr.top() + 37.0), Align2::LEFT_CENTER, &m.filename, font(11.5, "regular"), pal.muted);
                    let mut on = mods::is_enabled(root, m);
                    let tr = Rect::from_min_size(pos2(rr.right() - 190.0, rr.center().y - 11.0), vec2(40.0, 22.0));
                    if ui.put(tr, |ui: &mut egui::Ui| theme::toggle(ui, pal, &mut on)).on_hover_text("Aktiv / Deaktiviert").changed() {
                        if let Err(e) = mods::set_enabled(root, m, on) {
                            self.h.error(format!("{e:#}"));
                        }
                    }
                    let br = Rect::from_min_size(pos2(rr.right() - 120.0, rr.center().y - 15.0), vec2(106.0, 30.0));
                    if ui.put(br, egui::Button::new(RichText::new("Entfernen").color(BAD))).clicked() {
                        remove = Some(m.clone());
                    }
                    ui.add_space(2.0);
                }
                ui.add_space(6.0);
            }
        });
        if let Some(m) = remove {
            mods::remove(root, &m);
            if let Some(pp) = self.prof.list.iter_mut().find(|x| x.id == p.id) {
                pp.mods.retain(|x| x != &m);
            }
            profiles::save(&self.prof.list);
        }
    }

    fn discover(&mut self, ui: &mut egui::Ui, pal: &Pal, p: &Profile, root: &Path) {
        // Art waehlen
        let labels: Vec<&str> = Kind::BROWSABLE.iter().map(|k| k.label()).collect();
        let mut sel = self.prof.kind.label().to_string();
        segmented(ui, pal, "pkind", &labels, &mut sel);
        if let Some(k) = Kind::BROWSABLE.iter().find(|k| k.label() == sel) {
            self.prof.kind = *k;
        }
        let kind = self.prof.kind;
        if kind.needs_loader() && p.loader == "Vanilla" {
            card(ui, pal, &format!("{} brauchen Fabric oder Quilt", kind.label()), |ui| {
                ui.label(RichText::new("Klicke oben auf „Bearbeiten“ und wähle Fabric oder Quilt als Modloader. Ressourcen- und Datenpakete funktionieren auch mit Vanilla.").color(pal.muted));
            });
            return;
        }
        if kind == Kind::Shader && !p.mods.iter().any(|m| m.project_id == mods::IRIS) {
            ui.label(RichText::new("Shader brauchen den Mod Iris – er wird beim ersten Shader automatisch mitinstalliert.").color(pal.muted).size(12.5));
        }
        // Datenpakete: Welt waehlen
        let worlds = mods::worlds(root);
        if kind == Kind::DataPack {
            if worlds.is_empty() {
                card(ui, pal, "Noch keine Welt", |ui| {
                    ui.label(RichText::new("Datenpakete gehören zu einer Welt. Starte das Profil einmal, erstelle eine Welt und komm dann hierher zurück.").color(pal.muted));
                });
                return;
            }
            if self.prof.world.as_ref().map_or(true, |w| !worlds.contains(w)) {
                self.prof.world = worlds.first().cloned();
            }
        }
        ui.horizontal(|ui| {
            if kind == Kind::DataPack {
                ui.label(RichText::new("Welt").color(pal.muted));
                let cur = self.prof.world.clone().unwrap_or_default();
                egui::ComboBox::from_id_salt("pworld").selected_text(cur).show_ui(ui, |ui| {
                    for w in &worlds {
                        ui.selectable_value(&mut self.prof.world, Some(w.clone()), w);
                    }
                });
            }
            let hint = format!("{} suchen … (leer = beliebteste)", kind.label());
            let r = ui.add(egui::TextEdit::singleline(&mut self.prof.query).hint_text(hint).desired_width(f32::INFINITY).margin(vec2(10.0, 7.0)));
            if r.changed() {
                self.prof.query_changed = Some(Instant::now());
            }
        });
        if self.prof.query_changed.is_none() {
            self.start_search(kind, &p.version, &p.loader);
        }
        let world = if kind == Kind::DataPack { self.prof.world.clone() } else { None };
        let installed = |hit: &Hit| p.mods.iter().any(|m| m.kind == hit.kind && m.project_id == hit.id && (hit.kind != Kind::DataPack || m.world == world));
        let label = format!("{} installieren", kind_singular(kind));
        if let Some(hit) = self.results(ui, pal, &label, &installed, false) {
            self.start_install(p, &hit, world.clone());
        }
    }

    fn modpack_browser(&mut self, ui: &mut egui::Ui, pal: &Pal) {
        let mut back = false;
        ui.horizontal(|ui| {
            if ui.button("‹ Profile").clicked() {
                back = true;
            }
        });
        if back {
            self.prof.modpacks = false;
            self.prof.query.clear();
            return;
        }
        header(ui, pal, "Modpack installieren", "Fertige Sammlungen von Modrinth – jedes Modpack wird ein eigenes Profil");
        self.progress_line(ui, pal);
        let r = ui.add(egui::TextEdit::singleline(&mut self.prof.query).hint_text("Modpacks suchen … (leer = beliebteste)").desired_width(f32::INFINITY).margin(vec2(10.0, 7.0)));
        if r.changed() {
            self.prof.query_changed = Some(Instant::now());
        }
        if self.prof.query_changed.is_none() {
            self.start_search(Kind::ModPack, "", "Fabric");
        }
        if let Some(hit) = self.results(ui, pal, "Installieren", &|_| false, true) {
            self.start_modpack(&hit);
        }
    }

    /// Ergebnisliste; liefert den Treffer, dessen Installieren-Knopf geklickt wurde.
    fn results(&mut self, ui: &mut egui::Ui, pal: &Pal, button: &str, installed: &dyn Fn(&Hit) -> bool, show_version: bool) -> Option<Hit> {
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
                ui.label(RichText::new("Suche auf Modrinth …").color(pal.muted));
            });
        } else if results.is_empty() {
            ui.label(RichText::new("Nichts gefunden.").color(pal.muted));
        }
        let busy = self.h.lock().busy;
        let mut clicked = None;
        let bw = ui.fonts(|f| f.layout_no_wrap(button.to_string(), font(12.5, "semibold"), Color32::WHITE).size().x) + 28.0;
        egui::ScrollArea::vertical().auto_shrink([false; 2]).show(ui, |ui| {
            for hit in &results {
                let (rr, _) = ui.allocate_exact_size(vec2(ui.available_width(), 72.0), Sense::hover());
                ui.painter().rect_filled(rr, pal.r + 2.0, pal.surface);
                ui.painter().rect_stroke(rr, pal.r + 2.0, Stroke::new(1.0_f32, pal.border));
                let ic = Rect::from_min_size(rr.min + vec2(12.0, 12.0), vec2(48.0, 48.0));
                self.thumb(ui, pal, ic, hit.icon.as_deref(), &hit.title);
                let text_clip = Rect::from_min_max(rr.min, pos2(rr.right() - bw - 30.0, rr.bottom()));
                let painter = ui.painter().with_clip_rect(text_clip);
                let tr = painter.text(pos2(ic.right() + 14.0, rr.top() + 20.0), Align2::LEFT_CENTER, &hit.title, font(14.5, "semibold"), pal.text);
                let link = ui.interact(tr, Id::new(("link", &hit.id)), Sense::click()).on_hover_text("Auf Modrinth ansehen");
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
                let mut info = format!("{} Downloads", mods::format_downloads(hit.downloads));
                if show_version {
                    if let Some(v) = hit.versions.last() {
                        info.push_str(&format!("  ·  bis Minecraft {v}"));
                    }
                }
                painter.text(pos2(ic.right() + 14.0, rr.top() + 58.0), Align2::LEFT_CENTER, info, font(11.0, "medium"), alpha(pal.muted, 200));
                let br = Rect::from_min_size(pos2(rr.right() - bw - 14.0, rr.center().y - 16.0), vec2(bw, 32.0));
                if installed(hit) {
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
                    let enabled = !(show_version && busy);
                    let resp = ui.interact(br, Id::new(("inst", &hit.id)), if enabled { Sense::click() } else { Sense::hover() });
                    let painter = ui.painter();
                    pal.fill(painter, br, pal.r);
                    if resp.hovered() && enabled {
                        painter.rect_filled(br, pal.r, alpha(Color32::WHITE, 25));
                    }
                    if !enabled {
                        painter.rect_filled(br, pal.r, alpha(pal.bg, 120));
                    }
                    painter.text(br.center(), Align2::CENTER_CENTER, button, font(12.5, "semibold"), pal.on_accent);
                    if resp.clicked() {
                        clicked = Some(hit.clone());
                    }
                }
                ui.add_space(2.0);
            }
        });
        clicked
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
