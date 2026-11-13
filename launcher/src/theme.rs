//! Design: Farben, Schriften, Verlaeufe, Icons und die animierte Block-Landschaft.
use eframe::egui::epaint::{Mesh, RectShape, TessellationOptions, Tessellator};
use eframe::egui::{
    self, pos2, vec2, Color32, FontData, FontDefinitions, FontFamily, FontId, Painter, Pos2, Rect, Rgba, Shape,
    Stroke,
};

pub const BG: Color32 = Color32::from_rgb(13, 12, 20);
pub const SURFACE: Color32 = Color32::from_rgb(22, 20, 31);
pub const SURFACE2: Color32 = Color32::from_rgb(31, 28, 44);
pub const BORDER: Color32 = Color32::from_rgb(44, 40, 64);
pub const TEXT: Color32 = Color32::from_rgb(242, 240, 250);
pub const MUTED: Color32 = Color32::from_rgb(142, 137, 166);
pub const ACCENT_A: Color32 = Color32::from_rgb(139, 92, 246);
pub const ACCENT_B: Color32 = Color32::from_rgb(236, 72, 153);
pub const GOOD: Color32 = Color32::from_rgb(52, 211, 153);
pub const BAD: Color32 = Color32::from_rgb(239, 68, 68);
pub const WARN: Color32 = Color32::from_rgb(251, 191, 36);

pub fn font(size: f32, weight: &str) -> FontId {
    match weight {
        "regular" => FontId::proportional(size),
        w => FontId::new(size, FontFamily::Name(w.into())),
    }
}

