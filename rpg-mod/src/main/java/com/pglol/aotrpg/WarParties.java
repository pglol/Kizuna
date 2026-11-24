package com.pglol.aotrpg;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.List;

/**
 * The wilds are lived in. Out beyond the towns there are always people about: Marleyan patrols
 * hunting for a fight (players first, titans too), bandits who don't like you coming close,
 * deserters, mercenaries who mind their own business until you strike, lone wandering swordsmen,
 * and now and then a blade master. Nothing is announced: you come across them.
 */
public final class WarParties {
    private WarParties() { }

    private record Group(String session, BlockPos at) { }

    private static final List<Group> groups = new ArrayList<>();
    private static MinecraftServer server;
    private static int seq;
    /** Groups kept around each player out in the wilds (fewer while fighting: the director holds new ones back). */
    private static final int NEAR_EACH = 4, MAX = 40;

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            groups.clear();
        });
        // Sparring partners pull their cuts: steel rings, nobody bleeds.
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DAMAGE.register((e, src, amt) -> {
            if (!Troops.sameSquad(e, src.getAttacker()) || !(e.getWorld() instanceof ServerWorld w)) return true;
            var at = e.getPos().add(0, 1.2, 0);
            AotParticles.burst(w, AotParticles.SPARK, 0xFFE08A, 0.4f, at.x, at.y, at.z, 6, 0.2, 0.3);
            w.playSound(null, e.getBlockPos(), net.minecraft.sound.SoundEvents.BLOCK_ANVIL_PLACE, net.minecraft.sound.SoundCategory.HOSTILE, 0.5f, 1.9f);
            return false;
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (s.getTicks() % 60 == 13) tick();
            if (s.getTicks() % 40 == 21) clearTowns();
        });
    }

    /** Nothing on the map: you find them. */
    public static void markers(ServerPlayerEntity p, List<Net.Marker> list) { }

    /** Monsters don't stalk the streets: any that get into a town where people are, vanish. */
    private static void clearTowns() {
        if (server == null) return;
        ServerWorld w = server.getOverworld();
        for (ServerPlayerEntity p : w.getPlayers()) {
            if (!Troops.safe(w, p.getX(), p.getZ())) continue;
            for (var e : w.getEntitiesByClass(net.minecraft.entity.mob.MobEntity.class, p.getBoundingBox().expand(64, 32, 64),
                m -> m instanceof net.minecraft.entity.mob.Monster && m.isAlive() && !AotRpg.isTitan(m) && !m.hasCustomName() && !Troops.is(m))) {
                if (!Troops.safe(w, e.getX(), e.getZ())) continue;
                w.spawnParticles(net.minecraft.particle.ParticleTypes.CLOUD, e.getX(), e.getY() + 0.8, e.getZ(), 8, 0.3, 0.4, 0.3, 0.02);
                e.discard();
            }
        }
    }

    private static void tick() {
        if (server == null) return;
        ServerWorld w = server.getOverworld();
        List<ServerPlayerEntity> out = new ArrayList<>();
        for (ServerPlayerEntity p : w.getPlayers()) {
            if (p.isSpectator() || !AotRpg.PROFILES.get(p.getUuid()).created) continue;
            out.add(p);
        }
        // Wiped out, or left far behind: gone.
        for (Group g : new ArrayList<>(groups)) {
            Vec3d at = Troops.where(g.session());
            Vec3d c = at != null ? at : Vec3d.ofCenter(g.at());
            boolean near = false;
            for (ServerPlayerEntity p : w.getPlayers()) if (p.squaredDistanceTo(c) < 320 * 320) near = true;
            if (Troops.count(g.session()) == 0 || !near) {
                groups.remove(g);
                Troops.clear(w, g.session());
            }
        }
        // Everyone out in the wilds has a couple of groups somewhere around them.
        for (ServerPlayerEntity p : out) {
            if (groups.size() >= MAX) break;
            int around = 0;
            for (Group g : groups) {
                Vec3d at = Troops.where(g.session());
                Vec3d c = at != null ? at : Vec3d.ofCenter(g.at());
                if (p.squaredDistanceTo(c) < 200 * 200) around++;
            }
            // The director decides when: not into a fight, not in the breather after it, and spaced out.
            if (around < NEAR_EACH && Director.allow(p, Director.Kind.WILD)) spawn(w, p);
        }
    }

    private static void spawn(ServerWorld w, ServerPlayerEntity near) {
        Random r = w.random;
        BlockPos at = null;
        for (int t = 0; t < 10 && at == null; t++) {
            // Ahead of you if you're travelling, so you meet them rather than outrun them.
            double a = Director.aheadAngle(near, r), d = 70 + r.nextInt(100);
            int x = (int) (near.getX() + Math.cos(a) * d), z = (int) (near.getZ() + Math.sin(a) * d);
            if (AotRpg.PLACES.nearest(x, z, 90, "town", "village", "city", "capital", "camp") != null) continue;
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos b = new BlockPos(x, y, z);
            if (!w.getFluidState(b.down()).isEmpty() || y <= w.getSeaLevel()) continue;
            at = b;
        }
        if (at == null) return;
        // New Game+: around a prestiged player they come harder (and pay in better loot).
        int level = Math.max(3, AotRpg.PLACES.levelAt(at.getX(), at.getZ())) + Prestige.enemyBonus(w, Vec3d.ofCenter(at), 220);
        String session = "wild:" + (seq++);
        float roll = r.nextFloat();
        int made;
        if (roll < 0.30f) {
            // A Marleyan patrol: they hunt.
            made = Troops.hunters(w, at, 3 + r.nextInt(3), level + 1, session, at, 220, r);
        } else if (roll < 0.48f) {
            made = Troops.group(w, at, 2 + r.nextInt(3), level, session, at, 140, r, Troops.Arch.BANDIT, 1 + r.nextInt(2));
        } else if (roll < 0.60f) {
            made = Troops.group(w, at, 2 + r.nextInt(2), level + 1, session, at, 140, r, Troops.Arch.DESERTER, 2 + r.nextInt(2));
        } else if (roll < 0.70f) {
            made = Troops.group(w, at, 2, level + 2, session, at, 180, r, Troops.Arch.MERCENARY, 3);
        } else if (roll < 0.80f) {
            // Brutes: one, or a pair.
            made = Troops.group(w, at, r.nextInt(3) == 0 ? 2 : 1, level + 3, session, at, 160, r, Troops.Arch.BRUTE, 3 + r.nextInt(2));
        } else if (roll < 0.88f) {
            made = Troops.group(w, at, 1, level + 4, session, at, 200, r, Troops.Arch.HEADSMAN, 4 + r.nextInt(2));
        } else if (roll < 0.97f) {
            // Alone, or a master travelling with an apprentice (they spar when nobody's about).
            made = Troops.group(w, at, r.nextInt(5) < 2 ? 2 : 1, level + 3, session, at, 200, r, Troops.Arch.SWORDSMAN, 3 + r.nextInt(3));
        } else {
            made = Troops.group(w, at, 1, level + 8, session, at, 200, r, Troops.Arch.MASTER, 5);
        }
        if (made > 0) {
            groups.add(new Group(session, at));
            Director.spawned(w, Vec3d.ofCenter(at), 200);
        }
    }
}
