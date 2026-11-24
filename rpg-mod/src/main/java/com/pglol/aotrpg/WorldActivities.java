package com.pglol.aotrpg;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Things to do around the world, several at once and all yours: a supply cache dropped in the
 * wilds, a bandit camp to clear, a titan sighted, survivors cornered and waiting for help, a lost
 * scout's kit. Each player always has a few within a few hundred blocks, on the compass, the
 * markers and the tracker, alongside whatever quest they're following. Done or abandoned (you went
 * far away), a new one turns up somewhere else.
 */
public final class WorldActivities {
    private WorldActivities() {}

    public enum Kind {
        CACHE("Supply Cache", "A Survey Corps drop. Reach it and claim it", 0x9AD0FF),
        CAMP("Bandit Camp", "Clear out the camp", 0xE0703A),
        TITAN("Titan Sighting", "Bring it down", 0xE04A3A),
        SURVIVORS("Survivors", "Clear their attackers, then reach them", 0x8CC08A),
        SCOUT("Lost Scout", "Recover the scout's kit", 0xE0B96A);

        public final String title, task;
        public final int color;

        Kind(String title, String task, int color) {
            this.title = title;
            this.task = task;
            this.color = color;
        }
    }

    private static final class Act {
        final Kind kind;
        final BlockPos at;
        final int level;
        final String session;
        boolean started, cleared;
        final List<UUID> spawned = new ArrayList<>();

        Act(Kind kind, BlockPos at, int level, String session) {
            this.kind = kind;
            this.at = at;
            this.level = level;
            this.session = session;
        }
    }

