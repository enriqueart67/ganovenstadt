package de.peregrine.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import de.peregrine.client.CosmeticLayer;
import de.peregrine.client.CustomCapes;
import de.peregrine.client.Modules;
import de.peregrine.client.Store;
import de.peregrine.client.module.Module;
import de.peregrine.client.setting.ModeSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Wardrobe: pick capes, wings and headwear from tiles and see them on a large 3D preview of your own
 * character. Hovering a tile tries it on, clicking equips it, dragging the preview turns the character.
 */
public final class WardrobeScreen extends Screen {
    private enum Tab { CAPES, WINGS, HEADWEAR, PETS }

    private static Tab tab = Tab.CAPES;
    private final Screen parent;
    private final Theme t = Theme.load();
    private final long openedAt = System.currentTimeMillis();
    private float yaw = 200f, yawVel; // preview rotation (degrees) and spin
    private boolean dragging;
    private double lastDragX;
    private long lastInteraction;
    private double scroll;

    private record Hit(int x, int y, int w, int h, Runnable action) {
    }

    private final List<Hit> hits = new ArrayList<>();

    public WardrobeScreen(Screen parent) {
        super(Component.literal("Wardrobe"));
        this.parent = parent;
        Modules.CAPE.refresh();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float pt) {
        try {
            extractBlurredBackground(g);
        } catch (Throwable ignored) {
        }
        g.fill(0, 0, width, height, t.light ? 0x66000000 : 0x99000000);
    }

    private static String up(String s) {
        return s.toUpperCase(java.util.Locale.ROOT);
    }

    // ------------------------------------------------------------------ items of a tab
    private record Item(String name, String note) {
    }

    private static final String UPLOAD = "Your photo";
    private String message;
    private long messageAt;

    private List<Item> items() {
        List<Item> l = new ArrayList<>();
        l.add(new Item("None", ""));
        switch (tab) {
            case PETS -> {
                for (String s : Modules.PET.style.modes) l.add(new Item(s, CosmeticLayer.PETS.containsKey(s) && s.matches("Parrot|Bee|Allay") ? "FLIES" : "SHOULDER"));
            }
            case CAPES -> {
                l.add(new Item(UPLOAD, "UPLOAD OR DROP"));
                for (String d : Modules.CAPE.design.modes) {
                    l.add(new Item(d, CustomCapes.isCustom(d) ? "YOUR PHOTO" : ""));
                }
            }
            case WINGS -> {
                for (String s : Modules.WINGS.style.modes) l.add(new Item(s, ""));
            }
            case HEADWEAR -> {
                for (String s : Modules.HEADWEAR.style.modes) l.add(new Item(s, s.equals("Halo") ? "GLOWS" : ""));
            }
        }
        return l;
    }

    private Module module() {
        return switch (tab) {
            case CAPES -> Modules.CAPE;
            case WINGS -> Modules.WINGS;
            case HEADWEAR -> Modules.HEADWEAR;
            case PETS -> Modules.PET;
        };
    }

    private ModeSetting styleSetting() {
        return switch (tab) {
            case CAPES -> Modules.CAPE.design;
            case WINGS -> Modules.WINGS.style;
            case HEADWEAR -> Modules.HEADWEAR.style;
            case PETS -> Modules.PET.style;
        };
    }

    private String equipped() {
        return module().enabled ? styleSetting().get() : "None";
    }

    private void equip(String name) {
        if (name.equals(UPLOAD)) {
            pickPhoto();
            return;
        }
        Module m = module();
        if (name.equals("None")) {
            if (m.enabled) m.toggle();
        } else {
            styleSetting().set(name);
            if (!m.enabled) m.toggle();
        }
        Store.save();
    }

    private void preview(String name) {
        CosmeticLayer.previewCape = tab == Tab.CAPES ? name : null;
        CosmeticLayer.previewWings = tab == Tab.WINGS ? name : null;
        CosmeticLayer.previewHeadwear = tab == Tab.HEADWEAR ? name : null;
        CosmeticLayer.previewPet = tab == Tab.PETS ? name : null;
        if (UPLOAD.equals(name)) CosmeticLayer.previewCape = null;
    }

