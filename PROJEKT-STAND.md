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
- **Komplett-Redesign Stufe 2 (alle Seiten, Start-Sequenz):**
  - **Start-Sequenz (`launcher/src/gui_launch.rs`)**, ausgelöst von PLAY (`begin_launch_sequence`):
    - Phase 1: Der PLAY-Ring glüht auf (`LaunchAnim::activation`), eine eisblaue Welle läuft vom Ring aus, und der Hintergrund dunkelt ab.
    - Phase 2: Der Falke stürzt mit Lichtspuren, Nachbildern und Partikeln herein und bremst präzise ab.
    - Phase 3: Startbildschirm mit Wortmarke (Buchstaben rücken zusammen), Version, einem Ring aus 5 Segmenten (je Phase eins) und einer Phasenleiste. Darunter steht die echte Statuszeile.
    - Phase 4: Der Falke stürzt hinaus und alles blendet aus.
    - **Echte Kopplung:** `Shared.game_stage` (`state.rs`) wird aus Log-Zeilen gesetzt: 1 = „[Launcher] Starting Minecraft“ bzw. „Starting <id> with …“, 2 = „[Timing] Minecraft window open“, 3 = „… fully loaded“, 8 = Fehler, 9 = Spiel beendet. Die Phase ergibt sich aus Status-Texten und Datei-Fortschritt (nur steigend).
    - Ohne Zahlen füllt sich ein Segment nie, es wandert nur ein Lichtpunkt. „Loading libraries“ füllt sich mit `done/total`. Die Sequenz endet erst bei Stufe 3 (Titelbildschirm), nach 90 s Notausgang ohne lesbares Log.
    - Fehler: Ansicht „Launch aborted“ mit Text und den Knöpfen BACK/CONSOLE (das alte Fehlerfenster wird dann nicht gezeigt). Schließt sich das Spiel vorher oder startet nichts (z. B. Anmeldung nötig), erscheint eine Meldung, dann blendet sie aus. „Continue in background“ blendet die Sequenz aus, der Ring zeigt weiter den Fortschritt. Die Titelleiste zeigt LAUNCHING.
  - **Mods (`gui_pages.rs::mods_page`):**
    - Reiter LIBRARY / SETUP (SETUP = alte Client-Seite: Client-Mods, Turbo, Umhänge, Tasten; `client_page(…, show_header)`).
    - Die Bibliothek liest `<shared>/peregrine-client.json`. Die Mod schreibt dort jetzt Name, Beschreibung und Kategorie je Modul hinein und speichert beim Start einmal.
    - Suche, Kategorie-Chips, Sortierung (Name/Kategorie/An zuerst), Karten- oder Listenansicht.
    - Ein Klick schaltet `enabled` in der Datei. Das ist gesperrt, solange Minecraft läuft (die Mod würde es überschreiben).
  - **Settings (`settings_page`):** Kategorien General, Graphics, Account, Launcher, Microsoft, Maintenance.
    - Graphics schreibt vor jedem Start `renderDistance`, `simulationDistance`, `maxFps`, `enableVsync`, `fullscreen`, `guiScale`, `entityShadows`, `renderClouds` in `options.txt` (`ClientMods.options`, `client_mods::apply_options`, nur wenn `options.txt` schon existiert).
    - Launcher: Theme, Hintergrund, Akzent, Animationen, UI-Größe.
    - Die alte `settings`-Funktion ist ungenutzt.
  - **Versions:** Reiter AVAILABLE und INSTALLED (Ordner `<game>/versions/<id>/<id>.json`). Installierte Versionen sind markiert, beim Laden gibt es eine Fortschrittszeile.
  - **Performance:** Anzeigen für FPS-Durchschnitt, Tiefst- und Höchstwert der letzten Sitzung (die Mod schreibt `<shared>/peregrine-session.json` über `SessionStats`, einmal pro Sekunde gemessen, nur in einer Welt), dazu System-RAM (Windows `GlobalMemoryStatusEx`, Linux `/proc/meminfo`) und CPU-Threads. Warnung, wenn mehr als ¾ des RAM vergeben sind.
  - **Schalter überall:** Pill-Form, gleitender Knopf, Eis-Schein im an-Zustand, Hover-Rand (`theme::toggle`, `pages_ui::switch_at`). Abschnitts-Markierungen sind Rauten.
  - **Nicht umgesetzt:** Forge/NeoForge. Der Launcher kann nur Vanilla, Fabric und Quilt installieren; Forge bräuchte einen eigenen Installer-Ablauf.
- **Titelbildschirm wieder Vanilla:** Modul „Main menu“ ist standardmäßig aus. Gespeicherte Einstellungen im alten Format 2 werden einmalig auf aus gesetzt (`Store`, neues Format 3). Der eigene Titel ist im Mod-Menü weiter einschaltbar. Falls er an ist, legt `TitleMenu` eine fehlende Demo-Welt per `createFreshLevel` an.
- **Mod-Menü im Launcher-Look (`gui/ClickGui.java`):**
  - Links ein Höhenband mit Teilstrichen, Kategorien als gesperrte Mono-Labels und ein eisblauer ◀-Zeiger, der weich zur aktiven Kategorie gleitet. Darüber stehen Falken-Marke und Wortmarke, unten „x / 43 ON“.
  - Rechts Module als Karten (3, 2 oder 1 Spalte je nach Platz; bei einer Spalte kompakt) mit Kategorie-Label, Akzentkante bei „an“, Hover-Rand und animierten Pill-Schaltern.
  - Fußleiste: primärer Knopf EDIT HUD LAYOUT, umrandeter Knopf AI ASSISTANT. Ein atmender Lichtschein liegt hinter dem Menü.
  - Neue Bausteine: `Ui.falcon`, `Ui.glow` (Texturen `textures/gui/falcon.png` und `glow.png` aus `tools/make_marks.py`), `Ui.diamond`, `Ui.pill`, `Type.spaced`.
  - Die Modul-Einstellungsseite (Regler, Modi, Farben) ist unverändert, nutzt aber die neuen Schalter.
- **Ladesymbol ohne Falke (`gui_launch.rs`):**
  - Ein orbitaler Ring aus feiner Spur, fünf Phasen-Markierungen, einem innen langsam drehenden Teilkreis und einem Fortschrittsbogen mit Schweif und leuchtendem Kopf.
  - Der Bogen folgt nur dem echten Anteil: abgeschlossene Phasen plus Dateifortschritt in „Loading libraries“, weich animiert, nie über 99,9 % vor dem Ziel. Ein schwacher, umlaufender Lichtpunkt zeigt nur Aktivität.
  - In der Mitte steht die Phase („02 / OF 05“). Beim Erfolg schließt sich der Ring, weitet sich und verblasst.
