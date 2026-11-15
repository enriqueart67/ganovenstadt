//! Design-System: anpassbares Aussehen, Farben, Schriften, Zeichen-Helfer und Hintergruende.
use eframe::egui::epaint::{Mesh, RectShape, TessellationOptions, Tessellator};
use eframe::egui::{
    self, pos2, vec2, Color32, FontData, FontDefinitions, FontFamily, FontId, Painter, Pos2, Rect, Rgba, Shape,
    Stroke,
};
use serde::{Deserialize, Serialize};

pub type Rgb = [u8; 3];

/// Aktuelle Design-Generation (4 = „Stoop“: fast schwarz, Silber, gedaempftes Eisblau, Hoehenband-Navigation; 3 = Flugkarte).
pub const DESIGN: u32 = 4;

pub const GOOD: Color32 = Color32::from_rgb(52, 199, 120);
pub const BAD: Color32 = Color32::from_rgb(235, 72, 72);
pub const WARN: Color32 = Color32::from_rgb(240, 180, 40);

pub fn c(rgb: Rgb) -> Color32 {
    Color32::from_rgb(rgb[0], rgb[1], rgb[2])
}

// ------------------------------------------------------------------ Einstellbares Aussehen
pub const PRESETS: [&str; 9] = ["Stoop", "Chart", "Night flight", "Paper", "Night", "Graphite", "OLED", "Midnight", "Light"];
pub const BACKGROUNDS: [&str; 7] = ["Atmosphere", "Chart", "None", "Landscape", "Gradient", "Solid", "Image"];
pub const SCENES: [&str; 3] = ["Evening", "Day", "Night"];
pub const FONTS: [&str; 4] = ["B612", "Plex", "Poppins", "Standard"];
pub const ACCENTS: [(&str, Rgb); 12] = [
    ("Ice", [141, 180, 212]),
    ("Chart magenta", [168, 36, 104]),
    ("Chart cyan", [22, 112, 146]),
    ("Signal orange", [255, 79, 0]),
    ("Blue", [79, 140, 255]),
    ("Teal", [45, 200, 180]),
    ("Green", [74, 200, 110]),
    ("Yellow", [245, 196, 30]),
    ("Orange", [250, 140, 50]),
    ("Red", [240, 90, 90]),
    ("Pink", [240, 110, 175]),
    ("White", [236, 236, 240]),
];

#[derive(Serialize, Deserialize, Clone, PartialEq)]
#[serde(default)]
pub struct Look {
    /// Design-Generation – aeltere gespeicherte Looks werden einmalig auf das neue Design gesetzt.
    pub design: u32,
    pub preset: String,
    pub bg: Rgb,
    pub surface: Rgb,
    pub surface2: Rgb,
    pub border: Rgb,
    pub text: Rgb,
    pub muted: Rgb,
    pub accent: Rgb,
    pub accent2: Rgb,
    pub gradients: bool,
    pub glow: bool,
    pub animations: bool,
    pub rounding: f32,
    pub ui_scale: f32,
    pub font: String,
    pub background: String,
    pub scene: String,
    pub image_path: String,
    pub dim: f32,
    pub title: String,
    pub subtitle: String,
    pub tagline: String,
    pub show_badge: bool,
    pub show_tagline: bool,
    pub show_logo: bool,
}

impl Default for Look {
    fn default() -> Self {
        let mut l = Self {
            design: DESIGN,
            preset: String::new(),
            bg: [0; 3],
            surface: [0; 3],
            surface2: [0; 3],
            border: [0; 3],
            text: [0; 3],
            muted: [0; 3],
            accent: ACCENTS[0].1,
            accent2: [201, 211, 221],
            gradients: false,
            glow: false,
            animations: true,
            rounding: 2.0,
            ui_scale: 1.0,
            font: "B612".into(),
            background: "Atmosphere".into(),
            scene: "Night".into(),
            image_path: String::new(),
            dim: 0.25,
            title: "PEREGRINE".into(),
            subtitle: "CLIENT".into(),
            tagline: "Minecraft launcher  ·  Fabric & Quilt  ·  Java included".into(),
            show_badge: false,
            show_tagline: true,
            show_logo: false,
        };
        l.apply_preset("Stoop");
        l
    }
}

impl Look {
    /// Settings saved by the earlier German version stored the German option names – map them over.
    pub fn migrate(&mut self) {
        let map = |v: &mut String, pairs: &[(&str, &str)]| {
            if let Some((_, en)) = pairs.iter().find(|(de, _)| *de == v.as_str()) {
                *v = (*en).to_string();
            }
        };
        map(&mut self.preset, &[("Papier", "Paper"), ("Nacht", "Night"), ("Graphit", "Graphite"), ("Mitternacht", "Midnight"), ("Hell", "Light")]);
        map(&mut self.background, &[("Keine", "None"), ("Landschaft", "Landscape"), ("Verlauf", "Gradient"), ("Einfarbig", "Solid"), ("Bild", "Image")]);
        map(&mut self.scene, &[("Abend", "Evening"), ("Tag", "Day"), ("Nacht", "Night")]);
        map(&mut self.tagline, &[("Minecraft-Launcher  ·  Fabric & Quilt  ·  Java inklusive", "Minecraft launcher  ·  Fabric & Quilt  ·  Java included")]);
    }

