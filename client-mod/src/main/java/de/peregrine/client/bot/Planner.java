package de.peregrine.client.bot;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Works out how to get an item from what you have: which tools are needed, what to gather, craft and smelt –
 * e.g. diamonds → wood → crafting table → wooden pickaxe → stone → stone pickaxe → iron → furnace → iron pickaxe →
 * down to Y -58. The plan is recomputed after every step from the real inventory, so it adapts when a tool
 * breaks or you add things yourself.
 *
 * <p>Crafting and smelting happen directly in your inventory (like the recipe book), using the real
 * ingredients. 3×3 recipes need a crafting table in the inventory, smelting needs a furnace and fuel and takes
 * the normal 10 seconds per item. Singleplayer only.</p>
 */
public final class Planner {
    private Planner() {
    }

    // ------------------------------------------------------------------ item groups
    /** Inventory key of an item: groups for things where any variant works, otherwise the item id. */
    static String key(String id) {
        if (id.endsWith("_log") || id.endsWith("_stem")) return "#log";
        if (id.endsWith("_planks")) return "#planks";
        if (id.equals("cobblestone") || id.equals("cobbled_deepslate") || id.equals("blackstone")) return "#stone";
        if (id.equals("coal") || id.equals("charcoal")) return "#coal";
        return id;
    }

    static boolean matches(String key, String id) {
        return key.equals(key(id)) || key.equals(id);
    }

    public static String nice(String key, int n) {
        String s = switch (key) {
            case "#log" -> n == 1 ? "log" : "logs";
            case "#planks" -> "planks";
            case "#stone" -> "cobblestone";
            case "#coal" -> "coal";
            case "raw_iron" -> "raw iron";
            case "raw_gold" -> "raw gold";
            case "raw_copper" -> "raw copper";
            case "lapis_lazuli" -> "lapis";
            case "diamond" -> n == 1 ? "diamond" : "diamonds";
            case "emerald" -> n == 1 ? "emerald" : "emeralds";
            case "stick" -> n == 1 ? "stick" : "sticks";
            case "torch" -> n == 1 ? "torch" : "torches";
            default -> key.replace('_', ' ') + (n > 1 && !key.endsWith("s") && !key.endsWith("_ingot") ? "s" : "");
        };
        if (key.endsWith("_ingot")) s = key.replace("_ingot", "").replace('_', ' ') + (n == 1 ? " ingot" : " ingots");
        if (n == 1 && (key.endsWith("leggings") || key.endsWith("boots") || key.equals("shears"))) return key.equals("shears") ? "shears" : s; // no "a" before plurals
        return n == 1 && !key.startsWith("#") && !Set.of("coal", "redstone").contains(key) ? (startsWithVowel(s) ? "an " : "a ") + s : n + " " + s;
    }

    private static boolean startsWithVowel(String s) {
        return !s.isEmpty() && "aeiou".indexOf(s.charAt(0)) >= 0;
    }

    // ------------------------------------------------------------------ knowledge
    record Recipe(String out, int yield, Map<String, Integer> in, boolean table) {
    }

    record Smelt(String out, String in) {
    }

    /** Where raw things come from: the ore (null = wood) and how many to gather at least when needed as an ingredient. */
    record Source(Tasks.Ore ore, int min) {
    }

    static final Map<String, Recipe> RECIPES = new LinkedHashMap<>();
    static final Map<String, Smelt> SMELTS = new HashMap<>();
    static final Map<String, Source> SOURCES = new HashMap<>();
    static final String[] PICK_TIERS = {"wooden", "stone", "iron", "diamond", "netherite"};

