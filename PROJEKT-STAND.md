# Peregrine Client – Projektstand (zum Weitermachen in einem neuen Chat)

> Diese Datei fasst alles zusammen, was bisher gebaut wurde. In einem neuen Chat einfach sagen:
> „Lies `PROJEKT-STAND.md` und mach weiter mit …“

- **Repository:** `enriqueart67/ganovenstadt`, Arbeits-Branch **`claude/youthful-pasteur-m8a12x`**
- Projektübersicht für Menschen: `launcher/README.md`, `client-mod/README.md`.
- Daneben liegt im Repo das ältere Browser-Spiel *Ganovenstadt* (`index.html`, `spiel.html`) – unabhängig vom Launcher.
- Sprache der Oberfläche: **Deutsch**.

---

## 1. Was ist der Peregrine Client?

Ein eigener **Minecraft-Launcher in Rust** (Ordner `launcher/`) plus eine eigene **Fabric-Mod**
(Ordner `client-mod/`) mit Mod-Menü im Spiel – ähnlich wie PvP-Clients (z. B. NoRisk), aber mit
eigenem, sehr klarem Design.

Entwicklung in Schritten: Python-Launcher → Neubau in Rust → Design-Runden → Name „Peregrine Client“
→ Falken-Icon (aus der PDF des Nutzers) → alle Versionen → Profile & Modrinth → Client-Mods →
Mod-Menü im Spiel → eigenes Design „Papier/Nacht“.

---

## 2. Launcher (`launcher/`, Rust, egui/eframe 0.29)

### Funktionen
| Bereich | Was es kann |
|---|---|
| **Spielen** | Alle Minecraft-Versionen aus Mojangs Manifest (Releases, Snapshots, Beta/Alpha), Suche + Filter in der Versionsauswahl. Vanilla, **Fabric**, **Quilt** (Forge/NeoForge **nicht**). Java wird automatisch von Mojang geladen. |
| **Schnell** | Paralleler Download (64 gleichzeitig, HTTP/2), Schnellstart ohne Prüfung bei bereits installierten Versionen (Marker-Datei `.ready-<loader>`), Option „Reparieren“. |
| **Login** | Microsoft-Login (OAuth PKCE → Xbox → XSTS → Minecraft). Braucht **eigene Azure Client-ID** (Einstellungen) + Freischaltung durch Mojang. Ohne Konto: offizieller **Demo-Modus**. |
| **Profile** | Eigene Profile mit eigenem Spielordner (`<spielordner>/instances/<id>`), Version, Loader (optional feste Loader-Version). Auf der Startseite per „Schnellstart“ oder in der Versionsauswahl wählbar. |
| **Modrinth** (nur Modrinth, CurseForge wurde entfernt) | Entdecken & Installieren von **Mods** (mit Abhängigkeiten), **Ressourcenpaketen**, **Shadern** (Iris wird automatisch mitinstalliert), **Datenpaketen** (Welt wählbar), **Modpacks** (.mrpack → neues Profil, gepinnte Loader-Version, Overrides, Titel/Icons per Hash-Lookup). Ein-/Ausschalten (`.disabled`), Entfernen. |
| **Client-Mods** | Legt die passende Peregrine-Mod (26.1/26.2/26.3) vor dem Start in Fabric/Quilt-Profile, installiert fehlende Fabric API. Einstellungen macht man **im Spiel** (Rechts-Shift) – der Launcher schreibt die Mod-Config **nicht**. |
| **Design** | Eigenes Peregrine-Design (siehe 2.2), komplett anpassbar unter „Aussehen“ (Farbschemas, Akzentfarbe, eigene Farben, Hintergrund-Bild/Landschaft, Schrift, Größe, Texte, Export/Import als Code). |
| **Sonstiges** | Konsole mit Spiel-Log, Einstellungen (RAM, JVM-Args, Spielordner, Fenstergröße, Java-Pfad), rahmenloses Fenster, Falken-Icon auch in der Windows-`.exe`. |