pub fn setup(ctx: &egui::Context) {
    let mut fonts = FontDefinitions::default();
    let fallback = ["NotoEmoji-Regular", "emoji-icon-font"];
    for (key, data) in [
        ("regular", &include_bytes!("../assets/fonts/Poppins-Regular.ttf")[..]),
        ("medium", &include_bytes!("../assets/fonts/Poppins-Medium.ttf")[..]),
        ("semibold", &include_bytes!("../assets/fonts/Poppins-SemiBold.ttf")[..]),
        ("bold", &include_bytes!("../assets/fonts/Poppins-Bold.ttf")[..]),
    ] {
        let name = format!("poppins-{key}");
        fonts.font_data.insert(name.clone(), FontData::from_static(data));
        if key == "regular" {
            fonts.families.get_mut(&FontFamily::Proportional).unwrap().insert(0, name);
        } else {
            let mut fam = vec![name];
            fam.extend(fallback.iter().map(|s| s.to_string()));
            fonts.families.insert(FontFamily::Name(key.into()), fam);
        }
    }
    ctx.set_fonts(fonts);

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
    style.spacing.button_padding = vec2(14.0, 8.0);
    style.spacing.interact_size.y = 32.0;
    style.spacing.slider_width = 320.0;
    style.spacing.scroll.bar_width = 6.0;
    let v = &mut style.visuals;
    *v = egui::Visuals::dark();
    v.panel_fill = BG;
    v.window_fill = SURFACE;
    v.extreme_bg_color = SURFACE2;
    v.faint_bg_color = SURFACE;
    v.override_text_color = Some(TEXT);
    v.selection.bg_fill = ACCENT_A;
    v.slider_trailing_fill = true;
    v.selection.stroke = Stroke::new(1.0_f32, TEXT);
    v.hyperlink_color = ACCENT_B;
    v.window_rounding = 16.0.into();
    v.menu_rounding = 12.0.into();
    v.window_stroke = Stroke::new(1.0_f32, BORDER);
    for w in [&mut v.widgets.inactive, &mut v.widgets.hovered, &mut v.widgets.active, &mut v.widgets.noninteractive, &mut v.widgets.open] {
        w.rounding = 10.0.into();
    }
    v.widgets.noninteractive.bg_stroke = Stroke::new(1.0_f32, BORDER);
    v.widgets.inactive.bg_fill = SURFACE2;
    v.widgets.inactive.weak_bg_fill = SURFACE2;
    v.widgets.inactive.bg_stroke = Stroke::new(1.0_f32, BORDER);
    v.widgets.hovered.bg_fill = Color32::from_rgb(44, 40, 62);
    v.widgets.hovered.weak_bg_fill = Color32::from_rgb(44, 40, 62);
    v.widgets.hovered.bg_stroke = Stroke::new(1.0_f32, ACCENT_A);
    v.widgets.active.bg_fill = ACCENT_A;
    v.widgets.active.weak_bg_fill = ACCENT_A;
    v.widgets.active.bg_stroke = Stroke::new(1.0_f32, ACCENT_B);
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

/// Weicher, runder Lichtschein (radialer Verlauf, keine Ringe).
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

/// Weicher Leuchtschein um ein Rechteck.
pub fn glow(p: &Painter, rect: Rect, rounding: f32, color: Color32, strength: f32) {
    for i in (1..=8).rev() {
        let e = i as f32 * 2.5;
        let a = ((9 - i) as f32 * 4.0 * strength) as u8;
        p.rect_filled(rect.expand(e), rounding + e, alpha(color, a));
    }
}

/// Ein-/Aus-Schalter.
pub fn toggle(ui: &mut egui::Ui, on: &mut bool) -> egui::Response {
    let (rect, mut resp) = ui.allocate_exact_size(vec2(44.0, 24.0), egui::Sense::click());
    if resp.clicked() {
        *on = !*on;
        resp.mark_changed();
    }
    let t = ui.ctx().animate_bool_with_time(resp.id, *on, 0.15);
    let p = ui.painter();
    if t > 0.0 {
        gradient_rrect(p, rect, 12.0, alpha(ACCENT_A, (255.0 * t) as u8), alpha(ACCENT_B, (255.0 * t) as u8));
    }
    p.rect_filled(rect, 12.0, alpha(SURFACE2, (255.0 * (1.0 - t)) as u8));
    p.rect_stroke(rect, 12.0, Stroke::new(1.0_f32, alpha(BORDER, (255.0 * (1.0 - t)) as u8)));
    let x = egui::lerp(rect.left() + 12.0..=rect.right() - 12.0, t);
    p.circle_filled(pos2(x, rect.center().y), 8.5, Color32::WHITE);
    resp
}

// ------------------------------------------------------------------ Icons (Vektor)
pub enum Icon {
    Home,
    Console,
    Settings,
    Close,
    Minimize,
    Maximize,
    Play,
    Chevron,
}

pub fn icon(p: &Painter, c: Pos2, s: f32, color: Color32, kind: Icon) {
    let st = Stroke::new(s * 0.11, color);
    let pt = |x: f32, y: f32| pos2(c.x + x * s, c.y + y * s);
    match kind {
        Icon::Home => {
            p.add(Shape::line(vec![pt(-0.45, -0.02), pt(0.0, -0.42), pt(0.45, -0.02)], st));
            p.add(Shape::line(vec![pt(-0.32, -0.12), pt(-0.32, 0.4), pt(0.32, 0.4), pt(0.32, -0.12)], st));
            p.rect_filled(Rect::from_min_max(pt(-0.09, 0.12), pt(0.09, 0.4)), 1.0, color);
        }
        Icon::Console => {
            p.rect_stroke(Rect::from_min_max(pt(-0.45, -0.36), pt(0.45, 0.36)), s * 0.1, st);
            p.add(Shape::line(vec![pt(-0.25, -0.12), pt(-0.08, 0.02), pt(-0.25, 0.16)], st));
            p.line_segment([pt(0.02, 0.17), pt(0.25, 0.17)], st);
        }
        Icon::Settings => {
            for i in 0..8 {
                let a = i as f32 * std::f32::consts::TAU / 8.0;
                let (sn, cs) = a.sin_cos();
                p.line_segment([pt(cs * 0.28, sn * 0.28), pt(cs * 0.46, sn * 0.46)], Stroke::new(s * 0.15, color));
            }
            p.circle_stroke(c, s * 0.3, st);
            p.circle_stroke(c, s * 0.11, st);
        }
        Icon::Close => {
            p.line_segment([pt(-0.3, -0.3), pt(0.3, 0.3)], st);
            p.line_segment([pt(0.3, -0.3), pt(-0.3, 0.3)], st);
        }
        Icon::Minimize => {
            p.line_segment([pt(-0.32, 0.0), pt(0.32, 0.0)], st);
        }
        Icon::Maximize => {
            p.rect_stroke(Rect::from_min_max(pt(-0.28, -0.28), pt(0.28, 0.28)), 1.0, st);
        }
        Icon::Play => {
            p.add(Shape::convex_polygon(vec![pt(-0.3, -0.4), pt(0.42, 0.0), pt(-0.3, 0.4)], color, Stroke::NONE));
        }
        Icon::Chevron => {
            p.add(Shape::line(vec![pt(-0.3, -0.12), pt(0.0, 0.16), pt(0.3, -0.12)], st));
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

struct Layer {
    block: f32,
    base: f32,
    amp: f32,
    speed: f32,
    color: Color32,
    rim: Color32,
    seed: f32,
    trees: bool,
}

fn paint_layer(p: &Painter, r: Rect, t: f32, l: &Layer) {
    let off = t * l.speed;
    let first = (off / l.block).floor() as i64;
    let shift = off - first as f32 * l.block;
    let cols = (r.width() / l.block) as i64 + 3;
    for c in 0..cols {
        let col = first + c;
        let x = r.left() + c as f32 * l.block - shift;
        let n = noise(col as f32 * 0.07 + l.seed);
        let mut y = r.top() + r.height() * (l.base - l.amp * n);
        y = r.top() + ((y - r.top()) / l.block).round() * l.block;
        let col_rect = Rect::from_min_max(pos2(x, y), pos2(x + l.block + 0.6, r.bottom()));
        p.rect_filled(col_rect, 0.0, l.color);
        p.rect_filled(Rect::from_min_max(pos2(x, y), pos2(x + l.block + 0.6, y + l.block * 0.3)), 0.0, l.rim);
        if l.trees && hash((col as u32) ^ 0x9e37_79b9) > 0.86 {
            let b = l.block / 2.0;
            let tx = x + l.block / 2.0 - b / 2.0;
            p.rect_filled(Rect::from_min_max(pos2(tx, y - b * 3.0), pos2(tx + b, y)), 0.0, l.color);
            p.rect_filled(Rect::from_min_max(pos2(tx - b * 1.5, y - b * 6.0), pos2(tx + b * 2.5, y - b * 2.5)), 0.0, l.color);
            p.rect_filled(Rect::from_min_max(pos2(tx - b * 0.5, y - b * 7.5), pos2(tx + b * 1.5, y - b * 6.0)), 0.0, l.color);
            p.rect_filled(Rect::from_min_max(pos2(tx - b * 1.5, y - b * 6.0), pos2(tx + b * 2.5, y - b * 5.6)), 0.0, l.rim);
        }
    }
}

pub fn paint_landscape(p: &Painter, r: Rect, t: f32) {
    let rgb = Color32::from_rgb;
    vertical_gradient(p, r, &[
        (0.0, rgb(16, 10, 36)),
        (0.38, rgb(58, 28, 104)),
        (0.62, rgb(170, 70, 140)),
        (0.80, rgb(250, 150, 110)),
        (1.0, rgb(250, 150, 110)),
    ]);
    let (w, h) = (r.width(), r.height());
    // Sterne
    for i in 0..110u32 {
        let x = r.left() + hash(i) * w;
        let y = r.top() + hash(i + 7000) * h * 0.45;
        let tw = ((t * (0.8 + hash(i + 300) * 1.5) + i as f32).sin() * 0.5 + 0.5) * (1.0 - (y - r.top()) / (h * 0.45));
        let s = if hash(i + 99) > 0.85 { 2.5 } else { 1.5 };
        p.rect_filled(Rect::from_center_size(pos2(x, y), vec2(s, s)), 0.0, alpha(Color32::WHITE, (30.0 + tw * 170.0) as u8));
    }
    // Eckige Sonne mit Schein
    let sun = pos2(r.left() + w * 0.7, r.top() + h * 0.56);
    radial_glow(p, sun, 260.0, Color32::from_rgb(255, 170, 150), 120.0);
    radial_glow(p, sun, 110.0, Color32::from_rgb(255, 220, 170), 110.0);
    p.rect_filled(Rect::from_center_size(sun, vec2(64.0, 64.0)), 0.0, rgb(255, 226, 160));
    p.rect_filled(Rect::from_center_size(sun, vec2(40.0, 40.0)), 0.0, rgb(255, 240, 200));
    // Block-Wolken
    for k in 0..7u32 {
        let speed = 6.0 + hash(k + 40) * 10.0;
        let span = w + 400.0;
        let x = r.left() - 200.0 + (hash(k) * span + t * speed).rem_euclid(span);
        let y = r.top() + h * (0.05 + 0.12 * hash(k + 20));
        let s = 10.0 + hash(k + 60) * 6.0;
        let col = Color32::from_rgba_unmultiplied(255, 220, 255, 13);
        p.rect_filled(Rect::from_min_size(pos2(x, y), vec2(s * 12.0, s * 2.0)), 0.0, col);
        p.rect_filled(Rect::from_min_size(pos2(x + s * 2.0, y - s * 1.5), vec2(s * 6.0, s * 1.5)), 0.0, col);
        p.rect_filled(Rect::from_min_size(pos2(x + s * 4.0, y + s * 2.0), vec2(s * 5.0, s)), 0.0, col);
    }
    // Berge in drei Ebenen (Parallaxe)
    for l in [
        Layer { block: 12.0, base: 0.60, amp: 0.13, speed: 3.0, color: rgb(74, 38, 110), rim: rgb(140, 70, 150), seed: 3.1, trees: false },
        Layer { block: 16.0, base: 0.72, amp: 0.10, speed: 7.0, color: rgb(44, 22, 72), rim: rgb(108, 48, 118), seed: 8.7, trees: true },
        Layer { block: 22.0, base: 0.84, amp: 0.06, speed: 14.0, color: rgb(22, 14, 38), rim: rgb(70, 34, 86), seed: 1.9, trees: true },
    ] {
        paint_layer(p, r, t, &l);
    }
    // Abdunkeln nach unten (fuer gute Lesbarkeit)
    vertical_gradient(p, r, &[
        (0.0, alpha(BG, 90)),
        (0.3, alpha(BG, 0)),
        (0.55, alpha(BG, 0)),
        (1.0, alpha(BG, 235)),
    ]);
}
