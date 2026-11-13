package de.peregrine.client;

import de.peregrine.client.module.Category;
import de.peregrine.client.module.HudModule;
import de.peregrine.client.module.Module;
import de.peregrine.client.module.TextHud;
import de.peregrine.client.mixin.OptionInstanceAccessor;
import de.peregrine.client.setting.BoolSetting;
import de.peregrine.client.setting.ColorSetting;
import de.peregrine.client.setting.ModeSetting;
import de.peregrine.client.setting.NumberSetting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/** Alle Module des Peregrine Client. */
public final class Modules {
    private Modules() {
    }

    // ================================================================== HUD
    public static final class Fps extends TextHud {
        Fps() {
            super("fps", "FPS", "Bilder pro Sekunde", true, 0.005, 0.01);
        }

        protected List<String[]> lines(Minecraft mc, boolean p) {
            return List.<String[]>of(new String[]{"FPS", String.valueOf(mc.getFps())});
        }
    }

    public static final class Cps extends TextHud {
        final ModeSetting mode = add(new ModeSetting("mode", "Anzeige", "Links | Rechts", "Links | Rechts", "Nur links"));

        Cps() {
            super("cps", "CPS", "Klicks pro Sekunde", true, 0.005, 0.08);
        }

        protected List<String[]> lines(Minecraft mc, boolean p) {
            String v = mode.is("Nur links") ? String.valueOf(Clicks.left()) : Clicks.left() + " | " + Clicks.right();
            return List.<String[]>of(new String[]{"CPS", v});
        }
    }

    public static final class Coords extends TextHud {
        final BoolSetting direction = add(new BoolSetting("direction", "Himmelsrichtung", true));
        final BoolSetting compact = add(new BoolSetting("compact", "Kompakt (eine Zeile)", false));

        Coords() {
            super("coords", "Koordinaten", "X, Y, Z und Blickrichtung", true, 0.005, 0.15);
        }

        protected List<String[]> lines(Minecraft mc, boolean p) {
            LocalPlayer pl = mc.player;
            if (pl == null) return List.of();
            List<String[]> l = new ArrayList<>();
            if (compact.on()) {
                l.add(new String[]{"XYZ", String.format("%.0f  %.0f  %.0f", pl.getX(), pl.getY(), pl.getZ())});
            } else {
                l.add(new String[]{"X", String.format("%.1f", pl.getX())});
                l.add(new String[]{"Y", String.format("%.1f", pl.getY())});
                l.add(new String[]{"Z", String.format("%.1f", pl.getZ())});
            }
            if (direction.on()) {
                String d = switch (pl.getDirection()) {
                    case NORTH -> "Norden (-Z)";
                    case SOUTH -> "Süden (+Z)";
                    case EAST -> "Osten (+X)";
                    case WEST -> "Westen (-X)";
                    default -> pl.getDirection().toString();
                };
                l.add(new String[]{"Richtung", d});
            }
            return l;
        }
    }

    public static final class Clock extends TextHud {
        final BoolSetting seconds = add(new BoolSetting("seconds", "Sekunden", false));

        Clock() {
            super("clock", "Uhrzeit", "Echte Uhrzeit", false, 0.005, 0.32);
        }

        protected List<String[]> lines(Minecraft mc, boolean p) {
            LocalTime t = LocalTime.now();
            String v = seconds.on() ? String.format("%02d:%02d:%02d", t.getHour(), t.getMinute(), t.getSecond()) : String.format("%02d:%02d", t.getHour(), t.getMinute());
            return List.<String[]>of(new String[]{"Uhrzeit", v});
        }
    }

    public static final class Ping extends TextHud {
        Ping() {
            super("ping", "Ping", "Verbindung zum Server in Millisekunden", false, 0.005, 0.38);
        }

        protected List<String[]> lines(Minecraft mc, boolean p) {
            int ping = 0;
            if (mc.player != null && mc.getConnection() != null) {
                PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
                if (info != null) ping = info.getLatency();
            }
            return List.<String[]>of(new String[]{"Ping", ping + " ms"});
        }
    }

