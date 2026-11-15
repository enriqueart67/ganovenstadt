//! Die Start-Sequenz: Aktivierung des PLAY-Rings, Sturzflug des Falken, eigener Startbildschirm mit echten
//! Ladephasen und Abflug, sobald Minecraft wirklich geladen ist. Alles haengt an echten Ereignissen
//! (Status-Meldungen, Datei-Fortschritt, Log-Zeilen des Spiels) – es wird nie Fortschritt vorgetaeuscht.
use std::time::Instant;

use eframe::egui::{self, pos2, vec2, Align2, Color32, Id, Pos2, Rect, Sense, Shape, Stroke};

use super::stoop_ui::{diamond, spaced};
use super::{App, Page};
use crate::theme::{alpha, font, Pal, BAD};

pub struct LaunchAnim {
    pub at: Instant,
    pub title: String,
    pub hidden: bool,
    stage: usize,
    saw_busy: bool,
    outcome: u8, // 0 running, 1 in game, 2 failed, 3 ended without starting
    done_at: Option<Instant>,
    message: String,
}

impl LaunchAnim {
    pub fn new(title: String) -> Self {
        Self { at: Instant::now(), title, hidden: false, stage: 0, saw_busy: false, outcome: 0, done_at: None, message: String::new() }
    }
    /// Activation glow of the PLAY ring (1 right after the click, fading).
    pub fn activation(&self) -> f32 {
        (1.0 - self.at.elapsed().as_secs_f32() / 0.9).clamp(0.0, 1.0)
    }
}

const STAGES: [&str; 5] = ["Initializing Peregrine", "Preparing Minecraft", "Loading libraries", "Initializing mods", "Starting game"];

fn ease_out(t: f32) -> f32 {
    let t = t.clamp(0.0, 1.0);
    1.0 - (1.0 - t).powi(4)
}

fn ease_in(t: f32) -> f32 {
    let t = t.clamp(0.0, 1.0);
    t * t * t
}

fn arc(p: &egui::Painter, c: Pos2, r: f32, a0: f32, a1: f32, stroke: Stroke) {
    let n = ((a1 - a0).abs() * 24.0).max(2.0) as usize;
    let pts: Vec<Pos2> = (0..=n).map(|i| {
        let a = a0 + (a1 - a0) * i as f32 / n as f32;
        pos2(c.x + a.cos() * r, c.y + a.sin() * r)
    }).collect();
    p.add(Shape::line(pts, stroke));
}

fn wrap(text: &str, max: usize) -> Vec<String> {
    let mut out = vec![String::new()];
    for w in text.split_whitespace() {
        let last = out.last_mut().unwrap();
        if !last.is_empty() && last.len() + w.len() + 1 > max {
            out.push(w.to_string());
        } else {
            if !last.is_empty() {
                last.push(' ');
            }
            last.push_str(w);
        }
    }
    out
}

impl App {
    /// Called when Play is pressed (before the launch itself starts).
    pub(super) fn begin_launch_sequence(&mut self) {
        if self.h.lock().busy {
            return;
        }
        let title = match self.active_profile() {
            Some(p) => format!("{}  ·  {} {}", p.name, p.loader, p.version),
            None => format!("Minecraft {}  ·  {}", self.cfg.version, self.cfg.loader),
        };
        {
            let mut s = self.h.lock();
            s.game_stage = 0;
            s.error = None;
        }
        self.launch_anim = Some(LaunchAnim::new(title.to_uppercase()));
    }