- **Menüs im Spiel rund und hochauflösend (`gui/Px.java`):**
  - Zeichnet in echten Bildschirmpixeln (`pose().scale(1/guiScale)`) mit Kantenglättung über Teilabdeckungs-Pixel: abgerundete Flächen und Rahmen, Kreise, Rauten, Pfeil und Haarlinien.
  - Umgestellt sind das Mod-Menü (Blatt mit weichem Schatten, Karten, Schalter, Regler-Knopf, Farbkreise, Knöpfe, Linien), `Ui.pill` und `Ui.diamond` sowie Assistent, Wegpunkte, Kleiderschrank, HUD-Editor und Titelmenü (Blätter, Knöpfe, Chips, Linien).
- **Berg im Launcher-Hintergrund (`gui_stoop.rs::paint_mountains`, Teil von „Atmosphere“):**
  - Ein Matterhorn-artiger Gipfel: asymmetrisch, mit steiler rechter Wand, Schulter links und felsigem Grat aus grobem und feinem Rauschen.
  - Schnee oberhalb einer zerfransten Schneegrenze, Kantenlicht in Eisblau, eine dunstige hintere Kette, Bodendunst und dunkle Vorberge.
  - Drei Ebenen mit unterschiedlicher Parallaxe. Die Flächen sind Meshes mit Farbverlauf.
  - Wichtig: Farben hier mit `mix` in Bildschirmfarben mischen. `theme::lerp_color` mischt linear und macht dunkle Töne viel zu hell.
- **Hintergrundbild „Peak“ (Wunsch des Nutzers, sein eigenes Bild):**
  - Das Bild liegt unter `launcher/assets/backgrounds/peak.jpg`. Das Original hatte 800×450; es ist mit Lanczos und leichtem Nachschärfen auf 1920×1080 vergrößert.
  - Eingebunden ist es in `paint_atmosphere` über `paint_peak`: formatfüllend mit 5 % Überstand für die Parallaxe und Abdunklung (Alpha 70). Auf allen Seiten außer Home wird zusätzlich abgedunkelt (Alpha 120), damit Text lesbar bleibt.
  - Der gezeichnete Berg (`paint_mountains`) bleibt im Code, wird aber nicht mehr genutzt.
  - Herkunft und Rechte des Bildes sind unbekannt (vom Nutzer hochgeladen).
- **PvP-Techniken Stufe 2 (`bot/Combat.java`, `techniques` + `attackWithSwap`):**
  - Hotbar-Wechsel wie per Taste: `quickUse` wählt das Item, setzt die Blickrichtung, benutzt das Item und wechselt im selben Tick zurück. Das UseItem-Paket trägt die Rotation. Items aus dem Hauptinventar holt er zuerst in Slot 9 bzw. 8.
  - **Attribute Swap:** Der Schlag wird mit der Waffe in der Hand geladen; im selben Tick wechselt er auf Axt (Schild hoch), Mace (Fall > 1,5) oder Breach-Mace (Rüstung ≥ 12), schlägt und wechselt zurück. Der Server rechnet mit der Ladung der ersten und den Werten der zweiten Waffe, weil der Ladezähler erst im nächsten Spieler-Tick zurückgesetzt wird.
  - **Windladung:**
    - Fallschaden verhindern: bei Fall > 6 Blöcke und Boden innerhalb von 3 Blöcken.
    - Den Gegner wegschießen: auf seine Füße bei < 4,5 Herzen und < 3,2 Blöcken.
    - Mace-Sprung wie bisher.
  - **Perlen:**
    - Flucht: < 3,5 Herzen, kein Totem, Gegner < 6 Blöcke – vom Gegner weg mit −28° Neigung.
    - Angriff: 14–60 Blöcke und freie Sicht; die Neigung kommt aus der Wurfbahn mit v = 1,5 und g = 0,03, flacher Bogen.
    - Pearl Catch: Zwei Ticks nach der Angriffs-Perle folgt eine Windladung an die eigenen Füße in Richtung Gegner.
  - **Lunge Swap:** Speer mit Lunge (Verzauberung per `getEnchantments().toString()` erkannt) bei 3,6–8,5 Blöcken. Er löst den normalen Angriff per Reflection auf `Minecraft.startAttack` aus (Stoß mit Vorwärtsschub der Verzauberung) und kehrt im nächsten Tick zur Waffe zurück.
  - Nicht im Spiel getestet: Die Demo-Welt hat keine dieser Items und keinen zweiten Spieler.
- **Block ESP und Combat Aura (`client-mod/src/main/java/de/peregrine/client/feature/`):**
  - **Wiederverwendet:**
    - das Modul- und Einstellungssystem: `Module`, die Settings und `Store`, das automatisch speichert;
    - das Mod-Menü (Karten, Einstellungsseite), Tasten über `KeyMappingHelper` und `toggle()` mit Hinweis;
    - die HUD-Ebene, die Kamera-Projektion der Wegpunkte (jetzt `Waypoints.camera` öffentlich und als `Projector` mit Clipping an der Kamera-Ebene), `Px` und `Ui.glow`;
    - `Bot.allowed` (Einzelspieler, LAN oder Operator) und das versionssichere `Bot.swing`.
  - **Neu:**
    - `ListSetting` (JSON-Array) für die Blockliste;
    - `Module.editor(parent)` für eigene Bildschirme;
    - `Px.line` (gedrehtes Rechteck in Bildschirmpixeln);
    - Kategorien heißen jetzt „Combat“ (früher PvP) und „Render“ (früher Visuals).
  - **Block ESP** (Render, Taste X, `key.peregrine.esp`):
    - Sucht rollierend pro Tick bis zu 320 Abschnitte, die schnell über `LevelChunkSection.maybeHas` (Palette) geprüft werden, und durchsucht höchstens 6 Abschnitte voll. Treffer werden je Abschnitt gemerkt; wer den Chunk-Radius verlässt, wird aufgeräumt.
    - Gezeichnet wird als Overlay: die Kontur nur der Flächen ohne Nachbar-Treffer (eine Ader bekommt eine Außenlinie), dazu der Lichtschein. Die nächsten N sind höchstens 10× pro Sekunde neu sortiert. Bis zu 10 Beschriftungen ohne Überlappung, die nächsten zuerst.
    - Einstellungen: Blöcke, Farbe, Deckkraft, Linie 1–4 px, Reichweite 16–128, max. Hervorhebungen, Name und Entfernung.
    - `gui/BlockPickerScreen`: Suche über alle Blöcke mit Autovervollständigung, Item-Symbolen, IDs und Enter = bester Treffer; Live-Vorschau, Palette, Regler, gewählte Blöcke, Zähler „FOUND“.
    - **Nur** im Einzelspieler, in eigenen LAN-Welten und auf Servern mit Operator-Recht (sonst ein Hinweis, nichts wird gesucht).
    - Getestet in 26.2 (Kohle/Eisen/Kupfer unter der Erde sichtbar, Dialog, Einstellungen).
  - **Combat Aura** (Combat, Taste R, `key.peregrine.aura`):
    - Reichweite 2–6 m; Ziele Hostile / Hostile + neutral / All mobs, nie Spieler (`Player` ausgeschlossen, nur `Mob`); Priorität Closest / Lowest health / Crosshair.
    - Drehung Smooth (°/tick) oder Instant; Timing „volle Ladung“ oder feste CPS.
    - Markierung: Ring (animiert), Box oder Glow, plus Name und HP-Balken. Optional durch Wände, optional nur mit Waffe.
    - Pausiert bei offenem Menü. **Nur `hasSingleplayerServer()`**, im Multiplayer immer aus. Nutzt nur den normalen Angriff, keine Tarnung.
    - Getestet in 26.2: Kuh erkannt, Ring und HP angezeigt, mitgeschlagen.
  - Launcher: `client_mods::KEYS` (X, R) und `MODULES` ergänzt, Kategorienamen angepasst.
