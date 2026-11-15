package de.peregrine.client.feature;

import de.peregrine.client.PeregrineClient;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * Decides where the world-information features (Block ESP, Entity ESP, the assistant) may run.
 *
 * <ul>
 *     <li>Singleplayer and LAN worlds you host: always.</li>
 *     <li>Servers where you are operator (permission level 2+): always – it is your server.</li>
 *     <li>Any other server: only when the server itself allows it. A server plugin sends the plugin message
 *     {@code peregrine:features} with a comma separated list, e.g. {@code blockesp,entityesp,assistant} (or
 *     {@code all}). The grant lasts until you disconnect.</li>
 * </ul>
 *
 * <p>Nothing here hides the client from a server or its anti-cheat; it only reads what the server already sends.</p>
 */
public final class Access {
    public static final String BLOCK_ESP = "blockesp", ENTITY_ESP = "entityesp", ASSISTANT = "assistant";

    private static volatile Set<String> granted = Set.of();
    private static volatile boolean announced;

    private Access() {
    }

    /** Raw UTF-8 bytes, so a Bukkit/Paper plugin can send it with {@code player.sendPluginMessage}. */
    public record Grant(String features) implements CustomPacketPayload {
        public static final Type<Grant> TYPE = new Type<>(Identifier.fromNamespaceAndPath("peregrine", "features"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Grant> CODEC = StreamCodec.of(
                (buf, g) -> buf.writeBytes(g.features.getBytes(StandardCharsets.UTF_8)),
                buf -> {
                    byte[] b = new byte[Math.min(buf.readableBytes(), 1024)];
                    buf.readBytes(b);
                    buf.skipBytes(buf.readableBytes());
                    return new Grant(new String(b, StandardCharsets.UTF_8));
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void init() {
        try {
            PayloadTypeRegistry.clientboundPlay().register(Grant.TYPE, Grant.CODEC);
            ClientPlayNetworking.registerGlobalReceiver(Grant.TYPE, (payload, ctx) -> ctx.client().execute(() -> grant(payload.features())));
            ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
                granted = Set.of();
                announced = false;
            });
        } catch (Throwable t) {
            PeregrineClient.LOG.warn("Server feature grants unavailable: {}", t.toString());
        }
    }

    private static void grant(String list) {
        java.util.HashSet<String> s = new java.util.HashSet<>();
        for (String part : list.split("[,; ]+")) {
            String f = part.trim().toLowerCase(Locale.ROOT);
            if (!f.isEmpty()) s.add(f);
        }
        granted = Set.copyOf(s);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && !s.isEmpty() && !announced) {
            mc.player.sendSystemMessage(Component.literal("§6[Peregrine]§r This server allows: " + String.join(", ", s)));
            announced = true;
        }
    }

    /** Singleplayer, a LAN world you host, or operator on this server. */
    public static boolean ownWorld(Minecraft mc) {
        if (mc.hasSingleplayerServer()) return true;
        try {
            return mc.player != null && mc.player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
        } catch (Throwable e) {
            return false;
        }
    }

    public static boolean allowed(Minecraft mc, String feature) {
        if (mc.player == null) return false;
        if (ownWorld(mc)) return true;
        Set<String> g = granted;
        return g.contains("all") || g.contains(feature);
    }

    /** Short explanation for the overlay message when a feature stays off. */
    public static String deniedMessage(String what) {
        return "§6[Peregrine]§r " + what + " is off here: this server has not allowed it (singleplayer, your LAN world, operator or a server grant).";
    }
}
