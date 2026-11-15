package de.peregrine.client.bot;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral;
import com.anthropic.models.beta.messages.BetaContentBlock;
import com.anthropic.models.beta.messages.BetaContentBlockParam;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaMessageParam;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.BetaTextBlockParam;
import com.anthropic.models.beta.messages.BetaTool;
import com.anthropic.models.beta.messages.BetaToolResultBlockParam;
import com.anthropic.models.beta.messages.BetaToolUseBlock;
import com.anthropic.models.beta.messages.MessageCreateParams;
import de.peregrine.client.PeregrineClient;
import de.peregrine.client.Waypoints;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Claude as the assistant's brain. It reads what you type plus a short picture of the game (health, inventory,
 * who is around, waypoints, what the bot is doing) and drives the bot through tools that use the same command
 * words as the chat. When it asks to be told, a finished or failed job comes back to it, so it can plan the next
 * step on its own (a few times per request, so it never runs away with your API credit).
 */
final class ClaudeBrain {
    private ClaudeBrain() {
    }

    static final String MODEL = "claude-opus-5-5";
    private static final int MAX_AUTO_TURNS = 6, MAX_HISTORY = 40;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Peregrine Claude");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static AnthropicClient client;
    private static final List<BetaMessageParam> history = new ArrayList<>();
    private static int autoTurns;

    static boolean busy() {
        return BUSY.get();
    }

    static synchronized void reset() {
        client = null;
        history.clear();
    }

    private static synchronized AnthropicClient client() {
        if (client == null) client = AnthropicOkHttpClient.builder().apiKey(Claude.key()).build();
        return client;
    }

    // ------------------------------------------------------------------ what Claude knows
    private static final String SYSTEM = """
            You are the Peregrine assistant inside Minecraft Java Edition. You control the player's own character \
            through a bot that walks, mines, chops, crafts, smelts, fights and follows. You see a short status of the \
            game before every message from the player.

            How to act:
            - Use the tool run_steps to do things. Steps are plain command words, joined with ", then ":
              get <n> <item> (wood, stone, coal, iron, gold, diamonds, redstone, lapis, emeralds, copper, any tool or \
            armor like "iron pickaxe" or "diamond armor", torches, furnace, chest, bucket, shield … – the bot crafts \
            everything needed on the way), go to <waypoint> | go to <x> <z> | go to <x> <y> <z>, \
            fight <player name> | kill <mob type>, follow <player> [for <seconds>], eat, pick up items, drop junk, \
            come back, wait <seconds>, say <text>, chat <text> (writes in the game chat; "/..." runs a command), \
            repeat <n> times: <steps>, forever: <steps>, and the player's saved commands by name.
            - Plan big goals as a sequence. Set notify=true when you want to hear how it went, then continue with \
            the next part when the result comes back. Do not repeat a step that failed the same way – change the plan.
            - The bot fights players with sword timing, crits, W-tap, strafing, shield break with the axe, attribute \
            swapping, stun slam, mace with wind charges, elytra mace dives, end crystals, pearls and pearl catch, \
            lunge swaps with a spear, totems and golden apples. It uses whatever the inventory holds. Only attack a \
            player the player explicitly names or clearly asks you to attack.
            - Use save_command when the player wants a reusable command ("remember …", "make a command …"); \
            names may contain <n> placeholders.
            - Use stop when the player wants everything to stop.

            How to talk:
            - Answer in the player's language (German or English), short and plain, like a teammate in voice chat. \
            No lists unless asked, no emojis.
            - If something is impossible here (no permission on this server, missing items, unknown place), say so \
            in one sentence and suggest what would work.
            """;

    private static BetaTool tool(String name, String description, Map<String, Object> props, List<String> required) {
        BetaTool.InputSchema.Properties.Builder p = BetaTool.InputSchema.Properties.builder();
        props.forEach((k, v) -> p.putAdditionalProperty(k, JsonValue.from(v)));
        return BetaTool.builder()
                .name(name)
                .description(description)
                .inputSchema(BetaTool.InputSchema.builder()
                        .properties(p.build())
                        .required(required)
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build())
                .strict(true)
                .build();
    }

    private static final List<BetaTool> TOOLS = List.of(
            tool("run_steps", "Start bot jobs now (replaces what it is doing). Steps use the command words from the instructions.",
                    Map.of("steps", Map.of("type", "string", "description", "e.g. \"get 16 wood, then iron pickaxe, then go to home\""),
                            "notify", Map.of("type", "boolean", "description", "true = you get a message when the jobs finish or fail")),
                    List.of("steps", "notify")),
            tool("stop", "Stop everything the bot is doing.", Map.of(), List.of()),
            tool("save_command", "Save a reusable command for the player. Use <name> placeholders in the name for values.",
                    Map.of("name", Map.of("type", "string", "description", "e.g. \"starter\" or \"wood <n>\""),
                            "steps", Map.of("type", "string", "description", "the steps, e.g. \"get <n> wood, then come back\"")),
                    List.of("name", "steps")),
            tool("delete_command", "Delete one of the player's saved commands.",
                    Map.of("name", Map.of("type", "string")), List.of("name")));