    pub fn apply_preset(&mut self, name: &str) {
        let (bg, s, s2, b, t, m): (Rgb, Rgb, Rgb, Rgb, Rgb, Rgb) = match name {
            // Peregrine-Design: warmes Papier bzw. tiefe Nacht, Flaechen = Hintergrund (nur Haarlinien)
            // Flugkarte: leicht gruenliches Kartenpapier mit Navy-Tinte bzw. Nachtflug-Cockpit
            // Stoop: fast schwarz, tiefe Anthrazit-Flaechen, silberne Schrift
            "Stoop" => ([8, 9, 12], [17, 19, 25], [23, 26, 33], [34, 38, 47], [228, 231, 236], [122, 128, 140]),
            "Chart" => ([236, 233, 222], [236, 233, 222], [225, 221, 207], [199, 194, 176], [27, 41, 51], [104, 112, 110]),
            "Night flight" => ([13, 19, 22], [13, 19, 22], [22, 31, 35], [40, 52, 56], [226, 222, 207], [127, 140, 138]),
            "Paper" => ([243, 241, 236], [243, 241, 236], [231, 228, 221], [210, 206, 197], [17, 17, 16], [112, 108, 100]),
            "Night" => ([14, 14, 13], [14, 14, 13], [29, 28, 26], [46, 45, 42], [237, 235, 230], [140, 136, 128]),
            "OLED" => ([0, 0, 0], [10, 10, 11], [20, 20, 22], [32, 32, 36], [240, 240, 242], [130, 130, 138]),
            "Midnight" => ([10, 14, 26], [16, 22, 40], [24, 32, 56], [34, 44, 74], [230, 234, 245], [136, 146, 176]),
            "Light" => ([244, 244, 246], [255, 255, 255], [236, 236, 240], [222, 222, 228], [20, 20, 24], [105, 105, 115]),
            _ => ([17, 17, 19], [24, 24, 27], [33, 33, 37], [42, 42, 47], [237, 237, 239], [139, 139, 147]),
        };
        (self.bg, self.surface, self.surface2, self.border, self.text, self.muted) = (bg, s, s2, b, t, m);
        self.preset = name.into();
    }
}

/// Fertige Farbpalette fuer das Zeichnen (aus `Look` berechnet).
pub struct Pal {
    pub bg: Color32,
    pub surface: Color32,
    pub surface2: Color32,
    pub border: Color32,
    pub text: Color32,
    pub muted: Color32,
    pub accent: Color32,
    pub accent2: Color32,
    pub on_accent: Color32,
    pub r: f32,
    pub grad: bool,
    pub light: bool,
    /// Hintergrund "Chart": die Mod legt im Spiel ihre Hoehenlinien unter die Menues.
    pub chart: bool,
}

pub fn luminance(c: Color32) -> f32 {
    (0.2126 * c.r() as f32 + 0.7152 * c.g() as f32 + 0.0722 * c.b() as f32) / 255.0
}

impl Pal {
    pub fn from(l: &Look) -> Self {
        let accent = c(l.accent);
        Self {
            bg: c(l.bg),
            surface: c(l.surface),
            surface2: c(l.surface2),
            border: c(l.border),
            text: c(l.text),
            muted: c(l.muted),
            accent,
            accent2: c(l.accent2),
            on_accent: if luminance(accent) > 0.62 { Color32::from_rgb(16, 16, 20) } else { Color32::WHITE },
            r: l.rounding,
            grad: l.gradients,
            light: luminance(c(l.bg)) > 0.5,
            chart: l.background == "Chart",
        }
    }
    /// Akzentflaeche: einfarbig oder (optional) mit Verlauf.
    pub fn fill(&self, p: &Painter, rect: Rect, r: f32) {
        if self.grad {
            gradient_rrect(p, rect, r, self.accent, self.accent2);
        } else {
            p.rect_filled(rect, r, self.accent);
        }
    }
    /// Primaerknopf im Peregrine-Stil: Tinte (Textfarbe), beim Ueberfahren Signalfarbe.
    pub fn primary(&self, p: &Painter, rect: Rect, hover: bool, label: &str) {
        p.rect_filled(rect, self.r, if hover { self.accent } else { self.text });
        p.text(rect.center(), egui::Align2::CENTER_CENTER, label.to_uppercase(), font(11.5, "monom"), if hover { Color32::WHITE } else { self.bg });
    }
}

// ------------------------------------------------------------------ Schriften & Stil
pub fn font(size: f32, weight: &str) -> FontId {
    match weight {
        "regular" => FontId::proportional(size),
        w => FontId::new(size, FontFamily::Name(w.into())),
    }
}