    private static void recipe(String out, int yield, boolean table, Object... in) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < in.length; i += 2) m.put((String) in[i], (Integer) in[i + 1]);
        RECIPES.put(out, new Recipe(out, yield, m, table));
    }

    static {
        recipe("#planks", 4, false, "#log", 1);
        recipe("stick", 4, false, "#planks", 2);
        recipe("crafting_table", 1, false, "#planks", 4);
        recipe("chest", 1, true, "#planks", 8);
        recipe("torch", 4, false, "#coal", 1, "stick", 1);
        recipe("furnace", 1, true, "#stone", 8);
        recipe("ladder", 3, true, "stick", 7);
        recipe("bucket", 1, true, "iron_ingot", 3);
        recipe("shears", 1, false, "iron_ingot", 2);
        recipe("shield", 1, true, "#planks", 6, "iron_ingot", 1);
        recipe("iron_block", 1, true, "iron_ingot", 9);
        recipe("diamond_block", 1, true, "diamond", 9);
        String[][] mats = {{"wooden", "#planks"}, {"stone", "#stone"}, {"iron", "iron_ingot"}, {"golden", "gold_ingot"}, {"diamond", "diamond"}};
        for (String[] m : mats) {
            recipe(m[0] + "_pickaxe", 1, true, m[1], 3, "stick", 2);
            recipe(m[0] + "_axe", 1, true, m[1], 3, "stick", 2);
            recipe(m[0] + "_shovel", 1, true, m[1], 1, "stick", 2);
            recipe(m[0] + "_sword", 1, true, m[1], 2, "stick", 1);
            recipe(m[0] + "_hoe", 1, true, m[1], 2, "stick", 2);
        }
        for (String[] m : new String[][]{{"iron", "iron_ingot"}, {"golden", "gold_ingot"}, {"diamond", "diamond"}}) {
            recipe(m[0] + "_helmet", 1, true, m[1], 5);
            recipe(m[0] + "_chestplate", 1, true, m[1], 8);
            recipe(m[0] + "_leggings", 1, true, m[1], 7);
            recipe(m[0] + "_boots", 1, true, m[1], 4);
        }
        SMELTS.put("iron_ingot", new Smelt("iron_ingot", "raw_iron"));
        SMELTS.put("gold_ingot", new Smelt("gold_ingot", "raw_gold"));
        SMELTS.put("copper_ingot", new Smelt("copper_ingot", "raw_copper"));
        SOURCES.put("#log", new Source(null, 4));
        SOURCES.put("#stone", new Source(Tasks.STONE, 12));
        for (Tasks.Ore o : Tasks.ORES) SOURCES.put(key(o.items()[0]), new Source(o, 1));
    }

    /** Can the assistant get this at all? */
    public static boolean knows(String key) {
        return RECIPES.containsKey(key) || SMELTS.containsKey(key) || SOURCES.containsKey(key);
    }

    // ------------------------------------------------------------------ planning
    enum Kind {GATHER, CRAFT, SMELT}

    record Step(Kind kind, String key, int amount, Recipe recipe, Source source, String fuel, int fuelAmount) {
        String describe() {
            return switch (kind) {
                case GATHER -> source.ore() == null ? "chop " + nice(key, amount) : "mine " + nice(key, amount);
                case CRAFT -> "craft " + nice(key, amount);
                case SMELT -> "smelt " + nice(key, amount);
            };
        }
    }

    /** Snapshot of the real inventory, by key. */
    static Map<String, Integer> snapshot(Minecraft mc) {
        Map<String, Integer> inv = new HashMap<>();
        var real = mc.player.getInventory();
        for (int i = 0; i < real.getContainerSize(); i++) {
            ItemStack it = real.getItem(i);
            if (it.isEmpty()) continue;
            inv.merge(key(BuiltInRegistries.ITEM.getKey(it.getItem()).getPath()), it.getCount(), Integer::sum);
        }
        return inv;
    }

    static int have(Map<String, Integer> inv, String key) {
        return inv.getOrDefault(key, 0);
    }

    static boolean hasPick(Map<String, Integer> inv, String tier) {
        boolean reached = false;
        for (String t : PICK_TIERS) {
            if (t.equals(tier)) reached = true;
            if (reached && have(inv, t + "_pickaxe") > 0) return true;
        }
        return false;
    }

    /** Fills {@code steps} with what has to happen so that {@code inv} holds {@code need} of {@code key}; returns an error or null. */
    static String ensure(Map<String, Integer> inv, String key, int need, List<Step> steps, int depth) {
        if (depth > 16 || steps.size() > 60) return "That would take too many steps.";
        if (key.startsWith("pick:")) {
            String tier = key.substring(5);
            return hasPick(inv, tier) ? null : ensure(inv, tier + "_pickaxe", 1, steps, depth + 1);
        }
        int deficit = need - have(inv, key);
        if (deficit <= 0) return null;

        Recipe r = RECIPES.get(key);
        if (r != null) {
            int times = (deficit + r.yield() - 1) / r.yield();
            for (int round = 0; ; round++) {
                if (r.table()) {
                    String e = ensure(inv, "crafting_table", 1, steps, depth + 1);
                    if (e != null) return e;
                }
                for (var in : r.in().entrySet()) {
                    String e = ensure(inv, in.getKey(), in.getValue() * times, steps, depth + 1);
                    if (e != null) return e;
                }
                boolean ok = !r.table() || have(inv, "crafting_table") > 0;
                for (var in : r.in().entrySet()) ok &= have(inv, in.getKey()) >= in.getValue() * times;
                if (ok) break;
                if (round > 5) return "I keep running short of materials for " + nice(key, 1) + ".";
            }
            for (var in : r.in().entrySet()) inv.merge(in.getKey(), -in.getValue() * times, Integer::sum);
            inv.merge(key, r.yield() * times, Integer::sum);
            steps.add(new Step(Kind.CRAFT, key, r.yield() * times, r, null, null, 0));
            return null;
        }

        Smelt sm = SMELTS.get(key);
        if (sm != null) {
            int times = deficit;
            String fuel;
            int fuelN;
            for (int round = 0; ; round++) {
                String e = ensure(inv, "furnace", 1, steps, depth + 1);
                if (e == null) e = ensure(inv, sm.in(), times, steps, depth + 1);
                if (e != null) return e;
                int coalN = (times + 7) / 8;
                if (have(inv, "#coal") >= coalN) {
                    fuel = "#coal";
                    fuelN = coalN;
                } else {
                    fuel = "#planks";
                    fuelN = (int) Math.ceil(times / 1.5);
                    e = ensure(inv, fuel, fuelN, steps, depth + 1);
                    if (e != null) return e;
                }
                if (have(inv, "furnace") > 0 && have(inv, sm.in()) >= times && have(inv, fuel) >= fuelN) break;
                if (round > 5) return "I keep running short of materials for smelting.";
            }
            inv.merge(sm.in(), -times, Integer::sum);
            inv.merge(fuel, -fuelN, Integer::sum);
            inv.merge(key, times, Integer::sum);
            steps.add(new Step(Kind.SMELT, key, times, null, null, fuel, fuelN));
            return null;
        }

        Source src = SOURCES.get(key);
        if (src != null) {
            if (src.ore() != null) {
                String e = ensure(inv, "pick:" + src.ore().pickaxe(), 1, steps, depth + 1);
                if (e != null) return e;
            }
            int amount = depth == 0 ? deficit : Math.max(deficit, src.min());
            inv.merge(key, amount, Integer::sum);
            steps.add(new Step(Kind.GATHER, key, amount, null, src, null, 0));
            return null;
        }
        return "I don't know how to get " + nice(key, 2).replaceFirst("^2 ", "") + " yet.";
    }

    /** Readable plan; repeated small steps (e.g. planks made three times) are shown once with the total. */
    static String describe(List<Step> steps) {
        Map<String, Integer> total = new LinkedHashMap<>();
        Map<String, Step> first = new LinkedHashMap<>();
        for (Step s : steps) {
            String k = s.kind() + ":" + s.key();
            total.merge(k, s.amount(), Integer::sum);
            first.putIfAbsent(k, s);
        }
        List<String> parts = new ArrayList<>();
        for (var e : first.entrySet()) {
            Step s = e.getValue();
            parts.add(new Step(s.kind(), s.key(), total.get(e.getKey()), s.recipe(), s.source(), s.fuel(), s.fuelAmount()).describe());
        }
        return String.join(" → ", parts);
    }

    // ------------------------------------------------------------------ the job
    /** Get {@code amount} more of something, doing whatever is needed first. */
    public static final class Get implements Bot.Task {
        private final String key;
        private final int amount;
        private int target = -1, wait, turns, sameSteps;
        private Bot.Task sub;
        private String lastStep, lastInv;

        public Get(String key, int amount) {
            this.key = key;
            this.amount = amount;
        }

        @Override
        public String status() {
            String head = "Getting " + nice(key, amount);
            return sub == null ? head : head + " · " + sub.status();
        }

        @Override
        public boolean tick(Minecraft mc) {
            if (sub != null) {
                if (sub.tick(mc)) {
                    sub = null;
                    wait = 6; // let the inventory sync before planning again
                }
                return false;
            }
            if (wait > 0) {
                wait--;
                return false;
            }
            Map<String, Integer> inv = snapshot(mc);
            if (target < 0) target = have(inv, key) + amount;
            List<Step> steps = new ArrayList<>();
            String err = ensure(inv, key, target, steps, 0);
            if (err != null) {
                Bot.stop(err);
                return false;
            }
            if (steps.isEmpty()) {
                Bot.say("Done! You have " + nice(key, amount) + " more now.");
                return true;
            }
            if (turns == 0 && steps.size() > 1) Bot.say("Plan: " + describe(steps) + ".");
            if (++turns > 150) {
                Bot.stop("This is taking far too many steps – I stopped.");
                return false;
            }
            Step step = steps.get(0);
            String invSig = snapshot(mc).toString();
            if (step.describe().equals(lastStep) && invSig.equals(lastInv)) {
                if (++sameSteps >= 3) {
                    Bot.stop("I can't make progress with \"" + step.describe() + "\" – I stopped.");
                    return false;
                }
            } else sameSteps = 0;
            lastStep = step.describe();
            lastInv = invSig;
            sub = switch (step.kind()) {
                case GATHER -> {
                    if (step.source().ore() == null) {
                        Tasks.Chop c = new Tasks.Chop(step.amount());
                        c.quiet = true;
                        yield c;
                    }
                    Tasks.Mine m = new Tasks.Mine(step.source().ore(), step.amount());
                    m.quiet = true;
                    yield m;
                }
                case CRAFT -> new Craft(step);
                case SMELT -> new SmeltJob(step);
            };
            return false;
        }
    }

    // ------------------------------------------------------------------ crafting & smelting in the inventory
    private static Item item(String id) {
        return BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(id));
    }

    /** Takes {@code n} items of a key from the server inventory; returns the id of the first one taken, or null if there aren't enough. */
    private static String take(net.minecraft.world.entity.player.Inventory inv, String key, int n) {
        int found = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (!it.isEmpty() && matches(key, BuiltInRegistries.ITEM.getKey(it.getItem()).getPath())) found += it.getCount();
        }
        if (found < n) return null;
        String first = null;
        for (int i = 0; i < 36 && n > 0; i++) {
            ItemStack it = inv.getItem(i);
            if (it.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(it.getItem()).getPath();
            if (!matches(key, id)) continue;
            if (first == null) first = id;
            int k = Math.min(n, it.getCount());
            it.shrink(k);
            n -= k;
        }
        return first;
    }

    private static boolean has(net.minecraft.world.entity.player.Inventory inv, String key, int n) {
        int found = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (!it.isEmpty() && matches(key, BuiltInRegistries.ITEM.getKey(it.getItem()).getPath())) found += it.getCount();
        }
        return found >= n;
    }

    private static void give(net.minecraft.world.entity.player.Inventory inv, String id, int n) {
        Item it = item(id);
        if (it == null || it == Items.AIR) return;
        inv.add(new ItemStack(it, n));
    }

    /** Planks of the same wood as the log (oak log → oak planks, crimson stem → crimson planks). */
    private static String planksFor(String log) {
        String p = log.replace("stripped_", "").replace("_log", "_planks").replace("_stem", "_planks");
        Item it = item(p);
        return it == null || it == Items.AIR ? "oak_planks" : p;
    }

    static final class Craft implements Bot.Task {
        private final Step step;
        private final int times;
        private int done, delay;

        Craft(Step step) {
            this.step = step;
            this.times = step.amount() / step.recipe().yield();
        }

        @Override
        public String status() {
            return "crafting " + nice(step.key(), step.amount());
        }

        @Override
        public boolean tick(Minecraft mc) {
            if (done == 0 && delay == 0) Bot.note("Crafting " + nice(step.key(), step.amount()) + ".");
            if (++delay % 5 != 0) return false; // a little pause per craft, like clicking it
            if (done >= times) return true;
            if (Bot.inventoryFull(mc)) Bot.makeRoom(mc);
            Recipe r = step.recipe();
            Bot.onServer(mc, sp -> {
                var inv = sp.getInventory();
                if (r.table() && !has(inv, "crafting_table", 1)) return;
                for (var in : r.in().entrySet()) if (!has(inv, in.getKey(), in.getValue())) return;
                String firstLog = null;
                for (var in : r.in().entrySet()) {
                    String t = take(inv, in.getKey(), in.getValue());
                    if (in.getKey().equals("#log")) firstLog = t;
                }
                String out = r.out().equals("#planks") ? planksFor(firstLog == null ? "oak_log" : firstLog) : r.out();
                give(inv, out, r.yield());
            });
            done++;
            return false;
        }
    }

    static final class SmeltJob implements Bot.Task {
        private final Step step;
        private int done, ticks;
        private double fuelLeft;

        SmeltJob(Step step) {
            this.step = step;
        }

        @Override
        public String status() {
            return "smelting " + nice(step.key(), step.amount()) + ": " + done + " / " + step.amount();
        }

        @Override
        public boolean tick(Minecraft mc) {
            if (ticks == 0) Bot.note("Smelting " + nice(step.key(), step.amount()) + " in your furnace (10 s each).");
            Bot.release(mc);
            if (++ticks % 200 != 0) return false; // vanilla furnace speed
            if (done >= step.amount()) return true;
            Smelt sm = SMELTS.get(step.key());
            boolean needFuel = fuelLeft < 1;
            String fuel = step.fuel();
            Bot.onServer(mc, sp -> {
                var inv = sp.getInventory();
                if (!has(inv, "furnace", 1) || !has(inv, sm.in(), 1)) return;
                if (needFuel && take(inv, fuel, 1) == null) return;
                take(inv, sm.in(), 1);
                give(inv, sm.out(), 1);
            });
            if (needFuel) fuelLeft += fuel.equals("#coal") ? 8 : 1.5;
            fuelLeft -= 1;
            done++;
            return done >= step.amount();
        }
    }
}
