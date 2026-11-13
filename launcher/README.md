# Peregrine Client – Minecraft-Launcher (Rust)

Ein schneller Minecraft-Launcher, geschrieben in **Rust** – mit cleanem Design, das du komplett anpassen kannst.

## Warum ist er schnell?
- **Startet sofort**: eine einzige kleine Programmdatei (~10 MB), kein Python, kein Java, kein Electron.
- **Lädt parallel**: bis zu 64 Dateien gleichzeitig über HTTP/2. Spiel, Bibliotheken,
  Assets und Java werden in einem Rutsch geholt.
- **Schnellstart**: Ist eine Version schon komplett installiert, wird gar nichts mehr
  geprüft oder geladen und Minecraft startet direkt. (Mit dem Haken **„Reparieren“**
  werden alle Dateien einmal per Prüfsumme kontrolliert.)
- **Optimierte Java-Einstellungen** (G1-Garbage-Collector wie im offiziellen Launcher)
  für weniger Ruckler.

## Aussehen anpassen (Seite „Aussehen“)
- **Farbschemas**: Graphit, OLED, Mitternacht, Hell – oder jede einzelne Farbe selbst wählen
- **Akzentfarbe** aus Vorlagen oder per Farbwähler, optional mit Farbverlauf und Leuchteffekt
- **Hintergrund**: Block-Landschaft (Abend/Tag/Nacht), Verlauf, Einfarbig oder **eigenes Bild**
  (einfach ins Fenster ziehen), mit regelbarer Abdunklung
- **Eckenrundung**, **Oberflächen-Größe**, **Schriftart**, Animationen an/aus
- **Startseite**: Titel, Untertitel und Slogan frei wählbar, Elemente ein-/ausblenden
- **Design teilen**: als Code kopieren und bei Freunden einfügen; Zurücksetzen mit einem Klick

## Profile & Inhalte (alles von Modrinth)
- **Profile anlegen**: Name, Minecraft-Version und Modloader (Fabric, Quilt oder Vanilla).
  Jedes Profil hat einen **eigenen Spielordner** – Welten, Einstellungen und Inhalte bleiben getrennt.
- **Entdecken** – alles von [Modrinth](https://modrinth.com), mit Icons, Beschreibung und Downloadzahlen,
  passend zur Version und zum Loader des Profils:
  - **Mods** – benötigte Abhängigkeiten (z. B. Fabric API, Sodium) kommen automatisch mit
  - **Ressourcenpakete**
  - **Shader** – der Shader-Mod **Iris** wird automatisch mitinstalliert
  - **Datenpakete** – du wählst, in welche Welt sie kommen
- **Modpacks installieren**: Ein Klick legt ein neues Profil mit allen Mods, Ressourcenpaketen,
  Konfigurationen und genau der Fabric-/Quilt-Version des Modpacks an (Modrinth-`.mrpack`).
- Installiertes nach Art sortiert, **ein-/ausschalten** oder **entfernen**; Profil bearbeiten, Ordner öffnen.
- Profile lassen sich auch auf der Startseite in der Versionsauswahl wählen und mit „Spielen“ starten.
- Mit **Vanilla** gehen Ressourcen- und Datenpakete; Mods und Shader brauchen Fabric oder Quilt.
  Forge/NeoForge werden (noch) nicht unterstützt.

## Funktionen
- **Alle Minecraft-Versionen**: Releases (auch die neuen Jahres-Versionen wie 26.1), Snapshots
  sowie die uralten Beta- und Alpha-Versionen – mit Suchfeld und Filtern
- Modloader: **Fabric** und **Quilt** (oder Vanilla)
- Passende **Java-Version wird automatisch** heruntergeladen
- **Microsoft-Login**, die Sitzung wird beim Start automatisch erneuert
- Ohne Account: offizieller **Demo-Modus**
- Einstellbar: Arbeitsspeicher, Fenstergröße, Spielordner, Java-Pfad, JVM-Argumente
- Live-Log der Spielausgabe, funktioniert offline mit installierten Versionen

## Fertige Programmdatei holen (ohne Rust)
Auf GitHub unter **Actions → „Peregrine Client bauen“** den neuesten Lauf öffnen und unten bei
*Artifacts* **Peregrine-Client-Windows** (bzw. Linux/macOS) herunterladen.

## Selbst bauen
[Rust installieren](https://rustup.rs), dann:

```bash
cd launcher
cargo build --release
```
Die Datei liegt dann unter `target/release/peregrine(.exe)`.

## Microsoft-Login einrichten (einmalig)
Microsoft verlangt für jeden Launcher eine eigene App-Registrierung:

1. Auf <https://portal.azure.com> → **App-Registrierungen** → **Neue Registrierung**
2. Kontotypen: **„Persönliche Microsoft-Konten“** auswählen
3. Umleitungs-URI: Plattform **„Mobile- und Desktopanwendungen“**, Adresse
   `http://localhost:8765/callback`
4. Unter **Authentifizierung** → „Öffentliche Clientflows zulassen“ auf **Ja**
5. Die **Anwendungs-ID (Client-ID)** kopieren und im Launcher unter
   **Einstellungen → Azure Client-ID** einfügen
6. Neue Apps muss Mojang einmalig für die Minecraft-API freischalten:
   <https://aka.ms/mce-reviewappid> (Formular, dauert ein paar Tage)

## Speicherorte
- Einstellungen, Login & Java: `~/.peregrine/`
- Spieldateien (Standard): `~/.peregrine/minecraft/`

## Logo
Das Icon – ein weißer Falke im Sturzflug auf schwarzem Abzeichen – stammt aus
`assets/logo/peregrine-icon.pdf`. Daraus erzeugt sind `icon.png` (Fenster-/Taskleisten-Symbol)
`icon.ico` (wird unter Windows beim Bauen in die `.exe` eingebettet – Explorer, Verknüpfungen,
Datei-Eigenschaften) und `logo.png` (Titelleiste und, falls unter „Aussehen › Startseite“ eingeschaltet, groß auf
der Startseite).

## Lizenzen
Schriftart **Poppins** (SIL Open Font License, siehe `assets/fonts/OFL.txt`).