/// Schriften: "Plex" (Peregrine-Design: IBM Plex Sans Condensed + Plex Mono), "Poppins" oder "Standard".
/// Familien: regular/medium/semibold/bold, "display" (grosse Titel), "mono"/"monom" (Beschriftungen).
pub fn setup_fonts(ctx: &egui::Context, which: &str) {
    let mut fonts = FontDefinitions::default();
    let base: Vec<String> = fonts.families[&FontFamily::Proportional].clone();
    let mut add = |name: &str, data: &'static [u8]| {
        fonts.font_data.insert(name.into(), FontData::from_static(data));
    };
    add("plex-regular", include_bytes!("../assets/fonts/IBMPlexSansCondensed-Regular.ttf"));
    add("plex-medium", include_bytes!("../assets/fonts/IBMPlexSansCondensed-Medium.ttf"));
    add("plex-semibold", include_bytes!("../assets/fonts/IBMPlexSansCondensed-SemiBold.ttf"));
    add("plex-bold", include_bytes!("../assets/fonts/IBMPlexSansCondensed-Bold.ttf"));
    add("plexmono-regular", include_bytes!("../assets/fonts/IBMPlexMono-Regular.ttf"));
    add("plexmono-medium", include_bytes!("../assets/fonts/IBMPlexMono-Medium.ttf"));
    add("b612-regular", include_bytes!("../assets/fonts/B612-Regular.ttf"));
    add("b612-bold", include_bytes!("../assets/fonts/B612-Bold.ttf"));
    add("b612mono-regular", include_bytes!("../assets/fonts/B612Mono-Regular.ttf"));
    add("b612mono-bold", include_bytes!("../assets/fonts/B612Mono-Bold.ttf"));
    add("poppins-regular", include_bytes!("../assets/fonts/Poppins-Regular.ttf"));
    add("poppins-medium", include_bytes!("../assets/fonts/Poppins-Medium.ttf"));
    add("poppins-semibold", include_bytes!("../assets/fonts/Poppins-SemiBold.ttf"));
    add("poppins-bold", include_bytes!("../assets/fonts/Poppins-Bold.ttf"));
    let fam = |first: Option<&str>| -> Vec<String> {
        let mut v = base.clone();
        if let Some(f) = first {
            v.insert(0, f.to_string());
        }
        v
    };
    let prefix = match which {
        "B612" => Some("b612"),
        "Poppins" => Some("poppins"),
        "Standard" => None,
        _ => Some("plex"),
    };
    for key in ["regular", "medium", "semibold", "bold"] {
        let f = match (which, key) {
            ("B612", "regular" | "medium") => Some("b612-regular".to_string()),
            ("B612", _) => Some("b612-bold".to_string()),
            _ => prefix.map(|p| format!("{p}-{key}")),
        };
        if key == "regular" {
            fonts.families.insert(FontFamily::Proportional, fam(f.as_deref()));
        } else {
            fonts.families.insert(FontFamily::Name(key.into()), fam(f.as_deref()));
        }
    }
    let display = prefix.map(|p| format!("{p}-bold"));
    fonts.families.insert(FontFamily::Name("display".into()), fam(display.as_deref()));
    let (mono, monom) = if which == "B612" { ("b612mono-regular", "b612mono-bold") } else { ("plexmono-regular", "plexmono-medium") };
    fonts.families.insert(FontFamily::Name("mono".into()), fam(Some(mono)));
    fonts.families.insert(FontFamily::Name("monom".into()), fam(Some(monom)));
    fonts.families.get_mut(&FontFamily::Monospace).unwrap().insert(0, mono.into());
    ctx.set_fonts(fonts);
}

pub fn apply_style(ctx: &egui::Context, pal: &Pal) {
    let mut style = (*ctx.style()).clone();
    use egui::TextStyle::*;
    style.text_styles = [
        (Heading, font(24.0, "bold")),
        (Body, FontId::proportional(15.0)),
        (Button, font(14.5, "medium")),
        (Small, FontId::proportional(11.5)),
        (Monospace, FontId::monospace(12.5)),
    ]
    .into();
    style.spacing.item_spacing = vec2(10.0, 10.0);
    style.spacing.button_padding = vec2(14.0, 7.0);
    style.spacing.interact_size.y = 30.0;
    style.spacing.slider_width = 300.0;
    style.spacing.scroll.bar_width = 6.0;
    style.spacing.slider_rail_height = 6.0;
    let v = &mut style.visuals;
    *v = if pal.light { egui::Visuals::light() } else { egui::Visuals::dark() };
    let r = pal.r.min(12.0);
    v.panel_fill = pal.bg;
    v.window_fill = pal.surface;
    v.extreme_bg_color = pal.surface2;
    v.faint_bg_color = pal.surface;
    v.code_bg_color = pal.surface2;
    v.override_text_color = Some(pal.text);
    v.selection.bg_fill = pal.accent;
    v.selection.stroke = Stroke::new(1.0_f32, pal.on_accent);
    v.slider_trailing_fill = true;
    v.handle_shape = egui::style::HandleShape::Rect { aspect_ratio: 0.45 };
    v.hyperlink_color = pal.accent;
    v.window_rounding = (pal.r + 4.0).into();
    v.menu_rounding = r.into();
    v.window_stroke = Stroke::new(1.0_f32, pal.border);
    v.window_shadow = egui::epaint::Shadow { offset: vec2(0.0, 8.0), blur: 24.0, spread: 0.0, color: alpha(Color32::BLACK, 90) };
    v.popup_shadow = v.window_shadow;
    let hover = lerp_color(pal.surface2, pal.text, 0.08);
    for w in [&mut v.widgets.inactive, &mut v.widgets.hovered, &mut v.widgets.active, &mut v.widgets.noninteractive, &mut v.widgets.open] {
        w.rounding = r.into();
        w.fg_stroke = Stroke::new(1.0_f32, pal.text);
    }
    v.widgets.noninteractive.bg_stroke = Stroke::new(1.0_f32, pal.border);
    v.widgets.noninteractive.bg_fill = pal.surface;
    // Knoepfe: nur feine Umrandung, beim Ueberfahren leicht gefuellt (klar, ruhig)
    v.extreme_bg_color = lerp_color(pal.bg, pal.surface2, 0.45);
    v.widgets.inactive.bg_fill = pal.surface2;
    v.widgets.inactive.weak_bg_fill = Color32::TRANSPARENT;
    v.widgets.inactive.bg_stroke = Stroke::new(1.0_f32, pal.border);
    v.widgets.hovered.bg_fill = hover;
    v.widgets.hovered.weak_bg_fill = pal.surface2;
    v.widgets.hovered.bg_stroke = Stroke::new(1.0_f32, pal.text);
    v.widgets.active.bg_fill = pal.accent;
    v.widgets.active.weak_bg_fill = pal.accent;
    v.widgets.active.bg_stroke = Stroke::new(1.0_f32, pal.accent);
    v.widgets.active.fg_stroke = Stroke::new(1.0_f32, pal.on_accent);
    v.widgets.open.bg_fill = pal.surface2;
    v.widgets.open.weak_bg_fill = pal.surface2;
    ctx.set_style(style);
}