    public static final class Server extends TextHud {
        Server() {
            super("server", "Server-Adresse", "Zeigt, auf welchem Server du bist", false, 0.005, 0.44);
        }

        protected List<String[]> lines(Minecraft mc, boolean p) {
            var s = mc.getCurrentServer();
            return List.<String[]>of(new String[]{"Server", s != null ? s.ip : "Einzelspieler"});
        }
    }

    public static final class Speed extends TextHud {
        Speed() {
            super("speed", "Geschwindigkeit", "Wie schnell du dich bewegst (Blöcke/s)", false, 0.005, 0.50);
        }

        protected List<String[]> lines(Minecraft mc, boolean p) {
            if (mc.player == null) return List.of();
            Vec3 v = mc.player.getDeltaMovement();
            double bps = Math.sqrt(v.x * v.x + v.z * v.z) * 20.0;
            return List.<String[]>of(new String[]{"Tempo", String.format("%.2f B/s", bps).replace('.', ',')});
        }
    }

    public static final class Days extends TextHud {
        Days() {
            super("days", "Tageszähler", "Wie viele Minecraft-Tage vergangen sind", false, 0.005, 0.56);
        }

        protected List<String[]> lines(Minecraft mc, boolean p) {
            if (mc.level == null) return List.of();
            return List.<String[]>of(new String[]{"Tag", String.valueOf(mc.level.getOverworldClockTime() / 24000L + 1)});
        }
    }

    public static final class Memory extends TextHud {
        Memory() {
            super("memory", "Arbeitsspeicher", "RAM-Verbrauch von Minecraft", false, 0.005, 0.62);
        }

        protected List<String[]> lines(Minecraft mc, boolean p) {
            Runtime r = Runtime.getRuntime();
            long used = (r.totalMemory() - r.freeMemory()) / 1048576, max = r.maxMemory() / 1048576;
            return List.<String[]>of(new String[]{"RAM", used + " / " + max + " MB (" + (used * 100 / Math.max(1, max)) + " %)"});
        }
    }

    public static final class Reach extends TextHud {
        static double last = -1;
        static long lastTime;

        Reach() {
            super("reach", "Reichweite", "Abstand deines letzten Treffers", false, 0.005, 0.68);
        }

        protected List<String[]> lines(Minecraft mc, boolean p) {
            boolean fresh = last >= 0 && System.currentTimeMillis() - lastTime < 3000;
            return List.<String[]>of(new String[]{"Reichweite", fresh ? String.format("%.2f m", last).replace('.', ',') : "–"});
        }
    }

    public static final class Combo extends TextHud {
        static int combo;
        static long lastHit;
        static int lastTarget;

        Combo() {
            super("combo", "Combo-Zähler", "Treffer in Folge, ohne selbst getroffen zu werden", false, 0.005, 0.74);
        }

        @Override
        public void tick(Minecraft mc) {
            if (mc.player != null && mc.player.hurtTime > 0) combo = 0;
            if (System.currentTimeMillis() - lastHit > 2500) combo = 0;
        }

        protected List<String[]> lines(Minecraft mc, boolean p) {
            return List.<String[]>of(new String[]{"Combo", combo + (combo == 1 ? " Treffer" : " Treffer")});
        }
    }

    public static final class Potions extends TextHud {
        Potions() {
            super("potions", "Trank-Effekte", "Aktive Effekte mit Restzeit", false, 0.80, 0.30);
            anchor = Anchor.RIGHT;
        }

        protected List<String[]> lines(Minecraft mc, boolean p) {
            List<String[]> l = new ArrayList<>();
            if (mc.player != null) {
                for (MobEffectInstance e : mc.player.getActiveEffects()) {
                    String name = e.getEffect().value().getDisplayName().getString();
                    if (e.getAmplifier() > 0) name += " " + roman(e.getAmplifier() + 1);
                    String time = e.isInfiniteDuration() ? "∞" : String.format("%d:%02d", e.getDuration() / 1200, (e.getDuration() / 20) % 60);
                    l.add(new String[]{name, time});
                }
            }
            if (l.isEmpty() && p) l.add(new String[]{"Schnelligkeit II", "1:30"});
            return l;
        }

