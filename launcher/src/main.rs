#![cfg_attr(all(windows, not(debug_assertions)), windows_subsystem = "windows")]
//! Peregrine Client – ein schneller Minecraft-Launcher in Rust.
mod auth;
mod client_mods;
mod config;
mod gui;
mod install;
mod launch;
mod mods;
mod net;
mod profiles;
mod state;
mod theme;

fn window_icon() -> eframe::egui::IconData {
    let img = image::load_from_memory(include_bytes!("../assets/logo/icon.png")).expect("Icon").to_rgba8();
    eframe::egui::IconData { width: img.width(), height: img.height(), rgba: img.into_raw() }
}

fn main() -> eframe::Result<()> {
    let opts = eframe::NativeOptions {
        viewport: eframe::egui::ViewportBuilder::default()
            .with_title(config::LAUNCHER_NAME)
            .with_inner_size([1100.0, 680.0])
            .with_min_inner_size([960.0, 600.0])
            .with_decorations(false)
            .with_icon(std::sync::Arc::new(window_icon())),
        ..Default::default()
    };
    eframe::run_native(config::LAUNCHER_NAME, opts, Box::new(|cc| Ok(Box::new(gui::App::new(cc)))))
}