// ------------------------------------------------------------------ Helfer
pub fn lerp_color(a: Color32, b: Color32, t: f32) -> Color32 {
    let t = t.clamp(0.0, 1.0);
    (Rgba::from(a) * (1.0 - t) + Rgba::from(b) * t).into()
}

pub fn alpha(c: Color32, a: u8) -> Color32 {
    Color32::from_rgba_unmultiplied(c.r(), c.g(), c.b(), a)
}

/// Abgerundetes Rechteck mit horizontalem Farbverlauf.
pub fn gradient_rrect(p: &Painter, rect: Rect, rounding: f32, a: Color32, b: Color32) {
    let ppp = p.ctx().pixels_per_point();
    let mut tess = Tessellator::new(ppp, TessellationOptions::default(), [1, 1], vec![]);
    let mut mesh = Mesh::default();
    tess.tessellate_rect(&RectShape::filled(rect, rounding, Color32::WHITE), &mut mesh);
    for v in &mut mesh.vertices {
        let t = ((v.pos.x - rect.left()) / rect.width().max(1.0)).clamp(0.0, 1.0);
        let al = v.color.a() as f32 / 255.0;
        v.color = lerp_color(a, b, t).linear_multiply(al);
    }
    p.add(Shape::mesh(mesh));
}

/// Senkrechter Verlauf mit mehreren Farbstopps.
pub fn vertical_gradient(p: &Painter, rect: Rect, stops: &[(f32, Color32)]) {
    let mut mesh = Mesh::default();
    for (i, (t, c)) in stops.iter().enumerate() {
        let y = rect.top() + rect.height() * t;
        mesh.colored_vertex(pos2(rect.left(), y), *c);
        mesh.colored_vertex(pos2(rect.right(), y), *c);
        if i > 0 {
            let b = (i as u32) * 2;
            mesh.add_triangle(b - 2, b - 1, b);
            mesh.add_triangle(b - 1, b, b + 1);
        }
    }
    p.add(Shape::mesh(mesh));
}

/// Weicher, runder Lichtschein (radialer Verlauf).
pub fn radial_glow(p: &Painter, c: Pos2, radius: f32, color: Color32, max_alpha: f32) {
    const SEG: u32 = 72;
    const RINGS: u32 = 16;
    let mut mesh = Mesh::default();
    mesh.colored_vertex(c, alpha(color, max_alpha as u8));
    for k in 1..=RINGS {
        let t = k as f32 / RINGS as f32;
        let a = (max_alpha * (1.0 - t).powf(2.2)) as u8;
        for i in 0..SEG {
            let ang = i as f32 / SEG as f32 * std::f32::consts::TAU;
            mesh.colored_vertex(pos2(c.x + ang.cos() * radius * t, c.y + ang.sin() * radius * t), alpha(color, a));
        }
    }
    let idx = |k: u32, i: u32| 1 + (k - 1) * SEG + (i % SEG);
    for i in 0..SEG {
        mesh.add_triangle(0, idx(1, i), idx(1, i + 1));
    }
    for k in 2..=RINGS {
        for i in 0..SEG {
            mesh.add_triangle(idx(k - 1, i), idx(k, i), idx(k, i + 1));
            mesh.add_triangle(idx(k - 1, i), idx(k, i + 1), idx(k - 1, i + 1));
        }
    }
    p.add(Shape::mesh(mesh));
}


/// Ein-/Aus-Schalter (flach: Spur + Knopf).
pub fn toggle(ui: &mut egui::Ui, pal: &Pal, on: &mut bool) -> egui::Response {
    let (rect, mut resp) = ui.allocate_exact_size(vec2(36.0, 18.0), egui::Sense::click());
    if resp.clicked() {
        *on = !*on;
        resp.mark_changed();
    }
    let t = ui.ctx().animate_bool_with_time(resp.id, *on, 0.12);
    let p = ui.painter();
    let r = pal.r.min(9.0);
    p.rect_filled(rect, r, lerp_color(pal.surface2, pal.accent, t));
    if t < 1.0 {
        p.rect_stroke(rect, r, Stroke::new(1.0_f32, alpha(pal.border, (255.0 * (1.0 - t)) as u8)));
    }
    let x = egui::lerp(rect.left() + 9.0..=rect.right() - 9.0, t);
    let knob = Rect::from_center_size(pos2(x, rect.center().y), vec2(12.0, 12.0));
    p.rect_filled(knob, (r - 2.0).max(0.0), if t > 0.5 { Color32::WHITE } else { pal.text });
    resp
}

