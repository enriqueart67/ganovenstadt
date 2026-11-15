package de.peregrine.client.feature;

import de.peregrine.client.bot.Bot;
import de.peregrine.client.gui.Type;
import de.peregrine.client.gui.Ui;
import de.peregrine.client.module.Category;
import de.peregrine.client.module.Module;
import de.peregrine.client.setting.BoolSetting;
import de.peregrine.client.setting.ColorSetting;
import de.peregrine.client.setting.ListSetting;
import de.peregrine.client.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Block ESP: highlights every chosen block type through all other blocks, also deep underground.
 *
 * <p>Finding blocks is cheap: chunk sections are checked against their block palette first ({@code maybeHas}),
 * so sections that cannot contain a wanted block are skipped without looking at their 4096 blocks. The world
 * around you is checked a little every tick (a rolling scan), never all at once. Drawing is a screen overlay
 * (outline + soft glow, projected like the waypoint markers), limited to the nearest matches.</p>
 *
 * <p>Allowed in singleplayer, LAN worlds you host and servers where you are operator – elsewhere seeing through
 * walls would be cheating against other players.</p>
 */
public final class BlockEsp extends Module {
    public final ListSetting blocks = add(new ListSetting("blocks", "Blocks", List.of("minecraft:diamond_ore", "minecraft:deepslate_diamond_ore")));
    public final ColorSetting color = add(new ColorSetting("color", "Color", 0xFF4F8CFF));
    public final NumberSetting opacity = add(new NumberSetting("opacity", "Fill opacity", 30, 0, 100, 5, " %"));
    public final NumberSetting outline = add(new NumberSetting("outline", "Outline", 2, 1, 4, 1, " px"));
    public final NumberSetting range = add(new NumberSetting("range", "Range", 48, 16, 128, 8, " blocks"));
    public final NumberSetting max = add(new NumberSetting("max", "Max highlights", 256, 32, 1024, 32, ""));
    public final BoolSetting labels = add(new BoolSetting("labels", "Show name and distance", true));

    private final Map<Long, List<BlockPos>> found = new HashMap<>(); // section key -> matches
    private Set<Block> targets = Set.of();
    private List<String> targetsFor = List.of();
    private int cursor;
    private ChunkPosKey lastCentre;
    private boolean warned;
    private List<BlockPos> near = new ArrayList<>();
    private long nearAt;

    private record ChunkPosKey(int x, int z, int r) {
    }

    public BlockEsp() {
        super("blockesp", "Block ESP", "Highlights chosen blocks through walls (X)", Category.GRAPHICS, false);
    }

    @Override
    public Screen editor(Screen parent) {
        return new de.peregrine.client.gui.BlockPickerScreen(parent, this);
    }

    public boolean allowed(Minecraft mc) {
        return mc.player != null && Bot.allowed(mc);
    }

    private static long key(int cx, int sy, int cz) {
        return ((long) cx & 0x3FFFFF) << 42 | ((long) (sy + 2048) & 0xFFFFF) << 22 | ((long) cz & 0x3FFFFF);
    }

    private void rebuildTargets() {
        Set<Block> s = new HashSet<>();
        for (String id : blocks.get()) {
            Identifier rl = Identifier.tryParse(id);
            if (rl == null) continue;
            Block b = BuiltInRegistries.BLOCK.getValue(rl);
            if (b != null && !b.defaultBlockState().isAir()) s.add(b);
        }
        targets = s;
        targetsFor = List.copyOf(blocks.get());
        found.clear();
        cursor = 0;
    }

    @Override
    protected void onToggle() {
        found.clear();
        cursor = 0;
        warned = false;
    }

