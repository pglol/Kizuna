package com.pglol.aotrpg;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The titans worth hunting at the top end.
 * <ul>
 *   <li><b>Ogres</b>: huge, slow, and they fight like it. A stomp that throws everyone on the ground
 *   around it (jump it), boulders torn up and hurled at anyone keeping their distance (watch for the
 *   shadow), and a roar once you've hurt it that staggers everyone near and calls other titans in.
 *   Two more nape strikes to fell, and the loot to match: Legendary gear (sometimes Mythic) for
 *   everyone who fought it, a fortune in Marks, a chance at a finisher.</li>
 *   <li><b>Apex titans</b>: around the strongest players (level 80 and up), now and then an
 *   ordinary titan comes out wrong: embers off its skin, everything an ogre does but faster,
 *   three more strikes, and the best loot in the world: Mythic gear more often than not.</li>
 * </ul>
 */
public final class Elites {
    private Elites() {}

    public static final String OGRE = "aot_elite_ogre", APEX = "aot_elite_apex";

    private static final class State {
        int stompCd = 60, throwCd = 80, tell, tellKind;
        boolean roared, announced;
        Vec3d aim;
    }

    private record Boulder(ServerWorld w, Vec3d from, Vec3d to, int ticks, int[] age, int level, UUID by) { }

    private static final Map<UUID, State> elites = new HashMap<>();
    private static final List<Boulder> boulders = new ArrayList<>();

    public static boolean ogre(Entity e) {
        return e != null && e.getCommandTags().contains(OGRE);
    }

    public static boolean apex(Entity e) {
        return e != null && e.getCommandTags().contains(APEX);
    }

    public static boolean elite(Entity e) {
        return ogre(e) || apex(e);
    }

    public static int extraStrikes(Entity e) {
        return apex(e) ? 3 : ogre(e) ? 2 : 0;
    }

    private static boolean ogrePath(Entity e) {
        return Registries.ENTITY_TYPE.getId(e.getType()).getPath().contains("ogre");
    }

    public static void makeApex(Entity e) {
        e.addCommandTag(APEX);
        elites.put(e.getUuid(), new State());
    }

    public static void register() {
        ServerEntityEvents.ENTITY_LOAD.register((e, w) -> {
            if (!AotRpg.isTitan(e) || TitanGuard.isShifter(e) || Raids.strikes(e) > 0) return;
            if (ogrePath(e) && !e.getCommandTags().contains(OGRE)) e.addCommandTag(OGRE);
            if (!elite(e) && TitanLevels.rootOf(e) == e && w.random.nextFloat() < 0.07f) {
                // An apex only shows itself to the strongest.
                for (ServerPlayerEntity p : w.getPlayers()) {
                    if (p.squaredDistanceTo(e) < 128 * 128 && AotRpg.PROFILES.get(p.getUuid()).level >= 80) {
                        makeApex(e);
                        break;
                    }
                }
            }
            if (elite(e)) elites.putIfAbsent(e.getUuid(), new State());
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (!boulders.isEmpty()) flyBoulders();
            if (s.getTicks() % 5 != 0 || elites.isEmpty()) return;
            for (ServerWorld w : s.getWorlds()) tick(w);
            if (s.getTicks() % 200 == 0) elites.keySet().removeIf(id -> {
                for (ServerWorld w : s.getWorlds()) if (w.getEntity(id) != null) return false;
                return true;
            });
        });
    }

