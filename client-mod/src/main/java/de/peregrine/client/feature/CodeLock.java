package de.peregrine.client.feature;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.peregrine.client.Store;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Access code for the whole client: until the right code is entered once, the mod menu, the assistant and all
 * modules stay off. Only a hash of the code is kept in the jar and in the settings. On servers that run the
 * PeregrineGrant plugin, the client says "unlocked" or "locked" (plugin message peregrine:hello) – the server can
 * then act on it (ban a locked client, if its owner wants that).
 */
public final class CodeLock {
    private CodeLock() {
    }

    private static final String HASH = "084464c5568b6920989962ed52526d18ed112ef7caf7ca135cf79cf1c5fe1dca";
    private static Boolean unlocked;
    private static int helloTicks = -1;

    public record Hello(String state) implements CustomPacketPayload {
        public static final Type<Hello> TYPE = new Type<>(Identifier.fromNamespaceAndPath("peregrine", "hello"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Hello> CODEC = StreamCodec.of(
                (buf, h) -> buf.writeBytes(h.state.getBytes(StandardCharsets.UTF_8)),
                buf -> {
                    byte[] b = new byte[Math.min(buf.readableBytes(), 64)];
                    buf.readBytes(b);
                    buf.skipBytes(buf.readableBytes());
                    return new Hello(new String(b, StandardCharsets.UTF_8));
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static String hash(String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(("peregrine:" + code.trim()).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }

    private static Path file() {
        Path h = Store.home();
        return (h != null ? h : net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()).resolve("peregrine-access.json");
    }

    public static boolean unlocked() {
        if (unlocked == null) {
            unlocked = false;
            try {
                if (Files.exists(file())) {
                    JsonObject o = JsonParser.parseString(Files.readString(file())).getAsJsonObject();
                    unlocked = o.has("key") && HASH.equals(o.get("key").getAsString());
                }
            } catch (Exception ignored) {
            }
        }
        return unlocked;
    }

    /** Checks a code; the right one unlocks the client for good on this computer. */
    public static boolean tryCode(String code) {
        String h = hash(code);
        if (!HASH.equals(h)) return false;
        unlocked = true;
        try {
            JsonObject o = new JsonObject();
            o.addProperty("key", h);
            Files.createDirectories(file().getParent());
            Files.writeString(file(), o.toString());
        } catch (Exception ignored) {
        }
        helloTicks = 0; // tell the server again
        return true;
    }

    public static void init() {
        try {
            PayloadTypeRegistry.serverboundPlay().register(Hello.TYPE, Hello.CODEC);
            ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> helloTicks = 0);
            ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> helloTicks = -1);
        } catch (Throwable t) {
            de.peregrine.client.PeregrineClient.LOG.warn("Access hello unavailable: {}", t.toString());
        }
    }

    /** After joining: once the server has announced the channel (only servers with the plugin do), say locked/unlocked. */
    public static void tick(Minecraft mc) {
        if (helloTicks < 0 || mc.getConnection() == null) return;
        if (++helloTicks > 20 * 30) {
            helloTicks = -1;
            return;
        }
        if (helloTicks % 20 != 0) return;
        try {
            if (ClientPlayNetworking.canSend(Hello.TYPE)) {
                ClientPlayNetworking.send(new Hello(unlocked() ? "unlocked" : "locked"));
                helloTicks = -1;
            }
        } catch (Throwable ignored) {
        }
    }
}