    // ------------------------------------------------------------------ photo capes from inside the game
    private void importPhoto(java.nio.file.Path file) {
        try {
            String design = CustomCapes.importPicture(file);
            Modules.CAPE.refresh();
            tab = Tab.CAPES;
            equip(design);
            say("Added " + design.substring("Photo: ".length()) + " – equipped");
        } catch (Exception e) {
            say("Could not use that picture: " + e.getMessage());
        }
    }

    /** The system's file picker (LWJGL tinyfd – bundled up to 26.2; newer versions: drop the picture instead). */
    private void pickPhoto() {
        try {
            Class<?> tinyfd = Class.forName("org.lwjgl.util.tinyfd.TinyFileDialogs");
            String path;
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                org.lwjgl.PointerBuffer filters = stack.mallocPointer(5);
                for (String f : new String[]{"*.png", "*.jpg", "*.jpeg", "*.bmp", "*.gif"}) filters.put(stack.UTF8(f));
                filters.flip();
                path = (String) tinyfd.getMethod("tinyfd_openFileDialog", CharSequence.class, CharSequence.class, org.lwjgl.PointerBuffer.class,
                        CharSequence.class, boolean.class).invoke(null, "Choose a picture for your cape", "", filters, "Pictures", false);
            }
            if (path != null && !path.isBlank()) importPhoto(java.nio.file.Path.of(path.split("\\|")[0]));
        } catch (Throwable e) {
            say("Drag a picture from your files onto the game window to make it a cape.");
        }
    }

    @Override
    public void onFilesDrop(java.util.List<java.nio.file.Path> files) {
        if (!files.isEmpty()) importPhoto(files.get(0));
    }

    private void say(String text) {
        message = text;
        messageAt = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ drawing
    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
        extractBackground(g, mx, my, pt);
        hits.clear();
        float a = Math.min(1f, (System.currentTimeMillis() - openedAt) / 160f);
        a = 1f - (1f - a) * (1f - a) * (1f - a);
        int W = Math.min(620, width - 16), H = Math.min(360, height - 16);
        int x0 = (width - W) / 2, y0 = (height - H) / 2 + Math.round((1f - a) * 10);
        g.fill(x0 + 3, y0 + 4, x0 + W + 3, y0 + H + 4, t.shadow);
        g.fill(x0, y0, x0 + W, y0 + H, t.bg | 0xFF000000);
        if (t.chart) Ui.chart(g, x0, y0, W, H, t.light);

        // ---------- top bar: back, title, tabs, close
        int bar = 24;
        boolean bh = Ui.in(mx, my, x0, y0, 70, bar);
        Type.left(g, "← MODS", Type.MONO, x0 + 14, y0 + 9, 0.8f, bh ? t.accent : t.muted);
        hits.add(new Hit(x0, y0, 70, bar, this::onClose));
        int nx = x0 + 90;
        for (Tab tb : Tab.values()) {
            String label = tb.name();
            int w = Type.width(label, Type.MONO, 0.8f);
            boolean sel = tb == tab, hov = Ui.in(mx, my, nx - 4, y0, w + 8, bar);
            Type.left(g, label, Type.MONO, nx, y0 + 9, 0.8f, sel || hov ? t.text : t.muted);
            if (sel) g.fill(nx, y0 + bar - 2, nx + w, y0 + bar, t.accent);
            hits.add(new Hit(nx - 4, y0, w + 8, bar, () -> {
                tab = tb;
                scroll = 0;
            }));
            nx += w + 14;
        }
        boolean xh = Ui.in(mx, my, x0 + W - 24, y0, 24, bar);
        Type.center(g, "×", Type.UI, x0 + W - 12, y0 + 6, 1.3f, xh ? t.accent : t.muted);
        hits.add(new Hit(x0 + W - 24, y0, 24, bar, this::closeAll));
        g.fill(x0, y0 + bar, x0 + W, y0 + bar + 1, t.line);

        // ---------- preview panel on the right
        int pw = Math.round(W * 0.42f), px = x0 + W - pw, py = y0 + bar + 1, ph = H - bar - 1;
        g.fill(px, py, px + pw, py + ph, t.card);
        g.fill(px, py, px + 1, py + ph, t.line);
        // floor shadow
        int fy = py + ph - 46;
        for (int i = 0; i < 6; i++) g.fill(px + pw / 2 - 30 + i * 3, fy + i, px + pw / 2 + 30 - i * 3, fy + i + 1, 0x18000000);

        // ---------- item grid on the left
        int gx = x0 + 14, gy = y0 + bar + 12, gw = W - pw - 28, gh = H - bar - 12 - 30;
        String title = switch (tab) {
            case CAPES -> "Capes";
            case WINGS -> "Wings";
            case HEADWEAR -> "Headwear";
            case PETS -> "Pets";
        };
        Type.left(g, title, Type.DISPLAY, gx, gy - 2, 2.0f, t.text);
        int top = gy + 26;
        List<Item> list = items();
        int cols = gw >= 260 ? 3 : 2, gap = 8;
        int tw = (gw - gap * (cols - 1)) / cols, th = 54;
        int rows = (list.size() + cols - 1) / cols;
        int maxScroll = Math.max(0, rows * (th + gap) - (gy + gh - top));
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        String equipped = equipped();
        String hovered = null;
        g.enableScissor(gx, top, gx + gw, gy + gh);
        for (int i = 0; i < list.size(); i++) {
            Item it = list.get(i);
            int x = gx + (i % cols) * (tw + gap), y = top + (i / cols) * (th + gap) - (int) scroll;
            if (y + th < top || y > gy + gh) continue;
            boolean vis = my >= top && my < gy + gh;
            boolean hov = vis && Ui.in(mx, my, x, y, tw, th);
            boolean on = it.name().equals(equipped);
            if (hov) hovered = it.name();
            g.fill(x, y, x + tw, y + th, hov ? t.cardHover : t.card);
            if (on) {
                g.fill(x, y, x + tw, y + 2, t.accent);
                g.fill(x, y + th - 2, x + tw, y + th, t.accent);
                g.fill(x, y, x + 2, y + th, t.accent);
                g.fill(x + tw - 2, y, x + tw, y + th, t.accent);
            }
            thumb(g, it.name(), x + 6, y + 6, 30, th - 12);
            Type.left(g, Type.fit(it.name(), Type.BOLD, 1f, tw - 44), Type.BOLD, x + 40, y + 12, 1f, t.text);
            String note = on ? "EQUIPPED" : it.note();
            Type.left(g, note, Type.MONO, x + 40, y + 26, 0.65f, on ? t.accent : t.muted);
            if (vis) hits.add(new Hit(x, Math.max(y, top), tw, Math.max(0, Math.min(y + th, gy + gh) - Math.max(y, top)), () -> equip(it.name())));
        }
        g.disableScissor();
        preview(hovered); // try on what the mouse is over

        // footer of the grid: options of the current tab
        int fy2 = y0 + H - 22;
        g.fill(x0, fy2 - 6, x0 + W - pw, fy2 - 5, t.line);
        if (tab == Tab.WINGS) {
            Type.left(g, "SIZE", Type.MONO, gx, fy2 + 2, 0.7f, t.muted);
            int sx = gx + 34;
            for (String s : Modules.WINGS.size.modes) {
                int w = Type.width(up(s), Type.MONO, 0.75f);
                boolean sel = Modules.WINGS.size.is(s), hov = Ui.in(mx, my, sx - 3, fy2 - 3, w + 6, 14);
                Type.left(g, up(s), Type.MONO, sx, fy2 + 2, 0.75f, sel ? t.accent : hov ? t.text : t.muted);
                if (sel) g.fill(sx, fy2 + 10, sx + w, fy2 + 11, t.accent);
                hits.add(new Hit(sx - 3, fy2 - 3, w + 6, 14, () -> {
                    Modules.WINGS.size.set(s);
                    Store.save();
                }));
                sx += w + 14;
            }
        } else if (tab == Tab.PETS) {
            Type.left(g, "SIDE", Type.MONO, gx, fy2 + 2, 0.7f, t.muted);
            int sx = gx + 34;
            for (String s : Modules.PET.side.modes) {
                int w = Type.width(up(s), Type.MONO, 0.75f);
                boolean sel = Modules.PET.side.is(s), hov = Ui.in(mx, my, sx - 3, fy2 - 3, w + 6, 14);
                Type.left(g, up(s), Type.MONO, sx, fy2 + 2, 0.75f, sel ? t.accent : hov ? t.text : t.muted);
                if (sel) g.fill(sx, fy2 + 10, sx + w, fy2 + 11, t.accent);
                hits.add(new Hit(sx - 3, fy2 - 3, w + 6, 14, () -> {
                    Modules.PET.side.set(s);
                    Store.save();
                }));
                sx += w + 14;
            }
        } else if (tab == Tab.CAPES) {
            Type.left(g, "DROP A PICTURE ON THIS WINDOW TO MAKE IT A CAPE", Type.MONO, gx, fy2 + 2, 0.65f, t.muted);
        } else {
            Type.left(g, "HOVER TO TRY ON  ·  CLICK TO EQUIP", Type.MONO, gx, fy2 + 2, 0.65f, t.muted);
        }

        if (message != null && System.currentTimeMillis() - messageAt < 4000) {
            int mw = Type.width(message, Type.UI, 0.9f) + 16;
            g.fill(x0 + 14, y0 + H - 50, x0 + 14 + mw, y0 + H - 34, t.text);
            Type.left(g, message, Type.UI, x0 + 22, y0 + H - 46, 0.9f, t.bg | 0xFF000000);
        }

        // ---------- the character
        long now = System.currentTimeMillis();
        if (!dragging) {
            yaw += yawVel;
            yawVel *= 0.92f;
            if (now - lastInteraction > 2500) yaw += 0.35f; // slow idle spin
        }
        Type.center(g, "DRAG TO TURN", Type.MONO, px + pw / 2f, py + ph - 16, 0.65f, t.muted);
        renderCharacter(g, px + 6, py + 8, px + pw - 6, py + ph - 26, Math.min(pw, ph) * 0.27f);
        hits.add(new Hit(px, py, pw, ph, () -> {
        }));
    }

    /** Small picture on each tile: the cape's back face or the wing texture; a mark for "None". */
    private void thumb(GuiGraphicsExtractor g, String name, int x, int y, int w, int h) {
        if (name.equals("None")) {
            g.fill(x, y, x + w, y + h, t.off);
            Type.center(g, "—", Type.UI, x + w / 2f, y + h / 2f - 5, 1.2f, t.muted);
            return;
        }
        if (name.equals(UPLOAD)) {
            g.fill(x, y, x + w, y + h, t.accent);
            Type.center(g, "+", Type.UI, x + w / 2f, y + h / 2f - 8, 2f, t.onAccent);
            return;
        }
        if (tab == Tab.PETS) {
            var def = CosmeticLayer.PETS.get(name);
            if (def == null) return;
            Identifier id = Identifier.withDefaultNamespace("textures/entity/" + def.texture() + ".png");
            g.fill(x, y, x + w, y + h, t.off);
            int sz = Math.min(w, h) - 4;
            g.blit(RenderPipelines.GUI_TEXTURED, id, x + (w - sz) / 2, y + (h - sz) / 2, 0, 0, sz, sz, 16, 16, 64, 64);
            return;
        }
        try {
            if (tab == Tab.CAPES) {
                Identifier id;
                int k;
                if (CustomCapes.isCustom(name)) {
                    id = CustomCapes.texture(name);
                    k = 16;
                } else {
                    id = Identifier.fromNamespaceAndPath("peregrine", "textures/cape/" + name.toLowerCase() + ".png");
                    k = 1;
                }
                if (id == null) return;
                int cw = Math.min(w, Math.round(h * 10f / 16f)), cx = x + (w - cw) / 2;
                g.blit(RenderPipelines.GUI_TEXTURED, id, cx, y, k, k, cw, h, 10 * k, 16 * k, 64 * k, 32 * k);
            } else if (tab == Tab.WINGS) {
                String style = name.toLowerCase();
                Identifier id = Identifier.fromNamespaceAndPath("peregrine", "textures/cosmetic/wings_" + style + ".png");
                // a swatch of the wing's main material
                int v = style.equals("butterfly") ? 1 : 24;
                g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 1, v, w, h, 26, 26, 128, 128);
            } else {
                Identifier id = Identifier.fromNamespaceAndPath("peregrine", "textures/cosmetic/hat_" + name.toLowerCase().replace(" ", "").replace("tophat", "tophat") + ".png");
                g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0, 0, w, h, 16, 16, name.equals("Halo") ? 64 : name.equals("Top hat") || name.equals("Crown") ? 128 : 64,
                        name.equals("Halo") || name.equals("Cat ears") ? 32 : 64);
            }
        } catch (Throwable ignored) {
            g.fill(x, y, x + w, y + h, t.off);
        }
    }

    /** The local player rendered large, turned by {@link #yaw} (like the inventory preview, but all the way round). */
    private void renderCharacter(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, float scale) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        try {
            @SuppressWarnings({"unchecked", "rawtypes"})
            EntityRenderState st = ((net.minecraft.client.renderer.entity.EntityRenderer) mc.getEntityRenderDispatcher().getRenderer(mc.player))
                    .createRenderState(mc.player, 1f);
            st.shadowPieces.clear();
            st.outlineColor = 0;
            if (st instanceof LivingEntityRenderState l) {
                l.bodyRot = yaw;
                l.yRot = 0;
                l.xRot = 0;
                l.boundingBoxWidth /= l.scale;
                l.boundingBoxHeight /= l.scale;
                l.scale = 1f;
            }
            Quaternionf rot = new Quaternionf().rotateZ((float) Math.PI);
            Quaternionf cam = new Quaternionf().rotateX((float) Math.toRadians(-8));
            rot.mul(cam);
            Vector3f offset = new Vector3f(0, st.boundingBoxHeight / 2f + 0.0625f, 0);
            g.entity(st, scale, offset, rot, cam, x1, y1, x2, y2);
        } catch (Throwable e) {
            Type.center(g, "PREVIEW UNAVAILABLE", Type.MONO, (x1 + x2) / 2f, (y1 + y2) / 2f, 0.7f, t.muted);
        }
    }

    // ------------------------------------------------------------------ input
    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        if (e.button() != InputConstants.MOUSE_BUTTON_LEFT) return super.mouseClicked(e, doubleClick);
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (Ui.in(e.x(), e.y(), h.x(), h.y(), h.w(), h.h())) {
                // the preview panel's hit is the last one added: start turning the character
                if (i == hits.size() - 1) {
                    dragging = true;
                    lastDragX = e.x();
                    lastInteraction = System.currentTimeMillis();
                    return true;
                }
                h.action().run();
                return true;
            }
        }
        return super.mouseClicked(e, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (dragging) {
            float d = (float) (e.x() - lastDragX);
            lastDragX = e.x();
            yaw -= d * 1.6f;
            yawVel = -d * 0.8f;
            lastInteraction = System.currentTimeMillis();
            return true;
        }
        return super.mouseDragged(e, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        dragging = false;
        return super.mouseReleased(e);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        scroll -= sy * 30;
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (e.key() == InputConstants.KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(e);
    }

    private void closeAll() {
        CosmeticLayer.previewCape = CosmeticLayer.previewWings = CosmeticLayer.previewHeadwear = null;
        Ui.open(null);
    }

    @Override
    public void onClose() {
        CosmeticLayer.previewCape = CosmeticLayer.previewWings = CosmeticLayer.previewHeadwear = null;
        Store.save();
        Ui.open(parent);
    }

    @Override
    public void removed() {
        CosmeticLayer.previewCape = CosmeticLayer.previewWings = CosmeticLayer.previewHeadwear = null;
        super.removed();
    }
}
