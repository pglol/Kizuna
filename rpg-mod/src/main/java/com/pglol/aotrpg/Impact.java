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
        w.spawnParticles(ParticleTypes.EXPLOSION, at.x, at.y, at.z, 1, 0, 0, 0, 0);
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
