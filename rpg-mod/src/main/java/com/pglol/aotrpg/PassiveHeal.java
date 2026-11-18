package com.pglol.aotrpg;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Out of the fight, you mend: 6 seconds after your last blow given or taken, health starts coming
 * back on its own, slowly at first and faster the longer you stay out of it (about 2% of your
 * health a second, up to 5%). Never in an arena match or a duel; at half the rate out on an
 * extraction run; not while down.
 */
public final class PassiveHeal {
    private PassiveHeal() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % 10 != 0) return;
            long now = System.currentTimeMillis();
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                if (!p.isAlive() || p.isSpectator() || p.getHealth() >= p.getMaxHealth()) continue;
                if (AotRpg.DOWNED.isDowned(p) || Arenas.inMatch(p) || TeamWatch.watching(p.getUuid())) continue;
                long since = now - Classes.lastCombat(p);
                if (since < 6000) continue;
                // 2% a second at first, building to 5% after half a minute out of it.
                float rate = 0.02f + 0.03f * Math.min(1, (since - 6000) / 30000f);
                if (Extraction.inRun(p.getUuid())) rate *= 0.5f;
                if (p.getHungerManager().getFoodLevel() <= 6) rate *= 0.5f;
                p.heal(Math.max(0.25f, p.getMaxHealth() * rate * 0.5f));
            }
        });
    }
}