// ------------------------------------------------------------------ Icons (Vektor)
pub enum Icon {
    Close,
    Minimize,
    Maximize,
    Chevron,
}

pub fn icon(p: &Painter, c: Pos2, s: f32, color: Color32, kind: Icon) {
    let st = Stroke::new(s * 0.09, color);
    let pt = |x: f32, y: f32| pos2(c.x + x * s, c.y + y * s);
    match kind {
        Icon::Close => {
            p.line_segment([pt(-0.28, -0.28), pt(0.28, 0.28)], st);
            p.line_segment([pt(0.28, -0.28), pt(-0.28, 0.28)], st);
        }
        Icon::Minimize => {
            p.line_segment([pt(-0.3, 0.0), pt(0.3, 0.0)], st);
        }
        Icon::Maximize => {
            p.rect_stroke(Rect::from_min_max(pt(-0.26, -0.26), pt(0.26, 0.26)), 1.0, st);
        }
        Icon::Chevron => {
            p.add(Shape::line(vec![pt(-0.28, -0.12), pt(0.0, 0.14), pt(0.28, -0.12)], st));
        }
    }
}

// ------------------------------------------------------------------ Landschaft
fn hash(i: u32) -> f32 {
    let mut x = i.wrapping_mul(747_796_405).wrapping_add(2_891_336_453);
    x = ((x >> ((x >> 28) + 4)) ^ x).wrapping_mul(277_803_737);
    ((x >> 22) ^ x) as f32 / u32::MAX as f32
}

fn noise(x: f32) -> f32 {
    x.sin() * 0.5 + (x * 2.3 + 1.7).sin() * 0.3 + (x * 5.1 + 0.3).sin() * 0.2
}

struct Scene {
    sky: [(f32, Color32); 4],
    stars: f32,
    sun: Color32,
    sun_y: f32,
    sun_glow: Color32,
    cloud: Color32,
    layers: [(Color32, Color32); 3],
}

fn scene(name: &str) -> Scene {
    let rgb = Color32::from_rgb;
    match name {
        "Day" => Scene {
            sky: [(0.0, rgb(70, 140, 220)), (0.45, rgb(130, 185, 240)), (0.8, rgb(200, 228, 250)), (1.0, rgb(220, 238, 252))],
            stars: 0.0,
            sun: rgb(255, 248, 210),
            sun_y: 0.22,
            sun_glow: rgb(255, 250, 220),
            cloud: Color32::from_rgba_unmultiplied(255, 255, 255, 70),
            layers: [
                (rgb(126, 160, 200), rgb(150, 182, 215)),
                (rgb(78, 140, 82), rgb(112, 178, 96)),
                (rgb(46, 98, 52), rgb(86, 150, 70)),
            ],
        },
        "Evening" => Scene {
            sky: [(0.0, rgb(28, 22, 48)), (0.4, rgb(70, 46, 100)), (0.75, rgb(190, 104, 112)), (1.0, rgb(238, 160, 112))],
            stars: 0.6,
            sun: rgb(255, 222, 170),
            sun_y: 0.58,
            sun_glow: rgb(255, 170, 130),
            cloud: Color32::from_rgba_unmultiplied(255, 220, 230, 22),
            layers: [
                (rgb(72, 46, 96), rgb(118, 74, 120)),
                (rgb(44, 28, 64), rgb(90, 52, 96)),
                (rgb(22, 16, 34), rgb(60, 36, 70)),
            ],
        },
        _ => Scene {
            sky: [(0.0, rgb(6, 9, 20)), (0.45, rgb(14, 22, 46)), (0.8, rgb(28, 42, 80)), (1.0, rgb(36, 52, 92))],
            stars: 1.0,
            sun: rgb(226, 232, 250),
            sun_y: 0.26,
            sun_glow: rgb(150, 170, 230),
            cloud: Color32::from_rgba_unmultiplied(170, 190, 240, 16),
            layers: [
                (rgb(26, 36, 66), rgb(40, 54, 92)),
                (rgb(16, 24, 46), rgb(32, 44, 76)),
                (rgb(9, 13, 26), rgb(22, 30, 52)),
            ],
        },
    }
}

