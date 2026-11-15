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
    private static final String[] SUGGESTIONS = {"my commands", "get 5 diamonds", "fight ", "follow me", "go to ", "help"};
    private final List<int[]> chips = new ArrayList<>(); // x, y, w, index
    private int stopX, stopY, stopW;
    private final List<Object[]> heads = new ArrayList<>(); // x, y, w, action
    private boolean keyMode; // typing the Anthropic API key (shown masked, never in the chat)

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
        for (int si = 3; si >= 1; si--) Px.roundRect(g, x0 - si * 2, y0 - si * 2 + 4, W + si * 4, H + si * 4, 8 + si * 2, 0x22000000);
        Px.roundRect(g, x0, y0, W, H, 7, 0xFF000000 | (t.bg & 0xFFFFFF));
        Px.roundStroke(g, x0, y0, W, H, 7, 0x1C000000 | (t.text & 0xFFFFFF));
        if (t.chart) Ui.chart(g, x0, y0, W, H, t.light);
        int cx = x0 + 14, cw = W - 28;
        Type.left(g, "Assistant", Type.DISPLAY, cx, y0 + 10, 2.1f, t.text);
        // Claude switch + key button (top right)
        heads.clear();
        boolean ai = de.peregrine.client.bot.Claude.enabled() && de.peregrine.client.bot.Claude.hasKey();
        int hx = cx + cw;
        String keyL = keyMode ? "CANCEL" : "KEY";
        int kw = Type.width(keyL, Type.MONO, 0.65f) + 10;
        hx -= kw;
        boolean kh = Ui.in(mx, my, hx, y0 + 11, kw, 12);
        Px.roundRect(g, hx, y0 + 11, kw, 12, 3, kh ? t.accent : t.card);
        Type.left(g, keyL, Type.MONO, hx + 5, y0 + 14, 0.65f, kh ? t.onAccent : t.muted);
        heads.add(new Object[]{hx, y0 + 11, kw, (Runnable) () -> {
            keyMode = !keyMode;
            input = "";
        }});
        String cl = "CLAUDE " + (ai ? "ON" : "OFF");
        int clw = Type.width(cl, Type.MONO, 0.65f) + 18;
        hx -= clw + 4;
        boolean ch = Ui.in(mx, my, hx, y0 + 11, clw, 12);
        Px.roundRect(g, hx, y0 + 11, clw, 12, 3, ai ? t.accent : ch ? t.cardHover : t.card);
        Px.roundRect(g, hx + 5, y0 + 15, 4, 4, 2, ai ? t.onAccent : t.muted);
        Type.left(g, cl, Type.MONO, hx + 12, y0 + 14, 0.65f, ai ? t.onAccent : t.muted);
        heads.add(new Object[]{hx, y0 + 11, clw, (Runnable) () -> {
            if (!de.peregrine.client.bot.Claude.hasKey()) {
                keyMode = true;
                input = "";
                return;
            }
            de.peregrine.client.bot.Claude.setEnabled(!de.peregrine.client.bot.Claude.enabled());
        }});

        // status line
        int sy = y0 + 36;
        boolean busy = Bot.busy();
        Px.roundRect(g, cx, sy, 5, 5, 3, busy ? t.accent : t.muted);
        String line = de.peregrine.client.bot.Claude.thinking() ? "Claude is thinking…" + (busy ? "  ·  " + Bot.status() : "") : Bot.status();
        Type.left(g, Type.fit(line, Type.MONO, 0.75f, cw - 70), Type.MONO, cx + 10, sy - 1, 0.75f, busy || de.peregrine.client.bot.Claude.thinking() ? t.text : t.muted);
        stopW = 0;
        if (busy) {
            String s = "STOP";
            stopW = Type.width(s, Type.MONO, 0.75f) + 14;
            stopX = cx + cw - stopW;
            stopY = sy - 5;
            boolean h = Ui.in(mx, my, stopX, stopY, stopW, 14);
            Px.roundRect(g, stopX, stopY, stopW, 14, 3, h ? t.accent : t.card);
            Type.left(g, s, Type.MONO, stopX + 7, stopY + 4, 0.75f, h ? t.onAccent : t.text);
        }
        g.fill(cx, sy + 12, cx + cw, sy + 13, t.line);

        // conversation, newest at the bottom
        int inputY = y0 + H - 46, top = sy + 18, bottom = inputY - 8;
        List<Object[]> rows = new ArrayList<>(); // {text, you?}
        List<String[]> chat = new ArrayList<>(Bot.CHAT);
        if (chat.isEmpty()) {
            for (String l : wrap("Tell me what you want and I'll play for you. \"get 5 diamonds\" – I chop wood, build a crafting table, make wooden, stone and iron pickaxes, smelt the iron and dig down to the diamonds. German works too: \"hol 64 holz und dann 20 fackeln\". Attack a player: \"fight Steve\" or \"greif Steve an\". Your own commands: MY COMMANDS below, or \"remember name: …\". Esc closes this window; I keep working.", 0.85f, cw - 12))
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
        Px.roundRect(g, cx, inputY, cw, 18, 3, t.card);
        g.fill(cx, inputY + 17, cx + cw, inputY + 18, t.accent);
        boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
        if (keyMode) {
            String shown = input.isEmpty() ? "Paste your Anthropic API key (Ctrl+V), then Enter. It stays on this PC." : "•".repeat(Math.min(40, input.length())) + "  " + input.length() + " characters";
            Type.left(g, Type.fit(shown + (blink ? "|" : ""), Type.UI, 0.9f, cw - 12), Type.UI, cx + 6, inputY + 5, 0.9f, input.isEmpty() ? t.muted : t.text);
        } else if (input.isEmpty()) Type.left(g, (de.peregrine.client.bot.Claude.active() ? "Ask Claude anything…" : "What should I do?") + (blink ? "|" : ""), Type.UI, cx + 6, inputY + 5, 0.9f, t.muted);
        else {
            String shown = input + (blink ? "|" : "");
            while (Type.width(shown, Type.UI, 0.9f) > cw - 12 && shown.length() > 1) shown = shown.substring(1);
            Type.left(g, shown, Type.UI, cx + 6, inputY + 5, 0.9f, t.text);
        }
        // suggestion chips
        chips.clear();
        int chx = cx, chy = inputY + 24;
        for (int i = 0; i < SUGGESTIONS.length; i++) {
            String s = SUGGESTIONS[i].trim().toUpperCase() + (SUGGESTIONS[i].endsWith(" ") ? " …" : "");
            int w = Type.width(s, Type.MONO, 0.65f) + 10;
            if (chx + w > cx + cw) break;
            boolean h = Ui.in(mx, my, chx, chy, w, 12);
            Px.roundRect(g, chx, chy, w, 12, 3, h ? t.accent : t.card);
            Type.left(g, s, Type.MONO, chx + 5, chy + 3, 0.65f, h ? t.onAccent : t.muted);
            chips.add(new int[]{chx, chy, w, i});
            chx += w + 4;
        }
    }

    private void send(String text) {
        text = text.trim();
        if (text.isEmpty()) return;
        if (keyMode) {
            keyMode = false;
            if (!text.startsWith("sk-")) {
                Bot.CHAT.add(new String[]{"bot", "That doesn't look like an Anthropic API key (they start with sk-ant-). Get one at console.anthropic.com."});
                return;
            }
            de.peregrine.client.bot.Claude.setKey(text);
            de.peregrine.client.bot.Claude.setEnabled(true);
            Bot.CHAT.add(new String[]{"bot", de.peregrine.client.bot.Claude.libsPresent()
                    ? "Key saved. Claude is my brain now – just talk to me normally."
                    : "Key saved, but the Claude libraries are missing. Start Minecraft from the Peregrine launcher – it installs them."});
            return;
        }
        Bot.CHAT.add(new String[]{"you", text});
        String low = text.toLowerCase(java.util.Locale.ROOT);
        boolean local = low.matches("(stop|stopp|halt|abbrechen|cancel)[.!]*") || low.matches("(forget|vergiss) (the )?(chat|conversation|gespräch|gespraech)");
        if (low.matches("(forget|vergiss) (the )?(chat|conversation|gespräch|gespraech)")) {
            de.peregrine.client.bot.Claude.forgetConversation();
            Bot.CHAT.add(new String[]{"bot", "Fresh start – I forgot our conversation."});
            return;
        }
        if (!local && de.peregrine.client.bot.Claude.active()) {
            if (de.peregrine.client.bot.Claude.thinking()) {
                Bot.CHAT.add(new String[]{"bot", "Still thinking about the last one – a second, please."});
                return;
            }
            de.peregrine.client.bot.Claude.ask(text);
            return;
        }
        String reply = Tasks.command(text);
        if (reply != null) Bot.CHAT.add(new String[]{"bot", reply});
        while (Bot.CHAT.size() > 60) Bot.CHAT.remove(0);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        if (e.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            for (Object[] h : heads) {
                if (Ui.in(e.x(), e.y(), (int) h[0], (int) h[1], (int) h[2], 12)) {
                    ((Runnable) h[3]).run();
                    return true;
                }
            }
            if (stopW > 0 && Ui.in(e.x(), e.y(), stopX, stopY, stopW, 14)) {
                send("stop");
                return true;
            }
            for (int[] c : chips) {
                if (Ui.in(e.x(), e.y(), c[0], c[1], c[2], 12)) {
                    String s = SUGGESTIONS[c[3]];
                    if (s.equals("my commands")) Ui.open(new CommandsScreen(this));
                    else if (s.endsWith(" ")) input = s;
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
        if (e.isPaste()) {
            input = (input + net.minecraft.client.Minecraft.getInstance().keyboardHandler.getClipboard().replaceAll(keyMode ? "\\s+" : "[\\r\\n]+", keyMode ? "" : " "));
            if (input.length() > 300) input = input.substring(0, 300);
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
        if (e.isAllowedChatCharacter() && input.length() < 300) {
            input += e.codepointAsString();
            return true;
        }
        return super.charTyped(e);
    }
}
