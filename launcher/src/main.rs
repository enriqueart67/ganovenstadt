#![cfg_attr(all(windows, not(debug_assertions)), windows_subsystem = "windows")]
//! Ganovenstadt-Launcher – ein schneller Minecraft-Launcher in Rust.
mod auth;
mod config;
mod gui;
mod install;
mod launch;
mod net;
mod state;
mod theme;

fn main() -> eframe::Result<()> {
    let opts = eframe::NativeOptions {
        viewport: eframe::egui::ViewportBuilder::default()
            .with_title(config::LAUNCHER_NAME)
            .with_inner_size([1100.0, 680.0])
            .with_min_inner_size([960.0, 600.0])
            .with_decorations(false),
        ..Default::default()
    };
    eframe::run_native(config::LAUNCHER_NAME, opts, Box::new(|cc| Ok(Box::new(gui::App::new(cc)))))
}