    /// Draws the launch screen over everything (except the title bar) and follows the real launch.
    pub(super) fn launch_overlay(&mut self, ctx: &egui::Context, pal: &Pal, t: f32) {
        let Some(mut anim) = self.launch_anim.take() else { return };
        let (busy, status, done, total, stage_game, error) = {
            let s = self.h.lock();
            (s.busy, s.status.clone(), s.done, s.total, s.game_stage, s.error.clone())
        };
        let el = anim.at.elapsed().as_secs_f32();
        if busy {
            anim.saw_busy = true;
        }
        // ---- follow the real launch
        if anim.outcome == 0 {
            if let Some(e) = error.clone() {
                anim.outcome = 2;
                anim.message = e;
                anim.done_at = Some(Instant::now());
            } else if stage_game == 3 {
                anim.outcome = 1;
                anim.done_at = Some(Instant::now());
            } else if stage_game == 9 {
                anim.outcome = 3;
                anim.message = "Minecraft closed before it finished loading – the console has the details.".into();
                anim.done_at = Some(Instant::now());
            } else if status.to_lowercase().contains("running") && stage_game < 2 && el > 90.0 {
                anim.outcome = 1; // game runs without a log we can read (e.g. detached) – stop waiting
                anim.done_at = Some(Instant::now());
            } else if !busy && el > 2.5 && !status.to_lowercase().contains("running") && stage_game == 0 {
                anim.outcome = 3; // nothing started, e.g. sign-in needed
                anim.message = status.clone();
                anim.done_at = Some(Instant::now());
            }
            let st = status.to_lowercase();
            let idx = if stage_game >= 1 || st.contains("starting minecraft") || st.contains("running") {
                4
            } else if st.contains("client mods") || st.contains("fabric api") || st.contains("installing fabric") || st.contains("installing quilt") || st.contains("loader") {
                3
            } else if total > 0 || st.contains("download") || st.contains("install") || st.contains("natives") || st.contains("assets") || st.contains("java") || st.contains("files") {
                2
            } else if el > 0.9 {
                1
            } else {
                0
            };
            anim.stage = anim.stage.max(idx);
        }
        let since_done = anim.done_at.map(|d| d.elapsed().as_secs_f32()).unwrap_or(0.0);
        let finished = match anim.outcome {
            1 => since_done > 2.2,
            3 => since_done > 3.2,
            _ => false,
        };
        if finished {
            return; // overlay gone
        }
        if anim.hidden && anim.outcome != 2 {
            self.launch_anim = Some(anim);
            return;
        }

        let screen = ctx.screen_rect();
        let area = Rect::from_min_max(pos2(screen.left(), screen.top() + 44.0), screen.max);
        let mut close = false;
        let mut open_console = false;
        let mut hide = false;
        egui::Area::new(Id::new("launchseq")).order(egui::Order::Foreground).fixed_pos(area.min).show(ctx, |ui| {
            ui.set_min_size(area.size());
            ui.allocate_rect(area, Sense::click()); // nothing underneath reacts while the sequence runs
            let p = ui.painter().with_clip_rect(area);
            // fade out at the end
            let out = match anim.outcome {
                1 => ease_in(((since_done - 1.0) / 1.2).max(0.0)),
                3 => ease_in(((since_done - 2.2) / 1.0).max(0.0)),
                _ => 0.0,
            };
            let vis = 1.0 - out;
            let a = |v: u8| ((v as f32) * vis) as u8;
            let ring = self.stoop.ring_c.unwrap_or(area.center());
            let center = pos2(area.center().x, area.top() + area.height() * 0.42);

            // ---- phase 1: darken + icy wave from the PLAY ring
            let dark = ease_out(el / 0.6);
            p.rect_filled(area, 0.0, alpha(pal.bg, a((dark * 246.0) as u8)));
            if el < 1.2 {
                let w = ease_out(el / 1.1);
                let rad = w * area.width().max(area.height()) * 0.9;
                p.circle_stroke(ring, rad, Stroke::new(2.0_f32, alpha(pal.accent, a(((1.0 - w) * 150.0) as u8))));
                p.circle_stroke(ring, rad * 0.72, Stroke::new(1.0_f32, alpha(pal.accent2, a(((1.0 - w) * 80.0) as u8))));
                super::stoop_ui::glow_at(&p, ring, 260.0 * (0.4 + w), 90.0 * (0.4 + w), pal.accent, (1.0 - w) * 60.0 * vis);
            }
            // atmosphere of the launch screen
            if el > 0.4 {
                let k = ease_out((el - 0.4) / 0.8) * vis;
                super::stoop_ui::glow_at(&p, center, area.width() * 0.45, area.height() * 0.42, pal.accent, 26.0 * k + 6.0 * (t * 0.6).sin().abs() * k);
                // particles drifting upwards like air past a diving bird
                for i in 0..60u32 {
                    let r1 = super::stoop_ui::rnd_pub(i * 3 + 1);
                    let r2 = super::stoop_ui::rnd_pub(i * 3 + 2);
                    let sp = 40.0 + r2 * 160.0;
                    let y = area.bottom() - ((t * sp + r1 * 2000.0) % (area.height() + 40.0));
                    let x = area.left() + r2 * area.width() + (t * 0.3 + r1 * 6.0).sin() * 10.0;
                    p.line_segment([pos2(x, y), pos2(x, y + 6.0 + r1 * 18.0)], Stroke::new(1.0_f32, alpha(pal.accent2, (10.0 + r1 * 40.0 * k) as u8)));
                }
            }

            // ---- the loader: precise orbital ring, follows only the real progress
            if el > 0.35 {
                let k = ease_out((el - 0.35) / 0.7);
                // success: the ring closes, widens and fades
                let grow = if anim.outcome == 1 { 1.0 + ease_out((since_done - 0.3) / 1.2) * 0.35 } else { 1.0 };
                let r = 92.0 * (0.6 + 0.4 * k) * grow;
                let fade_ring = vis * k;
                let ring_a = |v: f32| (v * fade_ring).clamp(0.0, 255.0) as u8;
                // real fraction: finished stages + file progress inside "Loading libraries"
                let sub = if anim.stage == 2 && total > 0 { (done as f32 / total as f32).clamp(0.0, 1.0) } else { 0.0 };
                let target = if anim.outcome == 1 { 1.0 } else { ((anim.stage as f32 + sub) / 5.0).min(0.999) };
                let shown_f = ctx.animate_value_with_time(Id::new("loaderf"), target, 0.45);
                let top = -std::f32::consts::FRAC_PI_2;
                // track + stage notches
                arc(&p, center, r, 0.0, std::f32::consts::TAU, Stroke::new(1.0_f32, alpha(pal.text, ring_a(30.0))));
                for i in 0..5 {
                    let a = top + i as f32 / 5.0 * std::f32::consts::TAU;
                    let dir = vec2(a.cos(), a.sin());
                    let reached = (i as f32) / 5.0 <= shown_f + 0.001;
                    p.line_segment([center + dir * (r - 5.0), center + dir * (r + 5.0)], Stroke::new(1.2_f32, alpha(if reached { pal.accent } else { pal.text }, ring_a(if reached { 230.0 } else { 60.0 }))));
                }
                // inner instrument dial, slowly turning
                for i in 0..72 {
                    let a = i as f32 / 72.0 * std::f32::consts::TAU + t * 0.08;
                    let dir = vec2(a.cos(), a.sin());
                    let len = if i % 6 == 0 { 6.0 } else { 2.5 };
                    p.line_segment([center + dir * (r * 0.78), center + dir * (r * 0.78 - len)], Stroke::new(1.0_f32, alpha(pal.text, ring_a(if i % 6 == 0 { 46.0 } else { 20.0 }))));
                }
                // progress arc with a soft tail and a glowing head
                if shown_f > 0.001 {
                    let a1 = top + shown_f * std::f32::consts::TAU;
                    let tail = (shown_f * std::f32::consts::TAU).min(1.6);
                    let steps = 28;
                    for s2 in 0..steps {
                        let f0 = s2 as f32 / steps as f32;
                        let f1 = (s2 + 1) as f32 / steps as f32;
                        let b0 = a1 - tail * (1.0 - f0);
                        let b1 = a1 - tail * (1.0 - f1);
                        arc(&p, center, r, b0, b1, Stroke::new(2.6_f32, alpha(pal.accent, ring_a(40.0 + 215.0 * f1))));
                    }
                    if shown_f * std::f32::consts::TAU > tail {
                        arc(&p, center, r, top, a1 - tail, Stroke::new(2.6_f32, alpha(pal.accent, ring_a(40.0))));
                    }
                    let head = center + vec2(a1.cos(), a1.sin()) * r;
                    super::stoop_ui::glow_at(&p, head, 22.0, 22.0, pal.accent, 90.0 * fade_ring);
                    p.circle_filled(head, 3.2, alpha(Color32::WHITE, ring_a(235.0)));
                }
                // activity (not progress): a faint light orbiting while work is going on
                if anim.outcome == 0 {
                    let a = t * 1.7;
                    let o = center + vec2(a.cos(), a.sin()) * (r + 12.0);
                    p.circle_filled(o, 1.6, alpha(pal.accent2, ring_a(110.0)));
                    arc(&p, center, r + 12.0, a - 0.5, a, Stroke::new(1.0_f32, alpha(pal.accent2, ring_a(50.0))));
                }
                // centre: current stage
                let cur = if anim.outcome == 1 { 5 } else { anim.stage + 1 };
                p.text(center + vec2(0.0, -6.0), Align2::CENTER_CENTER, format!("{cur:02}"), font(44.0 * (0.9 + 0.1 * k), "display"), alpha(pal.text, ring_a(255.0)));
                spaced(&p, center + vec2(0.0, 26.0), Align2::CENTER_CENTER, "OF 05", font(9.0, "monom"), alpha(pal.muted, ring_a(220.0)), 2.6);
            }

            // ---- wordmark, title, stages
            if el > 0.9 {
                let k = ease_out((el - 0.9) / 0.6);
                let r = 96.0;
                // wordmark: letters close in while it appears
                let wk = ease_out((el - 1.0) / 0.8);
                spaced(&p, pos2(center.x, center.y + r + 46.0), Align2::CENTER_CENTER, "PEREGRINE", font(26.0, "display"), alpha(pal.text, a((255.0 * wk) as u8)), 9.0 + (1.0 - wk) * 26.0);
                spaced(&p, pos2(center.x, center.y + r + 76.0), Align2::CENTER_CENTER, &anim.title, font(10.0, "mono"), alpha(pal.muted, a((230.0 * wk) as u8)), 2.4);

                // stage line
                let y = center.y + r + 130.0;
                let total_w = (area.width() - 120.0).min(860.0);
                let step = total_w / 5.0;
                for (i, label) in STAGES.iter().enumerate() {
                    let x = center.x - total_w / 2.0 + step * (i as f32 + 0.5);
                    let done_i = i < anim.stage || anim.outcome == 1;
                    let cur = i == anim.stage && anim.outcome == 0;
                    let col = if anim.outcome == 2 && i == anim.stage { BAD } else if done_i { pal.accent } else if cur { pal.text } else { alpha(pal.muted, 120) };
                    let pulse = if cur { (t * 1.1) % 1.0 } else { 0.0 };
                    diamond(&p, pos2(x, y), 3.4, alpha(col, a((255.0 * k) as u8)), pulse);
                    if i < 4 {
                        p.line_segment([pos2(x + 10.0, y), pos2(x + step - 10.0, y)], Stroke::new(1.0_f32, alpha(if done_i { pal.accent } else { pal.text }, a(if done_i { 120 } else { 24 }))));
                    }
                    p.text(pos2(x, y + 20.0), Align2::CENTER_CENTER, label.to_uppercase(), font(9.0, "monom"), alpha(col, a((255.0 * k) as u8)));
                }
                // what is happening right now (real status)
                let (line, col) = match anim.outcome {
                    1 => ("In flight – have fun".to_string(), pal.accent),
                    2 => ("Launch aborted".to_string(), BAD),
                    3 => (if anim.message.is_empty() { "Stopped".into() } else { anim.message.clone() }, pal.muted),
                    _ => {
                        let mut s = match stage_game {
                            2 => "Minecraft window is open – loading resources".to_string(),
                            1 => "Starting Minecraft – Java is warming up".to_string(),
                            _ if status.to_lowercase().contains("running") => "Starting Minecraft – Java is warming up".to_string(),
                            _ => status.trim_end_matches('.').trim_end_matches(" …").to_string(),
                        };
                        if total > 0 && anim.stage == 2 {
                            s = format!("{s}   {done} / {total}");
                        }
                        (s, pal.text)
                    }
                };
                p.text(pos2(center.x, y + 54.0), Align2::CENTER_CENTER, line, font(13.5, "medium"), alpha(col, a((255.0 * k) as u8)));
                if anim.outcome == 2 {
                    let mut yy = y + 80.0;
                    for l in wrap(&anim.message, 90).into_iter().take(4) {
                        p.text(pos2(center.x, yy), Align2::CENTER_CENTER, l, font(12.0, "regular"), pal.muted);
                        yy += 18.0;
                    }
                    let b1 = Rect::from_center_size(pos2(center.x - 80.0, yy + 30.0), vec2(140.0, 36.0));
                    let b2 = Rect::from_center_size(pos2(center.x + 80.0, yy + 30.0), vec2(140.0, 36.0));
                    let r1 = ui.interact(b1, Id::new("lsback"), Sense::click());
                    let r2 = ui.interact(b2, Id::new("lscon"), Sense::click());
                    p.rect_filled(b1, 2.0, if r1.hovered() { pal.accent } else { pal.text });
                    p.text(b1.center(), Align2::CENTER_CENTER, "BACK", font(11.0, "monom"), pal.bg);
                    p.rect_stroke(b2, 2.0, Stroke::new(1.0_f32, if r2.hovered() { pal.text } else { alpha(pal.text, 60) }));
                    p.text(b2.center(), Align2::CENTER_CENTER, "CONSOLE", font(11.0, "monom"), pal.text);
                    close = r1.clicked();
                    open_console = r2.clicked();
                } else if anim.outcome == 0 && el > 2.0 {
                    let hr = Rect::from_center_size(pos2(center.x, area.bottom() - 34.0), vec2(260.0, 24.0));
                    let rh = ui.interact(hr, Id::new("lshide"), Sense::click());
                    spaced(&p, hr.center(), Align2::CENTER_CENTER, "CONTINUE IN BACKGROUND", font(9.0, "monom"), if rh.hovered() { pal.text } else { alpha(pal.muted, 160) }, 2.4);
                    hide = rh.clicked();
                }
            }
        });
        ctx.request_repaint();
        if close || open_console {
            self.h.lock().error = None;
            if open_console {
                self.page = Page::Console;
            }
            return;
        }
        if hide {
            anim.hidden = true;
        }
        self.launch_anim = Some(anim);
    }
}
