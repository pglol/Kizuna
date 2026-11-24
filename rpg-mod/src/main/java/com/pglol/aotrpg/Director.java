package com.pglol.aotrpg;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The encounter director: one place that decides how busy the world is around each player, so
 * every spawner (wild groups, Marley's camps and hunters, roaming titans, ogres, hordes, titan
 * waves) asks before it adds anything.
 * <p>
 * Each player moves through a rhythm: CALM (exploring, quiet), BUILD (something's due: groups
 * placed ahead of you, the odd big titan), PEAK (a fight: nothing new joins it), RELAX (after a
 * fight: a breather to loot, heal and move on, longer after a long fight). In the open, CALM
 * turns to BUILD after a while, so there's never a long empty stretch either.
 */
public final class Director {
    private Director() {}

    public enum Phase { CALM, BUILD, PEAK, RELAX }

    /** What's asking: a group of people, a titan wave, a big titan (ogre, horde, abnormal), Marley's hunters. */
    public enum Kind { WILD, WAVE, BIG, HUNT }

    private static final class St {
        Phase phase = Phase.CALM;
        long phaseAt = System.currentTimeMillis(), fightStart, lastFight, relaxUntil, lastSpawn;
    }

    private static final Map<UUID, St> states = new HashMap<>();
    /** A fight is over once nothing's happened for this long. */
    private static final long FIGHT_OVER = 8_000;
    /** Quiet this long and something comes looking. */
    private static final long CALM_FOR = 25_000;
    /** At least this long between new groups near the same player. */
    private static final long SPAWN_GAP = 14_000;

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (s.getTicks() % 20 != 5) return;
            long now = System.currentTimeMillis();
            for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) update(p, now);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> states.remove(h.player.getUuid()));
    }

    private static St st(ServerPlayerEntity p) {
        return states.computeIfAbsent(p.getUuid(), k -> new St());
    }

    /** In a fight right now: struck or striking lately, people coming at you, a titan on you. */
    public static boolean fighting(ServerPlayerEntity p) {
        if (Classes.inCombat(p) || Troops.engaging(p, 40) > 0) return true;
        if (!(p.getWorld() instanceof ServerWorld w)) return false;
        return !w.getEntitiesByClass(MobEntity.class, new Box(p.getBlockPos()).expand(48, 32, 48),
            m -> m.isAlive() && m.getTarget() == p && AotRpg.isTitan(m)).isEmpty();
    }

    /** Fighting people (not only titans): the time titans should leave you be. */
    public static boolean fightingPeople(ServerPlayerEntity p) {
        return Troops.engaging(p, 40) > 0;
    }

    private static void update(ServerPlayerEntity p, long now) {
        St s = st(p);
        boolean fight = !p.isSpectator() && fighting(p);
        if (fight) {
            s.lastFight = now;
            if (s.phase != Phase.PEAK) {
                s.phase = Phase.PEAK;
                s.phaseAt = s.fightStart = now;
            }
            return;
        }
        switch (s.phase) {
            case PEAK -> {
                if (now - s.lastFight < FIGHT_OVER) return;
                // A breather that grows with the fight: half a minute after a skirmish, a minute and a half after a battle.
                long lasted = s.lastFight - s.fightStart;
                s.relaxUntil = now + Math.max(18_000, Math.min(60_000, 18_000 + lasted / 3));
                s.phase = Phase.RELAX;
                s.phaseAt = now;
            }
            case RELAX -> {
                if (now < s.relaxUntil) return;
                s.phase = Phase.CALM;
                s.phaseAt = now;
            }
            case CALM -> {
                if (now - s.phaseAt >= CALM_FOR) {
                    s.phase = Phase.BUILD;
                    s.phaseAt = now;
                }
            }
            case BUILD -> { }
        }
    }

    public static Phase phase(ServerPlayerEntity p) {
        return st(p).phase;
    }

    /** May this kind of thing turn up near this player now? */
    public static boolean allow(ServerPlayerEntity p, Kind k) {
        St s = st(p);
        long now = System.currentTimeMillis();
        if (s.phase == Phase.PEAK || s.phase == Phase.RELAX) return false;
        return switch (k) {
            // People: spaced out, never a pile at once.
            case WILD -> now - s.lastSpawn >= SPAWN_GAP;
            // Titan waves and big titans: only once the quiet has gone on a while.
            case WAVE, BIG -> s.phase == Phase.BUILD;
            // Marley's hunters keep coming, but not into the middle of a fight or the breather after it.
            case HUNT -> true;
        };
    }

    /** May this turn up here? Asks everyone within r of the spot (all must agree). */
    public static boolean allowAt(ServerWorld w, Vec3d at, double r, Kind k) {
        for (ServerPlayerEntity p : w.getPlayers()) {
            if (p.isSpectator() || p.getPos().squaredDistanceTo(at) > r * r) continue;
            if (!allow(p, k)) return false;
        }
        return true;
    }

    /** Something was set going near this player (and everyone close by): space the next one out. */
    public static void spawned(ServerWorld w, Vec3d at, double r) {
        long now = System.currentTimeMillis();
        for (ServerPlayerEntity p : w.getPlayers()) {
            if (p.getPos().squaredDistanceTo(at) > r * r) continue;
            St s = st(p);
            s.lastSpawn = now;
            // Something's on its way: the build-up is spent.
            if (s.phase == Phase.BUILD) {
                s.phase = Phase.CALM;
                s.phaseAt = now;
            }
        }
    }

    /** Where to put something for a player on the move: ahead of them, so it isn't left behind. */
    public static double aheadAngle(ServerPlayerEntity p, net.minecraft.util.math.random.Random r) {
        Vec3d v = p.getVelocity();
        if (v.horizontalLengthSquared() > 0.25 * 0.25) {
            double a = Math.atan2(v.z, v.x);
            return a + (r.nextDouble() - 0.5) * 1.3;
        }
        return r.nextDouble() * Math.PI * 2;
    }
}
