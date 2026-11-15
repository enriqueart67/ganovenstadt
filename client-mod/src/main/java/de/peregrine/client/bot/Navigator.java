package de.peregrine.client.bot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Finds a route with A* and follows it. Walking is cheap, digging costs more the harder the block is,
 * water, jumps and drops cost a little extra – so it walks around a hill instead of tunnelling through it,
 * but digs when that is clearly shorter. Never steps next to lava, never digs straight down, never falls more
 * than three blocks. Far goals are approached in pieces (it re-plans when the partial route ends).
 */
public final class Navigator {
    private static final int MAX_NODES = 3000;
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private final BlockPos goal;
    private final double reach; // done when the eyes are this close to the goal's centre (0 = stand on it)
    private Direction lastDir;
    private List<Move> path;
    private int index;
    private List<BlockPos> toClear = new ArrayList<>();
    private int lastProgressTick, ticks, failedPlans;
    public String problem;
    /** Give up after this many ticks without getting closer (default 20 s). */
    public int timeout = 20 * 20;
    private double bestDist = Double.MAX_VALUE;

    /** One step of a route: the cell the feet end up in and the blocks that have to go first. */
    private record Move(BlockPos from, BlockPos cell, List<BlockPos> clear, double cost, Direction dir) {
    }

    public Navigator(BlockPos goal, double reach, Direction preferred) {
        this.goal = goal.immutable();
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
        if (arrived(feet, p.getEyePosition())) {
            Bot.release(mc);
            return true;
        }
        // progress = getting closer to the goal (walking back and forth does not count)
        double dist = estimate(feet);
        if (dist < bestDist - 0.5) {
            bestDist = dist;
            lastProgressTick = ticks;
        }
        if (ticks - lastProgressTick > timeout) {
            problem = "I'm stuck here.";
            Bot.release(mc);
            return true;
        }

        // advance along the route; re-plan when we left it or it ended
        while (path != null && index < path.size() && feet.equals(path.get(index).cell())) index++;
        if (path == null || index >= path.size() || offRoute(feet)) {
            if (!plan(mc, feet)) {
                Bot.release(mc);
                return true;
            }
            if (path.isEmpty()) { // planned route is empty although not arrived: nothing better reachable
                problem = "I can't find a way there.";
                Bot.release(mc);
                return true;
            }
        }
        Move m = path.get(index);
        if (toClear.isEmpty() && m.clear() != null) toClear = new ArrayList<>(m.clear());

        // 1) dig the blocks of the step (top first, so nothing falls on us)
        while (!toClear.isEmpty()) {
            BlockPos b = toClear.get(0);
            if (Bot.passable(mc, b)) {
                toClear.remove(0);
                lastProgressTick = ticks; // breaking blocks is progress too
                continue;
            }
            if (Bot.isLava(mc.level.getBlockState(b)) || Bot.unsafeToMine(mc, b)) { // world changed: re-plan
                path = null;
                toClear.clear();
                return false;
            }
            Bot.mine(mc, b);
            return false;
        }
        // 2) walk into the step cell
        BlockPos target = m.cell();
        Vec3 t = Vec3.atBottomCenterOf(target);
        Bot.look(p, new Vec3(t.x, p.getEyeY() - (target.getY() < feet.getY() ? 0.6 : 0), t.z));
        mc.options.keyUp.setDown(true);
        boolean up = target.getY() > feet.getY();
        mc.options.keyJump.setDown(up && p.onGround() || p.horizontalCollision && p.onGround() || p.isInWater());
        return false;
    }

    private boolean offRoute(BlockPos feet) {
        Move m = path.get(index);
        return !feet.equals(m.from()) && !feet.equals(m.cell()) && feet.distManhattan(m.cell()) > 2;
    }

    private boolean arrived(BlockPos feet, Vec3 eye) {
        if (reach <= 0) return feet.getX() == goal.getX() && feet.getZ() == goal.getZ() && feet.getY() == goal.getY();
        return eye.distanceTo(Vec3.atCenterOf(goal)) <= reach;
    }

    private boolean arrivedAt(BlockPos cell) {
        return arrived(cell, new Vec3(cell.getX() + 0.5, cell.getY() + 1.62, cell.getZ() + 0.5));
    }

    private double estimate(BlockPos c) {
        if (reach > 0) return Math.max(0, Math.sqrt(c.above().distSqr(goal)) - reach);
        return Math.abs(c.getX() - goal.getX()) + Math.abs(c.getZ() - goal.getZ()) + Math.abs(c.getY() - goal.getY()) * 1.5;
    }

    // ------------------------------------------------------------------ route planning (A*)
    private static final class Node {
        final BlockPos pos;
        final double g, f;
        final Node parent;
        final Move move;

        Node(BlockPos pos, double g, double f, Node parent, Move move) {
            this.pos = pos;
            this.g = g;
            this.f = f;
            this.parent = parent;
            this.move = move;
        }
    }

