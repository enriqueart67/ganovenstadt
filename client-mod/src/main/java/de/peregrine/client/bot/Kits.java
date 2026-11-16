package de.peregrine.client.bot;

import net.minecraft.client.Minecraft;

/**
 * Ready-made kits for your own world: gear the assistant fights best with, put in fixed hotbar slots so the fight
 * code finds everything at once. Only where the world runs on this computer (singleplayer or your LAN world) – it
 * uses the server's own /item command; on a real server nobody can make items, so there it explains that.
 */
final class Kits {
    private Kits() {
    }

    private static final String ARMOR_ENCH = "enchantments={\"minecraft:blast_protection\":4,\"minecraft:unbreaking\":3,\"minecraft:mending\":1}";

    static String cart(Minecraft mc) {
        if (mc.getSingleplayerServer() == null || mc.player == null)
            return "I can only make a kit in a world that runs on this computer (singleplayer or your LAN world). On a server you'd need the items yourself: rails, TNT minecarts, a Flame bow, arrows and blast protection armor.";
        String me = mc.player.getName().getString();
        String[] cmds = {
                "item replace entity " + me + " armor.head with netherite_helmet[" + ARMOR_ENCH + "]",
                "item replace entity " + me + " armor.chest with netherite_chestplate[" + ARMOR_ENCH + "]",
                "item replace entity " + me + " armor.legs with netherite_leggings[" + ARMOR_ENCH + "]",
                "item replace entity " + me + " armor.feet with netherite_boots[" + ARMOR_ENCH + "]",
                "item replace entity " + me + " weapon.offhand with totem_of_undying",
                "item replace entity " + me + " hotbar.0 with netherite_sword[enchantments={\"minecraft:sharpness\":5,\"minecraft:unbreaking\":3}]",
                "item replace entity " + me + " hotbar.1 with bow[enchantments={\"minecraft:flame\":1,\"minecraft:infinity\":1,\"minecraft:unbreaking\":3}]",
                "item replace entity " + me + " hotbar.2 with rail 64",
                "item replace entity " + me + " hotbar.3 with tnt_minecart",
                "item replace entity " + me + " hotbar.4 with tnt_minecart",
                "item replace entity " + me + " hotbar.5 with tnt_minecart",
                "item replace entity " + me + " hotbar.6 with golden_apple 64",
                "item replace entity " + me + " hotbar.7 with ender_pearl 16",
                "item replace entity " + me + " hotbar.8 with totem_of_undying",
                "item replace entity " + me + " inventory.0 with arrow",
                "item replace entity " + me + " inventory.1 with rail 64",
        };
        Bot.onServer(mc, sp -> {
            var server = sp.level().getServer();
            var src = server.createCommandSourceStack().withSuppressedOutput();
            for (String c : cmds) server.getCommands().performPrefixedCommand(src, c);
            for (int i = 2; i < 18; i++) server.getCommands().performPrefixedCommand(src, "item replace entity " + me + " inventory." + i + " with tnt_minecart");
            for (int i = 18; i < 22; i++) server.getCommands().performPrefixedCommand(src, "item replace entity " + me + " inventory." + i + " with totem_of_undying");
        });
        return "Cart kit: netherite armor with Blast Protection IV, totems, Sharpness V sword, Flame bow, rails, 19 TNT minecarts, golden apples, pearls. Now \"fight <name>\".";
    }
}
