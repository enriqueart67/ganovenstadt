//! Bettet unter Windows das Falken-Icon und die Programm-Infos in die .exe ein
//! (sichtbar im Explorer, auf Verknuepfungen und in den Datei-Eigenschaften).
fn main() {
    println!("cargo:rerun-if-changed=assets/logo/icon.ico");
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("windows") {
        let mut res = winresource::WindowsResource::new();
        res.set_icon("assets/logo/icon.ico")
            .set("ProductName", "Peregrine Client")
            .set("FileDescription", "Peregrine Client – Minecraft-Launcher")
            .set("InternalName", "peregrine.exe")
            .set("OriginalFilename", "peregrine.exe");
        if let Err(e) = res.compile() {
            println!("cargo:warning=Icon konnte nicht eingebettet werden: {e}");
        }
    }
}
