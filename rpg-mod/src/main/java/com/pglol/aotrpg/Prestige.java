package com.pglol.aotrpg;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Prestige: New Game+. At level 100 you can re-enlist: back to level 1 (stats and skills start
 * over), keeping everything you own: gear, Marks, homes, horses, cosmetics, story, Veteran ranks.
 * <p>
 * What you get for it, each time (ten ranks in all):
 * <ul>
 *   <li>a perk of your choosing, permanent and stacking (a little more XP, Marks, gas, health...);</li>
 *   <li>Marks and a new finisher on the spot, and the rank's emblem by your name;</li>
 *   <li>the climb back is quicker (more XP while you're under 100), and your old gear comes back to
 *   hand sooner (it counts you as higher level than you are);</li>
 *   <li>the world takes you seriously: enemies near you are tougher, and pay in better loot.</li>
 * </ul>
 */
public final class Prestige {
    private Prestige() {}

    public static final int MAX = 10;

    public enum Perk {
        SCHOLAR("Scholar", "+10% XP from everything"),
        PROSPEROUS("Prosperous", "+10% Marks earned"),
        TRAINED("Trained", "+1 skill point, every time you start over"),
        DRILLED("Drilled", "+1 stat point, every time you start over"),
        EFFICIENT("Efficient Valves", "Gear bursts cost 8% less gas"),
        HARDY("Hardy", "+4 max health"),
        SALVAGER("Salvager", "+10% Salvage from extraction runs");

        public final String title, desc;

        Perk(String title, String desc) {
            this.title = title;
            this.desc = desc;
        }
    }

    /** Each perk can be taken this many times. */
    public static final int PERK_MAX = 3;

    public static int rank(Profile pr) {
        return pr.prestige;
    }

    public static int perk(Profile pr, Perk k) {
        return pr.prestigePerks == null ? 0 : pr.prestigePerks.getOrDefault(k.name(), 0);
    }

    // ------------------------------------------------------------------ what it does

    /** XP multiplier: the perk, plus the quicker climb while under the cap (25% a rank, up to double). */
    public static double xpMult(Profile pr) {
        double m = 1 + 0.10 * perk(pr, Perk.SCHOLAR);
        if (pr.prestige > 0 && pr.level < Progression.MAX_LEVEL) m += Math.min(1.0, 0.25 * pr.prestige);
        return m;
    }

    public static double marksMult(Profile pr) {
        return 1 + 0.10 * perk(pr, Perk.PROSPEROUS);
    }

    public static double gasMult(Profile pr) {
        return 1 - 0.08 * perk(pr, Perk.EFFICIENT);
    }

    public static double salvageMult(Profile pr) {
        return 1 + 0.10 * perk(pr, Perk.SALVAGER);
    }

    public static int health(Profile pr) {
        return 4 * perk(pr, Perk.HARDY);
    }

    /** The level gear checks against: your old kit comes back to hand sooner (15 levels a rank). */
    public static int gearLevel(Profile pr) {
        return Math.min(Progression.MAX_LEVEL, pr.level + 15 * pr.prestige);
    }

    /** How much tougher enemies are around this spot (the highest prestige of anyone close by). */
    public static int enemyBonus(ServerWorld w, Vec3d at, double r) {
        int best = 0;
        for (ServerPlayerEntity p : w.getPlayers()) {
            if (p.getPos().squaredDistanceTo(at) < r * r) best = Math.max(best, AotRpg.PROFILES.get(p.getUuid()).prestige);
        }
        return best * 4;
    }

    // ------------------------------------------------------------------ re-enlisting

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(Net.PrestigeAction.ID, (payload, ctx) -> {
            String a = payload.action();
            if (a.equals("open")) send(ctx.player(), true);
            else if (a.startsWith("confirm:")) confirm(ctx.player(), a.substring(8));
        });
        CommandRegistrationCallback.EVENT.register((d, reg, env) -> {
            d.register(CommandManager.literal("prestige").executes(c -> {
                send(c.getSource().getPlayerOrThrow(), true);
                return 1;
            }).then(CommandManager.literal("top").executes(c -> {
                top(c.getSource());
                return 1;
            })));
        });
    }

    public static void send(ServerPlayerEntity p, boolean open) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        if (!pr.created) return;
        List<String> perks = new ArrayList<>();
        for (Perk k : Perk.values()) perks.add(k.name() + "|" + k.title + "|" + k.desc + "|" + perk(pr, k) + "|" + PERK_MAX);
        boolean eligible = pr.level >= Progression.MAX_LEVEL && pr.prestige < MAX;
        if (ServerPlayNetworking.canSend(p, Net.PrestigeView.ID)) ServerPlayNetworking.send(p, new Net.PrestigeView(pr.prestige, pr.level, eligible, perks, open));
    }

    private static void confirm(ServerPlayerEntity p, String perkName) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        if (!pr.created || pr.level < Progression.MAX_LEVEL || pr.prestige >= MAX) return;
        if (Classes.inCombat(p) || Extraction.inRun(p.getUuid())) {
            p.sendMessage(Text.literal("Not in the middle of a fight or a run.").formatted(Formatting.RED), true);
            return;
        }
        Perk perk;
        try {
            perk = Perk.valueOf(perkName);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (perk(pr, perk) >= PERK_MAX) return;
        if (pr.prestigePerks == null) pr.prestigePerks = new java.util.HashMap<>();
        pr.prestigePerks.merge(perk.name(), 1, Integer::sum);
        pr.prestige++;
        // Back to the start: level, stats and skills (what you own stays yours).
        pr.level = 1;
        pr.xp = 0;
        pr.stats.clear();
        pr.points = CharacterCreation.START_POINTS + perk(pr, Perk.DRILLED);
        pr.skills.clear();
        pr.skillPoints = Skill.pointsForLevel(1) + perk(pr, Perk.TRAINED);
        AotRpg.PROGRESSION.apply(p, pr);
        p.setHealth(p.getMaxHealth());
        AotRpg.STAMINA.refill(p);
        // Paid on the spot: Marks and a new finisher.
        long pay = 5000L * pr.prestige;
        AotRpg.WALLET.addMarks(p, pay, null);
        AotRpg.COSMETICS.dropFinisher(p, p.getRandom());
        AotRpg.PROFILES.save(p.getUuid());
        AotRpg.sync(p, pr);
        AotRpg.NAMETAGS.update(p, pr);
        var w = p.getServerWorld();
        AotParticles.one(w, AotParticles.fx(AotParticles.FLASH, 0xFFE8A8, 8f), p.getX(), p.getY() + 1, p.getZ(), 0, 0, 0);
        AotParticles.one(w, AotParticles.fx(AotParticles.RING, 0xE0B96A, 6f), p.getX(), p.getY() + 0.05, p.getZ(), 0, 0, 0);
        AotParticles.burst(w, AotParticles.STREAK, 0xF4ECD8, 0.9f, p.getX(), p.getY() + 1, p.getZ(), 30, 0.4, 1.2);
        w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 1f, 0.7f);
        w.playSound(null, p.getBlockPos(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 0.7f, 0.8f);
        Reveal.show(p, "PRESTIGE " + roman(pr.prestige), perk.title + " · +" + String.format(java.util.Locale.ROOT, "%,d", pay) + " Marks · a new finisher", "minecraft:nether_star", 4);
        for (ServerPlayerEntity o : p.getServer().getPlayerManager().getPlayerList()) {
            if (o != p) Notify.toast(o, Text.literal(pr.name + " re-enlisted").formatted(Formatting.GOLD, Formatting.BOLD),
                Text.literal("Prestige " + roman(pr.prestige)), 0xE0B96A, "minecraft:nether_star", null);
        }
        send(p, false);
    }

    public static String roman(int n) {
        String[] r = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return n >= 0 && n < r.length ? r[n] : String.valueOf(n);
    }

    /** The most decorated: prestige, then Veteran rank, then level (online and saved alike). */
    private static void top(net.minecraft.server.command.ServerCommandSource src) {
        List<Profile> all = AotRpg.PROFILES.all();
        all.removeIf(pr -> !pr.created);
        all.sort(java.util.Comparator.comparingInt((Profile pr) -> -pr.prestige).thenComparingInt(pr -> -pr.veteran).thenComparingInt(pr -> -pr.level));
        src.sendFeedback(() -> Text.literal("The most decorated").formatted(Formatting.GOLD, Formatting.BOLD), false);
        for (int i0 = 0; i0 < Math.min(10, all.size()); i0++) {
            final int i = i0;
            Profile pr = all.get(i);
            String line = (i + 1) + ". " + pr.name + "  ·  " + (pr.prestige > 0 ? "Prestige " + roman(pr.prestige) + "  ·  " : "")
                + (pr.veteran > 0 ? "Veteran " + pr.veteran + "  ·  " : "") + "Lv " + pr.level;
            src.sendFeedback(() -> Text.literal(line).formatted(i < 3 ? Formatting.YELLOW : Formatting.GRAY), false);
        }
    }
}
