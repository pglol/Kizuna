package com.pglol.aotrpg;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Keeps a dedicated server on the newest build of the mod, the same one the launcher gives players.
 * On start and every ten minutes it looks up the newest published aot-rpg.jar; when it's a different
 * build it's downloaded next to the running jar (as aot-rpg.jar.update) and ops are told. It's
 * swapped in when the server restarts: the looping start script does that, and restarts by itself
 * once the server is empty (or on /aotrpg update restart). Settings: config/aot_rpg_update.properties.
 */
public final class ServerUpdate {
    private ServerUpdate() {}

    /** The newest published build (same fingerprint as BuildId), once known. */
    public static volatile String latest;
    private static volatile Path staged;
    private static volatile String announced = "";
    private static MinecraftServer server;
    private static final boolean LOOPING = Boolean.getBoolean("aotrpg.autorestart");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private static ScheduledExecutorService timer;
    private static boolean hooked;

    private static Properties config() {
        Properties p = new Properties();
        p.setProperty("enabled", "true");
        p.setProperty("repo", "pglol/minecraft");
        p.setProperty("branch", "claude/minecraft-aot-map-uucuom");
        p.setProperty("path", "dist/aot-rpg.jar");
        Path f = FabricLoader.getInstance().getConfigDir().resolve("aot_rpg_update.properties");
        try {
            if (Files.exists(f)) {
                try (var in = Files.newInputStream(f)) {
                    p.load(in);
                }
            } else {
                try (var out = Files.newOutputStream(f)) {
                    p.store(out, "AoT RPG server updates: the server keeps itself on the newest build (enabled=false to stop)");
                }
            }
        } catch (Exception ignored) {
        }
        return p;
    }

