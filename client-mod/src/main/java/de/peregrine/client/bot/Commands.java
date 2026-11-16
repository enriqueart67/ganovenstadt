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
import java.util.function.Supplier;

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
    /** Jobs one after another. Each job is only built when it starts, so it sees the world as it is then. */
    public static final class Chain implements Bot.Task {
        private final List<Supplier<Bot.Task>> jobs;
        private final List<String> names;
        private Bot.Task cur;
        private int i;

        public Chain(List<Supplier<Bot.Task>> jobs, List<String> names) {
            this.jobs = jobs;
            this.names = names;
        }

        private Bot.Task cur() {
            if (cur == null && i < jobs.size()) cur = jobs.get(i).get();
            return cur;
        }

        @Override
        public String status() {
            Bot.Task c = cur();
            if (c == null) return "Done: " + (names.isEmpty() ? "all jobs" : String.join(", then ", names));
            return (jobs.size() > 1 ? (i + 1) + "/" + jobs.size() + " · " : "") + c.status();
        }

        @Override
        public boolean idle() {
            return cur != null && cur.idle();
        }

        @Override
        public void unstuck(Minecraft mc) {
            if (cur != null) cur.unstuck(mc);
        }

        /** A duel runs right now (it handles its own eating). */
        boolean inFight() {
            return cur instanceof Combat.Duel || cur instanceof Chain c && c.inFight() || cur instanceof Actions.Repeat r && r.inFight();
        }

        /** The duel running right now somewhere inside this chain, or null. */
        public Combat.Duel duel() {
            if (cur instanceof Combat.Duel d) return d;
            if (cur instanceof Chain c) return c.duel();
            if (cur instanceof Actions.Repeat r) return r.duel();
            return null;
        }

        /** The current job failed: move on to the next one. False when there is none. */
        public boolean skip() {
            if (cur instanceof Actions.Repeat r && r.skip()) return true;
            if (cur instanceof Chain c && c.skip()) return true;
            if (i + 1 >= jobs.size()) return false;
            i++;
            cur = null;
            return true;
        }

        @Override
        public boolean tick(Minecraft mc) {
            Bot.Task c = cur();
            if (c == null) return true;
            if (c.tick(mc) && c == cur) {
                Bot.release(mc);
                i++;
                cur = null;
            }
            return i >= jobs.size();
        }
    }

    // ------------------------------------------------------------------ main entry
    private static final Pattern NUMBER = Pattern.compile("(\\d+)");
    private static final Pattern REPEAT = Pattern.compile("(?:^|[,;]\\s*|\\s+(?:and then|then|und dann|dann)\\s+)"
            + "(?:(?:repeat|wiederhole|wiederhol|loop|mach)\\s+(\\d+)\\s*(?:times|time|mal|x)?|(\\d+)\\s*(?:x|mal|times)"
            + "|(forever|immer wieder|immer|endlos|für immer|fuer immer|repeat forever|repeat|wiederhole|loop))\\s*:\\s*");

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
        if (t.matches("(combat log|kampf ?log|kampfprotokoll|fight log)")) {
            java.nio.file.Path f = CombatLog.file();
            de.peregrine.client.gui.EmergencyScreen.open(f.getParent().toFile());
            return java.nio.file.Files.exists(f) ? "Opened the folder – the file is " + f.getFileName() + ". Send it over after a fight, it shows every jump, hit and miss."
                    : "No fight logged yet. After the next fight it is in " + f.getParent() + ".";
        }

        // own commands: remember / forget / list / show
        Matcher rem = Pattern.compile("^(?:merke?|merk dir|speicher(?:e)?|lerne?|remember|save|learn|teach|new command|neuer befehl)\\s+(?:dir\\s+)?(?:den befehl\\s+|command\\s+)?\"?([\\p{L}\\d _<>{}-]{1,32}?)\"?\\s*[:=]\\s*(.+)$").matcher(t);
        if (rem.find()) return remember(rem.group(1), rem.group(2));
        Matcher forget = Pattern.compile("^(?:vergiss|lösche|loesche|forget|delete|remove)\\s+(?:den befehl\\s+|command\\s+)?\"?(.+?)\"?$").matcher(t);
        if (forget.find() && routineKey(forget.group(1).trim()) != null) {
            String k = routineKey(forget.group(1).trim());
            routines().remove(k);
            save();
            return "Forgot \"" + k + "\".";
        }
        Matcher show = Pattern.compile("^(?:show|zeig|zeige)\\s+(?:mir\\s+)?(?:den befehl\\s+|command\\s+)?\"?(.+?)\"?$").matcher(t);
        if (show.find() && routineKey(show.group(1).trim()) != null) {
            String k = routineKey(show.group(1).trim());
            return "\"" + k + "\" = " + routines().get(k);
        }
        if (t.matches("(meine befehle|my commands|routinen|routines|saved|gespeichert.*|liste|list)")) {
            if (routines().isEmpty()) return "You have no own commands yet. Make one: remember starter: get 16 wood, then a stone pickaxe";
            StringBuilder b = new StringBuilder("Your commands: ");
            routines().forEach((k, v) -> b.append("\"").append(k).append("\" = ").append(v).append(";  "));
            return b.toString().trim();
        }

        if (mc.player == null || mc.level == null) return "Join a world first.";
        if (!Bot.allowed(mc)) return Bot.NOT_ALLOWED;
        if (t.matches("(?:gib mir |give me |mach mir |make me |hol mir )?(?:ein |einen |a )?(?:cart ?kit|kit cart|cart pvp kit|kit|pvp kit)(?: bitte| please)?[.!]*"))
            return Kits.cart(mc);
        return run(t);
    }

    /** Parses a command (already lower case) and starts it. Returns the reply. */
    public static String run(String t) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return "Join a world first.";
        List<Supplier<Bot.Task>> jobs = new ArrayList<>();
        List<String> said = new ArrayList<>();
        origin = mc.player.blockPosition();
        String err = parse(norm(t), jobs, said, 0, false);
        if (err != null) return err.substring(0, 1).toUpperCase(Locale.ROOT) + err.substring(1);
        if (jobs.isEmpty()) return "Sorry, I didn't get that. Type \"help\" to see examples.";
        Bot.start(new Chain(jobs, said));
        return jobs.size() == 1 ? cap(said.get(0)) + "." : "Okay, " + jobs.size() + " jobs: " + String.join("; then ", said) + ".";
    }

    /** Checks a command without running it; null when it is fine. */
    public static String check(String t) {
        String err = parse(norm(t), new ArrayList<>(), new ArrayList<>(), 0, true);
        return err;
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    public static String remember(String nameIn, String what) {
        String name = nameIn.trim().toLowerCase(Locale.ROOT).replaceAll("\\{(\\w+)}", "<$1>");
        what = what.trim().replaceAll("\\{(\\w+)}", "<$1>");
        if (name.isEmpty() || what.isEmpty()) return "Write it like this: remember starter: get 16 wood, then a stone pickaxe";
        if (name.matches("(stop|help|status|list|remember|forget|show).*")) return "\"" + name + "\" is already a word I use – pick another name.";
        // check with sample values for the placeholders
        String probe = what;
        for (String ph : placeholders(name)) probe = probe.replace("<" + ph + ">", "1");
        String err = parse(norm(probe), new ArrayList<>(), new ArrayList<>(), 1, true);
        if (err != null) return "I can't save that – " + err;
        routines().put(name, what);
        save();
        List<String> ph = placeholders(name);
        String use = ph.isEmpty() ? name : name.replaceAll("<\\w+>", "10");
        return "Saved. Say \"" + use + "\" and I'll do: " + what;
    }

    public static void forget(String name) {
        if (routines().remove(name) != null) save();
    }

    private static List<String> placeholders(String name) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("<(\\w+)>").matcher(name);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static String routineKey(String name) {
        name = name.toLowerCase(Locale.ROOT);
        if (routines().containsKey(name)) return name;
        for (String k : routines().keySet()) if (k.replaceAll("\\s*<\\w+>", "").trim().equals(name)) return k;
        return null;
    }

    /** "farm 20" against "farm <n>" → the saved steps with 20 filled in; null if no saved command fits. */
    private static String expand(String clause) {
        String own = routines().get(clause);
        if (own != null) return own;
        for (var e : routines().entrySet()) {
            List<String> ph = placeholders(e.getKey());
            if (ph.isEmpty()) continue;
            String rx = "^" + java.util.Arrays.stream(e.getKey().split("<\\w+>", -1)).map(x -> x.isEmpty() ? "" : Pattern.quote(x)).reduce((a, b) -> a + "(.+?)" + b).orElse("") + "$";
            Matcher m = Pattern.compile(rx).matcher(clause);
            if (!m.matches()) continue;
            String body = e.getValue();
            for (int i = 0; i < ph.size(); i++) body = body.replace("<" + ph.get(i) + ">", m.group(i + 1).trim());
            return body;
        }
        return null;
    }

    private static BlockPos origin;

    static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ").replaceAll("[!?]+$", "");
    }

    /** Splits into clauses and turns each into a job. {@code dry} only checks. Returns an error or null. */
    private static String parse(String t, List<Supplier<Bot.Task>> jobs, List<String> said, int depth, boolean dry) {
        if (depth > 4) return "your commands call each other in a loop.";
        // "repeat 3 times: …" / "forever: …" – everything after the colon is repeated
        Matcher rep = REPEAT.matcher(t);
        if (rep.find()) {
            String before = t.substring(0, rep.start()).trim(), body = t.substring(rep.end()).trim();
            if (!before.isEmpty()) {
                String e = parse(before, jobs, said, depth, dry);
                if (e != null) return e;
            }
            int times = rep.group(1) != null ? Integer.parseInt(rep.group(1)) : rep.group(2) != null ? Integer.parseInt(rep.group(2)) : 0;
            List<Supplier<Bot.Task>> inner = new ArrayList<>();
            List<String> innerSaid = new ArrayList<>();
            String e = parse(body, inner, innerSaid, depth + 1, dry);
            if (e != null) return e;
            if (inner.isEmpty()) return "what should I repeat? For example \"repeat 3 times: get 10 wood, then wait 30\".";
            jobs.add(() -> new Actions.Repeat(inner, innerSaid, times));
            said.add((times > 0 ? times + " times" : "over and over (say stop to end it)") + ": " + String.join(", then ", innerSaid));
            return null;
        }
        String[] clauses = t.split("\\s*(?:,\\s*(?:und\\s+)?(?:dann|danach|anschließend|then|after that)\\s+|\\s+(?:und dann|und danach|dann|danach|anschließend|and then|then|after that)\\s+|;|,\\s*(?=(?:hol|hole|mach|bau|farm|mine|geh|gehe|lauf|get|make|craft|go|chop|sammel|sammle|besorg|wait|warte|say|sag|chat|eat|iss|follow|folge|collect|heb|come|komm|drop|wirf|kill|fight|töte)\\b))\\s*");
        for (String c : clauses) {
            c = c.trim().replaceAll("^(und|and|bitte|please)\\s+", "").replaceAll("[.]+$", "").trim();
            if (c.isEmpty()) continue;
            String own = expand(c); // one of your own commands?
            if (own != null) {
                String e = parse(norm(own), jobs, said, depth + 1, dry);
                if (e != null) return e;
                continue;
            }
            // "x und y" with two jobs: try splitting on und/and when the whole clause makes no sense
            int jobsBefore = jobs.size(), saidBefore = said.size();
            String e = clause(c, jobs, said, dry);
            if (e != null) {
                String[] parts = c.split("\\s+(?:und|and)\\s+");
                if (parts.length > 1) {
                    boolean ok = true;
                    for (String p : parts) if (clause(p.trim(), jobs, said, dry) != null) ok = false;
                    if (ok) continue;
                    while (said.size() > saidBefore) said.remove(said.size() - 1);
                    while (jobs.size() > jobsBefore) jobs.remove(jobs.size() - 1);
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

    private static int seconds(String s, int def) {
        Matcher m = Pattern.compile("(\\d+)\\s*(min|minute|minuten|minutes|m)\\b").matcher(s);
        if (m.find()) return Integer.parseInt(m.group(1)) * 60;
        return number(s, def);
    }

    /** One clause → one job (added to {@code jobs}, a sentence to {@code said}). Returns an error or null. */
    private static String clause(String c, List<Supplier<Bot.Task>> jobs, List<String> said, boolean dry) {
        Minecraft mc = Minecraft.getInstance();
        Matcher m;
        // ---- small actions for your own commands
        if ((m = Pattern.compile("^(?:wait|warte|warten|pause|sleep|schlaf)(?:\\s+(?:for\\s+|für\\s+|fuer\\s+)?(.*))?$").matcher(c)).find()) {
            int sec = Math.max(1, Math.min(3600, seconds(m.group(1) == null ? "" : m.group(1), 5)));
            jobs.add(() -> new Actions.Wait(sec));
            said.add("waiting " + sec + " s");
            return null;
        }
        if ((m = Pattern.compile("^(?:say|sag|sage|tell me|sag mir)\\s+(.+)$").matcher(c)).find()) {
            String txt = m.group(1);
            jobs.add(() -> new Actions.Say(txt));
            said.add("saying \"" + txt + "\"");
            return null;
        }
        if ((m = Pattern.compile("^(?:chat|schreib|schreibe|write|run command|befehl)\\s+(.+)$").matcher(c)).find() || c.startsWith("/")) {
            String txt = c.startsWith("/") ? c : m.group(1);
            jobs.add(() -> new Actions.Chat(txt));
            said.add(txt.startsWith("/") ? "running " + txt : "writing \"" + txt + "\" in the chat");
            return null;
        }
        if (c.matches("(eat|iss|essen|iss was|iss etwas|eat something|eat up|satt werden)")) {
            jobs.add(Actions.Eat::new);
            said.add("eating");
            return null;
        }
        if (c.matches("(collect|pick up|pickup|collect items|pick up items|pick everything up|heb auf|heb alles auf|aufheben|sammel alles ein|sammle alles ein|items einsammeln|einsammeln)")) {
            jobs.add(Actions.Collect::new);
            said.add("picking up the items around me");
            return null;
        }
        if (c.matches("(drop junk|throw away junk|clean inventory|clear inventory|wirf müll weg|wirf muell weg|müll wegwerfen|muell wegwerfen|inventar aufräumen|inventar aufraeumen)")) {
            jobs.add(Actions.DropJunk::new);
            said.add("throwing away junk");
            return null;
        }
        if (c.matches("(guard|guard here|guard this spot|bewache|bewach|bewache hier|bewache den platz|wache|wache halten)")) {
            BlockPos at = mc.player != null ? mc.player.blockPosition() : BlockPos.ZERO;
            jobs.add(() -> new Actions.GuardSpot(at));
            said.add("guarding this spot – every monster within 16 blocks, until you say stop");
            return null;
        }
        if (c.matches("(come back|go back|return|komm zurück|komm zurueck|geh zurück|geh zurueck|zurück|zurueck)")) {
            BlockPos at = origin != null ? origin : mc.player != null ? mc.player.blockPosition() : BlockPos.ZERO;
            jobs.add(() -> Actions.back(at));
            said.add("going back to where we started");
            return null;
        }
        if ((m = Pattern.compile("^(?:follow|folge|folg|follow me|folge mir|komm mit)(?:\\s+(?!mir$|me$)(.+?))?(?:\\s+(?:for|für|fuer)\\s+(\\d+\\s*\\w*))?$").matcher(c)).find()) {
            String who = m.group(1) == null || m.group(1).matches("(mir|me|the player|dem spieler)") ? null : m.group(1).trim();
            int sec = m.group(2) == null ? 0 : seconds(m.group(2), 60);
            if (who == null && mc.player != null && c.matches("(follow me|folge mir|komm mit)")) who = null;
            String w = who;
            jobs.add(() -> new Actions.Follow(w, sec));
            said.add("following " + (w == null ? "the nearest player" : w) + (sec > 0 ? " for " + sec + " s" : " until you say stop"));
            return null;
        }

        // ---- walking
        Matcher go = Pattern.compile("^(?:go to|goto|walk to|run to|geh(?:e)? (?:zu|nach|zum|zur)|lauf(?:e)? (?:zu|nach|zum|zur)|bring mich (?:zu|nach|zum|zur)|komm (?:zu|nach|zum|zur))\\s+(?:dem |der |den |the |waypoint |wegpunkt )?(.+)$").matcher(c);
        if (go.find()) {
            String where = go.group(1).trim();
            Matcher xz = Pattern.compile("(-?\\d+)\\s*[ ,/]\\s*(-?\\d+)(?:\\s*[ ,/]\\s*(-?\\d+))?").matcher(where);
            if (xz.matches()) {
                int x = Integer.parseInt(xz.group(1));
                Integer yy = xz.group(3) != null ? Integer.valueOf(xz.group(2)) : null;
                int z = Integer.parseInt(xz.group(3) != null ? xz.group(3) : xz.group(2));
                jobs.add(() -> {
                    Minecraft mm = Minecraft.getInstance();
                    int y = yy != null ? yy : mm.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    if (yy == null && y <= mm.level.getMinY()) y = mm.player.getBlockY();
                    return new Tasks.GoTo(new BlockPos(x, y, z), x + " " + y + " " + z);
                });
                said.add("walking to " + x + (yy != null ? " " + yy : "") + " " + z);
                return null;
            }
            if (dry || mc.level == null) {
                said.add("walk to " + where);
                jobs.add(() -> null);
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
                if (hit == null && (n.contains(where) || where.contains(n) || near(n, where))) hit = w;
            }
            if (hit == null && where.matches("(home|hause|zuhause|haus|base|basis|bett|bed)")) {
                for (Waypoints.Waypoint w : Waypoints.all(mc))
                    if (w.dimension.equals(dim) && w.name.toLowerCase(Locale.ROOT).matches(".*(home|haus|base|basis|bett|bed).*")) hit = w;
            }
            if (hit == null) return "I don't know a waypoint called \"" + where + "\". Add one with M, or give me coordinates like \"go to 100 -250\".";
            Waypoints.Waypoint h = hit;
            jobs.add(() -> new Tasks.GoTo(new BlockPos(h.x, h.y, h.z), h.name));
            said.add("walking to " + hit.name + " (" + Math.round(hit.distance(mc.player.position())) + " m)");
            return null;
        }

        // ---- fighting (players, or mobs by name)
        Matcher fight = Pattern.compile("^(?:fight|duel|pvp|kill|attack|beat|kämpf(?:e)?(?: gegen| mit)?|kaempf(?:e)?(?: gegen| mit)?|töte|toete|besiege|bekämpf(?:e)?|bekaempf(?:e)?|greif(?:e)?|hau)(?:\\s+(.*?))?(?:\\s+an)?$").matcher(c);
        if (fight.find()) {
            String who = fight.group(1) == null ? "" : fight.group(1).trim().replaceAll("^(gegen|mit|den|die|das|the|player|spieler)\\s+", "");
            if (who.matches("(|nearest|nearest player|the nearest|nächsten|naechsten|nächsten spieler|jemand|anyone|someone|everyone|alle|all)")) who = null;
            String w = who;
            jobs.add(() -> new Combat.Duel(w));
            said.add("fighting " + (who == null ? "the nearest player" : who));
            return null;
        }

        // ---- getting / making things
        String what = c.replaceAll("\\b(\\d+)\\b", " ")
                .replaceAll("\\b(bitte|please|mir|me|for me|für mich|fuer mich|ich (will|möchte|moechte|brauche|hätte gern|haette gern)|i (want|need|would like)|can you|could you|kannst du|könntest du|koenntest du|noch|mal|some|ein paar|paar|a few|einen stack|ein stack|a stack|stack|viele|lots of|a lot of|many|stück|stueck|x)\\b", " ")
                .replaceAll("^\\s*(hol|hole|holen|besorg|besorge|besorgen|sammel|sammle|sammeln|farm|farme|farmen|mine|mining|abbauen|bau ab|baue ab|grab|grabe|get|collect|gather|fetch|bring|bring mir|make|craft|crafte|craften|mach|mache|machen|bau|baue|bauen|stell her|stelle her|erstell|erstelle|herstellen|chop|fäll|fälle|faellen|hack|hacke|schmelz|schmelze|smelt)\\b", " ")
                .replaceAll("\\b(ab|her|holen|machen|bauen|herstellen|craften|farmen|sammeln|besorgen)\\s*$", " ")
                .replaceAll("\\b(ein|eine|einen|einem|a|an|the|der|die|das|den|dem|some)\\b", " ")
                .replaceAll("\\s+", " ").trim();
        if (what.isEmpty()) return "what should I get? For example \"get 10 diamonds\".";
        List<String> keys = Items.find(what);
        if (keys.isEmpty()) {
            String guess = guessCommand(c);
            return "I don't know what \"" + what + "\" is." + (guess != null ? " Did you mean \"" + guess + "\"?" : "");
        }
        for (String key : keys) {
            if (!Planner.knows(key))
                return "I can't get " + Items.display(key) + " yet. I can gather wood, stone and all ores, and craft tools, armor, torches, furnaces, chests, buckets and shields – including everything needed for them.";
            int def = key.equals("#log") ? 16 : key.equals("#stone") ? 32 : Planner.SOURCES.containsKey(key) || key.equals("torch") || key.equals("stick") || key.equals("#planks") ? 10 : 1;
            int n = keys.size() > 1 ? 1 : number(c, def);
            jobs.add(() -> new Planner.Get(key, n));
            said.add("getting " + Planner.nice(key, n));
        }
        return null;
    }

    // ------------------------------------------------------------------ typos
    /** Edit distance, for "diamnods" → diamonds and "starer" → starter. */
    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++)
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    static boolean near(String a, String b) {
        int max = Math.max(a.length(), b.length()) <= 5 ? 1 : 2;
        return Math.abs(a.length() - b.length()) <= max && distance(a, b) <= max;
    }

    /** The closest of your own commands to what was typed, or null. */
    private static String guessCommand(String c) {
        String best = null;
        int bd = Integer.MAX_VALUE;
        for (String k : routines().keySet()) {
            String plain = k.replaceAll("\\s*<\\w+>", "").trim();
            String head = c.split(" ")[0];
            int d = distance(head, plain);
            if (d < bd && near(head, plain)) {
                bd = d;
                best = k;
            }
        }
        return best;
    }

    private static String help() {
        return "Tell me what you want, in English or German: \"get 10 diamonds\", \"hol 64 holz\", \"mach mir eine eisenrüstung\", \"craft 16 torches\", "
                + "\"go to Home\", \"geh zu 100 -250\", \"kill zombie\", \"follow Steve\", \"eat\", \"pick up items\", \"wait 30\", \"come back\", \"say done\", \"chat /time set day\". "
                + "Chain them with \"then\" / \"dann\", repeat with \"repeat 5 times: …\" or \"forever: …\". "
                + "Your own commands: \"remember wood <n>: get <n> wood, then come back\" – then say \"wood 32\". Or open MY COMMANDS below. "
                + "\"my commands\" lists them, \"show wood\", \"forget wood\", \"stop\" stops me.";
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
            for (var e : WORDS.entrySet()) // a typo of a known word ("diamnods", "eisn")
                if (e.getKey().length() >= 4 && near(p, e.getKey())) {
                    out.add(e.getValue());
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
                else if (p.length() >= 3 && (name.contains(p) || spaced.contains(p))) score = 10 + Math.min(name.length(), spaced.length()) - p.length();
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
