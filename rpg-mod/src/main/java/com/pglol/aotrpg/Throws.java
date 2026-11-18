package com.pglol.aotrpg;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Grab and throw, for the strong: crouch and right-click a person up close to seize them (it
 * takes Strength 10), hold them off the ground, then strike (or wait a moment) to hurl them where
 * you look. More Strength throws harder and farther; from 20 a throw is enough to put them through
 * a wall. Nobody stronger than you can be lifted, nor party members, nor anyone in a town.
 */
public final class Throws {
    private Throws() { }

    public static final int NEED = 10, WALL = 20;
    private static final int HOLD = 30, COOLDOWN_MS = 8000;

    private static final class Hold {
        final ServerPlayerEntity by;
        final LivingEntity on;
        int t;

        Hold(ServerPlayerEntity by, LivingEntity on) {
            this.by = by;
            this.on = on;
        }
    }

    private static final Map<UUID, Hold> holds = new HashMap<>();
    private static final Map<UUID, Long> cooldown = new HashMap<>();

    public static void register() {
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClient || hand != Hand.MAIN_HAND || !(player instanceof ServerPlayerEntity p) || !p.isSneaking()) return ActionResult.PASS;
            if (!(entity instanceof LivingEntity le) || !throwable(le)) return ActionResult.PASS;
            return grab(p, le) ? ActionResult.SUCCESS : ActionResult.PASS;
        });
        // Strike whoever you're holding: the throw.
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClient || !(player instanceof ServerPlayerEntity p)) return ActionResult.PASS;
            Hold h = holds.get(p.getUuid());
            if (h == null || h.on != entity) return ActionResult.PASS;
            fling(h);
            return ActionResult.FAIL;
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> tick());
    }

    /** People (players, soldiers, the hostile kind), never titans or townsfolk. */
    private static boolean throwable(LivingEntity e) {
        if (!e.isAlive() || AotRpg.isTitan(e)) return false;
        return e instanceof ServerPlayerEntity || Troops.is(e) || e instanceof net.minecraft.entity.mob.Monster;
    }

    public static boolean holding(Entity e) {
        for (Hold h : holds.values()) if (h.on == e) return true;
        return false;
    }

    private static int strength(ServerPlayerEntity p) {
        return AotRpg.PROFILES.get(p.getUuid()).total(Stat.STRENGTH);
    }

    private static boolean grab(ServerPlayerEntity p, LivingEntity t) {
        if (holds.containsKey(p.getUuid()) || holding(p) || Finishers.inScene(p) || Finishers.inScene(t)) return false;
        if (p.squaredDistanceTo(t) > 3.2 * 3.2) return false;
        if (Troops.safe(p.getWorld(), p.getX(), p.getZ())) return false;
        int str = strength(p);
        if (str < NEED) {
            p.sendMessage(Text.literal("Too weak to lift them (Strength " + str + "/" + NEED + ")").formatted(Formatting.GRAY), true);
            return true;
        }
        Long cd = cooldown.get(p.getUuid());
        if (cd != null && System.currentTimeMillis() < cd) return true;
        if (t instanceof ServerPlayerEntity o) {
            // Only someone you could hit: not your party, and in an arena or duel only when it's on.
            if (Arenas.decides(o, p) ? !Arenas.allowHit(o, p) : AotRpg.DUELS.dueling(o.getUuid(), p.getUuid())
                ? !AotRpg.DUELS.allowHit(o, p) : AotRpg.PARTIES.same(p.getUuid(), o.getUuid())) return false;
            if (strength(o) >= str) {
                // As strong as you or stronger: they shove you off.
                Impact.launch(p, o, 0.6);
                p.getServerWorld().playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK, SoundCategory.PLAYERS, 1f, 0.8f);
                cooldown.put(p.getUuid(), System.currentTimeMillis() + COOLDOWN_MS / 2);
                return true;
            }
        }
        holds.put(p.getUuid(), new Hold(p, t));
        cooldown.put(p.getUuid(), System.currentTimeMillis() + COOLDOWN_MS);
        Stuns.stun(t, HOLD + 10);
        ServerWorld w = p.getServerWorld();
        w.playSound(null, t.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.PLAYERS, 1f, 0.6f);
        w.playSound(null, t.getBlockPos(), SoundEvents.ITEM_ARMOR_EQUIP_LEATHER.value(), SoundCategory.PLAYERS, 1f, 0.7f);
        return true;
    }

    private static void tick() {
        if (holds.isEmpty()) return;
        for (Hold h : new java.util.ArrayList<>(holds.values())) {
            ServerPlayerEntity p = h.by;
            LivingEntity t = h.on;
            if (p.isRemoved() || !p.isAlive() || !t.isAlive() || t.isRemoved() || t.getWorld() != p.getWorld() || p.squaredDistanceTo(t) > 6 * 6) {
                holds.remove(p.getUuid());
                continue;
            }
            // Held up off the ground in front of you.
            Vec3d look = p.getRotationVec(1f).multiply(1, 0, 1);
            look = look.lengthSquared() < 1e-4 ? Vec3d.ZERO : look.normalize();
            Vec3d at = p.getPos().add(look.multiply(1.1)).add(0, 0.55, 0);
            if (t instanceof ServerPlayerEntity tp) tp.teleport(p.getServerWorld(), at.x, at.y, at.z, p.getYaw() + 180, 0);
            else {
                t.refreshPositionAndAngles(at.x, at.y, at.z, p.getYaw() + 180, 0);
                t.setHeadYaw(p.getYaw() + 180);
            }
            t.setVelocity(Vec3d.ZERO);
            t.velocityModified = true;
            t.fallDistance = 0;
            if (++h.t >= HOLD) fling(h);
        }
    }

    /** The throw: along your look, harder the stronger you are. */
    private static void fling(Hold h) {
        holds.remove(h.by.getUuid());
        ServerPlayerEntity p = h.by;
        LivingEntity t = h.on;
        ServerWorld w = p.getServerWorld();
        int str = strength(p);
        double power = 1.0 + 0.045 * str + (str >= WALL ? 0.3 : 0);
        // Pushed a step out in front first, so the throw goes the way you face.
        Vec3d look = p.getRotationVec(1f).multiply(1, 0, 1).normalize();
        t.setPosition(t.getX() + look.x * 0.3, t.getY(), t.getZ() + look.z * 0.3);
        t.timeUntilRegen = 0;
        t.damage(w.getDamageSources().playerAttack(p), 2 + str * 0.2f);
        Impact.launch(t, p, power);
        t.addVelocity(0, Math.max(0, -p.getPitch()) * 0.01, 0);
        Stuns.stun(t, 20);
        w.playSound(null, t.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 1.2f, 0.6f);
        w.playSound(null, t.getBlockPos(), SoundEvents.ENTITY_IRON_GOLEM_ATTACK, SoundCategory.PLAYERS, 1f, 0.8f);
        Adrenaline.gain(p, 10);
    }
}
