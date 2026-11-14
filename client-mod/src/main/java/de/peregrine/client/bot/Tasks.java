package de.peregrine.client.bot;

import de.peregrine.client.Waypoints;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** The jobs the assistant can do. */
public final class Tasks {
    private Tasks() {
    }

    // ------------------------------------------------------------------ ores
    /** Ore name → block ids, item that counts, best height, needs at least this pickaxe. */
    public record Ore(String name, Set<String> blocks, String[] items, int y, String pickaxe) {
    }

    public static final Ore[] ORES = {
            new Ore("diamonds", Set.of("diamond_ore", "deepslate_diamond_ore"), new String[]{"diamond"}, -58, "iron"),
            new Ore("iron", Set.of("iron_ore", "deepslate_iron_ore"), new String[]{"raw_iron"}, 16, "stone"),
            new Ore("coal", Set.of("coal_ore", "deepslate_coal_ore"), new String[]{"coal"}, 45, "wooden"),
            new Ore("gold", Set.of("gold_ore", "deepslate_gold_ore"), new String[]{"raw_gold"}, -16, "iron"),
            new Ore("redstone", Set.of("redstone_ore", "deepslate_redstone_ore"), new String[]{"redstone"}, -58, "iron"),
            new Ore("lapis", Set.of("lapis_ore", "deepslate_lapis_ore"), new String[]{"lapis_lazuli"}, 0, "stone"),
            new Ore("copper", Set.of("copper_ore", "deepslate_copper_ore"), new String[]{"raw_copper"}, 48, "stone"),
            new Ore("emeralds", Set.of("emerald_ore", "deepslate_emerald_ore"), new String[]{"emerald"}, 100, "iron"),
    };

    /** Strip-mines at the ore's best height and digs out every matching ore it can see nearby. */
    public static final class Mine implements Bot.Task {
        private final Ore ore;
        private final int wanted;
        private int start = -1;
        private Navigator nav;
        private BlockPos target; // ore block being mined
        private BlockPos collect;  // where its drop lies
        private final Set<BlockPos> skip = new HashSet<>();
        private Direction dir;
        private String phase = "starting";
        private int steps;

        public Mine(Ore ore, int wanted) {
            this.ore = ore;
            this.wanted = wanted;
        }

        private int have(Minecraft mc) {
            return Bot.count(mc, ore.items());
        }

        @Override
        public String status() {
            Minecraft mc = Minecraft.getInstance();
            int got = mc.player == null || start < 0 ? 0 : have(mc) - start;
            return "Mining " + ore.name() + ": " + got + " / " + wanted + "  (" + phase + ")";
        }

        @Override
        public boolean tick(Minecraft mc) {
            LocalPlayer p = mc.player;
            if (start < 0) {
                start = have(mc);
                dir = p.getDirection();
                BlockState sample = BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(ore.blocks().iterator().next())).defaultBlockState();
                if (!Bot.hasToolFor(mc, sample)) {
                    Bot.stop("For " + ore.name() + " I need at least " + (ore.pickaxe().startsWith("i") ? "an " : "a ") + ore.pickaxe() + " pickaxe in your hotbar.");
                    return false;
                }
            }
            if (have(mc) - start >= wanted) {
                phase = "done";
                Bot.say("Done! " + wanted + " " + ore.name() + " are in your inventory.");
                return true;
            }
            if (Bot.inventoryFull(mc)) {
                Bot.stop("Your inventory is full – I stopped. Empty it and ask me again.");
                return false;
            }
            if (p.getFoodData().getFoodLevel() <= 6) {
                Bot.stop("You're hungry – eat something, then ask me again.");
                return false;
            }

