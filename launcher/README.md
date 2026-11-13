# Ganovenstadt-Launcher (Rust)

Ein schneller Minecraft-Launcher, geschrieben in **Rust**, mit eigener Oberfläche (egui).

## Warum ist er schnell?
- **Startet sofort**: eine einzige kleine Programmdatei (~10 MB), kein Python, kein Java, kein Electron.
- **Lädt parallel**: bis zu 64 Dateien gleichzeitig über HTTP/2. Spiel, Bibliotheken,
  Assets und Java werden in einem Rutsch geholt.
- **Schnellstart**: Ist eine Version schon komplett installiert, wird gar nichts mehr
  geprüft oder geladen und Minecraft startet direkt. (Mit dem Haken **„Reparieren“**
  werden alle Dateien einmal per Prüfsumme kontrolliert.)
- **Optimierte Java-Einstellungen** (G1-Garbage-Collector wie im offiziellen Launcher)
  für weniger Ruckler.

## Funktionen
- Alle Minecraft-Versionen (Releases, optional Snapshots), auch uralte
- Modloader: **Fabric** und **Quilt** (oder Vanilla)
- Passende **Java-Version wird automatisch** heruntergeladen
- **Microsoft-Login**, die Sitzung wird beim Start automatisch erneuert
- Ohne Account: offizieller **Demo-Modus**
- Einstellbar: Arbeitsspeicher, Fenstergröße, Spielordner, Java-Pfad, JVM-Argumente
- Live-Log der Spielausgabe, funktioniert offline mit installierten Versionen

## Fertige Programmdatei holen (ohne Rust)
Auf GitHub unter **Actions → „Launcher bauen“** den neuesten Lauf öffnen und unten bei
*Artifacts* **Ganovenstadt-Launcher-Windows** (bzw. Linux/macOS) herunterladen.

## Selbst bauen
[Rust installieren](https://rustup.rs), dann:

```bash
cd launcher
cargo build --release
```
Die Datei liegt dann unter `target/release/ganovenstadt-launcher(.exe)`.

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
- Einstellungen, Login & Java: `~/.ganovenstadt-launcher/`
- Spieldateien (Standard): `~/.ganovenstadt-launcher/minecraft/`
