package com.pglol.aotrpg;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The gas chase. Someone's stuck in a wall or flying from a blow: strike at them from range with
 * ODM gear on and a burst of gas fires you straight at them, blades first. You arrive cutting, and
 * the cut carries on: still in the wall, they go on through it; in the air, they're knocked on
 * again. Strike again while they're still flying and you're off after them once more. The chain
 * runs as long as you have gas and they have nowhere to land.
 */
public final class Pursuit {
    private Pursuit() {}

    private static final double RANGE = 34, MIN = 2.6;

    private static final class Chase {
        final LivingEntity target;
        final long at;
        int combo;

        Chase(LivingEntity target, long at, int combo) {
            this.target = target;
            this.at = at;
            this.combo = combo;
        }
    }

    private static final Map<UUID, Chase> chasing = new HashMap<>();
    /** The running combo per player, and when it was last added to (it drops after a pause). */
    private static final Map<UUID, long[]> combos = new HashMap<>();

    public static boolean chasing(ServerPlayerEntity p) {
        return chasing.containsKey(p.getUuid());
    }

    /** Wide open to it: stuck in a wall, smashed through one, or in the air from a blow. */
    public static boolean open(LivingEntity e) {
        return Impact.pinned(e) || Impact.thrown(e) || Impact.ragdolled(e);
    }

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(Net.Pursue.ID, (payload, ctx) -> start(ctx.player(), payload.entity()));
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (chasing.isEmpty()) return;
            for (var en : new ArrayList<>(chasing.entrySet())) {
                ServerPlayerEntity p = s.getPlayerManager().getPlayer(en.getKey());
                if (p == null) {
                    chasing.remove(en.getKey());
                    continue;
                }
                tick(p, en.getValue());
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> {
            chasing.remove(h.player.getUuid());
            combos.remove(h.player.getUuid());
        });
    }

    private static void start(ServerPlayerEntity p, int id) {
        ServerWorld w = p.getServerWorld();
        if (chasing(p) || p.isSpectator() || p.hasVehicle() || AotRpg.DOWNED.isDowned(p) || Locks.locked(p) || Cinematics.busy(p)) return;
        if (!(w.getEntityById(id) instanceof LivingEntity t) || !t.isAlive() || !open(t) || !AirMoves.foe(p, t)) return;
        if (!Guard.melee(p.getMainHandStack()) || AotItems.isApgGun(p.getMainHandStack()) || OdmBoost.gear(p).isEmpty()) return;
        Vec3d c = t.getBoundingBox().getCenter();
        double d = p.getEyePos().distanceTo(c);
        if (d > RANGE || d < MIN) return;
        // A clear line to them (the wall they're stuck in doesn't count).
        var hit = w.raycast(new RaycastContext(p.getEyePos(), c, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p));
        if (hit.getType() == HitResult.Type.BLOCK && hit.getPos().distanceTo(c) > 1.6) return;
        if (!AotRpg.ODM.burst(p)) {
            p.sendMessage(net.minecraft.text.Text.literal("Out of gas.").formatted(net.minecraft.util.Formatting.RED), true);
            return;
        }
        long now = w.getTime();
        long[] cb = combos.computeIfAbsent(p.getUuid(), k -> new long[] {0, 0});
        if (now - cb[1] > 70) cb[0] = 0;
        chasing.put(p.getUuid(), new Chase(t, now, (int) cb[0]));
        p.fallDistance = 0;
        // Off like a shot: a jet of gas behind, a boom of air in front.
        w.spawnParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 0.9, p.getZ(), 20, 0.3, 0.3, 0.3, 0.12);
        w.spawnParticles(ParticleTypes.POOF, p.getX(), p.getY() + 0.8, p.getZ(), 10, 0.2, 0.2, 0.2, 0.05);
        AotParticles.one(w, AotParticles.fx(AotParticles.RING, 0xEAF4FF, 3f), p.getX(), p.getY() + 1, p.getZ(), 0, 0, 0);
        w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_BREEZE_WIND_BURST.value(), SoundCategory.PLAYERS, 1f, 1.1f);
        w.playSound(null, p.getBlockPos(), HitSounds.WHOOSH, SoundCategory.PLAYERS, 1.1f, 0.8f);
        Net.RushFx fx = new Net.RushFx(p.getId(), Adrenaline.FX_AFTER, p.getYaw(), 0xEAF4FF);
        for (ServerPlayerEntity o : PlayerLookup.around(w, p.getPos(), 64)) if (ServerPlayNetworking.canSend(o, Net.RushFx.ID)) ServerPlayNetworking.send(o, fx);
    }

    private static void tick(ServerPlayerEntity p, Chase c) {
        ServerWorld w = p.getServerWorld();
        LivingEntity t = c.target;
        long now = w.getTime();
        if (!t.isAlive() || t.isRemoved() || t.getWorld() != w || now - c.at > 30 || Locks.locked(p) || AotRpg.DOWNED.isDowned(p)) {
            chasing.remove(p.getUuid());
            return;
        }
        Vec3d aim = t.getBoundingBox().getCenter();
        Vec3d me = p.getPos().add(0, p.getHeight() * 0.5, 0);
        Vec3d to = aim.subtract(me);
        double d = to.length();
        if (d < 2.2 + t.getWidth() * 0.5) {
            chasing.remove(p.getUuid());
            strike(w, p, t, to.normalize(), c);
            return;
        }
        // Homing in: fast, and faster still the further there is to go.
        double speed = Math.min(d - 1.5, 1.6 + Math.min(1.2, d * 0.04));
        p.setVelocity(to.normalize().multiply(speed));
        p.velocityModified = true;
        p.fallDistance = 0;
        if (now % 2 == 0) {
            w.spawnParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 0.9, p.getZ(), 3, 0.15, 0.15, 0.15, 0.02);
            AotParticles.burst(w, AotParticles.STREAK, 0xEAF4FF, 0.6f, p.getX(), p.getY() + 1, p.getZ(), 3, 0.2, 0.3);
        }
    }

    private static void strike(ServerWorld w, ServerPlayerEntity p, LivingEntity t, Vec3d dir, Chase c) {
        long now = w.getTime();
        long[] cb = combos.computeIfAbsent(p.getUuid(), k -> new long[] {0, 0});
        cb[0] = c.combo + 1;
        cb[1] = now;
        var atk = p.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE);
        float base = atk == null ? 6 : (float) atk.getValue();
        // Each link in the chain hits a little harder.
        float dmg = base * (1.15f + 0.12f * Math.min(6, c.combo));
        if (AotRpg.isTitan(t)) dmg *= 0.5f;
        boolean wasPinned = Impact.pinned(t);
        t.timeUntilRegen = 0;
        boolean landed = t.damage(w.getDamageSources().playerAttack(p), dmg);
        // In the air (not stuck): the cut sends them on, the way you were going.
        if (landed && !wasPinned && !Impact.ragdolled(t) && !AotRpg.isTitan(t)) {
            Impact.launch(t, p, 1.5 + 0.1 * Math.min(5, c.combo));
        }
        // You stop dead in the air where you struck, ready for the next one.
        p.setVelocity(p.getVelocity().multiply(0.12).add(0, 0.32, 0));
        p.velocityModified = true;
        p.fallDistance = 0;
        Vec3d at = t.getBoundingBox().getCenter();
        p.swingHand(net.minecraft.util.Hand.MAIN_HAND, true);
        AotParticles.one(w, AotParticles.fx(AotParticles.ARC, 0xFFFFFF, 3.5f), at.x, at.y, at.z, 0, 0, 0);
        AotParticles.one(w, AotParticles.fx(AotParticles.FLASH, 0xFFFFFF, 5f), at.x, at.y, at.z, 0, 0, 0);
        AotParticles.burst(w, AotParticles.STREAK, 0xFFFFFF, 0.8f, at.x, at.y, at.z, 10, 0.2, 0.8);
        AotParticles.burst(w, AotParticles.SPARK, 0xFFE8A8, 0.5f, at.x, at.y, at.z, 10, 0.3, 0.5);
        Impact.frame(w, at, 0.9f + 0.05f * Math.min(6, c.combo), p, t);
        w.playSound(null, t.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 1f, 1.2f);
        w.playSound(null, t.getBlockPos(), HitSounds.HEAVY, SoundCategory.PLAYERS, 1.1f, 0.95f + 0.04f * Math.min(6, c.combo));
        if (ServerPlayNetworking.canSend(p, Net.RushFx.ID)) ServerPlayNetworking.send(p, new Net.RushFx(p.getId(), Adrenaline.FX_CHAIN, 0, (int) cb[0]));
        Adrenaline.gain(p, 6);
    }
}