    private static ServerPlayerEntity target(ServerWorld w, LivingEntity t) {
        ServerPlayerEntity best = null;
        double bd = 48 * 48;
        if (t instanceof MobEntity m && m.getTarget() instanceof ServerPlayerEntity tp && tp.isAlive() && !tp.isSpectator()) return tp;
        for (ServerPlayerEntity p : w.getPlayers()) {
            if (p.isSpectator() || p.isCreative() || !p.isAlive()) continue;
            double d = p.squaredDistanceTo(t);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    private static void tick(ServerWorld w) {
        for (var en : new ArrayList<>(elites.entrySet())) {
            if (!(w.getEntity(en.getKey()) instanceof LivingEntity t) || !t.isAlive()) continue;
            State st = en.getValue();
            boolean apex = apex(t);
            int lv = Math.max(1, TitanLevels.level(t));
            // Apex: embers off its skin, always.
            if (apex) {
                Vec3d c = t.getPos().add(0, t.getHeight() * 0.6, 0);
                AotParticles.burst(w, AotParticles.SPARK, 0xFF5A2A, 0.6f, c.x, c.y, c.z, 4, t.getWidth() * 0.4, 0.15);
            }
            ServerPlayerEntity p = target(w, t);
            if (p == null) continue;
            double hd = Math.hypot(p.getX() - t.getX(), p.getZ() - t.getZ());
            if (!st.announced && hd < 70) {
                st.announced = true;
                String title = apex ? "APEX TITAN" : "OGRE TITAN";
                List<ServerPlayerEntity> near = new ArrayList<>();
                for (ServerPlayerEntity o : w.getPlayers()) if (o.squaredDistanceTo(t) < 90 * 90) near.add(o);
                Cinematics.intro(near, t.getPos(), List.of(t), title, (apex ? "+3" : "+2") + " nape strikes · " + (apex ? "Mythic loot" : "Legendary loot"),
                    apex ? 0xE02A2A : 0xB06AE0);
            }
            float speed = apex ? 1.4f : 1f;
            if (st.stompCd > 0) st.stompCd -= Math.round(5 * speed);
            if (st.throwCd > 0) st.throwCd -= Math.round(5 * speed);
            // The roar, once it's been hurt at the nape.
            if (!st.roared && AotRpg.TITAN_LEVELS.strikesOn(t) >= 1) {
                st.roared = true;
                roar(w, t, lv);
            }
            if (st.tell > 0) {
                st.tell -= 5;
                Vec3d c = t.getPos();
                if (st.tellKind == 1) {
                    AotParticles.one(w, AotParticles.fx(AotParticles.RING, 0xE0B96A, 2f + (24 - st.tell) * 0.35f), c.x, c.y + 0.1, c.z, 0, 0, 0);
                    w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.DIRT.getDefaultState()), c.x, c.y + 0.2, c.z, 12, 4, 0.1, 4, 0.1);
                } else {
                    Vec3d hand = t.getPos().add(0, t.getHeight() * 0.85, 0);
                    w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.STONE.getDefaultState()), hand.x, hand.y, hand.z, 10, 0.8, 0.8, 0.8, 0.1);
                    if (st.aim != null) AotParticles.one(w, AotParticles.fx(AotParticles.RING, 0xE04A3A, 2.4f), st.aim.x, st.aim.y + 0.1, st.aim.z, 0, 0, 0);
                }
                if (st.tell <= 0) {
                    if (st.tellKind == 1) stomp(w, t, lv, apex);
                    else hurl(w, t, p, st, lv);
                }
                continue;
            }
            if (st.stompCd <= 0 && hd < 11 + t.getWidth() * 0.5) {
                st.tell = apex ? 16 : 24;
                st.tellKind = 1;
                st.stompCd = apex ? 110 : 160;
                w.playSound(null, t.getBlockPos(), SoundEvents.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 2f, 0.5f);
                w.playSound(null, t.getBlockPos(), SoundEvents.ENTITY_WARDEN_SONIC_CHARGE, SoundCategory.HOSTILE, 1.2f, 0.6f);
            } else if (st.throwCd <= 0 && hd > 14 && hd < 48) {
                st.tell = apex ? 10 : 16;
                st.tellKind = 2;
                st.throwCd = apex ? 80 : 120;
                st.aim = p.getPos().add(p.getVelocity().multiply(10, 0, 10));
                int gy = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(st.aim.x), (int) Math.floor(st.aim.z));
                if (Math.abs(gy - p.getY()) < 8) st.aim = new Vec3d(st.aim.x, gy, st.aim.z);
                w.playSound(null, t.getBlockPos(), SoundEvents.BLOCK_STONE_BREAK, SoundCategory.HOSTILE, 2f, 0.4f);
                w.playSound(null, t.getBlockPos(), SoundEvents.ENTITY_IRON_GOLEM_HURT, SoundCategory.HOSTILE, 1.5f, 0.4f);
            }
        }
    }

    private static void quake(ServerWorld w, Vec3d at, double range, float strength, int ticks) {
        for (ServerPlayerEntity o : PlayerLookup.around(w, at, range)) {
            if (!ServerPlayNetworking.canSend(o, Net.Quake.ID)) continue;
            float k = (float) (1 - Math.sqrt(o.squaredDistanceTo(at)) / range);
            if (k > 0.05f) ServerPlayNetworking.send(o, new Net.Quake(strength * k, ticks));
        }
    }

    private static void stomp(ServerWorld w, LivingEntity t, int lv, boolean apex) {
        Vec3d c = t.getPos();
        double r = 10 + t.getWidth() * 0.5;
        Impact.crater(w, t.getBlockPos().down(), 3);
        for (int i = 0; i < 3; i++) AotParticles.one(w, AotParticles.fx(AotParticles.RING, 0xC8B090, 5f + i * 4), c.x, c.y + 0.1, c.z, 0, 0, 0);
        for (int i = 0; i < 20; i++) {
            double a = i * Math.PI * 2 / 20;
            AotParticles.one(w, AotParticles.fx(AotParticles.PUFF, 0x8A7A68, 2f), c.x + Math.cos(a) * 3, c.y + 0.3, c.z + Math.sin(a) * 3, Math.cos(a) * 0.5, 0.1, Math.sin(a) * 0.5);
        }
        quake(w, c, 40, 1.2f, 16);
        w.playSound(null, t.getBlockPos(), SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.HOSTILE, 2f, 0.5f);
        w.playSound(null, t.getBlockPos(), SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.HOSTILE, 1.5f, 0.4f);
        for (ServerPlayerEntity p : w.getPlayers()) {
            if (p.isSpectator() || p.isCreative()) continue;
            double d = Math.hypot(p.getX() - c.x, p.getZ() - c.z);
            if (d > r || Math.abs(p.getY() - c.y) > 4) continue;
            // Jumped it, or up on the gear: it passes under you.
            if (!p.isOnGround() && p.getY() - c.y > 1.2) continue;
            float k = (float) (1 - d / r * 0.5);
            p.damage(w.getDamageSources().mobAttack(t), (10 + lv * 0.25f) * k * (apex ? 1.3f : 1));
            Impact.launch(p, t, 1.3 + 0.4 * k);
        }
    }

    private static void hurl(ServerWorld w, LivingEntity t, ServerPlayerEntity p, State st, int lv) {
        Vec3d from = t.getPos().add(0, t.getHeight() * 0.85, 0);
        Vec3d to = st.aim != null ? st.aim : p.getPos();
        int ticks = (int) Math.max(14, Math.min(30, from.distanceTo(to) * 0.6));
        boulders.add(new Boulder(w, from, to, ticks, new int[1], lv, t.getUuid()));
        w.playSound(null, t.getBlockPos(), SoundEvents.ENTITY_IRON_GOLEM_ATTACK, SoundCategory.HOSTILE, 2f, 0.4f);
        st.aim = null;
    }

    private static void flyBoulders() {
        for (Boulder b : new ArrayList<>(boulders)) {
            int a = ++b.age()[0];
            double k = a / (double) b.ticks();
            ServerWorld w = b.w();
            Vec3d p = b.from().lerp(b.to(), k).add(0, Math.sin(k * Math.PI) * Math.max(6, b.from().distanceTo(b.to()) * 0.25), 0);
            w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.COBBLESTONE.getDefaultState()), p.x, p.y, p.z, 14, 0.6, 0.6, 0.6, 0.05);
            if (a % 3 == 0) AotParticles.one(w, AotParticles.fx(AotParticles.RING, 0xE04A3A, 2.6f), b.to().x, b.to().y + 0.1, b.to().z, 0, 0, 0);
            if (a < b.ticks()) continue;
            boulders.remove(b);
            Vec3d c = b.to();
            Impact.crater(w, BlockPos.ofFloored(c).down(), 2);
            AotParticles.one(w, AotParticles.fx(AotParticles.BURST, 0xE8D8B8, 4f), c.x, c.y + 0.5, c.z, 0, 0, 0);
            AotParticles.burst(w, AotParticles.SHARD, 0x8A8070, 0.7f, c.x, c.y + 0.5, c.z, 18, 0.4, 0.5);
            quake(w, c, 30, 0.8f, 10);
            w.playSound(null, BlockPos.ofFloored(c), SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.HOSTILE, 1.6f, 0.7f);
            Entity by = w.getEntity(b.by());
            for (ServerPlayerEntity pl : w.getPlayers()) {
                if (pl.isSpectator() || pl.isCreative() || pl.squaredDistanceTo(c) > 3.6 * 3.6) continue;
                pl.damage(by != null ? w.getDamageSources().mobAttack((LivingEntity) by) : w.getDamageSources().generic(), 14 + b.level() * 0.3f);
                Impact.launch(pl, by != null ? by : pl, 1.4);
            }
        }
    }

    private static void roar(ServerWorld w, LivingEntity t, int lv) {
        Vec3d c = t.getPos();
        w.playSound(null, t.getBlockPos(), SoundEvents.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 2.5f, 0.5f);
        AotParticles.one(w, AotParticles.fx(AotParticles.RING, 0xFFFFFF, 12f), c.x, c.y + t.getHeight() * 0.8, c.z, 0, 0, 0);
        quake(w, c, 50, 1f, 30);
        for (ServerPlayerEntity p : w.getPlayers()) {
            if (p.squaredDistanceTo(t) > 24 * 24 || p.isSpectator()) continue;
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 1, false, false, true));
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 60, 0, false, false, true));
        }
        // Others answer the call.
        List<EntityType<?>> kinds = TitanTypes.ordinary();
        if (kinds.isEmpty()) return;
        for (int i = 0; i < 2; i++) {
            double a = w.random.nextDouble() * Math.PI * 2;
            int x = (int) (c.x + Math.cos(a) * 30), z = (int) (c.z + Math.sin(a) * 30);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            Entity n = kinds.get(w.random.nextInt(kinds.size())).create(w);
            if (n == null) continue;
            n.refreshPositionAndAngles(x + 0.5, w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z), z + 0.5, w.random.nextFloat() * 360, 0);
            n.addCommandTag("aot_titan");
            TitanLevels.fix(n, Math.max(1, lv - 4));
            w.spawnEntity(n);
        }
    }

    /** One of them fell: everyone who fought it shares the haul (each their own roll). */
    public static void onKill(ServerPlayerEntity killer, LivingEntity dead) {
        if (killer == null || !elite(dead)) return;
        elites.remove(dead.getUuid());
        boolean apex = apex(dead);
        int lv = Math.max(1, TitanLevels.level(dead));
        for (ServerPlayerEntity p : AotRpg.LOOT.earners(killer, dead)) {
            var r = p.getRandom();
            long marks = (apex ? 900 : 650) + lv * (apex ? 15L : 12L);
            AotRpg.WALLET.earn(p, marks, apex ? "Apex titan slain" : "Ogre slain");
            AotRpg.SEASON.xp(p, apex ? 1200 : 800);
            AotRpg.PROGRESSION.award(p, (apex ? 1400 : 900) + lv * 10L, apex ? "Apex titan" : "Ogre");
            Gear.Rarity rar = apex ? (r.nextFloat() < 0.6f ? Gear.Rarity.MYTHIC : Gear.Rarity.LEGENDARY)
                : (r.nextFloat() < 0.2f ? Gear.Rarity.MYTHIC : Gear.Rarity.LEGENDARY);
            AotRpg.SATCHEL.add(p, Gear.roll(r, rar, Gear.dropLevel(p, lv, 3)));
            if (apex) AotRpg.SATCHEL.add(p, Gear.roll(r, Gear.Rarity.LEGENDARY, Gear.dropLevel(p, lv, 2)));
            if (r.nextFloat() < (apex ? 0.3f : 0.15f)) AotRpg.COSMETICS.dropFinisher(p, w(p));
            Reveal.show(p, apex ? "APEX TITAN SLAIN" : "OGRE SLAIN", "+" + marks + " Marks · " + rar.title + " gear in your satchel", "minecraft:nether_star", rar.ordinal());
        }
        Contracts.onTitanKill(killer, dead);
    }

    private static net.minecraft.util.math.random.Random w(ServerPlayerEntity p) {
        return p.getRandom();
    }

    /** For other systems (contracts, delves): one of these titans, here. */
    public static Entity spawn(ServerWorld w, double x, double y, double z, boolean apexKind, int level) {
        List<EntityType<?>> og = TitanTypes.ogres();
        List<EntityType<?>> kinds = apexKind || og.isEmpty() ? TitanTypes.ordinary() : og;
        if (kinds.isEmpty()) return null;
        Entity t = kinds.get(w.random.nextInt(kinds.size())).create(w);
        if (t == null) return null;
        t.refreshPositionAndAngles(x, y, z, w.random.nextFloat() * 360, 0);
        t.addCommandTag("aot_titan");
        t.addCommandTag(apexKind || og.isEmpty() ? APEX : OGRE);
        TitanLevels.fix(t, level);
        if (!w.spawnEntity(t)) return null;
        elites.put(t.getUuid(), new State());
        return t;
    }

    static Text label(Entity e) {
        return Text.literal(apex(e) ? "Apex" : "Ogre").formatted(apex(e) ? Formatting.RED : Formatting.LIGHT_PURPLE);
    }
}
