package de.peregrine.client.setting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.List;

/** A list of ids (e.g. the blocks Block ESP highlights). Saved as a JSON array. */
public final class ListSetting extends Setting<List<String>> {
    public ListSetting(String id, String name, List<String> def) {
        super(id, name, List.copyOf(def));
        value = new ArrayList<>(def);
    }

    public boolean contains(String s) {
        return value.contains(s);
    }

    public void toggle(String s) {
        List<String> v = new ArrayList<>(value);
        if (!v.remove(s)) v.add(s);
        value = v;
    }

    @Override
    public void reset() {
        value = new ArrayList<>(def);
    }

    @Override
    public JsonElement save() {
        JsonArray a = new JsonArray();
        value.forEach(a::add);
        return a;
    }

    @Override
    public void load(JsonElement e) {
        if (e == null || !e.isJsonArray()) return;
        List<String> v = new ArrayList<>();
        e.getAsJsonArray().forEach(x -> v.add(x.getAsString()));
        value = v;
    }
}