fn paint_layer(p: &Painter, r: Rect, t: f32, block: f32, base: f32, amp: f32, speed: f32, seed: f32, trees: bool, (color, rim): (Color32, Color32)) {
    let off = t * speed;
    let first = (off / block).floor() as i64;
    let shift = off - first as f32 * block;
    let cols = (r.width() / block) as i64 + 3;
    for k in 0..cols {
        let col = first + k;
        let x = r.left() + k as f32 * block - shift;
        let n = noise(col as f32 * 0.07 + seed);
        let mut y = r.top() + r.height() * (base - amp * n);
        y = r.top() + ((y - r.top()) / block).round() * block;
        p.rect_filled(Rect::from_min_max(pos2(x, y), pos2(x + block + 0.6, r.bottom())), 0.0, color);
        p.rect_filled(Rect::from_min_max(pos2(x, y), pos2(x + block + 0.6, y + block * 0.3)), 0.0, rim);
        if trees && hash((col as u32) ^ 0x9e37_79b9) > 0.86 {
            let b = block / 2.0;
            let tx = x + block / 2.0 - b / 2.0;
            p.rect_filled(Rect::from_min_max(pos2(tx, y - b * 3.0), pos2(tx + b, y)), 0.0, color);
            p.rect_filled(Rect::from_min_max(pos2(tx - b * 1.5, y - b * 6.0), pos2(tx + b * 2.5, y - b * 2.5)), 0.0, color);
            p.rect_filled(Rect::from_min_max(pos2(tx - b * 0.5, y - b * 7.5), pos2(tx + b * 1.5, y - b * 6.0)), 0.0, color);
            p.rect_filled(Rect::from_min_max(pos2(tx - b * 1.5, y - b * 6.0), pos2(tx + b * 2.5, y - b * 5.6)), 0.0, rim);
        }
    }
}

pub fn paint_landscape(p: &Painter, r: Rect, t: f32, scene_name: &str) {
    let s = scene(scene_name);
    vertical_gradient(p, r, &s.sky);
    let (w, h) = (r.width(), r.height());
    if s.stars > 0.0 {
        for i in 0..110u32 {
            let x = r.left() + hash(i) * w;
            let y = r.top() + hash(i + 7000) * h * 0.5;
            let tw = ((t * (0.8 + hash(i + 300) * 1.5) + i as f32).sin() * 0.5 + 0.5) * (1.0 - (y - r.top()) / (h * 0.5));
            let sz = if hash(i + 99) > 0.85 { 2.0 } else { 1.2 };
            let a = (20.0 + tw * 170.0) * s.stars;
            p.rect_filled(Rect::from_center_size(pos2(x, y), vec2(sz, sz)), 0.0, alpha(Color32::WHITE, a as u8));
        }
    }
    let sun = pos2(r.left() + w * 0.72, r.top() + h * s.sun_y);
    radial_glow(p, sun, 230.0, s.sun_glow, 70.0);
    p.rect_filled(Rect::from_center_size(sun, vec2(48.0, 48.0)), 0.0, s.sun);
    for k in 0..6u32 {
        let speed = 5.0 + hash(k + 40) * 8.0;
        let span = w + 400.0;
        let x = r.left() - 200.0 + (hash(k) * span + t * speed).rem_euclid(span);
        let y = r.top() + h * (0.04 + 0.12 * hash(k + 20));
        let sz = 9.0 + hash(k + 60) * 5.0;
        p.rect_filled(Rect::from_min_size(pos2(x, y), vec2(sz * 12.0, sz * 2.0)), 0.0, s.cloud);
        p.rect_filled(Rect::from_min_size(pos2(x + sz * 2.0, y - sz * 1.5), vec2(sz * 6.0, sz * 1.5)), 0.0, s.cloud);
    }
    paint_layer(p, r, t, 12.0, 0.60, 0.13, 3.0, 3.1, false, s.layers[0]);
    paint_layer(p, r, t, 16.0, 0.72, 0.10, 7.0, 8.7, true, s.layers[1]);
    paint_layer(p, r, t, 22.0, 0.84, 0.06, 14.0, 1.9, true, s.layers[2]);
}

// ------------------------------------------------------------------ Logo
/// Laedt das Icon (Falke im Sturzflug, schwarzes Abzeichen) als Textur mit Mipmaps.
pub fn load_logo(ctx: &egui::Context) -> egui::TextureHandle {
    let img = image::load_from_memory(include_bytes!("../assets/logo/logo.png")).expect("Logo").to_rgba8();
    let ci = egui::ColorImage::from_rgba_unmultiplied([img.width() as usize, img.height() as usize], img.as_raw());
    let opts = egui::TextureOptions { mipmap_mode: Some(egui::TextureFilter::Linear), ..egui::TextureOptions::LINEAR };
    ctx.load_texture("logo", ci, opts)
}

/// Zeichnet das Icon (bringt sein eigenes schwarzes Abzeichen mit).
pub fn logo(p: &Painter, _pal: &Pal, rect: Rect, tex: &egui::TextureHandle) {
    p.image(tex.id(), rect, Rect::from_min_max(pos2(0.0, 0.0), pos2(1.0, 1.0)), Color32::WHITE);
}

// ------------------------------------------------------------------ Flugkarte (Design 3)
/// Wegpunkt-Symbol wie auf Luftfahrtkarten (VOR-Sechseck mit Punkt).
pub fn waypoint(p: &Painter, c: Pos2, r: f32, color: Color32, filled: bool) {
    let pts: Vec<Pos2> = (0..6).map(|i| {
        let a = std::f32::consts::PI / 3.0 * i as f32;
        pos2(c.x + r * a.cos(), c.y + r * a.sin())
    }).collect();
    if filled {
        p.add(Shape::convex_polygon(pts, color, Stroke::NONE));
    } else {
        p.add(Shape::closed_line(pts, Stroke::new(1.3_f32, color)));
        p.circle_filled(c, (r * 0.28).max(1.2), color);
    }
}

