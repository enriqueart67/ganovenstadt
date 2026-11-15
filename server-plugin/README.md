# PeregrineGrant – Server-Plugin (Paper / Spigot / Purpur)

Erlaubt ausgewählten Spielern auf **deinem** Server die Peregrine-Funktionen (Assistent/Bot, Block ESP, Entity ESP, Combat Aura) – auch ohne OP – und bannt Spieler, deren Peregrine Client nicht mit dem Zugangscode freigeschaltet ist.

## Installation
1. `downloads/PeregrineGrant.jar` in den Ordner `plugins/` des Servers legen, Server neu starten.
2. `plugins/PeregrineGrant/allowed.txt` bearbeiten:
   ```
   features: all
   ban-without-code: true
   DeinName
   ```
   - `features:` `all` oder eine Liste aus `blockesp, entityesp, assistant, aura`
   - `ban-without-code:` `true` = wer den Peregrine Client ohne Code benutzt, wird gebannt
   - darunter ein Spielername pro Zeile (`*` = alle)
3. Server neu starten (oder `/reload confirm`).

## Bauen
`./build.sh` (JDK 17+). Die Klassen in `stubs/` sind nur Platzhalter zum Kompilieren; der Server liefert die echten Bukkit-Klassen.

## Grenzen
- Gebannt wird nur auf dem Server, auf dem das Plugin läuft.
- Spieler ohne Peregrine Client senden nichts und werden nicht angefasst.
- Nicht auf einem echten Server getestet (Paper-Quellen waren in der Bauumgebung gesperrt).
