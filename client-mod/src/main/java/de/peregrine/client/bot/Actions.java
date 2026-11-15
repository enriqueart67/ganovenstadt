package de.peregrine.client.bot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/** Small building blocks for your own commands: wait, say, chat, eat, pick up, follow, repeat … */
public final class Actions {
    private Actions() {
    }

    /** Does nothing for a while. */
    public static final class Wait implements Bot.Task {
        private final int ticks;
        private int t;

        public Wait(int seconds) {
            this.ticks = Math.max(1, seconds) * 20;
        }

        @Override
        public boolean idle() {
            return true;
        }

        @Override
        public String status() {
            return "Waiting " + Math.max(0, (ticks - t + 19) / 20) + " s";
        }

        @Override
        public boolean tick(Minecraft mc) {
            Bot.release(mc);
            return ++t >= ticks;
        }
    }

    /** A line in the assistant window (and the action bar). */
    public static final class Say implements Bot.Task {
        private final String text;

        public Say(String text) {
            this.text = text;
        }

        @Override
        public String status() {
            return "Saying something";
        }

        @Override
        public boolean tick(Minecraft mc) {
            Bot.say(text);
            return true;
        }
    }

    /** Writes into the real game chat; text starting with / runs as a command (it needs the rights for it). */
    public static final class Chat implements Bot.Task {
        private final String text;

        public Chat(String text) {
            this.text = text;
        }

        @Override
        public String status() {
            return "Chat: " + text;
        }

        @Override
        public boolean tick(Minecraft mc) {
            if (mc.getConnection() == null) return true;
            if (text.startsWith("/")) mc.getConnection().sendCommand(text.substring(1));
            else mc.getConnection().sendChat(text);
            return true;
        }
    }

    /** Eats until the food bar is full (or nothing edible is left). */
    public static final class Eat implements Bot.Task {
        private int t;

        @Override
        public boolean idle() {
            return true;
        }

        @Override
        public String status() {
            return "Eating";
        }

        @Override
        public boolean tick(Minecraft mc) {
            LocalPlayer p = mc.player;
            if (p.getFoodData().getFoodLevel() >= 20 || ++t > 20 * 30) {
                Bot.release(mc);
                return true;
            }
            if (p.isUsingItem()) return false;
            if (!Guard.eatNow(mc, false)) {
                Bot.release(mc);
                if (t < 5) Bot.note("Nothing to eat.");
                return true;
            }
            return false;
        }
    }

    /** Walks over every dropped item within 16 blocks. */
    public static final class Collect implements Bot.Task {
        private Navigator nav;
        private ItemEntity item;
        private int tries;

        @Override
        public String status() {
            return "Picking up items";
        }

        @Override
        public void unstuck(Minecraft mc) {
            nav = null;
            item = null;
        }

        @Override
        public boolean tick(Minecraft mc) {
            LocalPlayer p = mc.player;
            if (item == null || !item.isAlive()) {
                nav = null;
                item = mc.level.getEntitiesOfClass(ItemEntity.class, new AABB(p.blockPosition()).inflate(16), ItemEntity::isAlive).stream()
                        .min(Comparator.comparingDouble(e -> e.distanceToSqr(p))).orElse(null);
                if (item == null || ++tries > 40) {
                    Bot.release(mc);
                    return true;
                }
            }
            if (nav == null) {
                nav = new Navigator(item.blockPosition(), 0, p.getDirection());
                nav.timeout = 20 * 8;
            }
            if (nav.tick(mc)) {
                nav = null;
                if (item.isAlive() && item.distanceToSqr(p) > 2) item = null; // could not reach it: next one
            }
            return false;
        }
    }

    /** Stays close to a player until you say stop (or for a set time). */
    public static final class Follow implements Bot.Task {
        private final String who;
        private final int maxTicks;
        private Navigator nav;
        private int t, replan;
        private boolean close;

        public Follow(String who, int seconds) {
            this.who = who;
            this.maxTicks = seconds <= 0 ? Integer.MAX_VALUE : seconds * 20;
        }

        @Override
        public boolean idle() {
            return close;
        }

        @Override
        public String status() {
            return "Following " + (who == null ? "the nearest player" : who);
        }

        @Override
        public void unstuck(Minecraft mc) {
            nav = null;
        }

        private Player find(Minecraft mc) {
            Player best = null;
            double bd = Double.MAX_VALUE;
            for (Player o : mc.level.players()) {
                if (o == mc.player) continue;
                if (who != null && !o.getName().getString().equalsIgnoreCase(who)) continue;
                double d = o.distanceToSqr(mc.player);
                if (d < bd) {
                    bd = d;
                    best = o;
                }
            }
            return best;
        }

        @Override
        public boolean tick(Minecraft mc) {
            if (++t > maxTicks) return true;
            Player o = find(mc);
            if (o == null) {
                Bot.fail("I can't see " + (who == null ? "anyone" : who) + " to follow.");
                return false;
            }
            double d = o.distanceTo(mc.player);
            close = d < 3.5;
            if (close) {
                nav = null;
                Bot.release(mc);
                Bot.look(mc.player, o.getEyePosition());
                return false;
            }
            if (nav == null || ++replan > 30) { // the target moves – plan again every 1.5 s
                replan = 0;
                nav = new Navigator(o.blockPosition(), 2.5, mc.player.getDirection());
            }
            if (nav.tick(mc)) nav = null;
            return false;
        }
    }

    /** Throws away junk (dirt, gravel, too much stone …). */
    public static final class DropJunk implements Bot.Task {
        @Override
        public String status() {
            return "Clearing the inventory";
        }

        @Override
        public boolean tick(Minecraft mc) {
            if (!Bot.makeRoom(mc)) Bot.note("Nothing to throw away.");
            return true;
        }
    }

    /** Runs the same steps several times – or until you say stop. */
    public static final class Repeat implements Bot.Task {
        private final List<Supplier<Bot.Task>> steps;
        private final List<String> names;
        private final int times; // <= 0 = forever
        private int round;
        private Commands.Chain current;

        public Repeat(List<Supplier<Bot.Task>> steps, List<String> names, int times) {
            this.steps = steps;
            this.names = names;
            this.times = times;
        }

        @Override
        public String status() {
            String r = "Round " + (round + 1) + (times > 0 ? "/" + times : "");
            return current == null ? r : r + " · " + current.status();
        }

        @Override
        public boolean idle() {
            return current != null && current.idle();
        }

        @Override
        public void unstuck(Minecraft mc) {
            if (current != null) current.unstuck(mc);
        }

        private int failedRounds;

        /** A step inside failed: go on with the next step, or the next round. Three failed rounds in a row end it. */
        boolean skip() {
            if (current != null && current.skip()) return true;
            current = null;
            round++;
            if (++failedRounds >= 3) return false;
            return times <= 0 || round < times;
        }

        @Override
        public boolean tick(Minecraft mc) {
            if (current == null) current = new Commands.Chain(steps, names);
            if (current.tick(mc)) {
                current = null;
                round++;
                failedRounds = 0;
                Bot.release(mc);
                if (times > 0 && round >= times) return true;
            }
            return false;
        }
    }

    /** Goes back to where the command was given. */
    public static Bot.Task back(BlockPos where) {
        return new Tasks.GoTo(where, "the start");
    }

    static Vec3 eye(Minecraft mc) {
        return mc.player.getEyePosition();
    }
}
