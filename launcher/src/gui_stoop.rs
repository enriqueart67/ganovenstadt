//! Design 4 „Stoop“ – benannt nach dem Sturzflug des Wanderfalken.
//! Navigation als Hoehenband (wie das Hoehenmesser-Band im Cockpit), Startseite mit einer sich drehenden
//! 3D-Figur auf einem Lichtring, der zugleich der PLAY-Knopf und die Ladeanzeige ist, Atmosphaere aus
//! atmendem Eislicht und Sturzflug-Luftstroemen. Dazu die Seiten Versions, Accounts und Performance.
use std::time::{Instant, SystemTime, UNIX_EPOCH};

use eframe::egui::{self, epaint::Mesh, pos2, vec2, Align2, Color32, Id, Pos2, Rect, RichText, Sense, Shape, Stroke, Vec2, ViewportCommand};

use super::{card, field, header, Action, App, Page};
use crate::theme::{self, alpha, font, icon, Icon, Pal, BAD, GOOD, WARN};
use crate::{client_mods, config, install, prism};

/// Zustand des Stoop-Designs (Seitenwechsel, Drehung der Figur).
pub struct StoopState {
    pub page_at: Instant,
    pub last_page: Option<u8>,
    pub yaw_drag: f32,
    pub version_filter: String,
    /// Mitte des PLAY-Rings (dort beginnt die Start-Sequenz).
    pub ring_c: Option<Pos2>,
    pub versions_installed: bool,
}

impl Default for StoopState {
    fn default() -> Self {
        Self { page_at: Instant::now(), last_page: None, yaw_drag: 0.0, version_filter: String::new(), ring_c: None, versions_installed: false }
    }
}

// ------------------------------------------------------------------ kleine Zeichen-Bausteine
fn rnd(i: u32) -> f32 {
    let mut x = i.wrapping_mul(0x9E37_79B1) ^ 0x85EB_CA6B;
    x ^= x >> 15;
    x = x.wrapping_mul(0x2C1B_3C6D);
    x ^= x >> 12;
    x = x.wrapping_mul(0x297A_2D39);
    x ^= x >> 15;
    x as f32 / u32::MAX as f32
}

fn ease(t: f32) -> f32 {
    let t = t.clamp(0.0, 1.0);
    1.0 - (1.0 - t).powi(3)
}

/// Text mit Buchstabenabstand (Versalien-Labels, Wortmarke).
pub(super) fn spaced(p: &egui::Painter, pos: Pos2, align: Align2, text: &str, f: egui::FontId, color: Color32, sp: f32) -> Rect {
    let glyphs: Vec<(String, f32)> = text.chars().map(|c| (c.to_string(), p.layout_no_wrap(c.to_string(), f.clone(), color).size().x)).collect();
    let w: f32 = glyphs.iter().map(|g| g.1).sum::<f32>() + sp * glyphs.len().saturating_sub(1) as f32;
    let h = p.layout_no_wrap("A".into(), f.clone(), color).size().y;
    let rect = align.anchor_size(pos, vec2(w, h));
    let mut x = rect.left();
    for (c, cw) in glyphs {
        p.text(pos2(x, rect.center().y), Align2::LEFT_CENTER, c, f.clone(), color);
        x += cw + sp;
    }
    rect
}

pub(super) fn rnd_pub(i: u32) -> f32 {
    rnd(i)
}

pub(super) fn glow_at(p: &egui::Painter, c: Pos2, rx: f32, ry: f32, color: Color32, max_a: f32) {
    if max_a >= 1.0 {
        ellipse_glow(p, c, rx, ry, color, max_a.min(255.0));
    }
}