### 2.2 Design „Papier/Nacht“ (aktueller Stand)
- Warmes Papier-Weiß `#F3F1EC` (Standard) bzw. „Nacht“ `#0E0E0D`, Text Tinte, **eine** Signalfarbe Orange `#FF4F00`.
- Keine Seitenleiste: **Text-Navigation** in der Titelleiste (START · PROFILE · CLIENT-MODS · AUSSEHEN · KONSOLE · EINSTELLUNGEN).
- Startseite: riesige Versionsnummer (IBM Plex Sans Condensed Bold), Mono-Labels (IBM Plex Mono), rechts „Übersicht“ + „Schnellstart“, unten breite **Spielen-Leiste** (füllt sich beim Laden orange).
- Haarlinien statt Kästen, Primärknöpfe in Tinte (`Pal::primary`), Abschnitte via `card()`/`header()` in `gui.rs`.
- `Look.design = 2` – ältere gespeicherte Looks werden einmalig auf das neue Design gesetzt.

### Dateien
| Datei | Inhalt |
|---|---|
| `src/main.rs` | Start, Fenster (rahmenlos, Icon) |
| `src/gui.rs` | App, Titelleiste/Navigation, Startseite, Versions-Popup, Aussehen, Konsole, Einstellungen, Start-Ablauf (`launch`, `setup_client_mods`), UI-Helfer |
| `src/gui_profiles.rs` | Seite Profile: Liste, Formular, Installiert, Entdecken, Modpack-Browser |
| `src/gui_client.rs` | Seite Client-Mods (an/aus, Tasten, Modulliste) |
| `src/theme.rs` | Design-System: `Look`/`Pal`, Presets, Schriften (`setup_fonts`), Stil, Landschaft, Icons, Logo |
| `src/install.rs` | Manifest, Versions-JSON (inheritsFrom), Bibliotheken/Natives/Assets/Java, Fabric/Quilt, `prepare()` |
| `src/launch.rs` | Java-Kommandozeile bauen, Spiel starten |
| `src/net.rs` | Paralleler Downloader mit SHA1 |
| `src/auth.rs` | Microsoft-Login |
| `src/mods.rs` | Modrinth: Suche, Installation, Modpacks, Verwalten |
| `src/profiles.rs` | Profile speichern/laden, Ordner |
| `src/client_mods.rs` | eingebettete Mod-Jars (`assets/client-mod/*.jar`), `prepare()` |
| `src/config.rs`, `src/state.rs` | Einstellungen/Konto, gemeinsamer Zustand |
| `build.rs` | bettet unter Windows Icon + Versionsinfo in die `.exe` ein (`winresource`) |
| `assets/` | Schriften (Plex, Poppins + OFL), Logo/Icon (`icon.png/.ico/logo.png`, Quelle `peregrine-icon.pdf`), Client-Mod-Jars |

Daten des Nutzers: `~/.peregrine/` (`config.json`, `account.json`, `profiles.json`, `runtime/` (Java), `minecraft/` (Spielordner, `instances/`)).

### Bauen
```bash
cd launcher
cargo build --release                 # Linux
cargo test --release                  # 11 Tests (+1 Live-Test: cargo test --release live -- --ignored)
# Windows-.exe von Linux aus:
rustup target add x86_64-pc-windows-gnu && apt install mingw-w64
CARGO_TARGET_X86_64_PC_WINDOWS_GNU_LINKER=x86_64-w64-mingw32-gcc cargo build --release --target x86_64-pc-windows-gnu
```
GitHub Actions (`.github/workflows/launcher.yml`, „Peregrine Client bauen“) baut Windows/Linux/macOS als Artefakte.

---

## 3. Client-Mod (`client-mod/`, Java 25, Fabric, Loom 1.19.4, Gradle 9.8)