- **Assistent mit ESP (`feature/OreFinder.java`):**
  - Die ESP-Suche als Einmal-Abfrage: Chunk-Ringe von innen nach außen, Abschnitte per `maybeHas` (Palette) vorgeprüft, Abbruch, sobald nichts Näheres mehr möglich ist.
  - `Tasks.Mine` fragt alle 2 s bis 48 Blöcke weit (wenn im Umkreis 5 nichts ist) und gräbt per A* direkt hin, statt blind Gänge zu graben. Für Stein ist das aus.
  - Während eines Erz-Auftrags zeigt `BlockEsp.setAssist(...)` die gesuchten Erze, auch wenn das Modul aus ist. Bei Fertig oder Stop wird das zurückgesetzt.
  - Getestet in 26.2:
    - „hol 3 kohle“ von leer bis fertig in unter 1 Minute.
    - „hol 2 eisen“: Eisenerz per ESP gefunden und markiert. Danach in der Höhle von einem Zombie getötet (Container ca. 5 FPS); seitdem zieht er sich unter 3 Herzen auch vor einem einzelnen Gegner zurück.
- **Stun Slam und Elytra-Mace (`bot/Combat.java`):**
  - `smash`: Gegen ein gehobenes Schild schlägt er im selben Tick erst mit der Axt (Schild aus), dann mit der Mace (Density bevorzugt). Das wird bei jedem Mace-Treffer im Fallen genutzt.
  - `elytraMace` (Gegner 7–48 Blöcke entfernt, Mace + Raketen + Elytra vorhanden), Ablauf:
    1. Elytra per Klick anlegen. `Guard.keepElytra` verhindert, dass `gear` sie gegen die Brustplatte tauscht.
    2. Springen und in der Luft erneut Springen drücken, damit die Flügel öffnen.
    3. Mit Raketen bei −55° steigen, bis er 20 Blöcke über dem Gegner ist.
    4. Steiler Sturzflug (35–88°, damit der Fall-Abstand wächst), Mace in die Hand, Treffer bei ≤ 3,4 Blöcken und Sinkgeschwindigkeit < −0,4.
    5. Nach der Landung kommt die Brustplatte wieder an. Abbruch nach 14 s, danach 3 s Pause.
  - Nicht im Spiel getestet (keine Elytra, Raketen oder Mace in der Demo).
- **Notfall-Ausloggen (`bot/EmergencyLogout.java`, Modul „Emergency logout“, Misc, standardmäßig an):**
  - Einstellungen: Auslösen bei 0,5–5 Herzen (Standard 0,5), „nur während der Assistent spielt“ (Standard an), Screenshot an/aus.
  - Ablauf:
    1. Bot stoppen und einen Bericht schreiben: Zeit, was dich getroffen hat (Todesnachricht der letzten Schadensquelle), Leben, Hunger, Rüstung, was der Bot tat, Position und Dimension, Hand und zweite Hand, Monster und Spieler im Umkreis von 24 Blöcken, die letzten 3 Chatzeilen des Assistenten.
    2. Offene Menüs schließen und **zwei gezeichnete Bilder abwarten.** Die Bilder zählt der HUD-Layer über `onFrame`. Bei niedrigen FPS laufen sonst viele Ticks pro Bild, und der Screenshot zeigt einen alten Stand. Sicherheitsgrenze: 5 s.
    3. Screenshot `screenshots/peregrine-emergency-<Zeit>.png` über `Screenshot.grab`; das Hauptbild wird per Reflection gesucht, weil die Methode zwischen den Versionen umbenannt wurde.
    4. Zwei Bilder später ausloggen wie im Pause-Menü (`level.disconnect` + `disconnect(EmergencyScreen, false)`).
  - Der Bericht liegt zusätzlich als `.txt` neben dem Bild.
  - `gui/EmergencyScreen`: Bericht und die Knöpfe OPEN SCREENSHOT, SCREENSHOT FOLDER und MAIN MENU. Dateien werden per Reflection über `Util.getPlatform().openFile/openPath` geöffnet, sonst über `java.awt.Desktop`.
  - Getestet in 26.2 mit vorübergehend erhöhter Schwelle (Screenshot zeigt die Welt, Bericht, Bildschirm).
- **Entity ESP (`feature/EntityEsp.java`, Render, Taste J, `key.peregrine.entityesp`):**
  - Farben: Monster rot, Tiere grün, Spieler blau, Items gelb, jeweils einzeln an/aus.
  - Stil: Box + Glow, Box oder Glow; Reichweite 8–128.
  - Name, Entfernung und Lebensbalken für die nächsten 12 (ohne Überlappung), dazu optionale Tracer vom unteren Bildschirmrand.
  - Positionen werden mit dem Teil-Tick interpoliert (`getPosition(pt)`), damit sie flüssig mitlaufen. Höchstens 160 Einträge.
  - Nur bei `Bot.allowed` (Einzelspieler, LAN, Operator).
- **Bot nutzt das Entity ESP:**
  - `Guard` erfasst jede Runde alle Monster im Umkreis von 24 Blöcken, auch ohne Sichtlinie (`EntityEsp.threats`), und daraus `Navigator.danger`. Der A* zahlt (6 − Abstand) × 1,3 Aufschlag für Felder nahe an Monstern, macht also einen Bogen.
  - Ein Creeper ohne Sichtlinie näher als 4,5 Blöcke löst die Flucht aus.
  - Jagd: Beute bis 48 Blöcke auch ohne Sicht (sichtbare zählen 1,4× günstiger); ist sie verdeckt, geht er per A* hin.
  - `EntityEsp.watch(ids)` hebt beobachtete Monster (< 16 Blöcke), die Beute und das Duell-Ziel hervor, auch bei ausgeschaltetem Modul. Bei Stop oder Fertig wird es geleert.
  - Getestet in 26.2: ESP mit Tieren, Monstern, Items und Tracern; „kill cow“ läuft mit Route zur verdeckten Kuh, das Ziel ist markiert.
