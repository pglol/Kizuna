package com.pglol.aotrpg;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How high people swing, by area: once a second, for everyone in the air on their gear (wearing
 * it, off the ground, moving), how far above the ground they are. Averaged per area, so cramped
 * or flat stretches where nobody gets any height show up. Saved to aot_rpg/swing_metrics.json;
 * operators read it with /aotrpg swingstats (lowest first) and clear it with /aotrpg swingstats reset.
 */
public final class SwingMetrics {
    private SwingMetrics() {}

    /** One area's tally: samples in the air, their total height, the highest, and time spent on the ground in it. */
    private static final class Tally {
        long air, ground;
        double sum, sumSq, max;
    }

    private static Map<String, Tally> data = new HashMap<>();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path file;

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(SwingMetrics::load);
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> save());
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (s.getTicks() % 20 == 11) sample(s);
            if (s.getTicks() % 6000 == 11) save();
        });
        CommandRegistrationCallback.EVENT.register((d, reg, env) -> d.register(CommandManager.literal("aotrpg").requires(src -> src.hasPermissionLevel(2))
            .then(CommandManager.literal("swingstats").executes(c -> {
                report(c.getSource());
                return 1;
            }).then(CommandManager.literal("reset").executes(c -> {
                data.clear();
                save();
                c.getSource().sendFeedback(() -> Text.literal("Swing metrics cleared.").formatted(Formatting.GRAY), false);
                return 1;
            })))));
    }

    private static void sample(MinecraftServer s) {
        for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
            if (p.isSpectator() || p.isCreative() || p.hasVehicle() || p.isTouchingWater() || !(p.getWorld() instanceof ServerWorld w)) continue;
            if (w.getRegistryKey() != net.minecraft.world.World.OVERWORLD || OdmBoost.gear(p).isEmpty()) continue;
            Net.Area a = AotRpg.PLACES.areaAt(p.getX(), p.getZ());
            String key = a == null ? "Wilds" : a.name();
            Tally t = data.computeIfAbsent(key, k -> new Tally());
            if (p.isOnGround()) {
                t.ground++;
                continue;
            }
            // Height over whatever's below (ground, roofs, branches), up to 96 blocks down.
            int top = w.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, p.getBlockX(), p.getBlockZ());
            double h = p.getY() - top;
            if (h < 0) {
                // Under a canopy or overhang: find the floor straight down instead.
                BlockPos.Mutable m = p.getBlockPos().mutableCopy();
                int n = 0;
                while (n < 96 && w.getBlockState(m.down()).getCollisionShape(w, m.down()).isEmpty()) {
                    m.move(0, -1, 0);
                    n++;
                }
                h = p.getY() - m.getY();
            }
            // Just hopping isn't swinging.
            if (h < 2.5 || p.getVelocity().horizontalLength() < 0.25) continue;
            t.air++;
            t.sum += h;
            t.sumSq += h * h;
            t.max = Math.max(t.max, h);
        }
    }

    private static void report(net.minecraft.server.command.ServerCommandSource src) {
        List<Map.Entry<String, Tally>> list = new ArrayList<>(data.entrySet());
        list.removeIf(e -> e.getValue().air + e.getValue().ground < 30);
        if (list.isEmpty()) {
            src.sendFeedback(() -> Text.literal("No swing data yet (needs some time in the air on gear).").formatted(Formatting.GRAY), false);
            return;
        }
        // Lowest average first: the stuffy places.
        list.sort(java.util.Comparator.comparingDouble(e -> e.getValue().air == 0 ? 0 : e.getValue().sum / e.getValue().air));
        src.sendFeedback(() -> Text.literal("Swing height by area (lowest first)").formatted(Formatting.GOLD, Formatting.BOLD), false);
        for (var e : list) {
            Tally t = e.getValue();
            double avg = t.air == 0 ? 0 : t.sum / t.air;
            double sd = t.air == 0 ? 0 : Math.sqrt(Math.max(0, t.sumSq / t.air - avg * avg));
            double airShare = 100.0 * t.air / Math.max(1, t.air + t.ground);
            Formatting col = avg < 8 ? Formatting.RED : avg < 16 ? Formatting.YELLOW : Formatting.GREEN;
            String line = String.format(java.util.Locale.ROOT, "%s: avg %.1f ±%.1f, max %.0f · %.0f%% of gear time airborne · %d samples",
                e.getKey(), avg, sd, t.max, airShare, t.air + t.ground);
            src.sendFeedback(() -> Text.literal(line).formatted(col), false);
        }
    }

    private static void load(MinecraftServer s) {
        file = s.getSavePath(WorldSavePath.ROOT).resolve("aot_rpg").resolve("swing_metrics.json");
        try {
            if (Files.exists(file)) {
                Map<String, Tally> m = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                    new com.google.gson.reflect.TypeToken<Map<String, Tally>>() { }.getType());
                if (m != null) data = new HashMap<>(m);
            }
        } catch (Exception e) {
            AotRpg.LOG.error("Could not read swing_metrics.json", e);
        }
    }

    private static void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            AotRpg.LOG.error("Could not save swing_metrics.json", e);
        }
    }
}