    /** Rolling scan: a few sections per tick. */
    @Override
    public void tick(Minecraft mc) {
        if (!enabled || mc.level == null || mc.player == null) {
            if (!found.isEmpty()) found.clear();
            return;
        }
        if (!allowed(mc)) {
            if (!warned) {
                mc.player.sendOverlayMessage(net.minecraft.network.chat.Component.literal("§6[Peregrine]§r Block ESP only works in singleplayer and on servers where you are operator."));
                warned = true;
            }
            found.clear();
            return;
        }
        if (!targetsFor.equals(blocks.get())) rebuildTargets();
        if (targets.isEmpty()) return;
        int r = (int) Math.ceil(range.d() / 16.0);
        int pcx = mc.player.blockPosition().getX() >> 4, pcz = mc.player.blockPosition().getZ() >> 4;
        ChunkPosKey centre = new ChunkPosKey(pcx, pcz, r);
        if (!centre.equals(lastCentre)) {
            // moved into another chunk: forget what is now out of range
            found.keySet().removeIf(k -> {
                int cx = (int) (k >> 42), cz = (int) (k & 0x3FFFFF);
                cx = (cx << 10) >> 10;
                cz = (cz << 10) >> 10;
                return Math.abs(cx - pcx) > r || Math.abs(cz - pcz) > r;
            });
            lastCentre = centre;
        }
        int side = r * 2 + 1;
        int minSec = mc.level.getMinY() >> 4;
        LevelChunk any = mc.level.getChunkSource().getChunk(pcx, pcz, ChunkStatus.FULL, false);
        int sections = any == null ? 24 : any.getSections().length;
        int total = side * side * sections;
        var pred = (java.util.function.Predicate<BlockState>) st -> targets.contains(st.getBlock());
        int cheap = 0, full = 0;
        while (cheap < 320 && full < 6) {
            if (cursor >= total) cursor = 0;
            int idx = cursor++;
            int si = idx % sections, ci = idx / sections;
            int cx = pcx - r + ci % side, cz = pcz - r + ci / side;
            long k = key(cx, si, cz);
            cheap++;
            LevelChunk chunk = mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
            if (chunk == null) {
                found.remove(k);
                continue;
            }
            LevelChunkSection[] secs = chunk.getSections();
            if (si >= secs.length) continue;
            LevelChunkSection sec = secs[si];
            if (sec == null || sec.hasOnlyAir() || !sec.maybeHas(pred)) {
                found.remove(k);
                continue;
            }
            full++;
            List<BlockPos> list = new ArrayList<>();
            int bx = cx << 4, by = (minSec + si) << 4, bz = cz << 4;
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++)
                        if (targets.contains(sec.getBlockState(x, y, z).getBlock())) list.add(new BlockPos(bx + x, by + y, bz + z));
            if (list.isEmpty()) found.remove(k);
            else found.put(k, list);
        }
    }

    /** Overlay: the nearest matches as outline + soft glow, optionally with name and distance. */
    public void render(GuiGraphicsExtractor g, Minecraft mc) {
        if (!enabled || found.isEmpty() || mc.player == null || !allowed(mc)) return;
        Projector pr = Projector.of(mc, g);
        if (pr == null) return;
        Vec3 cam = pr.camera();
        // the nearest matches, re-sorted at most 10× a second (thousands of matches stay cheap)
        long now = System.currentTimeMillis();
        if (now - nearAt > 100) {
            double maxD = range.d();
            List<BlockPos> all = new ArrayList<>();
            for (List<BlockPos> l : found.values())
                for (BlockPos b : l)
                    if (b.distToCenterSqr(cam) <= maxD * maxD) all.add(b);
            all.sort((a, b) -> Double.compare(a.distToCenterSqr(cam), b.distToCenterSqr(cam)));
            near = all.size() > max.i() ? new ArrayList<>(all.subList(0, max.i())) : all;
            nearAt = now;
        }
        int limit = near.size();
        int col = color.argb();
        int lineCol = 0xFF000000 | (col & 0xFFFFFF);
        int glowA = (int) Math.round(opacity.d() / 100.0 * 0xC0);
        float width = (float) outline.d();
        List<LabelCand> cands = new ArrayList<>();
        // far ones first, so the nearest are drawn on top
        for (int i = limit - 1; i >= 0; i--) {
            BlockPos b = near.get(i);
            BlockState st = mc.level.getBlockState(b);
            if (!targets.contains(st.getBlock())) continue; // mined since the last scan
            int faces = 0;
            net.minecraft.core.Direction[] dirs = {net.minecraft.core.Direction.WEST, net.minecraft.core.Direction.EAST, net.minecraft.core.Direction.DOWN,
                    net.minecraft.core.Direction.UP, net.minecraft.core.Direction.NORTH, net.minecraft.core.Direction.SOUTH};
            for (int f = 0; f < 6; f++) if (!targets.contains(mc.level.getBlockState(b.relative(dirs[f])).getBlock())) faces |= 1 << f;
            if (faces == 0) continue; // completely inside a vein: nothing to outline
            AABB box = new AABB(b).deflate(0.002);
            float[] s = pr.faces(g, box, faces, width, lineCol);
            if (s == null) continue;
            if (glowA > 0) {
                int cx = Math.round((s[0] + s[2]) / 2), cy = Math.round((s[1] + s[3]) / 2);
                int gw = Math.max(6, Math.round((s[2] - s[0]) * 1.6f)), gh = Math.max(6, Math.round((s[3] - s[1]) * 1.6f));
                Ui.glow(g, cx, cy, gw, gh, (glowA << 24) | (col & 0xFFFFFF));
            }
            if (labels.on() && i < 40) {
                double d = Math.sqrt(b.distToCenterSqr(cam));
                cands.add(new LabelCand((s[0] + s[2]) / 2, s[1] - 10, st.getBlock().getName().getString() + "  " + Math.round(d) + " m", d));
            }
        }
        drawLabels(g, cands);
    }

    private record LabelCand(float cx, float y, String text, double d) {
    }

    private static void drawLabels(GuiGraphicsExtractor g, List<LabelCand> cands) {
        cands.sort((a, b) -> Double.compare(a.d(), b.d()));
        List<float[]> used = new ArrayList<>();
        float sc = 0.7f;
        for (LabelCand c : cands) {
            if (used.size() >= 10) break;
            int tw = Type.width(c.text(), Type.BOLD, sc);
            float tx = c.cx() - tw / 2f, ty = c.y();
            boolean free = true;
            for (float[] r : used) if (tx - 4 < r[2] && tx + tw + 4 > r[0] && ty - 2 < r[3] && ty + 10 > r[1]) free = false;
            if (!free) continue;
            used.add(new float[]{tx - 4, ty - 2, tx + tw + 4, ty + 10});
            de.peregrine.client.gui.Px.roundRect(g, tx - 4, ty - 2, tw + 8, 10, 3, 0xB0101216);
            Type.left(g, c.text(), Type.BOLD, tx, ty, sc, 0xFFFFFFFF);
        }
    }

    /** For the picker: how many matches are currently known. */
    public int matches() {
        int n = 0;
        for (List<BlockPos> l : found.values()) n += l.size();
        return n;
    }
}
