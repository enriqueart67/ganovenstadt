//! Design-System: anpassbares Aussehen, Farben, Schriften, Zeichen-Helfer und Hintergruende.
use eframe::egui::epaint::{Mesh, RectShape, TessellationOptions, Tessellator};
use eframe::egui::{
    self, pos2, vec2, Color32, FontData, FontDefinitions, FontFamily, FontId, Painter, Pos2, Rect, Rgba, Shape,
    Stroke,
};
use serde::{Deserialize, Serialize};

pub type Rgb = [u8; 3];

pub const GOOD: Color32 = Color32::from_rgb(52, 199, 120);
pub const BAD: Color32 = Color32::from_rgb(235, 72, 72);
pub const WARN: Color32 = Color32::from_rgb(240, 180, 40);

pub fn c(rgb: Rgb) -> Color32 {
    Color32::from_rgb(rgb[0], rgb[1], rgb[2])
}

// ------------------------------------------------------------------ Einstellbares Aussehen
pub const PRESETS: [&str; 4] = ["Graphit", "OLED", "Mitternacht", "Hell"];
pub const BACKGROUNDS: [&str; 4] = ["Landschaft", "Verlauf", "Einfarbig", "Bild"];
pub const SCENES: [&str; 3] = ["Abend", "Tag", "Nacht"];
pub const FONTS: [&str; 2] = ["Poppins", "Standard"];
pub const ACCENTS: [(&str, Rgb); 8] = [
    ("Blau", [79, 140, 255]),
    ("Türkis", [45, 200, 180]),
    ("Grün", [74, 200, 110]),
    ("Gelb", [245, 196, 30]),
    ("Orange", [250, 140, 50]),
    ("Rot", [240, 90, 90]),
    ("Pink", [240, 110, 175]),
    ("Weiß", [236, 236, 240]),
];

#[derive(Serialize, Deserialize, Clone, PartialEq)]
#[serde(default)]
pub struct Look {
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
}

impl Default for Look {
    fn default() -> Self {
        let mut l = Self {
            preset: String::new(),
            bg: [0; 3],
            surface: [0; 3],
            surface2: [0; 3],
            border: [0; 3],
            text: [0; 3],
            muted: [0; 3],
            accent: ACCENTS[0].1,
            accent2: [56, 189, 248],
            gradients: false,
            glow: false,
            animations: true,
            rounding: 8.0,
            ui_scale: 1.0,
            font: "Poppins".into(),
            background: "Landschaft".into(),
            scene: "Nacht".into(),
            image_path: String::new(),
            dim: 0.25,
            title: "PEREGRINE".into(),
            subtitle: "CLIENT".into(),
            tagline: "Minecraft-Launcher  ·  Fabric & Quilt  ·  Java inklusive".into(),
            show_badge: true,
            show_tagline: true,
        };
        l.apply_preset("Graphit");
        l
    }
}

