package de.peregrine.client.module;

public enum Category {
    HUD("HUD"),
    PVP("PvP"),
    GRAPHICS("Visuals"),
    MOVEMENT("Movement"),
    WORLD("World"),
    COSMETICS("Cosmetics"),
    OTHER("Misc");

    public final String label;

    Category(String label) {
        this.label = label;
    }
}
