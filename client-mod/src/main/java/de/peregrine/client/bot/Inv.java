package de.peregrine.client.bot;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ContainerInput;

/**
 * Inventory moves done with normal inventory clicks (the same packets as clicking with the mouse), so they work
 * on servers too: swap into the hand, totem into the off hand, put on armor, throw away junk.
 * Inventory index: 0–8 hotbar, 9–35 main inventory. Menu slots: 5–8 armor, 9–35 main, 36–44 hotbar, 45 off hand.
 */
final class Inv {
    private Inv() {
    }

    private static long lastClick;

    static int menuSlot(int invIndex) {
        return invIndex < 9 ? 36 + invIndex : invIndex;
    }

    /** At most one move every 60 ms – faster clicking desyncs on laggy servers. */
    static boolean ready() {
        return System.currentTimeMillis() - lastClick >= 60;
    }

    private static void click(Minecraft mc, int slot, int button, ContainerInput input) {
        var p = mc.player;
        mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, slot, button, input, p);
        lastClick = System.currentTimeMillis();
    }

    /** Moves an item from the main inventory into a hotbar slot (the hotbar item goes where it was). */
    static void toHotbar(Minecraft mc, int invIndex, int hotbar) {
        if (invIndex < 9 || !ready()) return;
        click(mc, menuSlot(invIndex), hotbar, ContainerInput.SWAP);
    }

    static void toOffhand(Minecraft mc, int invIndex) {
        if (!ready()) return;
        click(mc, menuSlot(invIndex), 40, ContainerInput.SWAP);
    }

    static void equip(Minecraft mc, int invIndex, EquipmentSlot slot) {
        int armor = switch (slot) {
            case HEAD -> 5;
            case CHEST -> 6;
            case LEGS -> 7;
            case FEET -> 8;
            default -> -1;
        };
        if (armor < 0 || !ready()) return;
        int from = menuSlot(invIndex);
        click(mc, from, 0, ContainerInput.PICKUP);  // pick up the better piece
        click(mc, armor, 0, ContainerInput.PICKUP); // swap with what is worn
        click(mc, from, 0, ContainerInput.PICKUP);  // put the old piece back
    }

    static void throwAway(Minecraft mc, int invIndex) {
        click(mc, menuSlot(invIndex), 1, ContainerInput.THROW);
    }
}
