package de.peregrine.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.realmsclient.RealmsMainScreen;
import de.peregrine.client.Modules;
import de.peregrine.client.PeregrineClient;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.SafetyScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Peregrine's main menu: a sheet of paper on the left carrying the wordmark and a text menu, the
 * vanilla panorama on the right. Replaces the vanilla title screen (module "Main menu").
 */
public final class TitleMenu extends Screen {
    /** Set when this screen failed or the player asked for the vanilla menu – then vanilla is left alone. */
    public static boolean bypass;

    private final Theme t = Theme.load();
    private final long openedAt = System.currentTimeMillis();

    private record Item(String label, String note, boolean enabled, Runnable action) {
    }

    private record Hit(int x, int y, int w, int h, Runnable action) {
    }

    private final List<Hit> hits = new ArrayList<>();

    public TitleMenu() {
        super(Component.literal("Peregrine Client"));
    }

    /** Called for every freshly initialised screen: swap the vanilla title screen for ours. */
    public static void onScreenInit(net.minecraft.client.Minecraft mc, Screen screen) {
        if (bypass || !Modules.MAIN_MENU.enabled || !(screen instanceof TitleScreen)) return;
        mc.execute(() -> {
            if (!bypass && Modules.MAIN_MENU.enabled) Ui.open(new TitleMenu());
        });
    }

    private List<Item> items() {
        var mc = minecraft;
        List<Item> l = new ArrayList<>();
        if (mc.isDemo()) {
            l.add(new Item("Play demo world", "", true, () -> {
                if (java.nio.file.Files.exists(mc.gameDirectory.toPath().resolve("saves/Demo_World/level.dat"))) mc.createWorldOpenFlows().openWorld("Demo_World", () -> Ui.open(this));
                else { // first demo start: create the world the way vanilla does
                    try {
                        mc.createWorldOpenFlows().createFreshLevel(net.minecraft.server.MinecraftServer.DEMO_SETTINGS.levelName(), net.minecraft.server.MinecraftServer.DEMO_SETTINGS,
                                net.minecraft.world.level.levelgen.WorldOptions.DEMO_OPTIONS, net.minecraft.world.level.levelgen.presets.WorldPresets::createNormalWorldDimensions, this);
                    } catch (Throwable e) {
                        vanilla();
                    }
                }
            }));
            l.add(new Item("Multiplayer", "NOT IN THE DEMO", false, () -> {
            }));
        } else {
            l.add(new Item("Singleplayer", "", true, () -> Ui.open(new SelectWorldScreen(this))));
            l.add(new Item("Multiplayer", "", true,
                    () -> Ui.open(mc.options.skipMultiplayerWarning ? new JoinMultiplayerScreen(this) : new SafetyScreen(this))));
            l.add(new Item("Realms", "", true, () -> Ui.open(new RealmsMainScreen(this))));
        }
        l.add(new Item("Mods", "RIGHT SHIFT IN GAME", true, () -> Ui.open(new ClickGui())));
        l.add(new Item("Options", "", true, () -> Ui.open(optionsScreen())));
        l.add(new Item("Quit", "", true, mc::stop));
        return l;
    }

    /** The options screen's constructor differs between versions – fill its parameters by type. */
    private Screen optionsScreen() {
        try {
            for (var ctor : OptionsScreen.class.getConstructors()) {
                Class<?>[] types = ctor.getParameterTypes();
                Object[] args = new Object[types.length];
                boolean ok = true;
                for (int i = 0; i < types.length; i++) {
                    if (types[i].isAssignableFrom(TitleMenu.class) || types[i] == Screen.class) args[i] = this;
                    else if (types[i].isInstance(minecraft.options)) args[i] = minecraft.options;
                    else if (types[i] == boolean.class) args[i] = false;
                    else ok = false;
                }
                if (ok) return (Screen) ctor.newInstance(args);
            }
        } catch (ReflectiveOperationException ignored) {
        }
        throw new IllegalStateException("no usable OptionsScreen constructor");
    }

