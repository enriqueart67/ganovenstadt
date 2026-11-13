# Peregrine Client – Client-Mod (Fabric)

Die Fabric-Mod hinter der Seite **„Client-Mods“** im Launcher. Der Launcher legt sie automatisch
in Fabric-/Quilt-Profile (Minecraft 26.1, 26.2, 26.3) und installiert bei Bedarf die Fabric API.

## Funktionen
| | |
|---|---|
| **Info-Anzeige** | FPS, CPS (links \| rechts), Koordinaten, Himmelsrichtung, Uhrzeit – Ecke, Farben, Hintergrund, Schatten wählbar |
| **Keystrokes** | W A S D, linke/rechte Maustaste mit CPS, Leertaste – gedrückte Tasten leuchten |
| **Fadenkreuz** | Kreuz, T, Punkt oder Kreis; Größe, Abstand, Dicke, Farbe, Umrandung, Mittelpunkt |
| **Zoom** | Taste **C** gedrückt halten, weich, Stärke einstellbar |
| **Fullbright** | Taste **G** |
| **Auto-Sprint** | Taste **V** |

Alle Tasten lassen sich im Spiel unter *Optionen › Steuerung › Peregrine Client* ändern.
Die Einstellungen stehen in `config/peregrine-client.json`; der Launcher schreibt sie, und die Mod
lädt Änderungen **während des Spiels** automatisch neu (etwa einmal pro Sekunde).

## Bauen
Braucht ein **JDK 25**. Für alle unterstützten Versionen bauen und in den Launcher kopieren:

```bash
cd client-mod
./build-all.sh          # -> launcher/assets/client-mod/peregrine-client-26.x.jar
```

Neue Minecraft-Version: in `build-all.sh` eine Zeile mit Minecraft- und Fabric-API-Version
ergänzen und in `launcher/src/client_mods.rs` bei `JARS` eintragen.
Minecraft 26.x ist nicht mehr verschleiert – die Mod nutzt direkt Mojangs Namen (keine Mappings).
