package de.peregrine.client.bot;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * Ready-made kits for your own world: the gear each technique needs, in fixed hotbar slots so the fight code finds
 * everything at once. Only where the world runs on this computer (singleplayer or your LAN world) – it uses the
 * server's own /item command; on a real server nobody can make items, so there it explains that. Also spawns the
 * sparring partner ({@link Sparring}).
 */
final class Kits {
    private Kits() {
    }

    private static final String SWORD = "netherite_sword[enchantments={\"minecraft:sharpness\":5,\"minecraft:unbreaking\":3}]";
    private static final String AXE = "netherite_axe[enchantments={\"minecraft:sharpness\":5,\"minecraft:unbreaking\":3}]";
    private static final String BOW = "bow[enchantments={\"minecraft:flame\":1,\"minecraft:infinity\":1,\"minecraft:unbreaking\":3}]";
    private static final String MACE = "mace[enchantments={\"minecraft:density\":5,\"minecraft:wind_burst\":1,\"minecraft:unbreaking\":3}]";
    private static final String SPEAR = "netherite_spear[enchantments={\"minecraft:lunge\":3,\"minecraft:unbreaking\":3}]";

    static final String NAMES = "cart, crystal, anchor, mace, elytra, full";

    private static boolean local(Minecraft mc) {
        return mc.getSingleplayerServer() != null && mc.player != null;
    }

    private static final String NOT_LOCAL = "I can only make kits in a world that runs on this computer (singleplayer or your LAN world). On a server nobody can make items – bring the gear yourself.";

