package com.pglol.aotrpg;

import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Levels, XP, the XP bar, and turning stats and disciplines into attribute bonuses. */
public final class Progression {
    public static final int MAX_LEVEL = 100;
    private final Map<UUID, ServerBossBar> bars = new HashMap<>();

    private static Identifier id(String path) {
        return Identifier.of(AotRpg.MOD_ID, path);
    }

    private static void set(ServerPlayerEntity p, RegistryEntry<EntityAttribute> attr, String key, double value,
                            EntityAttributeModifier.Operation op) {
        EntityAttributeInstance inst = p.getAttributeInstance(attr);
        if (inst == null) return;
        Identifier mid = id(key);
        inst.removeModifier(mid);
        if (value != 0) inst.addPersistentModifier(new EntityAttributeModifier(mid, value, op));
    }

    /** Re-applies every bonus from the profile. Safe to call any time. */
    public void apply(ServerPlayerEntity p, Profile pr) {
        var add = EntityAttributeModifier.Operation.ADD_VALUE;
        var mul = EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE;
        set(p, EntityAttributes.GENERIC_ATTACK_DAMAGE, "stat_strength", 0.5 * pr.total(Stat.STRENGTH), add);
        set(p, EntityAttributes.GENERIC_MOVEMENT_SPEED, "stat_agility", 0.015 * pr.total(Stat.AGILITY), mul);
        set(p, EntityAttributes.GENERIC_MAX_HEALTH, "stat_endurance", 2.0 * pr.total(Stat.ENDURANCE), add);
        // Every level makes you a little tougher too (and hits harder: see Combat.power).
        set(p, EntityAttributes.GENERIC_MAX_HEALTH, "level_health", Math.max(0, pr.level - 1), add);
        set(p, EntityAttributes.GENERIC_MAX_HEALTH, "prestige_health", Prestige.health(pr), add);
        set(p, EntityAttributes.GENERIC_ARMOR, "stat_resolve", pr.total(Stat.RESOLVE), add);

        Discipline d = pr.discipline;
        set(p, EntityAttributes.GENERIC_MOVEMENT_SPEED, "disc_speed",
            d == Discipline.SCOUT ? 0.08 : d == Discipline.MARKSMAN ? 0.05 : 0, mul);
        set(p, EntityAttributes.GENERIC_JUMP_STRENGTH, "disc_jump", d == Discipline.SCOUT ? 0.10 : 0, mul);
        set(p, EntityAttributes.GENERIC_SAFE_FALL_DISTANCE, "disc_fall", d == Discipline.SCOUT ? 3 : 0, add);
        set(p, EntityAttributes.GENERIC_ATTACK_DAMAGE, "disc_damage", d == Discipline.VANGUARD ? 2 : 0, add);
        set(p, EntityAttributes.GENERIC_ATTACK_SPEED, "disc_attack_speed", d == Discipline.VANGUARD ? 0.10 : 0, mul);
        set(p, EntityAttributes.GENERIC_MAX_HEALTH, "disc_health",
            d == Discipline.GUARDIAN ? 6 : d == Discipline.MEDIC ? 4 : 0, add);
        set(p, EntityAttributes.GENERIC_ARMOR, "disc_armor", d == Discipline.GUARDIAN ? 2 : 0, add);
        set(p, EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, "disc_knockback", d == Discipline.GUARDIAN ? 0.2 : 0, add);
        for (Skill sk : Skill.values()) {
            if (sk.attribute != null) set(p, sk.attribute, "skill_" + sk.name().toLowerCase(), pr.has(sk) ? sk.amount : 0, sk.op());
        }
        set(p, EntityAttributes.GENERIC_JUMP_STRENGTH, "skill_wings_jump", pr.has(Skill.WINGS_OF_FREEDOM) ? 0.15 : 0, mul);
        set(p, EntityAttributes.GENERIC_ARMOR_TOUGHNESS, "skill_battle_toughness", pr.has(Skill.BATTLE_HARDENED) ? 2 : 0, add);
        set(p, EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, "skill_unbreakable_kb", pr.has(Skill.UNBREAKABLE) ? 0.25 : 0, add);
        if (p.getHealth() > p.getMaxHealth()) p.setHealth(p.getMaxHealth());
    }

