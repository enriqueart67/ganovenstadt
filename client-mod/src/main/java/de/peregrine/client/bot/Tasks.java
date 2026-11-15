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

    /** Any stone that drops cobblestone; dug near where you are (y = "a few blocks below you"). */
    public static final Ore STONE = new Ore("cobblestone", Set.of("stone", "cobblestone", "deepslate", "cobbled_deepslate", "blackstone"),
            new String[]{"cobblestone", "cobbled_deepslate", "blackstone"}, Integer.MIN_VALUE, "wooden");

    /** Strip-mines at the ore's best height and digs out every matching ore it can see nearby. */
    public static final class Mine implements Bot.Task {
        private final Ore ore;
        private final int wanted;
        private int start = -1;
        private Navigator nav;
        private BlockPos target; // ore block being mined
        private BlockPos collect;  // where its drop lies
        private int collectTries;
        private final Set<BlockPos> skip = new HashSet<>();
        private Direction dir;
        private String phase = "starting";
        private int steps, y, findTicks = 40;
        private BlockState sample;
        /** Part of a bigger plan: no "done" message, and a missing tool ends the step instead of the whole job. */
        public boolean quiet;

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
                sample = BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(ore.blocks().iterator().next())).defaultBlockState();
                y = ore.y() != Integer.MIN_VALUE ? ore.y() : Math.max(mc.level.getMinY() + 8, p.getBlockY() - 5);
            }
            if (have(mc) - start >= wanted) {
                phase = "done";
                de.peregrine.client.Modules.BLOCK_ESP.setAssist(java.util.Set.of());
                if (!quiet) Bot.say("Done! " + wanted + " " + ore.name() + " are in your inventory.");
                return true;
            }
            if (!Bot.hasToolFor(mc, sample)) { // never had one, or it just broke
                if (quiet) return true; // the plan will craft a new one
                Bot.stop("For " + ore.name() + " I need at least " + (ore.pickaxe().startsWith("i") ? "an " : "a ") + ore.pickaxe() + " pickaxe.");
                return false;
            }
            if (Bot.inventoryFull(mc) && !Bot.makeRoom(mc)) {
                Bot.stop("Your inventory is full of useful things – I stopped. Empty it and ask me again.");
                return false;
            }
            if (p.getFoodData().getFoodLevel() <= 6) {
                Bot.stop("You're hungry – eat something, then ask me again.");
                return false;
            }

            // 1) collecting a drop
            if (collect != null) {
                phase = "picking up";
                if (nav == null) { // walk to where the item really lies (it may have fallen or bounced)
                    BlockPos drop = Bot.dropNear(mc, collect);
                    if (drop == null || ++collectTries > 3) {
                        collect = null;
                        collectTries = 0;
                        return false;
                    }
                    nav = new Navigator(drop, 0, dir);
                    nav.timeout = 20 * 8;
                }
                if (nav.tick(mc)) {
                    nav = null;
                    if (Bot.dropNear(mc, collect) == null) {
                        collect = null;
                        collectTries = 0;
                    }
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
            // 3) look around for ores: close by every tick, further out (ESP engine, 48 blocks) every 2 seconds
            boolean realOre = ore != STONE; // stone is everywhere – no need to search or highlight it
            if (realOre) de.peregrine.client.Modules.BLOCK_ESP.setAssist(ore.blocks());
            BlockPos found = scan(mc, p.blockPosition(), 5);
            if (found == null && realOre && ++findTicks >= 40) {
                findTicks = 0;
                found = de.peregrine.client.feature.OreFinder.nearest(mc, ore.blocks(), p.blockPosition(), 48, skip);
                if (found != null && !quiet) Bot.note("Spotted " + ore.name() + " " + Math.round(Math.sqrt(found.distSqr(p.blockPosition()))) + " blocks away.");
            }
            if (found != null) {
                target = found;
                nav = null;
                return false;
            }
            // 4) get down (or up) to the right height, then tunnel forwards
            BlockPos feet = p.blockPosition();
            if (Math.abs(feet.getY() - y) > 2) {
                phase = "digging " + (feet.getY() > y ? "down" : "up") + " to Y " + y;
                if (nav == null) {
                    int dist = Math.abs(feet.getY() - y);
                    nav = new Navigator(feet.relative(dir, dist).atY(y), 0, dir);
                }
            } else {
                phase = "strip-mining";
                if (nav == null) {
                    steps++;
                    if (steps % 40 == 0) dir = dir.getClockWise(); // turn now and then to cover new ground
                    nav = new Navigator(feet.relative(dir, 3).atY(y), 0, dir);
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
        private int collectTries;
        private final Set<BlockPos> skip = new HashSet<>();
        private String phase = "looking for trees";
        public boolean quiet;

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
                if (!quiet) Bot.say("Done! " + wanted + " logs collected.");
                return true;
            }
            if (Bot.inventoryFull(mc) && !Bot.makeRoom(mc)) {
                Bot.stop("Your inventory is full – I stopped.");
                return false;
            }
            if (collect != null) {
                phase = "picking up";
                if (nav == null) { // walk to where the item really lies (it may have fallen or bounced)
                    BlockPos drop = Bot.dropNear(mc, collect);
                    if (drop == null || ++collectTries > 3) {
                        collect = null;
                        collectTries = 0;
                        return false;
                    }
                    nav = new Navigator(drop, 0, p.getDirection());
                    nav.timeout = 20 * 8;
                }
                if (nav.tick(mc)) {
                    nav = null;
                    if (Bot.dropNear(mc, collect) == null) {
                        collect = null;
                        collectTries = 0;
                    }
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

    /** Turns a sentence (English or German) into jobs. Returns the reply to show. */
    public static String command(String text) {
        return Commands.handle(text);
    }
}