    private static final int EACH = 3;
    private static final Map<UUID, List<Act>> acts = new HashMap<>();
    private static int seq;

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (s.getTicks() % 20 != 3) return;
            for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) tick(p);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> {
            List<Act> l = acts.remove(h.player.getUuid());
            if (l != null) for (Act a : l) cleanup(s.getOverworld(), a);
        });
    }

    public static void markers(ServerPlayerEntity p, List<Net.Marker> list) {
        for (Act a : acts.getOrDefault(p.getUuid(), List.of())) {
            list.add(new Net.Marker("activity", a.kind.title, a.at.getX(), a.at.getY(), a.at.getZ(), a.kind.color));
        }
    }

    private static boolean eligible(ServerPlayerEntity p) {
        return !p.isSpectator() && AotRpg.PROFILES.get(p.getUuid()).created && p.getWorld().getRegistryKey() == net.minecraft.world.World.OVERWORLD
            && !Extraction.inRun(p.getUuid()) && !Extraction.inLobby(p);
    }

    private static void tick(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        List<Act> list = acts.computeIfAbsent(p.getUuid(), k -> new ArrayList<>());
        boolean changed = false;
        if (!eligible(p)) {
            return;
        }
        for (Act a : new ArrayList<>(list)) {
            double d = Math.hypot(p.getX() - a.at.getX(), p.getZ() - a.at.getZ());
            // Left far behind: it fades and something new turns up.
            if (d > 900) {
                cleanup(w, a);
                list.remove(a);
                changed = true;
                continue;
            }
            if (step(w, p, a, d)) {
                complete(w, p, a);
                list.remove(a);
                changed = true;
            }
        }
        while (list.size() < EACH) {
            Act a = place(w, p);
            if (a == null) break;
            list.add(a);
            changed = true;
        }
        if (changed) {
            AotRpg.QUESTS.markers(p, true);
            send(p);
        }
    }

    /** Advance one; true when it's done. */
    private static boolean step(ServerWorld w, ServerPlayerEntity p, Act a, double d) {
        Random r = w.random;
        switch (a.kind) {
            case CACHE, SCOUT -> {
                if (!a.started && d < 120) {
                    a.started = true;
                    if (a.kind == Kind.CACHE) w.setBlockState(a.at, Blocks.BARREL.getDefaultState(), 3);
                }
                return d < 3.5 && Math.abs(p.getY() - a.at.getY()) < 6;
            }
            case CAMP -> {
                if (!a.started && d < 110) {
                    a.started = true;
                    Troops.group(w, a.at, 3 + r.nextInt(2), a.level, a.session, a.at, 40, r, Troops.Arch.BANDIT, 2 + r.nextInt(2));
                    Director.spawned(w, Vec3d.ofCenter(a.at), 200);
                    return false;
                }
                return a.started && Troops.count(a.session) == 0;
            }
            case TITAN -> {
                if (!a.started && d < 140) {
                    a.started = true;
                    List<EntityType<?>> kinds = TitanTypes.ordinary();
                    if (kinds.isEmpty()) return true;
                    Entity t = kinds.get(r.nextInt(kinds.size())).create(w);
                    if (t == null) return true;
                    t.refreshPositionAndAngles(a.at.getX() + 0.5, a.at.getY(), a.at.getZ() + 0.5, r.nextFloat() * 360, 0);
                    t.addCommandTag("aot_titan");
                    TitanLevels.fix(t, a.level);
                    w.spawnEntity(t);
                    a.spawned.add(t.getUuid());
                    Director.spawned(w, Vec3d.ofCenter(a.at), 200);
                    return false;
                }
                if (!a.started) return false;
                for (UUID id : a.spawned) if (w.getEntity(id) instanceof LivingEntity le && le.isAlive()) return false;
                return true;
            }
            case SURVIVORS -> {
                if (!a.started && d < 110) {
                    a.started = true;
                    for (int i = 0; i < 3; i++) {
                        VillagerEntity v = EntityType.VILLAGER.create(w);
                        if (v == null) continue;
                        v.refreshPositionAndAngles(a.at.getX() + 0.5 + r.nextInt(3) - 1, a.at.getY(), a.at.getZ() + 0.5 + r.nextInt(3) - 1, r.nextFloat() * 360, 0);
                        v.setCustomName(Text.literal("Stranded Survivor").formatted(Formatting.GRAY));
                        v.setAiDisabled(true);
                        v.setInvulnerable(true);
                        v.setSilent(true);
                        v.addCommandTag("aot_activity_folk");
                        w.spawnEntity(v);
                        a.spawned.add(v.getUuid());
                    }
                    BlockPos foes = a.at.add(r.nextInt(9) - 4, 0, r.nextInt(9) - 4);
                    Troops.group(w, w.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, foes), 3, a.level, a.session, a.at, 30, r,
                        r.nextBoolean() ? Troops.Arch.BANDIT : Troops.Arch.DESERTER, 2);
                    Director.spawned(w, Vec3d.ofCenter(a.at), 200);
                    return false;
                }
                if (!a.started) return false;
                if (!a.cleared && Troops.count(a.session) == 0) {
                    a.cleared = true;
                    p.sendMessage(Text.literal("The survivors are safe: go to them").formatted(Formatting.GREEN), true);
                }
                return a.cleared && d < 6;
            }
        }
        return false;
    }

    private static void complete(ServerWorld w, ServerPlayerEntity p, Act a) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        long marks = 120 + a.level * 8L + (a.kind == Kind.TITAN || a.kind == Kind.SURVIVORS ? 120 : 0);
        AotRpg.WALLET.earn(p, marks, a.kind.title);
        AotRpg.PROGRESSION.award(p, 40 + a.level * 6L, a.kind.title);
        // Better finds now and then (and always from survivors' gratitude or a scout's kit).
        Random r = w.random;
        if (a.kind == Kind.SCOUT || a.kind == Kind.SURVIVORS || r.nextInt(3) == 0) {
            Gear.Rarity rar = r.nextInt(8) == 0 ? Gear.Rarity.EPIC : r.nextInt(3) == 0 ? Gear.Rarity.RARE : Gear.Rarity.UNCOMMON;
            Loot.claim(p, Gear.roll(r, rar, Gear.dropLevel(p, a.level, 1)));
        }
        if (a.kind == Kind.SURVIVORS) {
            for (UUID id : a.spawned) {
                Entity e = w.getEntity(id);
                if (e != null) {
                    w.spawnParticles(ParticleTypes.HAPPY_VILLAGER, e.getX(), e.getY() + 1.5, e.getZ(), 6, 0.3, 0.3, 0.3, 0);
                    e.discard();
                }
            }
        }
        if (a.kind == Kind.SCOUT || a.kind == Kind.CACHE) w.playSound(null, a.at, SoundEvents.BLOCK_BARREL_OPEN, SoundCategory.BLOCKS, 1f, 1f);
        w.playSound(null, p.getBlockPos(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 0.6f, 1.2f);
        Notify.toast(p, Text.literal(a.kind.title + " done").formatted(Formatting.GOLD, Formatting.BOLD),
            Text.literal("+" + marks + " Marks · more to find nearby"), a.kind.color, "minecraft:compass", null);
        AotRpg.TASKS.count(p, Tasks.QUESTS, 1);
        // The cache's barrel stays to rummage through; it's cleared away later with the area.
        AotRpg.SCHEDULER.later(1200, () -> cleanup(w, a));
    }

    private static void cleanup(ServerWorld w, Act a) {
        Troops.clear(w, a.session);
        for (UUID id : a.spawned) {
            Entity e = w.getEntity(id);
            if (e != null && e.isAlive() && (e instanceof VillagerEntity || !(e instanceof LivingEntity le) || le.getHealth() >= le.getMaxHealth())) e.discard();
        }
        if (a.kind == Kind.CACHE && w.getBlockState(a.at).isOf(Blocks.BARREL)) w.setBlockState(a.at, Blocks.AIR.getDefaultState(), 3);
    }

    /** Somewhere a few hundred blocks off, on dry land out of town, for something new. */
    private static Act place(ServerWorld w, ServerPlayerEntity p) {
        Random r = w.random;
        for (int t = 0; t < 12; t++) {
            double ang = r.nextDouble() * Math.PI * 2, d = 160 + r.nextInt(300);
            int x = (int) (p.getX() + Math.cos(ang) * d), z = (int) (p.getZ() + Math.sin(ang) * d);
            if (AotRpg.PLACES.nearest(x, z, 100, "town", "village", "city", "capital", "camp") != null) continue;
            if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                // Unloaded: trust the sea level for now; the marker settles on the real ground once it loads.
                if (t < 8) continue;
            }
            int y = w.isChunkLoaded(x >> 4, z >> 4) ? w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) : w.getSeaLevel() + 2;
            BlockPos b = new BlockPos(x, y, z);
            if (w.isChunkLoaded(x >> 4, z >> 4) && (!w.getFluidState(b.down()).isEmpty() || y <= w.getSeaLevel())) continue;
            // Never two of the same at once.
            Kind[] all = Kind.values();
            Kind k = all[r.nextInt(all.length)];
            for (Act o : acts.getOrDefault(p.getUuid(), List.of())) if (o.kind == k) k = all[(k.ordinal() + 1) % all.length];
            int lv = Math.max(3, AotRpg.PLACES.levelAt(x, z)) + Prestige.enemyBonus(w, p.getPos(), 50);
            return new Act(k, b, lv, "act:" + (seq++));
        }
        return null;
    }

    private static void send(ServerPlayerEntity p) {
        if (!ServerPlayNetworking.canSend(p, Net.Activities.ID)) return;
        List<String> rows = new ArrayList<>();
        for (Act a : acts.getOrDefault(p.getUuid(), List.of())) {
            String state = a.kind == Kind.SURVIVORS && a.cleared ? "Reach the survivors" : a.kind.task;
            rows.add(a.kind.title + "|" + state + "|" + a.at.getX() + "|" + a.at.getZ() + "|" + a.kind.color);
        }
        ServerPlayNetworking.send(p, new Net.Activities(rows));
    }
}
