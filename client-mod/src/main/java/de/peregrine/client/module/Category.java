package de.peregrine.client.module;

public enum Category {
    HUD("HUD"),
    PVP("Combat"),
    GRAPHICS("Render"),
    MOVEMENT("Movement"),
    WORLD("World"),
    COSMETICS("Cosmetics"),
    OTHER("Misc");

    public final String label;

    Category(String label) {
        this.label = label;
    }
}
