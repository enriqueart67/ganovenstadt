# Peregrine Client – Projektstand (zum Weitermachen in einem neuen Chat)

> Diese Datei fasst alles zusammen, was bisher gebaut wurde. In einem neuen Chat einfach sagen:
> „Lies `PROJEKT-STAND.md` und mach weiter mit …“

- **Repository:** `enriqueart67/ganovenstadt`, Arbeits-Branch **`claude/laughing-babbage-h788yo`** (davor `claude/youthful-pasteur-m8a12x`)
- Projektübersicht für Menschen: `launcher/README.md`, `client-mod/README.md`.
- Daneben liegt im Repo das ältere Browser-Spiel *Ganovenstadt* (`index.html`, `spiel.html`) – unabhängig vom Launcher.
- Sprache der Oberfläche: **Deutsch**.

---

## 0. Stand nach der großen Runde „Englisch + Look + Features“ (zuerst lesen)

- **Sprache:** Launcher und Mod sind komplett **Englisch**. Alte deutsche Optionsnamen in `config.json` werden per `Look::migrate()` umgestellt. Mod-Modusnamen (z. B. „Left | Right“) sind englisch – alte gespeicherte Werte fallen auf den Standard zurück. `PROJEKT-STAND.md` bleibt deutsch (für den Nutzer).
- **Look im Spiel:** Die Mod liefert IBM Plex als TTF mit (`assets/peregrine/font/*.json|ttf`, `gui/Type.java`). Mod-Menü (`gui/ClickGui`), Emote-Rad, Wegpunkt-Liste und der eigene Startbildschirm (`gui/TitleMenu`, ersetzt den Vanilla-Titel über `ScreenEvents.AFTER_INIT`; Modul „Main menu“, Link „VANILLA MENU“, bei Fehlern automatisch zurück zu Vanilla) nutzen dieselbe Typografie wie der Launcher: Haarlinien, Mono-Labels, eine Akzentfarbe, keine Emoji-Symbole. Farben kommen aus `theme.json` des Launchers.
- **Neue Module (40 gesamt):** Minimap (`Minimap.java`, 128×128-Textur, 4×/s), Waypoints (`Waypoints.java`, Taste M → `gui/WaypointScreen`, Marker per Projektion im HUD + auf der Minimap, „Last death“), Time (`ClockMixin` auf `ClientClockManager.getTotalTicks`), Weather (Clear/Rain/Storm), Low fire (`FireOverlayMixin`), Block outline (`BlockOutlineMixin`), Mention highlight, Main menu; 16 Emotes auf 2 Seiten (Mausrad).
- **Sicherheit bei Versionsunterschieden:** Alle neueren Mixins liegen in `peregrine.optional.mixins.json` (`required: false`). Getestet: Ein Mixin mit falscher Signatur wird nur übersprungen (WARN im Log), das Spiel startet trotzdem. Nur die 5 alten Kern-Mixins sind Pflicht. Versionsunterschiede bisher: `GameRenderer.mainCamera()` (26.1: `getMainCamera()`, per Reflection), `OptionsScreen`-Konstruktor (26.3 anders, per Reflection), Maustasten-Konstanten (26.3).
- **Launcher neu:** Seite **Servers** (`servers.rs` Status-Ping, `gui_extra.rs`; „Join“ startet mit `--quickPlayMultiplayer` bzw. über Prism `--server`; `Config.servers`, `Config.runtime_server`), Seite **Screenshots** (alle Profile, Vorschaubilder im Hintergrund geladen, Öffnen/Löschen), **Profil-Vorlagen** (PvP/Shaders/Survival in `gui_profiles.rs`, `TEMPLATES` mit Modrinth-Projekt-IDs).
- **Im Spiel getestet (nur 26.2, Demo-Modus):** Startbildschirm, Menü, Einstellungsseite, Emote-Rad, Minimap, Wegpunkt anlegen/umbenennen/Marker, Zeit, Blockumrandung, Chat-Zeitstempel. **Nicht getestet:** 26.1 und 26.3 im Spiel (bauen aber), Low fire (man müsste brennen), Wetter-Modi Rain/Storm, Mention highlight, Auto-GG, Server-„Join“ (Demo erlaubt kein Multiplayer), alles unter echtem Windows.
- **Emote-Rad mit Vorschau:** In der Mitte steht die eigene Figur (`InventoryScreen.extractEntityInInventoryFollowsMouse`) und spielt das anvisierte Emote in Schleife (`Emotes.setPreview`, `PlayerModelMixin` prüft `Emotes.posing()`). Getestet (26.2).
- **3D-Cosmetics (`CosmeticLayer.java`):** echte Modelle aus vielen Boxen mit Tiefe. Flügel sind **gegliedert** (Schulter → Unterarm → Hand → Finger; Drache/Dämon mit Hautsegmenten und Krallen, Engel mit Feder-Schichten: Handschwingen, Armschwingen, Deckfedern; Schmetterling mit zwei Flügellappen) und falten sich beim Flattern (Gelenkwinkel pro Frame in `submit`). Kopfbedeckungen detailliert: Zylinder mit Hutband, Schnalle und hochgebogener Krempe, Krone mit Samtkappe, Zacken mit Perlen und 4 Edelsteinen, Heiligenschein (22 Segmente, leuchtet), Hörner aus 5 sich verjüngenden, gebogenen Segmenten, Katzenohren mit Innenohr und Büschel. Texturen sind **Material-Atlanten** (Bänder für Knochen, Haut mit gewelltem Rand, Federn, Gold, Edelsteine …) aus `client-mod/tools/make_cosmetics.py`. Gezeichnet über `submitCustomGeometry` (gleich in 26.1–26.3). Nur für den eigenen Spieler.
- **Haustiere (Modul Pet):** echte Vanilla-Modelle und -Texturen (`ModelLayers.PARROT/BEE/ALLAY/FOX/AXOLOTL/FROG/COPPER_GOLEM`, `ctx.bakeLayer` aus dem Fabric-Layer-Callback), fliegende schweben neben dem Kopf mit Flügelschlag (`left_wing`/`right_wing` werden in Root/`body`/`bone` gesucht), andere sitzen auf der Schulter; Seite links/rechts. In 26.2 getestet (Papagei, Biene, Allay, Fuchs).
- **Foto-Umhänge im Spiel:** Kachel „Your photo“ im Reiter Capes öffnet die Dateiauswahl (LWJGL `TinyFileDialogs`, per Reflection – gibt es in 26.3 nicht mehr, dort Hinweis aufs Hineinziehen), oder Bild ins Spielfenster ziehen (`Screen.onFilesDrop`). `CustomCapes.importPicture` macht dieselbe 1024×512-Umwandlung wie der Launcher (Java AWT/ImageIO: PNG, JPG, BMP, GIF – kein WEBP) und legt es sofort an. Ohne Launcher landen die Umhänge in `config/peregrine-capes`. Umwandlung außerhalb des Spiels getestet, Dateiauswahl/Drop nicht (kein Desktop im Container).
- **Kleiderschrank (`gui/WardrobeScreen`):** Reiter COSMETICS im Mod-Menü öffnet ihn (nur in einer Welt). Kategorien Capes/Wings/Headwear als Kacheln mit Vorschaubild (Umhang-Rückseite bzw. Material), „EQUIPPED“-Rahmen, Klick = ausrüsten, „None“ = ablegen; **Hover = anprobieren** (`CosmeticLayer.previewCape/Wings/Headwear`, auch im Umhang-Mixin). Rechts große 3D-Figur, gebaut wie die Inventar-Vorschau (`createRenderState` + `GuiGraphicsExtractor.entity`), aber frei drehbar per Ziehen mit Schwung und langsamer Eigendrehung. Flügelgröße als Auswahl unten. In 26.2 getestet.
- **Noch offen aus der Wunschliste:** Trefferfarbe, kleine Schild-/Item-Ansicht, Item-Physik, Hüte/Flügel, Auto-Text, Chat kopieren/zusammenfassen, Cosmetics-Server (andere sehen Umhang/Emotes), Minimap in Höhlen/Nether (zeigt dort nur die Oberfläche bzw. Decke).
- **Startzeit-Messung:** Die Konsole zeigt `[Timing]`-Zeilen (Launcher-Vorbereitung, Übergabe durch Prism, Minecraft-Fenster offen, Titelbildschirm geladen). Alle Launcher-Zeilen stehen zusätzlich in `~/.peregrine/launcher.log` (wird bei jedem Launcher-Start neu angelegt). Gemessen im Container (Software-Grafik, langsam): Vorbereitung 0,0 s, Fenster nach ~8 s, Titel nach ~10–11 s; davon ~2 s JVM-Start, ~4 s Minecraft-Bootstrap/Klassen laden vor dem Fenster.
- **Prism vorstarten (nur Windows, ungetestet):** `prism::prestart` startet die Komponente beim Öffnen des Launchers unsichtbar (alle Fenster per `hide_windows_while` versteckt), wenn über Prism angemeldet und kein direktes Konto. „Play“ ruft dann `prismlauncher --launch <id>`, das laut Prism-Quellcode (`Application.cpp`, ApplicationMessage „launch“) an die laufende Instanz weitergereicht wird. Ändern sich `instance.cfg`/`mmc-pack.json` (RAM, Java, Version), wird die Hintergrund-Instanz beendet, weil Prism Instanz-Einstellungen im Speicher hält; der Start läuft dann einmal normal. Vor dem Anmelden und beim Schließen des Launchers wird sie beendet (nicht, solange ein Spiel läuft: `GAME_RUNNING`). Max. 5 Vorstarts pro Launcher-Sitzung.
- **KI-Assistent / Roboter (`bot/Bot.java`, `bot/Navigator.java`, `bot/Tasks.java`, `gui/AssistantScreen.java`, Taste K):** Chatfenster im Peregrine-Look; versteht Befehle auf Englisch und Deutsch über Schlüsselwörter (kein Online-KI-Dienst, keine Kosten). Öffnen: Taste K (zusätzlich direkt per `InputConstants.isKeyDown` abgefragt, falls K doppelt belegt ist) oder Knopf „AI ASSISTANT“ unten im Mod-Menü: „mine/farm N diamonds|iron|coal|gold|redstone|lapis|copper|emeralds“ (auch „dias“, „eisen“, „kohle“ …), „chop/hol N wood|holz“, „go to <Wegpunkt>“ / „geh zu X Z“ bzw. „X Y Z“, „stop“, „help“. Steuert den eigenen Spieler mit den normalen Tasten (keyUp/keyJump, Blickrichtung, `gameMode.continueDestroyBlock`); `BotAttackMixin` (optional) verhindert, dass Vanilla das Abbauen jeden Tick abbricht. Erz-Farmen: Werkzeug-Prüfung vorab, Treppe zur besten Y-Höhe graben (nie senkrecht), Stollen, Erz im Umkreis 5 suchen, abbauen, Drop aufsammeln, zählen. Sicherheit: nur Einzelspieler (`hasSingleplayerServer`), Stopp bei < 4 Herzen, Hunger ≤ 6, vollem Inventar, Lava; kein Öffnen von Blöcken neben Lava/Wasser oder unter Kies/Sand, max. 3 Blöcke Fall. In 26.2 getestet: „hol 3 holz“ (fertig, 3 Stämme), „geh zu -60 75“ (angekommen), „farm 5 dias“ ohne Spitzhacke (korrekte Absage). Erz-Abbau selbst ungetestet (Demo-Welt hat keine Spitzhacke). `swing` per Reflection (26.3 andere Signatur, dort ohne Armschwung).
- **KI-Assistent Stufe 2 (schlauer):**
  - **Wegsuche (`bot/Navigator.java`):** A* mit Kosten. Laufen 1, Graben 1,5 + 2,5 × Härte, Wasser +2,5, Stufe hoch +0,6, Fall +0,6 pro Block, Richtungswechsel +0,15. Es gibt keine Felder neben Lava, max. 3 Blöcke Fall und nie senkrecht nach unten. Max. 3000 Knoten; findet er das Ziel nicht, nimmt er den Teilweg zum nächsten Punkt und plant dort neu. Ein Hänger ist „20 s nicht näher gekommen“ (Graben zählt als Fortschritt).
  - **Planer (`bot/Planner.java`):** rechnet vom echten Inventar rückwärts, was fehlt. Rezepte für Bretter, Stöcke, Werkbank, Truhe, Fackeln, Ofen, Leiter, Eimer, Schere, Schild, Blöcke, alle Werkzeuge (Holz/Stein/Eisen/Gold/Diamant) und Rüstungen (Eisen/Gold/Diamant). Schmelzen für Eisen, Gold und Kupfer. Quellen: Holz (Chop), Stein (`Tasks.STONE`, ein paar Blöcke unter dir) und alle Erze mit der nötigen Spitzhacken-Stufe. Nach jedem Schritt wird neu geplant (Werkzeug kaputt → neues bauen). Herstellen und Schmelzen passieren **direkt im Inventar auf dem Einzelspieler-Server** (`Bot.onServer`): echte Zutaten werden verbraucht, 3×3 braucht eine Werkbank und Schmelzen Ofen plus Brennstoff, 10 s pro Stück. Ein Werkzeug aus dem Hauptinventar wird in die Hand getauscht. Bei vollem Inventar wirft er Müll weg (Erde, Kies, Stein über 128 …).
  - **Selbstschutz (`bot/Guard.java`):** kämpft gegen Monster in Sicht (bestes Schwert/Axt, wartet auf volle Schlagkraft), flieht vor Creepern, isst bei Hunger ≤ 14 oder Verletzung, ruht bei < 5 Herzen (stoppt, wenn nichts zu essen da ist), stellt im Dunkeln unter Tage alle 7 Blöcke eine Fackel. Stoppt, wenn du stirbst.
  - **Sprache (`bot/Commands.java`):** freie Sätze auf Deutsch und Englisch. Mehrere Aufträge mit „und dann / dann / then / , hol …“. Gegenstände über Synonyme (dias, eisenhacke, steinaxt, eisenrüstung …) oder über jeden Item-Namen in der Spielsprache bzw. die ID. Eigene Befehle: „merke starter: …“ / „remember x: …“, „meine befehle“, „vergiss x“; gespeichert in `<peregrine.home>/peregrine-assistant.json`.
  - **Getestet in 26.2 (Demo, leeres Inventar):** „hol 2 eisenbarren“ hat komplett geklappt (Holz → Werkbank → Holzspitzhacke → Stein → Ofen → Steinspitzhacke → Eisen → schmelzen). Gezeigte Pläne für „hol 5 dias“, „mach mir eine eisenrüstung“, eigene Befehle und „und dann“. **Nicht getestet:** ganzer Diamanten-Lauf bis Y -58, Kampf (ein Skelett hat den alten Stand getötet, danach kam Guard dazu), Essen, Fackeln.
  - Titelmenü: Eine neue Demo-Welt wird jetzt direkt wie in Vanilla angelegt (`createFreshLevel`). Vorher reichte ein leerer `Demo_World`-Ordner, damit das Öffnen fehlschlug.
