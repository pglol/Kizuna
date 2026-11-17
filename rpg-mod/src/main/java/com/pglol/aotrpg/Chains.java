package com.pglol.aotrpg;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Chains: blow after blow without a break builds a chain, and every link hits a little harder.
 * How long a chain can run depends on how good you are (Agility; Balanced builds flow longest).
 * In the air the chain holds your foe up with you, so a good fighter can keep a juggle going.
 * The chain shows on your screen, and past three links everyone watching sees your afterimages.
 */
public final class Chains {
    private Chains() { }

    private static final class Chain {
        int count;
        long at;
        UUID on;
    }

    private static final Map<UUID, Chain> chains = new HashMap<>();
    private static final long WINDOW_MS = 1300;

    public static void register() {
        ServerLivingEntityEvents.AFTER_DAMAGE.register((victim, source, base, taken, blocked) -> {
            if (blocked || taken <= 0 || !(source.getAttacker() instanceof ServerPlayerEntity att) || att == victim || !Combat.melee(source)) return;
            hit(att, victim);
        });
    }

    /** How far a chain can run for this fighter. */
    private static int cap(ServerPlayerEntity p) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        int cap = 3 + pr.total(Stat.AGILITY) / 3;
        if (Builds.of(pr) == Builds.BALANCED) cap += 2;
        return Math.min(10, cap);
    }

    /** Damage multiplier for the next blow (applied before it lands). */
    public static double bonus(ServerPlayerEntity att) {
        Chain c = chains.get(att.getUuid());
        if (c == null || System.currentTimeMillis() - c.at > WINDOW_MS) return 1;
        return 1 + 0.05 * Math.min(c.count, cap(att));
    }

    private static void hit(ServerPlayerEntity att, LivingEntity victim) {
        long now = System.currentTimeMillis();
        Chain c = chains.computeIfAbsent(att.getUuid(), k -> new Chain());
        c.count = now - c.at < WINDOW_MS ? c.count + 1 : 1;
        c.at = now;
        c.on = victim.getUuid();
        int cap = cap(att);
        if (c.count > cap) c.count = cap;
        ServerWorld w = att.getServerWorld();
        // The juggle: both of you in the air, the chain keeps them up there with you.
        if (!victim.isOnGround() && !att.isOnGround() && c.count >= 2) {
            Vec3d v = victim.getVelocity();
            victim.setVelocity(v.x * 0.6, Math.max(v.y, 0.32), v.z * 0.6);
            victim.velocityModified = true;
            Vec3d a = att.getVelocity();
            att.setVelocity(a.x, Math.max(a.y, 0.14), a.z);
            att.velocityModified = true;
            att.fallDistance = 0;
            victim.fallDistance = 0;
        }
        // Flowing (Balanced): every fourth link staggers.
        if (c.count % 4 == 0 && Builds.of(AotRpg.PROFILES.get(att.getUuid())) == Builds.BALANCED) Stuns.stun(victim, 10);
        if (c.count >= 2 && ServerPlayNetworking.canSend(att, Net.RushFx.ID))
            ServerPlayNetworking.send(att, new Net.RushFx(att.getId(), Adrenaline.FX_CHAIN, 0, c.count));
        if (c.count >= 3) {
            Net.RushFx fx = new Net.RushFx(att.getId(), Adrenaline.FX_AFTER, att.getYaw(), c.count >= cap ? 0xFFD24A : 0xE8E8F0);
            for (ServerPlayerEntity o : PlayerLookup.tracking(att)) if (ServerPlayNetworking.canSend(o, Net.RushFx.ID)) ServerPlayNetworking.send(o, fx);
            Vec3d at = victim.getPos().add(0, victim.getHeight() * 0.6, 0);
            AotParticles.one(w, AotParticles.fx(AotParticles.ARC, c.count >= cap ? 0xFFD24A : 0xFFFFFF, 2.4f), at.x, at.y, at.z, 0, 0, 0);
        }
    }

    public static void forget(UUID id) {
        chains.remove(id);
    }
}
