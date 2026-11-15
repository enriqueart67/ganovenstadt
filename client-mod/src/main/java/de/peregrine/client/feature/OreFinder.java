package de.peregrine.client.feature;

import de.peregrine.client.bot.Bot;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.Set;
import java.util.function.Predicate;

/**
 * The Block ESP search engine as a one-shot query: nearest block of the given ids around a point. Chunk sections
 * are checked against their palette first ({@code maybeHas}) and only sections that can contain a match are read
 * block by block; chunks are visited in rings from the inside out and the search stops as soon as no nearer
 * match is possible. Used by the assistant to walk straight to ores instead of tunnelling blindly.
 */
public final class OreFinder {
    private OreFinder() {
    }

    public static BlockPos nearest(Minecraft mc, Set<String> ids, BlockPos c, int radius, Set<BlockPos> skip) {
        if (mc.level == null) return null;
        Predicate<BlockState> pred = st -> ids.contains(Bot.id(st));
        int ccx = c.getX() >> 4, ccz = c.getZ() >> 4, rings = (radius >> 4) + 1;
        int minSec = mc.level.getMinY() >> 4;
        BlockPos best = null;
        double bestD = (double) radius * radius;
        for (int ring = 0; ring <= rings; ring++) {
            // nothing in this ring can be nearer than what we have
            if (best != null && Math.pow(Math.max(0, (ring - 1) * 16), 2) > bestD) break;
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    LevelChunk chunk = mc.level.getChunkSource().getChunk(ccx + dx, ccz + dz, ChunkStatus.FULL, false);
                    if (chunk == null) continue;
                    LevelChunkSection[] secs = chunk.getSections();
                    for (int si = 0; si < secs.length; si++) {
                        int by = (minSec + si) << 4;
                        if (by + 16 < c.getY() - radius || by > c.getY() + radius) continue;
                        LevelChunkSection sec = secs[si];
                        if (sec == null || sec.hasOnlyAir() || !sec.maybeHas(pred)) continue;
                        int bx = (ccx + dx) << 4, bz = (ccz + dz) << 4;
                        for (int y = 0; y < 16; y++)
                            for (int z = 0; z < 16; z++)
                                for (int x = 0; x < 16; x++) {
                                    if (!pred.test(sec.getBlockState(x, y, z))) continue;
                                    BlockPos p = new BlockPos(bx + x, by + y, bz + z);
                                    double d = p.distSqr(c);
                                    if (d < bestD && (skip == null || !skip.contains(p))) {
                                        bestD = d;
                                        best = p;
                                    }
                                }
                    }
                }
            }
        }
        return best;
    }
}
