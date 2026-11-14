package de.peregrine.client.bot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks to a block one step at a time and digs its own way: level steps, stairs up (jump) and stairs
 * down (never straight down). Before opening a block it checks for lava, water and falling blocks and
 * picks another direction when a step is unsafe.
 */
public final class Navigator {
    private final BlockPos goal;
    private final double reach; // done when the eyes are this close to the goal's centre (0 = stand on it)
    private Direction lastDir;
    private BlockPos stepTarget;
    private List<BlockPos> toClear = new ArrayList<>();
    private int stuck, lastProgressTick, ticks;
    private BlockPos lastFeet;
    public String problem;

    public Navigator(BlockPos goal, double reach, Direction preferred) {
        this.goal = goal;
        this.reach = reach;
        this.lastDir = preferred;
    }

    public BlockPos goal() {
        return goal;
    }

    /** One tick. Returns true when arrived; sets {@link #problem} and returns true when it gives up. */
    public boolean tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        ticks++;
        BlockPos feet = p.blockPosition();
        if (arrived(p, feet)) {
            Bot.release(mc);
            return true;
        }
        if (!feet.equals(lastFeet)) {
            lastFeet = feet;
            lastProgressTick = ticks;
        }
        if (ticks - lastProgressTick > 20 * 12) {
            problem = "I'm stuck here.";
            Bot.release(mc);
            return true;
        }

        if (stepTarget == null || feet.equals(stepTarget)) {
            if (!planStep(mc, feet)) {
                Bot.release(mc);
                return true;
            }
        }
        // 1) dig the blocks of the step (top first, so nothing falls on us)
        while (!toClear.isEmpty()) {
            BlockPos b = toClear.get(0);
            if (Bot.passable(mc, b)) {
                toClear.remove(0);
                continue;
            }
            Bot.mine(mc, b);
            return false;
        }
        // 2) walk into the step cell
        Vec3 target = Vec3.atBottomCenterOf(stepTarget);
        Bot.look(p, new Vec3(target.x, p.getEyeY() - (stepTarget.getY() < feet.getY() ? 0.6 : 0), target.z));
        mc.options.keyUp.setDown(true);
        boolean up = stepTarget.getY() > feet.getY();
        mc.options.keyJump.setDown(up && p.onGround() || p.horizontalCollision && p.onGround() || p.isInWater());
        // overshoot / wrong cell: re-plan
        if (feet.distManhattan(stepTarget) > 3) stepTarget = null;
        return false;
    }

    private boolean arrived(LocalPlayer p, BlockPos feet) {
        if (reach <= 0) return feet.equals(goal) || (feet.getX() == goal.getX() && feet.getZ() == goal.getZ() && Math.abs(feet.getY() - goal.getY()) <= 0);
        return p.getEyePosition().distanceTo(Vec3.atCenterOf(goal)) <= reach;
    }

    /** Chooses the next cell and the blocks that must be removed for it. */
    private boolean planStep(Minecraft mc, BlockPos feet) {
        int dx = goal.getX() - feet.getX(), dz = goal.getZ() - feet.getZ(), dy = goal.getY() - feet.getY();
        List<Direction> dirs = new ArrayList<>();
        Direction primary, secondary;
        if (dx == 0 && dz == 0) {
            primary = lastDir != null ? lastDir : Direction.NORTH; // vertical only: walk a staircase
            secondary = primary.getClockWise();
        } else if (Math.abs(dx) >= Math.abs(dz)) {
            primary = dx > 0 ? Direction.EAST : Direction.WEST;
            secondary = dz == 0 ? (lastDir != null && lastDir.getAxis() == Direction.Axis.Z ? lastDir : Direction.SOUTH) : dz > 0 ? Direction.SOUTH : Direction.NORTH;
        } else {
            primary = dz > 0 ? Direction.SOUTH : Direction.NORTH;
            secondary = dx == 0 ? (lastDir != null && lastDir.getAxis() == Direction.Axis.X ? lastDir : Direction.EAST) : dx > 0 ? Direction.EAST : Direction.WEST;
        }
        dirs.add(primary);
        dirs.add(secondary);
        dirs.add(secondary.getOpposite());
        dirs.add(primary.getOpposite());
        for (Direction d : dirs) {
            if (tryStep(mc, feet, d, dy)) {
                lastDir = d;
                return true;
            }
        }
        problem = "Every way from here is blocked or dangerous (lava, water or a deep drop).";
        return false;
    }

    private boolean tryStep(Minecraft mc, BlockPos feet, Direction d, int dy) {
        BlockPos next = feet.relative(d);
        List<BlockPos> clear = new ArrayList<>();
        BlockPos cell;
        if (dy < 0) { // stairs down: the cell ahead and one lower
            cell = next.below();
            clear.add(next.above());
            clear.add(next);
            clear.add(cell);
        } else if (dy > 0) { // stairs up: needs room above our own head to jump
            cell = next.above();
            clear.add(feet.above(2));
            clear.add(cell.above());
            clear.add(cell);
        } else {
            cell = next;
            clear.add(next.above());
            clear.add(next);
        }
        for (BlockPos b : clear) {
            if (!Bot.passable(mc, b) && Bot.unsafeToMine(mc, b)) return false;
            if (Bot.isLava(mc.level.getBlockState(b))) return false;
        }
        // something to stand on (allow a short drop)
        BlockPos floor = cell.below();
        int drop = 0;
        while (!Bot.solid(mc, floor) && drop < 4) {
            if (Bot.isLava(mc.level.getBlockState(floor))) return false;
            floor = floor.below();
            drop++;
        }
        if (drop >= 4 || Bot.isLava(mc.level.getBlockState(floor.above()))) return false;
        String under = Bot.id(mc.level.getBlockState(floor));
        if (under.contains("magma") || under.contains("cactus")) return false;
        clear.removeIf(b -> Bot.passable(mc, b));
        toClear = clear;
        stepTarget = drop > 0 ? floor.above() : cell;
        return true;
    }
}