fn vnoise(x: f32, y: f32, seed: u32) -> f32 {
    let (xi, yi) = (x.floor() as i32, y.floor() as i32);
    let (xf, yf) = (x - x.floor(), y - y.floor());
    let h = |i: i32, j: i32| hash((i.wrapping_mul(73856093) ^ j.wrapping_mul(19349663)) as u32 ^ seed.wrapping_mul(83492791));
    let s = |t: f32| t * t * (3.0 - 2.0 * t);
    let (u, v) = (s(xf), s(yf));
    let a = h(xi, yi) + (h(xi + 1, yi) - h(xi, yi)) * u;
    let b = h(xi, yi + 1) + (h(xi + 1, yi + 1) - h(xi, yi + 1)) * u;
    a + (b - a) * v
}

/// Gelaende-Hoehe 0..1 (fraktales Rauschen) fuer die Hoehenlinien.
fn terrain(x: f32, y: f32) -> f32 {
    let mut v = 0.0;
    let mut amp = 0.55;
    let mut f = 1.0;
    for o in 0..4 {
        v += vnoise(x * f, y * f, 11 + o) * amp;
        amp *= 0.5;
        f *= 2.03;
    }
    v
}

struct ChartCache {
    size: (u32, u32),
    lines: Vec<(Pos2, Pos2, bool)>, // relativ zur linken oberen Ecke; true = Zaehllinie (staerker)
    labels: Vec<(Pos2, String)>,
}

static CHART: std::sync::Mutex<Option<ChartCache>> = std::sync::Mutex::new(None);

/// Hoehenlinien per Marching Squares – einmal pro Fenstergroesse berechnet.
fn contours(w: f32, h: f32) -> ChartCache {
    let cell = 12.0_f32;
    let (nx, ny) = ((w / cell) as usize + 2, (h / cell) as usize + 2);
    let scale = 1.0 / 260.0;
    let mut grid = vec![0.0_f32; nx * ny];
    for j in 0..ny {
        for i in 0..nx {
            grid[j * nx + i] = terrain(i as f32 * cell * scale + 3.1, j as f32 * cell * scale + 7.7);
        }
    }
    let levels = 16;
    let mut lines = Vec::new();
    let mut labels = Vec::new();
    for l in 1..levels {
        let iso = l as f32 / levels as f32 * 0.95 + 0.05;
        let index = l % 4 == 0;
        let mut labelled = false;
        for j in 0..ny - 1 {
            for i in 0..nx - 1 {
                let v = [grid[j * nx + i], grid[j * nx + i + 1], grid[(j + 1) * nx + i + 1], grid[(j + 1) * nx + i]];
                let mut code = 0;
                for (k, val) in v.iter().enumerate() {
                    if *val > iso {
                        code |= 1 << k;
                    }
                }
                if code == 0 || code == 15 {
                    continue;
                }
                let (x0, y0) = (i as f32 * cell, j as f32 * cell);
                let t = |a: f32, b: f32| ((iso - a) / (b - a)).clamp(0.0, 1.0);
                let top = pos2(x0 + cell * t(v[0], v[1]), y0);
                let right = pos2(x0 + cell, y0 + cell * t(v[1], v[2]));
                let bottom = pos2(x0 + cell * (1.0 - t(v[2], v[3])), y0 + cell);
                let left = pos2(x0, y0 + cell * (1.0 - t(v[3], v[0])));
                let segs: &[(Pos2, Pos2)] = &match code {
                    1 | 14 => [(left, top), (left, top)],
                    2 | 13 => [(top, right), (top, right)],
                    3 | 12 => [(left, right), (left, right)],
                    4 | 11 => [(right, bottom), (right, bottom)],
                    5 => [(left, top), (right, bottom)],
                    6 | 9 => [(top, bottom), (top, bottom)],
                    7 | 8 => [(left, bottom), (left, bottom)],
                    10 => [(top, right), (left, bottom)],
                    _ => continue,
                };
                for (a, b) in segs.iter() {
                    lines.push((*a, *b, index));
                }
                if index && !labelled && i > 6 && j > 6 && (i + j * 7 + l * 13) % 97 == 0 {
                    labels.push((pos2(x0, y0), format!("{}", 400 + l * 150)));
                    labelled = true;
                }
            }
        }
    }
    lines.dedup();
    ChartCache { size: (w as u32, h as u32), lines, labels }
}

