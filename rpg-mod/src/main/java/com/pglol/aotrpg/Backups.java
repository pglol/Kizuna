package com.pglol.aotrpg;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Backups of everything the mod keeps (characters, stashes, homes, seasons, the economy): zipped
 * every half hour and when the server stops, into aot_rpg_backups/ beside the world, the last 48
 * kept. Written on a background thread so the server never stalls for it.
 */
public final class Backups {
    private Backups() {}

    private static final int KEEP = 48;
    private static volatile boolean running;

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (s.getTicks() % 36_000 == 600) run(s, false);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> run(s, true));
    }

    public static void run(MinecraftServer s, boolean now) {
        if (running) return;
        Path src = s.getSavePath(WorldSavePath.ROOT).resolve("aot_rpg");
        Path out = s.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize().getParent().resolve("aot_rpg_backups");
        Runnable job = () -> {
            running = true;
            try {
                if (!Files.isDirectory(src)) return;
                Files.createDirectories(out);
                Path zip = out.resolve("aot_rpg-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".zip");
                try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip)); var walk = Files.walk(src)) {
                    for (Path f : (Iterable<Path>) walk::iterator) {
                        if (!Files.isRegularFile(f) || f.toString().contains("reports")) continue;
                        z.putNextEntry(new ZipEntry(src.relativize(f).toString().replace('\\', '/')));
                        try {
                            Files.copy(f, z);
                        } catch (Exception ignored) {
                            // A file being written right now: skip it this time.
                        }
                        z.closeEntry();
                    }
                }
                try (var list = Files.list(out)) {
                    var old = list.filter(f -> f.getFileName().toString().startsWith("aot_rpg-")).sorted(java.util.Comparator.reverseOrder()).skip(KEEP).toList();
                    for (Path f : old) Files.deleteIfExists(f);
                }
            } catch (Exception e) {
                AotRpg.LOG.error("Backup failed", e);
            } finally {
                running = false;
            }
        };
        if (now) job.run();
        else new Thread(job, "aot_rpg-backup").start();
    }
}