- **Server-Freigabe (`feature/Access.java`):**
  - **Server-Freigabe (`Access`):** Block ESP, Entity ESP und der Assistent laufen im Einzelspieler, im eigenen LAN, als Operator, oder wenn der Server die Plugin-Nachricht `peregrine:features` schickt (rohe UTF-8-Liste: `blockesp,entityesp,assistant` oder `all`; Fabric `PayloadTypeRegistry.clientboundPlay`). Die Freigabe gilt bis zum Trennen. `Bot.allowed` ruft jetzt `Access.allowed(mc, "assistant")`. Die Combat Aura bleibt nur im Einzelspieler. Eine Liste beliebiger Server zum Eintragen gibt es bewusst nicht.
  - Ein Combat Trainer mit Dummys wurde kurz eingebaut und auf Wunsch des Nutzers wieder komplett entfernt (keine Dummys gewünscht).
  - Server-Freigabe nicht getestet (kein Server mit Plugin im Container).
  - Testumgebung: Im neuen Container fehlten `libxkbcommon-x11-0` (Launcher startet sonst nicht), JDK 25 (`openjdk-25-jdk-headless`), MinGW und xdotool. Maven Central antwortet beim ersten Gradle-Lauf manchmal mit 429, dann einfach wiederholen. In `config.json` muss der Loader `"Fabric"` heißen (groß).
- **Assistent Stufe 3: kein Hängenbleiben, eigene Befehle, Claude als Gehirn:**
  - **Hänger-Erkennung (`Bot.watchdog`):** Fortschritt heißt: 1,2 Blöcke bewegt, Inventar verändert oder ein Block abgebaut. Ausnahmen sind Ausruhen, Essen, Warten, Folgen in der Nähe und das Duell. Nach 12 s ohne Fortschritt: rückwärts, seitwärts und springen, dann `Task.unstuck` (Route vergessen, Ziel überspringen, Richtung wechseln). Nach 25 s dasselbe zur anderen Seite. Nach 40 s gibt er diesen Schritt auf, nennt die Position und macht mit dem nächsten weiter.
  - **`Bot.fail`:** Scheitert ein Teilschritt, läuft der Rest der Kette weiter („Skipping to the next job“). Früher brach alles ab. `Bot.stop` gilt nur noch für Tod, fehlende Freigabe und Notfall.
  - Blöcke, die nach 15 s nicht brechen, landen in `Navigator.AVOID`; Routen gehen darum herum. Früher kam nach 30 s eine Exception, und der ganze Auftrag brach ab.
  - Ausruhen höchstens 45 s, danach 60 s Pause (früher endlos). Nach der Jagd wird die Beute bis zu 5 s lang wirklich eingesammelt; vorher ging er nur einen Tick hin, ließ das Fleisch liegen und jagte endlos weiter (im Test gesehen).
  - **Eigene Befehle (`Commands`, `Actions`):** Neue Bausteine sind wait, say, chat (auch `/befehl`), eat, pick up items, drop junk, come back, follow <Spieler> [for N] sowie `repeat N times: …` und `forever: …` (alles nach dem Doppelpunkt wird wiederholt; drei gescheiterte Runden in Folge beenden es). Parameter im Namen: „remember holz <n>: get <n> wood …“, Aufruf mit „holz 32“. Tippfehler werden bei Items und eigenen Befehlen toleriert (Levenshtein, „Did you mean …“). Jobs werden erst beim Start gebaut (`Supplier`), sehen also die Welt, wie sie dann ist. „show <name>“ zeigt einen Befehl.
  - **Editor `gui/CommandsScreen`** (Knopf MY COMMANDS im Assistenten): links die Liste, rechts NAME und STEPS, SAVE prüft die Schritte vorher, dazu RUN, DELETE, NEW und 5 Beispiele. Einfügen mit Strg+V (`KeyEvent.isPaste`, gibt es in 26.1–26.3).
  - **Claude-Modus (`bot/Claude.java` ohne SDK, `bot/ClaudeBrain.java` mit SDK):** offizielles Anthropic-Java-SDK 3.3.0, Modell `claude-opus-5-5`, Effort medium, Beta-Endpunkt mit `fallbacksDefault()` (Header `server-side-fallback-2026-07-01`), System-Prompt mit Cache. Vor jeder Nachricht bekommt Claude ein Bild vom Spiel: Position, Leben, Hunger, Inventar, Wesen im Umkreis von 32 Blöcken, Wegpunkte, eigene Befehle, Bot-Status und Freigabe. Werkzeuge: `run_steps` (dieselben Befehlswörter, vorher mit `Commands.check` geprüft, optional `notify`), `stop`, `save_command`, `delete_command`. Mit `notify` meldet `Bot.watcher` fertig oder gescheitert zurück, und Claude plant weiter (höchstens 6 automatische Runden pro Anfrage, 8 Tool-Runden pro Antwort, Verlauf höchstens 40 Nachrichten). Der API-Key kommt über den Knopf KEY im Assistenten (wird maskiert angezeigt und landet nicht im Chat) in `<peregrine.home>/peregrine-claude.json`, ersatzweise aus `ANTHROPIC_API_KEY`. Schalter CLAUDE ON/OFF. „stop“ wirkt immer sofort und lokal, „vergiss das Gespräch“ setzt den Verlauf zurück. Kostet API-Guthaben des Nutzers.
  - **SDK als eigene Mod `client-mod/ai-libs`** (`peregrine-ai-libs.jar`, 44 MB, Jar-in-Jar per Loom `include`, slf4j ausgelassen): Das SDK allein hat 40 MB. In jeder Versions-Mod wäre die `.exe` über 140 MB groß geworden (GitHub-Grenze 100 MB). Jetzt ist es einmal eingebettet (`client_mods::AI_LIBS`), die `.exe` hat rund 59 MB. Die Hauptmod nutzt das SDK nur `compileOnly` und prüft per `Claude.libsPresent()`. `build-all.sh` baut beides.
  - **Aufräumen:** Alle 5 s werden im Einzelspieler Mobs mit dem Tag `peregrine_dummy` entfernt, Reste des gelöschten Combat Trainers. Im Test hat so ein gespeicherter Dummy den Spieler zweimal getötet.
  - **Getestet in 26.2:** Bibliotheks-Mod lädt (84 Mods, keine Konflikte), eigener Befehl mit Parameter („holz 2“: 3 Schritte gestartet), Editor (Liste, Beispiel, SAVE mit `forever:`), Claude-Weg mit absichtlich falschem Key (Anfrage geht raus, 401 wird als „Claude rejected the API key“ gemeldet), Aufräumen des alten Dummys. **Nicht getestet:** echte Antworten von Claude (kein Key des Nutzers im Container), die Hänger-Erkennung in einer echten Hängesituation, follow, repeat zur Laufzeit, chat, 26.1 und 26.3 im Spiel, Windows.
