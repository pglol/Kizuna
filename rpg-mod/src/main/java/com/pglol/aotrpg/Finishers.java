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

    /** The scene, in ticks (the client plays the same timeline): dash, wind-up, cut, kill, over. */
    private static final int DASH = 6, CUT = 19, KILL = 38, END = 56;

    private static final class Scene {
        final ServerPlayerEntity by;
        final LivingEntity on;
        final Vec3d from, to;
        final float yaw;
        int t;

        Scene(ServerPlayerEntity by, LivingEntity on, Vec3d from, Vec3d to, float yaw) {
            this.by = by;
            this.on = on;
            this.from = from;
            this.to = to;
            this.yaw = yaw;
        }
    }

    private static final List<Scene> pending = new ArrayList<>();
    /** Our own killing blow is let through. */
    private static boolean striking;

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(s -> tick());
        // Nobody else gets a hand in: the two in a finisher can't be hurt until it's done.
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DAMAGE.register((e, src, amt) -> striking || !inScene(e));
        // And the one finishing doesn't swing at anything else meanwhile.
        net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.register((pl, world, hand, target, hit) ->
            !world.isClient && inScene(pl) ? net.minecraft.util.ActionResult.FAIL : net.minecraft.util.ActionResult.PASS);
    }

    /** Our own killing blow is landing right now. */
    public static boolean striking() {
        return striking;
    }

    /** In a finisher (either side) right now. */
    public static boolean inScene(Entity e) {
        for (Scene q : pending) if (q.by == e || q.on == e && q.t < KILL) return true;
        return false;
    }

    /** Down and nearly done. */
    public static boolean finishable(LivingEntity e) {
        if (e == null || !e.isAlive() || AotRpg.isTitan(e) || inScene(e)) return false;
        if (!(Stuns.stunned(e) || Impact.pinned(e))) return false;
        float limit = e instanceof ServerPlayerEntity ? 0.25f : 0.2f;
        return e.getHealth() <= e.getMaxHealth() * limit;
    }

    public static void finish(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        if (inScene(p)) return;
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
        // Squared up: the one finishing ends a long step off, the two facing each other.
        Vec3d dir = best.getPos().subtract(p.getPos()).multiply(1, 0, 1);
        dir = dir.lengthSquared() < 1e-4 ? look : dir.normalize();
        // The scene ends with them through and past, kneeling behind the foe (the client draws the way there).
        Vec3d spot = best.getPos().add(dir.multiply(1.9));
        float yaw = (float) (Math.toDegrees(Math.atan2(dir.z, dir.x)) - 90);
        face(best, yaw + 180);
        Stuns.stun(best, END + 6);
        // Everyone around sees it play out (sent before the move, so they know where it started).
        Net.RushFx fx = new Net.RushFx(p.getId(), Adrenaline.FX_FINISH, yaw, best.getId());
        for (ServerPlayerEntity o : PlayerLookup.around(w, best.getPos(), 64)) if (ServerPlayNetworking.canSend(o, Net.RushFx.ID)) ServerPlayNetworking.send(o, fx);
        Vec3d end = new Vec3d(spot.x, best.getY(), spot.z);
        pending.add(new Scene(p, best, end, end, yaw));
        p.teleport(w, end.x, end.y, end.z, yaw, 10);
        w.playSound(null, best.getBlockPos(), SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 1.5f, 0.6f);
        w.playSound(null, best.getBlockPos(), SoundEvents.ITEM_TRIDENT_RETURN, SoundCategory.PLAYERS, 1.2f, 0.5f);
    }

    private static void face(LivingEntity e, float yaw) {
        if (e instanceof ServerPlayerEntity sp) {
            sp.teleport(sp.getServerWorld(), sp.getX(), sp.getY(), sp.getZ(), yaw, 0);
        } else {
            e.setYaw(yaw);
            e.setBodyYaw(yaw);
            e.setHeadYaw(yaw);
        }
    }

    private static void hold(Entity e) {
        e.setVelocity(Vec3d.ZERO);
        e.velocityModified = true;
        e.fallDistance = 0;
    }

    private static void abort(Scene q) {
        pending.remove(q);
        if (!(q.by.getWorld() instanceof ServerWorld w)) return;
        Net.RushFx fx = new Net.RushFx(q.by.getId(), Adrenaline.FX_FINISH, 0, -1);
        for (ServerPlayerEntity o : PlayerLookup.around(w, q.by.getPos(), 64)) if (ServerPlayNetworking.canSend(o, Net.RushFx.ID)) ServerPlayNetworking.send(o, fx);
    }

    private static void tick() {
        if (pending.isEmpty()) return;
        for (Scene q : new ArrayList<>(pending)) {
            ServerPlayerEntity p = q.by;
            LivingEntity v = q.on;
            boolean victimGone = !v.isAlive() || v.isRemoved() || v.getWorld() != p.getWorld();
            if (p.isRemoved() || !p.isAlive() || q.t < KILL && victimGone) {
                abort(q);
                continue;
            }
            ServerWorld w = p.getServerWorld();
            int t = ++q.t;
            // Held where the scene ends; the way there is drawn by everyone's client.
            Vec3d at = q.to;
            if (p.getPos().squaredDistanceTo(at) > 0.09) p.teleport(w, at.x, at.y, at.z, q.yaw, 10);
            hold(p);
            if (t < KILL) {
                hold(v);
                if (!(v instanceof ServerPlayerEntity)) face(v, q.yaw + 180);
                if (t == DASH + 2) w.playSound(null, v.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 1.2f, 0.6f);
            }
            if (t == CUT) {
                // The blade lands.
                Vec3d c = v.getPos().add(0, v.getHeight() * 0.6, 0);
                AotParticles.one(w, AotParticles.fx(AotParticles.FLASH, 0xFFFFFF, 9f), c.x, c.y, c.z, 0, 0, 0);
                AotParticles.one(w, AotParticles.fx(AotParticles.ARC, 0xFFFFFF, 5f), c.x, c.y, c.z, 0, 0, 0);
                AotParticles.burst(w, AotParticles.STREAK, 0xFFFFFF, 1f, c.x, c.y, c.z, 24, 0.2, 1.2);
                Impact.frame(w, c, 1.4f, p, v);
                for (ServerPlayerEntity o : PlayerLookup.around(w, c, 64)) {
                    if (ServerPlayNetworking.canSend(o, Net.ImpactFx.ID)) ServerPlayNetworking.send(o, new Net.ImpactFx(c.x, c.y, c.z, 1f));
                }
                w.playSound(null, v.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, SoundCategory.PLAYERS, 1.5f, 0.5f);
                w.playSound(null, v.getBlockPos(), HitSounds.HEAVY, SoundCategory.PLAYERS, 1.4f, 0.8f);
                for (ServerPlayerEntity o : PlayerLookup.around(w, c, 24)) if (ServerPlayNetworking.canSend(o, Net.Quake.ID)) ServerPlayNetworking.send(o, new Net.Quake(0.5f, 10));
            }
            if (t == KILL && !victimGone) {
                // A beat too long on their feet, then it lets go.
                Vec3d c = v.getPos().add(0, v.getHeight() * 0.6, 0);
                v.timeUntilRegen = 0;
                striking = true;
                try {
                    var src = w.getDamageSources().playerAttack(p);
                    v.damage(src, v.getHealth() + 60);
                    // Anything that still kept them standing (a ward, a last stand, a mob's own
                    // rules): the finisher finishes. In an arena or a duel the hit above already
                    // ended their round, so they're left to that.
                    if (v.isAlive() && !(v instanceof ServerPlayerEntity sp && (Arenas.decides(sp, p) || AotRpg.DUELS.dueling(sp.getUuid(), p.getUuid())))) {
                        v.setHealth(0);
                        v.onDeath(src);
                    }
                } finally {
                    striking = false;
                }
                AotParticles.one(w, AotParticles.fx(AotParticles.BURST, 0xFF3A2A, 6f), c.x, c.y, c.z, 0, 0, 0);
                AotParticles.burst(w, AotParticles.SPARK, 0xFF5A3A, 0.6f, c.x, c.y, c.z, 30, 0.3, 0.9);
                w.playSound(null, v.getBlockPos(), SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.PLAYERS, 0.4f, 1.8f);
                Adrenaline.gain(p, 30);
            }
            if (t >= END) pending.remove(q);
        }
    }
}