    /** Short picture of the game – read on the game thread. */
    private static String snapshot(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return "[game] Not in a world.";
        StringBuilder b = new StringBuilder("[game]\n");
        b.append("where: ").append(p.getBlockX()).append(' ').append(p.getBlockY()).append(' ').append(p.getBlockZ())
                .append(" in ").append(Waypoints.dimension(mc)).append(", facing ").append(p.getDirection().getName()).append('\n');
        b.append("health ").append(Math.round(p.getHealth())).append("/20, food ").append(p.getFoodData().getFoodLevel())
                .append("/20, armor ").append(p.getArmorValue()).append('\n');
        b.append("hand: ").append(name(p.getMainHandItem())).append(", offhand: ").append(name(p.getOffhandItem())).append('\n');
        Map<String, Integer> inv = new TreeMap<>();
        var in = p.getInventory();
        for (int i = 0; i < in.getContainerSize(); i++) {
            ItemStack s = in.getItem(i);
            if (!s.isEmpty()) inv.merge(BuiltInRegistries.ITEM.getKey(s.getItem()).getPath(), s.getCount(), Integer::sum);
        }
        b.append("inventory: ").append(inv.isEmpty() ? "empty" : inv.toString().replaceAll("[{}]", "")).append('\n');
        List<String> near = new ArrayList<>();
        mc.level.getEntitiesOfClass(LivingEntity.class, new AABB(p.blockPosition()).inflate(32), e -> e != p && e.isAlive()).stream()
                .sorted(Comparator.comparingDouble(e -> e.distanceToSqr(p))).limit(14)
                .forEach(e -> near.add((e instanceof Player ? "player " + e.getName().getString() : BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath()
                        + (e instanceof Enemy ? " (hostile)" : "")) + " " + Math.round(e.distanceTo(p)) + "m"));
        long items = mc.level.getEntitiesOfClass(ItemEntity.class, new AABB(p.blockPosition()).inflate(16), Entity::isAlive).size();
        b.append("around (32m): ").append(near.isEmpty() ? "nothing" : String.join(", ", near)).append(items > 0 ? "; " + items + " dropped items" : "").append('\n');
        List<String> wps = new ArrayList<>();
        String dim = Waypoints.dimension(mc);
        for (Waypoints.Waypoint w : Waypoints.all(mc)) if (w.dimension.equals(dim)) wps.add(w.name + " (" + w.x + " " + w.y + " " + w.z + ")");
        b.append("waypoints: ").append(wps.isEmpty() ? "none" : String.join(", ", wps)).append('\n');
        b.append("saved commands: ").append(Commands.routines().isEmpty() ? "none" : Commands.routines().toString()).append('\n');
        b.append("bot: ").append(Bot.busy() ? "busy – " + Bot.status() : "idle (last: " + Bot.status() + ")").append('\n');
        b.append("world: ").append(mc.hasSingleplayerServer() ? "singleplayer/own LAN" : "server " + (mc.getCurrentServer() != null ? mc.getCurrentServer().ip : "?"))
                .append(Bot.allowed(mc) ? ", bot allowed" : ", bot NOT allowed here (no permission)");
        return b.toString();
    }

    private static String name(ItemStack s) {
        return s.isEmpty() ? "nothing" : s.getCount() + " " + BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
    }

    // ------------------------------------------------------------------ conversation
    /** What you typed. Runs in the background; replies land in the assistant window. */
    static void ask(String text) {
        Minecraft mc = Minecraft.getInstance();
        String state = snapshot(mc);
        autoTurns = 0;
        submit(state + "\n\n[player] " + text, text);
    }

    /** A job ended – Claude decides what to do next (limited number of times per request). */
    private static void event(String what) {
        Minecraft mc = Minecraft.getInstance();
        if (++autoTurns > MAX_AUTO_TURNS) {
            Bot.say("I'll stop planning on my own here – tell me how to go on.");
            return;
        }
        submit(snapshot(mc) + "\n\n[result] The jobs you started " + what, null);
    }

    /** Claude can't be reached: do what was typed with the built-in command parser instead of dropping it. */
    private static void fallback(String why, String original) {
        if (original == null) {
            reply(why);
            return;
        }
        Minecraft.getInstance().execute(() -> {
            synchronized (ClaudeBrain.class) {
                if (!history.isEmpty()) history.remove(history.size() - 1); // the unanswered message
            }
            String r = Commands.handle(original);
            Bot.CHAT.add(new String[]{"bot", why + " I handled it myself: " + (r == null ? "nothing to do." : r)});
        });
    }

