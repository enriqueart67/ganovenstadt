# ⛏ Ganovenstadt-Launcher

Ein eigener Minecraft-Launcher in Python mit grafischer Oberfläche.

## Funktionen
- Alle Minecraft-Versionen (Releases, optional Snapshots)
- Modloader: **Fabric, Quilt, Forge, NeoForge** (oder Vanilla)
- Lädt Spiel, Bibliotheken, Assets **und die passende Java-Version** automatisch herunter
- **Microsoft-Login** (Sitzung wird beim Start automatisch erneuert)
- Ohne Account: offizieller **Demo-Modus**
- Einstellbar: Arbeitsspeicher (RAM), Fenstergröße, Spielordner, eigener Java-Pfad, JVM-Argumente
- Fortschrittsbalken und Live-Log der Spielausgabe

## Installation
Du brauchst **Python 3.10 oder neuer** (unter Windows bei der Installation „Add to PATH“ anhaken).

```bash
cd launcher
pip install -r requirements.txt
python launcher.py
```

Unter Linux muss eventuell noch tkinter installiert werden: `sudo apt install python3-tk`.

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
   <https://aka.ms/mce-reviewappid> (Formular ausfüllen, dauert ein paar Tage)

Danach auf **„Mit Microsoft anmelden“** klicken – der Browser öffnet sich, nach der
Anmeldung bist du im Launcher eingeloggt.

## Speicherorte
- Einstellungen & Login: `~/.ganovenstadt-launcher/`
- Spieldateien (Standard): `~/.ganovenstadt-launcher/minecraft/` – getrennt vom offiziellen Launcher.