    private final Map<UUID, Integer> hungerTier = new HashMap<>();

    /** Hungry players hit softer and move slower. Called once a second. */
    public void hunger(ServerPlayerEntity p) {
        if (p.isCreative() || p.isSpectator()) return;
        int food = p.getHungerManager().getFoodLevel();
        int tier = food <= 2 ? 2 : food <= 6 ? 1 : 0;
        Integer old = hungerTier.put(p.getUuid(), tier);
        if (old != null && old == tier) return;
        var total = EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
        set(p, EntityAttributes.GENERIC_MOVEMENT_SPEED, "hunger_speed", tier == 2 ? -0.20 : tier == 1 ? -0.10 : 0, total);
        set(p, EntityAttributes.GENERIC_ATTACK_DAMAGE, "hunger_damage", tier == 2 ? -0.30 : tier == 1 ? -0.15 : 0, total);
        if (old != null && tier > old) {
            p.sendMessage(Text.literal(tier == 2 ? "You are starving. You feel weak and slow." : "You are hungry. You feel a little weaker.")
                .formatted(Formatting.RED), true);
        }
    }

    public void forgetHunger(ServerPlayerEntity p) {
        hungerTier.remove(p.getUuid());
    }

    /**
     * XP for beating something of this level: more for tougher foes, far less for ones well
     * beneath you (no farming the shallows at level 60).
     */
    public static long forLevel(int foe, int you, long base) {
        double k = 1 + 0.25 * Math.max(0, foe);
        int gap = you - foe;
        if (gap > 8) k *= Math.max(0.2, 1 - (gap - 8) * 0.06);
        else if (gap < -3) k *= Math.min(1.6, 1 + (-gap - 3) * 0.05);
        return Math.max(1, Math.round(base * k));
    }

