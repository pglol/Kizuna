package com.pglol.aotrpg;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
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
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Marleyan war parties in the open world: every so often a landing party comes ashore near
 * someone (never inside a town), the horn goes up across the island, and it's on the map for
 * everyone. They hunt: the nearest fighter, and any titan in their way. Whoever helps put them
 * down shares the pay. Something to ride out to together.
 */
public final class WarParties {
    private WarParties() { }

    private static final class Party {
        final String session;
        final BlockPos landed;
        final String where;
        final long until;
        final Set<UUID> fought = new HashSet<>();

        Party(String session, BlockPos landed, String where, long until) {
            this.session = session;
            this.landed = landed;
            this.where = where;
            this.until = until;
        }
    }

    private static final List<Party> parties = new ArrayList<>();
    private static MinecraftServer server;
    private static long nextAt;
    private static int seq;

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            parties.clear();
            nextAt = System.currentTimeMillis() + 4 * 60_000L;
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (s.getTicks() % 20 == 13) tick();
        });
    }

    /** On the map for everyone: where the party is now. */
    public static void markers(ServerPlayerEntity p, List<Net.Marker> list) {
        if (p.getWorld().getRegistryKey() != World.OVERWORLD) return;
        for (Party w : parties) {
            Vec3d at = Troops.where(w.session);
            BlockPos b = at == null ? w.landed : BlockPos.ofFloored(at);
            list.add(new Net.Marker("event", "Marleyan war party", b.getX(), b.getY(), b.getZ(), 0xE04A3A));
        }
    }

    private static void tick() {
        if (server == null) return;
        ServerWorld w = server.getOverworld();
        long now = System.currentTimeMillis();
        List<ServerPlayerEntity> out = new ArrayList<>();
        for (ServerPlayerEntity p : w.getPlayers()) {
            if (p.isSpectator() || p.isCreative() || !AotRpg.PROFILES.get(p.getUuid()).created) continue;
            out.add(p);
        }
        // Those under way: who's fighting them, and how it ends.
        for (Party pt : new ArrayList<>(parties)) {
            Vec3d at = Troops.where(pt.session);
            if (at != null) for (ServerPlayerEntity p : out) if (p.squaredDistanceTo(at) < 60 * 60) pt.fought.add(p.getUuid());
            int left = Troops.count(pt.session);
            if (left == 0) {
                parties.remove(pt);
                routed(w, pt);
            } else if (now > pt.until) {
                parties.remove(pt);
                Troops.clear(w, pt.session);
                for (ServerPlayerEntity p : out) p.sendMessage(Text.literal("The war party withdraws to the coast").formatted(Formatting.GRAY), true);
            }
        }
        if (out.isEmpty() || parties.size() >= 2 || now < nextAt) return;
        // The next one in six to ten minutes (sooner with more people about).
        nextAt = now + (6 + w.random.nextInt(5)) * 60_000L / Math.max(1, Math.min(3, out.size() / 3 + 1));
        land(w, out.get(w.random.nextInt(out.size())), out);
    }

    /** Ashore near someone, out in the open (never in a town). */
    private static void land(ServerWorld w, ServerPlayerEntity near, List<ServerPlayerEntity> everyone) {
        Random r = w.random;
        BlockPos at = null;
        for (int t = 0; t < 12 && at == null; t++) {
            double a = r.nextDouble() * Math.PI * 2, d = 90 + r.nextInt(45);
            int x = (int) (near.getX() + Math.cos(a) * d), z = (int) (near.getZ() + Math.sin(a) * d);
            if (AotRpg.PLACES.nearest(x, z, 90, "town", "village", "city", "capital", "camp") != null) continue;
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos b = new BlockPos(x, y, z);
            if (!w.getFluidState(b.down()).isEmpty() || y <= w.getSeaLevel()) continue;
            at = b;
        }
        if (at == null) return;
        int level = Math.max(5, AotRpg.PLACES.levelAt(at.getX(), at.getZ()) + 2);
        String session = "war:" + (seq++);
        int made = Troops.hunters(w, at, 5 + r.nextInt(2), level, session, at, 500, r);
        if (made == 0) return;
        Net.Area area = AotRpg.PLACES.nearest(at.getX(), at.getZ(), 600, "town", "village", "city", "capital");
        String where = area == null ? "the wilds" : "near " + area.name();
        parties.add(new Party(session, at, where, System.currentTimeMillis() + 15 * 60_000L));
        AotParticles.burst(w, AotParticles.PUFF, 0xD8D0C0, 2f, at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5, 40, 3, 0.1);
        for (ServerPlayerEntity p : everyone) {
            if (ServerPlayNetworking.canSend(p, Net.EventBanner.ID))
                ServerPlayNetworking.send(p, new Net.EventBanner("MARLEYAN WAR PARTY", "Landed " + where + "  ·  Lv " + level, 0xE04A3A, "minecraft:crossbow"));
            p.playSoundToPlayer(SoundEvents.EVENT_RAID_HORN.value(), SoundCategory.HOSTILE, 0.8f, 0.8f);
            AotRpg.QUESTS.markers(p, true);
        }
    }

    /** Put down: the pay goes round everyone who fought, and the island hears of it. */
    private static void routed(ServerWorld w, Party pt) {
        for (ServerPlayerEntity p : w.getPlayers()) {
            boolean fought = pt.fought.contains(p.getUuid());
            if (ServerPlayNetworking.canSend(p, Net.EventBanner.ID))
                ServerPlayNetworking.send(p, new Net.EventBanner("WAR PARTY ROUTED", fought ? "Your share: 250 Marks" : "Put down " + pt.where, 0xF2C14E, "minecraft:iron_sword"));
            if (fought) {
                AotRpg.WALLET.earn(p, 250, "War party routed");
                p.playSoundToPlayer(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 0.7f, 1.1f);
            }
            AotRpg.QUESTS.markers(p, true);
        }
    }
}
