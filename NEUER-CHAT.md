# Übergabe für einen neuen Chat – Peregrine Client

**Diesen Text in den neuen Chat einfügen** (oder sagen: „Lies NEUER-CHAT.md und PROJEKT-STAND.md im Repo und mach weiter“).

---

## Projekt
- **Repo:** `enriqueart67/ganovenstadt`, Arbeits-Branch **`claude/laughing-babbage-h788yo`** (darauf committen und pushen, keinen PR ohne Nachfrage).
- **Peregrine Client** = eigener Minecraft-Launcher (Rust, `launcher/`) + Fabric-Client-Mod (Java, `client-mod/`) für **Minecraft 26.1, 26.2 und 26.3**.
- **Ausführliche Doku aller Teile:** `PROJEKT-STAND.md` (Deutsch, Abschnitt „0.“ und folgende Stichpunkte – zuerst lesen).
- **Download der fertigen Windows-.exe:** https://github.com/enriqueart67/ganovenstadt/raw/claude/laughing-babbage-h788yo/downloads/Peregrine_Client.exe

## Was der Nutzer will (Stil)
- Antworten **auf Deutsch**, Texte im Launcher und in der Mod **auf Englisch**, nichts soll „nach KI aussehen“.
- Er testet unter **Windows**; im Container gibt es nur Linux + Xvfb (Software-Grafik, 1–10 FPS).
- Schnell sichtbare Ergebnisse (Screenshots schicken), ehrlich sagen, was nicht getestet ist.
- Grenzen, die bisher gelten: Bot/PvP/ESP nur **Einzelspieler, eigene LAN-Welt, Server mit Operator-Recht oder Server, der es per Plugin-Nachricht `peregrine:features` erlaubt** (`feature/Access`); keine frei eintragbare Server-Liste; Combat Aura **nur Einzelspieler**; keine fremden Microsoft-Client-IDs, kein Offline-Bypass.

## Bauen
- **Mod (alle drei Versionen + Kopie in den Launcher):**
  `cd client-mod && JAVA_HOME=<JDK 25> ./build-all.sh` → legt `launcher/assets/client-mod/peregrine-client-26.x.jar` ab (werden in den Launcher eingebettet).
  Nur 26.2 schnell: `./gradlew build --offline -q`.
  JDK im alten Container lag unter `~/.peregrine/runtime/java-runtime-epsilon` des Test-Home; in einem neuen Container ggf. JDK 25 installieren oder vom Launcher laden lassen. **`JAVA_TOOL_OPTIONS` nicht leeren** (Proxy-Truststore).
- **Launcher Linux:** `cd launcher && cargo build --release`
- **Launcher Windows (.exe):** `CARGO_TARGET_X86_64_PC_WINDOWS_GNU_LINKER=x86_64-w64-mingw32-gcc cargo build --release --target x86_64-pc-windows-gnu`, dann `cp target/x86_64-pc-windows-gnu/release/peregrine.exe ../downloads/Peregrine_Client.exe`
- Reihenfolge nach Mod-Änderungen: build-all → Launcher bauen (bettet Jars ein) → .exe kopieren → committen → pushen.

## Versionsunterschiede (wichtig beim Programmieren der Mod)
- Mojang-Namen (keine Mappings). Unterschiede 26.1/26.2/26.3 per Reflection gelöst: Kamera-Getter (`Waypoints.camera`), `Gui.setScreen`/`screen()` (`Ui.open`, `Ui.current`), `swing` (`Bot.swing`), `InputConstants.isKeyDown`, `Screenshot`-RenderTarget, `Util…openFile`.
- Immer `InputConstants.MOUSE_BUTTON_LEFT` (26.3 = 1, sonst 0). 26.3 benennt `com.mojang.blaze3d` teilweise in `renderpearl` um → kein direktes 3D-Welt-Rendering, Overlays laufen über die HUD-Ebene + `feature/Projector`.
- Vor neuen Minecraft-APIs mit `javap` in allen drei Jars prüfen (`~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/<v>/…jar`).
- Neue riskante Mixins in `peregrine.optional.mixins.json` (required:false).

## Testen im Container (Skripte waren im Scratchpad, nicht im Repo – bei Bedarf neu anlegen)
- Xvfb `:82`, Launcher mit `HOME=<Test-Home>` und `use_prism:false` starten, im Launcher „PLAY“ klicken, im **Vanilla**-Titel „Play Demo World“ (bei 1280×720 ca. 640,353) klicken, dann `xdotool` + `import` für Screenshots.
- Bei `pkill -f java-runtime-epsilon` im selben Befehl tötet man die eigene Shell → Testskripte mit `setsid -w` starten.
- Mod-Einstellungen für Tests direkt in `<Test-Home>/.peregrine/client/peregrine-client.json` setzen (Module unter `modules.<id>.enabled/settings`).

## Stand (Kurzfassung – Details in PROJEKT-STAND.md)
- **Launcher (Design „Stoop“):** fast schwarz/Eisblau, Höhenband-Navigation, Startseite mit 3D-Figur auf dem PLAY-Ring, Hintergrundbild „Peak“ (Berg des Nutzers, `launcher/assets/backgrounds/peak.jpg`), Seiten Home/Mods (Bibliothek mit echten Schaltern)/Versions/Accounts/Performance/Settings (+ Profiles, Servers, Screenshots, Appearance, Console), Start-Sequenz mit Lade-Ring an echte Ereignisse gekoppelt (`gui_launch.rs`). Anmeldung über unsichtbares Prism.
- **Mod:** ~48 Module (HUD, Combat, Render, Movement, World, Cosmetics, Misc), Mod-Menü (Rechte Shift) im Launcher-Look mit Vektor-Zeichner `gui/Px` (rund, hochauflösend), Kosmetik (3D-Flügel, Hüte, Haustiere, Foto-Umhänge), Emotes, Wegpunkte, Minimap, **Block ESP (X)**, **Entity ESP (J)**, **Combat Aura (R, nur SP)**, **Emergency logout**, Titelbildschirm = Vanilla.
- **KI-Assistent (K):** Befehle Deutsch/Englisch; Planer baut Werkzeuge/Rüstung/Fackeln selbst (craftet/schmilzt nur im SP), A*-Wege mit Gefahrenkarte aus Entity ESP, findet Erze per Block-ESP-Engine (`OreFinder`), Selbstschutz (`Guard`), eigene gespeicherte Befehle, PvP (`Combat`: Crystal, Mace, Schwert, Attribute Swap, Windladung, Perlen, Pearl Catch, Lunge, Stun Slam, Elytra-Mace), Notfall-Ausloggen mit Screenshot bei ½ Herz.

## Offen / ungetestet
- Alles unter echtem Windows; Prism-Vorstart; 26.1/26.3 im Spiel.
- PvP-Techniken gegen echte Spieler (Crystal, Mace, Perlen, Lunge, Stun Slam, Elytra-Mace).
- Forge/NeoForge im Launcher (nicht eingebaut).
- Mögliche nächste Idee (vom Nutzer noch nicht bestätigt): „Kampf-Aufnahme“, aus der der Bot den Stil des Spielers lernt.
