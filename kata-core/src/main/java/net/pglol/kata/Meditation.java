package net.pglol.kata;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Meditating (the menu is open): the fighter settles into a meditation pose where they are. After a
 * windup, and only out of combat (no strike given or taken for five seconds), they can't be touched:
 * no damage, and enemies lose interest. Moving off the spot, striking, or being hit before it settles
 * ends it. Everyone around sees the state (KataNet.MEDITATE: 0 none, 1 settling, 2 untouchable).
 */
public final class Meditation {
    private Meditation() {}

    /** Ticks of settling before the untouchable part can begin, and how long since the last fight it needs. */
    public static final int WINDUP = 30, CALM = 100;

    private record State(int start, Vec3d at, int shown) { }

    private static final Map<UUID, State> ON = new HashMap<>();
    /** The last server tick each fighter gave or took a strike. */
    private static final Map<UUID, Integer> LAST_COMBAT = new HashMap<>();

    static void register() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((e, src, amount) -> {
            if (e instanceof ServerPlayerEntity p) {
                if (untouchable(p)) return false;
                if (ON.containsKey(p.getUuid())) stop(p); // hit while still settling: it's broken
            }
            return true;
        });
    }

    /** A strike was given or taken: in combat for the next five seconds. */
    static void combat(net.minecraft.entity.LivingEntity e) {
        if (!(e instanceof ServerPlayerEntity p) || p.getServer() == null) return;
        LAST_COMBAT.put(p.getUuid(), p.getServer().getTicks());
        if (LAST_COMBAT.size() > 1024) LAST_COMBAT.clear();
        State s = ON.get(p.getUuid());
        if (s != null && s.shown < 2) stop(p);
    }

    static void request(ServerPlayerEntity p, boolean on) {
        if (!on) {
            stop(p);
            return;
        }
        if (ON.containsKey(p.getUuid()) || !p.isAlive() || p.isSpectator()) return;
        ON.put(p.getUuid(), new State(p.getServer().getTicks(), p.getPos(), 1));
        Fighting.relayAround(p, KataNet.MEDITATE, 1);
    }

    public static void stop(ServerPlayerEntity p) {
        if (ON.remove(p.getUuid()) != null) Fighting.relayAround(p, KataNet.MEDITATE, 0);
    }

    public static boolean meditating(net.minecraft.entity.Entity e) {
        return e instanceof ServerPlayerEntity p && ON.containsKey(p.getUuid());
    }

    /** Settled, and out of combat: nothing touches them. */
    public static boolean untouchable(net.minecraft.entity.Entity e) {
        if (!(e instanceof ServerPlayerEntity p)) return false;
        State s = ON.get(p.getUuid());
        return s != null && s.shown == 2;
    }

    static void tick(MinecraftServer server) {
        if (ON.isEmpty()) return;
        int now = server.getTicks();
        for (var it = ON.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(en.getKey());
            State s = en.getValue();
            if (p == null || !p.isAlive()) {
                it.remove();
                continue;
            }
            // Walked off the spot (or was carried off it): it's over.
            if (p.getPos().squaredDistanceTo(s.at) > 0.8 * 0.8) {
                it.remove();
                Fighting.relayAround(p, KataNet.MEDITATE, 0);
                continue;
            }
            int last = LAST_COMBAT.getOrDefault(p.getUuid(), -9999);
            boolean settled = now - s.start >= WINDUP && now - last >= CALM;
            if (settled && s.shown != 2) {
                en.setValue(new State(s.start, s.at, 2));
                Fighting.relayAround(p, KataNet.MEDITATE, 2);
            }
            // Enemies lose interest.
            if (settled && now % 10 == 0) {
                for (MobEntity m : p.getServerWorld().getEntitiesByClass(MobEntity.class, p.getBoundingBox().expand(32), m -> m.getTarget() == p)) m.setTarget(null);
            }
        }
    }
}