            // 1) collecting a drop
            if (collect != null) {
                phase = "picking up";
                if (nav == null) nav = new Navigator(collect, 0, dir);
                if (nav.tick(mc)) {
                    nav = null;
                    collect = null;
                }
                return false;
            }
            // 2) mining an ore we found
            if (target != null) {
                phase = "mining " + ore.name();
                if (!ore.blocks().contains(Bot.id(mc.level.getBlockState(target)))) { // gone
                    collect = target;
                    target = null;
                    nav = null;
                    return false;
                }
                if (p.getEyePosition().distanceTo(Vec3.atCenterOf(target)) <= 4.2) {
                    if (Bot.unsafeToMine(mc, target)) {
                        skip.add(target);
                        target = null;
                        return false;
                    }
                    Bot.mine(mc, target);
                    return false;
                }
                if (nav == null) nav = new Navigator(target, 4.0, dir);
                if (nav.tick(mc)) {
                    if (nav.problem != null) {
                        skip.add(target);
                        target = null;
                    }
                    nav = null;
                }
                return false;
            }
            // 3) look around for ores
            BlockPos found = scan(mc, p.blockPosition(), 5);
            if (found != null) {
                target = found;
                nav = null;
                return false;
            }
            // 4) get down (or up) to the right height, then tunnel forwards
            BlockPos feet = p.blockPosition();
            if (Math.abs(feet.getY() - ore.y()) > 2) {
                phase = "digging " + (feet.getY() > ore.y() ? "down" : "up") + " to Y " + ore.y();
                if (nav == null) {
                    int dist = Math.abs(feet.getY() - ore.y());
                    nav = new Navigator(feet.relative(dir, dist).atY(ore.y()), 0, dir);
                }
            } else {
                phase = "strip-mining";
                if (nav == null) {
                    steps++;
                    if (steps % 40 == 0) dir = dir.getClockWise(); // turn now and then to cover new ground
                    nav = new Navigator(feet.relative(dir, 3).atY(ore.y()), 0, dir);
                }
            }
            if (nav.tick(mc)) {
                if (nav.problem != null) {
                    dir = dir.getClockWise();
                    Bot.say(nav.problem + " Turning.");
                }
                nav = null;
            }
            return false;
        }

