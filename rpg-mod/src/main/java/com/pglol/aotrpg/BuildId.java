package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Same build on both ends, or no game: the client tells the server which build of the mod it's
 * running (a fingerprint of the jar itself), and a mismatch, or an old client that can't say, is
 * turned away at the door with a plain message instead of crashing somewhere later.
 */
public final class BuildId {
    private BuildId() {}

    private static String id;
    private static final Map<UUID, String> heard = new ConcurrentHashMap<>();

    /** A short fingerprint of this mod's jar ("dev" when running from source). */
    public static synchronized String id() {
        if (id != null) return id;
        id = "dev";
        FabricLoader.getInstance().getModContainer("aot_rpg").ifPresent(m -> {
            try {
                for (Path p : m.getOrigin().getPaths()) {
                    if (!Files.isRegularFile(p)) continue;
                    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                    crc.update(Files.readAllBytes(p));
                    id = String.format("%08x", crc.getValue());
                }
            } catch (Exception ignored) {
                // Unusual origin: no fingerprint, treat as dev.
            }
        });
        return id;
    }

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(Net.Hello.ID, (payload, ctx) -> {
            heard.put(ctx.player().getUuid(), payload.build());
            check(ctx.player());
        });
        ServerPlayConnectionEvents.JOIN.register((h, sender, s) -> {
            ServerPlayerEntity p = h.player;
            AotRpg.SCHEDULER.later(100, () -> {
                if (p.isDisconnected() || !AotRpg.hasClient(p)) return;
                if (!heard.containsKey(p.getUuid())) kick(p, "an older version");
            });
        });
        ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> heard.remove(h.player.getUuid()));
    }

    private static void check(ServerPlayerEntity p) {
        String theirs = heard.get(p.getUuid()), ours = id();
        if (theirs == null || ours.equals("dev") || theirs.equals("dev") || theirs.equals(ours)) return;
        kick(p, "build " + theirs);
    }

    private static void kick(ServerPlayerEntity p, String theirs) {
        AotRpg.LOG.info("{} joined with {} of the mod (server has {}): turned away", p.getName().getString(), theirs, id());
        p.networkHandler.disconnect(Text.literal("Your AoT RPG mod is out of date").formatted(Formatting.GOLD, Formatting.BOLD)
            .append(Text.literal("\n\nYou have " + theirs + "; this server runs build " + id() + ".").formatted(Formatting.GRAY))
            .append(Text.literal("\nRestart your launcher to update automatically, or download the latest aot-rpg.jar, then join again.").formatted(Formatting.WHITE)));
    }
}
