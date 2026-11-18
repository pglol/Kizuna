package com.pglol.aotrpg;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Sword fighting. Hold the guard key with a blade drawn to block: hits from the front do no harm
 * but cost stamina (and push the attacker back); run out and the guard breaks. When two fighters
 * swing at each other at the same moment their blades often clash: neither is hurt, both are
 * thrown apart. Titans can't be fully blocked, only softened.
 */
public final class Guard {
    public static final int FX_BLOCK = 0, FX_CLASH = 1, FX_BREAK = 2;
    private static final long CLASH_WINDOW_MS = 420;
    private static final float CLASH_CHANCE = 0.35f;
    /** Two who just clashed don't clash again for a while (a fight, not a stream of locks). */
    private static final long CLASH_REST_MS = 6000;
    private final java.util.Map<String, Long> clashedAt = new java.util.HashMap<>();

    private record Swing(int target, long at) { }

    private final Map<UUID, Long> guarding = new HashMap<>();
    private final Map<UUID, Long> brokenUntil = new HashMap<>();
    private final Map<UUID, Swing> swings = new HashMap<>();

    public static boolean melee(ItemStack s) {
        if (s.isEmpty()) return false;
        if (s.getItem() instanceof SwordItem || s.getItem() instanceof AxeItem) return true;
        String path = Registries.ITEM.getId(s.getItem()).getPath();
        return Loadout.isGrip(s) || path.contains("sword") || path.contains("blade") || path.contains("katana");
    }

    /** A blade swing reported by the client (Danny's blades hit through their own packets, not the vanilla attack). */
    public void swungAt(ServerPlayerEntity p, int targetId) {
        swings.put(p.getUuid(), new Swing(targetId, System.currentTimeMillis()));
    }

    public void swung(ServerPlayerEntity p, Entity target) {
        swings.put(p.getUuid(), new Swing(target.getId(), System.currentTimeMillis()));
    }

    /** How soon after raising the guard a hit counts as a perfect parry. */
    private static final long PARRY_MS = 260;
    /** After a perfect parry: until when your next hit lands harder. */
    private final Map<UUID, Long> counterUntil = new java.util.HashMap<>();

    /** The riposte after a perfect parry: true (once) if this hit is it. */
    public boolean takeCounter(ServerPlayerEntity p) {
        Long until = counterUntil.remove(p.getUuid());
        return until != null && System.currentTimeMillis() < until;
    }

    public boolean guarding(ServerPlayerEntity p) {
        return guarding.containsKey(p.getUuid());
    }

    /** The guard key, pressed or released. */
    /** The optional guard key (unbound by default): held or released. */
    public void set(ServerPlayerEntity p, boolean on) {
        if (on) keyHeld.add(p.getUuid());
        else keyHeld.remove(p.getUuid());
    }

    private final java.util.Set<UUID> keyHeld = new java.util.HashSet<>();

    /** Holding right click with a blade (its block animation) is the guard. */
    private static boolean raisingBlade(ServerPlayerEntity p) {
        return p.isUsingItem() && melee(p.getActiveItem());
    }

