package com.pglol.aotrpg;

import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stops a modified client hammering the server with requests. Each kind of action has a bucket per
 * player: a burst is fine (moving a whole stash across at once), but it only refills at a steady
 * rate, so a flood runs dry and is ignored (and a player who keeps at it is logged).
 */
public final class RateLimit {
    private RateLimit() {}

    private static final Map<String, double[]> buckets = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> strikes = new ConcurrentHashMap<>();

    /** One allowed every intervalMs on average, with a burst of up to `burst` at once. */
    public static boolean allow(ServerPlayerEntity p, String what, long intervalMs, int burst) {
        String k = p.getUuid() + ":" + what;
        long now = System.currentTimeMillis();
        double[] b = buckets.computeIfAbsent(k, x -> new double[] {burst, now});
        b[0] = Math.min(burst, b[0] + (now - b[1]) / (double) Math.max(1, intervalMs));
        b[1] = now;
        if (b[0] < 1) {
            int n = strikes.merge(p.getUuid(), 1, Integer::sum);
            if (n % 200 == 0) AotRpg.LOG.warn("{} is sending '{}' far too fast ({} ignored)", p.getName().getString(), what, n);
            return false;
        }
        b[0] -= 1;
        if (buckets.size() > 20_000) buckets.clear();
        return true;
    }

    public static boolean allow(ServerPlayerEntity p, String what, long intervalMs) {
        return allow(p, what, intervalMs, 1);
    }
}
