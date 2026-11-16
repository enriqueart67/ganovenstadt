package de.peregrine.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import de.peregrine.client.bot.Bot;
import de.peregrine.client.bot.Commands;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Your own assistant commands: a list on the left, name and steps on the right.
 * Steps use the same words as the chat ("get 16 wood, then wait 10"); &lt;n&gt; in the name becomes a value you pass.
 */
public final class CommandsScreen extends Screen {
    private final Screen parent;
    private final Theme t = Theme.load();
    private String name = "", steps = "", note = "", original;
    private boolean noteBad;
    private int field = 0; // 0 name, 1 steps
    private final List<Object[]> hits = new ArrayList<>(); // {x, y, w, h, action}
    private int scroll;

    private static final String[][] EXAMPLES = {
            {"starter", "get 16 wood, then a stone pickaxe, then 16 torches"},
            {"wood <n>", "get <n> wood, then come back"},
            {"guard", "forever: kill zombie, then pick up items, then wait 5"},
            {"duel <name>", "say good luck, then fight <name>"},
            {"morning", "chat /time set day, then eat, then drop junk"},
    };

    public CommandsScreen(Screen parent) {
        super(Component.literal("My commands"));
        this.parent = parent;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        Ui.open(parent);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, t.light ? 0x55000000 : 0x88000000);
    }

    private void button(GuiGraphicsExtractor g, int mx, int my, int x, int y, String label, boolean primary, Runnable action) {
        int w = Type.width(label, Type.MONO, 0.7f) + 16, h = 15;
        boolean hov = Ui.in(mx, my, x, y, w, h);
        if (primary) Px.roundRect(g, x, y, w, h, 3, hov ? t.accentHover : t.accent);
        else {
            Px.roundRect(g, x, y, w, h, 3, hov ? t.cardHover : t.card);
            Px.roundStroke(g, x, y, w, h, 3, 0x30000000 | (t.text & 0xFFFFFF));
        }
        Type.left(g, label, Type.MONO, x + 8, y + 4.5f, 0.7f, primary ? t.onAccent : t.text);
        hits.add(new Object[]{x, y, w, h, action});
    }

    private int buttonWidth(String label) {
        return Type.width(label, Type.MONO, 0.7f) + 16;
    }

    private static List<String> wrap(String s, float scale, int max) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String w : s.split(" ", -1)) {
            String next = line.isEmpty() ? w : line + " " + w;
            if (Type.width(next, Type.UI, scale) > max && !line.isEmpty()) {
                out.add(line.toString());
                line = new StringBuilder(w);
            } else line = new StringBuilder(next);
        }
        out.add(line.toString());
        return out;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
        extractBackground(g, mx, my, pt);
        hits.clear();
        int W = Math.min(460, width - 16), H = Math.min(300, height - 16);
        int x0 = (width - W) / 2, y0 = (height - H) / 2;
        for (int si = 3; si >= 1; si--) Px.roundRect(g, x0 - si * 2, y0 - si * 2 + 4, W + si * 4, H + si * 4, 8 + si * 2, 0x22000000);
        Px.roundRect(g, x0, y0, W, H, 7, 0xFF000000 | (t.bg & 0xFFFFFF));
        Px.roundStroke(g, x0, y0, W, H, 7, 0x1C000000 | (t.text & 0xFFFFFF));
        int cx = x0 + 14;
        Type.left(g, "My commands", Type.DISPLAY, cx, y0 + 10, 2.1f, t.text);
        Type.right(g, "ASSISTANT", Type.MONO, x0 + W - 14, y0 + 14, 0.7f, t.muted);

        // ---- list
        int listW = 120, ly = y0 + 40, lh = H - 74;
        Px.roundRect(g, cx, ly, listW, lh, 4, t.card);
        Map<String, String> all = Commands.routines();
        int ry = ly + 5 - scroll;
        g.enableScissor(cx, ly, cx + listW, ly + lh);
        if (all.isEmpty()) {
            for (String l : wrap("Nothing saved yet. Write one on the right, or pick an example below.", 0.75f, listW - 12)) {
                Type.left(g, l, Type.UI, cx + 6, ry, 0.75f, t.muted);
                ry += 9;
            }
        }
        for (var e : all.entrySet()) {
            String k = e.getKey();
            boolean sel = k.equals(original), hov = Ui.in(mx, my, cx, ry - 2, listW, 13) && my >= ly && my < ly + lh;
            if (sel || hov) Px.roundRect(g, cx + 3, ry - 2, listW - 6, 13, 3, sel ? t.accentSoft : t.cardHover);
            if (sel) Px.roundRect(g, cx + 3, ry - 2, 2, 13, 1, t.accent);
            Type.left(g, Type.fit(k, Type.UI, 0.8f, listW - 16), Type.UI, cx + 9, ry + 1, 0.8f, sel ? t.text : t.muted);
            if (my >= ly && my < ly + lh) hits.add(new Object[]{cx, ry - 2, listW, 13, (Runnable) () -> select(k)});
            ry += 14;
        }
        g.disableScissor();
        button(g, mx, my, cx, ly + lh + 6, "+ NEW", false, this::clear);

        // ---- editor
        int ex = cx + listW + 12, ew = x0 + W - 14 - ex;
        Type.spaced(g, "NAME", Type.MONO, ex, ly, 0.62f, t.muted, 1.2f);
        int ny = ly + 9;
        field(g, ex, ny, ew, 18, name, "e.g. starter   or   wood <n>", field == 0, mx, my, 0);
        Type.spaced(g, "STEPS", Type.MONO, ex, ny + 26, 0.62f, t.muted, 1.2f);
        int sy = ny + 35, sh = 64;
        boolean hs = Ui.in(mx, my, ex, sy, ew, sh);
        Px.roundRect(g, ex, sy, ew, sh, 3, field == 1 ? t.cardHover : t.card);
        if (field == 1) Px.roundRect(g, ex, sy + sh - 1, ew, 1, 0, t.accent);
        boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
        String shown = steps + (field == 1 && blink ? "|" : "");
        if (steps.isEmpty() && field != 1) shown = "get 16 wood, then a stone pickaxe, then come back";
        List<String> lines = wrap(shown, 0.85f, ew - 12);
        int start = Math.max(0, lines.size() - 6);
        for (int i = start; i < lines.size(); i++)
            Type.left(g, lines.get(i), Type.UI, ex + 6, sy + 5 + (i - start) * 10, 0.85f, steps.isEmpty() && field != 1 ? t.muted : t.text);
        hits.add(new Object[]{ex, sy, ew, sh, (Runnable) () -> field = 1});
        if (hs) { /* hover only */ }

        // hint + validation
        int hy = sy + sh + 5;
        String hint = "Words like in the chat. Join steps with \"then\". \"repeat 3 times: …\", \"forever: …\". <n> in the name = a value you give: \"wood 32\".";
        List<String> msg = wrap(note.isEmpty() ? hint : note, 0.72f, ew); // a message replaces the hint
        for (int i = 0; i < Math.min(2, msg.size()); i++) {
            String l = msg.get(i) + (i == 1 && msg.size() > 2 ? " …" : "");
            Type.left(g, l, Type.UI, ex, hy, 0.72f, note.isEmpty() ? t.muted : noteBad ? 0xFFF05A5A : t.accent);
            hy += 8;
        }

        // examples
        int exy = y0 + H - 50;
        Type.spaced(g, "EXAMPLES", Type.MONO, ex, exy, 0.6f, t.muted, 1.2f);
        int chx = ex, chy = exy + 9;
        for (String[] e : EXAMPLES) {
            String s = e[0].toUpperCase();
            int w = Type.width(s, Type.MONO, 0.62f) + 10;
            if (chx + w > ex + ew) break;
            boolean h = Ui.in(mx, my, chx, chy, w, 12);
            Px.roundRect(g, chx, chy, w, 12, 3, h ? t.accent : t.card);
            Type.left(g, s, Type.MONO, chx + 5, chy + 3, 0.62f, h ? t.onAccent : t.muted);
            hits.add(new Object[]{chx, chy, w, 12, (Runnable) () -> {
                original = null;
                name = e[0];
                steps = e[1];
                note = "Example loaded – change it if you like, then SAVE.";
                noteBad = false;
            }});
            chx += w + 4;
        }

        // actions
        int by = y0 + H - 22;
        int bx = x0 + W - 14;
        bx -= buttonWidth("SAVE");
        button(g, mx, my, bx, by, "SAVE", true, this::save);
        bx -= buttonWidth("RUN") + 6;
        button(g, mx, my, bx, by, "RUN", false, this::run);
        if (original != null) {
            bx -= buttonWidth("DELETE") + 6;
            button(g, mx, my, bx, by, "DELETE", false, this::delete);
        }
        button(g, mx, my, ex, by, "← BACK", false, this::onClose);
    }

    private void field(GuiGraphicsExtractor g, int x, int y, int w, int h, String value, String placeholder, boolean focus, int mx, int my, int idx) {
        Px.roundRect(g, x, y, w, h, 3, focus ? t.cardHover : t.card);
        if (focus) Px.roundRect(g, x, y + h - 1, w, 1, 0, t.accent);
        boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
        if (value.isEmpty() && !focus) Type.left(g, placeholder, Type.UI, x + 6, y + 5, 0.9f, t.muted);
        else {
            String s = value + (focus && blink ? "|" : "");
            while (Type.width(s, Type.UI, 0.9f) > w - 12 && s.length() > 1) s = s.substring(1);
            Type.left(g, s, Type.UI, x + 6, y + 5, 0.9f, t.text);
        }
        hits.add(new Object[]{x, y, w, h, (Runnable) () -> field = idx});
    }

    private void select(String k) {
        original = k;
        name = k;
        steps = Commands.routines().getOrDefault(k, "");
        note = "";
    }

    private void clear() {
        original = null;
        name = "";
        steps = "";
        note = "";
        field = 0;
    }

    private void save() {
        if (name.isBlank() || steps.isBlank()) {
            note = "Give it a name and at least one step.";
            noteBad = true;
            return;
        }
        String key = name.trim().toLowerCase(java.util.Locale.ROOT);
        if (original != null && !original.equals(key)) Commands.forget(original);
        String reply = Commands.remember(name, steps);
        noteBad = !reply.startsWith("Saved");
        note = reply;
        if (!noteBad) original = key.replaceAll("\\{(\\w+)}", "<$1>");
    }

    private void run() {
        if (steps.isBlank()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            note = "Join a world first.";
            noteBad = true;
            return;
        }
        if (!Bot.allowed(mc)) {
            note = Bot.NOT_ALLOWED;
            noteBad = true;
            return;
        }
        String s = steps;
        if (s.contains("<")) {
            note = "This one needs a value – run it from the chat, e.g. \"" + name.replaceAll("<\\w+>", "10") + "\".";
            noteBad = true;
            return;
        }
        String reply = Commands.run(s);
        Bot.CHAT.add(new String[]{"you", name.isBlank() ? s : name});
        Bot.CHAT.add(new String[]{"bot", reply});
        note = reply;
        noteBad = !Bot.busy();
    }

    private void delete() {
        if (original == null) return;
        Commands.forget(original);
        note = "Deleted \"" + original + "\".";
        noteBad = false;
        original = null;
        name = "";
        steps = "";
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        if (e.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                Object[] h = hits.get(i);
                if (Ui.in(e.x(), e.y(), (int) h[0], (int) h[1], (int) h[2], (int) h[3])) {
                    ((Runnable) h[4]).run();
                    return true;
                }
            }
        }
        return super.mouseClicked(e, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        scroll = Math.max(0, Math.min(Math.max(0, Commands.routines().size() * 14 - 120), scroll - (int) (dy * 14)));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (TextInput.key(this, e)) return true;
        if (e.key() == InputConstants.KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (e.key() == InputConstants.KEY_TAB) {
            field = 1 - field;
            return true;
        }
        if (e.isPaste()) {
            String clip = Minecraft.getInstance().keyboardHandler.getClipboard().replaceAll("[\\r\\n]+", " ");
            if (field == 0) name = (name + clip).substring(0, Math.min(32, (name + clip).length()));
            else steps = (steps + clip).substring(0, Math.min(600, (steps + clip).length()));
            return true;
        }
        if (e.key() == InputConstants.KEY_BACKSPACE) {
            if (field == 0 && !name.isEmpty()) name = name.substring(0, name.length() - 1);
            if (field == 1 && !steps.isEmpty()) steps = steps.substring(0, steps.length() - 1);
            return true;
        }
        if (e.key() == InputConstants.KEY_RETURN || e.key() == InputConstants.KEY_NUMPADENTER) {
            if (field == 0) field = 1;
            else save();
            return true;
        }
        return true; // swallow game keys while typing
    }

    @Override
    public boolean charTyped(CharacterEvent e) {
        if (TextInput.echo(e)) return true; // already typed by its key press
        if (!e.isAllowedChatCharacter()) return super.charTyped(e);
        String c = e.codepointAsString();
        if (field == 0 && name.length() < 32) name += c;
        if (field == 1 && steps.length() < 600) steps += c;
        return true;
    }

    @Override
    public void removed() {
        TextInput.focus(this, false);
        super.removed();
    }

    @Override
    protected void init() {
        super.init();
        TextInput.focus(this, true); // a text field has focus: Minecraft switches the window to text input
    }
}