    public void tick(ServerPlayerEntity p, int ticks) {
        boolean raising = raisingBlade(p);
        boolean want = (raising || keyHeld.contains(p.getUuid())) && melee(p.getMainHandStack()) && !p.isDead();
        long now = System.currentTimeMillis();
        if (want && !guarding(p) && brokenUntil.getOrDefault(p.getUuid(), 0L) <= now && !AotRpg.STAMINA.exhausted(p)) {
            guarding.put(p.getUuid(), now);
        }
        if (!want || AotRpg.STAMINA.exhausted(p)) {
            guarding.remove(p.getUuid());
            return;
        }
        if (!guarding(p)) return;
        // A raised guard shakes off anyone's hooks: they can't reel you in or swing off you.
        if (ticks % 2 == 0) shedHooks(p);
        AotRpg.STAMINA.hold(p);
        if (p.isSprinting()) p.setSprinting(false);
        // Raising the blade already slows you like any held use; the key guard needs its own slow.
        if (!raising && ticks % 10 == 0) p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 14, 0, false, false, false));
    }

    /** An ODM hook (or wire anchor) from someone's gear: not the player's own. */
    static boolean hook(Entity e, ServerPlayerEntity self) {
        var id = net.minecraft.registry.Registries.ENTITY_TYPE.getId(e.getType());
        if (id.getNamespace().equals("minecraft")) return false;
        String path = id.getPath();
        if (!(path.contains("hook") || path.contains("grapple") || path.contains("anchor"))) return false;
        Entity owner = e instanceof net.minecraft.entity.projectile.ProjectileEntity pe ? pe.getOwner() : null;
        return owner != self;
    }

    private static void shedHooks(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        boolean any = false;
        for (Entity e : w.getOtherEntities(p, p.getBoundingBox().expand(1.2), e -> hook(e, p))) {
            e.discard();
            any = true;
        }
        if (any) {
            Vec3d at = p.getEyePos().add(p.getRotationVec(1f).multiply(0.5));
            AotParticles.burst(w, AotParticles.SPARK, 0xBFD8FF, 0.5f, at.x, at.y, at.z, 10, 0.3, 0.3);
            w.playSound(null, p.getX(), p.getY() + 1, p.getZ(), SoundEvents.ITEM_SHIELD_BLOCK, SoundCategory.PLAYERS, 0.9f, 1.6f);
            w.playSound(null, p.getX(), p.getY() + 1, p.getZ(), SoundEvents.BLOCK_CHAIN_BREAK, SoundCategory.PLAYERS, 0.9f, 1.2f);
        }
    }

    public void forget(UUID id) {
        guarding.remove(id);
        keyHeld.remove(id);
        brokenUntil.remove(id);
        swings.remove(id);
    }

    /** Is the attacker in front of the defender (within about 75 degrees of where they look)? */
    private static boolean inFront(LivingEntity defender, Entity attacker) {
        Vec3d look = defender.getRotationVec(1f).multiply(1, 0, 1).normalize();
        Vec3d to = attacker.getPos().subtract(defender.getPos()).multiply(1, 0, 1).normalize();
        return look.dotProduct(to) > 0.26;
    }

    private static void push(Entity e, Entity from, double strength) {
        Vec3d d = e.getPos().subtract(from.getPos()).multiply(1, 0, 1);
        if (d.lengthSquared() < 1e-4) d = new Vec3d(0, 0, 1);
        d = d.normalize().multiply(strength);
        e.addVelocity(d.x, 0.18, d.z);
        e.velocityModified = true;
    }

    private static void fx(ServerWorld w, int kind, Vec3d at, ServerPlayerEntity by) {
        String slot = kind == FX_CLASH ? "clash" : "block";
        String style = by == null ? "" : AotRpg.COSMETICS.selected(by, slot);
        Net.GuardFx msg = new Net.GuardFx(kind, at.x, at.y, at.z, style);
        for (ServerPlayerEntity o : PlayerLookup.around(w, at, 64)) {
            if (ServerPlayNetworking.canSend(o, Net.GuardFx.ID)) ServerPlayNetworking.send(o, msg);
        }
        switch (kind) {
            case FX_CLASH -> {
                w.playSound(null, at.x, at.y, at.z, SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.PLAYERS, 0.6f, 1.7f);
                w.playSound(null, at.x, at.y, at.z, SoundEvents.ITEM_TRIDENT_HIT, SoundCategory.PLAYERS, 1f, 1.4f);
            }
            case FX_BREAK -> w.playSound(null, at.x, at.y, at.z, SoundEvents.ITEM_SHIELD_BREAK, SoundCategory.PLAYERS, 1f, 0.8f);
            default -> w.playSound(null, at.x, at.y, at.z, SoundEvents.ITEM_SHIELD_BLOCK, SoundCategory.PLAYERS, 1f, 1.2f);
        }
    }

    public void register() {
        // Remember who swung at whom, for clashes.
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!world.isClient && player instanceof ServerPlayerEntity sp && melee(sp.getMainHandStack())) {
                swings.put(sp.getUuid(), new Swing(entity.getId(), System.currentTimeMillis()));
            }
            return ActionResult.PASS;
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            Entity src = source.getAttacker();
            if (src == null || src == entity || amount <= 0) return true;
            long now = System.currentTimeMillis();
            ServerWorld w = (ServerWorld) entity.getWorld();
            // Clash: both fighters swung at each other just now.
            // Only blade on blade, face to face: never a hook or wire hit, never from out of reach.
            Entity direct = source.getSource();
            boolean bladeHit = (direct == null || direct == src) && entity.squaredDistanceTo(src) < 4.5 * 4.5;
            String pair = src.getUuidAsString().compareTo(entity.getUuidAsString()) < 0
                ? src.getUuidAsString() + entity.getUuidAsString() : entity.getUuidAsString() + src.getUuidAsString();
            boolean rested = now - clashedAt.getOrDefault(pair, 0L) > CLASH_REST_MS;
            if (bladeHit && rested && entity instanceof ServerPlayerEntity def && src instanceof ServerPlayerEntity att && Combat.melee(source)
                && melee(att.getMainHandStack()) && melee(def.getMainHandStack()) && !guarding(def)) {
                Swing theirs = swings.get(def.getUuid());
                // They swung at this attacker, or swung at all while facing them close by (air fights rarely line up a target).
                boolean atThem = theirs != null && (theirs.target() == att.getId()
                    || (theirs.target() < 0 || w.getEntityById(theirs.target()) == null) && inFront(def, att) && inFront(att, def) && def.squaredDistanceTo(att) < 4 * 4);
                if (theirs != null && atThem && now - theirs.at() < CLASH_WINDOW_MS
                    && att.getRandom().nextFloat() < CLASH_CHANCE) {
                    swings.remove(def.getUuid());
                    swings.remove(att.getUuid());
                    clashedAt.put(pair, now);
                    if (clashedAt.size() > 256) clashedAt.values().removeIf(v -> now - v > CLASH_REST_MS);
                    // The second clash running between the same two: the blades bind.
                    if (Clashes.clash(att, def)) return false;
                    // Usually an even clash, both thrown apart. Now and then one side overpowers the
                    // other (strength and stamina behind the blow) and sends them flying.
                    Profile ap = AotRpg.PROFILES.get(att.getUuid()), dp0 = AotRpg.PROFILES.get(def.getUuid());
                    float force = ap.total(Stat.STRENGTH) * 2 + ap.level * 0.2f + AotRpg.STAMINA.fraction(att.getUuid()) * 5f + att.getRandom().nextFloat() * 6;
                    float resist = dp0.total(Stat.STRENGTH) * 2 + dp0.level * 0.2f + AotRpg.STAMINA.fraction(def.getUuid()) * 5f + att.getRandom().nextFloat() * 6;
                    if (Math.abs(force - resist) > 4) {
                        ServerPlayerEntity win = force > resist ? att : def, lose = win == att ? def : att;
                        Impact.launch(lose, win, 1.6);
                        push(win, lose, 0.2);
                        lose.sendMessage(Text.literal("OVERPOWERED").formatted(Formatting.RED, Formatting.BOLD), true);
                        win.sendMessage(Text.literal("OVERPOWER").formatted(Formatting.GOLD, Formatting.BOLD), true);
                    } else {
                        Impact.launch(att, def, 0.9);
                        Impact.launch(def, att, 0.9);
                    }
                    AotRpg.STAMINA.spend(att, 5);
                    AotRpg.STAMINA.spend(def, 5);
                    Vec3d mid = att.getEyePos().add(def.getEyePos()).multiply(0.5).subtract(0, 0.3, 0);
                    fx(w, FX_CLASH, mid, att);
                    att.sendMessage(Text.literal("CLASH").formatted(Formatting.GOLD, Formatting.BOLD), true);
                    def.sendMessage(Text.literal("CLASH").formatted(Formatting.GOLD, Formatting.BOLD), true);
                    for (ServerPlayerEntity c : new ServerPlayerEntity[] {att, def}) {
                        if (AotRpg.PROFILES.get(c.getUuid()).has(Skill.BLADE_DANCER)) AotRpg.STAMINA.spend(c, -20);
                    }
                    return false;
                }
            }
            // Block: a guarding fighter facing the hit.
            if (!(entity instanceof ServerPlayerEntity def) || !guarding(def)) return true;
            // A hook's hit is turned from any side, and the hook falls away.
            if (source.getSource() != null && hook(source.getSource(), def)) {
                source.getSource().discard();
                shedHooks(def);
                return false;
            }
            if (!inFront(def, src)) return true;
            boolean titan = AotRpg.isTitan(src);
            // A perfect parry: the guard came up just as the hit arrived. Nothing gets through, it
            // costs nothing, the attacker is thrown off balance, and your next cut lands harder.
            Long raised = guarding.get(def.getUuid());
            if (raised != null && now - raised <= PARRY_MS) {
                Vec3d at0 = def.getEyePos().add(def.getRotationVec(1f).multiply(0.7)).subtract(0, 0.3, 0);
                fx(w, FX_CLASH, at0, def);
                AotParticles.one(w, AotParticles.fx(AotParticles.FLASH, 0xFFF4C8, 5f), at0.x, at0.y, at0.z, 0, 0, 0);
                AotParticles.one(w, AotParticles.fx(AotParticles.BURST, 0xFFD24A, 3.5f), at0.x, at0.y, at0.z, 0, 0, 0);
                AotParticles.burst(w, AotParticles.SPARK, 0xFFE08A, 0.55f, at0.x, at0.y, at0.z, 18, 0.2, 0.5);
                AotParticles.burst(w, AotParticles.SHARD, 0xE8E8F0, 0.4f, at0.x, at0.y, at0.z, 8, 0.15, 0.4);
                Impact.frame(w, at0, 0.8f, def, src);
                w.playSound(null, def.getX(), def.getY() + 1, def.getZ(), net.minecraft.sound.SoundEvents.BLOCK_ANVIL_LAND,
                    net.minecraft.sound.SoundCategory.PLAYERS, 0.9f, 2f);
                w.playSound(null, def.getX(), def.getY() + 1, def.getZ(), net.minecraft.sound.SoundEvents.ITEM_TRIDENT_HIT,
                    net.minecraft.sound.SoundCategory.PLAYERS, 1f, 1.4f);
                AotRpg.STAMINA.spend(def, -15);
                counterUntil.put(def.getUuid(), now + 1500);
                Mentors.count(def, "parries", 1);
                Adrenaline.gain(def, 15);
                def.sendMessage(Text.literal("PERFECT PARRY").formatted(Formatting.GOLD, Formatting.BOLD), true);
                if (titan) {
                    // Nothing stops a titan: you turn the blow and are thrown clear of it.
                    push(def, src, 1.1);
                } else if (src instanceof ServerPlayerEntity att) {
                    Impact.launch(att, def, 1.4);
                    // Turned clean: dazed for a beat, wide open to the riposte.
                    Stuns.stun(att, 18);
                    att.sendMessage(Text.literal("Parried!").formatted(Formatting.RED, Formatting.BOLD), true);
                } else if (Troops.is(src)) {
                    Stuns.stun((LivingEntity) src, 26);
                } else {
                    push(src, def, 0.9);
                }
                return false;
            }
            Profile dp = AotRpg.PROFILES.get(def.getUuid());
            float cost = (amount * (titan ? 3f : 5f) + 4) * (dp.has(Skill.BULWARK) ? 0.65f : 1f) * (dp.has(Skill.TNK_STALWART) ? 0.75f : 1f);
            Vec3d at = def.getEyePos().add(def.getRotationVec(1f).multiply(0.7)).subtract(0, 0.4, 0);
            if (AotRpg.STAMINA.spend(def, cost)) {
                fx(w, FX_BLOCK, at, def);
                AotRpg.ABILITIES.blocked(def);
                AotRpg.CLASSES.blocked(def);
                if (titan) {
                    // Titans are too strong to stop: the guard only softens the blow.
                    push(def, src, 0.8);
                    def.damage(w.getDamageSources().generic(), amount * (dp.has(Skill.BULWARK) ? 0.175f : 0.35f));
                    return false;
                }
                push(src, def, Combat.melee(source) ? 0.75 : 0);
                push(def, src, 0.25);
                return false;
            }
            // Out of stamina: the guard breaks and the hit lands at half strength.
            AotRpg.STAMINA.drain(def);
            guarding.remove(def.getUuid());
            def.stopUsingItem();
            brokenUntil.put(def.getUuid(), now + 2500);
            def.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 50, 2, false, false, false));
            // A broken guard leaves you reeling.
            Stuns.stun(def, 24);
            def.sendMessage(Text.literal("Guard broken!").formatted(Formatting.RED, Formatting.BOLD), true);
            fx(w, FX_BREAK, at, def);
            def.damage(w.getDamageSources().generic(), amount * 0.5f);
            return false;
        });
    }
}
