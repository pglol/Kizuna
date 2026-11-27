package net.pglol.kata;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * Being seen (and heard), for whoever's watching: AoT RPG's witness system, made general for Kata's
 * NPCs to come. A watcher sees in a 90 degree cone out to 16 blocks with a clear line of sight (less
 * in the dark, nothing while you're invisible); crouching halves how far, a crouch run is three
 * quarters. Close by they hear you: walking within 2.5 blocks, running within 6, a crouch run within
 * 3; a crouch walk, never.
 *
 * Seeing you builds their awareness, losing you lets it fade. 0.3 and up they're suspicious (they
 * turn to look); 1 is alerted (noticed you). Each player is told about the watchers around them, and
 * sees a mark over each that fills as they grow aware (an eye, "?", "!") and, crouched, HIDDEN /
 * SEEN / DISCOVERED under the crosshair.
 *
 * Who watches: anything a registered rule says (see {@link #watch}); training dummies and any living
 * thing tagged "kata_watcher" to begin with. Future NPCs add their own rule and read the levels
 * (level, watching, alerted, alert, calm, seenBy, seenByAny) to decide what they do.
 */
public final class Awareness {
    private Awareness() {}

    public static final double RANGE = 16, CONE_COS = Math.cos(Math.toRadians(45));
    public static final String TAG = "kata_watcher";

    private static final List<BiPredicate<LivingEntity, ServerPlayerEntity>> RULES = new ArrayList<>();

    private static final class Eye {
        float level;
        boolean sees;
        boolean alerted;
    }

    /** Per player: what every watcher near them makes of them. */
    private static final Map<UUID, Map<Integer, Eye>> EYES = new HashMap<>();
    private static final Map<UUID, Vec3d> LAST = new HashMap<>();
    private static final Map<UUID, Double> SPEED = new HashMap<>();
    private static MinecraftServer server;

    /** Add a rule for who can watch a player (future NPCs register theirs). */
    public static void watch(BiPredicate<LivingEntity, ServerPlayerEntity> rule) {
        RULES.add(rule);
    }

    public static void register() {
        watch((e, p) -> e instanceof Dummy);
        watch((e, p) -> e.getCommandTags().contains(TAG));
        ServerTickEvents.END_SERVER_TICK.register(Awareness::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> {
            EYES.remove(h.getPlayer().getUuid());
            LAST.remove(h.getPlayer().getUuid());
            SPEED.remove(h.getPlayer().getUuid());
        });
    }

    private static boolean watcher(LivingEntity e, ServerPlayerEntity p) {
        if (!e.isAlive() || e == p || e instanceof ServerPlayerEntity) return false;
        for (var r : RULES) if (r.test(e, p)) return true;
        return false;
    }

    /** Blocks a tick the player is going, from where they really are (the server's own view). */
    private static double speed(ServerPlayerEntity p) {
        return SPEED.getOrDefault(p.getUuid(), 0.0);
    }

    /** Crouched and moving fast: the crouch run. */
    public static boolean crouchRunning(ServerPlayerEntity p) {
        return p.isSneaking() && speed(p) > 0.13;
    }

    /** Can this watcher see (or hear) the player right now? */
    public static boolean sees(LivingEntity npc, ServerPlayerEntity p) {
        if (p.isInvisible() || p.isSpectator()) return false;
        double d = npc.distanceTo(p);
        boolean crouched = p.isSneaking(), crun = crouchRunning(p);
        double heard = crun ? 3 : crouched ? 0 : p.isSprinting() ? 6 : 2.5;
        if (d < heard) return true;
        double range = RANGE * (crun ? 0.75 : crouched ? 0.5 : 1);
        if (p.getServerWorld().getLightLevel(p.getBlockPos()) < 7) range *= 0.6;
        if (d > range) return false;
        float yaw = npc.getHeadYaw() * MathHelper.RADIANS_PER_DEGREE;
        Vec3d look = new Vec3d(-MathHelper.sin(yaw), 0, MathHelper.cos(yaw));
        Vec3d to = new Vec3d(p.getX() - npc.getX(), 0, p.getZ() - npc.getZ());
        if (to.lengthSquared() < 1e-4) return true;
        if (look.dotProduct(to.normalize()) < CONE_COS) return false;
        return npc.canSee(p);
    }

    /** How aware this watcher is of this player, 0 to 1. */
    public static float level(LivingEntity npc, ServerPlayerEntity p) {
        Map<Integer, Eye> m = EYES.get(p.getUuid());
        Eye e = m == null ? null : m.get(npc.getId());
        return e == null ? 0 : e.level;
    }

    /** The player this watcher is most aware of (0.15 and up), or null. */
    public static ServerPlayerEntity watching(LivingEntity npc) {
        if (server == null) return null;
        ServerPlayerEntity best = null;
        float lv = 0.15f;
        for (var en : EYES.entrySet()) {
            Eye e = en.getValue().get(npc.getId());
            if (e == null || e.level < lv) continue;
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(en.getKey());
            if (p != null && p.getWorld() == npc.getWorld()) {
                best = p;
                lv = e.level;
            }
        }
        return best;
    }

    /** Someone has fully noticed this player. */
    public static boolean alerted(ServerPlayerEntity p) {
        Map<Integer, Eye> m = EYES.get(p.getUuid());
        if (m == null) return false;
        for (Eye e : m.values()) if (e.alerted) return true;
        return false;
    }

    /** How many watchers can see this player now. */
    public static int seenBy(ServerPlayerEntity p) {
        Map<Integer, Eye> m = EYES.get(p.getUuid());
        if (m == null) return 0;
        int n = 0;
        for (Eye e : m.values()) if (e.sees) n++;
        return n;
    }

    /** Anyone able to see this player right now (checked on the spot). */
    public static boolean seenByAny(ServerPlayerEntity p) {
        for (LivingEntity npc : p.getServerWorld().getEntitiesByClass(LivingEntity.class, p.getBoundingBox().expand(24), n -> watcher(n, p)))
            if (sees(npc, p)) return true;
        return false;
    }

    /** Everyone who sees this player now becomes fully alert (they did something loud). */
    public static void alert(ServerPlayerEntity p) {
        Map<Integer, Eye> m = EYES.computeIfAbsent(p.getUuid(), k -> new HashMap<>());
        for (LivingEntity npc : p.getServerWorld().getEntitiesByClass(LivingEntity.class, p.getBoundingBox().expand(24), n -> watcher(n, p))) {
            if (!sees(npc, p)) continue;
            Eye e = m.computeIfAbsent(npc.getId(), k -> new Eye());
            e.level = 1;
            e.sees = true;
            e.alerted = true;
        }
        send(p, m);
    }

    /** Forget all awareness of this player (a fresh try). */
    public static void calm(ServerPlayerEntity p) {
        Map<Integer, Eye> m = EYES.get(p.getUuid());
        if (m == null) return;
        for (Eye e : m.values()) {
            e.level = 0;
            e.sees = e.alerted = false;
        }
        send(p, m);
        m.clear();
    }

    private static void tick(MinecraftServer s) {
        server = s;
        for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
            Vec3d now = p.getPos(), was = LAST.put(p.getUuid(), now);
            if (was != null) SPEED.put(p.getUuid(), Math.hypot(now.x - was.x, now.z - was.z));
        }
        if (s.getTicks() % 5 != 0) return;
        for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
            Map<Integer, Eye> m = EYES.computeIfAbsent(p.getUuid(), k -> new HashMap<>());
            if (p.isCreative() || p.isSpectator()) {
                if (!m.isEmpty()) calm(p);
                continue;
            }
            Map<Integer, Eye> next = new HashMap<>();
            for (LivingEntity npc : p.getServerWorld().getEntitiesByClass(LivingEntity.class, p.getBoundingBox().expand(24), n -> watcher(n, p))) {
                Eye e = m.getOrDefault(npc.getId(), new Eye());
                e.sees = sees(npc, p);
                if (e.sees) {
                    float close = 1 + (float) (1 - npc.distanceTo(p) / RANGE);
                    float pace = crouchRunning(p) ? 1.2f : p.isSneaking() ? 0.7f : p.isSprinting() ? 1.5f : 1f;
                    e.level = Math.min(1, e.level + 0.06f * close * pace);
                } else e.level = Math.max(0, e.level - 0.05f); // out of sight, they lose interest
                if (e.level >= 1) e.alerted = true;
                else if (e.level < 0.6f) e.alerted = false;
                if (e.level >= 0.3f && e.sees && npc instanceof MobEntity mob) mob.getLookControl().lookAt(p, 30, 30);
                if (e.level > 0.01f || e.sees || p.isSneaking()) next.put(npc.getId(), e);
            }
            // Watchers that dropped out: tell the player they're gone.
            for (var en : m.entrySet()) if (!next.containsKey(en.getKey())) tell(p, en.getKey(), 0);
            m.clear();
            m.putAll(next);
            send(p, m);
        }
    }

    private static void send(ServerPlayerEntity p, Map<Integer, Eye> m) {
        for (var en : m.entrySet()) {
            Eye e = en.getValue();
            tell(p, en.getKey(), Math.round(Math.min(1, e.level) * 100) | (e.sees ? 1 << 8 : 0) | (e.alerted ? 1 << 9 : 0));
        }
    }

    private static void tell(ServerPlayerEntity p, int entity, int value) {
        if (ServerPlayNetworking.canSend(p, KataNet.FightOf.ID)) ServerPlayNetworking.send(p, new KataNet.FightOf(entity, KataNet.AWARE, value));
    }
}
