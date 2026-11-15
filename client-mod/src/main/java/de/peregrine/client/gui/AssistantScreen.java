package de.peregrine.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import de.peregrine.client.bot.Bot;
import de.peregrine.client.bot.Tasks;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Chat with the assistant: type what you want done, it does it. */
public final class AssistantScreen extends Screen {
    private final Theme t = Theme.load();
    private String input = "";
    private static final String[] SUGGESTIONS = {"get 5 diamonds", "iron armor", "16 torches", "go to Home", "my commands", "help"};
    private final List<int[]> chips = new ArrayList<>(); // x, y, w, index
    private int stopX, stopY, stopW;

    public AssistantScreen() {
        super(Component.literal("Assistant"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, t.light ? 0x55000000 : 0x88000000);
    }

    /** Splits text into lines that fit. */
    private static List<String> wrap(String s, float scale, int max) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String w : s.split(" ")) {
            String next = line.isEmpty() ? w : line + " " + w;
            if (Type.width(next, Type.UI, scale) > max && !line.isEmpty()) {
                out.add(line.toString());
                line = new StringBuilder(w);
            } else line = new StringBuilder(next);
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
        extractBackground(g, mx, my, pt);
        int W = Math.min(380, width - 16), H = Math.min(290, height - 16);
        int x0 = (width - W) / 2, y0 = (height - H) / 2;
        g.fill(x0 + 3, y0 + 4, x0 + W + 3, y0 + H + 4, t.shadow);
        g.fill(x0, y0, x0 + W, y0 + H, t.bg | 0xFF000000);
        Ui.chart(g, x0, y0, W, H, t.light);
        int cx = x0 + 14, cw = W - 28;
        Type.left(g, "Assistant", Type.DISPLAY, cx, y0 + 10, 2.1f, t.text);
        Type.right(g, "SINGLEPLAYER · OWN SERVER", Type.MONO, cx + cw, y0 + 14, 0.7f, t.muted);

        // status line
        int sy = y0 + 36;
        boolean busy = Bot.busy();
        g.fill(cx, sy, cx + 5, sy + 5, busy ? t.accent : t.muted);
        Type.left(g, Type.fit(Bot.status(), Type.MONO, 0.75f, cw - 70), Type.MONO, cx + 10, sy - 1, 0.75f, busy ? t.text : t.muted);
        stopW = 0;
        if (busy) {
            String s = "STOP";
            stopW = Type.width(s, Type.MONO, 0.75f) + 14;
            stopX = cx + cw - stopW;
            stopY = sy - 5;
            boolean h = Ui.in(mx, my, stopX, stopY, stopW, 14);
            g.fill(stopX, stopY, stopX + stopW, stopY + 14, h ? t.accent : t.card);
            Type.left(g, s, Type.MONO, stopX + 7, stopY + 4, 0.75f, h ? t.onAccent : t.text);
        }
        g.fill(cx, sy + 12, cx + cw, sy + 13, t.line);

        // conversation, newest at the bottom
        int inputY = y0 + H - 46, top = sy + 18, bottom = inputY - 8;
        List<Object[]> rows = new ArrayList<>(); // {text, you?}
        List<String[]> chat = new ArrayList<>(Bot.CHAT);
        if (chat.isEmpty()) {
            for (String l : wrap("Tell me what you want and I'll play for you. \"get 5 diamonds\" – I chop wood, build a crafting table, make wooden, stone and iron pickaxes, smelt the iron and dig down to the diamonds. German works too: \"hol 64 holz und dann 20 fackeln\". Save your own commands with \"remember name: …\". Esc closes this window; I keep working.", 0.85f, cw - 12))
                rows.add(new Object[]{l, false});
        }
        for (String[] m : chat) {
            boolean you = m[0].equals("you");
            for (String l : wrap(m[1], 0.85f, cw - 40)) rows.add(new Object[]{l, you});
            rows.add(new Object[]{"", you});
        }
        int lh = 10;
        int y = bottom - rows.size() * lh;
        g.enableScissor(x0, top, x0 + W, bottom);
        for (Object[] r : rows) {
            if (y + lh > top) {
                String s = (String) r[0];
                boolean you = (Boolean) r[1];
                if (you) Type.right(g, s, Type.UI, cx + cw - 4, y, 0.85f, t.accent);
                else Type.left(g, s, Type.UI, cx + 4, y, 0.85f, t.text);
            }
            y += lh;
        }
        g.disableScissor();

        // input
        g.fill(cx, inputY, cx + cw, inputY + 18, t.card);
        g.fill(cx, inputY + 17, cx + cw, inputY + 18, t.accent);
        boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
        if (input.isEmpty()) Type.left(g, "What should I do?" + (blink ? "|" : ""), Type.UI, cx + 6, inputY + 5, 0.9f, t.muted);
        else {
            String shown = input + (blink ? "|" : "");
            while (Type.width(shown, Type.UI, 0.9f) > cw - 12 && shown.length() > 1) shown = shown.substring(1);
            Type.left(g, shown, Type.UI, cx + 6, inputY + 5, 0.9f, t.text);
        }
        // suggestion chips
        chips.clear();
        int chx = cx, chy = inputY + 24;
        for (int i = 0; i < SUGGESTIONS.length; i++) {
            String s = SUGGESTIONS[i].toUpperCase();
            int w = Type.width(s, Type.MONO, 0.65f) + 10;
            if (chx + w > cx + cw) break;
            boolean h = Ui.in(mx, my, chx, chy, w, 12);
            g.fill(chx, chy, chx + w, chy + 12, h ? t.accent : t.card);
            Type.left(g, s, Type.MONO, chx + 5, chy + 3, 0.65f, h ? t.onAccent : t.muted);
            chips.add(new int[]{chx, chy, w, i});
            chx += w + 4;
        }
    }

    private void send(String text) {
        text = text.trim();
        if (text.isEmpty()) return;
        Bot.CHAT.add(new String[]{"you", text});
        String reply = Tasks.command(text);
        if (reply != null) Bot.CHAT.add(new String[]{"bot", reply});
        while (Bot.CHAT.size() > 60) Bot.CHAT.remove(0);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        if (e.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            if (stopW > 0 && Ui.in(e.x(), e.y(), stopX, stopY, stopW, 14)) {
                send("stop");
                return true;
            }
            for (int[] c : chips) {
                if (Ui.in(e.x(), e.y(), c[0], c[1], c[2], 12)) {
                    String s = SUGGESTIONS[c[3]];
                    if (s.startsWith("go to")) input = "go to ";
                    else send(s);
                    return true;
                }
            }
        }
        return super.mouseClicked(e, doubleClick);
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (e.key() == InputConstants.KEY_RETURN || e.key() == InputConstants.KEY_NUMPADENTER) {
            send(input);
            input = "";
            return true;
        }
        if (e.key() == InputConstants.KEY_BACKSPACE) {
            if (!input.isEmpty()) input = input.substring(0, input.length() - 1);
            return true;
        }
        if (e.key() == InputConstants.KEY_ESCAPE) return super.keyPressed(e);
        return true; // swallow game keys while typing
    }

    @Override
    public boolean charTyped(CharacterEvent e) {
        if (e.isAllowedChatCharacter() && input.length() < 120) {
            input += e.codepointAsString();
            return true;
        }
        return super.charTyped(e);
    }
}
