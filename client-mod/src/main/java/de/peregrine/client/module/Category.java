package de.peregrine.client.module;

public enum Category {
    HUD("HUD"),
    PVP("PvP"),
    GRAPHICS("Grafik"),
    MOVEMENT("Bewegung"),
    OTHER("Sonstiges");

    public final String label;

    Category(String label) {
        this.label = label;
    }
}