- **Testrunde Assistent + PvP-Ausbau + Entity-Picker:**
  - **Getestet in 26.2 (frische Demo-Welt):** „repeat 2 times: say hallo, then wait 3“ (2 Runden), „hol 2 holtz“ (Tippfehler erkannt, fertig), „mach mir eine steinspitzhacke“ von leerem Inventar komplett durch (Bretter, Werkbank, Stöcke, Holzspitzhacke, Holzschwert, Bruchstein, Steinspitzhacke), „kill pig“ (gewonnen), „follow me“ ohne Mitspieler (saubere Absage), „geh zu 40 200 40“ (unerreichbar: Meldung statt stummem Hängen). Rückfall bei ungültigem Claude-Key: Befehl wird lokal ausgeführt. Notfall-Logout bei 0,2 Herzen hat ausgelöst.
  - **Gefunden und behoben:** Mit ungültigem Key gingen alle Befehle verloren, jetzt gibt es einen lokalen Rückfall. Die Statuszeile zeigte „chopping“, während der Selbstschutz jagte oder floh; jetzt steht dort z. B. „Hunting for food (3 hearts) · then: …“, und nach 2 Minuten nur Überleben kommt eine Erklärung. Ein einzelner Buchstabe passte auf irgendein Item („t“ wurde TNT); Teiltreffer jetzt erst ab 3 Zeichen. Bei unerreichbarer Höhe kommt eine klare Meldung.
  - **PvP (`Combat.java`):** Attribute Swap wählt das Item mit dem höchsten geschätzten Schaden (Axt, Mace mit Fallbonus, Density und Breach, Schwert, Speer; Schärfe +2,5), geladen mit der Waffe in der Hand; gegen Schild zuerst die Axt. Speer-Swap bei 3,0–4,3 Blöcken (nur der Speer reicht so weit), Nahkampf greift dann bis 4,3 an. Pearl Catch: Perle, eine Tick später Windladung schräg hinter sich (Yaw + 180°, Pitch 68°), damit der Stoß nach vorn geht; auch bei der Flucht-Perle, Engage schon ab 9 Blöcken, wenn Perlen und Windladungen da sind. **Richtiger Moment** (`calm`): Engage-Perle, Mace-Sprung und Elytra nur, wenn 1,5 s kein Treffer kam und genug Leben da ist; Elytra-Mace erst ab 12 Blöcken und bei freiem Himmel; Lunge nicht direkt nach einem Treffer oder während eines Pearl Catch. **Eingebaute Gegner** (`breakIn`): ohne Sichtlinie bis 7 Blöcke wird der erste Block auf der Linie Auge–Auge (sonst Auge–Füße) abgebaut, notfalls erst hingelaufen. **Crystal:** Obsidian nicht mehr direkt neben den Gegner (das hat ihn eingebaut), sondern 2 Blöcke entfernt auf unserer Seite, höchstens 2 Basen pro 10 s. Nichts davon gegen echte Spieler getestet (kein zweiter Spieler, keine Items in der Demo).
  - **Entity-ESP-Picker (`gui/EntityPickerScreen`):** alle Entity-Typen mit Spawn-Ei-Symbol durchsuchbar, Spieler in der Nähe oder ein beliebiger Name (Enter), eigene Pick-Farbe, Modus „Only my picks“ und die Kategorie-Schalter. Die Picks stehen als `picks` (`minecraft:cow`, `player:steve`) oben in den Einstellungen mit CHOOSE. In 26.2 getestet: Zombie, Schwein und „steve“ ausgewählt, Zombies leuchten lila durch Wände.
- **PvP Runde 3 (Essen, Totems, Crystal-Sicherheit, Bewegung):**
  - **Essen im Kampf (Bug):** Früher aß er im Duell bei weniger als 4,5 Herzen sofort, auch normales Essen und auch bei vollem Hunger. Weil das nicht heilt, aß er immer wieder und griff nicht mehr an. Jetzt heilt im Kampf nur ein goldener Apfel (`Guard.canGapple`/`eatGapple`, verzauberter zuerst, danach 5 s Pause, nicht während Regeneration läuft). Er isst nicht, wenn er in der letzten Sekunde getroffen wurde. Wird er beim Essen getroffen oder kommt ein Gegner auf 2,5 Blöcke ran, bricht er ab und kämpft. Normales Essen im Duell nur bei Hunger ≤ 6 und Abstand > 8. Bei Mobs: Rückzug, dann Apfel; kein Essen mehr beim Weglaufen.
  - **Totems:** Mit Totem (Hand oder Inventar) bleibt er im Kampf. Ohne Totem bei < 3,5 Herzen läuft er weg (`Guard.backOff`, höchstens 3 s), die Flucht-Perle gibt es weiter.
  - **Crystal:** Eigenschaden (vor Rüstung, also eher zu hoch geschätzt) muss mindestens 4 Herzen übrig lassen und darf nie über 6 Herzen liegen, auch mit Totem (`safeForUs`). Vor dem Setzen kommt ein Sprint-Treffer mit dem Schwert („hit up“), wenn der Gegner am Boden ist; dann folgt der Kristall, solange er in der Luft ist.
  - **Bewegung:** Im Nahkampf läuft er diagonal (vorwärts + seitwärts). Der A* hat diagonale Schritte auf gleicher Höhe (Kosten 1,41, beide Seitenfelder frei, keine Ecken abschneiden, nie neben Lava).
  - **Säule:** Ist der Gegner > 3 Blöcke höher und < 4 Blöcke seitlich entfernt, baut er sich mit Bruchstein, Erde & Co. hoch (springen, Block unter die Füße, Decke vorher abbauen). Brücken zu Gegnern in der Ferne gibt es noch nicht.
  - **Elytra-Mace** schon ab 9 Blöcken und 4,5 Herzen (vorher 12 und 6); nach der Landung zieht `Guard.gear` sofort die Brustplatte an.
  - **Getestet:** nur Kompilieren (26.1–26.3) und ein Lauftest, bei dem der Bot nachts mit Holzschwert bei 4 FPS von Zombies getötet wurde (keine Aussage). Alles andere braucht einen echten Gegner mit Ausrüstung.