    private static Path jar() {
        return FabricLoader.getInstance().getModContainer("aot_rpg")
            .flatMap(m -> m.getOrigin().getPaths().stream().filter(Files::isRegularFile).findFirst()).orElse(null);
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            if (!s.isDedicated()) return;
            server = s;
            // An update that was already swapped in: tidy up.
            Path j = jar();
            if (j != null) {
                Path u = j.resolveSibling(j.getFileName() + ".update");
                try {
                    if (Files.exists(u) && crc(Files.readAllBytes(u)).equals(BuildId.id())) Files.delete(u);
                } catch (Exception ignored) {
                }
            }
            timer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "AoT RPG updates");
                t.setDaemon(true);
                return t;
            });
            timer.scheduleWithFixedDelay(ServerUpdate::check, 5, 600, TimeUnit.SECONDS);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            if (timer != null) timer.shutdownNow();
        });
        CommandRegistrationCallback.EVENT.register((d, reg, env) -> d.register(CommandManager.literal("aotrpg")
            .requires(s -> s.hasPermissionLevel(3))
            .then(CommandManager.literal("update")
                .executes(c -> {
                    c.getSource().sendFeedback(() -> Text.literal("Checking for a newer build…").formatted(Formatting.GRAY), false);
                    if (timer != null) timer.execute(() -> {
                        check();
                        tell(c.getSource().getServer(), status());
                    });
                    return 1;
                })
                .then(CommandManager.literal("restart").executes(c -> {
                    if (staged == null) {
                        c.getSource().sendFeedback(() -> Text.literal("No new build waiting. (" + status() + ")").formatted(Formatting.GRAY), false);
                        return 0;
                    }
                    restart(c.getSource().getServer(), "Server restarting to install the new AoT RPG build");
                    return 1;
                })))));
    }

    private static String status() {
        if (latest == null) return "Couldn't reach GitHub; running build " + BuildId.id();
        if (latest.equals(BuildId.id())) return "Up to date (build " + BuildId.id() + ")";
        return "Build " + latest + " is downloaded; it installs on the next restart" + (LOOPING ? " (automatic once the server is empty)" : "");
    }

    private static void check() {
        try {
            Properties cfg = config();
            if (!"true".equalsIgnoreCase(cfg.getProperty("enabled", "true")) || BuildId.id().equals("dev")) return;
            String repo = cfg.getProperty("repo"), branch = cfg.getProperty("branch"), path = cfg.getProperty("path");
            // Read from the newest commit, not the branch (the branch link is cached for minutes).
            String sha = HTTP.send(HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + repo + "/commits/" + branch))
                .header("Accept", "application/vnd.github.sha").header("User-Agent", "aot-rpg-server").timeout(Duration.ofSeconds(15)).build(),
                HttpResponse.BodyHandlers.ofString()).body().trim();
            String ref = sha.matches("[0-9a-f]{40}") ? sha : branch;
            HttpResponse<byte[]> r = HTTP.send(HttpRequest.newBuilder(URI.create("https://raw.githubusercontent.com/" + repo + "/" + ref + "/" + path))
                .header("User-Agent", "aot-rpg-server").timeout(Duration.ofSeconds(120)).build(), HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() != 200 || r.body().length < 10_000) return;
            String id = crc(r.body());
            latest = id;
            Path j = jar();
            if (j == null) return;
            Path u = j.resolveSibling(j.getFileName() + ".update");
            if (id.equals(BuildId.id())) {
                Files.deleteIfExists(u);
                staged = null;
                return;
            }
            if (!Files.exists(u) || !crc(Files.readAllBytes(u)).equals(id)) {
                Path tmp = j.resolveSibling(j.getFileName() + ".part");
                Files.write(tmp, r.body());
                Files.move(tmp, u, StandardCopyOption.REPLACE_EXISTING);
            }
            staged = u;
            hookInstall(j, u);
            MinecraftServer s = server;
            if (s == null) return;
            s.execute(() -> {
                if (!announced.equals(id)) {
                    announced = id;
                    AotRpg.LOG.info("New AoT RPG build {} downloaded (running {}); it installs on the next restart", id, BuildId.id());
                    tell(s, "A new AoT RPG build (" + id + ") is ready. " + (LOOPING
                        ? "The server restarts to install it as soon as nobody's on, or now with /aotrpg update restart."
                        : "Restart the server to install it."));
                }
                if (LOOPING && s.getPlayerManager().getCurrentPlayerCount() == 0) restart(s, "Installing the new AoT RPG build");
            });
        } catch (Exception e) {
            AotRpg.LOG.warn("Update check failed: {}", e.toString());
        }
    }

    /** A player arrived with the newest build while the server is behind: install as soon as it's free. */
    public static void playerAhead(MinecraftServer s) {
        if (timer != null) timer.execute(ServerUpdate::check);
    }

    private static void tell(MinecraftServer s, String msg) {
        s.execute(() -> {
            AotRpg.LOG.info(msg);
            for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
                if (s.getPlayerManager().isOperator(p.getGameProfile())) p.sendMessage(Text.literal("[AoT RPG] ").formatted(Formatting.GOLD).append(Text.literal(msg).formatted(Formatting.GRAY)));
            }
        });
    }

    private static void restart(MinecraftServer s, String why) {
        AotRpg.LOG.info(why);
        for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
            p.networkHandler.disconnect(Text.literal(why).formatted(Formatting.GOLD).append(Text.literal("\nBack in a minute: just join again.").formatted(Formatting.GRAY)));
        }
        if (LOOPING) {
            try {
                Files.writeString(Path.of("update-restart.flag"), "1", StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }
        }
        s.stop(false);
    }

    /**
     * Swap the new jar in once the server has shut down. The looping start script does it itself before
     * starting again; otherwise: straight away on Linux and macOS, and on Windows (where the running jar
     * is locked) by a small PowerShell step that waits for this process to end.
     */
    private static synchronized void hookInstall(Path jar, Path update) {
        if (hooked || LOOPING) return;
        hooked = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!Files.exists(update)) return;
            try {
                if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
                    Files.move(update, jar, StandardCopyOption.REPLACE_EXISTING);
                    return;
                }
                String cmd = "Wait-Process -Id " + ProcessHandle.current().pid() + " -ErrorAction SilentlyContinue; Start-Sleep -Seconds 1; Move-Item -Force -LiteralPath "
                    + quote(update) + " -Destination " + quote(jar);
                new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", cmd)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            } catch (Exception ignored) {
            }
        }, "AoT RPG update install"));
    }

    private static String quote(Path p) {
        return "'" + p.toAbsolutePath().toString().replace("'", "''") + "'";
    }

    static String crc(byte[] b) {
        java.util.zip.CRC32 c = new java.util.zip.CRC32();
        c.update(b);
        return String.format("%08x", c.getValue());
    }
}
