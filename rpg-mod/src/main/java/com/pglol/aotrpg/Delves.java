package com.pglol.aotrpg;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.AreaEffectCloudEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Caves are dungeons. Go down into one and it wakes:
 * <ol>
 *   <li><b>The holders</b>: whoever has made the cave theirs (raiders, with brutes and headsmen in
 *   the deeper, harder ones) are waiting, and angry you came;</li>
 *   <li><b>The cave fights back</b> the whole time: the roof comes down over someone (dust falls
 *   first: move), and pockets of bad air seep up;</li>
 *   <li><b>The Warden</b>: when the holders are down, the cave's master comes. An ogre, in the
 *   halls big enough for one; otherwise the biggest brute you've seen, and his headsmen.</li>
 * </ol>
 * Clear it and everyone down there shares the vault: Marks, gear (Legendary and better in the hard
 * ones), XP. Then the cave is quiet for twenty minutes.
 */
public final class Delves {
    private Delves() {}

    private enum Phase { IDLE, HOLDERS, WARDEN }

    private static final class Delve {
        Phase phase = Phase.IDLE;
        String session;
        long ready, lastNear, nextTrap;
        UUID warden;
        final Set<UUID> in = new HashSet<>();
        Vec3d trapAt;
        int trapKind, trapT;
    }

