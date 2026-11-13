package de.peregrine.client.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/** Farbe (ARGB) – im Menue aus einer Palette waehlbar. */
public final class ColorSetting extends Setting<Integer> {
    public static final int[] PALETTE = {
            0xFFFFFFFF, 0xFFAAAAAA, 0xFF4F8CFF, 0xFF2DC8B4, 0xFF4AC86E, 0xFFF5C41E,
            0xFFFA8C32, 0xFFF05A5A, 0xFFF06EAF, 0xFFA06EF0, 0xFF55FF55, 0xFF000000
    };

    public ColorSetting(String id, String name, int def) {
        super(id, name, def);
    }

    public int argb() {
        return value;
    }

    @Override
    public JsonElement save() {
        return new JsonPrimitive(String.format("#%08X", value));
    }

    @Override
    public void load(JsonElement e) {
        if (e == null || !e.isJsonPrimitive()) return;
        try {
            String h = e.getAsString().replace("#", "");
            long v = Long.parseLong(h, 16);
            value = (int) (h.length() <= 6 ? 0xFF000000L | v : v);
        } catch (Exception ignored) {
        }
    }
}