/// Hintergrund „Flugkarte“: Hoehenlinien, Koordinatengitter mit Grad-Beschriftung, eine Flugroute mit
/// Wegpunkten und eine Kompassrose. Alles sehr zurueckhaltend, damit Text darueber gut lesbar bleibt.
pub fn paint_chart(p: &Painter, r: Rect, pal: &Pal) {
    let light = pal.light;
    let contour = if light { Color32::from_rgb(150, 118, 72) } else { Color32::from_rgb(110, 140, 128) };
    let grid = pal.accent2;
    {
        let mut cache = CHART.lock().unwrap();
        let size = (r.width() as u32, r.height() as u32);
        if cache.as_ref().map_or(true, |c| c.size != size) {
            *cache = Some(contours(r.width(), r.height()));
        }
        let c = cache.as_ref().unwrap();
        let thin = Stroke::new(1.0_f32, alpha(contour, if light { 34 } else { 26 }));
        let thick = Stroke::new(1.2_f32, alpha(contour, if light { 62 } else { 46 }));
        let shapes: Vec<Shape> = c.lines.iter().map(|(a, b, idx)| Shape::line_segment([r.min + a.to_vec2(), r.min + b.to_vec2()], if *idx { thick } else { thin })).collect();
        p.extend(shapes);
        for (at, txt) in &c.labels {
            p.text(r.min + at.to_vec2(), egui::Align2::CENTER_CENTER, txt, font(9.0, "mono"), alpha(contour, if light { 110 } else { 80 }));
        }
    }
    // Koordinatengitter (Breiten-/Laengengrade) mit Randbeschriftung
    let step = 168.0;
    let gs = Stroke::new(1.0_f32, alpha(grid, if light { 26 } else { 30 }));
    let mut x = r.left() + 96.0;
    let mut lon = 8.0_f32;
    while x < r.right() {
        p.line_segment([pos2(x, r.top()), pos2(x, r.bottom())], gs);
        p.text(pos2(x + 4.0, r.bottom() - 6.0), egui::Align2::LEFT_BOTTOM, format!("{:03.0}°{:02.0}′E", lon.floor(), (lon.fract() * 60.0).round()), font(9.0, "mono"), alpha(grid, 90));
        x += step;
        lon += 0.5;
    }
    let mut y = r.top() + 120.0;
    let mut lat = 47.5_f32;
    while y < r.bottom() {
        p.line_segment([pos2(r.left(), y), pos2(r.right(), y)], gs);
        p.text(pos2(r.right() - 6.0, y - 3.0), egui::Align2::RIGHT_BOTTOM, format!("{:02.0}°{:02.0}′N", lat.floor(), (lat.fract() * 60.0).round()), font(9.0, "mono"), alpha(grid, 90));
        // kleine Minuten-Striche am linken Rand
        for k in 1..6 {
            let ty = y + step * k as f32 / 6.0;
            p.line_segment([pos2(r.left(), ty), pos2(r.left() + 5.0, ty)], gs);
        }
        y += step;
        lat -= 0.5;
    }
    // Flugroute: gestrichelt, Magenta, mit drei Wegpunkten
    let route = [
        pos2(r.left() + r.width() * 0.06, r.bottom() - r.height() * 0.18),
        pos2(r.left() + r.width() * 0.38, r.top() + r.height() * 0.58),
        pos2(r.left() + r.width() * 0.71, r.top() + r.height() * 0.30),
        pos2(r.right() - r.width() * 0.03, r.top() + r.height() * 0.12),
    ];
    let rc = alpha(pal.accent, if light { 70 } else { 90 });
    for w in route.windows(2) {
        let (a, b) = (w[0], w[1]);
        let len = (b - a).length();
        let dir = (b - a) / len;
        let mut d = 0.0;
        while d < len {
            let e = (d + 9.0).min(len);
            p.line_segment([a + dir * d, a + dir * e], Stroke::new(1.4_f32, rc));
            d += 15.0;
        }
        // Kurs in Grad auf halber Strecke
        let mid = a + (b - a) * 0.5;
        let hdg = ((dir.x.atan2(-dir.y).to_degrees() + 360.0) % 360.0).round();
        p.text(mid + vec2(8.0, -10.0), egui::Align2::LEFT_BOTTOM, format!("{hdg:03.0}°"), font(9.5, "mono"), rc);
    }
    for (i, (pt, ident)) in route.iter().zip(["PRGN", "FALKE", "KSTRL", "HOBBY"]).enumerate() {
        if i == 0 || i == 3 {
            continue;
        }
        waypoint(p, *pt, 6.0, rc, false);
        p.text(*pt + vec2(10.0, 8.0), egui::Align2::LEFT_TOP, ident, font(9.5, "monom"), rc);
    }
    // Kompassrose unten rechts
    let c = pos2(r.right() - 150.0, r.bottom() - 215.0);
    let rad = 92.0;
    let cs = alpha(grid, if light { 60 } else { 70 });
    p.circle_stroke(c, rad, Stroke::new(1.0_f32, cs));
    p.circle_stroke(c, rad * 0.62, Stroke::new(1.0_f32, alpha(grid, 30)));
    for deg in (0..360).step_by(5) {
        let a = (deg as f32).to_radians();
        let dir = vec2(a.sin(), -a.cos());
        let len = if deg % 30 == 0 { 12.0 } else if deg % 10 == 0 { 7.0 } else { 4.0 };
        p.line_segment([c + dir * rad, c + dir * (rad - len)], Stroke::new(1.0_f32, cs));
        if deg % 30 == 0 {
            let label = match deg {
                0 => "N".to_string(),
                90 => "E".to_string(),
                180 => "S".to_string(),
                270 => "W".to_string(),
                d => format!("{}", d / 10),
            };
            p.text(c + dir * (rad - 22.0), egui::Align2::CENTER_CENTER, label, font(9.5, "monom"), cs);
        }
    }
    // Nordpfeil und Missweisung
    p.line_segment([c, c + vec2(0.0, -rad * 0.55)], Stroke::new(1.2_f32, cs));
    let var = 3.0_f32.to_radians();
    p.line_segment([c, c + vec2(var.sin(), -var.cos()) * rad * 0.5], Stroke::new(1.0_f32, alpha(pal.accent, 70)));
    p.text(c + vec2(0.0, rad * 0.30), egui::Align2::CENTER_CENTER, "VAR 3°E", font(8.5, "mono"), cs);
}