        private BlockPos scan(Minecraft mc, BlockPos c, int r) {
            BlockPos best = null;
            double bestD = Double.MAX_VALUE;
            for (BlockPos b : BlockPos.betweenClosed(c.offset(-r, -r, -r), c.offset(r, r, r))) {
                if (skip.contains(b)) continue;
                if (!ore.blocks().contains(Bot.id(mc.level.getBlockState(b)))) continue;
                double d = b.distSqr(c);
                if (d < bestD) {
                    bestD = d;
                    best = b.immutable();
                }
            }
            return best;
        }
    }

    // ------------------------------------------------------------------ wood
    public static final class Chop implements Bot.Task {
        private final int wanted;
        private int start = -1;
        private Navigator nav;
        private BlockPos tree, collect;
        private final Set<BlockPos> skip = new HashSet<>();
        private String phase = "looking for trees";

        public Chop(int wanted) {
            this.wanted = wanted;
        }

        private static boolean isLog(String id) {
            return id.endsWith("_log") || id.endsWith("_stem");
        }

        private int have(Minecraft mc) {
            int n = 0;
            var inv = mc.player.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                var it = inv.getItem(i);
                if (!it.isEmpty() && isLog(BuiltInRegistries.ITEM.getKey(it.getItem()).getPath())) n += it.getCount();
            }
            return n;
        }

        @Override
        public String status() {
            Minecraft mc = Minecraft.getInstance();
            int got = mc.player == null || start < 0 ? 0 : have(mc) - start;
            return "Chopping wood: " + got + " / " + wanted + "  (" + phase + ")";
        }

        @Override
        public boolean tick(Minecraft mc) {
            LocalPlayer p = mc.player;
            if (start < 0) {
                start = have(mc);
            }
            if (have(mc) - start >= wanted) {
                phase = "done";
                Bot.say("Done! " + wanted + " logs collected.");
                return true;
            }
            if (Bot.inventoryFull(mc)) {
                Bot.stop("Your inventory is full – I stopped.");
                return false;
            }
            if (collect != null) {
                phase = "picking up";
                if (nav == null) nav = new Navigator(collect, 0, p.getDirection());
                if (nav.tick(mc)) {
                    nav = null;
                    collect = null;
                }
                return false;
            }
            if (tree == null) {
                tree = nearestLog(mc, p.blockPosition());
                if (tree == null) {
                    Bot.stop("I can't see any trees near here. Walk to a forest and ask me again.");
                    return false;
                }
                nav = null;
            }
            // the lowest log of the trunk that is still there, then upwards
            BlockPos log = null;
            for (int up = -1; up <= 7; up++) {
                BlockPos b = tree.above(up);
                if (isLog(Bot.id(mc.level.getBlockState(b)))) {
                    log = b;
                    break;
                }
            }
            if (log == null) { // trunk done: collect drops at its base
                collect = tree;
                tree = null;
                return false;
            }
            phase = "chopping";
            if (p.getEyePosition().distanceTo(Vec3.atCenterOf(log)) <= 4.3) {
                Bot.mine(mc, log);
                return false;
            }
            if (log.getY() > tree.getY() + 3) { // the rest is out of reach – take what fell and move on
                skip.add(tree);
                collect = tree;
                tree = null;
                return false;
            }
            if (nav == null) nav = new Navigator(log, 3.8, p.getDirection());
            if (nav.tick(mc)) {
                if (nav.problem != null) {
                    skip.add(tree);
                    tree = null;
                }
                nav = null;
            }
            return false;
        }

        private BlockPos nearestLog(Minecraft mc, BlockPos c) {
            return java.util.stream.StreamSupport.stream(BlockPos.betweenClosed(c.offset(-24, -6, -24), c.offset(24, 8, 24)).spliterator(), false)
                    .filter(b -> isLog(Bot.id(mc.level.getBlockState(b))))
                    // trunk base: a log with ground (not log) below
                    .filter(b -> !isLog(Bot.id(mc.level.getBlockState(b.below()))) && Bot.solid(mc, b.below()))
                    .map(BlockPos::immutable)
                    .filter(b -> !skip.contains(b))
                    .min(Comparator.comparingDouble(b -> b.distSqr(c)))
                    .orElse(null);
        }
    }

    // ------------------------------------------------------------------ walking
    public static final class GoTo implements Bot.Task {
        private final Navigator nav;
        private final String name;

        public GoTo(BlockPos target, String name) {
            this.nav = new Navigator(target, 0, null);
            this.name = name;
        }

        @Override
        public String status() {
            Minecraft mc = Minecraft.getInstance();
            int d = mc.player == null ? 0 : (int) Math.sqrt(mc.player.blockPosition().distSqr(nav.goal()));
            if (d == 0) return "Arrived at " + name;
            return "Walking to " + name + ": " + d + " m left";
        }

        @Override
        public boolean tick(Minecraft mc) {
            if (nav.tick(mc)) {
                if (nav.problem != null) Bot.stop(nav.problem);
                else Bot.say("I'm at " + name + ".");
                return nav.problem == null;
            }
            return false;
        }
    }

    // ------------------------------------------------------------------ understanding what you say
    private static int number(String s, int def) {
        var m = java.util.regex.Pattern.compile("(\\d+)").matcher(s);
        return m.find() ? Math.max(1, Math.min(2304, Integer.parseInt(m.group(1)))) : def;
    }

    /** Turns a sentence (English or German) into a task. Returns the reply to show. */
    public static String command(String text) {
        Minecraft mc = Minecraft.getInstance();
        String t = text.toLowerCase(Locale.ROOT).trim();
        if (t.isEmpty()) return null;
        if (t.matches(".*\\b(stop|stopp|halt|cancel|abbrechen|hör auf|hoer auf)\\b.*")) {
            if (!Bot.busy()) return "I'm not doing anything right now.";
            Bot.stop(null);
            return "Stopped.";
        }
        if (mc.player == null || mc.level == null) return "Join a world first.";
        if (!mc.hasSingleplayerServer()) return "I only work in singleplayer – servers ban automated play, and I don't want your account banned.";
        if (t.matches(".*\\b(help|hilfe|was kannst du|what can you do)\\b.*")) {
            return "Try: \"mine 20 diamonds\", \"farm 64 iron\", \"chop 32 wood\", \"go to Home base\", \"go to 100 -250\", \"stop\". German works too: \"farm 100 dias\", \"hol 64 holz\".";
        }
        // mining
        String[][] oreWords = {
                {"diamonds", "dia", "diamond", "diamant"}, {"iron", "eisen", "iron ore"}, {"coal", "kohle"}, {"gold"},
                {"redstone"}, {"lapis"}, {"copper", "kupfer"}, {"emeralds", "emerald", "smaragd"}};
        for (int i = 0; i < oreWords.length; i++) {
            for (String w : oreWords[i]) {
                if (t.contains(w)) {
                    Ore ore = ORES[i];
                    int n = number(t, 10);
                    BlockState sample = BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(ore.blocks().iterator().next())).defaultBlockState();
                    if (!Bot.hasToolFor(mc, sample))
                        return "For " + ore.name() + " I need at least " + (ore.pickaxe().startsWith("i") ? "an " : "a ") + ore.pickaxe() + " pickaxe in your hotbar. Put one there and ask me again.";
                    Bot.start(new Mine(ore, n));
                    return "Mining " + n + " " + ore.name() + ". I'll dig down to Y " + ore.y() + " and strip-mine, keep away from lava, stop when you're low on health and only dig in staircases. Say \"stop\" to stop.";
                }
            }
        }
        if (t.matches(".*\\b(wood|holz|logs?|stämme|staemme|baum|bäume|tree|trees|chop|fäll|fäll?en)\\b.*")) {
            int n = number(t, 32);
            Bot.start(new Chop(n));
            return "Chopping " + n + " logs.";
        }
        // go to
        var m = java.util.regex.Pattern.compile("(?:go to|goto|geh(?:e)? zu|lauf(?:e)? zu|bring mich zu|walk to)\\s+(.+)").matcher(t);
        if (m.find()) {
            String where = m.group(1).trim();
            var xz = java.util.regex.Pattern.compile("(-?\\d+)\\s*[ ,]\\s*(-?\\d+)(?:\\s*[ ,]\\s*(-?\\d+))?").matcher(where);
            if (xz.matches()) {
                int x = Integer.parseInt(xz.group(1)), y, z;
                if (xz.group(3) != null) {
                    y = Integer.parseInt(xz.group(2));
                    z = Integer.parseInt(xz.group(3));
                } else {
                    z = Integer.parseInt(xz.group(2));
                    y = mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    if (y <= mc.level.getMinY()) y = mc.player.getBlockY();
                }
                Bot.start(new GoTo(new BlockPos(x, y, z), x + " " + y + " " + z));
                return "Walking to " + x + " " + y + " " + z + ".";
            }
            String dim = Waypoints.dimension(mc);
            for (Waypoints.Waypoint w : Waypoints.all(mc)) {
                if (w.dimension.equals(dim) && w.name.toLowerCase(Locale.ROOT).contains(where)) {
                    Bot.start(new GoTo(new BlockPos(w.x, w.y, w.z), w.name));
                    return "Walking to " + w.name + " (" + Math.round(w.distance(mc.player.position())) + " m).";
                }
            }
            return "I don't know a waypoint called \"" + where + "\". Add one with M, or give me coordinates like \"go to 100 -250\".";
        }
        return "Sorry, I didn't get that. Type \"help\" to see what I can do.";
    }
}
