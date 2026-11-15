package de.peregrine.client.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/** Zahl mit Schieberegler. */
public final class NumberSetting extends Setting<Double> {
    public final double min, max, step;
    public final String unit;

    public NumberSetting(String id, String name, double def, double min, double max, double step, String unit) {
        super(id, name, def);
        this.min = min;
        this.max = max;
        this.step = step;
        this.unit = unit;
    }

    public double d() {
        return value;
    }

    public int i() {
        return (int) Math.round(value);
    }

    /** Wert aus Anteil 0..1 setzen (auf Schrittweite gerundet). */
    public void setFraction(double f) {
        double v = min + Math.max(0, Math.min(1, f)) * (max - min);
        v = Math.round(v / step) * step;
        value = Math.max(min, Math.min(max, v));
    }

    public double fraction() {
        return (value - min) / (max - min);
    }

    public String text() {
        String s = step >= 1 ? String.valueOf(i()) : String.format("%.1f", value).replace('.', ',');
        return s + unit;
    }

    @Override
    public JsonElement save() {
        return new JsonPrimitive(value);
    }

    @Override
    public void load(JsonElement e) {
        if (e != null && e.isJsonPrimitive()) value = Math.max(min, Math.min(max, e.getAsDouble()));
    }
}
