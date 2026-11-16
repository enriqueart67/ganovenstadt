package de.peregrine.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import de.peregrine.client.feature.CodeLock;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** Asks for the access code before the client opens up. */
public final class CodeScreen extends Screen {
    private final Theme t = Theme.load();
    private final Screen next;
    private String code = "", note = "";
    private boolean bad;

    public CodeScreen(Screen next) {
        super(Component.literal("Peregrine access"));
        this.next = next;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, t.light ? 0x66000000 : 0x99000000);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
        extractBackground(g, mx, my, pt);
        int W = Math.min(260, width - 16), H = 128;
        int x0 = (width - W) / 2, y0 = (height - H) / 2;
        for (int i = 3; i >= 1; i--) Px.roundRect(g, x0 - i * 2, y0 - i * 2 + 4, W + i * 4, H + i * 4, 8 + i * 2, 0x22000000);
        Px.roundRect(g, x0, y0, W, H, 7, 0xFF000000 | (t.bg & 0xFFFFFF));
        Px.roundStroke(g, x0, y0, W, H, 7, 0x1C000000 | (t.text & 0xFFFFFF));
        Ui.falcon(g, x0 + 16, y0 + 14, 14, 0xFF000000 | (t.accent & 0xFFFFFF));
        Type.spaced(g, "PEREGRINE", Type.MONO, x0 + 36, y0 + 17, 0.7f, t.text, 2f);
        Type.left(g, "Access code", Type.DISPLAY, x0 + 16, y0 + 36, 1.6f, t.text);
        Type.left(g, "This client is private. Enter the code to use it.", Type.UI, x0 + 16, y0 + 56, 0.75f, t.muted);
        int fy = y0 + 72;
        Px.roundRect(g, x0 + 16, fy, W - 32, 20, 4, t.card);
        Px.roundRect(g, x0 + 16, fy + 19, W - 32, 1, 0, bad ? 0xFFF05A5A : t.accent);
        boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
        String shown = "•".repeat(code.length()) + (blink ? "|" : "");
        Type.left(g, code.isEmpty() && !blink ? "Code" : shown, Type.UI, x0 + 22, fy + 6, 0.95f, code.isEmpty() ? t.muted : t.text);
        if (!note.isEmpty()) Type.left(g, note, Type.UI, x0 + 16, y0 + H - 22, 0.75f, bad ? 0xFFF05A5A : t.accent);
        else Type.left(g, "Enter to confirm · Esc to close", Type.MONO, x0 + 16, y0 + H - 22, 0.6f, t.muted);
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (TextInput.key(this, e)) return true;
        if (e.key() == InputConstants.KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (e.key() == InputConstants.KEY_BACKSPACE) {
            if (!code.isEmpty()) code = code.substring(0, code.length() - 1);
            return true;
        }
        if (e.key() == InputConstants.KEY_RETURN || e.key() == InputConstants.KEY_NUMPADENTER) {
            if (CodeLock.tryCode(code)) {
                note = "Unlocked.";
                bad = false;
                Ui.open(next);
            } else {
                note = "Wrong code.";
                bad = true;
                code = "";
            }
            return true;
        }
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent e) {
        if (TextInput.echo(e)) return true; // already typed by its key press
        if (e.isAllowedChatCharacter() && code.length() < 16) code += e.codepointAsString();
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
