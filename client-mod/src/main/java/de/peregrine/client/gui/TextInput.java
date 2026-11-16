package de.peregrine.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * Typing into Peregrine's own screens. Since 26.x, Minecraft only switches the window into text input while a
 * text field has focus ({@code Minecraft.onTextInputFocusChange}); our screens draw their own fields, so on Windows
 * the typed characters could get lost. Two things fix that:
 * <ul>
 *   <li>a screen with a text field reports focus like a vanilla text box ({@link #focus});</li>
 *   <li>as a fallback, printable keys are turned into characters from the key press itself (the key name of the
 *   current keyboard layout, so QWERTZ works); when the normal character event follows anyway, it is dropped, so
 *   nothing is typed twice.</li>
 * </ul>
 */
public final class TextInput {
    private TextInput() {
    }

    private static int pending = -1;
    private static boolean synthetic;

    /** Tell Minecraft a text field in this screen has focus (true) or lost it (false). */
    public static void focus(GuiEventListener screen, boolean on) {
        try {
            Minecraft.getInstance().onTextInputFocusChange(screen, on);
        } catch (Throwable ignored) {
        }
    }

    /** The character a key press types (layout aware), or null for keys that don't type anything plain. */
    static String fromKey(KeyEvent e) {
        int mods = e.modifiers();
        if ((mods & (InputConstants.MOD_CONTROL | InputConstants.MOD_ALT | InputConstants.MOD_SUPER)) != 0) return null;
        int k = e.key();
        if (k == InputConstants.KEY_SPACE) return " ";
        if (k >= InputConstants.KEY_NUMPAD0 && k <= InputConstants.KEY_NUMPAD0 + 9) return String.valueOf((char) ('0' + k - InputConstants.KEY_NUMPAD0));
        String n;
        try { // the key's name in the current keyboard layout (GLFW up to 26.2, SDL in 26.3)
            n = InputConstants.getKey(e).getDisplayName().getString();
        } catch (Throwable t) {
            return null;
        }
        if (n == null || n.codePointCount(0, n.length()) != 1) return null;
        boolean shift = (mods & InputConstants.MOD_SHIFT) != 0;
        boolean caps = (mods & InputConstants.MOD_CAPS_LOCK) != 0;
        int cp = n.codePointAt(0);
        if (Character.isLetter(cp)) return shift != caps ? n.toUpperCase() : n.toLowerCase();
        return shift ? null : n; // digits and punctuation: unshifted only; shifted symbols depend on the layout
    }

    /**
     * Call at the start of a screen's keyPressed. Types the key's character through the screen's own charTyped and
     * returns true if the screen took it.
     */
    public static boolean key(Screen screen, KeyEvent e) {
        String s = fromKey(e);
        if (s == null) return false;
        int cp = s.codePointAt(0);
        synthetic = true;
        boolean took;
        try {
            took = screen.charTyped(new CharacterEvent(cp));
        } finally {
            synthetic = false;
        }
        pending = took ? cp : -1;
        return took;
    }

    /** Call at the start of charTyped: true = this character was already typed by its key press, skip it. */
    public static boolean echo(CharacterEvent e) {
        if (synthetic) return false;
        boolean dup = pending == e.codepoint() || pending >= 0 && Character.toLowerCase(pending) == Character.toLowerCase(e.codepoint());
        pending = -1;
        return dup;
    }
}
