package de.peregrine.client.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/** Auswahl aus festen Moeglichkeiten (Klick schaltet weiter). */
public final class ModeSetting extends Setting<String> {
    public String[] modes;

    public ModeSetting(String id, String name, String def, String... modes) {
        super(id, name, def);
        this.modes = modes;
    }

    /** Auswahl neu setzen (z. B. neu hochgeladene Umhänge); unbekannter Wert fällt auf den Standard zurück. */
    public void setModes(String... m) {
        modes = m;
        boolean ok = false;
        for (String x : m) if (x.equals(value)) ok = true;
        if (!ok) value = def;
    }

    public boolean is(String m) {
        return value.equals(m);
    }

    public void cycle(int dir) {
        int i = 0;
        for (int k = 0; k < modes.length; k++) if (modes[k].equals(value)) i = k;
        value = modes[Math.floorMod(i + dir, modes.length)];
    }

    @Override
    public JsonElement save() {
        return new JsonPrimitive(value);
    }

    @Override
    public void load(JsonElement e) {
        if (e == null || !e.isJsonPrimitive()) return;
        String s = e.getAsString();
        for (String m : modes) if (m.equals(s)) value = s;
    }
}
