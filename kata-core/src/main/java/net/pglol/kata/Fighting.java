package net.pglol.kata;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Hand to hand: the fists' Combat Deck, guarding and stances. Shared numbers for both sides; the
 * server half (guard state, strikes, blocking) lives here too.
 *
 * Stances are the body's orientation to the opponent, four quadrants: top-right and top-left with
 * the chest towards them, bottom-right and bottom-left with the back turned. The stance you're in
 * picks the move a punch throws, and every move ends in a stance, so strings flow from one to the next.
 * Guarding blocks from every direction, whatever the stance.
 */
public final class Fighting {
    private Fighting() {}

    // Stances.
    public static final int TR = 0, TL = 1, BR = 2, BL = 3;
    // Moves (fists).
    public static final int JAB = 0, CROSS = 1, BACKFIST = 2, BACK_ELBOW = 3;
    public static final String[] NAMES = {"Jab", "Cross", "Spinning backfist", "Spinning back elbow"};
    /** Damage (half-hearts) of each move. */
    public static final float[] DAMAGE = {2.0f, 3.0f, 4.0f, 3.5f};
    /** The stance each move starts from, and the one it leaves you in. */
    public static final int[] START = {TR, TL, BR, BL};
    public static final int[] END = {TL, TR, TR, TL};
    /** Seconds from the press to contact, and to being ready again. */
    public static final float[] CONTACT = {0.10f, 0.14f, 0.22f, 0.19f};
    public static final float[] LENGTH = {0.30f, 0.38f, 0.55f, 0.50f};
    /** Reach from the eyes (blocks). */
    public static final double REACH = 3.0;

    /** The move a punch throws from this stance. */
    public static int moveFor(int stance) {
        return switch (stance) {
            case TL -> CROSS;
            case BR -> BACKFIST;
            case BL -> BACK_ELBOW;
            default -> JAB;
        };
    }

    public static boolean top(int stance) {
        return stance == TR || stance == TL;
    }

    // ---- server ----

    private static final Map<UUID, Boolean> GUARD = new HashMap<>();
    private static final Map<UUID, Integer> LAST = new HashMap<>();

    public static boolean guarding(Entity e) {
        return e instanceof ServerPlayerEntity p && GUARD.getOrDefault(p.getUuid(), false);
    }

    static void register() {
        // Guard: hits from anyone are blocked, from any side. The block can turn you (your stance
        // changes side with the angle of the hit; your game is told so it can show it).
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((victim, source, amount) -> {
            if (!(victim instanceof ServerPlayerEntity p) || !guarding(p)) return true;
            Entity by = source.getAttacker();
            if (by == null || source.isIn(DamageTypeTags.BYPASSES_SHIELD)) return true;
            p.getWorld().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ITEM_SHIELD_BLOCK, SoundCategory.PLAYERS, 0.8f, 1.2f + p.getRandom().nextFloat() * 0.2f);
            // Which side it came from: right (+1) or left (-1) of where they face.
            Vec3d to = by.getPos().subtract(p.getPos());
            double yaw = Math.toRadians(p.getYaw());
            double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
            int side = to.x * rx + to.z * rz >= 0 ? 1 : -1;
            if (ServerPlayNetworking.canSend(p, KataNet.FightOf.ID)) ServerPlayNetworking.send(p, new KataNet.FightOf(p.getId(), KataNet.GUARD_HIT, side));
            // A blocked hit still shoves.
            double len = Math.max(1e-3, Math.hypot(to.x, to.z));
            p.takeKnockback(0.15 + amount * 0.02, to.x / len, to.z / len);
            p.velocityModified = true;
            return false;
        });
    }

    static void guard(ServerPlayerEntity p, boolean on) {
        GUARD.put(p.getUuid(), on);
        if (GUARD.size() > 1024) GUARD.clear();
        relay(p, KataNet.GUARD, on ? 1 : 0);
    }

    static void stance(ServerPlayerEntity p, int stance) {
        relay(p, KataNet.STANCE, MathHelper.clamp(stance, 0, 3));
    }

    /** A punch thrown at target (an entity id, or -1 for none in reach): checked, then landed. */
    static void strike(ServerPlayerEntity p, int move, int target) {
        if (move < 0 || move > 3 || !p.isAlive() || p.isSpectator()) return;
        int now = p.getServer().getTicks();
        Integer last = LAST.get(p.getUuid());
        if (last != null && now - last < 4) return; // faster than any string allows
        LAST.put(p.getUuid(), now);
        if (LAST.size() > 1024) LAST.clear();
        relay(p, KataNet.STRIKE, move);
        if (target < 0 || guarding(p)) return;
        Entity e = p.getWorld().getEntityById(target);
        if (!(e instanceof LivingEntity t) || !t.isAlive() || t == p) return;
        // In reach (a little slack for lag) and in front of them.
        Vec3d eye = p.getEyePos();
        Vec3d near = new Vec3d(MathHelper.clamp(eye.x, t.getBoundingBox().minX, t.getBoundingBox().maxX),
            MathHelper.clamp(eye.y, t.getBoundingBox().minY, t.getBoundingBox().maxY),
            MathHelper.clamp(eye.z, t.getBoundingBox().minZ, t.getBoundingBox().maxZ));
        if (eye.distanceTo(near) > REACH + 0.6) return;
        Vec3d to = t.getPos().subtract(p.getPos());
        Vec3d look = Vec3d.fromPolar(0, p.getYaw());
        double len = Math.hypot(to.x, to.z);
        if (len > 0.3 && (to.x * look.x + to.z * look.z) / len < 0.3) return;
        // Strings land: no invulnerability gap between hits of a combo.
        t.timeUntilRegen = 0;
        if (t.damage(p.getDamageSources().playerAttack(p), DAMAGE[move])) {
            t.takeKnockback(0.25 + DAMAGE[move] * 0.04, -look.x, -look.z);
            p.getWorld().playSound(null, t.getX(), t.getY(), t.getZ(), SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.PLAYERS, 0.9f, 0.9f + p.getRandom().nextFloat() * 0.25f);
        }
    }

    private static void relay(ServerPlayerEntity p, int kind, int value) {
        for (ServerPlayerEntity o : PlayerLookup.tracking(p)) {
            if (o != p && ServerPlayNetworking.canSend(o, KataNet.FightOf.ID)) ServerPlayNetworking.send(o, new KataNet.FightOf(p.getId(), kind, value));
        }
    }
}