- **Design 3 „Flugkarte“ (aktuelles Design, `theme::DESIGN = 3`, alte Looks werden einmalig umgestellt):**
  - **Idee:** Peregrine = Wanderfalke, deshalb Luftfahrtkarten-Optik statt „Swiss Minimal“.
  - **Farben:** Kartenpapier `#ECE9DE`, Navy-Tinte `#1B2933`, Karten-Magenta `#A82468` als Akzent, Karten-Cyan `#167092` für Gitter und Kompass. Dunkle Variante „Night flight“.
  - **Schrift:** Airbus-Cockpitschrift **B612** und B612 Mono (OFL, `launcher/assets/fonts/B612*`, `OFL-B612.txt`). In der Mod ersetzen sie IBM Plex (`assets/peregrine/font/b612_*.ttf`, Lizenz unter `resources/licenses/OFL-B612.txt`).
  - **Launcher-Hintergrund `Chart`** (`theme::paint_chart`): Höhenlinien per Marching Squares über fraktales Rauschen (pro Fenstergröße gecacht, jede 4. Linie kräftiger mit Höhenzahl), Koordinatengitter mit Grad-/Minuten-Beschriftung, gestrichelte magentafarbene Flugroute mit Kursangaben und den Wegpunkten FALKE/KSTRL (VOR-Sechsecke, `theme::waypoint`) sowie eine Kompassrose mit Missweisung.
  - **PLAY-Leiste:** Landebahnschwelle (6 Streifen). Navigation, Abschnitte und das Versions-Zeichen nutzen Wegpunkt-Sechsecke.
  - **Im Spiel:** Die Höhenlinien kommen aus einer Textur (`textures/gui/chart.png`, erzeugt mit `client-mod/tools/make_chart.py`). Sie werden über `Ui.chart` eingefärbt in Mod-Menü, Assistent, Wegpunkte, Kleiderschrank und Startbildschirm gezeichnet. Die Standardfarben der Mod (ohne Launcher) sind die Flugkarten-Palette.
  - Das Instagram-Reel, das der Nutzer als Vorlage geschickt hat, konnte nicht geöffnet werden (Instagram im Container gesperrt).
