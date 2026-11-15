package de.peregrine.client.bot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Peregrine assistant's body: runs one task at a time and drives your character with the normal
 * controls (walk, jump, look, mine). Singleplayer only – servers ban automated play.
 */
public final class Bot {
    private Bot() {
    }

    public interface Task {
        /** One game tick; return true when the task is finished. */
        boolean tick(Minecraft mc);

        String status();
    }

    private static Task task;
    private static String lastStatus = "Idle";
    public static final List<String[]> CHAT = new ArrayList<>(); // {"you"|"bot", text}

    public static final String NOT_ALLOWED = "I only work in singleplayer, on LAN worlds you host and on servers where you are an operator (your own server). On other servers this would be cheating against other players – and get you banned.";

    /** Singleplayer / own LAN world, or a server where you are an operator (= your own server). */
    public static boolean allowed(Minecraft mc) {
        if (mc.hasSingleplayerServer()) return true;
        try {
            return mc.player != null && mc.player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
        } catch (Throwable e) {
            return false;
        }
    }

    public static Task current() {
        return task;
    }

    public static boolean busy() {
        return task != null;
    }

    public static String status() {
        return task != null ? task.status() : lastStatus;
    }

    public static void say(String text) {
        CHAT.add(new String[]{"bot", text});
        while (CHAT.size() > 60) CHAT.remove(0);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendOverlayMessage(Component.literal("§6[Peregrine AI]§r " + text));
    }

    /** Short note in the action bar only (not in the conversation). */
    public static void note(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendOverlayMessage(Component.literal("§6[Peregrine AI]§r " + text));
    }