    private static void submit(String userText, String original) {
        WORKER.submit(() -> {
            BUSY.set(true);
            try {
                synchronized (ClaudeBrain.class) {
                    history.add(BetaMessageParam.ofUser(userText));
                    trim();
                }
                loop();
            } catch (UnauthorizedException e) {
                fallback("Claude rejected the API key (KEY button to change it).", original);
            } catch (RateLimitException e) {
                fallback("Claude is rate limited right now.", original);
            } catch (AnthropicServiceException e) {
                fallback("Claude had a problem (" + e.statusCode() + ").", original);
                PeregrineClient.LOG.warn("Claude request failed: {}", e.getMessage());
            } catch (Throwable e) {
                fallback("Couldn't reach Claude (" + e.getClass().getSimpleName() + ").", original);
                PeregrineClient.LOG.warn("Claude request failed", e);
            } finally {
                BUSY.set(false);
            }
        });
    }

    /** Keeps the conversation short: drops old turns, always starting again at a plain player message. */
    private static void trim() {
        while (history.size() > MAX_HISTORY) {
            history.remove(0);
            while (!history.isEmpty() && !(history.get(0).isUser() && history.get(0).asUser().content().isString())) history.remove(0);
        }
    }

    private static MessageCreateParams params() {
        MessageCreateParams.Builder b = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(16000L)
                .addBeta("server-side-fallback-2026-07-01")
                .fallbacksDefault() // a refused request is answered by a fallback model instead of failing
                .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.MEDIUM).build())
                .systemOfBetaTextBlockParams(List.of(BetaTextBlockParam.builder().text(SYSTEM)
                        .cacheControl(BetaCacheControlEphemeral.builder().build()).build()));
        for (BetaTool t : TOOLS) b.addTool(t);
        synchronized (ClaudeBrain.class) {
            b.messages(new ArrayList<>(history));
        }
        return b.build();
    }

    private static void loop() throws Exception {
        for (int round = 0; round < 8; round++) {
            BetaMessage m = client().beta().messages().create(params());
            synchronized (ClaudeBrain.class) {
                history.add(m.toParam());
            }
            StringBuilder said = new StringBuilder();
            List<BetaContentBlockParam> results = new ArrayList<>();
            for (BetaContentBlock block : m.content()) {
                block.text().ifPresent(t -> said.append(t.text()));
                if (block.toolUse().isPresent()) results.add(runTool(block.toolUse().get()));
            }
            if (!said.isEmpty()) reply(said.toString().trim());
            BetaStopReason stop = m.stopReason().orElse(BetaStopReason.END_TURN);
            if (stop.equals(BetaStopReason.REFUSAL)) {
                reply("Claude declined that one.");
                synchronized (ClaudeBrain.class) {
                    history.clear(); // a refused turn should not shape later answers
                }
                return;
            }
            if (results.isEmpty()) return;
            synchronized (ClaudeBrain.class) {
                history.add(BetaMessageParam.ofUserOfContentBlockParams(results));
            }
        }
    }

    private static void reply(String text) {
        Minecraft.getInstance().execute(() -> {
            Bot.CHAT.add(new String[]{"bot", text});
            while (Bot.CHAT.size() > 60) Bot.CHAT.remove(0);
        });
    }

    // ------------------------------------------------------------------ tools (run on the game thread)
    private static BetaContentBlockParam runTool(BetaToolUseBlock use) {
        String out;
        boolean error = false;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> in = use._input().convert(Map.class);
            out = Minecraft.getInstance().submit(() -> execute(use.name(), in)).get();
            if (out.startsWith("ERROR ")) {
                error = true;
                out = out.substring(6);
            }
        } catch (Exception e) {
            error = true;
            out = "Tool failed: " + e.getMessage();
        }
        return BetaContentBlockParam.ofToolResult(BetaToolResultBlockParam.builder().toolUseId(use.id()).content(out).isError(error).build());
    }

    private static String str(Map<String, Object> in, String k) {
        Object v = in.get(k);
        return v == null ? "" : v.toString().trim();
    }

    private static String execute(String tool, Map<String, Object> in) {
        Minecraft mc = Minecraft.getInstance();
        switch (tool) {
            case "run_steps" -> {
                if (mc.player == null) return "ERROR Not in a world.";
                if (!Bot.allowed(mc)) return "ERROR " + Bot.NOT_ALLOWED;
                String steps = str(in, "steps");
                String err = Commands.check(steps);
                if (err != null) return "ERROR " + err;
                String reply = Commands.run(steps);
                if (!Bot.busy()) return "ERROR " + reply;
                if (Boolean.TRUE.equals(in.get("notify"))) Bot.watcher = ClaudeBrain::event;
                Bot.CHAT.add(new String[]{"bot", "▸ " + reply});
                return "Started. " + reply;
            }
            case "stop" -> {
                Bot.stop(null);
                return "Stopped.";
            }
            case "save_command" -> {
                String r = Commands.remember(str(in, "name"), str(in, "steps"));
                return r.startsWith("Saved") ? r : "ERROR " + r;
            }
            case "delete_command" -> {
                Commands.forget(str(in, "name").toLowerCase(Locale.ROOT));
                return "Deleted (if it existed).";
            }
            default -> {
                return "ERROR Unknown tool " + tool;
            }
        }
    }
}