/// Elliptischer weicher Lichtschein.
fn ellipse_glow(p: &egui::Painter, c: Pos2, rx: f32, ry: f32, color: Color32, max_a: f32) {
    const SEG: u32 = 64;
    const RINGS: u32 = 14;
    let mut mesh = Mesh::default();
    mesh.colored_vertex(c, alpha(color, max_a as u8));
    for k in 1..=RINGS {
        let t = k as f32 / RINGS as f32;
        let a = (max_a * (1.0 - t).powf(2.0)) as u8;
        for i in 0..SEG {
            let ang = i as f32 / SEG as f32 * std::f32::consts::TAU;
            mesh.colored_vertex(pos2(c.x + ang.cos() * rx * t, c.y + ang.sin() * ry * t), alpha(color, a));
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

/// Status-Raute: gefuellt, optional mit atmendem Aussenrahmen.
pub(super) fn diamond(p: &egui::Painter, c: Pos2, r: f32, color: Color32, pulse: f32) {
    let pts = |r: f32| vec![pos2(c.x, c.y - r), pos2(c.x + r, c.y), pos2(c.x, c.y + r), pos2(c.x - r, c.y)];
    p.add(Shape::convex_polygon(pts(r), color, Stroke::NONE));
    if pulse > 0.0 {
        let rr = r + 2.0 + pulse * 4.0;
        p.add(Shape::closed_line(pts(rr), Stroke::new(1.0_f32, alpha(color, ((1.0 - pulse) * 150.0) as u8))));
    }
}

/// Bildmarke: Falke im Sturzflug (angelegte Fluegel, Spitze nach links unten).
pub(super) fn falcon(p: &egui::Painter, c: Pos2, s: f32, color: Color32) {
    let v = |x: f32, y: f32| pos2(c.x + x * s, c.y + y * s);
    let (tip, w1, notch, w2) = (v(-0.52, 0.56), v(0.58, -0.18), v(0.14, -0.08), v(0.22, -0.62));
    p.add(Shape::convex_polygon(vec![tip, w1, notch], color, Stroke::NONE));
    p.add(Shape::convex_polygon(vec![tip, notch, w2], color, Stroke::NONE));
}

fn ellipse_pts(c: Pos2, rx: f32, ry: f32, a0: f32, a1: f32, n: usize) -> Vec<Pos2> {
    (0..=n).map(|i| {
        let a = a0 + (a1 - a0) * i as f32 / n as f32;
        pos2(c.x + a.cos() * rx, c.y + a.sin() * ry)
    }).collect()
}

// ------------------------------------------------------------------ Hintergrund
/// Atmosphaere: atmendes Eislicht, kuehler Gegenschein und feine Sturzflug-Luftstroeme (mit Parallaxe).
pub(super) fn paint_atmosphere(p: &egui::Painter, r: Rect, pal: &Pal, t: f32, par: Vec2) {
    let breathe = 0.5 + 0.5 * (t * 0.45).sin();
    ellipse_glow(p, pos2(r.center().x + par.x * 18.0, r.top() + r.height() * 0.52 + par.y * 10.0), r.width() * 0.42, r.height() * 0.46, pal.accent, 18.0 + 8.0 * breathe);
    ellipse_glow(p, pos2(r.left() + r.width() * 0.12 + par.x * 8.0, r.top() + r.height() * 0.08), r.width() * 0.35, r.height() * 0.30, pal.accent2, 7.0);
    paint_mountains(p, r, pal, par);
    // Luftstroeme: duenne Linien, die schraeg nach unten links fallen
    let dir = vec2(-0.32, 1.0).normalized();
    for i in 0..34u32 {
        let depth = 0.35 + rnd(i * 7 + 1) * 0.65;
        let speed = 30.0 + rnd(i * 7 + 2) * 110.0 * depth;
        let len = 26.0 + rnd(i * 7 + 3) * 120.0 * depth;
        let span = r.height() + len * 2.0;
        let y = ((t * speed / span + rnd(i * 7 + 4)) % 1.0) * span - len;
        let x = r.left() + rnd(i * 7 + 5) * (r.width() * 1.35) + par.x * 30.0 * depth;
        let a = pos2(x - 0.32 * (y - r.top()), r.top() + y);
        let b = a + dir * len;
        let al = (4.0 + rnd(i * 7 + 6) * 12.0 * depth) as u8;
        p.line_segment([a, b], Stroke::new(1.0_f32, alpha(pal.accent2, al)));
    }
    // Rand abdunkeln – Blick wie durch ein Cockpitfenster
    theme::vertical_gradient(p, Rect::from_min_max(r.min, pos2(r.right(), r.top() + 90.0)), &[(0.0, alpha(pal.bg, 230)), (1.0, alpha(pal.bg, 0))]);
    theme::vertical_gradient(p, Rect::from_min_max(pos2(r.left(), r.bottom() - 70.0), r.max), &[(0.0, alpha(pal.bg, 0)), (1.0, alpha(pal.bg, 170))]);
}

// ------------------------------------------------------------------ Berg
fn ridge_noise(x: f32, seed: u32) -> f32 {
    let mut v = 0.0;
    let mut amp = 1.0;
    let mut f = 1.0;
    for o in 0..5 {
        let xi = (x * f).floor();
        let xf = x * f - xi;
        let a = rnd(xi as i32 as u32 ^ (seed + o * 977));
        let b = rnd((xi as i32 + 1) as u32 ^ (seed + o * 977));
        let t = xf * xf * (3.0 - 2.0 * xf);
        v += (a + (b - a) * t - 0.5) * amp;
        amp *= 0.5;
        f *= 2.1;
    }
    v
}

/// Farbmischung direkt in Bildschirmfarben (lerp_color mischt linear und wird dadurch viel heller).
fn mix(a: Color32, b: Color32, t: f32) -> Color32 {
    let l = |x: u8, y: u8| (x as f32 + (y as f32 - x as f32) * t).round().clamp(0.0, 255.0) as u8;
    Color32::from_rgba_unmultiplied(l(a.r(), b.r()), l(a.g(), b.g()), l(a.b(), b.b()), l(a.a(), b.a()))
}

/// Fuellt die Flaeche unter einer Kammlinie mit einem Verlauf (oben `top`, unten `bottom`).
fn fill_range(p: &egui::Painter, pts: &[Pos2], base: f32, top: Color32, bottom: Color32) {
    let mut mesh = Mesh::default();
    for w in pts.windows(2) {
        let i = mesh.vertices.len() as u32;
        mesh.colored_vertex(w[0], top);
        mesh.colored_vertex(w[1], top);
        mesh.colored_vertex(pos2(w[1].x, base), bottom);
        mesh.colored_vertex(pos2(w[0].x, base), bottom);
        mesh.add_triangle(i, i + 1, i + 2);
        mesh.add_triangle(i, i + 2, i + 3);
    }
    p.add(Shape::mesh(mesh));
}

/// Der Berg im Hintergrund: ein markanter Gipfel (Matterhorn-artig, asymmetrisch) mit Schnee im Eislicht,
/// dahinter eine dunstige Kette, davor dunkle Vorberge. Leichte Parallaxe mit der Maus.
fn paint_mountains(p: &egui::Painter, r: Rect, pal: &Pal, par: Vec2) {
    let w = r.width();
    let h = r.height();
    let base = r.bottom();
    let n = 160;
    let xs = |i: usize, shift: f32| r.left() - 40.0 + (w + 80.0) * i as f32 / n as f32 + shift;
    // hintere Kette: niedrig, gleichmaessig, im Dunst
    let back: Vec<Pos2> = (0..=n).map(|i| {
        let x = xs(i, par.x * 6.0);
        let u = (x - r.left()) / w;
        let y = base - h * (0.30 + 0.08 * (u * 3.1 + 0.4).sin() + 0.10 * ridge_noise(u * 3.0, 11));
        pos2(x, y)
    }).collect();
    fill_range(p, &back, base, mix(pal.bg, pal.accent2, 0.06), mix(pal.bg, pal.accent2, 0.015));
    // der Gipfel
    let peak_x = 0.64;
    let main: Vec<Pos2> = (0..=n).map(|i| {
        let x = xs(i, par.x * 12.0);
        let u = (x - r.left()) / w;
        let d = u - peak_x;
        // steile Nordwand rechts, laengerer Grat links
        let slope = if d < 0.0 { (1.0 + d / 0.30).max(0.0) } else { (1.0 - d / 0.17).max(0.0) };
        let peak = slope.powf(1.35) * 0.56;
        let shoulder = (1.0 - ((u - 0.40) / 0.22).abs()).max(0.0).powf(1.6) * 0.20;
        // rocky ridge: coarse and fine noise, stronger near the summit
        let rock = 0.045 * ridge_noise(u * 9.0, 23) + 0.018 * ridge_noise(u * 41.0, 29);
        let y = base - h * (0.16 + peak.max(shoulder) + rock * (0.5 + peak * 2.2));
        pos2(x, y)
    }).collect();
    fill_range(p, &main, base, mix(pal.bg, pal.accent2, 0.11), mix(pal.bg, Color32::BLACK, 0.2));
    // Schnee oberhalb der Schneegrenze, mit zerfranster Unterkante
    let snow_line = base - h * 0.52;
    let mut mesh = Mesh::default();
    for wdw in main.windows(2) {
        let (a, b) = (wdw[0], wdw[1]);
        if a.y > snow_line && b.y > snow_line {
            continue;
        }
        let jag = |x: f32| snow_line + 10.0 * ridge_noise(x / 37.0, 5) + 18.0 * ((x - r.left()) / w * 40.0).sin().abs();
        let (la, lb) = (jag(a.x).max(a.y), jag(b.x).max(b.y));
        let i = mesh.vertices.len() as u32;
        let top = mix(pal.accent2, Color32::WHITE, 0.2);
        mesh.colored_vertex(a, alpha(top, 90));
        mesh.colored_vertex(b, alpha(top, 90));
        mesh.colored_vertex(pos2(b.x, lb), alpha(pal.accent, 4));
        mesh.colored_vertex(pos2(a.x, la), alpha(pal.accent, 4));
        mesh.add_triangle(i, i + 1, i + 2);
        mesh.add_triangle(i, i + 2, i + 3);
    }
    p.add(Shape::mesh(mesh));
    // Kantenlicht auf dem Grat
    let rim: Vec<Pos2> = main.iter().copied().filter(|q| q.y < base - h * 0.30).collect();
    if rim.len() > 1 {
        p.add(Shape::line(rim, Stroke::new(1.0_f32, alpha(pal.accent, 40))));
    }
    // Dunst vor dem Fuss des Berges
    theme::vertical_gradient(p, Rect::from_min_max(pos2(r.left(), base - h * 0.30), pos2(r.right(), base - h * 0.12)), &[(0.0, alpha(pal.bg, 0)), (1.0, alpha(mix(pal.bg, pal.accent2, 0.03), 120))]);
    // dunkle Vorberge
    let front: Vec<Pos2> = (0..=n).map(|i| {
        let x = xs(i, par.x * 22.0);
        let u = (x - r.left()) / w;
        let y = base - h * (0.12 + 0.05 * (u * 5.3 + 1.2).sin() + 0.05 * ridge_noise(u * 5.0, 41));
        pos2(x, y)
    }).collect();
    fill_range(p, &front, base, mix(pal.bg, Color32::BLACK, 0.3), mix(pal.bg, Color32::BLACK, 0.5));
}

// ------------------------------------------------------------------ Figur
struct Part {
    min: [f32; 3],
    max: [f32; 3],
    pivot: [f32; 3],
    rot_x: f32,
    head: bool,
}

/// Blockfigur in 3D: Kopf, Koerper, Arme, Beine; dreht sich, atmet leicht, eisblaue Augen. Monochrom mit
/// Kantenlicht – eher Skulptur als Spielfigur.
fn paint_figure(p: &egui::Painter, feet: Pos2, height: f32, yaw: f32, t: f32, pal: &Pal) {
    let s = height / 32.0;
    let swing = (t * 1.4).sin();
    let parts = [
        Part { min: [-4.0, 0.0, -2.0], max: [0.0, 12.0, 2.0], pivot: [-2.0, 12.0, 0.0], rot_x: swing * 0.05, head: false },
        Part { min: [0.0, 0.0, -2.0], max: [4.0, 12.0, 2.0], pivot: [2.0, 12.0, 0.0], rot_x: -swing * 0.05, head: false },
        Part { min: [-4.0, 12.0, -2.0], max: [4.0, 24.0, 2.0], pivot: [0.0, 12.0, 0.0], rot_x: 0.0, head: false },
        Part { min: [-8.0, 12.0, -2.0], max: [-4.0, 24.0, 2.0], pivot: [-6.0, 22.0, 0.0], rot_x: -swing * 0.07, head: false },
        Part { min: [4.0, 12.0, -2.0], max: [8.0, 24.0, 2.0], pivot: [6.0, 22.0, 0.0], rot_x: swing * 0.07, head: false },
        Part { min: [-4.0, 24.0, -4.0], max: [4.0, 32.0, 4.0], pivot: [0.0, 24.0, 0.0], rot_x: (t * 0.6).sin() * 0.06, head: true },
    ];
    let tilt = 0.2_f32;
    let (cy, sy) = (yaw.cos(), yaw.sin());
    let (ct, st) = (tilt.cos(), tilt.sin());
    let world = |v: [f32; 3], part: &Part, normal: bool| -> [f32; 3] {
        let (mut x, mut y, mut z) = (v[0], v[1], v[2]);
        if !normal {
            y -= part.pivot[1];
            z -= part.pivot[2];
        }
        let (cr, sr) = (part.rot_x.cos(), part.rot_x.sin());
        let (y1, z1) = (y * cr - z * sr, y * sr + z * cr);
        y = y1;
        z = z1;
        if !normal {
            y += part.pivot[1];
            z += part.pivot[2];
        }
        let (x2, z2) = (x * cy + z * sy, -x * sy + z * cy);
        x = x2;
        z = z2;
        let (y3, z3) = (y * ct - z * st, y * st + z * ct);
        [x, y3, z3]
    };
    let screen = |w: [f32; 3]| pos2(feet.x + w[0] * s, feet.y - w[1] * s);
    let light = {
        let l = [-0.55_f32, 0.65, 0.55];
        let n = (l[0] * l[0] + l[1] * l[1] + l[2] * l[2]).sqrt();
        [l[0] / n, l[1] / n, l[2] / n]
    };
    let base = Color32::from_rgb(38, 42, 51);
    struct Face {
        depth: f32,
        pts: [Pos2; 4],
        color: Color32,
        eyes: bool,
        chest: bool,
    }
    let mut faces = Vec::new();
    for part in &parts {
        let (a, b) = (part.min, part.max);
        // Ecken: (Normale, 4 Ecken oben-links, oben-rechts, unten-rechts, unten-links von aussen gesehen)
        let defs: [([f32; 3], [[f32; 3]; 4]); 6] = [
            ([0.0, 0.0, 1.0], [[a[0], b[1], b[2]], [b[0], b[1], b[2]], [b[0], a[1], b[2]], [a[0], a[1], b[2]]]),
            ([0.0, 0.0, -1.0], [[b[0], b[1], a[2]], [a[0], b[1], a[2]], [a[0], a[1], a[2]], [b[0], a[1], a[2]]]),
            ([1.0, 0.0, 0.0], [[b[0], b[1], b[2]], [b[0], b[1], a[2]], [b[0], a[1], a[2]], [b[0], a[1], b[2]]]),
            ([-1.0, 0.0, 0.0], [[a[0], b[1], a[2]], [a[0], b[1], b[2]], [a[0], a[1], b[2]], [a[0], a[1], a[2]]]),
            ([0.0, 1.0, 0.0], [[a[0], b[1], a[2]], [b[0], b[1], a[2]], [b[0], b[1], b[2]], [a[0], b[1], b[2]]]),
            ([0.0, -1.0, 0.0], [[a[0], a[1], b[2]], [b[0], a[1], b[2]], [b[0], a[1], a[2]], [a[0], a[1], a[2]]]),
        ];
        for (fi, (n, corners)) in defs.iter().enumerate() {
            let nw = world(*n, part, true);
            if nw[2] <= 0.02 {
                continue;
            }
            let w: Vec<[f32; 3]> = corners.iter().map(|c| world(*c, part, false)).collect();
            let depth = w.iter().map(|v| v[2]).sum::<f32>() / 4.0;
            let diffuse = (nw[0] * light[0] + nw[1] * light[1] + nw[2] * light[2]).max(0.0);
            let k = 0.5 + 0.75 * diffuse;
            let mut col = Color32::from_rgb((base.r() as f32 * k) as u8, (base.g() as f32 * k) as u8, (base.b() as f32 * k * 1.04).min(255.0) as u8);
            // Kantenlicht: Flaechen, die zur Seite zeigen, bekommen einen Hauch Eisblau
            let rim = nw[0].abs().powf(3.0) * 0.35;
            col = theme::lerp_color(col, pal.accent, rim);
            let chest = !part.head && fi == 0 && part.min[0] == -4.0 && part.min[1] == 12.0;
            faces.push(Face { depth, pts: [screen(w[0]), screen(w[1]), screen(w[2]), screen(w[3])], color: col, eyes: part.head && fi == 0, chest });
        }
    }
    faces.sort_by(|a, b| a.depth.partial_cmp(&b.depth).unwrap_or(std::cmp::Ordering::Equal));
    let edge = Stroke::new(1.0_f32, alpha(pal.accent2, 34));
    for f in &faces {
        p.add(Shape::convex_polygon(f.pts.to_vec(), f.color, edge));
        if f.chest {
            // Falken-Zeichen auf der Brust
            let c = (f.pts[0].to_vec2() + f.pts[1].to_vec2() + f.pts[2].to_vec2() + f.pts[3].to_vec2()) / 4.0;
            let width = (f.pts[1] - f.pts[0]).length();
            if width > s * 3.0 {
                falcon(p, (c - vec2(0.0, s * 1.8)).to_pos2(), s * 1.6, alpha(pal.accent, 200));
            }
        }
        if f.eyes {
            let sub = |u: f32, v: f32| {
                let top = f.pts[0] + (f.pts[1] - f.pts[0]) * u;
                let bot = f.pts[3] + (f.pts[2] - f.pts[3]) * u;
                top + (bot - top) * v
            };
            for (u0, u1) in [(0.12, 0.36), (0.64, 0.88)] {
                let q = vec![sub(u0, 0.46), sub(u1, 0.46), sub(u1, 0.6), sub(u0, 0.6)];
                let c = q.iter().fold(Vec2::ZERO, |acc, p| acc + p.to_vec2()) / 4.0;
                theme::radial_glow(p, c.to_pos2(), s * 2.3, pal.accent, 55.0);
                p.add(Shape::convex_polygon(q, pal.accent, Stroke::NONE));
            }
        }
    }
}

// ------------------------------------------------------------------ Seiten
const PRIMARY: [(&str, u8); 6] = [("HOME", 0), ("MODS", 1), ("VERSIONS", 2), ("ACCOUNTS", 3), ("PERFORMANCE", 4), ("SETTINGS", 5)];
const SECONDARY: [(&str, u8); 5] = [("Profiles", 6), ("Servers", 7), ("Screenshots", 8), ("Appearance", 9), ("Console", 10)];

pub(super) fn page_from(i: u8) -> Page {
    match i {
        0 => Page::Home,
        1 => Page::Client,
        2 => Page::Versions,
        3 => Page::Accounts,
        4 => Page::Performance,
        5 => Page::Settings,
        6 => Page::Profiles,
        7 => Page::Servers,
        8 => Page::Shots,
        9 => Page::Look,
        _ => Page::Console,
    }
}

pub(super) fn page_index(p: Page) -> u8 {
    match p {
        Page::Home => 0,
        Page::Client => 1,
        Page::Versions => 2,
        Page::Accounts => 3,
        Page::Performance => 4,
        Page::Settings => 5,
        Page::Profiles => 6,
        Page::Servers => 7,
        Page::Shots => 8,
        Page::Look => 9,
        Page::Console => 10,
    }
}

fn ago(secs: u64) -> String {
    if secs == 0 {
        return "never".into();
    }
    let now = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0);
    let d = now.saturating_sub(secs);
    match d {
        0..=89 => "just now".into(),
        90..=3599 => format!("{} min ago", d / 60),
        3600..=86_399 => format!("{} h ago", d / 3600),
        _ => format!("{} days ago", d / 86_400),
    }
}

impl App {
    /// Seitenwechsel: 0..1 fuer das Einblenden der neuen Seite.
    pub(super) fn page_fade(&mut self) -> f32 {
        let idx = page_index(self.page);
        if self.stoop.last_page != Some(idx) {
            self.stoop.last_page = Some(idx);
            self.stoop.page_at = Instant::now();
        }
        ease(self.stoop.page_at.elapsed().as_secs_f32() / 0.32)
    }

    // -------------------------------------------------------------- Titelleiste
    pub(super) fn stoop_title(&mut self, ui: &mut egui::Ui, r: Rect, pal: &Pal, busy: bool) {
        let ctx = ui.ctx().clone();
        let bar = ui.interact(r, Id::new("titlebar"), Sense::click_and_drag());
        if bar.drag_started() {
            ctx.send_viewport_cmd(ViewportCommand::StartDrag);
        }
        if bar.double_clicked() {
            let max = ctx.input(|i| i.viewport().maximized.unwrap_or(false));
            ctx.send_viewport_cmd(ViewportCommand::Maximized(!max));
        }
        let p = ui.painter();
        falcon(p, pos2(r.left() + 30.0, r.center().y), 11.0, pal.text);
        spaced(p, pos2(r.left() + 50.0, r.center().y), Align2::LEFT_CENTER, &self.cfg.look.title.to_uppercase(), font(12.0, "monom"), pal.text, 5.0);
        // Statusanzeige in der Mitte
        let running = prism::game_running();
        let launching = self.launch_anim.is_some();
        let (label, col) = if busy || (launching && !running) {
            ("LAUNCHING", WARN)
        } else if running {
            ("IN FLIGHT", GOOD)
        } else {
            ("READY", pal.accent)
        };
        let now = ui.input(|i| i.time) as f32;
        let pulse = (now * 0.8).fract();
        let c = pos2(r.center().x - 40.0, r.center().y);
        diamond(p, c, 3.2, col, if busy || running || launching { pulse } else { 0.0 });
        spaced(p, pos2(c.x + 12.0, c.y), Align2::LEFT_CENTER, label, font(10.0, "monom"), pal.muted, 3.0);
        // Fenster-Knoepfe
        let mut x = r.right();
        for (kind, cmd) in [(Icon::Close, 0), (Icon::Maximize, 1), (Icon::Minimize, 2)] {
            let br = Rect::from_min_max(pos2(x - 46.0, r.top()), pos2(x, r.bottom()));
            x -= 46.0;
            let resp = ui.interact(br, Id::new(("winbtn", cmd)), Sense::click());
            let p = ui.painter();
            let hot = resp.hovered();
            if hot {
                p.rect_filled(br, 0.0, if cmd == 0 { BAD } else { pal.surface2 });
            }
            icon(p, br.center(), 13.0, if hot && cmd == 0 { Color32::WHITE } else if hot { pal.text } else { pal.muted }, kind);
            if resp.clicked() {
                match cmd {
                    0 => ctx.send_viewport_cmd(ViewportCommand::Close),
                    1 => {
                        let max = ctx.input(|i| i.viewport().maximized.unwrap_or(false));
                        ctx.send_viewport_cmd(ViewportCommand::Maximized(!max));
                    }
                    _ => ctx.send_viewport_cmd(ViewportCommand::Minimized(true)),
                }
            }
        }
        if busy || running {
            ctx.request_repaint_after(std::time::Duration::from_millis(33));
        }
    }

    // -------------------------------------------------------------- Navigation: Hoehenband
    pub(super) fn nav_rail(&mut self, ui: &mut egui::Ui, r: Rect, pal: &Pal, account_name: Option<String>) {
        let tape_x = r.left() + 30.0;
        let top = r.top() + 18.0;
        let bottom = r.bottom() - 70.0;
        let p = ui.painter().clone();
        // Band mit Teilstrichen
        p.line_segment([pos2(tape_x, top), pos2(tape_x, bottom)], Stroke::new(1.0_f32, alpha(pal.text, 40)));
        let mut y = top;
        let mut k = 0;
        while y <= bottom {
            let long = k % 5 == 0;
            p.line_segment([pos2(tape_x - if long { 7.0 } else { 3.5 }, y), pos2(tape_x, y)], Stroke::new(1.0_f32, alpha(pal.text, if long { 46 } else { 22 })));
            y += 9.0;
            k += 1;
        }
        let current = page_index(self.page);
        let mut pointer_y = None;
        let mut y = top + 26.0;
        for (label, idx) in PRIMARY {
            let row = Rect::from_min_size(pos2(r.left(), y - 14.0), vec2(r.width(), 28.0));
            let resp = ui.interact(row, Id::new(("nav", idx)), Sense::click());
            let hov = ui.ctx().animate_bool_with_time(Id::new(("navh", idx)), resp.hovered(), 0.18);
            let active = current == idx;
            if active {
                pointer_y = Some(y);
            }
            p.line_segment([pos2(tape_x, y), pos2(tape_x + 8.0 + hov * 6.0, y)], Stroke::new(1.0_f32, alpha(pal.text, if active { 160 } else { 60 + (hov * 80.0) as u8 })));
            let col = if active { pal.text } else { theme::lerp_color(pal.muted, pal.text, hov) };
            spaced(&p, pos2(tape_x + 26.0 + hov * 4.0, y), Align2::LEFT_CENTER, label, font(11.0, "monom"), col, 2.6);
            if resp.clicked() {
                self.goto(page_from(idx));
            }
            y += 44.0;
        }
        // Nebenseiten, kleiner
        y += 14.0;
        p.line_segment([pos2(tape_x + 18.0, y - 18.0), pos2(r.right() - 22.0, y - 18.0)], Stroke::new(1.0_f32, alpha(pal.text, 18)));
        for (label, idx) in SECONDARY {
            let row = Rect::from_min_size(pos2(r.left(), y - 11.0), vec2(r.width(), 22.0));
            let resp = ui.interact(row, Id::new(("nav", idx)), Sense::click());
            let hov = ui.ctx().animate_bool_with_time(Id::new(("navh", idx)), resp.hovered(), 0.18);
            let active = current == idx;
            if active {
                pointer_y = Some(y);
            }
            let col = if active { pal.text } else { theme::lerp_color(alpha(pal.muted, 170), pal.text, hov) };
            p.text(pos2(tape_x + 26.0 + hov * 4.0, y), Align2::LEFT_CENTER, label, font(12.5, "regular"), col);
            if resp.clicked() {
                self.goto(page_from(idx));
            }
            y += 30.0;
        }
        // Zeiger gleitet zur aktiven Seite
        if let Some(py) = pointer_y {
            let ay = ui.ctx().animate_value_with_time(Id::new("navptr"), py, 0.32);
            let c = pos2(tape_x, ay);
            p.add(Shape::convex_polygon(vec![pos2(c.x + 4.0, c.y), pos2(c.x + 13.0, c.y - 5.5), pos2(c.x + 13.0, c.y + 5.5)], pal.accent, Stroke::NONE));
            p.line_segment([pos2(c.x - 9.0, c.y), pos2(c.x + 2.0, c.y)], Stroke::new(2.0_f32, pal.accent));
            ellipse_glow(&p, c, 26.0, 12.0, pal.accent, 30.0);
            if (ay - py).abs() > 0.5 {
                // waehrend der Zeiger gleitet weiter zeichnen
                ui.ctx().request_repaint();
            }
        }
        // Konto unten
        let ar = Rect::from_min_size(pos2(r.left(), r.bottom() - 52.0), vec2(r.width(), 34.0));
        let resp = ui.interact(ar, Id::new("navacct"), Sense::click());
        let (name, col) = match account_name {
            Some(n) => (n, GOOD),
            None => ("Sign in".to_string(), WARN),
        };
        diamond(&p, pos2(tape_x, ar.center().y), 3.5, col, 0.0);
        p.text(pos2(tape_x + 26.0, ar.center().y - 6.0), Align2::LEFT_CENTER, &name, font(13.0, "medium"), if resp.hovered() { pal.text } else { theme::lerp_color(pal.muted, pal.text, 0.6) });
        p.text(pos2(tape_x + 26.0, ar.center().y + 9.0), Align2::LEFT_CENTER, format!("PEREGRINE {}", config::LAUNCHER_VERSION), font(9.0, "mono"), alpha(pal.muted, 150));
        if resp.clicked() {
            self.goto(Page::Accounts);
        }
    }

    pub(super) fn goto(&mut self, page: Page) {
        if self.page == Page::Settings && page != Page::Settings && self.cfg.game_dir != self.saved.game_dir {
            self.load_versions();
        }
        self.page = page;
    }

    // -------------------------------------------------------------- Startseite
    pub(super) fn stoop_home(&mut self, ui: &mut egui::Ui, area: Rect, t: f32, pal: &Pal, account_name: Option<String>, action: &mut Option<Action>) {
        let active = self.active_profile();
        let (busy, status, done, total) = {
            let s = self.h.lock();
            (s.busy, s.status.clone(), s.done, s.total)
        };
        let mouse = ui.input(|i| i.pointer.hover_pos()).unwrap_or(area.center());
        let par = vec2((mouse.x - area.center().x) / area.width(), (mouse.y - area.center().y) / area.height());
        let col_w = (area.width() * 0.26).clamp(220.0, 300.0);
        let left = Rect::from_min_size(area.min, vec2(col_w, area.height()));
        let right = Rect::from_min_max(pos2(area.right() - col_w, area.top()), area.max);
        let stage = Rect::from_min_max(pos2(left.right() + 10.0, area.top()), pos2(right.left() - 10.0, area.bottom()));
        let p = ui.painter().clone();

        // ---------- Buehne: Figur auf dem Lichtring (= PLAY)
        let ring_c = pos2(stage.center().x + par.x * 6.0, stage.bottom() - 118.0);
        let rx = (stage.width() * 0.36).clamp(110.0, 190.0);
        let ry = rx * 0.24;
        let fig_h = ((ring_c.y - stage.top()) * 0.78).clamp(140.0, 380.0);
        let play_rect = Rect::from_center_size(pos2(ring_c.x, ring_c.y + 30.0), vec2(rx * 2.2, ry * 2.0 + 110.0));
        let enabled = !busy && (active.is_some() || !self.cfg.version.is_empty());
        let resp = ui.interact(play_rect, Id::new("play"), if enabled { Sense::click() } else { Sense::hover() });
        // Figur drehen per Ziehen
        let drag = ui.interact(Rect::from_min_max(pos2(stage.left(), stage.top()), pos2(stage.right(), ring_c.y - 20.0)), Id::new("figdrag"), Sense::drag());
        self.stoop.yaw_drag += drag.drag_delta().x * 0.012;
        let hov = ui.ctx().animate_bool_with_time(Id::new("playhover"), resp.hovered() && enabled, 0.22);
        let press = ui.ctx().animate_bool_with_time(Id::new("playpress"), resp.is_pointer_button_down_on(), 0.08);

        self.stoop.ring_c = Some(ring_c);
        // Aktivierung: direkt nach dem Klick leuchtet der Ring auf
        let act = self.launch_anim.as_ref().map(|l| l.activation()).unwrap_or(0.0);
        ellipse_glow(&p, ring_c, rx * 1.5, ry * 2.4, pal.accent, 22.0 + hov * 30.0 + act * 140.0);
        if act > 0.0 {
            p.add(Shape::convex_polygon(ellipse_pts(ring_c, rx, ry, 0.0, std::f32::consts::TAU, 64), alpha(pal.accent, (act * 90.0) as u8), Stroke::new(2.0_f32, alpha(Color32::WHITE, (act * 200.0) as u8))));
        }
        // hintere Haelfte des Rings (hinter der Figur)
        let back = ellipse_pts(ring_c, rx, ry, std::f32::consts::PI, std::f32::consts::TAU, 48);
        p.add(Shape::line(back, Stroke::new(1.0_f32, alpha(pal.accent2, 70 + (hov * 80.0) as u8))));
        p.add(Shape::line(ellipse_pts(ring_c, rx * 0.74, ry * 0.74, std::f32::consts::PI, std::f32::consts::TAU, 40), Stroke::new(1.0_f32, alpha(pal.accent2, 26))));
        // Schatten
        ellipse_glow(&p, ring_c, rx * 0.42, ry * 0.42, Color32::BLACK, 160.0);
        // Figur
        // sanftes Hin- und Herschwenken, damit man meistens das Gesicht sieht (Ziehen dreht frei)
        let yaw = 0.42 * (t * 0.23).sin() + par.x * 0.55 + self.stoop.yaw_drag;
        paint_figure(&p, pos2(ring_c.x, ring_c.y - press * 3.0), fig_h, yaw, t, pal);
        // vordere Haelfte + Skala
        let front = ellipse_pts(ring_c, rx, ry, 0.0, std::f32::consts::PI, 48);
        p.add(Shape::line(front, Stroke::new(1.2_f32, alpha(pal.accent2, 110 + (hov * 120.0) as u8))));
        for i in 0..72 {
            let a = i as f32 / 72.0 * std::f32::consts::TAU;
            if a.sin() < 0.0 {
                continue;
            }
            let o = pos2(ring_c.x + a.cos() * rx, ring_c.y + a.sin() * ry);
            let len = if i % 6 == 0 { 7.0 } else { 3.0 };
            p.line_segment([o, o + vec2(0.0, len)], Stroke::new(1.0_f32, alpha(pal.accent2, if i % 6 == 0 { 70 } else { 30 })));
        }
        // aktiver Bogen: beim Ueberfahren kreisend, beim Laden der Fortschritt
        let arc = |a0: f32, a1: f32, w: f32, c: Color32| {
            p.add(Shape::line(ellipse_pts(ring_c, rx, ry, a0, a1, 40), Stroke::new(w, c)));
        };
        if busy {
            if total > 0 {
                let f = done as f32 / total as f32;
                arc(-std::f32::consts::FRAC_PI_2, -std::f32::consts::FRAC_PI_2 + f * std::f32::consts::TAU, 2.6, pal.accent);
            } else {
                let a = t * 2.2;
                arc(a, a + 1.4, 2.6, pal.accent);
            }
            ui.ctx().request_repaint();
        } else if hov > 0.01 {
            let a = t * 1.3;
            arc(a, a + 1.2 * hov, 2.4, alpha(pal.accent, (hov * 255.0) as u8));
            arc(a + std::f32::consts::PI, a + std::f32::consts::PI + 0.5 * hov, 1.4, alpha(pal.accent, (hov * 140.0) as u8));
            // aufsteigende Lichtpunkte
            for i in 0..16u32 {
                let ph = (t * (0.25 + rnd(i) * 0.3) + rnd(i + 50)) % 1.0;
                let x = ring_c.x + (rnd(i + 99) - 0.5) * rx * 1.7;
                let y = ring_c.y - ph * fig_h * 0.9;
                p.circle_filled(pos2(x, y), 1.2, alpha(pal.accent, ((1.0 - ph) * hov * 150.0) as u8));
            }
        }
        // Beschriftung
        let ly = ring_c.y + ry + 40.0 - hov * 3.0;
        let (big, sub) = if busy {
            (if total > 0 { format!("{:.0}%", done as f32 / total.max(1) as f32 * 100.0) } else { "LAUNCHING".into() }, status.to_uppercase())
        } else {
            let what = match &active {
                Some(ap) => format!("{}  ·  {} {}", ap.name, ap.loader, ap.version),
                None => format!("MINECRAFT {}  ·  {}", self.cfg.version, self.cfg.loader),
            };
            ("PLAY".into(), what.to_uppercase())
        };
        spaced(&p, pos2(ring_c.x, ly), Align2::CENTER_CENTER, &big, font(28.0, "display"), if hov > 0.5 { pal.accent } else { pal.text }, 9.0);
        let sub_g = if sub.chars().count() > 46 { format!("{}…", sub.chars().take(45).collect::<String>()) } else { sub };
        spaced(&p, pos2(ring_c.x, ly + 26.0), Align2::CENTER_CENTER, &sub_g, font(9.5, "mono"), pal.muted, 2.0);
        if !busy {
            spaced(&p, pos2(ring_c.x, ly + 44.0), Align2::CENTER_CENTER, "ENTER", font(8.5, "monom"), alpha(pal.muted, (90.0 + hov * 120.0) as u8), 3.0);
        }
        let enter = ui.input(|i| i.key_pressed(egui::Key::Enter)) && !ui.ctx().wants_keyboard_input();
        if enabled && (resp.clicked() || enter) && !self.version_open {
            *action = Some(Action::Play);
        }
        if hov > 0.01 && hov < 0.99 {
            ui.ctx().request_repaint();
        }

        // ---------- links: Version & Sitzung
        let p = ui.painter().with_clip_rect(left.expand(6.0));
        let mut y = left.top() + 6.0;
        let hour_greeting = {
            let secs = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0);
            match (secs / 3600) % 24 {
                5..=10 => "GOOD MORNING",
                11..=16 => "GOOD AFTERNOON",
                _ => "GOOD EVENING",
            }
        };
        let who = account_name.clone().unwrap_or_else(|| "PILOT".into()).to_uppercase();
        spaced(&p, pos2(left.left(), y), Align2::LEFT_TOP, &format!("{hour_greeting}, {who}"), font(9.5, "mono"), pal.muted, 2.2);
        y += 30.0;
        let shown = match &active {
            Some(ap) => ap.name.clone(),
            None if self.cfg.version.is_empty() => "…".into(),
            None => self.cfg.version.clone(),
        };
        let mut size = 92.0_f32;
        while size > 36.0 && p.layout_no_wrap(shown.clone(), font(size, "display"), pal.text).size().x > left.width() - 10.0 {
            size -= 4.0;
        }
        let vg = p.text(pos2(left.left() - size * 0.03, y), Align2::LEFT_TOP, &shown, font(size, "display"), pal.text);
        let vresp = ui.interact(vg.expand(4.0), Id::new("vercard"), Sense::click());
        if vresp.hovered() {
            p.line_segment([pos2(vg.left(), vg.bottom() + 2.0), pos2(vg.right(), vg.bottom() + 2.0)], Stroke::new(1.0_f32, pal.accent));
        }
        let vc = Rect::from_min_size(pos2(left.left(), vg.bottom() - 52.0), vec2(vg.width().max(200.0), 52.0));
        if vresp.clicked() {
            self.version_open = !self.version_open;
            self.just_opened = true;
            self.focus_search = 3;
        }
        y = vg.bottom() + 8.0;
        spaced(&p, pos2(left.left(), y), Align2::LEFT_TOP, if active.is_some() { "PROFILE  ·  CHANGE" } else { "JAVA EDITION  ·  CHANGE" }, font(9.0, "mono"), alpha(pal.muted, 190), 2.0);
        y += 30.0;
        // Loader-Wahl mit gleitendem Strich
        if active.is_none() {
            let mut x = left.left();
            let mut sel_x = (left.left(), 10.0);
            for l in install::LOADERS {
                let g = p.layout_no_wrap(l.to_string(), font(15.0, "medium"), pal.text);
                let rr = Rect::from_min_size(pos2(x, y), vec2(g.size().x, 26.0));
                let r2 = ui.interact(rr.expand2(vec2(6.0, 2.0)), Id::new(("ld", l)), Sense::click());
                let sel = self.cfg.loader == l;
                p.text(pos2(x, y + 11.0), Align2::LEFT_CENTER, l, font(15.0, "medium"), if sel || r2.hovered() { pal.text } else { pal.muted });
                if sel {
                    sel_x = (x, g.size().x);
                }
                if r2.clicked() {
                    self.cfg.loader = l.to_string();
                }
                x += g.size().x + 24.0;
            }
            let ax = ui.ctx().animate_value_with_time(Id::new("ldx"), sel_x.0, 0.25);
            let aw = ui.ctx().animate_value_with_time(Id::new("ldw"), sel_x.1, 0.25);
            p.line_segment([pos2(ax, y + 26.0), pos2(ax + aw, y + 26.0)], Stroke::new(2.0_f32, pal.accent));
        } else {
            let r2 = ui.interact(Rect::from_min_size(pos2(left.left(), y), vec2(200.0, 26.0)), Id::new("leaveprof"), Sense::click());
            p.text(pos2(left.left(), y + 11.0), Align2::LEFT_CENTER, "← plain Minecraft instead", font(14.0, "regular"), if r2.hovered() { pal.text } else { pal.muted });
            if r2.clicked() {
                self.cfg.active_profile = None;
            }
        }
        y += 58.0;
        // Sitzung
        spaced(&p, pos2(left.left(), y), Align2::LEFT_TOP, "SESSION", font(9.5, "monom"), pal.text, 3.0);
        y += 24.0;
        let rows: Vec<(&str, String, Color32)> = vec![
            ("PILOT", account_name.clone().unwrap_or_else(|| "Guest · demo".into()), if account_name.is_some() { GOOD } else { WARN }),
            ("LAST FLIGHT", ago(self.cfg.stats_last_launch), pal.accent2),
            ("FLIGHTS", self.cfg.stats_launches.to_string(), pal.accent2),
            ("MEMORY", format!("{:.1} GB", self.cfg.ram_mb as f32 / 1024.0), pal.accent2),
            ("CLIENT MODS", if self.cfg.client.enabled { "on  ·  Right Shift".into() } else { "off".into() }, if self.cfg.client.enabled { GOOD } else { pal.muted }),
        ];
        for (k, v, c) in rows {
            if y + 30.0 > left.bottom() {
                break;
            }
            p.line_segment([pos2(left.left(), y), pos2(left.right(), y)], Stroke::new(1.0_f32, alpha(pal.text, 16)));
            diamond(&p, pos2(left.left() + 3.0, y + 15.0), 2.4, c, 0.0);
            p.text(pos2(left.left() + 14.0, y + 15.0), Align2::LEFT_CENTER, k, font(9.5, "mono"), pal.muted);
            p.text(pos2(left.right(), y + 15.0), Align2::RIGHT_CENTER, v, font(13.0, "medium"), pal.text);
            y += 30.0;
        }

        // ---------- rechts: zuletzt & Server
        let p = ui.painter().with_clip_rect(right.expand(6.0));
        let mut y = right.top() + 6.0;
        spaced(&p, pos2(right.left(), y), Align2::LEFT_TOP, "RECENT", font(9.5, "monom"), pal.text, 3.0);
        y += 24.0;
        let list = self.prof.list.clone();
        if list.is_empty() {
            let r2 = ui.interact(Rect::from_min_size(pos2(right.left(), y), vec2(right.width(), 40.0)), Id::new("noprof"), Sense::click());
            p.text(pos2(right.left(), y + 10.0), Align2::LEFT_TOP, "No profiles yet – make one for\nPvP, shaders or survival →", font(12.5, "regular"), if r2.hovered() { pal.text } else { pal.muted });
            if r2.clicked() {
                self.goto(Page::Profiles);
            }
            y += 52.0;
        }
        for pr in list.iter().take(4) {
            let rr = Rect::from_min_size(pos2(right.left(), y), vec2(right.width(), 44.0));
            let r2 = ui.interact(rr, Id::new(("qs", &pr.id)), Sense::click());
            let h2 = ui.ctx().animate_bool_with_time(Id::new(("qsh", &pr.id)), r2.hovered(), 0.15);
            let sel = self.cfg.active_profile.as_deref() == Some(pr.id.as_str());
            p.line_segment([pos2(right.left(), y), pos2(right.right(), y)], Stroke::new(1.0_f32, alpha(pal.text, 16)));
            if sel {
                p.line_segment([pos2(right.left(), y + 8.0), pos2(right.left(), y + 36.0)], Stroke::new(2.0_f32, pal.accent));
            }
            p.text(pos2(right.left() + 12.0 + h2 * 4.0, y + 16.0), Align2::LEFT_CENTER, &pr.name, font(14.0, if sel { "semibold" } else { "regular" }), pal.text);
            p.text(pos2(right.left() + 12.0 + h2 * 4.0, y + 31.0), Align2::LEFT_CENTER, format!("{} {}  ·  {} MODS", pr.loader, pr.version, pr.mods.len()).to_uppercase(), font(9.0, "mono"), pal.muted);
            if r2.clicked() {
                self.cfg.active_profile = if sel { None } else { Some(pr.id.clone()) };
            }
            y += 44.0;
        }
        y += 22.0;
        spaced(&p, pos2(right.left(), y), Align2::LEFT_TOP, "SERVERS", font(9.5, "monom"), pal.text, 3.0);
        y += 24.0;
        let servers = self.server_status();
        if servers.is_empty() {
            let r2 = ui.interact(Rect::from_min_size(pos2(right.left(), y), vec2(right.width(), 40.0)), Id::new("nosrv"), Sense::click());
            p.text(pos2(right.left(), y + 10.0), Align2::LEFT_TOP, "Add your servers to see who is\nonline and join in one click →", font(12.5, "regular"), if r2.hovered() { pal.text } else { pal.muted });
            if r2.clicked() {
                self.goto(Page::Servers);
            }
        }
        let now = ui.input(|i| i.time) as f32;
        for (name, addr, state, info) in servers.into_iter().take(4) {
            if y + 44.0 > right.bottom() {
                break;
            }
            let rr = Rect::from_min_size(pos2(right.left(), y), vec2(right.width(), 44.0));
            let r2 = ui.interact(rr, Id::new(("srv", &addr)), Sense::click());
            let h2 = ui.ctx().animate_bool_with_time(Id::new(("srvh", &addr)), r2.hovered(), 0.15);
            p.line_segment([pos2(right.left(), y), pos2(right.right(), y)], Stroke::new(1.0_f32, alpha(pal.text, 16)));
            let (c, pulse) = match state {
                1 => (GOOD, (now * 0.6).fract()),
                2 => (alpha(pal.muted, 160), 0.0),
                _ => (WARN, (now * 1.2).fract()),
            };
            diamond(&p, pos2(right.left() + 4.0, y + 22.0), 3.0, c, pulse);
            p.text(pos2(right.left() + 18.0 + h2 * 4.0, y + 16.0), Align2::LEFT_CENTER, &name, font(14.0, "regular"), pal.text);
            p.text(pos2(right.left() + 18.0 + h2 * 4.0, y + 31.0), Align2::LEFT_CENTER, if h2 > 0.5 { "JOIN →".to_string() } else { info.to_uppercase() }, font(9.0, "mono"), if h2 > 0.5 { pal.accent } else { pal.muted });
            if r2.clicked() && !busy {
                self.extra.join = Some(addr.clone());
                *action = Some(Action::Play);
            }
            y += 44.0;
        }
        ui.ctx().request_repaint_after(std::time::Duration::from_millis(250));

        if self.version_open {
            self.version_popup(ui, pal, vc, area);
        }
        self.just_opened = false;
    }

    // -------------------------------------------------------------- Versions
    pub(super) fn versions_page(&mut self, ui: &mut egui::Ui, r: Rect, pal: &Pal) {
        let (versions, busy, status, done, total) = {
            let s = self.h.lock();
            (s.versions.clone(), s.busy, s.status.clone(), s.done, s.total)
        };
        // installed = folders in <game>/versions with their json
        let installed: Vec<String> = std::fs::read_dir(std::path::Path::new(&self.cfg.game_dir).join("versions"))
            .into_iter()
            .flatten()
            .flatten()
            .filter(|e| e.path().join(format!("{}.json", e.file_name().to_string_lossy())).exists())
            .map(|e| e.file_name().to_string_lossy().into_owned())
            .collect();
        ui.allocate_new_ui(egui::UiBuilder::new().max_rect(r.shrink2(vec2(24.0, 18.0))), |ui| {
            header(ui, pal, "Versions", "Every Minecraft release – pick one and press Play");
            if busy {
                let (rr, _) = ui.allocate_exact_size(vec2(ui.available_width().min(640.0), 34.0), Sense::hover());
                let p = ui.painter();
                p.text(pos2(rr.left(), rr.top() + 8.0), Align2::LEFT_CENTER, status.to_uppercase(), font(10.0, "monom"), pal.text);
                let bar = Rect::from_min_size(pos2(rr.left(), rr.top() + 22.0), vec2(rr.width(), 2.0));
                p.rect_filled(bar, 0.0, alpha(pal.text, 20));
                if total > 0 {
                    p.rect_filled(Rect::from_min_size(bar.min, vec2(bar.width() * done as f32 / total as f32, 2.0)), 0.0, pal.accent);
                    p.text(pos2(rr.right(), rr.top() + 8.0), Align2::RIGHT_CENTER, format!("{done} / {total}"), font(10.0, "mono"), pal.muted);
                } else {
                    let ph = (ui.input(|i| i.time) as f32 * 0.7) % 1.0;
                    p.rect_filled(Rect::from_min_size(pos2(bar.left() + bar.width() * ph * 0.8, bar.top()), vec2(bar.width() * 0.2, 2.0)), 0.0, pal.accent);
                }
                ui.ctx().request_repaint();
            }
            ui.horizontal(|ui| {
                let mut avail = !self.stoop.versions_installed;
                super::chip(ui, pal, "Available", &mut avail);
                let mut inst = self.stoop.versions_installed;
                super::chip(ui, pal, &format!("Installed  {}", installed.len()), &mut inst);
                if inst != self.stoop.versions_installed {
                    self.stoop.versions_installed = inst;
                } else if avail == self.stoop.versions_installed {
                    self.stoop.versions_installed = !avail;
                }
                ui.add_space(18.0);
                super::chip(ui, pal, "Releases", &mut self.cfg.show_releases);
                super::chip(ui, pal, "Snapshots", &mut self.cfg.show_snapshots);
                super::chip(ui, pal, "Old", &mut self.cfg.show_old);
                ui.add_space(16.0);
                ui.add(egui::TextEdit::singleline(&mut self.stoop.version_filter).hint_text("search, e.g. 26.2").desired_width(220.0).margin(vec2(10.0, 6.0)));
            });
            ui.add_space(6.0);
            ui.horizontal(|ui| {
                ui.label(RichText::new("MOD LOADER").font(font(10.0, "mono")).color(pal.muted));
                let mut l = self.cfg.loader.clone();
                super::segmented(ui, pal, "verloader", &install::LOADERS, &mut l);
                self.cfg.loader = l;
            });
            ui.add_space(10.0);
            let filter = self.stoop.version_filter.trim().to_lowercase();
            let shown: Vec<_> = versions
                .iter()
                .filter(|v| match v.kind.as_str() {
                    "release" => self.cfg.show_releases,
                    "snapshot" => self.cfg.show_snapshots,
                    _ => self.cfg.show_old,
                })
                .filter(|v| !self.stoop.versions_installed || installed.iter().any(|i| i == &v.id))
                .filter(|v| filter.is_empty() || v.id.to_lowercase().contains(&filter))
                .cloned()
                .collect();
            if shown.is_empty() {
                ui.label(RichText::new(if versions.is_empty() { "Loading the version list …" } else { "Nothing matches." }).color(pal.muted));
            }
            let latest = versions.iter().find(|v| v.kind == "release").map(|v| v.id.clone());
            egui::ScrollArea::vertical().auto_shrink([false; 2]).show(ui, |ui| {
                let w = ui.available_width();
                let cols = ((w / 230.0) as usize).max(1);
                let cw = w / cols as f32;
                for chunk in shown.chunks(cols) {
                    ui.horizontal(|ui| {
                        ui.spacing_mut().item_spacing.x = 0.0;
                        for v in chunk {
                            let (rr, resp) = ui.allocate_exact_size(vec2(cw, 58.0), Sense::click());
                            let p = ui.painter();
                            let sel = self.cfg.version == v.id && self.cfg.active_profile.is_none();
                            let hov = ui.ctx().animate_bool_with_time(Id::new(("vh", &v.id)), resp.hovered(), 0.15);
                            let inner = rr.shrink2(vec2(6.0, 4.0));
                            if hov > 0.0 || sel {
                                p.rect_filled(inner, 2.0, alpha(pal.surface2, if sel { 255 } else { (hov * 200.0) as u8 }));
                            }
                            if sel {
                                p.line_segment([inner.left_top(), inner.left_bottom()], Stroke::new(2.0_f32, pal.accent));
                            }
                            p.text(pos2(inner.left() + 14.0 + hov * 3.0, inner.top() + 20.0), Align2::LEFT_CENTER, &v.id, font(19.0, "semibold"), pal.text);
                            let is_inst = installed.iter().any(|i| i == &v.id);
                            let tag = format!(
                                "{}{}",
                                if latest.as_deref() == Some(v.id.as_str()) { "LATEST RELEASE".to_string() } else { v.kind.to_uppercase() },
                                if is_inst { "  ·  INSTALLED" } else { "" }
                            );
                            p.text(pos2(inner.left() + 14.0 + hov * 3.0, inner.top() + 39.0), Align2::LEFT_CENTER, tag, font(9.0, "mono"), if latest.as_deref() == Some(v.id.as_str()) { pal.accent } else { pal.muted });
                            if client_mods::jar_for(&v.id).is_some() {
                                diamond(p, pos2(inner.right() - 14.0, inner.center().y), 3.0, pal.accent, 0.0);
                            }
                            if resp.on_hover_text(if client_mods::jar_for(&v.id).is_some() { "Peregrine client mods available" } else { "Plain Minecraft (no Peregrine mods for this version)" }).clicked() {
                                self.cfg.version = v.id.clone();
                                self.cfg.active_profile = None;
                            }
                        }
                    });
                }
            });
        });
    }

    // -------------------------------------------------------------- Accounts
    pub(super) fn accounts_page(&mut self, ui: &mut egui::Ui, r: Rect, pal: &Pal, account: &Option<config::Account>, action: &mut Option<Action>) {
        let (prism_found, prism_acc) = {
            let s = self.h.lock();
            (s.prism.clone(), s.prism_account.clone())
        };
        ui.allocate_new_ui(egui::UiBuilder::new().max_rect(r.shrink2(vec2(24.0, 18.0))), |ui| {
            header(ui, pal, "Accounts", "Who flies – your Microsoft account");
            let prism_acc = if self.cfg.use_prism { prism_acc.clone() } else { None };
            let (name, kind, col) = match (account, &prism_acc) {
                (Some(a), _) => (a.name.clone(), "Microsoft account  ·  direct sign-in", GOOD),
                (None, Some(n)) => (n.clone(), "Microsoft account  ·  easy sign-in", GOOD),
                (None, None) => ("Not signed in".to_string(), "Without an account Minecraft runs as a demo", WARN),
            };
            let (rr, _) = ui.allocate_exact_size(vec2(ui.available_width().min(640.0), 150.0), Sense::hover());
            let p = ui.painter();
            p.rect_filled(rr, 2.0, pal.surface);
            p.line_segment([rr.left_top(), rr.left_bottom()], Stroke::new(2.0_f32, col));
            // Initialen als Abzeichen
            let badge = Rect::from_min_size(rr.min + vec2(26.0, 30.0), vec2(90.0, 90.0));
            p.rect_filled(badge, 2.0, pal.surface2);
            p.rect_stroke(badge, 2.0, Stroke::new(1.0_f32, alpha(pal.accent2, 40)));
            let initial = name.chars().next().unwrap_or('?').to_uppercase().to_string();
            p.text(badge.center(), Align2::CENTER_CENTER, initial, font(46.0, "display"), pal.text);
            diamond(p, badge.right_bottom() - vec2(6.0, 6.0), 4.0, col, 0.0);
            p.text(pos2(badge.right() + 26.0, rr.top() + 48.0), Align2::LEFT_CENTER, &name, font(30.0, "display"), pal.text);
            spaced(p, pos2(badge.right() + 26.0, rr.top() + 82.0), Align2::LEFT_CENTER, &kind.to_uppercase(), font(9.5, "mono"), pal.muted, 1.6);
            ui.add_space(18.0);
            ui.horizontal(|ui| {
                if account.is_some() {
                    if ui.button("Sign out").clicked() {
                        *action = Some(Action::Logout);
                    }
                } else if prism_acc.is_some() {
                    if ui.button("Switch account").clicked() {
                        *action = Some(Action::OpenPrism);
                    }
                } else if self.cfg.use_prism && prism_found.is_none() && !prism::can_install() {
                    if ui.button("Download sign-in component").clicked() {
                        let _ = open::that(prism::DOWNLOAD_URL);
                    }
                } else if ui.button("Sign in with Microsoft").clicked() {
                    *action = Some(if self.cfg.use_prism && self.cfg.client_id.is_empty() { Action::OpenPrism } else { Action::Login });
                }
            });
            ui.add_space(20.0);
            card(ui, pal, "Sign-in method", |ui| {
                ui.horizontal(|ui| {
                    theme::toggle(ui, pal, &mut self.cfg.use_prism);
                    ui.label("Easy sign-in (recommended) – you sign in once, it is remembered");
                });
                ui.label(RichText::new("Your password never passes through Peregrine – you sign in on Microsoft's own page.").color(pal.muted).size(12.5));
            });
        });
    }

    // -------------------------------------------------------------- Performance
    pub(super) fn performance_page(&mut self, ui: &mut egui::Ui, r: Rect, pal: &Pal) {
        ui.allocate_new_ui(egui::UiBuilder::new().max_rect(r.shrink2(vec2(24.0, 18.0))), |ui| {
            header(ui, pal, "Performance", "Memory, speed mods, Java – and how your last session ran");
            egui::ScrollArea::vertical().auto_shrink([false; 2]).show(ui, |ui| {
                // ---- system + last session as instrument readouts
                let total_mb = super::pages_ui::total_ram_mb();
                let cores = std::thread::available_parallelism().map(|n| n.get()).unwrap_or(0);
                let session: Option<serde_json::Value> = std::fs::read_to_string(client_mods::shared_dir().join("peregrine-session.json")).ok().and_then(|t| serde_json::from_str(&t).ok());
                let g = |k: &str| session.as_ref().and_then(|s| s.get(k)).and_then(|v| v.as_i64());
                let readouts: Vec<(&str, String, String)> = vec![
                    ("AVG FPS", g("fps_avg").map(|v| v.to_string()).unwrap_or("–".into()), "last session".into()),
                    ("LOW", g("fps_min").map(|v| v.to_string()).unwrap_or("–".into()), "lowest second".into()),
                    ("PEAK", g("fps_max").map(|v| v.to_string()).unwrap_or("–".into()), "highest second".into()),
                    ("SYSTEM RAM", if total_mb > 0 { format!("{:.0} GB", total_mb as f32 / 1024.0) } else { "–".into() }, std::env::consts::OS.to_string()),
                    ("CPU", if cores > 0 { format!("{cores}") } else { "–".into() }, "threads".into()),
                ];
                let (rr, _) = ui.allocate_exact_size(vec2(ui.available_width().min(820.0), 92.0), Sense::hover());
                let p = ui.painter();
                let cw = rr.width() / readouts.len() as f32;
                for (i, (k, v, sub)) in readouts.iter().enumerate() {
                    let x = rr.left() + i as f32 * cw;
                    if i > 0 {
                        p.line_segment([pos2(x, rr.top() + 8.0), pos2(x, rr.bottom() - 8.0)], Stroke::new(1.0_f32, alpha(pal.text, 16)));
                    }
                    spaced(p, pos2(x + 16.0, rr.top() + 14.0), Align2::LEFT_CENTER, k, font(9.0, "monom"), pal.muted, 2.0);
                    p.text(pos2(x + 16.0, rr.top() + 48.0), Align2::LEFT_CENTER, v, font(30.0, "display"), if i < 3 && v != "–" { pal.accent } else { pal.text });
                    p.text(pos2(x + 16.0, rr.top() + 76.0), Align2::LEFT_CENTER, sub.to_uppercase(), font(8.5, "mono"), alpha(pal.muted, 170));
                }
                if session.is_none() {
                    ui.label(RichText::new("FPS numbers appear after your first session with the Peregrine client mods.").color(pal.muted).size(12.0));
                } else if let (Some(m), Some(v)) = (g("minutes"), session.as_ref().and_then(|s| s.get("version")).and_then(|v| v.as_str())) {
                    ui.label(RichText::new(format!("Last session: {m} min on Minecraft {v}.")).color(pal.muted).size(12.0));
                }
                if total_mb > 0 && self.cfg.ram_mb as u64 > total_mb * 3 / 4 {
                    ui.label(RichText::new(format!("Careful: {:.1} GB for Minecraft is more than ¾ of your {:.0} GB – Windows and the launcher need some too.", self.cfg.ram_mb as f32 / 1024.0, total_mb as f32 / 1024.0)).color(WARN).size(12.5));
                }
                ui.add_space(12.0);
                card(ui, pal, "Memory", |ui| {
                    // Speicher als Zellenleiste: jede Zelle 1 GB
                    let gb = self.cfg.ram_mb as f32 / 1024.0;
                    let (rr, resp) = ui.allocate_exact_size(vec2(ui.available_width().min(620.0), 64.0), Sense::click_and_drag());
                    let p = ui.painter();
                    p.text(pos2(rr.left(), rr.top() + 14.0), Align2::LEFT_CENTER, format!("{gb:.1}"), font(34.0, "display"), pal.text);
                    p.text(pos2(rr.left() + 74.0, rr.top() + 20.0), Align2::LEFT_CENTER, "GB", font(11.0, "monom"), pal.muted);
                    let cells = 16;
                    let bar = Rect::from_min_max(pos2(rr.left() + 120.0, rr.top() + 4.0), pos2(rr.right(), rr.top() + 26.0));
                    let cw = bar.width() / cells as f32;
                    for i in 0..cells {
                        let c = Rect::from_min_size(pos2(bar.left() + i as f32 * cw, bar.top()), vec2(cw - 3.0, bar.height()));
                        let on = (i as f32 + 1.0) <= gb;
                        p.rect_filled(c, 1.0, if on { pal.accent } else { alpha(pal.text, 18) });
                    }
                    p.text(pos2(bar.left(), bar.bottom() + 14.0), Align2::LEFT_CENTER, "1 GB", font(9.0, "mono"), pal.muted);
                    p.text(pos2(bar.right(), bar.bottom() + 14.0), Align2::RIGHT_CENTER, "16 GB", font(9.0, "mono"), pal.muted);
                    if let Some(pos) = resp.interact_pointer_pos() {
                        if bar.expand2(vec2(0.0, 8.0)).contains(pos) || resp.dragged() {
                            let f = ((pos.x - bar.left()) / bar.width()).clamp(0.0, 1.0);
                            let g = (f * cells as f32).ceil().max(1.0);
                            self.cfg.ram_mb = (g * 1024.0) as u32;
                        }
                    }
                    ui.horizontal(|ui| {
                        for (label, mb) in [("Light  2 GB", 2048), ("Balanced  4 GB", 4096), ("Modded  6 GB", 6144), ("Heavy  8 GB", 8192)] {
                            let mut on = self.cfg.ram_mb == mb;
                            super::chip(ui, pal, label, &mut on);
                            if on && self.cfg.ram_mb != mb {
                                self.cfg.ram_mb = mb;
                            }
                        }
                    });
                    ui.label(RichText::new("4 GB is right for most players. More only helps with big modpacks – too much can even cause stutters.").color(pal.muted).size(12.5));
                });
                card(ui, pal, "Speed mods", |ui| {
                    ui.horizontal(|ui| {
                        theme::toggle(ui, pal, &mut self.cfg.client.turbo);
                        ui.label("Install performance mods automatically (Fabric)");
                    });
                    if self.cfg.client.turbo {
                        for (slug, name, desc) in client_mods::TURBO {
                            let mut on = !self.cfg.client.turbo_off.iter().any(|o| o == slug);
                            ui.horizontal(|ui| {
                                ui.add_space(14.0);
                                theme::toggle(ui, pal, &mut on);
                                ui.label(RichText::new(name).font(font(14.0, "medium")));
                                ui.label(RichText::new(desc).color(pal.muted).size(12.5));
                            });
                            if on {
                                self.cfg.client.turbo_off.retain(|o| o != slug);
                            } else if !self.cfg.client.turbo_off.iter().any(|o| o == slug) {
                                self.cfg.client.turbo_off.push(slug.to_string());
                            }
                        }
                    }
                });
                card(ui, pal, "Java", |ui| {
                    field(ui, pal, "Java path (empty = the right version is downloaded automatically)", &mut self.cfg.java_path, "automatic");
                    field(ui, pal, "Extra JVM arguments", &mut self.cfg.extra_jvm_args, "e.g. -XX:+UseZGC");
                });
                card(ui, pal, "Window", |ui| {
                    ui.horizontal(|ui| {
                        ui.label(RichText::new("Game window").color(pal.muted));
                        ui.add(egui::DragValue::new(&mut self.cfg.width).range(320..=7680).suffix(" px"));
                        ui.label("×");
                        ui.add(egui::DragValue::new(&mut self.cfg.height).range(240..=4320).suffix(" px"));
                    });
                });
            });
        });
    }
}