    private boolean plan(Minecraft mc, BlockPos start) {
        toClear = new ArrayList<>();
        index = 0;
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        Map<Long, Double> best = new HashMap<>();
        Node first = new Node(start, 0, estimate(start) * 1.6, null, null);
        open.add(first);
        best.put(start.asLong(), 0.0);
        Node found = null, closest = first;
        double closestH = estimate(start);
        int expanded = 0;
        while (!open.isEmpty() && expanded < MAX_NODES) {
            Node n = open.poll();
            if (n.g > best.getOrDefault(n.pos.asLong(), Double.MAX_VALUE)) continue;
            expanded++;
            if (arrivedAt(n.pos)) {
                found = n;
                break;
            }
            double h = estimate(n.pos);
            if (h < closestH) {
                closestH = h;
                closest = n;
            }
            for (Direction d : HORIZONTAL) {
                for (int dy = -1; dy <= 1; dy++) {
                    Move m = move(mc, n.pos, d, dy);
                    if (m == null) continue;
                    double turn = n.move != null && n.move.dir() != d ? 0.15 : 0; // prefer straight lines
                    double g = n.g + m.cost() + turn;
                    long key = m.cell().asLong();
                    if (g >= best.getOrDefault(key, Double.MAX_VALUE)) continue;
                    best.put(key, g);
                    open.add(new Node(m.cell(), g, g + estimate(m.cell()) * 1.6, n, m));
                }
            }
        }
        Node end = found != null ? found : closest;
        if (end == first || (found == null && closestH > estimate(start) - 1)) {
            // A* found nothing useful (e.g. completely enclosed or the search ran out): try one plain step
            Move m = greedy(mc, start);
            if (m == null || ++failedPlans > 6) {
                problem = "Every way from here is blocked or dangerous (lava, water or a deep drop).";
                return false;
            }
            path = new ArrayList<>(List.of(m));
            return true;
        }
        failedPlans = 0;
        List<Move> route = new ArrayList<>();
        for (Node n = end; n.move != null; n = n.parent) route.add(n.move);
        Collections.reverse(route);
        path = route;
        if (!route.isEmpty()) lastDir = route.get(0).dir();
        return true;
    }

    /** Old behaviour as a fallback: one step towards the goal on the main axis. */
    private Move greedy(Minecraft mc, BlockPos feet) {
        int dx = goal.getX() - feet.getX(), dz = goal.getZ() - feet.getZ(), dy = Integer.signum(goal.getY() - feet.getY());
        Direction primary = dx == 0 && dz == 0 ? (lastDir != null ? lastDir : Direction.NORTH)
                : Math.abs(dx) >= Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
        for (Direction d : new Direction[]{primary, primary.getClockWise(), primary.getCounterClockWise(), primary.getOpposite()}) {
            Move m = move(mc, feet, d, dy);
            if (m == null && dy != 0) m = move(mc, feet, d, 0);
            if (m != null) return m;
        }
        return null;
    }

    /** A step from {@code feet} in direction d: level (dy 0, may drop), stairs up (1) or stairs down (-1). */
    private Move move(Minecraft mc, BlockPos feet, Direction d, int dy) {
        BlockPos next = feet.relative(d);
        List<BlockPos> clear = new ArrayList<>(3);
        BlockPos cell;
        double cost = 1;
        if (dy < 0) { // stairs down: the cell ahead and one lower (never straight down)
            cell = next.below();
            clear.add(next.above());
            clear.add(next);
            clear.add(cell);
            cost += 0.3;
        } else if (dy > 0) { // stairs up: needs room above our own head to jump
            cell = next.above();
            clear.add(feet.above(2));
            clear.add(cell.above());
            clear.add(cell);
            cost += 0.6;
        } else {
            cell = next;
            clear.add(next.above());
            clear.add(next);
        }
        if (cell.getY() <= mc.level.getMinY() + 1 || cell.getY() >= mc.level.getMaxY() - 2) return null;
        for (BlockPos b : clear) {
            BlockState s = mc.level.getBlockState(b);
            if (Bot.isLava(s)) return null;
            if (!Bot.passable(mc, b)) {
                if (Bot.unsafeToMine(mc, b)) return null;
                float hard = s.getDestroySpeed(mc.level, b);
                cost += 1.5 + hard * 2.5; // digging is slow – walk around when that is not much longer
            } else if (Bot.isWater(s)) cost += 2.5;
        }
        // something to stand on (allow a short drop)
        BlockPos floor = cell.below();
        int drop = 0;
        boolean digsFloor = false;
        for (BlockPos b : clear) if (b.equals(floor)) digsFloor = true;
        while ((digsFloor || !Bot.solid(mc, floor)) && drop < 4) {
            digsFloor = false;
            BlockState s = mc.level.getBlockState(floor);
            if (Bot.isLava(s)) return null;
            if (Bot.isWater(s)) break; // landing in water is fine
            floor = floor.below();
            drop++;
        }
        if (drop >= 4 || Bot.isLava(mc.level.getBlockState(floor.above()))) return null;
        String under = Bot.id(mc.level.getBlockState(floor));
        if (under.contains("magma") || under.contains("cactus") || under.contains("campfire")) return null;
        // never end next to lava
        BlockPos land = drop > 0 ? floor.above() : cell;
        for (Direction n : Direction.values()) if (Bot.isLava(mc.level.getBlockState(land.relative(n)))) return null;
        cost += drop * 0.6;
        clear.removeIf(b -> Bot.passable(mc, b));
        return new Move(feet.immutable(), land.immutable(), clear, cost, d);
    }
}
