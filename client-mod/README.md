# Peregrine Client – Client-Mod (Fabric)

Die Fabric-Mod hinter der Seite **„Client-Mods“** im Launcher. Der Launcher legt sie automatisch
in Fabric-/Quilt-Profile (Minecraft 26.1, 26.2, 26.3) und installiert bei Bedarf die Fabric API.

## Mod-Menü im Spiel (Rechts-Shift)
- **Kategorien** links (Alle, HUD, PvP, Grafik, Bewegung, Sonstiges), **Suche** oben
- **Modul-Kacheln** mit Ein-/Aus-Schalter, Zahnrad = **Einstellungen** (Schalter, Schieberegler,
  Auswahl, Farbpalette), „Zurücksetzen“ pro Modul
- **HUD bearbeiten**: alle Anzeigen mit der Maus verschieben (rastet an Rändern/Mitte ein),
  Mausrad = Größe. Nicht verschobene Anzeigen ordnen sich automatisch ohne Überlappung an.
- Alles wird sofort in `config/peregrine-client.json` des Profils gespeichert.

## Module (22)
| Kategorie | Module |
|---|---|
| **HUD** | FPS · CPS · Koordinaten (+ Richtung) · Keystrokes · Rüstung (mit Haltbarkeit) · Trank-Effekte · Uhrzeit · Ping · Server-Adresse · Geschwindigkeit · Tageszähler · Arbeitsspeicher · Reichweite · Combo-Zähler |
| **PvP** | Fadenkreuz (Kreuz/T/Punkt/Kreis, Größe, Abstand, Dicke, Farbe, Umrandung, Mittelpunkt) · Kein Schadens-Wackeln |
| **Grafik** | Zoom (Taste C, weich, Stärke) · Fullbright (Taste G) · Kein Regen |
| **Bewegung** | Auto-Sprint (Taste V) · Schleichen umschalten |
| **Sonstiges** | HUD-Stil (Farben, Hintergrund, Deckkraft, Schatten) |

Alle Tasten lassen sich im Spiel unter *Optionen › Steuerung › Peregrine Client* ändern.

## Bauen
Braucht ein **JDK 25**. Für alle unterstützten Versionen bauen und in den Launcher kopieren:

```bash
cd client-mod
./build-all.sh          # -> launcher/assets/client-mod/peregrine-client-26.x.jar
```

Neue Minecraft-Version: in `build-all.sh` eine Zeile mit Minecraft- und Fabric-API-Version
ergänzen und in `launcher/src/client_mods.rs` bei `JARS` eintragen.
Minecraft 26.x ist nicht mehr verschleiert – die Mod nutzt direkt Mojangs Namen (keine Mappings).
