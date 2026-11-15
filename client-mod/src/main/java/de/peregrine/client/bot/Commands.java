package de.peregrine.client.bot;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.peregrine.client.Store;
import de.peregrine.client.Waypoints;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Understands what you type – English or German, free sentences, several jobs in a row
 * ("get 10 wood and then 5 diamonds"), and your own saved commands ("remember starter: …").
 */
public final class Commands {
    private Commands() {
    }

    // ------------------------------------------------------------------ your own commands
    private static Map<String, String> routines;

    private static Path file() {
        Path h = Store.home();
        return h != null ? h.resolve("peregrine-assistant.json")
                : net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("peregrine-assistant.json");
    }

    public static Map<String, String> routines() {
        if (routines == null) {
            routines = new LinkedHashMap<>();
            try {
                if (Files.exists(file())) {
                    JsonObject o = JsonParser.parseString(Files.readString(file())).getAsJsonObject();
                    if (o.has("commands")) for (var e : o.getAsJsonObject("commands").entrySet()) routines.put(e.getKey(), e.getValue().getAsString());
                }
            } catch (Exception ignored) {
            }
        }
        return routines;
    }

    private static void save() {
        try {
            JsonObject o = new JsonObject(), c = new JsonObject();
            routines().forEach(c::addProperty);
            o.add("commands", c);
            Files.createDirectories(file().getParent());
            Files.writeString(file(), new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ several jobs in a row
    static final class Sequence implements Bot.Task {
        private final List<Bot.Task> jobs;
        private int i;

        Sequence(List<Bot.Task> jobs) {
            this.jobs = jobs;
        }

        @Override
        public String status() {
            return (i + 1) + "/" + jobs.size() + " · " + jobs.get(Math.min(i, jobs.size() - 1)).status();
        }

        @Override
        public boolean tick(Minecraft mc) {
            if (jobs.get(i).tick(mc)) {
                Bot.release(mc);
                i++;
            }
            return i >= jobs.size();
        }
    }

    // ------------------------------------------------------------------ main entry
    private static final Pattern NUMBER = Pattern.compile("(\\d+)");

    /** Handles one message; returns the reply (or null). */
    public static String handle(String text) {
        Minecraft mc = Minecraft.getInstance();
        String t = norm(text);
        if (t.isEmpty()) return null;

        if (t.matches("(stop|stopp|halt|cancel|abbrechen|hör auf|hoer auf|aufhören|stop it|stopp bitte|stop please)[.!]*")) {
            if (!Bot.busy()) return "I'm not doing anything right now.";
            Bot.stop(null);
            return "Stopped.";
        }
        if (t.matches("(help|hilfe|\\?|was kannst du.*|what can you do.*|befehle|commands)")) return help();
        if (t.matches("(status|was machst du.*|what are you doing.*)")) return Bot.status();

        // own commands: remember / forget / list
        Matcher rem = Pattern.compile("^(?:merke?|merk dir|speicher(?:e)?|lerne?|remember|save|learn|teach)\\s+(?:dir\\s+)?(?:den befehl\\s+|command\\s+)?\"?([\\p{L}\\d _-]{1,24}?)\"?\\s*[:=]\\s*(.+)$").matcher(t);
        if (rem.find()) {
            String name = rem.group(1).trim(), what = rem.group(2).trim();
            if (name.isEmpty() || what.isEmpty()) return "Write it like this: remember starter: get 16 wood, then a stone pickaxe";
            List<String> check = new ArrayList<>();
            JOBS.clear();
            String err = parse(what, check, 0, true);
            JOBS.clear();
            if (err != null) return "I can't save that – " + err;
            routines().put(name, what);
            save();
            return "Saved. Say \"" + name + "\" and I'll do: " + what;
        }
        Matcher forget = Pattern.compile("^(?:vergiss|lösche|loesche|forget|delete|remove)\\s+(?:den befehl\\s+|command\\s+)?\"?(.+?)\"?$").matcher(t);
        if (forget.find() && routines().containsKey(forget.group(1).trim())) {
            routines().remove(forget.group(1).trim());
            save();
            return "Forgot \"" + forget.group(1).trim() + "\".";
        }
        if (t.matches("(meine befehle|my commands|routinen|routines|saved|gespeichert.*|liste|list)")) {
            if (routines().isEmpty()) return "You have no own commands yet. Make one: remember starter: get 16 wood, then a stone pickaxe";
            StringBuilder b = new StringBuilder("Your commands: ");
            routines().forEach((k, v) -> b.append("\"").append(k).append("\" = ").append(v).append(";  "));
            return b.toString().trim();
        }

        if (mc.player == null || mc.level == null) return "Join a world first.";
        if (!mc.hasSingleplayerServer()) return "I only work in singleplayer – servers ban automated play, and I don't want your account banned.";

        List<Bot.Task> jobs = new ArrayList<>();
        List<String> said = new ArrayList<>();
        JOBS.clear();
        String err = parse(t, said, 0, false);
        if (err != null) {
            JOBS.clear();
            return err.substring(0, 1).toUpperCase(Locale.ROOT) + err.substring(1);
        }
        jobs.addAll(JOBS);
        JOBS.clear();
        if (jobs.isEmpty()) return "Sorry, I didn't get that. Type \"help\" to see examples.";
        Bot.start(jobs.size() == 1 ? jobs.get(0) : new Sequence(jobs));
        return jobs.size() == 1 ? said.get(0) : "Okay, " + jobs.size() + " jobs: " + String.join("; then ", said) + ".";
    }

    private static final List<Bot.Task> JOBS = new ArrayList<>();

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ").replaceAll("[!?]+$", "");
    }

    /** Splits into clauses and turns each into a job. {@code dry} only checks. Returns an error or null. */
    private static String parse(String t, List<String> said, int depth, boolean dry) {
        if (depth > 3) return "your commands call each other in a loop.";
        String[] clauses = t.split("\\s*(?:,\\s*(?:und\\s+)?(?:dann|danach|anschließend|then|after that)\\s+|\\s+(?:und dann|und danach|dann|danach|anschließend|and then|then|after that)\\s+|;|,\\s*(?=(?:hol|hole|mach|bau|farm|mine|geh|gehe|lauf|get|make|craft|go|chop|sammel|sammle|besorg)\\b))\\s*");
        for (String c : clauses) {
            c = c.trim().replaceAll("^(und|and|bitte|please)\\s+", "").replaceAll("[.]+$", "").trim();
            if (c.isEmpty()) continue;
            // an own command?
            String own = routines().get(c);
            if (own != null) {
                String e = parse(norm(own), said, depth + 1, dry);
                if (e != null) return e;
                continue;
            }
            // "x und y" with two jobs: try splitting on und/and when the whole clause makes no sense
            String e = clause(c, said, dry);
            if (e != null) {
                String[] parts = c.split("\\s+(?:und|and)\\s+");
                if (parts.length > 1) {
                    boolean ok = true;
                    int before = said.size(), jobsBefore = JOBS.size();
                    for (String p : parts) if (clause(p.trim(), said, dry) != null) ok = false;
                    if (ok) continue;
                    while (said.size() > before) said.remove(said.size() - 1);
                    while (JOBS.size() > jobsBefore) JOBS.remove(JOBS.size() - 1);
                }
                return e;
            }
        }
        return null;
    }

    private static int number(String s, int def) {
        Matcher m = NUMBER.matcher(s);
        if (m.find()) return Math.max(1, Math.min(2304, Integer.parseInt(m.group(1))));
        if (s.matches(".*\\b(ein paar|paar|some|a few)\\b.*")) return Math.min(def, 5);
        if (s.matches(".*\\b(ein stack|einen stack|a stack|stack)\\b.*")) return 64;
        if (s.matches(".*\\b(viele|lots of|a lot of|many)\\b.*")) return Math.max(def, 32);
        return def;
    }

    /** One clause → one job (added to {@link #JOBS} and a sentence to {@code said}). */
    private static String clause(String c, List<String> said, boolean dry) {
        Minecraft mc = Minecraft.getInstance();
        // walking
        Matcher go = Pattern.compile("^(?:go to|goto|walk to|run to|geh(?:e)? (?:zu|nach|zum|zur)|lauf(?:e)? (?:zu|nach|zum|zur)|bring mich (?:zu|nach|zum|zur)|komm (?:zu|nach|zum|zur))\\s+(?:dem |der |den |the |waypoint |wegpunkt )?(.+)$").matcher(c);
        if (go.find()) {
            String where = go.group(1).trim();
            Matcher xz = Pattern.compile("(-?\\d+)\\s*[ ,/]\\s*(-?\\d+)(?:\\s*[ ,/]\\s*(-?\\d+))?").matcher(where);
            if (xz.matches()) {
                if (dry || mc.level == null) {
                    said.add("walk to " + where);
                    JOBS.add(null);
                    return null;
                }
                int x = Integer.parseInt(xz.group(1)), y, z;
                if (xz.group(3) != null) {
                    y = Integer.parseInt(xz.group(2));
                    z = Integer.parseInt(xz.group(3));
                } else {
                    z = Integer.parseInt(xz.group(2));
                    y = mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    if (y <= mc.level.getMinY()) y = mc.player.getBlockY();
                }
                JOBS.add(new Tasks.GoTo(new BlockPos(x, y, z), x + " " + y + " " + z));
                said.add("walking to " + x + " " + y + " " + z);
                return null;
            }
            if (dry || mc.level == null) {
                said.add("walk to " + where);
                JOBS.add(null);
                return null;
            }
            String dim = Waypoints.dimension(mc);
            Waypoints.Waypoint hit = null;
            for (Waypoints.Waypoint w : Waypoints.all(mc)) {
                String n = w.name.toLowerCase(Locale.ROOT);
                if (!w.dimension.equals(dim)) continue;
                if (n.equals(where)) {
                    hit = w;
                    break;
                }
                if (hit == null && (n.contains(where) || where.contains(n))) hit = w;
            }
            if (hit == null && where.matches("(home|hause|zuhause|haus|base|basis|bett|bed)")) {
                for (Waypoints.Waypoint w : Waypoints.all(mc))
                    if (w.dimension.equals(dim) && w.name.toLowerCase(Locale.ROOT).matches(".*(home|haus|base|basis|bett|bed).*")) hit = w;
            }
            if (hit == null) return "I don't know a waypoint called \"" + where + "\". Add one with M, or give me coordinates like \"go to 100 -250\".";
            JOBS.add(new Tasks.GoTo(new BlockPos(hit.x, hit.y, hit.z), hit.name));
            said.add("walking to " + hit.name + " (" + Math.round(hit.distance(mc.player.position())) + " m)");
            return null;
        }

        // getting / making things
        String what = c.replaceAll("\\b(\\d+)\\b", " ")
                .replaceAll("\\b(bitte|please|mir|me|for me|für mich|fuer mich|ich (will|möchte|moechte|brauche|hätte gern|haette gern)|i (want|need|would like)|can you|kannst du|könntest du|koenntest du|noch|mal|some|ein paar|paar|a few|einen stack|ein stack|a stack|stack|viele|lots of|a lot of|many|stück|stueck|x)\\b", " ")
                .replaceAll("^\\s*(hol|hole|holen|besorg|besorge|besorgen|sammel|sammle|sammeln|farm|farme|farmen|mine|mining|abbauen|bau ab|baue ab|grab|grabe|get|collect|gather|fetch|bring|bring mir|make|craft|crafte|craften|mach|mache|machen|bau|baue|bauen|stell her|stelle her|erstell|erstelle|herstellen|chop|fäll|fälle|faellen|hack|hacke|schmelz|schmelze|smelt)\\b", " ")
                .replaceAll("\\b(ab|her|holen|machen|bauen|herstellen|craften|farmen|sammeln|besorgen)\\s*$", " ")
                .replaceAll("\\b(ein|eine|einen|einem|a|an|the|der|die|das|den|dem|some)\\b", " ")
                .replaceAll("\\s+", " ").trim();
        if (what.isEmpty()) return "what should I get? For example \"get 10 diamonds\".";
        List<String> keys = Items.find(what);
        if (keys.isEmpty()) return "I don't know what \"" + what + "\" is.";
        for (String key : keys) {
            if (!Planner.knows(key))
                return "I can't get " + Items.display(key) + " yet. I can gather wood, stone and all ores, and craft tools, armor, torches, furnaces, chests, buckets and shields – including everything needed for them.";
            int def = key.equals("#log") ? 16 : key.equals("#stone") ? 32 : Planner.SOURCES.containsKey(key) || key.equals("torch") || key.equals("stick") || key.equals("#planks") ? 10 : 1;
            int n = keys.size() > 1 ? 1 : number(c, def);
            JOBS.add(dry ? null : new Planner.Get(key, n));
            said.add("getting " + Planner.nice(key, n));
        }
        return null;
    }

    private static String help() {
        return "Just tell me what you want, in English or German. Examples: \"get 10 diamonds\" (I make the pickaxes I need first), "
                + "\"hol 64 holz\", \"mach mir eine eisenrüstung\", \"craft 16 torches\", \"go to Home\", \"geh zu 100 -250\", "
                + "\"hol 20 holz und dann 5 eisenbarren\". Save your own: \"remember starter: get 16 wood, then a stone pickaxe\" – then just say \"starter\". "
                + "\"my commands\" lists them, \"forget starter\" deletes one, \"stop\" stops me.";
    }

    // ------------------------------------------------------------------ item names
    static final class Items {
        private static final Map<String, String> WORDS = new LinkedHashMap<>();

        static {
            String[][] w = {
                    {"#log", "holz", "wood", "logs", "log", "stämme", "staemme", "stamm", "baumstämme", "baumstamm", "bäume", "baeume", "baum", "trees", "tree"},
                    {"#planks", "bretter", "holzbretter", "planks", "plank", "planken"},
                    {"#stone", "stein", "steine", "stone", "cobble", "cobblestone", "bruchstein", "bruchsteine", "pflasterstein"},
                    {"#coal", "kohle", "coal", "holzkohle", "charcoal"},
                    {"stick", "stöcke", "stoecke", "stock", "stick", "sticks", "stäbe", "staebe"},
                    {"diamond", "dias", "dia", "diamant", "diamanten", "diamond", "diamonds", "diamis"},
                    {"raw_iron", "eisen", "eisenerz", "iron", "iron ore", "raw iron", "rohes eisen", "roheisen"},
                    {"iron_ingot", "eisenbarren", "iron ingot", "iron ingots", "ingots", "barren"},
                    {"raw_gold", "gold", "golderz", "gold ore", "raw gold", "rohgold"},
                    {"gold_ingot", "goldbarren", "gold ingot", "gold ingots"},
                    {"raw_copper", "kupfer", "copper", "kupfererz", "raw copper"},
                    {"copper_ingot", "kupferbarren", "copper ingot", "copper ingots"},
                    {"redstone", "redstone", "rotstein"},
                    {"lapis_lazuli", "lapis", "lapislazuli", "lapis lazuli"},
                    {"emerald", "smaragd", "smaragde", "emerald", "emeralds"},
                    {"crafting_table", "werkbank", "crafting table", "craftingtable", "werktisch"},
                    {"furnace", "ofen", "furnace", "schmelzofen"},
                    {"torch", "fackel", "fackeln", "torch", "torches"},
                    {"chest", "truhe", "kiste", "chest"},
                    {"bucket", "eimer", "bucket"},
                    {"shield", "schild", "shield"},
                    {"shears", "schere", "shears"},
                    {"ladder", "leiter", "leitern", "ladder", "ladders"},
            };
            for (String[] row : w) for (int i = 1; i < row.length; i++) WORDS.put(row[i], row[0]);
        }

        private static final String[][] MATERIAL = {
                {"wooden", "holz", "wood", "wooden", "hölzern", "hoelzern"},
                {"stone", "stein", "stone"},
                {"iron", "eisen", "iron"},
                {"golden", "gold", "golden"},
                {"diamond", "diamant", "dia", "diamond", "dias"},
                {"netherite", "netherit", "netherite"},
        };
        private static final String[][] TOOL = {
                {"pickaxe", "spitzhacke", "spitzhacken", "pickaxe", "pickaxes", "pick", "picke", "hacke", "hacken"},
                {"axe", "axt", "äxte", "axe", "beil"},
                {"shovel", "schaufel", "spaten", "shovel"},
                {"sword", "schwert", "sword"},
                {"hoe", "feldhacke", "hoe"},
                {"helmet", "helm", "helmet"},
                {"chestplate", "brustpanzer", "brustplatte", "chestplate", "harnisch"},
                {"leggings", "hose", "beinschutz", "leggings", "beinschienen"},
                {"boots", "stiefel", "schuhe", "boots"},
                {"armor", "rüstung", "ruestung", "armor", "armour", "rüstungsset", "full armor"},
        };

        /** Item keys for a phrase ("eisenrüstung" → helmet, chestplate, leggings, boots). */
        static List<String> find(String phrase) {
            String p = phrase.trim();
            List<String> out = new ArrayList<>();
            // material + tool: "eisenhacke", "iron pickaxe", "diamant schwert", "stone pick"
            for (String[] t : TOOL) {
                for (int ti = 1; ti < t.length; ti++) {
                    String tw = t[ti];
                    if (!p.endsWith(tw)) continue;
                    String rest = p.substring(0, p.length() - tw.length()).replaceAll("[- ]+$", "").trim();
                    if (t[0].equals("pickaxe") && tw.startsWith("hacke") && rest.endsWith("feld")) continue; // feldhacke = hoe
                    String mat = rest.isEmpty() ? "" : material(rest);
                    if (mat == null) continue;
                    if (mat.isEmpty()) mat = t[0].equals("armor") || t[0].matches("helmet|chestplate|leggings|boots") ? "iron" : "stone";
                    if (t[0].equals("armor")) {
                        if (mat.equals("wooden") || mat.equals("stone")) mat = "iron";
                        for (String piece : new String[]{"helmet", "chestplate", "leggings", "boots"}) out.add(mat + "_" + piece);
                    } else {
                        if (t[0].matches("helmet|chestplate|leggings|boots") && (mat.equals("wooden") || mat.equals("stone"))) mat = "iron";
                        out.add(mat + "_" + t[0]);
                    }
                    return out;
                }
            }
            String w = WORDS.get(p);
            if (w == null && p.endsWith("s")) w = WORDS.get(p.substring(0, p.length() - 1));
            if (w == null && p.endsWith("n")) w = WORDS.get(p.substring(0, p.length() - 1));
            if (w != null) {
                out.add(w);
                return out;
            }
            // any item by its name in the game's language or its id ("iron block", "Eisenblock", "iron_block")
            String best = null;
            int bestScore = Integer.MAX_VALUE;
            for (Item item : BuiltInRegistries.ITEM) {
                String id = BuiltInRegistries.ITEM.getKey(item).getPath();
                String name;
                try {
                    name = new ItemStack(item).getHoverName().getString().toLowerCase(Locale.ROOT);
                } catch (Exception e) {
                    name = id;
                }
                String spaced = id.replace('_', ' ');
                int score;
                if (name.equals(p) || spaced.equals(p) || id.equals(p)) score = 0;
                else if (name.equals(p + "s") || spaced.equals(p + "s") || (p.endsWith("s") && spaced.equals(p.substring(0, p.length() - 1)))) score = 1;
                else if (name.contains(p) || spaced.contains(p)) score = 10 + Math.min(name.length(), spaced.length()) - p.length();
                else continue;
                if (score < bestScore) {
                    bestScore = score;
                    best = id;
                }
            }
            if (best != null) out.add(Planner.key(best));
            return out;
        }

        private static String material(String word) {
            for (String[] m : MATERIAL) for (int i = 1; i < m.length; i++) if (word.equals(m[i]) || word.equals(m[i] + "e") || word.equals(m[i] + "en") || word.equals(m[i] + "ne")) return m[0];
            return null;
        }

        static String display(String key) {
            return Planner.nice(key, 2).replaceFirst("^2 ", "");
        }
    }
}
