package com.pglol.aotrpg;

import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Stuns: dazed for a beat after a guard is broken, a parry lands clean, a pressure point is hit
 * or a slam is countered. A stunned fighter can't swing and barely moves; the one who stunned
 * them gets a window. Then a moment of immunity, so nobody is stun-locked.
 */
public final class Stuns {
    private Stuns() { }

    private static final Map<UUID, long[]> stunned = new HashMap<>();

    public static void register() {
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) ->
            !world.isClient && stunned(player) ? ActionResult.FAIL : ActionResult.PASS);
    }

    public static boolean stunned(LivingEntity e) {
        long[] s = stunned.get(e.getUuid());
        return s != null && e.getWorld().getTime() < s[0];
    }

    /** Stuns for this many ticks (unless they've only just come out of one). True if it took. */
    public static boolean stun(LivingEntity e, int ticks) {
        if (e == null || !e.isAlive() || AotRpg.isTitan(e)) return false;
        long now = e.getWorld().getTime();
        long[] s = stunned.get(e.getUuid());
        if (s != null && now < s[1]) return false;
        stunned.put(e.getUuid(), new long[] {now + ticks, now + ticks + 40});
        if (stunned.size() > 512) stunned.values().removeIf(v -> v[1] < now);
        e.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, ticks, 4, false, false, false));
        e.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, ticks, 3, false, false, false));
        if (Troops.is(e)) Troops.stagger(e, ticks);
        if (e.getWorld() instanceof ServerWorld w) {
            w.playSound(null, e.getX(), e.getY() + 1, e.getZ(), SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK, SoundCategory.PLAYERS, 1f, 0.6f);
            w.playSound(null, e.getX(), e.getY() + 1, e.getZ(), SoundEvents.BLOCK_BELL_USE, SoundCategory.PLAYERS, 0.5f, 1.8f);
            Net.RushFx fx = new Net.RushFx(e.getId(), Adrenaline.FX_STUNNED, e.getYaw(), ticks);
            for (ServerPlayerEntity o : PlayerLookup.around(w, e.getPos(), 48)) if (ServerPlayNetworking.canSend(o, Net.RushFx.ID)) ServerPlayNetworking.send(o, fx);
        }
        return true;
    }

    public static void forget(UUID id) {
        stunned.remove(id);
    }
}
