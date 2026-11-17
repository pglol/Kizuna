package com.pglol.aotrpg;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Finishers: a foe who's down (stunned, pinned to a wall, face down in the dirt) and nearly done
 * can be finished. You're on them in a blink, the world holds its breath for half a second, and
 * one cut ends it, for everyone nearby to see.
 */
public final class Finishers {
    private Finishers() { }

    public static final int KIND = 11;

    private record Pending(ServerPlayerEntity by, LivingEntity on, Vec3d at, int[] left) { }

    private static final List<Pending> pending = new ArrayList<>();

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(s -> tick());
    }

    /** Down and nearly done. */
    public static boolean finishable(LivingEntity e) {
        if (e == null || !e.isAlive() || AotRpg.isTitan(e)) return false;
        if (!(Stuns.stunned(e) || Impact.pinned(e))) return false;
        float limit = e instanceof ServerPlayerEntity ? 0.25f : 0.2f;
        return e.getHealth() <= e.getMaxHealth() * limit;
    }

    public static void finish(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        for (Pending q : pending) if (q.by() == p) return;
        Vec3d look = p.getRotationVec(1f).multiply(1, 0, 1).normalize();
        LivingEntity best = null;
        double bd = 4.2 * 4.2;
        for (Entity en : w.getOtherEntities(p, p.getBoundingBox().expand(4.5, 2, 4.5), x -> x instanceof LivingEntity le && finishable(le))) {
            Vec3d to = en.getPos().subtract(p.getPos()).multiply(1, 0, 1);
            double d = to.lengthSquared();
            if (d > bd || d > 1e-4 && to.normalize().dotProduct(look) < 0.3) continue;
            bd = d;
            best = (LivingEntity) en;
        }
        if (best == null) return;
        // On them in a blink, facing them.
        Vec3d from = best.getPos().subtract(look.multiply(1.3));
        p.requestTeleport(from.x, best.getY(), from.z);
        Stuns.stun(best, 20);
        pending.add(new Pending(p, best, from, new int[] {10}));
        Net.RushFx fx = new Net.RushFx(p.getId(), Adrenaline.FX_FINISH, p.getYaw(), best.getId());
        for (ServerPlayerEntity o : PlayerLookup.around(w, best.getPos(), 40)) if (ServerPlayNetworking.canSend(o, Net.RushFx.ID)) ServerPlayNetworking.send(o, fx);
        Impact.frame(w, best.getPos().add(0, 1, 0), 1.3f, p, best);
        for (ServerPlayerEntity o : PlayerLookup.around(w, best.getPos(), 24)) {
            if (ServerPlayNetworking.canSend(o, Net.ImpactFx.ID)) ServerPlayNetworking.send(o, new Net.ImpactFx(best.getX(), best.getY() + 1, best.getZ(), 1f));
        }
        w.playSound(null, best.getBlockPos(), SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 1.5f, 0.6f);
        w.playSound(null, best.getBlockPos(), SoundEvents.ITEM_TRIDENT_RETURN, SoundCategory.PLAYERS, 1.2f, 0.5f);
    }

    private static void tick() {
        if (pending.isEmpty()) return;
        for (Pending q : new ArrayList<>(pending)) {
            ServerPlayerEntity p = q.by();
            LivingEntity v = q.on();
            if (!p.isAlive() || !v.isAlive() || p.getWorld() != v.getWorld()) {
                pending.remove(q);
                continue;
            }
            // The held breath: both still.
            p.setVelocity(Vec3d.ZERO);
            p.velocityModified = true;
            v.setVelocity(Vec3d.ZERO);
            v.velocityModified = true;
            if (--q.left()[0] > 0) continue;
            pending.remove(q);
            ServerWorld w = p.getServerWorld();
            Vec3d at = v.getPos().add(0, v.getHeight() * 0.6, 0);
            v.timeUntilRegen = 0;
            v.damage(w.getDamageSources().playerAttack(p), v.getHealth() + 60);
            AotParticles.one(w, AotParticles.fx(AotParticles.FLASH, 0xFFFFFF, 9f), at.x, at.y, at.z, 0, 0, 0);
            AotParticles.one(w, AotParticles.fx(AotParticles.BURST, 0xFF3A2A, 6f), at.x, at.y, at.z, 0, 0, 0);
            AotParticles.one(w, AotParticles.fx(AotParticles.ARC, 0xFFFFFF, 5f), at.x, at.y, at.z, 0, 0, 0);
            AotParticles.burst(w, AotParticles.STREAK, 0xFFFFFF, 1f, at.x, at.y, at.z, 24, 0.2, 1.2);
            AotParticles.burst(w, AotParticles.SPARK, 0xFF5A3A, 0.6f, at.x, at.y, at.z, 30, 0.3, 0.9);
            Impact.frame(w, at, 1.4f, p, v);
            w.playSound(null, v.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, SoundCategory.PLAYERS, 1.5f, 0.5f);
            w.playSound(null, v.getBlockPos(), SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.PLAYERS, 0.4f, 1.8f);
            for (ServerPlayerEntity o : PlayerLookup.around(w, at, 24)) if (ServerPlayNetworking.canSend(o, Net.Quake.ID)) ServerPlayNetworking.send(o, new Net.Quake(0.5f, 10));
            Adrenaline.gain(p, 30);
        }
    }
}