        private static String roman(int n) {
            return switch (n) {
                case 2 -> "II";
                case 3 -> "III";
                case 4 -> "IV";
                case 5 -> "V";
                default -> String.valueOf(n);
            };
        }
    }

    public static final class Armor extends HudModule {
        final BoolSetting durability = add(new BoolSetting("durability", "Haltbarkeit anzeigen", true));
        final ModeSetting layout = add(new ModeSetting("layout", "Anordnung", "Untereinander", "Untereinander", "Nebeneinander"));
        private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND};

        Armor() {
            super("armor", "Rüstung", "Rüstung und Waffe mit Haltbarkeit", false, 0.90, 0.60);
            anchor = Anchor.RIGHT;
        }

        protected int[] draw(GuiGraphicsExtractor g, Minecraft mc, boolean preview) {
            if (mc.player == null) return new int[]{0, 0};
            Font f = mc.font;
            boolean vertical = layout.is("Untereinander");
            int x = 0, y = 0, w = 0, h = 0, shown = 0;
            for (EquipmentSlot slot : SLOTS) {
                ItemStack st = mc.player.getItemBySlot(slot);
                if (st.isEmpty()) continue;
                shown++;
                g.item(st, x, y);
                int cw = 16;
                if (durability.on() && st.isDamageableItem()) {
                    int left = st.getMaxDamage() - st.getDamageValue();
                    float frac = left / (float) st.getMaxDamage();
                    int col = frac > 0.5f ? 0xFF55FF55 : frac > 0.2f ? 0xFFFFAA00 : 0xFFFF5555;
                    String t = String.valueOf(left);
                    g.text(f, t, x + 18, y + 4, col, true);
                    cw = 18 + f.width(t);
                }
                if (vertical) {
                    w = Math.max(w, cw);
                    y += 17;
                    h = y;
                } else {
                    x += cw + 4;
                    w = x;
                    h = 16;
                }
            }
            if (shown == 0 && preview) {
                g.text(f, "Rüstung", 0, 4, 0xFFFFFFFF, true);
                return new int[]{f.width("Rüstung"), 16};
            }
            return new int[]{w, h};
        }
    }

    public static final class Keystrokes extends HudModule {
        final BoolSetting mouse = add(new BoolSetting("mouse", "Maustasten mit CPS", true));
        final BoolSetting space = add(new BoolSetting("space", "Leertaste", true));
        final ColorSetting pressed = add(new ColorSetting("pressed", "Farbe gedrückt", 0xFF4F8CFF));
        private static final int K = 20, GAP = 2;

        Keystrokes() {
            super("keystrokes", "Keystrokes", "Zeigt W A S D, Maustasten und Leertaste", true, 0.99, 0.62);
            anchor = Anchor.BOTTOM_RIGHT;
        }

        protected int[] draw(GuiGraphicsExtractor g, Minecraft mc, boolean preview) {
            Font f = mc.font;
            var o = mc.options;
            int w = K * 3 + GAP * 2, y = 0;
            key(g, f, o.keyUp, "W", K + GAP, y, K, K);
            y += K + GAP;
            key(g, f, o.keyLeft, "A", 0, y, K, K);
            key(g, f, o.keyDown, "S", K + GAP, y, K, K);
            key(g, f, o.keyRight, "D", (K + GAP) * 2, y, K, K);
            y += K + GAP;
            if (mouse.on()) {
                int mw = (w - GAP) / 2;
                key(g, f, o.keyAttack, "LMB " + Clicks.left(), 0, y, mw, K);
                key(g, f, o.keyUse, "RMB " + Clicks.right(), mw + GAP, y, w - mw - GAP, K);
                y += K + GAP;
            }
            if (space.on()) {
                key(g, f, o.keyJump, "———", 0, y, w, 10);
                y += 10 + GAP;
            }
            return new int[]{w, y - GAP};
        }

        private void key(GuiGraphicsExtractor g, Font f, KeyMapping km, String label, int x, int y, int w, int h) {
            var st = STYLE;
            boolean down = km.isDown();
            int bg = down ? (pressed.argb() & 0x00FFFFFF) | 0xD0000000 : (st.background.on() ? (int) (st.opacity.d() * 255) << 24 : 0);
            g.fill(x, y, x + w, y + h, bg);
            g.text(f, label, x + (w - f.width(label)) / 2, y + (h - f.lineHeight) / 2 + 1, down ? 0xFFFFFFFF : st.valueColor.argb(), st.shadow.on());
        }
    }

    // ================================================================== PvP
    public static final class Crosshair extends Module {
        public final ModeSetting style = add(new ModeSetting("style", "Form", "Kreuz", "Kreuz", "T", "Punkt", "Kreis"));
        public final NumberSetting size = add(new NumberSetting("size", "Größe", 5, 1, 15, 1, ""));
        public final NumberSetting gap = add(new NumberSetting("gap", "Abstand", 2, 0, 10, 1, ""));
        public final NumberSetting thickness = add(new NumberSetting("thickness", "Dicke", 1, 1, 4, 1, ""));
        public final ColorSetting color = add(new ColorSetting("color", "Farbe", 0xFFFFFFFF));
        public final BoolSetting outline = add(new BoolSetting("outline", "Umrandung", true));
        public final BoolSetting dot = add(new BoolSetting("dot", "Punkt in der Mitte", false));

        Crosshair() {
            super("crosshair", "Fadenkreuz", "Eigenes Fadenkreuz: Form, Größe, Farbe", Category.PVP, true);
        }

        public void draw(GuiGraphicsExtractor g) {
            int cx = g.guiWidth() / 2, cy = g.guiHeight() / 2;
            int t = thickness.i(), s = size.i(), gp = gap.i(), lo = t / 2, hi = t - lo;
            List<int[]> r = new ArrayList<>();
            switch (style.get()) {
                case "Punkt" -> r.add(new int[]{cx - lo - s / 2, cy - lo - s / 2, cx + hi + s / 2, cy + hi + s / 2});
                case "Kreis" -> {
                    int rad = gp + s;
                    for (int a = 0; a < 360; a += 4) {
                        double rr = Math.toRadians(a);
                        int px = cx + (int) Math.round(Math.cos(rr) * rad), py = cy + (int) Math.round(Math.sin(rr) * rad);
                        r.add(new int[]{px - lo, py - lo, px + hi, py + hi});
                    }
                }
                default -> {
                    if (!style.is("T")) r.add(new int[]{cx - lo, cy - gp - s, cx + hi, cy - gp});
                    r.add(new int[]{cx - lo, cy + gp + (hi - lo), cx + hi, cy + gp + s + (hi - lo)});
                    r.add(new int[]{cx - gp - s, cy - lo, cx - gp, cy + hi});
                    r.add(new int[]{cx + gp + (hi - lo), cy - lo, cx + gp + s + (hi - lo), cy + hi});
                }
            }
            if (dot.on() && !style.is("Punkt")) r.add(new int[]{cx - lo, cy - lo, cx + hi, cy + hi});
            if (outline.on()) for (int[] q : r) g.fill(q[0] - 1, q[1] - 1, q[2] + 1, q[3] + 1, 0xC0000000);
            for (int[] q : r) g.fill(q[0], q[1], q[2], q[3], color.argb());
        }
    }

    public static final class NoHurtCam extends Module {
        NoHurtCam() {
            super("nohurtcam", "Kein Schadens-Wackeln", "Kamera wackelt nicht, wenn du Schaden bekommst", Category.PVP, false);
        }
    }

    // ================================================================== Grafik
    public static final class Zoom extends Module {
        public final NumberSetting factor = add(new NumberSetting("factor", "Stärke", 4, 2, 10, 0.5, "×"));
        public final BoolSetting smooth = add(new BoolSetting("smooth", "Weich zoomen", true));

        Zoom() {
            super("zoom", "Zoom", "Taste C gedrückt halten zum Heranzoomen", Category.GRAPHICS, true);
        }
    }

    public static final class Fullbright extends Module {
        private Double saved;

        Fullbright() {
            super("fullbright", "Fullbright", "Alles hell – auch in Höhlen und nachts (Taste G)", Category.GRAPHICS, false);
        }

        @Override
        public void tick(Minecraft mc) {
            var gamma = mc.options.gamma();
            OptionInstanceAccessor acc = (OptionInstanceAccessor) (Object) gamma;
            if (enabled) {
                if (saved == null) saved = gamma.get();
                if (gamma.get() < 15.0) acc.peregrine$setValue(15.0);
            } else if (saved != null) {
                acc.peregrine$setValue(saved);
                saved = null;
            }
        }
    }

    public static final class NoWeather extends Module {
        NoWeather() {
            super("noweather", "Kein Regen", "Regen und Gewitter nur bei dir ausblenden", Category.GRAPHICS, false);
        }
    }

    // ================================================================== Bewegung
    public static final class ToggleSprint extends Module {
        ToggleSprint() {
            super("togglesprint", "Auto-Sprint", "Automatisch sprinten beim Vorwärtslaufen (Taste V)", Category.MOVEMENT, false);
        }

        @Override
        public void tick(Minecraft mc) {
            if (enabled && mc.player != null && mc.options.keyUp.isDown() && !mc.player.isShiftKeyDown()) {
                mc.options.keySprint.setDown(true);
            }
        }
    }

    public static final class ToggleSneak extends Module {
        private boolean sneaking;

        ToggleSneak() {
            super("togglesneak", "Schleichen umschalten", "Schleichtaste einmal drücken statt halten", Category.MOVEMENT, false);
        }

        @Override
        public void tick(Minecraft mc) {
            boolean pressed = false;
            while (mc.options.keyShift.consumeClick()) pressed = true;
            if (!enabled) {
                if (sneaking) mc.options.keyShift.setDown(false);
                sneaking = false;
                return;
            }
            if (pressed) {
                sneaking = !sneaking;
                if (!sneaking) mc.options.keyShift.setDown(false);
            }
            if (sneaking && mc.mouseHandler.isMouseGrabbed()) mc.options.keyShift.setDown(true);
        }
    }

    // ================================================================== Neue HUD-Anzeigen
    private static int countItems(Minecraft mc, net.minecraft.world.item.Item... items) {
        if (mc.player == null) return 0;
        var inv = mc.player.getInventory();
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            for (var it : items) if (st.is(it)) n += st.getCount();
        }
        return n;
    }

    public static final class Saturation extends TextHud {
        Saturation() {
            super("saturation", "Sättigung", "Versteckte Sättigung deines Hungers", false, 0.005, 0.86);
        }

        protected List<String[]> lines(Minecraft mc, boolean p) {
            if (mc.player == null) return List.of();
            float s = mc.player.getFoodData().getSaturationLevel();
            return List.<String[]>of(new String[]{"Sättigung", String.format("%.1f", s).replace('.', ',')});
        }
    }

    public static final class Arrows extends TextHud {
        Arrows() {
            super("arrows", "Pfeil-Zähler", "Wie viele Pfeile du dabei hast", false, 0.90, 0.80);
        }

        protected List<String[]> lines(Minecraft mc, boolean p) {
            int n = countItems(mc, net.minecraft.world.item.Items.ARROW, net.minecraft.world.item.Items.SPECTRAL_ARROW, net.minecraft.world.item.Items.TIPPED_ARROW);
            return List.<String[]>of(new String[]{"Pfeile", String.valueOf(n)});
        }
    }

    public static final class Totems extends TextHud {
        Totems() {
            super("totems", "Totem-Zähler", "Wie viele Totems der Unsterblichkeit du hast", false, 0.90, 0.86);
        }

        protected List<String[]> lines(Minecraft mc, boolean p) {
            int n = countItems(mc, net.minecraft.world.item.Items.TOTEM_OF_UNDYING);
            return List.<String[]>of(new String[]{"Totems", String.valueOf(n)});
        }
    }

    public static final class Biome extends TextHud {
        Biome() {
            super("biome", "Biom", "In welchem Biom du gerade bist", false, 0.005, 0.92);
        }

        protected List<String[]> lines(Minecraft mc, boolean p) {
            if (mc.player == null || mc.level == null) return List.of();
            String id = mc.level.getBiome(mc.player.blockPosition()).getRegisteredName();
            String name = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
            name = name.replace('_', ' ');
            if (!name.isEmpty()) name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
            return List.<String[]>of(new String[]{"Biom", name});
        }
    }

    public static final class Session extends TextHud {
        private long start;

        Session() {
            super("session", "Spielzeit", "Wie lange du schon in dieser Welt/auf diesem Server bist", false, 0.005, 0.98);
        }

        @Override
        public void tick(Minecraft mc) {
            if (mc.level == null) start = 0;
            else if (start == 0) start = System.currentTimeMillis();
        }

        protected List<String[]> lines(Minecraft mc, boolean p) {
            long s = start == 0 ? 0 : (System.currentTimeMillis() - start) / 1000;
            return List.<String[]>of(new String[]{"Zeit", String.format("%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)});
        }
    }

    public static final class Packs extends TextHud {
        Packs() {
            super("packs", "Ressourcenpakete", "Zeigt, welche Ressourcenpakete aktiv sind", false, 0.80, 0.02);
        }

        protected List<String[]> lines(Minecraft mc, boolean p) {
            List<String[]> l = new ArrayList<>();
            for (var pack : mc.getResourcePackRepository().getSelectedPacks()) {
                String id = pack.getId();
                // nur echte Ressourcenpakete (Ordner resourcepacks) und die eingebauten Zusatzpakete – keine Mods
                if (!(id.startsWith("file/") || id.equals("programmer_art") || id.equals("high_contrast"))) continue;
                l.add(new String[]{"", pack.getTitle().getString()});
            }
            if (l.isEmpty()) l.add(new String[]{"Pakete", "keine"});
            return l;
        }
    }

    // ================================================================== Neue Funktionen
    public static final class Freelook extends Module {
        public final BoolSetting thirdPerson = add(new BoolSetting("third", "Dabei in die Ansicht von hinten wechseln", true));
        public final BoolSetting invert = add(new BoolSetting("invert", "Maus umkehren (hoch/runter)", false));

        Freelook() {
            super("freelook", "Freelook", "Umsehen, ohne dich zu drehen – Taste (Links-Alt) halten", Category.PVP, true);
        }
    }

    public static final class ChatTime extends Module {
        public final ModeSetting format = add(new ModeSetting("format", "Format", "HH:mm", "HH:mm", "HH:mm:ss"));

        ChatTime() {
            super("chattime", "Chat-Zeitstempel", "Uhrzeit vor jeder Chat-Nachricht", Category.OTHER, false);
        }
    }

    public static final class AutoGG extends Module {
        public final ModeSetting text = add(new ModeSetting("text", "Nachricht", "gg", "gg", "GG", "gg wp", "Good Game"));
        private static final String[] TRIGGERS = {"won the game", "winner", "victory", "hat gewonnen", "gewinner", "1st killer", "#1", "you won", "du hast gewonnen", "game over"};
        private long last;

        AutoGG() {
            super("autogg", "Auto-GG", "Schreibt „gg“ in den Chat, wenn eine Runde vorbei ist", Category.OTHER, false);
        }

        public void onMessage(String msg) {
            if (!enabled) return;
            String m = msg.toLowerCase();
            boolean hit = false;
            for (String t : TRIGGERS) if (m.contains(t)) hit = true;
            if (!hit || System.currentTimeMillis() - last < 15000) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.getConnection() == null || mc.getCurrentServer() == null) return; // nur auf Servern
            last = System.currentTimeMillis();
            mc.getConnection().sendChat(text.get());
        }
    }

    // ================================================================== Cosmetics
    public static final class Cape extends Module {
        public static final String[] DESIGNS = {"Falke", "Nacht", "Papier", "Flamme", "Ozean", "Galaxie", "Regenbogen", "Kristall"};
        public final ModeSetting design = add(new ModeSetting("design", "Design", "Falke", CustomCapes.allDesigns()));
        public final BoolSetting elytra = add(new BoolSetting("elytra", "Auch auf der Elytra", true));

        Cape() {
            super("cape", "Umhang", "Eigener Umhang oder eigenes Foto (im Launcher hochladen) – F5 zum Ansehen", Category.COSMETICS, true);
        }

        /** Neu im Launcher hochgeladene Bilder in die Auswahl übernehmen. */
        public void refresh() {
            design.setModes(CustomCapes.allDesigns());
        }
    }

    public static final class EmoteModule extends Module {
        public final BoolSetting camera = add(new BoolSetting("camera", "Kamera nach vorne drehen", true));
        public final BoolSetting stopOnMove = add(new BoolSetting("stop", "Beim Laufen abbrechen", true));

        EmoteModule() {
            super("emotes", "Emotes", "Animationen für deine Spielfigur – Rad mit Taste B", Category.COSMETICS, true);
        }

        @Override
        public void tick(Minecraft mc) {
            Emotes.tick(mc);
        }
    }

    // ================================================================== Sonstiges
    public static final class Style extends Module {
        public final ColorSetting labelColor = add(new ColorSetting("label", "Farbe Beschriftung", 0xFF4F8CFF));
        public final ColorSetting valueColor = add(new ColorSetting("value", "Farbe Werte", 0xFFFFFFFF));
        public final BoolSetting background = add(new BoolSetting("background", "Hintergrund", true));
        public final NumberSetting opacity = add(new NumberSetting("opacity", "Deckkraft Hintergrund", 0.55, 0.1, 1.0, 0.05, ""));
        public final BoolSetting shadow = add(new BoolSetting("shadow", "Textschatten", true));

        Style() {
            super("style", "HUD-Stil", "Farben und Hintergrund aller Anzeigen", Category.OTHER, true);
        }
    }

    // ================================================================== Liste
    public static final Fps FPS = new Fps();
    public static final Cps CPS = new Cps();
    public static final Coords COORDS = new Coords();
    public static final Keystrokes KEYSTROKES = new Keystrokes();
    public static final Armor ARMOR = new Armor();
    public static final Potions POTIONS = new Potions();
    public static final Clock CLOCK = new Clock();
    public static final Ping PING = new Ping();
    public static final Server SERVER = new Server();
    public static final Speed SPEED = new Speed();
    public static final Days DAYS = new Days();
    public static final Memory MEMORY = new Memory();
    public static final Reach REACH = new Reach();
    public static final Combo COMBO = new Combo();
    public static final Crosshair CROSSHAIR = new Crosshair();
    public static final NoHurtCam NO_HURT_CAM = new NoHurtCam();
    public static final Zoom ZOOM = new Zoom();
    public static final Fullbright FULLBRIGHT = new Fullbright();
    public static final NoWeather NO_WEATHER = new NoWeather();
    public static final ToggleSprint TOGGLE_SPRINT = new ToggleSprint();
    public static final ToggleSneak TOGGLE_SNEAK = new ToggleSneak();
    public static final Saturation SATURATION = new Saturation();
    public static final Arrows ARROWS = new Arrows();
    public static final Totems TOTEMS = new Totems();
    public static final Biome BIOME = new Biome();
    public static final Session SESSION = new Session();
    public static final Packs PACKS = new Packs();
    public static final Freelook FREELOOK = new Freelook();
    public static final ChatTime CHAT_TIME = new ChatTime();
    public static final AutoGG AUTO_GG = new AutoGG();
    public static final Cape CAPE = new Cape();
    public static final EmoteModule EMOTES = new EmoteModule();
    public static final Style STYLE = new Style();

    public static final List<Module> ALL = List.of(
            FPS, CPS, COORDS, KEYSTROKES, ARMOR, POTIONS, CLOCK, PING, SERVER, SPEED, DAYS, MEMORY, REACH, COMBO,
            SATURATION, ARROWS, TOTEMS, BIOME, SESSION, PACKS, FREELOOK, CHAT_TIME, AUTO_GG,
            CROSSHAIR, NO_HURT_CAM, ZOOM, FULLBRIGHT, NO_WEATHER, TOGGLE_SPRINT, TOGGLE_SNEAK, CAPE, EMOTES, STYLE);

    public static List<HudModule> huds() {
        List<HudModule> l = new ArrayList<>();
        for (Module m : ALL) if (m instanceof HudModule h) l.add(h);
        return l;
    }
}