    private void vanilla() {
        bypass = true;
        Ui.open(new TitleScreen());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    private static int fade(int argb, float f) {
        return ((int) (((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, f))) << 24) | (argb & 0xFFFFFF);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float pt) {
        extractPanorama(g, pt);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
        try {
            draw(g, mx, my, pt);
        } catch (Throwable e) {
            PeregrineClient.LOG.warn("Main menu failed, falling back to the vanilla one: {}", e.toString());
            vanilla();
        }
    }

    private void draw(GuiGraphicsExtractor g, int mx, int my, float pt) {
        extractBackground(g, mx, my, pt);
        hits.clear();
        float a = Math.min(1f, (System.currentTimeMillis() - openedAt) / 260f);
        a = 1f - (1f - a) * (1f - a) * (1f - a);
        int paper = 0xFF000000 | (t.bg & 0xFFFFFF);
        int pw = Math.max(176, Math.min(260, Math.round(width * 0.38f)));
        int px = Math.round((a - 1f) * 24);
        g.fill(px, 0, px + pw, height, paper);
        g.fill(px + pw, 0, px + pw + 1, height, t.line);
        // soft edge towards the panorama
        for (int i = 0; i < 10; i++) g.fill(px + pw + 1 + i, 0, px + pw + 2 + i, height, fade(0x38000000, (10 - i) / 10f));

        int x = px + 18, right = px + pw - 18;
        // wordmark
        Type.left(g, "PEREGRINE", Type.MONO, x, 16, 0.8f, t.text);
        Type.left(g, "CLIENT", Type.MONO, x + Type.width("PEREGRINE ", Type.MONO, 0.8f), 16, 0.8f, t.muted);
        g.fill(x, 28, right, 29, t.line);
        float big = Math.min(3.4f, (pw - 36) / (float) Type.width("Peregrine", Type.DISPLAY, 1f));
        int ty = Math.max(40, height / 2 - 96);
        Type.left(g, "Peregrine", Type.DISPLAY, x - 1, ty, big, t.text);
        g.fill(x + Type.width("Peregrine", Type.DISPLAY, big) + 3, ty + Math.round(2.5f * big), x + Type.width("Peregrine", Type.DISPLAY, big) + 3 + Math.round(2.2f * big),
                ty + Math.round(4.7f * big), t.accent);
        String ver = "MINECRAFT " + SharedConstants.getCurrentVersion().name().toUpperCase() + (minecraft.isDemo() ? "  ·  DEMO" : "");
        Type.left(g, ver, Type.MONO, x, ty + Math.round(10.5f * big) + 2, 0.75f, t.muted);

        // text menu
        List<Item> items = items();
        int rowH = Math.max(17, Math.min(24, (height - (ty + Math.round(10.5f * big) + 22) - 34) / items.size()));
        int my0 = ty + Math.round(10.5f * big) + 22;
        g.fill(x, my0 - 1, right, my0, t.line);
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            int y = my0 + i * rowH;
            boolean hov = it.enabled() && Ui.in(mx, my, px, y, pw, rowH);
            float ts = rowH >= 22 ? 1.5f : 1.25f;
            int ink = !it.enabled() ? t.muted2 : hov ? t.accent : t.text;
            Type.left(g, it.label(), Type.BOLD, x + (hov ? 4 : 0), y + (rowH - 9 * ts) / 2f + 1, ts, ink);
            if (hov) Type.right(g, "→", Type.UI, right, y + (rowH - 13) / 2f, 1.4f, t.accent);
            else if (!it.note().isEmpty()) Type.right(g, it.note(), Type.MONO, right, y + (rowH - 6) / 2f, 0.65f, t.muted2);
            g.fill(x, y + rowH - 1, right, y + rowH, t.line);
            if (it.enabled()) hits.add(new Hit(px, y, pw, rowH, it.action()));
        }

        // footer
        String user = minecraft.getUser().getName();
        Type.left(g, "PLAYING AS", Type.MONO, x, height - 26, 0.65f, t.muted);
        Type.left(g, user, Type.BOLD, x, height - 18, 1f, t.text);
        boolean vh = Ui.in(mx, my, right - 70, height - 22, 70, 14);
        Type.right(g, "VANILLA MENU", Type.MONO, right, height - 17, 0.65f, vh ? t.accent : t.muted);
        hits.add(new Hit(right - 70, height - 22, 70, 14, this::vanilla));
        String legal = Type.fit("NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.", Type.MONO, 0.55f, width - (px + pw) - 24);
        Type.right(g, legal, Type.MONO, width - 8, height - 10, 0.55f, 0xB0FFFFFF);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        if (e.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = hits.get(i);
                if (Ui.in(e.x(), e.y(), h.x(), h.y(), h.w(), h.h())) {
                    try {
                        h.action().run();
                    } catch (Throwable ex) {
                        PeregrineClient.LOG.warn("Main menu action failed: {}", ex.toString());
                        vanilla();
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(e, doubleClick);
    }
}