- **Roboter-Selbstschutz Stufe 2 (`bot/Guard.java`), in Reihenfolge:**
  1. Aus Lava springen und laufen (statt aufzugeben).
  2. Den Kopf freigraben, wenn er in einem Block steckt.
  3. Bei wenig Luft auftauchen.
  4. Vor Creepern fliehen, oder sie wegschlagen, wenn sie schon zu nah sind.
  5. Bei Übermacht zurückweichen (4+ Monster, oder 2+ bei < 4 Herzen).
  6. Notfall-Essen unter 4 Herzen, mit goldenen Äpfeln.
  7. Kämpfen mit kritischen Treffern (springen, im Fallen schlagen) und Zickzack gegen Skelette und andere Fernkämpfer.
  8. Essen.
  9. Ohne Essen Kühe, Schweine, Schafe, Hühner oder Kaninchen jagen und die Drops einsammeln.
  10. Ausruhen.
  11. Alle 2 s die beste Rüstung anziehen und ein Totem in die zweite Hand legen (serverseitig, Einzelspieler).
  12. Fackeln setzen.
  - **Planer:** Vor jedem Erz-Auftrag baut er ein Schwert. Vor tiefen Erzen (Ziel-Y < 16) macht er 16 Fackeln. Kohle, die als Zutat gebraucht wird, brennt er als Holzkohle aus Stämmen.
  - Nicht im Spiel getestet (Nutzer wollte keine weiteren Tests), nur für 26.1/26.2/26.3 gebaut.