- **Schild gegen Mace, Stun Slam bei jedem Fall-Treffer (`Combat.shieldUp`, `attackWithSwap`):** Ist ein gegnerischer Spieler mit Mace in der Hand in der Luft, mehr als 1,5 Blöcke über uns und weniger als 5 Blöcke seitlich entfernt, hebt der Bot ein Schild. Das kommt aus der zweiten Hand oder wird aus der Hotbar in die Haupthand getauscht, damit das Totem in der zweiten Hand bleibt. Er hält es bis 8 Ticks nach der Landung und wechselt dann zurück. Fällt er selbst (Fall > 1,5) auf einen Gegner mit erhobenem Schild und hat Axt und Mace, kommt immer der Stun Slam (Axt, dann Mace im selben Tick), auch bei normalen Krit-Sprüngen. Ohne Axt gibt es keinen Mace-Sprung gegen ein erhobenes Schild. Nicht im Spiel getestet.
- **Crystal genau geprüft:** `crystalDamage` folgt der Vanilla-Formel (Stärke 6: (i² + i) / 2 · 84 + 1). Abstand zu Füßen oder Körpermitte, der kleinere zählt, volle Trefferfläche, keine Rüstung. Bei Spielern zählt die Schwierigkeit: Easy min(d/2 + 1, d), Hard ×1,5 – das fehlte vorher, auf Hard wurde der Eigenschaden unterschätzt. `safeForUs`: mindestens 4 Herzen müssen übrig bleiben, nie mehr als 6 Herzen auf einmal, auch mit Totem. Vor dem Setzen und dem Zerschlagen werden auch alle Kristalle im Umkreis von 12 Blöcken geprüft (Kettenexplosion; der schlimmste zählt wegen der Unverwundbarkeits-Ticks). **Hochschlagen ist Pflicht:** Gecrystallt wird nur, wenn der Gegner in der Luft ist oder vor weniger als 15 Ticks getroffen wurde. Sonst übernimmt der Schwertkampf, geht ran und schlägt. Jeder Treffer über `attackWithSwap` zählt. Nicht im Spiel getestet.
- **Keine Pausen im Kampf, Heil-Flug, Aura auf Spieler:**
  - Goldene Äpfel isst er im Laufen und Strafen, ohne extra Rückzug, aber nicht in der Sekunde, in der er getroffen wird. Ausruhen gibt es nicht, solange ein Monster im Umkreis von 16 Blöcken ist. Ohne Totem bei < 3 Herzen kurzer Sprint weg (höchstens 2 s), im Laufen isst er.
  - **Heil-Flug (`Combat.healTrip`):** nur bei ≤ 2 Herzen und ohne Totem. Mit Elytra und Raketen fliegt er vom Gegner weg (−25°, Raketen, bis 5 s oder 60 Blöcke), gleitet runter, zieht die Brustplatte an und heilt: Apfel, Essen, dann Regeneration bis 8 Herzen oder 40 s. Dann geht das Duell weiter (hinlaufen, oder per Elytra-Mace). Ohne Elytra: Perle und Sprint.
  - **Elytra-Mace** nur, wenn über dem Gegner 24 Blöcke frei sind (`openAbove`), auch während des Flugs geprüft. Geht er unter ein Dach, bricht er den Sturz ab.
  - **Notfall-Logout** ist standardmäßig aus. Gespeicherte Einstellungen werden einmalig umgestellt (`Store` Format 4).
  - **Combat Aura:** neue Ziele „Players“ und „Players + hostile“ (nie Zuschauer oder Kreativ-Spieler). Sie läuft jetzt unter derselben Regel wie der PvP-Bot (`Access.AURA`): Einzelspieler, eigene LAN-Welt, Operator oder Server-Freigabe `aura`; vorher nur Einzelspieler. Keine Tarnung vor Anti-Cheats.
  - Nichts davon im Spiel getestet.
- **Mace-Stil nach dem Video des Nutzers** (Ranked-Mace-PvP in einer Arena: zwei Maces, Netherite-Schwert, goldene Äpfel, Brustplatte/Elytra-Tausch, Windladungen, Perlen, Totem in der zweiten Hand; ständige Windladungs-Sprünge mit Mace-Schlag von oben, Stun Slam gegen Schilde, Windladungen gegen Gegner in der Luft):
  - Mace-Sprung bis 7 Blöcke Abstand, schon 0,75 s nach einem Treffer, ab 3 Herzen.
  - Zweite Windladung am höchsten Punkt (|vy| < 0,12, Gegner seitlich < 6 Blöcke, höchstens 14 Blöcke über ihm) für mehr Fallhöhe.
  - Ohne Schild: Windladung in die Flugbahn eines Gegners, der mit Mace von oben kommt.
  - Nicht im Spiel getestet.
- **Kein Ausruhen mehr, Heilen nur 100 Blöcke weg:** Der Ausruh-Zweig im `Guard` ist entfernt („I'm hurt – resting …“ gibt es nicht mehr). Bei wenig Leben ohne Essen jagt er unterwegs. `Combat.healTrip` nur mit Elytra und Raketen (≤ 2 Herzen, kein Totem): Er fliegt, bis kein Spieler näher als 110 Blöcke ist (höchstens 30 s), landet und heilt erst, wenn niemand innerhalb von 100 Blöcken ist. Kommt jemand näher, startet er wieder. Ohne Elytra gibt es keine Heilpause, er kämpft weiter (goldene Äpfel im Laufen bleiben). Nicht im Spiel getestet.
- **Elytra-Mace neu, Kollisionsschutz, Meteor-Regeln, nie Ausruhen:**
  - **Elytra-Mace (`elytraMace`):** 1) Elytra an, springen, Flügel öffnen. 2) Mit Raketen auf 25 Blöcke über dem Gegner steigen. 3) Waagerecht fliegen, bis er über ihm ist (etwas früher, je nach Tempo). 4) **Dort die beste Brustplatte anziehen**, die Elytra geht aus, freier Fall. Vorher blieb die Elytra an, er glitt hinunter und hatte kaum Fallhöhe. 5) Mace-Schlag oder Stun Slam beim Fallen; verfehlt und der Boden ist nah: Windladung unter die Füße. Der allgemeine Fall-Cancel ist während des Flugs aus, damit er den Schlag nicht vorwegnimmt.
  - **Nie gegen Blöcke fliegen (`steerClear`):** Raycasts 8 Blöcke voraus in Wunschrichtung und 10 entlang der Bewegung, auf Kopf-, Fuß- und Überkopfhöhe. Ist etwas im Weg, steigt er (−45° oder −70°) und dreht zur freien Seite (±35°, ±70°, ±110°), notfalls mit Rakete, sonst zurück und hoch. Gilt bei Elytra-Mace und Heil-Flug.
  - **Crystal nach Meteor-Vorbild** (Meteor Client `CrystalAura`, nur Spielregeln, keine Tarnung): Mindestschaden 6, wenn das Leben des Gegners ≤ 8 ist nur 1,5 (Face-Place); erst zerschlagen, dann setzen; kein Kristall in der Hitbox des Gegners; hinter Wänden nur bis 3 Blöcke; Weakness → erst Waffe in die Hand; gerade angegriffene Kristalle 4 Ticks ignorieren.
  - **Wind Burst:** Nach einem Mace-Treffer mit Wind Burst bleibt er im Mace-Modus (Kette).
  - **Nie Ausruhen:** Er isst auch, wenn er getroffen wird (kein Abbruch, keine Sperre). Der Heil-Flug wartet nicht mehr auf Regeneration: landen, essen, sofort zurück.
  - Nicht im Spiel getestet.