    /** kind = cart | crystal | anchor | mace | elytra | full */
    static String give(Minecraft mc, String kind) {
        if (!local(mc)) return NOT_LOCAL;
        String me = mc.player.getName().getString();
        List<String> c = new ArrayList<>();
        c.add("clear " + me);
        // trained vs. the sparring partner: full blast protection lost every sword trade. Explosive kits get the usual
        // PvP mix (blast protection only on the leggings), mace kits plain protection
        boolean boom = !kind.equals("sword") && (kind.equals("cart") || kind.equals("crystal") || kind.equals("anchor") || kind.equals("full"));
        for (String[] a : new String[][]{{"head", "helmet"}, {"chest", "chestplate"}, {"legs", "leggings"}, {"feet", "boots"}}) {
            String prot = boom && a[0].equals("legs") ? "blast_protection" : "protection";
            String extra = a[0].equals("feet") ? ",\"minecraft:feather_falling\":4" : "";
            c.add("item replace entity " + me + " armor." + a[0] + " with netherite_" + a[1] + "[enchantments={\"minecraft:" + prot + "\":4,\"minecraft:unbreaking\":3,\"minecraft:mending\":1" + extra + "}]");
        }
        c.add("item replace entity " + me + " weapon.offhand with totem_of_undying");
        String[] bar;
        List<String> inv = new ArrayList<>();
        switch (kind) {
            case "crystal" -> {
                bar = new String[]{SWORD, "end_crystal 64", "obsidian 64", AXE, "golden_apple 64", "ender_pearl 16", "experience_bottle 64", "cobblestone 64", "totem_of_undying"};
                inv.add("end_crystal 64");
                inv.add("end_crystal 64");
                inv.add("obsidian 64");
            }
            case "sword" -> { // same gear as the sparring partner: a fair sword fight
                bar = new String[]{SWORD, AXE, "golden_apple 8", "cooked_beef 64", "totem_of_undying", "air", "air", "air", "air"};
            }
            case "anchor" -> {
                bar = new String[]{SWORD, "respawn_anchor 64", "glowstone 64", AXE, "golden_apple 64", "ender_pearl 16", "cobblestone 64", "obsidian 64", "totem_of_undying"};
                inv.add("respawn_anchor 64");
                inv.add("glowstone 64");
            }
            case "mace" -> {
                bar = new String[]{SWORD, MACE, "wind_charge 64", AXE, "golden_apple 64", "ender_pearl 16", SPEAR, "cobblestone 64", "totem_of_undying"};
                inv.add("wind_charge 64");
            }
            case "elytra" -> {
                bar = new String[]{SWORD, MACE, "firework_rocket 64", "wind_charge 64", "golden_apple 64", "ender_pearl 16", SPEAR, AXE, "totem_of_undying"};
                inv.add("elytra[enchantments={\"minecraft:unbreaking\":3}]");
                inv.add("firework_rocket 64");
                inv.add("wind_charge 64");
            }
            case "full" -> {
                bar = new String[]{SWORD, "end_crystal 64", "obsidian 64", MACE, "wind_charge 64", BOW, "rail 64", "tnt_minecart", "totem_of_undying"};
                inv.add("golden_apple 64");
                inv.add("ender_pearl 16");
                inv.add("respawn_anchor 64");
                inv.add("glowstone 64");
                inv.add("arrow");
                inv.add(AXE);
                inv.add(SPEAR);
                inv.add("end_crystal 64");
                for (int i = 0; i < 8; i++) inv.add("tnt_minecart");
            }
            default -> { // cart
                bar = new String[]{SWORD, BOW, "rail 64", "tnt_minecart", "tnt_minecart", "tnt_minecart", "golden_apple 64", "ender_pearl 16", "totem_of_undying"};
                inv.add("arrow");
                inv.add("rail 64");
                for (int i = 0; i < 16; i++) inv.add("tnt_minecart");
            }
        }
        for (int i = 0; i < 9; i++) if (!bar[i].equals("air")) c.add("item replace entity " + me + " hotbar." + i + " with " + bar[i]);
        for (int i = 0; i < (kind.equals("sword") ? 3 : 4); i++) inv.add("totem_of_undying");
        for (int i = 0; i < inv.size() && i < 27; i++) c.add("item replace entity " + me + " inventory." + i + " with " + inv.get(i));
        run(mc, c);
        return switch (kind) {
            case "crystal" -> "Crystal kit: blast protection netherite, totems, crystals, obsidian, sword + axe, golden apples, pearls.";
            case "sword" -> "Sword kit: same gear as the sparring partner (protection netherite, Sharpness V sword, axe, 5 totems, 8 golden apples).";
            case "anchor" -> "Anchor kit: blast protection netherite, totems, respawn anchors + glowstone, sword + axe, golden apples.";
            case "mace" -> "Mace kit: blast protection netherite, totems, mace (Density V, Wind Burst), wind charges, Lunge III spear, sword + axe.";
            case "elytra" -> "Elytra mace kit: armor (chestplate on, elytra in the bag), rockets, mace, wind charges, Lunge spear, totems.";
            case "full" -> "Full kit: everything – crystals, mace, carts, anchors, pearls, totems.";
            default -> "Cart kit: blast protection netherite, totems, Flame bow, rails, 19 TNT minecarts, golden apples, pearls.";
        } + " Then: \"fight <name>\" – or \"sparring\" for a training partner.";
    }

    /** Spawns the sparring partner 6 blocks in front of you. variant: "", "blast", "shield". */
    static String sparring(Minecraft mc, String variant) {
        if (!local(mc)) return NOT_LOCAL;
        var p = mc.player;
        double yaw = Math.toRadians(p.getYRot());
        double x = p.getX() - Math.sin(yaw) * 6, z = p.getZ() + Math.cos(yaw) * 6;
        run(mc, List.of(Sparring.spawnCommands(p.getName().getString(), x, p.getY(), z, variant)));
        return "Sparring partner is here (" + (variant.isBlank() ? "protection" : variant) + " armor, sword, totems, golden apples – it strafes, hits back and dodges carts and crystals). Say \"fight sparring\".";
    }

    private static void run(Minecraft mc, List<String> cmds) {
        Bot.onServer(mc, sp -> {
            var server = sp.level().getServer();
            var src = server.createCommandSourceStack().withSuppressedOutput();
            for (String c : cmds) server.getCommands().performPrefixedCommand(src, c);
        });
    }
}
