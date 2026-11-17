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
    private static final int NEAR_EACH = 4, MAX = 32;

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            groups.clear();
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (s.getTicks() % 60 == 13) tick();
        });
    }

    /** Nothing on the map: you find them. */
    public static void markers(ServerPlayerEntity p, List<Net.Marker> list) { }

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
            if (around < NEAR_EACH) spawn(w, p);
        }
    }

    private static void spawn(ServerWorld w, ServerPlayerEntity near) {
        Random r = w.random;
        BlockPos at = null;
        for (int t = 0; t < 10 && at == null; t++) {
            double a = r.nextDouble() * Math.PI * 2, d = 60 + r.nextInt(110);
            int x = (int) (near.getX() + Math.cos(a) * d), z = (int) (near.getZ() + Math.sin(a) * d);
            if (AotRpg.PLACES.nearest(x, z, 90, "town", "village", "city", "capital", "camp") != null) continue;
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos b = new BlockPos(x, y, z);
            if (!w.getFluidState(b.down()).isEmpty() || y <= w.getSeaLevel()) continue;
            at = b;
        }
        if (at == null) return;
        int level = Math.max(3, AotRpg.PLACES.levelAt(at.getX(), at.getZ()));
        String session = "wild:" + (seq++);
        float roll = r.nextFloat();
        int made;
        if (roll < 0.34f) {
            // A Marleyan patrol: they hunt.
            made = Troops.hunters(w, at, 3 + r.nextInt(3), level + 1, session, at, 400, r);
        } else if (roll < 0.56f) {
            made = Troops.group(w, at, 2 + r.nextInt(3), level, session, at, 140, r, Troops.Arch.BANDIT, 1 + r.nextInt(2));
        } else if (roll < 0.70f) {
            made = Troops.group(w, at, 2 + r.nextInt(2), level + 1, session, at, 140, r, Troops.Arch.DESERTER, 2 + r.nextInt(2));
        } else if (roll < 0.82f) {
            made = Troops.group(w, at, 2, level + 2, session, at, 180, r, Troops.Arch.MERCENARY, 3);
        } else if (roll < 0.97f) {
            made = Troops.group(w, at, 1, level + 3, session, at, 200, r, Troops.Arch.SWORDSMAN, 3 + r.nextInt(3));
        } else {
            made = Troops.group(w, at, 1, level + 8, session, at, 200, r, Troops.Arch.MASTER, 5);
        }
        if (made > 0) groups.add(new Group(session, at));
    }
}