- **PvP und eigener Server:**
  - **Freigabe (`Bot.allowed`):** Der Assistent läuft im Einzelspieler und in selbst gehosteten LAN-Welten. Auf Servern läuft er nur, wenn der Spieler dort Operator ist (`permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)`, also Level 2+). Auf fremden Servern bleibt er aus (Cheat gegen andere Spieler, Bann-Gefahr).
  - **Inventar über echte Klicks (`bot/Inv.java`, `handleContainerInput`):** in die Hand tauschen, Totem in die zweite Hand, Rüstung anziehen, Müll wegwerfen. Das funktioniert auch auf Servern.
  - **Herstellen und Schmelzen** bleiben nur im Einzelspieler (serverseitig). Auf Servern hält der Planer beim ersten Herstell-Schritt an und sagt, was der Spieler selbst bauen soll.
  - **`bot/Combat.java` (`Duel`)**, Befehle „fight/duel/pvp/kill <Name>“, „kämpf gegen <Name>“, „töte …“. Ohne Namen gilt der nächste Spieler, mit einem Mob-Namen der nächste Mob dieser Art.
    - Crystal: Kristalle zerschlagen, wenn das dem Gegner mehr schadet als uns. Sonst den besten Obsidian- oder Grundgestein-Block im Umkreis von 3 um den Gegner suchen (Schaden nach der Explosionsformel mit Stärke 6, eigener Schaden × 0,6 abgezogen, nie tödlich ohne Totem) und dort einen Kristall setzen. Ohne passende Stelle setzt er Obsidian neben den Gegner.
    - Mace: Windladung an die eigenen Füße (Blick nach unten), dann im Fallen mit der Mace schlagen.
    - Schwert: Sprint-Treffer mit W-Tap, kritische Treffer, wenn der Gegner in der Luft ist oder fast tot, unregelmäßiges Strafen, Abstand ~2,6–3,0. Hebt der Gegner das Schild, nimmt er die Axt.
    - Verteidigung: `Guard.gear` jede Runde (Totem nach dem Verbrauch sofort ersetzen), goldener Apfel unter ~4,5 Herzen.
    - Bei mehr als 10 Blöcken Abstand läuft er per A* hin.
  - **Im Duell:** `Guard` ruht nicht, jagt nicht und zieht sich nicht vor Gruppen zurück. Mobs bekämpft er nur, wenn sie näher als 3 Blöcke sind.
  - **Getestet:** nur „kill cow“ in 26.2 (finden, hinlaufen, schlagen, Sieg erkennen). Gegen echte Spieler, Crystal und Mace ist nichts getestet (geht im Container nicht). Es ist ein Regelwerk, kein trainiertes Modell.
