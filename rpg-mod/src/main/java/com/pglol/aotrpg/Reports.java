package com.pglol.aotrpg;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.WorldSavePath;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * /report &lt;what happened&gt;: a bug report with everything an admin needs saved alongside it (where
 * you were, what you held, your level and state, what was around you, the mod build), written to
 * aot_rpg/reports/ and flagged to any operator online. /reports lists the latest for operators.
 */
public final class Reports {
    private Reports() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((d, reg, env) -> {
            d.register(CommandManager.literal("report").then(CommandManager.argument("what", StringArgumentType.greedyString()).executes(c -> {
                ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
                if (!RateLimit.allow(p, "report", 30_000)) {
                    p.sendMessage(Text.literal("One report every 30 seconds, please.").formatted(Formatting.GRAY), false);
                    return 0;
                }
                String file = write(p, StringArgumentType.getString(c, "what"));
                p.sendMessage(Text.literal("Thanks: your report was saved (" + file + ").").formatted(Formatting.GREEN), false);
                for (ServerPlayerEntity o : p.getServer().getPlayerManager().getPlayerList()) {
                    if (o != p && o.hasPermissionLevel(2)) o.sendMessage(Text.literal("[Report] " + p.getName().getString() + ": "
                        + StringArgumentType.getString(c, "what")).formatted(Formatting.GOLD), false);
                }
                return 1;
            })));
            d.register(CommandManager.literal("reports").requires(s -> s.hasPermissionLevel(2)).executes(c -> {
                Path dir = dir(c.getSource().getServer());
                try (var files = Files.list(dir)) {
                    var list = files.sorted(java.util.Comparator.reverseOrder()).limit(10).toList();
                    if (list.isEmpty()) c.getSource().sendFeedback(() -> Text.literal("No reports."), false);
                    for (Path f : list) {
                        String first = Files.readAllLines(f).stream().filter(l -> l.startsWith("Report:")).findFirst().orElse("");
                        c.getSource().sendFeedback(() -> Text.literal(f.getFileName() + "  " + first).formatted(Formatting.GRAY), false);
                    }
                } catch (Exception e) {
                    c.getSource().sendFeedback(() -> Text.literal("No reports."), false);
                }
                return 1;
            }));
        });
    }

    private static Path dir(net.minecraft.server.MinecraftServer s) {
        return s.getSavePath(WorldSavePath.ROOT).resolve("aot_rpg").resolve("reports");
    }

    private static String write(ServerPlayerEntity p, String what) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String name = stamp + "_" + p.getName().getString() + ".txt";
        StringBuilder b = new StringBuilder();
        b.append("Report: ").append(what).append('\n');
        b.append("Player: ").append(p.getName().getString()).append(" (").append(p.getUuid()).append(")\n");
        b.append("Character: ").append(pr.name).append(" · Lv ").append(pr.level).append(" · Prestige ").append(pr.prestige).append(" · mode ").append(pr.mode).append('\n');
        b.append("Where: ").append(p.getWorld().getRegistryKey().getValue()).append(" ").append(p.getBlockPos().toShortString())
            .append(" · area ").append(AotRpg.PLACES.areaAt(p.getX(), p.getZ()) == null ? "wilds" : AotRpg.PLACES.areaAt(p.getX(), p.getZ()).name()).append('\n');
        b.append("Health: ").append(Math.round(p.getHealth())).append("/").append(Math.round(p.getMaxHealth()))
            .append(" · in combat ").append(Classes.inCombat(p)).append(" · pacing ").append(Director.phase(p))
            .append(" · downed ").append(AotRpg.DOWNED.isDowned(p)).append(" · in a run ").append(Extraction.inRun(p.getUuid())).append('\n');
        b.append("Holding: ").append(p.getMainHandStack().getName().getString()).append(" / ").append(p.getOffHandStack().getName().getString()).append('\n');
        int titans = 0, troops = 0, others = 0;
        for (var e : p.getServerWorld().getOtherEntities(p, p.getBoundingBox().expand(64))) {
            if (AotRpg.isTitan(e)) titans++;
            else if (Troops.is(e)) troops++;
            else others++;
        }
        b.append("Around (64 blocks): ").append(titans).append(" titans, ").append(troops).append(" troops, ").append(others).append(" other entities\n");
        b.append("Online: ").append(p.getServer().getCurrentPlayerCount()).append(" · server build ").append(BuildId.id())
            .append(" · TPS-ish tick time ").append(String.format(java.util.Locale.ROOT, "%.1fms", p.getServer().getAverageTickTime())).append('\n');
        try {
            Path d = dir(p.getServer());
            Files.createDirectories(d);
            Files.writeString(d.resolve(name), b.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            AotRpg.LOG.error("Could not save report", e);
        }
        AotRpg.LOG.info("[Report] {}", b.toString().replace('\n', ' '));
        return name;
    }
}