- **Beste Technik je Moment:** `crystalPotential` schätzt den Schaden des besten sicheren Kristalls: vorhandene Kristalle, Obsidian- oder Grundgestein-Basen, oder eine Basis 2 Blöcke neben dem Gegner, wenn Obsidian da ist. Mace-Sprung zählt grob 14. Bei Crystal ≥ 6 und ≥ 0,8 × Mace spielt er Crystal-PvP (Schwert schlägt hoch, kein Mace-Sprung), sonst Mace, dann Crystal, dann Schwert. Nicht im Spiel getestet.
- **Schild sofort brechen:** Hebt der Gegner das Schild und ist er in 3,6 Blöcken Reichweite, wechselt der Bot im selben Tick auf die Axt, schlägt, wechselt zurück (ohne auf volle Ladung zu warten; 5 Ticks Pause bis zum nächsten Versuch). Nicht im Spiel getestet.
- **Mace-Treffsicherheit, Elytra-Kreisflug, Kampf-Protokoll, Bewachen, Tränke:**
  - **Mace-Sprung:** Abstand > 2,5: Windladung schräg hinter sich (Yaw + 180°, Pitch 90 − 6 × Abstand, 55–85°), damit der Stoß zum Gegner schiebt. In der Luft `airSteer`: Fallzeit simuliert (Schwerkraft 0,08, Luftwiderstand 0,98) bis auf Kopfhöhe des Gegners; seine Position bei der Landung vorhergesagt; eigener Schwung (0,91 pro Tick) abgezogen; Rest über W/S/A/D im Blickrahmen. Zweite Windladung nur bei < 4 Blöcken seitlich. Treffer bei `reachTo` (Auge bis Hitbox) ≤ Reichweite − 0,05 im Fallen. Vorher wurde von Füßen zu Füßen gemessen.
  - **Elytra-Kreisflug behoben:** Prüfstrahlen auf Körperhöhe (±0,35), da ein gleitender Spieler flach liegt; der Fußstrahl −1,2 traf ständig den Boden. Ausweichkurs wird 10 Ticks gehalten. Boden voraus → nur hochziehen. Steigen bei < 15 Blöcken fast senkrecht (−80°) mit fester Richtung. Abwurf, wenn der Schwung ihn im Fall auf den Gegner trägt (Geschwindigkeit × (1 − 0,91^t) / 0,09), oder sofort, wenn er schon vorbei ist; im Fall lenkt `airSteer`.
  - **`CombatLog`:** `<peregrine.home>/peregrine-combat.log` (Windows: `%USERPROFILE%\.peregrine\client\`), neu pro Duell. Darin: Ausrüstung, Sprünge, zweite Sprünge, SMASH/MISS mit Reichweite, Fallhöhe und Abstand, Elytra-Abwurf und Elytra-Treffer, Axt gegen Schild. Assistent-Befehl „combat log“ öffnet den Ordner.
  - **Ideen aus MeinBot** (Mineflayer-Bot, github.com/Meindo/meinbot, MIT): Befehl „guard“/„bewache“ (`Actions.GuardSpot`: Monster im Umkreis von 16 Blöcken bekämpfen, zurück auf den Platz; ein eigener Befehl gleichen Namens hat Vorrang) und Tränke (`Guard.healPotion`: Wurftrank Heilung/Regeneration an die Füße, sonst Trank trinken, wenn kein goldener Apfel da ist; `strengthPotion` zu Kampfbeginn).
  - **Getestet in 26.2:** „bewache“ (läuft, Selbstschutz kämpft nebenher), „combat log“ (Meldung und Pfad). Mace, Elytra und Tränke nicht (keine Items in der Demo).
- **Pausen-Bug, Zugangscode, Server-Plugin:**
  - **Schwerer Bug in `Bot.holdBest`:** Das gehaltene Item bekam +0,001 Bonus, auch wenn es gar nicht passte (Wert 0). Fehlte das gesuchte Item (Essen, Apfel, Waffe, Kristall), galt das Item in der Hand als „bestes“. Die Aufrufer meinten, sie wechselten noch, und warteten für immer: Das war die Pause, in der er nichts tat. Jetzt zählen nur Items mit Wert > 0. Getestet: „wait 20“ zählt herunter, danach läuft der Auftrag.
  - Pausen im Kampf werden zum Essen genutzt (`Guard.idleEat`: Gegner nicht sichtbar, Bewachen, Folgen, Warten). Die Kristall-Wartezeit hat keinen Leerlauf mehr (Schwertkampf läuft weiter). Die Statuszeile zeigt den genauen Selbstschutz-Zweig (`Guard.why`).
  - **Zugangscode (`feature/CodeLock`, `gui/CodeScreen`):** Code 1751, gespeichert nur als SHA-256 von „peregrine:1751“ (im Jar und in `peregrine-access.json`). Bis zur Eingabe gibt es kein HUD, kein Menü, keinen Assistenten und keine Modul-Ticks; jede Peregrine-Taste öffnet die Abfrage. Auf Servern meldet der Client einmal „unlocked“ oder „locked“ auf `peregrine:hello`, sobald der Server den Kanal kennt (prüft bis 30 s nach dem Join). Getestet: Abfrage, falscher Code, 1751 öffnet das Menü. Ein Java-Kundiger kann die Prüfung aus der Mod entfernen.
  - **Server-Plugin `server-plugin/` → `downloads/PeregrineGrant.jar`:** Für Paper, Spigot und Purpur, gebaut gegen eigene Bukkit-Stubs, weil die Paper-Quellen gesperrt waren. `allowed.txt`: `features:`, `ban-without-code:`, Spielernamen (`*` = alle). Sendet `peregrine:features` an gelistete Spieler (20, 60 und 140 Ticks nach dem Join und nach „unlocked“) und bannt per Konsolen-`ban` bei „locked“. Nicht auf einem echten Server getestet.
  - **Crystal:** Liegt der beste sichere Kristall bei ≥ 4 Schaden, spielt er Crystal-PvP, auch mit Mace. Die Suche nach Basis-Stellen geht jetzt über 2–3 Blöcke Abstand und eine Höhe tiefer.
  - Ein „guard“ aus „Meine Befehle“ hat Vorrang vor dem eingebauten „guard“ (Test-Home hatte so einen).
- **Schnellere Wege (`Navigator`, `Combat`):** Er sprintet beim Laufen (Hunger > 6, nicht im Wasser). Sprint-Sprünge auf mindestens 4 flachen, freien Schritten mit Platz über dem Kopf. Er peilt den weitesten Routenpunkt (bis 8 Schritte) an, der gerade und frei erreichbar ist (`straightLine`: beide Schultern, Boden, kein Wasser oder Lava), und erkennt übersprungene Schritte, statt neu zu planen. Im Duell läuft er zum vorhergesagten Ort (Tempo × min(40, d / 0,28) × 0,6) und plant neu, wenn der sich um mehr als 3 Blöcke verschiebt oder nach 3 s. Getestet in 26.2: 41 Blöcke durch Wald, angekommen.
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

## PvP-Bot Runde 2 (Branch `claude/beautiful-hawking-m1ufrc`)

- **Pausen-Fehler behoben:** `Guard` erkannte Duelle nicht (`Bot.current()` ist immer ein `Commands.Chain`, nie direkt `Combat.Duel`). Dadurch aß er mitten im Kampf normales Essen, jagte Tiere oder wich vor Monstern aus. Neu: `Chain.duel()` / `Repeat.duel()`.
- **Essen in Bewegung:** Während Apfel, Essen oder Trank (1,6 s) bleibt er nicht mehr stehen. `Duel.whileBusy` weicht zurück, schaut den Gegner an und strafet.
- **Echter Explosionsschaden (`bot/Blast.java`):** Abstand von den Füßen, Sichtanteil mit demselben Strahlengitter wie Vanilla, Schwierigkeit, Rüstung + Härte (aus den getragenen Teilen geschätzt, falls das Attribut fehlt), Protection/Blast Protection, Resistance. Vorher wurde immer der schlimmste Fall ohne Rüstung angenommen. Deshalb fand er gegen Netherite kaum Stellen.
- **Crystal:** kein Warten mehr auf „erst hochschlagen“. Jeden Tick wird ein Kristall zerschlagen oder gesetzt. Es reicht 1 Block Luft über der Basis (wie Vanilla). Mindestschaden 2 Herzen (weniger, wenn es tötet). Eigener Schaden höchstens 5 Herzen (6 mit Totem), es bleiben immer 2 Herzen übrig. Die Kandidaten werden erst grob (ohne Strahlen) sortiert, genau gerechnet werden nur die besten 10.
- **Respawn Anchor (neu, außerhalb des Nethers):** setzen → mit Glowstone laden → mit anderem Item zünden (Stärke 5). Gleiche Sicherheitsregel, und er tritt zurück, wenn er selbst zu nah steht.
- **Schwert:** Treffer nur bei echter Reichweite (Auge → Hitbox, wie der Server prüft, nicht mehr Mittelpunkt-Abstand 3,0) und nur, wenn die Unverwundbarkeit des Gegners vorbei ist (`hurtTime`). Abstandsspiel: Beim Aufladen hält er sich am Rand der Gegner-Reichweite und tritt so heran, dass der Schlag beim Ankommen geladen ist. Er zielt mit Vorhalt auf Brusthöhe.
- **Hängen in der Nähe:** Bei fehlender Sichtlinie, einem Gegner auf einem Absatz oder 1 s ohne Annäherung läuft er per A* (`chaseAround`), statt gegen die Wand zu drücken. Graben (`breakIn`) nur noch, wenn der Gegner an allen 4 Seiten eingemauert ist.
- **Mace/Elytra-Mace:** Landepunkt 1,1 Blöcke neben dem Gegner statt genau darüber. Von oben ist das Zeitfenster in Reichweite bei schnellem Fall kürzer als ein Tick, deshalb gab es Fehlschläge. Seitlich bleibt der Gegner mehrere Ticks in Reichweite.
- **Kein Reach-Hack:** Vanilla-Server würden Angriffe bis ~6 Blöcke annehmen. Der Bot bleibt bewusst bei der normalen Reichweite.
- **Getestet:** nur Kompilieren für 26.1/26.2/26.3. Nichts im Spiel, nichts gegen echte Spieler. Das Kampf-Log (`peregrine-combat.log`) schreibt jetzt auch Schwerttreffer, Kristall- und Anchor-Schätzungen.
- **Jump Reset:** In dem Tick, in dem ein Treffer des Gegners ankommt (`hurtTime ≥ 9`, am Boden, Gegner in der Nähe), springt er nach vorn mit Sprint. Der Rückstoß wird so großteils aufgehoben, und er kann in der Luft zurückschlagen, wenn sein Schlag geladen ist.
- **Kombos:** Ein Treffer zählt als gelandet, wenn direkt nach seinem Schlag die Treffer-Animation des Gegners startet. Solange der Gegner nicht zurücktrifft (1,5 s Fenster), ist er „im Combo“: kein Strafen, kurzer W-Tap ohne Rückschritt, volles Nachsprinten, keine Crit-Sprünge (außer zum Erledigen), der nächste Sprint-Treffer kommt, sobald der Schlag geladen und der Gegner in Reichweite ist. Status zeigt „combo xN“, das Log schreibt Combo-Längen und Jump Resets mit.
- **Attribute Swap (richtig gerechnet):** Im 26.2-Servercode geprüft: `handleSetCarriedItem` setzt nur den Slot. `Player.attack` nimmt Grundschaden und Aufladung aus dem Attribut (aktualisiert erst im Spieler-Tick, also vom vorher gehaltenen Item), Verzauberungen (`getEnchantedDamage`) und den Mace-Bonus (`getAttackDamageBonus`) aber vom Item in der Hand. Deshalb lädt er mit dem schnellen Schwert und tauscht für den Schlag das Item mit dem größten Zusatz ein: Axt gegen Schild, Mace beim Fallen (Bonus + Density + Breach gegen Rüstung), Sharpness, Fire Aspect (wenn der Gegner nicht brennt), Speer jenseits der Schwertreichweite. Steht im Log als „attribute swap A -> B“.
- **Elytra-Mace neu:** startet jetzt auch mitten im Kampf (nicht erst ab 9 Blöcken Abstand, nur nicht, solange Treffer einschlagen, und nicht, wenn Kristalle gerade mehr bringen). Er steigt fast senkrecht bis ~22 über den Gegner. Abwurf erst, wenn der **Landepunkt als Vektor** (Schwung × Luftbremsung 0,91 über die echte Fallzeit gegen die Vorhersage des Gegners) innerhalb dessen liegt, was die Lufttasten noch korrigieren können. Vorher zählte nur „Abstand ≤ Schwungweite“ ohne Richtung, deshalb gab es die Fehlwürfe. Ist er zu schnell, bremst er mit der Nase nach oben, ohne Rakete. Bei einem Fehlschlag zieht er die Elytra wieder an und gleitet herunter (oder Windladung kurz vor dem Boden), statt aus 25 Blöcken aufzuschlagen. Log: „DROP … predicted miss …“, „ELYTRA SMASH/MISS“.