- **Design 4 „Stoop“ (aktuell, `theme::DESIGN = 4`, `launcher/src/gui_stoop.rs`):** benannt nach dem Sturzflug des Wanderfalken. Vorgabe des Nutzers: fast schwarz `#08090C`, Anthrazit `#111319`, Silber, ein gedämpftes Eisblau `#8DB4D4`, kein Neon, kein Glas, keine typischen Karten.
  - **Navigation:** ein Höhenband links (Teilstriche wie ein Höhenmesser). Hauptseiten HOME, MODS, VERSIONS, ACCOUNTS, PERFORMANCE, SETTINGS; darunter kleiner Profiles, Servers, Screenshots, Appearance, Console. Ein eisblauer Zeiger gleitet animiert zur aktiven Seite. Beim Überfahren werden Teilstrich und Label länger bzw. rücken nach rechts. Unten stehen Konto-Raute und Version.
  - **Titelleiste:** Falken-Bildmarke (`falcon`, Flügel angelegt) und gesperrte Wortmarke. In der Mitte steht der Status als Raute (READY, PREPARING oder IN FLIGHT, pulsierend).
  - **Startseite:**
    - Mitte: eine 3D-Blockfigur (eigener Mini-Renderer: Boxen, Painter-Sortierung, Licht von oben links, eisblaues Kantenlicht, leuchtende Augen, Falke auf der Brust). Sie schwenkt sanft, atmet mit Armen und Beinen, folgt der Maus und lässt sich per Ziehen drehen.
    - Die Figur steht auf einem perspektivischen Lichtring mit Skala. **Der Ring ist der PLAY-Knopf** (auch Enter). Beim Überfahren kreisen Lichtbögen und Lichtpunkte steigen auf. Beim Laden wird der Ring zur Fortschrittsanzeige.
    - Links: Gruß, große Versionsnummer (Klick öffnet die Versionsliste), Loader-Wahl mit gleitendem Strich, Block SESSION (Pilot, Last flight, Flights, Memory, Client mods). Die Werte kommen aus der neuen `Config.stats_last_launch`/`stats_launches`.
    - Rechts: RECENT (Profile) und SERVERS mit Live-Ping (`App::server_status` in `gui_extra.rs`); ein Klick verbindet direkt.
  - **Neue Seiten:**
    - Versions: Filter, Suche, Loader, Raster mit „Latest release“ und einer Raute, wenn Peregrine-Mods für die Version verfügbar sind.
    - Accounts: Abzeichen mit Initiale, Art der Anmeldung, Knöpfe.
    - Performance: Speicher als 16-Zellen-Leiste zum Klicken oder Ziehen plus Voreinstellungen, Turbo-Mods, Java, Fenstergröße. Performance und Java sind dafür aus Settings herausgenommen.
  - **Hintergrund „Atmosphere“:** atmender eisblauer Lichtschein mit Parallaxe, kühler Gegenschein und 34 feine Luftströme, die schräg „herabstürzen“. Oben und unten ist der Rand abgedunkelt.
  - **Seitenwechsel:** Die Seite blendet in 0,32 s ein und gleitet dabei 14 px nach oben (`page_fade`, Kind-Ui mit `set_opacity`).
  - **Mod:** liest `pattern` aus `theme.json`. Die Höhenlinien gibt es nur beim Hintergrund „Chart“. Ohne Launcher nutzt die Mod jetzt die Stoop-Farben.
  - Die alten Funktionen `title_bar`, `home` und `visual` in `gui.rs` sind ungenutzt (`#[allow(dead_code)]`). Das Flugkarten-Design gibt es weiter als Preset „Chart“ und als Hintergrund „Chart“.