    /** XP with a word of what for (over the hotbar). */
    public void award(ServerPlayerEntity p, long xp, String label) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        if (!pr.created || xp <= 0) return;
        // Titan Hunt week: titan kills pay double.
        String lab = label == null ? "" : label.toLowerCase(java.util.Locale.ROOT);
        if (Weekly.now() == Weekly.Op.TITAN_HUNT && (lab.contains("titan") || lab.contains("nape") || lab.contains("ogre") || lab.contains("apex"))) xp *= 2;
        if (!Pops.send(p, "xp", xp, label)) p.sendMessage(Text.literal("+" + xp + " XP  ").formatted(Formatting.GOLD, Formatting.BOLD).append(Text.literal(label).formatted(Formatting.GRAY)), true);
        addXp(p, pr, xp);
    }

    /** XP for each Veteran rank past the cap. */
    public static long veteranNeed() {
        return Profile.xpForNext(MAX_LEVEL);
    }

    public void addXp(ServerPlayerEntity p, Profile pr, long amount) {
        if (!pr.created) return;
        if (pr.level >= MAX_LEVEL) {
            // At the cap the XP still counts: Veteran ranks, each paying out (and a new finisher every fifth).
            pr.vetXp += Math.round(amount * (1 + Roles.bonus(pr)) * Prestige.xpMult(pr) * Weekly.xp(false));
            while (pr.vetXp >= veteranNeed()) {
                pr.vetXp -= veteranNeed();
                pr.veteran++;
                long pay = 1500 + 100L * pr.veteran;
                AotRpg.WALLET.addMarks(p, pay, null);
                if (pr.veteran % 5 == 0) AotRpg.COSMETICS.dropFinisher(p, p.getRandom());
                var w = p.getServerWorld();
                AotParticles.one(w, AotParticles.fx(AotParticles.RING, 0xE0B96A, 3.5f), p.getX(), p.getY() + 0.05, p.getZ(), 0, 0, 0);
                w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 1f, 0.5f);
                Reveal.show(p, "Veteran " + pr.veteran, "+" + pay + " Marks" + (pr.veteran % 5 == 0 ? " · a new finisher" : "") + " · " + (5 - pr.veteran % 5) % 5 + " ranks to the next finisher",
                    "levelup", pr.veteran % 5 == 0 ? 4 : 3);
            }
            AotRpg.PROFILES.save(p.getUuid());
            AotRpg.sync(p, pr);
            return;
        }
        amount = Math.round(amount * (1 + Roles.bonus(pr)) * Prestige.xpMult(pr) * Weekly.xp(false));
        pr.xp += amount;
        boolean up = false;
        while (pr.level < MAX_LEVEL && pr.xp >= Profile.xpForNext(pr.level)) {
            pr.xp -= Profile.xpForNext(pr.level);
            pr.level++;
            pr.points++;
            pr.skillPoints += Skill.pointsForLevel(pr.level) - Skill.pointsForLevel(pr.level - 1);
            up = true;
        }
        if (pr.level >= MAX_LEVEL) pr.xp = 0;
        if (up) {
            apply(p, pr);
            AotRpg.NAMETAGS.update(p, pr);
            // Felt, not just read: back to full, a burst of gold, a chord.
            p.setHealth(p.getMaxHealth());
            AotRpg.STAMINA.refill(p);
            var w = p.getServerWorld();
            // A rush of air and light up off you (seen by those around), a low resonant note.
            AotParticles.one(w, AotParticles.fx(AotParticles.RING, 0xE8D8B0, 3.5f), p.getX(), p.getY() + 0.05, p.getZ(), 0, 0, 0);
            AotParticles.burst(w, AotParticles.STREAK, 0xF4ECD8, 0.7f, p.getX(), p.getY() + 1, p.getZ(), 14, 0.35, 0.7);
            w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 1f, 0.6f);
            Reveal.show(p, "Level " + pr.level, "+2% damage · +1 health · +1 stat point" + (pr.level % 10 == 0 ? " · +2 skill points" : pr.level % 2 == 0 ? " · +1 skill point" : "")
                + (AotRpg.hasClient(p) ? "" : "  (/character)"), "levelup", pr.level % 10 == 0 ? 4 : 3);
        }
        AotRpg.sync(p, pr);
    }

    /** The XP boss bar, for players without the mod's HUD. */
    public void updateBar(ServerPlayerEntity p, Profile pr) {
        if (!pr.created) return;
        if (AotRpg.hasClient(p)) {
            removeBar(p);
            return;
        }
        ServerBossBar bar = bars.computeIfAbsent(p.getUuid(),
            u -> new ServerBossBar(Text.empty(), BossBar.Color.YELLOW, BossBar.Style.NOTCHED_10));
        if (!bar.getPlayers().contains(p)) bar.addPlayer(p);
        long need = Profile.xpForNext(pr.level);
        bar.setName(Text.literal("Lv " + pr.level + "  ").formatted(Formatting.GOLD, Formatting.BOLD)
            .append(Text.literal(pr.name + " the " + pr.discipline.title).formatted(Formatting.WHITE))
            .append(Text.literal(pr.level >= MAX_LEVEL ? "   Veteran " + pr.veteran + "  " + pr.vetXp + " / " + veteranNeed() : "   " + pr.xp + " / " + need + " XP").formatted(Formatting.GRAY)));
        bar.setPercent(pr.level >= MAX_LEVEL ? 1f : Math.min(1f, (float) pr.xp / need));
        bar.setColor(switch (pr.discipline) {
            case SCOUT -> BossBar.Color.GREEN;
            case VANGUARD -> BossBar.Color.RED;
            case GUARDIAN -> BossBar.Color.BLUE;
            case MARKSMAN -> BossBar.Color.YELLOW;
            case MEDIC -> BossBar.Color.PINK;
        });
    }

    public void removeBar(ServerPlayerEntity p) {
        ServerBossBar bar = bars.remove(p.getUuid());
        if (bar != null) bar.clearPlayers();
    }
}
