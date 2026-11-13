package de.peregrine.client.setting;

import com.google.gson.JsonElement;

/** Eine Einstellung eines Moduls (Schalter, Schieberegler, Auswahl oder Farbe). */
public abstract class Setting<T> {
    public final String id;
    public final String name;
    protected final T def;
    protected T value;

    protected Setting(String id, String name, T def) {
        this.id = id;
        this.name = name;
        this.def = def;
        this.value = def;
    }

    public T get() {
        return value;
    }

    public void set(T v) {
        value = v;
    }

    public void reset() {
        value = def;
    }

    public abstract JsonElement save();

    public abstract void load(JsonElement e);
}