- **Testen:** Skripte der Sitzung lagen im Scratchpad (nicht im Repo). Ablauf: Xvfb starten, Launcher mit `HOME=<testordner>` und `use_prism:false` starten, „PLAY“ klicken, im Titel „Play demo world“ klicken, dann `xdotool` + `import`.

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
| **Login** | Microsoft-Login (OAuth PKCE → Xbox → XSTS → Minecraft). Braucht **eigene Azure Client-ID** (Einstellungen) + Freischaltung durch Mojang. |
| **Start über Prism** (Standard, solange kein eigenes Konto) | `src/prism.rs`: Peregrine sucht den **Prism Launcher**, liest nur den Kontonamen aus `accounts.json` (Titelleiste „NAME · PRISM“), legt pro Version/Profil eine Prism-Instanz `peregrine-…` an (`instance.cfg`, `mmc-pack.json`, `.minecraft` = Junction/Symlink auf den Peregrine-Spielordner), legt Client-Mod + Fabric API hinein, lädt nur Java selbst (`install::prepare_java`) und startet `prismlauncher --launch <id>`. Spiel-Log wird aus `logs/latest.log` in die Konsole gespiegelt. Einstellungen › „Start über Prism Launcher“ (an/aus, Pfad, Status, „Prism öffnen“). **Prism wird mitgeliefert:** Fehlt Prism, lädt Peregrine unter Windows beim Klick auf „Anmelden“ die portable Prism-Version (MinGW-Build, ohne VC++-Runtime; GitHub-API „latest“, Fallback 9.4) nach `~/.peregrine/prism/` (`prism::install`). `prism::login` öffnet Prism nur zum Anmelden und beendet es, sobald in `accounts.json` ein (neues) Konto steht. Peregrine sieht keine Tokens. Linux/macOS: Prism selbst installieren (Download-Link). Schalter aus: Demo-Modus. **Prism ist in der Oberfläche unsichtbar** (Wunsch des Nutzers): keine Prism-Texte in Titelleiste/Startseite/Einstellungen/Konsole („[Anmeldung]“), Einstellungskarte heißt „Anmeldung“ („Einfache Anmeldung“). Unter Windows wird nur die eigene Kopie benutzt; `prism::preset` schreibt vor dem ersten Öffnen `prismlauncher.cfg` (Sprache, Java, AutoUpdate aus …), damit möglichst keine Ersteinrichtung erscheint (ungetestet). Sichtbar bleibt nur das einmalige Anmeldefenster. Beim Spielstart versteckt `hide_windows_while` (Windows, `windows-sys` EnumWindows/ShowWindow) alle Fenster des Prism-Prozesses bzw. mit „Prism Launcher“ im Titel (z. B. „Please wait… Zugriffstoken“); nach 240 s ohne Spiel-Log werden sie wieder gezeigt (falls Prism den Nutzer braucht). Die Spielen-Leiste zeigt Stufen-Fortschritt (Java → Client-Mods → Anmeldung → Spieldateien → Log) und bleibt belegt, bis im Log „Backend library“/„Sound engine started“ steht; ohne Prozentwert läuft ein wandernder Balken. Mit Nutzer-Prism 11.1.1 bestätigt: Start funktioniert, nur das Token-Fenster war sichtbar (jetzt versteckt, ungetestet). |
| **Profile** | Eigene Profile mit eigenem Spielordner (`<spielordner>/instances/<id>`), Version, Loader (optional feste Loader-Version). Auf der Startseite per „Schnellstart“ oder in der Versionsauswahl wählbar. |
| **Modrinth** (nur Modrinth, CurseForge wurde entfernt) | Entdecken & Installieren von **Mods** (mit Abhängigkeiten), **Ressourcenpaketen**, **Shadern** (Iris wird automatisch mitinstalliert), **Datenpaketen** (Welt wählbar), **Modpacks** (.mrpack → neues Profil, gepinnte Loader-Version, Overrides, Titel/Icons per Hash-Lookup). Ein-/Ausschalten (`.disabled`), Entfernen. |
| **Client-Mods** | Standard-Loader ist jetzt Fabric; ist Vanilla gewählt und die Client-Mods sind an (und es gibt eine Mod für die Version), startet `launch()` automatisch mit Fabric (sonst kein Mod-Menü – war ein Nutzer-Problem). Legt die passende Peregrine-Mod (26.1/26.2/26.3) vor dem Start in Fabric/Quilt-Profile, installiert fehlende Fabric API. Einstellungen macht man **im Spiel** (Rechts-Shift) – der Launcher schreibt die Mod-Config **nicht**. |
| **Turbo (mehr FPS)** | `setup_turbo` (gui.rs) installiert vor jedem Start in Fabric/Quilt-Profilen die Leistungs-Mods aus `client_mods::TURBO` (Sodium, Lithium, FerriteCore, ImmediatelyFast, Entity Culling) passend zur MC-Version von Modrinth (`mods::pick_file`), ersetzt alte Versionen, entfernt sie beim Ausschalten. Buchführung in `mods/.peregrine-turbo.json`; vom Nutzer deaktivierte (`.disabled`) bleiben aus. Schalter auf der Seite Client-Mods („Turbo – mehr FPS“, Standard an). More Culling wurde entfernt (braucht Cloth Config). Im Spiel getestet: 26.2 + Turbo + Peregrine-Mod lädt, Welt läuft, HUD wird angezeigt. |
| **Tasten** | Seite Client-Mods › „Tasten“: Menü/Zoom/Fullbright/Auto-Sprint per Klick + Tastendruck (oder Sondertaste wie Shift/Strg/Maus 4) ändern. Gespeichert in `client.keys`, vor dem Start per `client_mods::apply_keys` in `options.txt` (`key_key.peregrine.*`) geschrieben – nur wenn options.txt existiert. Im Spiel getestet (Zoom → X bleibt erhalten). Die Menü-Taste schließt das Menü auch wieder (`PeregrineClient.isMenuKey`). |
| **Startzeit** | Loader-Version (`install::loader_version`, Cache `~/.peregrine/cache/loader-versions.json`, auch offline) und Turbo-Update-Prüfung (`mods/.peregrine-turbo-check`) nur 1× pro Tag online. Gemessen hier: Klick → Hauptmenü-Ladeende ≈ 7,5 s. Java-AOT-Cache (JEP 514) getestet und **verworfen**: kein Gewinn im echten Ablauf, erster Start +2 min. |
| **Gemeinsame Mod-Einstellungen & Design** | Launcher schreibt vor jedem Start `~/.peregrine/client/theme.json` (Farben aus „Aussehen“, `client_mods::write_theme`) und startet Java mit `-Dperegrine.home=~/.peregrine/client` (`Config.runtime_jvm`, direkt + Prism-JvmArgs, Pfade mit Leerzeichen in Anführungszeichen). Die Mod speichert dann `peregrine-client.json` dort (alle Profile gleich; beim ersten Mal wird die Profil-Config übernommen) und färbt das Menü/Emote-Rad danach (`gui/Theme.java`). Turbo-Mods einzeln abschaltbar (`client.turbo_off`). |
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
| `src/prism.rs` | Start über Prism Launcher (Suche, Konto-Name, Instanz anlegen, Start, Log mitlesen) |
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
- **Mod-Menü im Spiel: Rechts-Shift** – neu gestaltet (dunkel, Orange-Akzent): großes Fenster mit unscharfem Hintergrund (`extractBlurredBackground`), Öffnen-/Seiten-Animation, Seitenleiste mit Logo, Kategorien mit Symbolen und Anzahl, „HUD bearbeiten“, Zähler aktiver Module; Kopf mit Titel + Suche; Kacheln (3 Spalten, 2 bei kleinem Fenster) mit Symbol, Name, Beschreibung, AKTIVIERT/AUS-Leiste, Zahnrad; Einstellungsseite mit Kopfkarte, Schalter, Zeilen-Karten; weiches Scrollen + Scrollleiste. Mit JDK aus `~/.peregrine/runtime/java-runtime-epsilon` (enthält javac) gebaut. Im Spiel (26.2) getestet. Alt: Kategorien (Alle, HUD, PvP, Grafik, Bewegung, Sonstiges), Suche, Kacheln mit Schalter, Zahnrad = Einstellungen (Schalter, Schieberegler, Auswahl, Farbpalette), „HUD bearbeiten“ (Drag & Drop, Mausrad = Größe, Einrasten; nicht verschobene Anzeigen ordnen sich automatisch an).
- **Cosmetics (nur lokal sichtbar!):** Umhang (`AbstractClientPlayerMixin` ersetzt `PlayerSkin.cape/elytra` für den eigenen Spieler; 8 Designs als PNG aus `client-mod/tools/make_capes.py` unter `assets/peregrine/textures/cape/`) und Emotes (`Emotes.java`, `PlayerModelMixin` @TAIL von `setupAnim(AvatarRenderState)`, nur `state.id == player.getId()`; Rad `gui/EmoteWheel`, Taste B `key.peregrine.emote`; dreht Kamera nach vorne, Laufen bricht ab). Andere Spieler sehen nichts – dafür bräuchte es einen eigenen Cosmetics-Server. Im Spiel (26.2) getestet.
- **Foto-Umhänge:** Launcher (Seite Client-Mods › Cosmetics, Bild ins Fenster ziehen oder Pfad eingeben) wandelt Bilder mit `client_mods::import_cape` in eine 1024×512-Umhangtextur (Umhang-Layout ×16 + Elytra-Bereich) und speichert sie in `~/.peregrine/client/capes/<Name>.png`. Die Mod (`CustomCapes.java`) listet sie als Design „Foto: <Name>“ (Auswahl wird beim Öffnen des Menüs aktualisiert, `ModeSetting.setModes`) und registriert sie als `DynamicTexture`. Getestet (26.2).
- **Emote-Rad neu:** runder Ring (zeilenweise gefüllt), Segment unter der Maus vergrößert/Akzentfarbe, Taste B halten → Richtung → loslassen, Klick oder 1–8; Mitte stoppt laufendes Emote. Getestet.
- **Neu (Paket 1 teilweise):** Freelook (Links-Alt halten; `EntityTurnMixin` + `CameraRotationMixin`), Chat-Zeitstempel (`ChatComponentMixin` auf privates `addMessage`), Auto-GG (Fabric `ClientReceiveMessageEvents.GAME`, nur auf Servern, 15 s Sperre), HUD: Sättigung, Pfeile, Totems, Biom, Spielzeit, Ressourcenpakete. Neue Mixins mit `require = 0` (kein Absturz, falls eine Version abweicht). Alle in 26.2 getestet außer Auto-GG.
- **33 Module (vorher 24):** FPS, CPS, Koordinaten, Keystrokes, Rüstung, Trank-Effekte, Uhrzeit, Ping, Server-Adresse, Geschwindigkeit, Tageszähler, Arbeitsspeicher, Reichweite, Combo-Zähler · Fadenkreuz, Kein Schadens-Wackeln · Zoom (C), Fullbright (G), Kein Regen · Auto-Sprint (V), Schleichen umschalten · HUD-Stil.
- Speichert in `config/peregrine-client.json` des Profils (`format: 2`, pro Modul `enabled`, `placed`, `x`, `y`, `settings`).
- Wichtige Klassen: `PeregrineClient` (Init, Tasten, HUD), `Modules` (alle Module), `module/*` (Basis), `setting/*`, `gui/ClickGui`, `gui/HudEditor`, `gui/Ui` (öffnet Screens per Reflection: 26.1 `Minecraft.setScreen`, 26.2+ `Gui.setScreen`), `Store`, Mixins (`CameraMixin` Zoom, `MouseHandlerMixin` CPS, `OptionInstanceAccessor` Fullbright, `GameRendererMixin` Hurt-Cam, `LevelMixin` Regen).
- **Achtung 26.3:** `PoseStack.mulPose` hat eine andere Signatur – Drehungen direkt über JOML (`pose.last().pose().rotateY`, `normal().rotateY`).
- **Achtung 26.3 (SDL):** Maustasten-Nummern geändert (`InputConstants.MOUSE_BUTTON_LEFT` 26.2 = 0, 26.3 = 1, rechts 1 → 3). Immer die Konstanten benutzen, nie 0/1 hart kodieren (war der Grund, warum im Menü in 26.3 kein Klick ging). Konstanten werden pro Version beim Bauen eingesetzt.
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
- Direkter Microsoft-Login braucht eigene Azure-App + Mojang-Freischaltung – bis dahin Start über Prism Launcher (einmal dort anmelden).
- Prism-Start ist mit einem Prism-Platzhalter getestet (Instanz-Dateien, Junction, Aufruf), **nicht** mit echtem Prism + Konto. Beim ersten Start lädt Prism die Spieldateien selbst (doppelt zu Peregrines eigenem Spielordner).
- Kein Forge/NeoForge (Profile, Modpacks, Mods nur Fabric/Quilt).
- Client-Mod nur für 26.1–26.3; In-Game nur auf 26.2 getestet. Rüstung/Trank/Reichweite/Combo/Hurt-Cam/Regen/Toggle-Sneak nicht im Spiel getestet.
- Keine Updates für installierte Modrinth-Inhalte; keine Zeitänderung (26.3 hat neue Uhr-Logik).
- Die `.exe` ist nicht signiert (Windows SmartScreen kann warnen → „Weitere Informationen“ › „Trotzdem ausführen“).

## 6. Mögliche nächste Schritte
- Mod-Menü im Spiel im Papier/Orange-Look des Launchers gestalten.
- Weitere Module: Freelook, Chat-Zeitstempel, wenig Feuer, Waypoints, Blockumrandung.
- „Alles aktualisieren“ für Modrinth-Inhalte; Forge/NeoForge; Skin-Kopf als Konto-Bild.
- Signierte Releases über GitHub Actions (Release-Workflow).
