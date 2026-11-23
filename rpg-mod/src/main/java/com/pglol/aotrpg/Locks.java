package com.pglol.aotrpg;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Held, pinned, stunned, or in a finisher (either side of it): no swinging. The server refuses the
 * blow, and the player's own client is told so it doesn't even swing (no arm, no sound).
 */
public final class Locks {
    private Locks() {}

    private static final Set<UUID> told = new HashSet<>();

    public static boolean locked(LivingEntity e) {
        if (e == null) return false;
        return Stuns.stunned(e) || Impact.pinned(e) || Throws.holding(e) || Troops.held(e) || Finishers.inScene(e);
    }

    /** In someone's hands, pinned, or on the losing end of a finisher. */
    public static boolean held(LivingEntity e) {
        return e != null && (Impact.pinned(e) || Throws.holding(e) || Troops.held(e) || Finishers.inScene(e));
    }

    public static void register() {
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) ->
            !world.isClient && locked(player) ? ActionResult.FAIL : ActionResult.PASS);
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> told.remove(h.player.getUuid()));
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
                boolean now = locked(p), was = told.contains(p.getUuid());
                if (now == was) continue;
                if (now) told.add(p.getUuid());
                else told.remove(p.getUuid());
                if (ServerPlayNetworking.canSend(p, Net.Lock.ID)) ServerPlayNetworking.send(p, new Net.Lock(now));
            }
        });
    }
}