- Minecraft 26.x ist **nicht mehr verschleiert** → Mojang-Namen direkt, keine Mappings (Plugin `net.fabricmc.fabric-loom`).
- Gebaut für **26.1 (26.1.2), 26.2, 26.3** mit `./build-all.sh` (kopiert nach `launcher/assets/client-mod/peregrine-client-26.x.jar`). Danach Launcher neu bauen (Jars werden per `include_bytes!` eingebettet).
- Braucht **JDK 25** (`JAVA_HOME`).
- **Mod-Menü im Spiel: Rechts-Shift.** Kategorien (Alle, HUD, PvP, Grafik, Bewegung, Sonstiges), Suche, Kacheln mit Schalter, Zahnrad = Einstellungen (Schalter, Schieberegler, Auswahl, Farbpalette), „HUD bearbeiten“ (Drag & Drop, Mausrad = Größe, Einrasten; nicht verschobene Anzeigen ordnen sich automatisch an).
- **22 Module:** FPS, CPS, Koordinaten, Keystrokes, Rüstung, Trank-Effekte, Uhrzeit, Ping, Server-Adresse, Geschwindigkeit, Tageszähler, Arbeitsspeicher, Reichweite, Combo-Zähler · Fadenkreuz, Kein Schadens-Wackeln · Zoom (C), Fullbright (G), Kein Regen · Auto-Sprint (V), Schleichen umschalten · HUD-Stil.
- Speichert in `config/peregrine-client.json` des Profils (`format: 2`, pro Modul `enabled`, `placed`, `x`, `y`, `settings`).
- Wichtige Klassen: `PeregrineClient` (Init, Tasten, HUD), `Modules` (alle Module), `module/*` (Basis), `setting/*`, `gui/ClickGui`, `gui/HudEditor`, `gui/Ui` (öffnet Screens per Reflection: 26.1 `Minecraft.setScreen`, 26.2+ `Gui.setScreen`), `Store`, Mixins (`CameraMixin` Zoom, `MouseHandlerMixin` CPS, `OptionInstanceAccessor` Fullbright, `GameRendererMixin` Hurt-Cam, `LevelMixin` Regen).
- Unterschiede 26.1/26.2/26.3 bisher: Bildschirm öffnen (s. o.), 26.3 nutzt SDL statt GLFW (daher `InputConstants.KEY_*` statt GLFW-Konstanten).

---

## 4. Test-Umgebung / Hinweise für Claude
- Cloud-Umgebung **„ganovenstadt“** erlaubt: Mojang-, Fabric-, Quilt-, Modrinth-, CurseForge-Hosts (+ Paketquellen). Ohne diese Umgebung sind Mojang/Modrinth gesperrt.
- Minecraft **26.2 rendert** im Container (Xvfb + llvmpipe) → In-Game-Tests mit Screenshots möglich (`Xvfb :77`, `xdotool`, `import`). **26.3 rendert dort nicht** (neues SDL-Grafik-Backend).
- Für Screenshots des Launchers: `DISPLAY=:77 WAYLAND_DISPLAY= LIBGL_ALWAYS_SOFTWARE=1 ./target/release/peregrine`.
- Ein CurseForge-API-Key ist in der Umgebung hinterlegt, wird aber nicht mehr gebraucht (CurseForge entfernt).
- Commits enden mit den Attributions-Zeilen (Co-Authored-By / Claude-Session). Kein Pull Request ohne ausdrücklichen Wunsch.

---

## 5. Bekannte Grenzen
- Microsoft-Login braucht eigene Azure-App + Mojang-Freischaltung (sonst nur Demo).
- Kein Forge/NeoForge (Profile, Modpacks, Mods nur Fabric/Quilt).
- Client-Mod nur für 26.1–26.3; In-Game nur auf 26.2 getestet. Rüstung/Trank/Reichweite/Combo/Hurt-Cam/Regen/Toggle-Sneak nicht im Spiel getestet.
- Keine Updates für installierte Modrinth-Inhalte; keine Zeitänderung (26.3 hat neue Uhr-Logik).
- Die `.exe` ist nicht signiert (Windows SmartScreen kann warnen → „Weitere Informationen“ › „Trotzdem ausführen“).

## 6. Mögliche nächste Schritte
- Mod-Menü im Spiel im Papier/Orange-Look des Launchers gestalten.
- Weitere Module: Freelook, Chat-Zeitstempel, wenig Feuer, Waypoints, Blockumrandung.
- „Alles aktualisieren“ für Modrinth-Inhalte; Forge/NeoForge; Skin-Kopf als Konto-Bild.
- Signierte Releases über GitHub Actions (Release-Workflow).
