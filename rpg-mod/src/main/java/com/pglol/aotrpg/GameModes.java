package com.pglol.aotrpg;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.WorldSavePath;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Game modes a character can switch between from the pause menu once unlocked by the story.
 * The list and each mode's required chapter live in <world>/aot_rpg/modes.json, so new modes
 * can be added as the story grows; "soon" modes show but can't be chosen yet.
 */
public final class GameModes {
    public static final class Mode {
        public String id;
        public String title;
        public String desc;
        public int unlockChapter;
        public boolean soon;

        Mode(String id, String title, String desc, int unlockChapter, boolean soon) {
            this.id = id;
            this.title = title;
            this.desc = desc;
            this.unlockChapter = unlockChapter;
            this.soon = soon;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private List<Mode> modes = defaults();

    private static final String OPEN_TITLE = "Open World",
        OPEN_DESC = "Paradis is yours to roam: towns, titans, homes, trade. Your gear is kept when you fall.",
        EXTRACT_DESC = "Gear up in the staging hall, drop into titan country with your squad, loot what you can and reach a flare to get out. Fall and it's all left behind.";

    private static List<Mode> defaults() {
        List<Mode> m = new ArrayList<>();
        m.add(new Mode("story", OPEN_TITLE, OPEN_DESC, 0, false));
        m.add(new Mode("extraction", "Extraction", EXTRACT_DESC, 0, false));
        return m;
    }

    /** Who may play Extraction (by UUID): it's closed except to those an admin lets in (operators always). */
    private java.util.Set<String> extraction = new java.util.LinkedHashSet<>();
    private Path allowFile;

    public boolean extractionAllowed(ServerPlayerEntity p) {
        return p.hasPermissionLevel(2) || extraction.contains(p.getUuidAsString());
    }

    public java.util.Set<String> extractionList() {
        return extraction;
    }

    public void allowExtraction(java.util.UUID id, boolean on) {
        if (on) extraction.add(id.toString());
        else extraction.remove(id.toString());
        try {
            Files.createDirectories(allowFile.getParent());
            Files.writeString(allowFile, GSON.toJson(extraction), StandardCharsets.UTF_8);
        } catch (Exception e) {
            AotRpg.LOG.error("Could not save extraction_allow.json", e);
        }
    }

    /** Open to this player? Extraction needs the allowlist; the open world is everyone's. */
    public boolean open(ServerPlayerEntity p, Mode m) {
        if (DeathCare.EXTRACTION.equals(m.id)) return extractionAllowed(p);
        return unlocked(AotRpg.PROFILES.get(p.getUuid()), m);
    }

    public void open(MinecraftServer server) {
        Path f = server.getSavePath(WorldSavePath.ROOT).resolve("aot_rpg").resolve("modes.json");
        try {
            if (Files.exists(f)) {
                List<Mode> read = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), new TypeToken<List<Mode>>() { }.getType());
                if (read != null && !read.isEmpty()) modes = read;
                // Two modes only now: the open world and Extraction.
                modes.removeIf(m -> !m.id.equals("story") && !m.id.equals("extraction"));
                if (find("extraction") == null) modes.add(defaults().get(1));
                // The main mode is the open world now, and Extraction is open to everyone.
                for (Mode m : modes) {
                    if (m.id.equals("story")) {
                        m.title = OPEN_TITLE;
                        m.desc = OPEN_DESC;
                    } else if (m.id.equals("extraction")) {
                        m.desc = EXTRACT_DESC;
                        m.unlockChapter = 0;
                        m.soon = false;
                    }
                }
                Files.writeString(f, GSON.toJson(modes), StandardCharsets.UTF_8);
            } else {
                Files.createDirectories(f.getParent());
                Files.writeString(f, GSON.toJson(modes), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            AotRpg.LOG.error("Could not read modes.json", e);
        }
        allowFile = server.getSavePath(WorldSavePath.ROOT).resolve("aot_rpg").resolve("extraction_allow.json");
        try {
            if (Files.exists(allowFile)) {
                java.util.Set<String> read = GSON.fromJson(Files.readString(allowFile, StandardCharsets.UTF_8), new TypeToken<java.util.LinkedHashSet<String>>() { }.getType());
                if (read != null) extraction = read;
            }
        } catch (Exception e) {
            AotRpg.LOG.error("Could not read extraction_allow.json", e);
        }
    }

    /**
     * /aotrpg extraction allow|deny &lt;player&gt; and /aotrpg extraction list. Someone taken off it
     * mid-session is walked back to the open world.
     */
    public static void register() {
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((d, reg, env) -> d.register(
            net.minecraft.server.command.CommandManager.literal("aotrpg").requires(s -> s.hasPermissionLevel(2))
                .then(net.minecraft.server.command.CommandManager.literal("extraction")
                    .then(net.minecraft.server.command.CommandManager.literal("allow").then(net.minecraft.server.command.CommandManager
                        .argument("player", net.minecraft.command.argument.GameProfileArgumentType.gameProfile()).executes(c -> {
                            for (var gp : net.minecraft.command.argument.GameProfileArgumentType.getProfileArgument(c, "player")) {
                                AotRpg.MODES.allowExtraction(gp.getId(), true);
                                c.getSource().sendFeedback(() -> Text.literal(gp.getName() + " can play Extraction.").formatted(Formatting.GREEN), true);
                                ServerPlayerEntity on = c.getSource().getServer().getPlayerManager().getPlayer(gp.getId());
                                if (on != null) {
                                    Notify.toast(on, Text.literal("Extraction unlocked").formatted(Formatting.GOLD, Formatting.BOLD),
                                        Text.literal("You're in the closed beta: pick it from Game Mode"), 0xE04A3A, "minecraft:firework_rocket", null);
                                    AotRpg.MODES.send(on, false);
                                }
                            }
                            return 1;
                        })))
                    .then(net.minecraft.server.command.CommandManager.literal("deny").then(net.minecraft.server.command.CommandManager
                        .argument("player", net.minecraft.command.argument.GameProfileArgumentType.gameProfile()).executes(c -> {
                            for (var gp : net.minecraft.command.argument.GameProfileArgumentType.getProfileArgument(c, "player")) {
                                AotRpg.MODES.allowExtraction(gp.getId(), false);
                                c.getSource().sendFeedback(() -> Text.literal(gp.getName() + " can no longer play Extraction.").formatted(Formatting.YELLOW), true);
                                ServerPlayerEntity on = c.getSource().getServer().getPlayerManager().getPlayer(gp.getId());
                                if (on != null && DeathCare.EXTRACTION.equals(AotRpg.PROFILES.get(on.getUuid()).mode) && !Extraction.inRun(on.getUuid())) {
                                    AotRpg.MODES.choose(on, DeathCare.STORY);
                                }
                            }
                            return 1;
                        })))
                    .then(net.minecraft.server.command.CommandManager.literal("list").executes(c -> {
                        var s = c.getSource().getServer();
                        List<String> names = new ArrayList<>();
                        for (String id : AotRpg.MODES.extractionList()) {
                            var prof = s.getUserCache() == null ? null : s.getUserCache().getByUuid(java.util.UUID.fromString(id)).orElse(null);
                            names.add(prof != null ? prof.getName() : id);
                        }
                        c.getSource().sendFeedback(() -> Text.literal("Extraction allowlist (" + names.size() + "): " + String.join(", ", names)), false);
                        return 1;
                    })))));
        // Someone left in Extraction who isn't on the list (any more) goes back to the open world.
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((h, sender, s) -> AotRpg.SCHEDULER.later(40, () -> {
            ServerPlayerEntity p = h.player;
            if (p.isDisconnected()) return;
            Profile pr = AotRpg.PROFILES.get(p.getUuid());
            if (pr.created && DeathCare.EXTRACTION.equals(pr.mode) && !AotRpg.MODES.extractionAllowed(p) && !Extraction.inRun(p.getUuid())) {
                AotRpg.MODES.choose(p, DeathCare.STORY);
            }
        }));
    }

    private Mode find(String id) {
        for (Mode m : modes) if (m.id.equals(id)) return m;
        return null;
    }

    public boolean unlocked(Profile pr, Mode m) {
        // The story is off, so modes no longer wait on story chapters: everything ready is open.
        return !m.soon && (!Story.STORY_ENABLED || pr.chapter >= m.unlockChapter || pr.unlockedModes.contains(m.id));
    }

    public void choose(ServerPlayerEntity p, String id) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        Mode m = find(id);
        if (m == null || !pr.created) return;
        if (!open(p, m)) {
            p.sendMessage(Text.literal(DeathCare.EXTRACTION.equals(m.id) ? "Extraction is closed: an admin has to let you in." : "Locked for now.").formatted(Formatting.RED), true);
            return;
        }
        if (p.getRecentDamageSource() != null) {
            p.sendMessage(Text.literal("You can't change mode in the middle of a fight.").formatted(Formatting.RED), true);
            return;
        }
        if (Extraction.inRun(p.getUuid())) {
            p.sendMessage(Text.literal("Get out of the zone first.").formatted(Formatting.RED), true);
            return;
        }
        pr.mode = m.id;
        AotRpg.PROFILES.save(p.getUuid());
        Notify.toast(p, Text.literal(m.title).formatted(Formatting.GOLD, Formatting.BOLD), null, 0xE0B96A, "minecraft:compass", null);
        send(p, false);
        // Extraction lives in its own staging hall; leaving it puts you back where you were.
        if (DeathCare.EXTRACTION.equals(m.id) && !Extraction.inLobby(p)) AotRpg.EXTRACT.toLobby(p);
        else if (!DeathCare.EXTRACTION.equals(m.id) && Extraction.inLobby(p)) AotRpg.EXTRACT.toOpenWorld(p);
    }

    public void unlock(ServerPlayerEntity p, String id) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        pr.unlockedModes.add(id);
        AotRpg.PROFILES.save(p.getUuid());
    }

    public void send(ServerPlayerEntity p, boolean open) {
        if (!ServerPlayNetworking.canSend(p, Net.ModeView.ID)) return;
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        List<Net.ModeEntry> list = new ArrayList<>();
        for (Mode m : modes) {
            boolean ok = open(p, m);
            String req = ok ? "" : DeathCare.EXTRACTION.equals(m.id) ? "Closed beta: by invitation" : "Locked";
            list.add(new Net.ModeEntry(m.id, m.title, m.desc, ok, m.id.equals(pr.mode), req));
        }
        ServerPlayNetworking.send(p, new Net.ModeView(list, pr.chapter, open));
    }
}
