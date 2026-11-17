package com.pglol.aotrpg;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Sending someone flying. A big enough blow (an overpowering clash, a counter off a perfect parry,
 * a guard turned, a troop's shove) launches them; if they hit a wall on the way they slam into it
 * and hang there pinned, limp, for a second and a half: open to whatever comes next.
 */
public final class Impact {
    private Impact() {}

    private record Flight(LivingEntity e, Vec3d dir, long until) { }

    private record Pin(LivingEntity e, Vec3d at, long until) { }

    private static final Map<UUID, Flight> flying = new HashMap<>();
    private static final Map<UUID, Pin> pinned = new HashMap<>();
    public static final int PIN_TICKS = 30;

    public static void register() {
        // Pinned to a wall: no swinging back until you peel off it.
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) ->
            !world.isClient && pinned.containsKey(player.getUuid()) ? ActionResult.FAIL : ActionResult.PASS);
        ServerLivingEntityEvents.AFTER_DEATH.register((e, src) -> {
            flying.remove(e.getUuid());
            pinned.remove(e.getUuid());
        });
    }

    public static boolean pinned(LivingEntity e) {
        return pinned.containsKey(e.getUuid());
    }

    /** Throws someone (away from `from`) with this much force; watched for a wall for the next ~0.6s. */
    public static void launch(LivingEntity e, net.minecraft.entity.Entity from, double strength) {
        if (e == null || from == null || pinned(e)) return;
        Vec3d d = e.getPos().subtract(from.getPos()).multiply(1, 0, 1);
        if (d.lengthSquared() < 1e-4) d = from.getRotationVec(1f).multiply(1, 0, 1);
        if (d.lengthSquared() < 1e-4) return;
        d = d.normalize();
        e.addVelocity(d.x * strength, 0.22 + strength * 0.08, d.z * strength);
        e.velocityModified = true;
        if (strength >= 0.9) flying.put(e.getUuid(), new Flight(e, d, e.getWorld().getTime() + 12));
        // A big one cuts to an impact frame for the two of them.
        if (strength >= 1.1 && e.getWorld() instanceof ServerWorld w) {
            Vec3d at = e.getPos().add(from.getPos()).multiply(0.5).add(0, 1.2, 0);
            frame(w, at, (float) Math.min(1.2, strength / 1.5), e, from);
            AotParticles.one(w, AotParticles.fx(AotParticles.FLASH, 0xFFFFFF, 6f), at.x, at.y, at.z, 0, 0, 0);
            AotParticles.one(w, AotParticles.fx(AotParticles.BURST, 0xFFF4D8, 4f), at.x, at.y, at.z, 0, 0, 0);
            AotParticles.burst(w, AotParticles.SPARK, 0xFFE8A8, 0.6f, at.x, at.y, at.z, 14, 0.3, 0.6);
            AotParticles.burst(w, AotParticles.STREAK, 0xFFFFFF, 0.8f, e.getX(), e.getY() + 1, e.getZ(), 10, 0.3, strength * 0.8);
        }
    }

    /** An impact frame: for whoever was in it, and anyone standing right there. */
    public static void frame(ServerWorld w, Vec3d at, float strength, net.minecraft.entity.Entity... involved) {
        Net.ImpactFx fx = new Net.ImpactFx(at.x, at.y, at.z, strength);
        java.util.Set<ServerPlayerEntity> to = new java.util.HashSet<>(PlayerLookup.around(w, at, 7));
        for (var e : involved) if (e instanceof ServerPlayerEntity sp) to.add(sp);
        for (ServerPlayerEntity o : to) if (ServerPlayNetworking.canSend(o, Net.ImpactFx.ID)) ServerPlayNetworking.send(o, fx);
    }

    public static void tick(MinecraftServer server) {
        if (!flying.isEmpty()) {
            for (var en : new ArrayList<>(flying.entrySet())) {
                Flight f = en.getValue();
                LivingEntity e = f.e();
                if (e.isRemoved() || !e.isAlive() || e.getWorld().getTime() > f.until()) {
                    flying.remove(en.getKey());
                    continue;
                }
                // Into a wall in the direction they were thrown.
                if (!e.horizontalCollision || !(e.getWorld() instanceof ServerWorld w)) continue;
                BlockPos wall = BlockPos.ofFloored(e.getPos().add(f.dir().multiply(e.getWidth() * 0.5 + 0.4)).add(0, 1, 0));
                BlockState ws = w.getBlockState(wall);
                if (ws.isAir() || !ws.isSolidBlock(w, wall)) continue;
                flying.remove(en.getKey());
                slam(w, e, ws, f.dir());
            }
        }
        if (pinned.isEmpty()) return;
        for (var en : new ArrayList<>(pinned.entrySet())) {
            Pin p = en.getValue();
            LivingEntity e = p.e();
            if (e.isRemoved() || !e.isAlive() || e.getWorld().getTime() > p.until()) {
                pinned.remove(en.getKey());
                continue;
            }
            // Held where they hit.
            if (e instanceof ServerPlayerEntity sp) {
                if (sp.squaredDistanceTo(p.at()) > 0.04) sp.requestTeleport(p.at().x, p.at().y, p.at().z);
            } else {
                e.setVelocity(Vec3d.ZERO);
                e.refreshPositionAndAngles(p.at().x, p.at().y, p.at().z, e.getYaw(), e.getPitch());
            }
            e.fallDistance = 0;
        }
    }

    /** Slammed face first into the ground: flat on it, limp, for a moment. */
    public static void faceplant(ServerWorld w, LivingEntity e, int ticks) {
        pin(w, e, ticks, Adrenaline.FX_FACEPLANT);
    }

    /** Driven into the ground from above: held there, limp, for a moment. */
    public static void pinDown(ServerWorld w, LivingEntity e, int ticks) {
        pin(w, e, ticks, Adrenaline.FX_PINNED);
    }

    private static void pin(ServerWorld w, LivingEntity e, int ticks, int kind) {
        flying.remove(e.getUuid());
        e.setVelocity(Vec3d.ZERO);
        e.velocityModified = true;
        e.fallDistance = 0;
        pinned.put(e.getUuid(), new Pin(e, e.getPos(), w.getTime() + ticks));
        e.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, ticks, 6, false, false, false));
        if (Troops.is(e)) Troops.stagger(e, ticks);
        Net.RushFx fx = new Net.RushFx(e.getId(), kind, e.getYaw(), ticks);
        for (ServerPlayerEntity o : PlayerLookup.around(w, e.getPos(), 64)) if (ServerPlayNetworking.canSend(o, Net.RushFx.ID)) ServerPlayNetworking.send(o, fx);
    }

    private static void slam(ServerWorld w, LivingEntity e, BlockState wall, Vec3d dir) {
        e.setVelocity(Vec3d.ZERO);
        e.velocityModified = true;
        pinned.put(e.getUuid(), new Pin(e, e.getPos(), w.getTime() + PIN_TICKS));
        e.damage(w.getDamageSources().flyIntoWall(), 3);
        e.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, PIN_TICKS, 6, false, false, false));
        e.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, PIN_TICKS, 2, false, false, false));
        if (Troops.is(e)) Troops.stagger(e, PIN_TICKS);
        Vec3d at = e.getPos().add(dir.multiply(e.getWidth() * 0.5)).add(0, e.getHeight() * 0.55, 0);
        w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, wall), at.x, at.y, at.z, 40, 0.3, 0.6, 0.3, 0.25);
        AotParticles.one(w, AotParticles.fx(AotParticles.BURST, 0xFFFFFF, 5f), at.x, at.y, at.z, 0, 0, 0);
        AotParticles.one(w, AotParticles.fx(AotParticles.FLASH, 0xFFF0D0, 5f), at.x, at.y, at.z, 0, 0, 0);
        AotParticles.burst(w, AotParticles.SHARD, 0x9A9080, 0.55f, at.x, at.y, at.z, 16, 0.3, 0.45);
        frame(w, at, 1f, e);
        w.playSound(null, e.getBlockPos(), SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 0.5f, 1.4f);
        w.playSound(null, e.getBlockPos(), SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.PLAYERS, 0.7f, 0.6f);
        float yaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        Net.RushFx fx = new Net.RushFx(e.getId(), Adrenaline.FX_PINNED, yaw, PIN_TICKS);
        for (ServerPlayerEntity o : PlayerLookup.around(w, e.getPos(), 64)) {
            if (ServerPlayNetworking.canSend(o, Net.RushFx.ID)) ServerPlayNetworking.send(o, fx);
            if (ServerPlayNetworking.canSend(o, Net.Quake.ID) && o.squaredDistanceTo(e) < 20 * 20) ServerPlayNetworking.send(o, new Net.Quake(o == e ? 0.6f : 0.25f, 8));
        }
    }
}
