package com.pglol.aotrpg;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The fight's pulse.
 *
 * Charging: sprinting straight at whoever you're locked on to, you come on unnaturally fast (the
 * client drives the motion), trailing afterimages; they hear their heartbeat and see you coming.
 *
 * Launch starts: break from a standstill into a sprint and your build shows. A Swift fighter may
 * vanish into wind streaks (a dodge: a moment where nothing touches them); a Heavy one cracks the
 * ground and shoulders anyone close aside; a Balanced one kicks off in a burst of dust.
 */
public final class Adrenaline {
    private Adrenaline() {}

    public static final int FX_CHARGE = 0, FX_CHARGE_END = 1, FX_VANISH = 2, FX_HEAVY = 3, FX_KICK = 4, FX_PINNED = 5, FX_HUNTED = 6, FX_STUNNED = 7, FX_FACEPLANT = 8;
    /** Client -> server kinds. */
    public static final int RUSH_START = 0, RUSH_STOP = 1, START_VANISH = 2, START_PLAIN = 3;

    private record Charge(int target, long until) { }

    private static final Map<UUID, Charge> charging = new HashMap<>();
    private static final Map<UUID, Long> dodgeUntil = new HashMap<>(), startCd = new HashMap<>();

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(Net.Rush.ID, (payload, ctx) -> on(ctx.player(), payload));
        // Aiming down the sights: everyone around sees the stance.
        ServerPlayNetworking.registerGlobalReceiver(Net.AimSet.ID, (payload, ctx) -> {
            ServerPlayerEntity p = ctx.player();
            Net.AimSeen seen = new Net.AimSeen(p.getId(), payload.on());
            for (ServerPlayerEntity o : PlayerLookup.tracking(p)) if (o != p && ServerPlayNetworking.canSend(o, Net.AimSeen.ID)) ServerPlayNetworking.send(o, seen);
        });
        // Vanished: for a moment nothing lands.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((e, src, amount) -> {
            if (!(e instanceof ServerPlayerEntity p)) return true;
            Long until = dodgeUntil.get(p.getUuid());
            if (until == null || p.getServerWorld().getTime() > until || src.getAttacker() == null) return true;
            AotParticles.burst(p.getServerWorld(), AotParticles.STREAK, 0xEAF4FF, 0.7f, p.getX(), p.getY() + 1, p.getZ(), 10, 0.4, 0.5);
            if (src.getAttacker() instanceof ServerPlayerEntity a) a.sendMessage(Text.literal("Evaded").formatted(Formatting.GRAY), true);
            return false;
        });
    }

    private static void on(ServerPlayerEntity p, Net.Rush r) {
        ServerWorld w = p.getServerWorld();
        long now = w.getTime();
        switch (r.kind()) {
            case RUSH_START -> {
                Entity t = w.getEntityById(r.target());
                if (!(t instanceof LivingEntity le) || !le.isAlive() || t.squaredDistanceTo(p) > 48 * 48 || AotRpg.DOWNED.isDowned(p)) return;
                charging.put(p.getUuid(), new Charge(r.target(), now + 100));
                broadcast(p, new Net.RushFx(p.getId(), FX_CHARGE, r.yaw(), r.target()));
                // The one being run down feels it.
                if (t instanceof ServerPlayerEntity tp) {
                    tp.playSoundToPlayer(SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 1f, 1.1f);
                    if (ServerPlayNetworking.canSend(tp, Net.RushFx.ID)) ServerPlayNetworking.send(tp, new Net.RushFx(p.getId(), FX_HUNTED, r.yaw(), 40));
                }
            }
            case RUSH_STOP -> {
                if (charging.remove(p.getUuid()) != null) broadcast(p, new Net.RushFx(p.getId(), FX_CHARGE_END, 0, 0));
            }
            case START_VANISH, START_PLAIN -> start(p, w, now, r.kind() == START_VANISH, r.yaw());
            default -> { }
        }
    }

    private static void start(ServerPlayerEntity p, ServerWorld w, long now, boolean vanish, float yaw) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        Builds b = Builds.of(pr);
        long cd = b == Builds.SWIFT ? 70 : 50;
        if (now - startCd.getOrDefault(p.getUuid(), -1000L) < cd - 4 || p.hasVehicle() || AotRpg.DOWNED.isDowned(p)) return;
        startCd.put(p.getUuid(), now);
        if (vanish && b == Builds.SWIFT && AotRpg.STAMINA.spend(p, 12)) {
            // The faster you are, the longer you're gone.
            int ms = Math.min(320, 90 + pr.total(Stat.AGILITY) * 14);
            dodgeUntil.put(p.getUuid(), now + 4 + ms / 50);
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_BREEZE_WIND_BURST.value(), SoundCategory.PLAYERS, 0.8f, 1.7f);
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PHANTOM_FLAP, SoundCategory.PLAYERS, 0.6f, 1.8f);
            broadcast(p, new Net.RushFx(p.getId(), FX_VANISH, yaw, ms));
            return;
        }
        if (b == Builds.HEAVY) {
            // The ground takes it: a crack underfoot and anyone right there is shouldered aside.
            BlockState under = w.getBlockState(p.getBlockPos().down());
            if (!under.isAir()) w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, under), p.getX(), p.getY() + 0.1, p.getZ(), 30, 0.6, 0.05, 0.6, 0.15);
            AotParticles.one(w, AotParticles.fx(AotParticles.RING, 0xE8D8B8, 5f), p.getX(), p.getY() + 0.08, p.getZ(), 0, 0, 0);
            AotParticles.burst(w, AotParticles.SHARD, 0x8A7A60, 0.5f, p.getX(), p.getY() + 0.3, p.getZ(), 14, 0.5, 0.35);
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_IRON_GOLEM_ATTACK, SoundCategory.PLAYERS, 0.9f, 0.6f);
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 0.3f, 1.8f);
            for (Entity e : w.getOtherEntities(p, p.getBoundingBox().expand(2.2, 0.5, 2.2), x -> x instanceof LivingEntity le && le.isAlive() && !AotRpg.isTitan(x))) {
                Vec3d d = e.getPos().subtract(p.getPos()).multiply(1, 0, 1);
                if (d.lengthSquared() < 1e-4) continue;
                d = d.normalize().multiply(0.45);
                e.addVelocity(d.x, 0.15, d.z);
                e.velocityModified = true;
            }
            broadcast(p, new Net.RushFx(p.getId(), FX_HEAVY, yaw, 0));
            for (ServerPlayerEntity o : PlayerLookup.around(w, p.getPos(), 12)) {
                if (ServerPlayNetworking.canSend(o, Net.Quake.ID)) ServerPlayNetworking.send(o, new Net.Quake(o == p ? 0.35f : 0.18f, 6));
            }
            return;
        }
        AotParticles.burst(w, AotParticles.PUFF, 0xF0F0F0, 0.6f, p.getX(), p.getY() + 0.1, p.getZ(), 8, 0.3, 0.05);
        w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_BREEZE_JUMP, SoundCategory.PLAYERS, 0.5f, 1.3f);
        broadcast(p, new Net.RushFx(p.getId(), FX_KICK, yaw, 0));
    }

    private static void broadcast(ServerPlayerEntity p, Net.RushFx fx) {
        if (ServerPlayNetworking.canSend(p, Net.RushFx.ID)) ServerPlayNetworking.send(p, fx);
        for (ServerPlayerEntity o : PlayerLookup.tracking(p)) if (o != p && ServerPlayNetworking.canSend(o, Net.RushFx.ID)) ServerPlayNetworking.send(o, fx);
    }

    public static void tick(MinecraftServer server) {
        if (charging.isEmpty()) return;
        for (var e : new ArrayList<>(charging.entrySet())) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
            if (p == null || p.getServerWorld().getTime() > e.getValue().until()) {
                charging.remove(e.getKey());
                if (p != null) broadcast(p, new Net.RushFx(p.getId(), FX_CHARGE_END, 0, 0));
            }
        }
    }
}
