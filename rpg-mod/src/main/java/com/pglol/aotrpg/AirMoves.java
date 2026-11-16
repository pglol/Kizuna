package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Moves in the air, and how each player runs.
 *
 * Slam slashes: hold the attack button in the air with blades in hand and you drive down the way
 * you're looking, blades over your head, and come down cutting. Three to choose from: the
 * Crescent (a wide ring), the Spiral (tighter, and leaves them reeling) and the Piercing Dive
 * (one target, hits hardest). Nobody's sent flying: it's a blow from above, not a launcher.
 *
 * Run styles: each player's choice, shown to everyone.
 */
public final class AirMoves {
    private AirMoves() {}

    public static final int CRESCENT = 3, SPIRAL = 4, PIERCE = 5;
    private static final long COOLDOWN_TICKS = 60;

    private record Dive(int kind, long at) { }

    private static final Map<UUID, Dive> diving = new HashMap<>();
    private static final Map<UUID, Long> lastSlam = new HashMap<>();
    private static final Map<UUID, String> runStyles = new HashMap<>();

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(Net.RunStyleSet.ID, (payload, ctx) -> setStyle(ctx.player(), payload.style()));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            // Everyone's style for the newcomer.
            for (var e : runStyles.entrySet()) {
                if (ServerPlayNetworking.canSend(handler.player, Net.RunStyle.ID)) ServerPlayNetworking.send(handler.player, new Net.RunStyle(e.getKey(), e.getValue()));
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            diving.remove(handler.player.getUuid());
            lastSlam.remove(handler.player.getUuid());
        });
    }

    private static void setStyle(ServerPlayerEntity p, String style) {
        String s = style == null ? "" : style.length() > 24 ? style.substring(0, 24) : style;
        runStyles.put(p.getUuid(), s);
        Net.RunStyle msg = new Net.RunStyle(p.getUuid(), s);
        for (ServerPlayerEntity o : p.getServer().getPlayerManager().getPlayerList()) {
            if (ServerPlayNetworking.canSend(o, Net.RunStyle.ID)) ServerPlayNetworking.send(o, msg);
        }
    }

    /** How high above the ground (up to 6 blocks; 6 means higher still). */
    private static double height(ServerPlayerEntity p) {
        Vec3d from = p.getPos();
        var hit = p.getWorld().raycast(new RaycastContext(from, from.add(0, -6, 0), RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.ANY, p));
        return hit.getType() == HitResult.Type.MISS ? 6 : from.y - hit.getPos().y;
    }

    /** The client started a slam (its own movement already turned down). */
    public static void slam(ServerPlayerEntity p, int kind, float yaw) {
        if (kind < CRESCENT || kind > PIERCE) return;
        long now = p.getServerWorld().getTime();
        if (now - lastSlam.getOrDefault(p.getUuid(), -1000L) < COOLDOWN_TICKS - 4 || diving.containsKey(p.getUuid())) return;
        if (p.hasVehicle() || AotRpg.DOWNED.isDowned(p) || !Guard.melee(p.getMainHandStack()) || height(p) < 1.6) return;
        if (!AotRpg.STAMINA.spend(p, 18)) return;
        lastSlam.put(p.getUuid(), now);
        diving.put(p.getUuid(), new Dive(kind, now));
        ServerWorld w = p.getServerWorld();
        w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_BREEZE_WIND_BURST.value(), SoundCategory.PLAYERS, 0.8f, 0.7f);
        w.playSound(null, p.getBlockPos(), SoundEvents.ITEM_TRIDENT_RIPTIDE_1.value(), SoundCategory.PLAYERS, 0.7f, 1.4f);
        Net.OdmMove move = new Net.OdmMove(p.getId(), kind, yaw);
        for (ServerPlayerEntity o : PlayerLookup.tracking(p)) {
            if (o != p && ServerPlayNetworking.canSend(o, Net.OdmMove.ID)) ServerPlayNetworking.send(o, move);
        }
    }

    public static void tick(MinecraftServer server) {
        if (diving.isEmpty()) return;
        for (var e : new ArrayList<>(diving.entrySet())) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
            Dive d = e.getValue();
            if (p == null || !p.isAlive()) {
                diving.remove(e.getKey());
                continue;
            }
            long age = p.getServerWorld().getTime() - d.at();
            ServerWorld w = p.getServerWorld();
            // The trail down.
            w.spawnParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 1, p.getZ(), 2, 0.15, 0.3, 0.15, 0.01);
            if (d.kind() == SPIRAL) w.spawnParticles(ParticleTypes.SWEEP_ATTACK, p.getX(), p.getY() + 1, p.getZ(), 1, 0.3, 0.2, 0.3, 0);
            boolean landed = p.isOnGround() || p.isTouchingWater() || age > 4 && height(p) < 0.4;
            if (!landed && age < 50) continue;
            diving.remove(e.getKey());
            if (landed) impact(w, p, d.kind());
        }
    }

    private static boolean foe(ServerPlayerEntity p, LivingEntity e) {
        if (e == p || !e.isAlive() || e.isSpectator()) return false;
        if (e instanceof ServerPlayerEntity o) return !AotRpg.PARTIES.same(p.getUuid(), o.getUuid()) && !o.isCreative();
        return e instanceof HostileEntity || AotRpg.isTitan(e) || Troops.is(e);
    }

    private static void impact(ServerWorld w, ServerPlayerEntity p, int kind) {
        p.fallDistance = 0;
        double radius = kind == CRESCENT ? 3.6 : kind == SPIRAL ? 2.6 : 1.8;
        float base = 7 + AotRpg.PROFILES.get(p.getUuid()).level * 0.12f;
        float dmg = base * (kind == CRESCENT ? 1f : kind == SPIRAL ? 0.85f : 1.7f);
        Vec3d at = p.getPos();
        List<LivingEntity> hit = new ArrayList<>();
        for (Entity en : w.getOtherEntities(p, p.getBoundingBox().expand(radius, 2, radius), x -> x instanceof LivingEntity le && foe(p, le))) {
            LivingEntity le = (LivingEntity) en;
            // A titan counts by how close you came to it, not its middle.
            double dx = Math.max(0, Math.abs(le.getX() - at.x) - le.getWidth() / 2), dz = Math.max(0, Math.abs(le.getZ() - at.z) - le.getWidth() / 2);
            if (dx * dx + dz * dz <= radius * radius) hit.add(le);
        }
        if (kind == PIERCE && hit.size() > 1) {
            hit.sort((a, b) -> Double.compare(a.squaredDistanceTo(p), b.squaredDistanceTo(p)));
            hit = hit.subList(0, 1);
        }
        for (LivingEntity le : hit) {
            le.timeUntilRegen = 0;
            le.damage(w.getDamageSources().playerAttack(p), dmg);
            // A blow from above: down and out a little, never launched.
            Vec3d away = le.getPos().subtract(at).multiply(1, 0, 1);
            if (away.lengthSquared() > 1e-4 && le.getHeight() < 4) {
                away = away.normalize().multiply(kind == PIERCE ? 0.1 : 0.32);
                le.addVelocity(away.x, 0.12, away.z);
                le.velocityModified = true;
            }
            if (kind == SPIRAL) le.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 40, 1));
            if (Troops.is(le)) Troops.stagger(le, kind == PIERCE ? 16 : 8);
        }
        // The landing: dust and stone thrown up, a ring of cuts, a thump through the ground.
        BlockPos under = p.getBlockPos().down();
        BlockState ground = w.getBlockState(under);
        if (!ground.isAir()) w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, ground), at.x, at.y + 0.1, at.z, 40, radius * 0.4, 0.1, radius * 0.4, 0.2);
        int ring = kind == PIERCE ? 6 : 14;
        for (int i = 0; i < ring; i++) {
            double a = i * Math.PI * 2 / ring;
            w.spawnParticles(ParticleTypes.SWEEP_ATTACK, at.x + Math.cos(a) * radius * 0.7, at.y + 0.4, at.z + Math.sin(a) * radius * 0.7, 1, 0, 0, 0, 0);
        }
        w.spawnParticles(kind == PIERCE ? ParticleTypes.CRIT : ParticleTypes.EXPLOSION, at.x, at.y + 0.3, at.z, kind == PIERCE ? 20 : 2, 0.3, 0.1, 0.3, 0.2);
        w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 0.45f, 1.5f);
        w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 1f, kind == PIERCE ? 1.4f : 0.8f);
        if (!hit.isEmpty()) w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, SoundCategory.PLAYERS, 1f, 0.9f);
        for (ServerPlayerEntity o : PlayerLookup.around(w, at, 16)) {
            if (ServerPlayNetworking.canSend(o, Net.Quake.ID)) ServerPlayNetworking.send(o, new Net.Quake(o == p ? 0.35f : 0.2f, 6));
        }
    }
}