    /** Nearest dropped item within 4 blocks of a spot (where to walk to pick it up), or null. */
    public static BlockPos dropNear(Minecraft mc, BlockPos around) {
        var items = mc.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(around).inflate(4));
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (var e : items) {
            double d = e.position().distanceToSqr(Vec3.atCenterOf(around));
            if (d < bd) {
                bd = d;
                best = e.blockPosition();
            }
        }
        return best;
    }

    public static void start(Task t) {
        stop(null);
        Guard.reset();
        task = t;
    }

    public static void stop(String why) {
        if (task != null) lastStatus = why != null ? why : "Stopped";
        task = null;
        de.peregrine.client.Modules.BLOCK_ESP.setAssist(java.util.Set.of()); // the assistant's highlights go away
        Guard.reset();
        release(Minecraft.getInstance());
        if (why != null) say(why);
    }

    public static void tick(Minecraft mc) {
        if (task == null) return;
        if (mc.player == null || mc.level == null) {
            stop(null);
            return;
        }
        if (!allowed(mc)) {
            stop(NOT_ALLOWED);
            return;
        }
        LocalPlayer p = mc.player;
        if (p.isDeadOrDying()) {
            stop("You died – sorry. I stopped. Respawn and ask me again.");
            return;
        }
        try {
            if (Guard.tick(mc)) return; // fighting, eating or resting first
            if (task != null && task.tick(mc)) {
                Task done = task;
                task = null;
                de.peregrine.client.Modules.BLOCK_ESP.setAssist(java.util.Set.of());
                lastStatus = done.status();
                release(mc);
            }
        } catch (Throwable e) {
            stop("Something went wrong: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ controls
    public static void release(Minecraft mc) {
        if (mc.options == null) return;
        mc.options.keyUp.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keySprint.setDown(false);
        mc.options.keyShift.setDown(false);
        mc.options.keyUse.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyRight.setDown(false);
        if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
    }

    /** Turns the head towards a point (smoothly, so it looks natural). */
    public static void look(LocalPlayer p, Vec3 target) {
        Vec3 eye = p.getEyePosition();
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        float dyaw = wrap(yaw - p.getYRot());
        p.setYRot(p.getYRot() + clamp(dyaw, -35, 35));
        p.setXRot(p.getXRot() + clamp(pitch - p.getXRot(), -25, 25));
        p.setYHeadRot(p.getYRot());
    }

    private static float wrap(float a) {
        a %= 360f;
        if (a >= 180f) a -= 360f;
        if (a < -180f) a += 360f;
        return a;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ------------------------------------------------------------------ world checks
    public static String id(BlockState s) {
        return BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
    }

    public static boolean isLava(BlockState s) {
        return !s.getFluidState().isEmpty() && BuiltInRegistries.FLUID.getKey(s.getFluidState().getType()).getPath().contains("lava");
    }

    public static boolean isWater(BlockState s) {
        return !s.getFluidState().isEmpty() && BuiltInRegistries.FLUID.getKey(s.getFluidState().getType()).getPath().contains("water");
    }

    /** Can the player's body be in this block? */
    public static boolean passable(Minecraft mc, BlockPos p) {
        BlockState s = mc.level.getBlockState(p);
        return s.getCollisionShape(mc.level, p).isEmpty() && !isLava(s) && !id(s).contains("fire");
    }

    public static boolean solid(Minecraft mc, BlockPos p) {
        BlockState s = mc.level.getBlockState(p);
        String n = id(s);
        return !s.getCollisionShape(mc.level, p).isEmpty() && !n.contains("magma") && !n.contains("cactus") && !n.contains("campfire");
    }

    /** Opening this block would let lava or water in (or it is unbreakable). */
    public static boolean unsafeToMine(Minecraft mc, BlockPos p) {
        BlockState s = mc.level.getBlockState(p);
        if (s.getDestroySpeed(mc.level, p) < 0) return true; // bedrock & co.
        for (Direction d : Direction.values()) {
            BlockState n = mc.level.getBlockState(p.relative(d));
            if (isLava(n) || (isWater(n) && d != Direction.DOWN)) return true;
        }
        // falling blocks above could bury us
        String above = id(mc.level.getBlockState(p.above()));
        return above.contains("gravel") || above.endsWith("sand");
    }

    // ------------------------------------------------------------------ mining
    private static BlockPos mining;
    private static int miningTicks;

    /** Mines the block step by step; returns true once it is gone (or was never there). */
    public static boolean mine(Minecraft mc, BlockPos pos) {
        LocalPlayer p = mc.player;
        if (passable(mc, pos)) {
            mining = null;
            return true;
        }
        BlockState s = mc.level.getBlockState(pos);
        if (!pos.equals(mining)) {
            mining = pos;
            miningTicks = 0;
            selectTool(mc, s);
        }
        miningTicks++;
        if (miningTicks % 20 == 0) selectTool(mc, s); // e.g. after a torch or a fight changed the hand
        Vec3 c = Vec3.atCenterOf(pos);
        look(p, c);
        Vec3 eye = p.getEyePosition();
        Direction face = Direction.getApproximateNearest(eye.x - c.x, eye.y - c.y, eye.z - c.z);
        mc.options.keyUp.setDown(false);
        mc.gameMode.continueDestroyBlock(pos, face);
        swing(p);
        if (miningTicks > 20 * 30) throw new IllegalStateException("cannot break " + id(s) + " – wrong tool?");
        return false;
    }

    private static java.lang.reflect.Method swingM;
    private static boolean swingLooked;

    /** Arm swing; the method's signature differs between versions, so look it up once. */
    public static void swing(LocalPlayer p) {
        try {
            if (!swingLooked) {
                swingLooked = true;
                for (var m : LocalPlayer.class.getMethods())
                    if (m.getName().equals("swing") && m.getParameterCount() == 1 && m.getParameterTypes()[0] == InteractionHand.class) swingM = m;
            }
            if (swingM != null) swingM.invoke(p, InteractionHand.MAIN_HAND);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    /** Picks the fastest item for this block; tools from the main inventory are swapped into the hand. */
    public static void selectTool(Minecraft mc, BlockState s) {
        var inv = mc.player.getInventory();
        int sel = inv.getSelectedSlot(), best = sel;
        float bestSpeed = inv.getItem(sel).getDestroySpeed(s);
        boolean bestDrops = inv.getItem(sel).isCorrectToolForDrops(s);
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it.isEmpty()) continue;
            float sp = it.getDestroySpeed(s);
            boolean drops = it.isCorrectToolForDrops(s);
            // getting the drop matters more than speed; among equals prefer the hotbar
            if ((drops && !bestDrops) || (drops == bestDrops && sp > bestSpeed + 0.01f)) {
                best = i;
                bestSpeed = sp;
                bestDrops = drops;
            }
        }
        if (best == sel) return;
        if (best < 9) inv.setSelectedSlot(best);
        else {
            Inv.toHotbar(mc, best, sel); // a normal inventory click – works on servers too
        }
    }

    /** Puts the best-scoring item (score > 0) in your hand; false if there is none. */
    public static boolean holdBest(Minecraft mc, java.util.function.ToDoubleFunction<ItemStack> score) {
        var inv = mc.player.getInventory();
        int sel = inv.getSelectedSlot(), best = -1;
        double bs = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it.isEmpty()) continue;
            double v = score.applyAsDouble(it) + (i == sel ? 0.001 : 0);
            if (v > bs) {
                bs = v;
                best = i;
            }
        }
        if (best < 0) return false;
        if (best == sel) return true;
        if (best < 9) inv.setSelectedSlot(best);
        else {
            Inv.toHotbar(mc, best, sel);
        }
        return true;
    }

    /** Is there an item in the inventory that gets drops from this block? */
    public static boolean hasToolFor(Minecraft mc, BlockState s) {
        var inv = mc.player.getInventory();
        for (int i = 0; i < 36; i++) if (inv.getItem(i).isCorrectToolForDrops(s)) return true;
        return !s.requiresCorrectToolForDrops();
    }

    // ------------------------------------------------------------------ singleplayer server access
    public static net.minecraft.server.level.ServerPlayer serverPlayer(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        return server == null || mc.player == null ? null : server.getPlayerList().getPlayer(mc.player.getUUID());
    }

    /** Runs code on the integrated server's thread with your server-side player. */
    public static void onServer(Minecraft mc, java.util.function.Consumer<net.minecraft.server.level.ServerPlayer> job) {
        var server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) return;
        java.util.UUID id = mc.player.getUUID();
        server.execute(() -> {
            var sp = server.getPlayerList().getPlayer(id);
            if (sp != null) job.accept(sp);
        });
    }

    private static final java.util.Set<String> JUNK = java.util.Set.of("dirt", "gravel", "andesite", "diorite", "granite", "tuff",
            "netherrack", "calcite", "dripstone_block", "pointed_dripstone", "flint", "rotten_flesh", "poisonous_potato", "sand", "red_sand", "coarse_dirt", "rooted_dirt");

    /** Full inventory: throws away junk (dirt, gravel …) and stone beyond two stacks. Returns false if nothing could go. */
    private static long lastRoom;

    public static boolean makeRoom(Minecraft mc) {
        if (System.currentTimeMillis() - lastRoom < 2000) return true; // the last clean-up is still syncing
        var inv = mc.player.getInventory();
        List<Integer> slots = new ArrayList<>();
        int stone = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it.isEmpty() || i == inv.getSelectedSlot()) continue;
            String id = BuiltInRegistries.ITEM.getKey(it.getItem()).getPath();
            if (JUNK.contains(id)) slots.add(i);
            else if (id.equals("cobblestone") || id.equals("cobbled_deepslate")) {
                stone += it.getCount();
                if (stone > 128) slots.add(i);
            }
        }
        if (slots.isEmpty()) return false;
        for (int i : slots) Inv.throwAway(mc, i);
        lastRoom = System.currentTimeMillis();
        say("My bags were full, so I dropped dirt, gravel and spare stone.");
        return true;
    }

    public static int count(Minecraft mc, String... itemIds) {
        var inv = mc.player.getInventory();
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it.isEmpty()) continue;
            String path = BuiltInRegistries.ITEM.getKey(it.getItem()).getPath();
            for (String want : itemIds) if (path.equals(want)) n += it.getCount();
        }
        return n;
    }

    public static boolean inventoryFull(Minecraft mc) {
        var inv = mc.player.getInventory();
        for (int i = 0; i < 36; i++) if (inv.getItem(i).isEmpty()) return false;
        return true;
    }

    public static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