impl Look {
    pub fn apply_preset(&mut self, name: &str) {
        let (bg, s, s2, b, t, m): (Rgb, Rgb, Rgb, Rgb, Rgb, Rgb) = match name {
            "OLED" => ([0, 0, 0], [10, 10, 11], [20, 20, 22], [32, 32, 36], [240, 240, 242], [130, 130, 138]),
            "Mitternacht" => ([10, 14, 26], [16, 22, 40], [24, 32, 56], [34, 44, 74], [230, 234, 245], [136, 146, 176]),
            "Hell" => ([244, 244, 246], [255, 255, 255], [236, 236, 240], [222, 222, 228], [20, 20, 24], [105, 105, 115]),
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
    pub glow: bool,
    pub light: bool,
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
            glow: l.glow,
            light: luminance(c(l.bg)) > 0.5,
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
    pub fn shine(&self, p: &Painter, rect: Rect, r: f32, strength: f32) {
        if self.glow {
            glow(p, rect, r, self.accent, strength);
        }
    }
}

// ------------------------------------------------------------------ Schriften & Stil
pub fn font(size: f32, weight: &str) -> FontId {
    match weight {
        "regular" => FontId::proportional(size),
        w => FontId::new(size, FontFamily::Name(w.into())),
    }
}

pub fn setup_fonts(ctx: &egui::Context, poppins: bool) {
    let mut fonts = FontDefinitions::default();
    let base: Vec<String> = fonts.families[&FontFamily::Proportional].clone();
    for (key, data) in [
        ("regular", &include_bytes!("../assets/fonts/Poppins-Regular.ttf")[..]),
        ("medium", &include_bytes!("../assets/fonts/Poppins-Medium.ttf")[..]),
        ("semibold", &include_bytes!("../assets/fonts/Poppins-SemiBold.ttf")[..]),
        ("bold", &include_bytes!("../assets/fonts/Poppins-Bold.ttf")[..]),
    ] {
        let mut fam = base.clone();
        if poppins {
            let name = format!("poppins-{key}");
            fonts.font_data.insert(name.clone(), FontData::from_static(data));
            fam.insert(0, name.clone());
            if key == "regular" {
                fonts.families.get_mut(&FontFamily::Proportional).unwrap().insert(0, name);
            }
        }
        if key != "regular" {
            fonts.families.insert(FontFamily::Name(key.into()), fam);
        }
    }
    ctx.set_fonts(fonts);
}

pub fn apply_style(ctx: &egui::Context, pal: &Pal) {
    let mut style = (*ctx.style()).clone();
    use egui::TextStyle::*;
    style.text_styles = [
        (Heading, font(24.0, "bold")),
        (Body, FontId::proportional(14.0)),
        (Button, font(14.0, "medium")),
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
    v.widgets.inactive.bg_fill = pal.surface2;
    v.widgets.inactive.weak_bg_fill = pal.surface2;
    v.widgets.inactive.bg_stroke = Stroke::new(1.0_f32, pal.border);
    v.widgets.hovered.bg_fill = hover;
    v.widgets.hovered.weak_bg_fill = hover;
    v.widgets.hovered.bg_stroke = Stroke::new(1.0_f32, pal.accent);
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

pub fn glow(p: &Painter, rect: Rect, rounding: f32, color: Color32, strength: f32) {
    for i in (1..=8).rev() {
        let e = i as f32 * 2.5;
        let a = ((9 - i) as f32 * 4.0 * strength) as u8;
        p.rect_filled(rect.expand(e), rounding + e, alpha(color, a));
    }
}

/// Ein-/Aus-Schalter.
pub fn toggle(ui: &mut egui::Ui, pal: &Pal, on: &mut bool) -> egui::Response {
    let (rect, mut resp) = ui.allocate_exact_size(vec2(40.0, 22.0), egui::Sense::click());
    if resp.clicked() {
        *on = !*on;
        resp.mark_changed();
    }
    let t = ui.ctx().animate_bool_with_time(resp.id, *on, 0.12);
    let p = ui.painter();
    p.rect_filled(rect, 11.0, lerp_color(pal.surface2, pal.accent, t));
    p.rect_stroke(rect, 11.0, Stroke::new(1.0_f32, alpha(pal.border, (255.0 * (1.0 - t)) as u8)));
    let x = egui::lerp(rect.left() + 11.0..=rect.right() - 11.0, t);
    let knob = if t > 0.5 { pal.on_accent } else if pal.light { Color32::WHITE } else { pal.text };
    p.circle_filled(pos2(x, rect.center().y), 8.0, knob);
    resp
}

// ------------------------------------------------------------------ Icons (Vektor)
pub enum Icon {
    Home,
    Console,
    Settings,
    Brush,
    Close,
    Minimize,
    Maximize,
    Play,
    Chevron,
}

pub fn icon(p: &Painter, c: Pos2, s: f32, color: Color32, kind: Icon) {
    let st = Stroke::new(s * 0.09, color);
    let pt = |x: f32, y: f32| pos2(c.x + x * s, c.y + y * s);
    match kind {
        Icon::Home => {
            p.add(Shape::line(vec![pt(-0.42, -0.02), pt(0.0, -0.4), pt(0.42, -0.02)], st));
            p.add(Shape::line(vec![pt(-0.3, -0.12), pt(-0.3, 0.38), pt(0.3, 0.38), pt(0.3, -0.12)], st));
        }
        Icon::Console => {
            p.rect_stroke(Rect::from_min_max(pt(-0.44, -0.34), pt(0.44, 0.34)), s * 0.08, st);
            p.add(Shape::line(vec![pt(-0.24, -0.1), pt(-0.08, 0.03), pt(-0.24, 0.16)], st));
            p.line_segment([pt(0.02, 0.16), pt(0.24, 0.16)], st);
        }
        Icon::Settings => {
            for i in 0..8 {
                let a = i as f32 * std::f32::consts::TAU / 8.0;
                let (sn, cs) = a.sin_cos();
                p.line_segment([pt(cs * 0.28, sn * 0.28), pt(cs * 0.44, sn * 0.44)], Stroke::new(s * 0.13, color));
            }
            p.circle_stroke(c, s * 0.29, st);
            p.circle_stroke(c, s * 0.1, st);
        }
        Icon::Brush => {
            p.circle_stroke(c, s * 0.4, st);
            for (x, y) in [(-0.16, -0.16), (0.14, -0.2), (-0.22, 0.12)] {
                p.circle_filled(pt(x, y), s * 0.07, color);
            }
            p.circle_filled(pt(0.14, 0.14), s * 0.1, color);
        }
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
        Icon::Play => {
            p.add(Shape::convex_polygon(vec![pt(-0.28, -0.38), pt(0.4, 0.0), pt(-0.28, 0.38)], color, Stroke::NONE));
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
        "Tag" => Scene {
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
        "Abend" => Scene {
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