    private static final Map<String, Delve> delves = new HashMap<>();
    private static int seq;

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (s.getTicks() % 5 != 0) return;
            ServerWorld w = s.getOverworld();
            long now = System.currentTimeMillis();
            for (Net.Area a : AotRpg.PLACES.areas()) {
                if (!a.look().equals("cave")) continue;
                Delve d = delves.computeIfAbsent(a.id(), k -> new Delve());
                tick(w, a, d, now, s.getTicks() % 20 == 0);
            }
        });
    }

    private static List<ServerPlayerEntity> inside(ServerWorld w, Net.Area a, double r) {
        List<ServerPlayerEntity> out = new ArrayList<>();
        for (ServerPlayerEntity p : w.getPlayers()) {
            if (p.isSpectator() || p.isCreative() || !p.isAlive()) continue;
            if (p.squaredDistanceTo(a.x(), a.y(), a.z()) < r * r && p.getY() < a.y() + 30) out.add(p);
        }
        return out;
    }

    private static int level(Net.Area a) {
        return Math.max(1, (a.min() + a.max()) / 2);
    }

    private static void tick(ServerWorld w, Net.Area a, Delve d, long now, boolean second) {
        List<ServerPlayerEntity> here = inside(w, a, 42);
        if (!here.isEmpty()) d.lastNear = now;
        for (ServerPlayerEntity p : here) d.in.add(p.getUuid());
        BlockPos floor = new BlockPos(a.x(), a.y(), a.z());
        switch (d.phase) {
            case IDLE -> {
                if (!second || here.isEmpty() || now < d.ready || !w.isChunkLoaded(a.x() >> 4, a.z() >> 4)) return;
                d.session = "delve:" + (seq++);
                d.in.clear();
                for (ServerPlayerEntity p : here) d.in.add(p.getUuid());
                Random r = w.random;
                int lv = level(a);
                UUID first = here.get(0).getUuid();
                int n = 3 + Math.min(4, here.size());
                Troops.groupBelow(w, floor, n, lv, d.session, 18, r, Troops.Arch.BANDIT, Math.min(5, 2 + lv / 25), first);
                if (lv >= 35) Troops.groupBelow(w, floor, 1, lv + 1, d.session, 14, r, Troops.Arch.BRUTE, 3 + lv / 40, first);
                if (lv >= 55) Troops.groupBelow(w, floor, 1, lv + 2, d.session, 14, r, Troops.Arch.HEADSMAN, 4, first);
                for (ServerPlayerEntity p : here) {
                    Troops.provokeAll(d.session, p.getUuid());
                    Notify.toast(p, Text.literal("Delve: " + a.name()).formatted(Formatting.GOLD),
                        Text.literal("Clear out its holders, then face its Warden"), 0xE0B96A);
                    p.playSoundToPlayer(SoundEvents.AMBIENT_CAVE.value(), SoundCategory.AMBIENT, 1f, 0.7f);
                }
                d.phase = Phase.HOLDERS;
                d.nextTrap = now + 8000;
            }
            case HOLDERS, WARDEN -> {
                // Everyone gone for a minute: it settles back, and will be waiting again soon.
                if (now - d.lastNear > 60_000) {
                    reset(w, d, now + 2 * 60_000);
                    return;
                }
                for (ServerPlayerEntity p : here) Troops.provokeAll(d.session, p.getUuid());
                traps(w, a, d, here, now);
                if (!second) return;
                if (d.phase == Phase.HOLDERS && Troops.count(d.session) == 0) {
                    d.phase = Phase.WARDEN;
                    warden(w, a, d, here, floor);
                } else if (d.phase == Phase.WARDEN && wardenDown(w, a, d)) {
                    clear(w, a, d);
                    reset(w, d, now + 20 * 60_000);
                }
            }
        }
    }

    private static boolean wardenDown(ServerWorld w, Net.Area a, Delve d) {
        if (d.warden != null) {
            Entity e = w.getEntity(d.warden);
            if (e != null && e.isAlive()) return false;
            // Unloaded, not killed: wait for it.
            if (e == null && !w.isChunkLoaded(a.x() >> 4, a.z() >> 4)) return false;
        }
        return Troops.count(d.session) == 0;
    }

    private static void warden(ServerWorld w, Net.Area a, Delve d, List<ServerPlayerEntity> here, BlockPos floor) {
        Random r = w.random;
        int lv = level(a);
        UUID first = here.isEmpty() ? null : here.get(0).getUuid();
        List<Entity> shown = new ArrayList<>();
        String title;
        // A hall big enough for an ogre: the ogre is its master.
        if (AotRpg.CAVES.ready(w, a) && !TitanTypes.ogres().isEmpty() && r.nextFloat() < 0.6f) {
            Entity o = Elites.spawn(w, a.x() + 0.5, a.y(), a.z() + 0.5, lv >= 80 && r.nextFloat() < 0.3f, lv + 5);
            if (o != null) {
                d.warden = o.getUuid();
                shown.add(o);
            }
            title = "CAVE WARDEN";
        } else {
            Troops.groupBelow(w, floor, 1, lv + 6, d.session, 10, r, Troops.Arch.BRUTE, 5, first);
            if (lv >= 45) Troops.groupBelow(w, floor, 2, lv + 3, d.session, 12, r, Troops.Arch.HEADSMAN, 5, first);
            for (VillagerEntity v : Troops.bodies(w, d.session)) {
                var size = v.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_SCALE);
                if (size != null && size.getBaseValue() > 1.2) {
                    size.setBaseValue(1.75);
                    var hp = v.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_MAX_HEALTH);
                    if (hp != null) hp.setBaseValue(hp.getBaseValue() * 2.2);
                    v.setHealth(v.getMaxHealth());
                    v.setCustomName(Text.literal("Lv " + (lv + 6) + " ").formatted(Formatting.GRAY).append(Text.literal("Warden of " + a.name()).formatted(Formatting.GOLD)));
                }
                shown.add(v);
            }
            title = "THE WARDEN";
        }
        for (ServerPlayerEntity p : here) Troops.provokeAll(d.session, p.getUuid());
        if (!shown.isEmpty()) Cinematics.intro(here, Vec3d.ofBottomCenter(floor), shown, title, a.name(), 0xE0B96A);
        w.playSound(null, floor, SoundEvents.ENTITY_WARDEN_EMERGE, SoundCategory.HOSTILE, 2f, 0.7f);
    }

    /** The cave itself: the roof coming down, bad air seeping up. */
    private static void traps(ServerWorld w, Net.Area a, Delve d, List<ServerPlayerEntity> here, long now) {
        if (d.trapAt != null) {
            d.trapT -= 5;
            Vec3d c = d.trapAt;
            if (d.trapKind == 0) {
                // Dust trickling down first.
                w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.FALLING_DUST, net.minecraft.block.Blocks.GRAVEL.getDefaultState()),
                    c.x, c.y + 5, c.z, 10, 1.2, 0.3, 1.2, 0);
                if (d.trapT <= 0) {
                    w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, net.minecraft.block.Blocks.STONE.getDefaultState()), c.x, c.y + 1.5, c.z, 60, 1.4, 1.2, 1.4, 0.2);
                    AotParticles.one(w, AotParticles.fx(AotParticles.RING, 0xA09080, 3f), c.x, c.y + 0.1, c.z, 0, 0, 0);
                    w.playSound(null, BlockPos.ofFloored(c), SoundEvents.BLOCK_DRIPSTONE_BLOCK_BREAK, SoundCategory.BLOCKS, 2f, 0.5f);
                    w.playSound(null, BlockPos.ofFloored(c), SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.BLOCKS, 0.6f, 0.6f);
                    for (ServerPlayerEntity p : here) {
                        if (p.squaredDistanceTo(c) > 2.4 * 2.4) continue;
                        p.damage(w.getDamageSources().fallingBlock(p), 6 + level(a) * 0.15f);
                        p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 40, 2, false, false, true));
                    }
                    d.trapAt = null;
                }
            } else {
                d.trapAt = null;
            }
            return;
        }
        if (now < d.nextTrap || here.isEmpty()) return;
        d.nextTrap = now + 7000 + w.random.nextInt(6000);
        ServerPlayerEntity p = here.get(w.random.nextInt(here.size()));
        if (w.random.nextFloat() < 0.65f) {
            d.trapAt = p.getPos();
            d.trapKind = 0;
            d.trapT = 25;
            w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_POINTED_DRIPSTONE_FALL, SoundCategory.BLOCKS, 1.5f, 0.6f);
        } else {
            // Bad air, a few blocks off.
            double ang = w.random.nextDouble() * Math.PI * 2;
            AreaEffectCloudEntity cloud = new AreaEffectCloudEntity(w, p.getX() + Math.cos(ang) * 3, p.getY(), p.getZ() + Math.sin(ang) * 3);
            cloud.setRadius(3.2f);
            cloud.setDuration(160);
            cloud.setRadiusGrowth(0.005f);
            cloud.setWaitTime(10);
            cloud.addEffect(new StatusEffectInstance(StatusEffects.POISON, 60, 1));
            cloud.addEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 80, 0));
            cloud.setParticleType(net.minecraft.particle.EntityEffectParticleEffect.create(ParticleTypes.ENTITY_EFFECT, 0x6A8A3A));
            w.spawnEntity(cloud);
            w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.BLOCKS, 1f, 0.5f);
        }
    }

    private static void clear(ServerWorld w, Net.Area a, Delve d) {
        int lv = level(a);
        Vec3d c = new Vec3d(a.x(), a.y(), a.z());
        for (UUID id : d.in) {
            if (!(w.getPlayerByUuid(id) instanceof ServerPlayerEntity p) || p.squaredDistanceTo(c) > 90 * 90) continue;
            var r = p.getRandom();
            long marks = 280 + lv * 8L;
            AotRpg.WALLET.earn(p, marks, "Delve cleared");
            AotRpg.PROGRESSION.award(p, 400 + lv * 10L, "Delve cleared");
            AotRpg.SEASON.xp(p, 600);
            Gear.Rarity rar = lv >= 80 ? (r.nextFloat() < 0.15f ? Gear.Rarity.MYTHIC : Gear.Rarity.LEGENDARY)
                : lv >= 50 ? (r.nextFloat() < 0.35f ? Gear.Rarity.LEGENDARY : Gear.Rarity.EPIC) : (r.nextFloat() < 0.4f ? Gear.Rarity.EPIC : Gear.Rarity.RARE);
            AotRpg.SATCHEL.add(p, Gear.roll(r, rar, Gear.dropLevel(p, lv, 2)));
            Reveal.show(p, "CAVE CLEARED", a.name() + " · +" + marks + " Marks · " + rar.title + " gear in your satchel", "minecraft:chest", rar.ordinal());
        }
    }

    private static void reset(ServerWorld w, Delve d, long ready) {
        if (d.session != null) Troops.clear(w, d.session);
        if (d.warden != null) {
            Entity e = w.getEntity(d.warden);
            if (e != null && e.isAlive() && d.phase != Phase.IDLE && System.currentTimeMillis() - d.lastNear > 60_000) e.discard();
        }
        d.phase = Phase.IDLE;
        d.warden = null;
        d.trapAt = null;
        d.in.clear();
        d.ready = ready;
    }

    /** In a delve right now (for other systems). */
    public static boolean busy(String caveId) {
        Delve d = delves.get(caveId);
        return d != null && d.phase != Phase.IDLE;
    }

    @SuppressWarnings("unused")
    private static Box box(Net.Area a) {
        return new Box(a.x() - 48, a.y() - 20, a.z() - 48, a.x() + 48, a.y() + 30, a.z() + 48);
    }

    @SuppressWarnings("unused")
    private static boolean living(LivingEntity e) {
        return e != null && e.isAlive();
    }
}
