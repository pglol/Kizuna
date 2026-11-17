package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.VillagerProfession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mentors: veterans in the towns who'll teach you, once you've shown them something. Each one sets
 * three trials (titans felled, parries landed, lives saved...) and then teaches a technique of
 * their own, worn on the fourth ability key (C). Two techniques are secret: one of them comes from a
 * man who only shows himself at night to those Levi has trained; the other no one can teach.
 * Operators can grant any technique (/technique).
 */
public final class Mentors {
    private Mentors() {}

    public record Step(String counter, int need, String label) { }

    public record Tech(String id, String name, String desc, int cooldown, boolean secret) { }

    public record Mentor(String id, String name, String title, String quote, String tech, List<Step> steps) { }

    public static final List<Tech> TECHS = List.of(
        new Tech("spin", "Spinning Slash", "A whirling cut in the air: everything within 4 blocks, hard.", 14, false),
        new Tech("instinct", "Hunter's Instinct", "Every foe within 80 blocks lights up for your squad.", 30, false),
        new Tech("bolt", "Thunder Bolt", "A spear of fire where you look, 40 blocks out.", 25, false),
        new Tech("advance", "Advance!", "Your squad surges forward: faster, tougher, and nothing can grab them.", 45, false),
        new Tech("aid", "Emergency Aid", "Get a downed squadmate up from 16 blocks away, or patch the worst hurt.", 60, false),
        new Tech("awakening", "Ackerman Awakening", "Eight seconds as something more than human.", 90, true),
        new Tech("coordinate", "The Coordinate", "A scream: every titan near you stops dead.", 120, true));

    public static final List<Mentor> MENTORS = List.of(
        new Mentor("levi", "Captain Levi", "Infantry mentor", "Clean your blades. Then show me you can use them.", "spin", List.of(
            new Step("titans", 15, "Fell 15 titans"), new Step("parries", 5, "Land 5 perfect parries"), new Step("slams", 10, "Hit with 10 air slams"))),
        new Mentor("sasha", "Sasha Blouse", "Recon mentor", "You can smell 'em coming, if you're hungry enough.", "instinct", List.of(
            new Step("troops", 5, "Cut down 5 Marleyan troops"), new Step("titans", 10, "Fell 10 titans"), new Step("extracts", 2, "Extract twice"))),
        new Mentor("hange", "Section Commander Hange", "Engineer mentor", "Oh, you want to learn? Wonderful. Bring me data.", "bolt", List.of(
            new Step("crafts", 10, "Craft 10 things at a workbench"), new Step("trees", 10, "Fell 10 trees"), new Step("titans", 20, "Fell 20 titans"))),
        new Mentor("erwin", "Commander Erwin", "Tank mentor", "Dedicate your heart. Hold the line so others live.", "advance", List.of(
            new Step("revives", 3, "Bring 3 squadmates back"), new Step("extracts", 3, "Extract 3 times"), new Step("titans", 25, "Fell 25 titans"))),
        new Mentor("krista", "Krista Lenz", "Medic mentor", "Nobody gets left behind. Not while I'm here.", "aid", List.of(
            new Step("revives", 5, "Bring 5 squadmates back"), new Step("parries", 3, "Land 3 perfect parries"), new Step("extracts", 1, "Extract once"))),
        new Mentor("kenny", "Kenny Ackerman", "Secret", "You've got that look. Levi's look. Let's see if it's in the blood.", "awakening", List.of()));

    public static Tech tech(String id) {
        for (Tech t : TECHS) if (t.id().equals(id)) return t;
        return null;
    }

    public static Mentor mentor(String id) {
        for (Mentor m : MENTORS) if (m.id().equals(id)) return m;
        return null;
    }

    private static Profile pr(ServerPlayerEntity p) {
        return AotRpg.PROFILES.get(p.getUuid());
    }

    // ------------------------------------------------------------------ trials

    /** Something a mentor counts happened (titans, troops, revives, parries, slams, crafts, trees, extracts). */
    public static void count(ServerPlayerEntity p, String counter, int n) {
        if (p == null || n <= 0) return;
        Profile pr = pr(p);
        if (!pr.created) return;
        pr.mentorCounts.merge(counter, n, Integer::sum);
    }

    /** How far into this mentor's trials: the step you're on (steps.size() when all are done). */
    private static int step(Profile pr, Mentor m) {
        int i = 0;
        for (Step s : m.steps()) {
            if (pr.mentorCounts.getOrDefault(m.id() + ":" + i, 0) < s.need()) return i;
            i++;
        }
        return i;
    }

    /** Progress counts against a step from the moment it was set (kills before don't count). */
    private static int have(Profile pr, Mentor m, int i) {
        Step s = m.steps().get(i);
        Integer base = pr.mentorCounts.get(m.id() + ":base" + i);
        int now = pr.mentorCounts.getOrDefault(s.counter(), 0);
        int got = base == null ? 0 : now - base;
        return Math.max(pr.mentorCounts.getOrDefault(m.id() + ":" + i, 0), Math.min(s.need(), got));
    }

    // ------------------------------------------------------------------ in town

    public static final String TAG = "aot_mentor";

    public static void register() {
        net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register((player, world, hand, e, hit) -> {
            if (world.isClient || !(player instanceof ServerPlayerEntity sp) || hand != net.minecraft.util.Hand.MAIN_HAND) return net.minecraft.util.ActionResult.PASS;
            for (String tag : e.getCommandTags()) {
                if (!tag.startsWith(TAG + ":")) continue;
                Mentor m = mentor(tag.substring(TAG.length() + 1));
                if (m != null) talk(sp, m, true);
                return net.minecraft.util.ActionResult.SUCCESS;
            }
            return net.minecraft.util.ActionResult.PASS;
        });
        ServerPlayNetworking.registerGlobalReceiver(Net.MentorAction.ID, (payload, ctx) -> action(ctx.player(), payload.action(), payload.mentor()));
        ServerPlayNetworking.registerGlobalReceiver(Net.MomentAction.ID, (payload, ctx) -> {
            if (payload.action().equals("play")) AotRpg.STORY.playMoment(ctx.player(), payload.id());
            AotRpg.STORY.sendMoments(ctx.player(), payload.action().equals("open"));
        });
    }

    private static UUID id(Net.Area t, String mentor) {
        return UUID.nameUUIDFromBytes(("aot_mentor:" + t.id() + ":" + mentor).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Each town hosts one mentor (round the towns in turn); Kenny walks the back streets at night. */
    public static void tick(MinecraftServer server, int ticks) {
        if (ticks % 40 != 23) return;
        ServerWorld w = server.getOverworld();
        List<Net.Area> towns = new ArrayList<>();
        for (Net.Area a : AotRpg.PLACES.areas()) if (a.look().equals("town")) towns.add(a);
        List<ServerPlayerEntity> players = w.getPlayers();
        boolean night = w.isNight();
        for (int i = 0; i < towns.size(); i++) {
            Net.Area t = towns.get(i);
            boolean near = false;
            for (ServerPlayerEntity p : players) if ((p.getX() - t.x()) * (p.getX() - t.x()) + (p.getZ() - t.z()) * (p.getZ() - t.z()) < 100 * 100) near = true;
            if (!near || !w.isChunkLoaded(t.x() >> 4, t.z() >> 4)) continue;
            Mentor m = MENTORS.get(i % (MENTORS.size() - 1));
            keep(w, t, m, 10, 26);
            // Kenny: only at night, only in some towns.
            Mentor kenny = mentor("kenny");
            UUID kid = id(t, kenny.id());
            if (night && Math.floorMod(t.id().hashCode(), 3) == 0) keep(w, t, kenny, 30, 50);
            else if (w.getEntity(kid) != null) w.getEntity(kid).discard();
        }
    }

    private static void keep(ServerWorld w, Net.Area t, Mentor m, int minR, int maxR) {
        Stalls.Spot spot = Stalls.place(w, "mentor:" + t.id() + ":" + m.id(), t, "MENTOR", minR, maxR, (t.id() + m.id()).hashCode());
        if (spot == null) return;
        BlockPos s = spot.pos();
        float yaw = spot.facing().asRotation();
        UUID id = id(t, m.id());
        Entity ent = w.getEntity(id);
        if (ent instanceof VillagerEntity v && v.isAlive()) {
            if (v.squaredDistanceTo(s.getX() + 0.5, s.getY(), s.getZ() + 0.5) > 0.5) v.refreshPositionAndAngles(s.getX() + 0.5, s.getY(), s.getZ() + 0.5, yaw, 0);
            return;
        }
        if (!w.isChunkLoaded(s.getX() >> 4, s.getZ() >> 4)) return;
        VillagerEntity v = EntityType.VILLAGER.create(w);
        if (v == null) return;
        v.setUuid(id);
        v.refreshPositionAndAngles(s.getX() + 0.5, s.getY(), s.getZ() + 0.5, yaw, 0);
        v.initialize(w, w.getLocalDifficulty(s), SpawnReason.EVENT, null);
        v.setVillagerData(v.getVillagerData().withProfession(VillagerProfession.NONE));
        v.setAiDisabled(true);
        v.setInvulnerable(true);
        v.setSilent(true);
        v.addCommandTag(TAG);
        v.addCommandTag(TAG + ":" + m.id());
        v.setCustomName(Text.literal(m.name()).styled(x -> x.withColor(0xE8C47A)));
        v.setCustomNameVisible(!m.id().equals("kenny"));
        w.spawnEntity(v);
    }

    // ------------------------------------------------------------------ talking

    public static void talk(ServerPlayerEntity p, Mentor m, boolean open) {
        Profile pr = pr(p);
        // Kenny only bothers with those Levi has trained.
        if (m.id().equals("kenny") && !pr.techniques.contains("spin")) {
            p.sendMessage(Text.literal("\"Get lost, kid.\"").formatted(Formatting.DARK_GRAY, Formatting.ITALIC), true);
            return;
        }
        if (m.id().equals("kenny") && !pr.techniques.contains("awakening")) {
            teach(p, m);
        }
        int at = step(pr, m);
        // A step being started sets its baseline: from here on it counts.
        if (at < m.steps().size() && !pr.mentorCounts.containsKey(m.id() + ":base" + at)) {
            pr.mentorCounts.put(m.id() + ":base" + at, pr.mentorCounts.getOrDefault(m.steps().get(at).counter(), 0));
            AotRpg.PROFILES.save(p.getUuid());
        }
        // A step finished since last time: mark it and set up the next.
        while (at < m.steps().size() && have(pr, m, at) >= m.steps().get(at).need()) {
            pr.mentorCounts.put(m.id() + ":" + at, m.steps().get(at).need());
            at++;
            if (at < m.steps().size()) pr.mentorCounts.put(m.id() + ":base" + at, pr.mentorCounts.getOrDefault(m.steps().get(at).counter(), 0));
            AotRpg.PROFILES.save(p.getUuid());
        }
        List<String> labels = new ArrayList<>();
        List<Integer> haves = new ArrayList<>(), needs = new ArrayList<>();
        for (int i = 0; i < m.steps().size(); i++) {
            Step s = m.steps().get(i);
            labels.add(s.label());
            haves.add(i < at ? s.need() : i == at ? have(pr, m, i) : 0);
            needs.add(s.need());
        }
        Tech t = tech(m.tech());
        int state = pr.techniques.contains(t.id()) ? 2 : at >= m.steps().size() ? 1 : 0;
        if (ServerPlayNetworking.canSend(p, Net.MentorView.ID)) {
            ServerPlayNetworking.send(p, new Net.MentorView(m.id(), m.name(), m.title(), m.quote(), t.name(), t.desc(), t.cooldown(), labels, haves, needs,
                state, t.id().equals(pr.technique), open));
        }
    }

    private static void action(ServerPlayerEntity p, String action, String mentorId) {
        Mentor m = mentor(mentorId);
        if (m == null) return;
        Profile pr = pr(p);
        Tech t = tech(m.tech());
        switch (action) {
            case "learn" -> {
                if (step(pr, m) >= m.steps().size() && !pr.techniques.contains(t.id())) teach(p, m);
            }
            case "equip" -> {
                if (pr.techniques.contains(t.id())) {
                    pr.technique = t.id();
                    AotRpg.PROFILES.save(p.getUuid());
                    Notify.toast(p, Text.literal(t.name()).formatted(Formatting.GOLD), Text.literal("Ready on your technique key"), 0xE8C47A, "minecraft:nether_star", "tech");
                }
            }
            default -> { }
        }
        talk(p, m, false);
    }

    private static void teach(ServerPlayerEntity p, Mentor m) {
        Profile pr = pr(p);
        Tech t = tech(m.tech());
        pr.techniques.add(t.id());
        if (pr.technique == null || pr.technique.isEmpty()) pr.technique = t.id();
        AotRpg.PROFILES.save(p.getUuid());
        Reveal.show(p, t.secret() ? "SECRET TECHNIQUE" : "TECHNIQUE LEARNED", t.name() + "  ·  " + m.name(), "minecraft:nether_star", t.secret() ? 5 : 3);
        p.playSoundToPlayer(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 0.8f, 1.1f);
    }

    // ------------------------------------------------------------------ using a technique

    private static final Map<UUID, Long> ready = new HashMap<>(), awake = new HashMap<>(), advance = new HashMap<>();

    /** Under Erwin's Advance: no titan can get a grip. */
    public static boolean unshakable(ServerPlayerEntity p) {
        return System.currentTimeMillis() < advance.getOrDefault(p.getUuid(), 0L);
    }

    public static boolean awakened(ServerPlayerEntity p) {
        return System.currentTimeMillis() < awake.getOrDefault(p.getUuid(), 0L);
    }

    public static void use(ServerPlayerEntity p) {
        Profile pr = pr(p);
        Tech t = pr.technique == null ? null : tech(pr.technique);
        if (t == null || !pr.techniques.contains(t.id())) {
            p.sendMessage(Text.literal("No technique yet: find a mentor in town").formatted(Formatting.GRAY), true);
            return;
        }
        long now = System.currentTimeMillis();
        long r = ready.getOrDefault(p.getUuid(), 0L);
        if (now < r) {
            p.sendMessage(Text.literal(t.name() + " · " + String.format(java.util.Locale.ROOT, "%.0fs", (r - now) / 1000.0)).formatted(Formatting.GRAY), true);
            return;
        }
        if (!fire(p, t)) return;
        ready.put(p.getUuid(), now + t.cooldown() * 1000L);
        Mentors.callout(p, t.name().toUpperCase(java.util.Locale.ROOT));
    }

    private static void callout(ServerPlayerEntity p, String s) {
        p.sendMessage(Text.literal(s).formatted(Formatting.GOLD, Formatting.BOLD), true);
    }

    private static boolean fire(ServerPlayerEntity p, Tech t) {
        ServerWorld w = p.getServerWorld();
        long now = System.currentTimeMillis();
        float lv = pr(p).level;
        switch (t.id()) {
            case "spin" -> {
                // A whirl of steel: everything around you, hard. Best in the air.
                float dmg = (9 + lv * 0.18f) * (p.isOnGround() ? 0.8f : 1.2f);
                for (LivingEntity e : Classes.foes(p, 4.5)) {
                    e.timeUntilRegen = 0;
                    e.damage(w.getDamageSources().playerAttack(p), dmg);
                }
                for (int i = 0; i < 24; i++) {
                    double a = i * Math.PI / 12;
                    w.spawnParticles(ParticleTypes.SWEEP_ATTACK, p.getX() + Math.cos(a) * 2.5, p.getY() + 1, p.getZ() + Math.sin(a) * 2.5, 1, 0, 0, 0, 0);
                }
                w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 1.2f, 0.7f);
                w.playSound(null, p.getBlockPos(), SoundEvents.ITEM_TRIDENT_RIPTIDE_3.value(), SoundCategory.PLAYERS, 0.8f, 1.3f);
                return true;
            }
            case "instinct" -> {
                for (Entity e : w.getOtherEntities(p, p.getBoundingBox().expand(80), e -> e instanceof LivingEntity le && le.isAlive()
                    && (AotRpg.isTitan(e) || Troops.is(e) || e instanceof net.minecraft.entity.mob.HostileEntity || e instanceof ServerPlayerEntity o && !AotRpg.PARTIES.same(o.getUuid(), p.getUuid())))) {
                    ((LivingEntity) e).addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 160, 0, false, false));
                }
                w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_FOX_SNIFF, SoundCategory.PLAYERS, 1f, 0.8f);
                for (ServerPlayerEntity a : Classes.squad(p, 80)) a.sendMessage(Text.literal("Hunter's Instinct · every foe lit").formatted(Formatting.GOLD), true);
                return true;
            }
            case "bolt" -> {
                var hit = p.raycast(40, 1f, false);
                Vec3d at = hit.getType() == HitResult.Type.MISS ? p.getEyePos().add(p.getRotationVec(1f).multiply(40)) : hit.getPos();
                Vec3d from = p.getEyePos();
                for (int i = 0; i <= 20; i++) {
                    Vec3d q = from.lerp(at, i / 20.0);
                    w.spawnParticles(ParticleTypes.SMOKE, q.x, q.y, q.z, 2, 0.05, 0.05, 0.05, 0);
                }
                w.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y, at.z, 1, 0, 0, 0, 0);
                w.playSound(null, BlockPos.ofFloored(at), SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 1.2f, 0.9f);
                for (Entity e : w.getOtherEntities(p, new net.minecraft.util.math.Box(at, at).expand(5), e -> e instanceof LivingEntity le && le.isAlive())) {
                    if (e instanceof ServerPlayerEntity o && AotRpg.PARTIES.same(o.getUuid(), p.getUuid())) continue;
                    ((LivingEntity) e).damage(w.getDamageSources().explosion(p, p), 10 + lv * 0.25f);
                }
                return true;
            }
            case "advance" -> {
                long until = now + 6000;
                for (ServerPlayerEntity a : Classes.squad(p, 16)) {
                    advance.put(a.getUuid(), until);
                    a.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 120, 1, false, true, true));
                    a.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 120, 1, false, true, true));
                    if (a != p) Notify.toast(a, Text.literal("ADVANCE!").formatted(Formatting.GOLD, Formatting.BOLD), Text.literal(pr(p).name + " leads the charge"), 0xE8C47A, "minecraft:goat_horn", "advance");
                }
                w.playSound(null, p.getBlockPos(), SoundEvents.EVENT_RAID_HORN.value(), SoundCategory.PLAYERS, 1f, 1.2f);
                return true;
            }
            case "aid" -> {
                for (ServerPlayerEntity a : Classes.squad(p, 16)) {
                    if (a != p && AotRpg.DOWNED.isDowned(a)) {
                        AotRpg.DOWNED.boost(a, p, 1f);
                        Downed.mend(a);
                        return true;
                    }
                }
                ServerPlayerEntity worst = null;
                for (ServerPlayerEntity a : Classes.squad(p, 16)) if (worst == null || a.getHealth() / a.getMaxHealth() < worst.getHealth() / worst.getMaxHealth()) worst = a;
                if (worst == null) return false;
                AotRpg.CLASSES.heal(p, worst, 8);
                Downed.mend(worst);
                return true;
            }
            case "awakening" -> {
                awake.put(p.getUuid(), now + 8000);
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 160, 1, false, true, true));
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 160, 2, false, true, true));
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.HASTE, 160, 1, false, false, true));
                AotRpg.STAMINA.refill(p);
                w.spawnParticles(ParticleTypes.SONIC_BOOM, p.getX(), p.getY() + 1, p.getZ(), 1, 0, 0, 0, 0);
                w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 1.4f, 0.7f);
                for (ServerPlayerEntity o : PlayerLookup.around(w, p.getPos(), 24)) {
                    if (ServerPlayNetworking.canSend(o, Net.Quake.ID)) ServerPlayNetworking.send(o, new Net.Quake(0.3f, 8));
                }
                return true;
            }
            case "coordinate" -> {
                for (Entity e : w.getOtherEntities(p, p.getBoundingBox().expand(30), AotRpg::isTitan)) {
                    if (!(e instanceof LivingEntity le)) continue;
                    le.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 70, 255, false, false));
                    if (le instanceof MobEntity mob) mob.setTarget(null);
                }
                w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.PLAYERS, 1.5f, 0.6f);
                w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, p.getX(), p.getY() + 1.5, p.getZ(), 80, 1.5, 1.5, 1.5, 0.5);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    // ------------------------------------------------------------------ operators

    public static void commands(com.mojang.brigadier.CommandDispatcher<ServerCommandSource> d) {
        d.register(CommandManager.literal("technique").requires(s -> s.hasPermissionLevel(2))
            .then(CommandManager.literal("grant").then(CommandManager.argument("player", net.minecraft.command.argument.EntityArgumentType.player())
                .then(CommandManager.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word())
                    .suggests((c, b) -> {
                        for (Tech t : TECHS) b.suggest(t.id());
                        b.suggest("all");
                        return b.buildFuture();
                    })
                    .executes(c -> grant(c.getSource(), net.minecraft.command.argument.EntityArgumentType.getPlayer(c, "player"),
                        com.mojang.brigadier.arguments.StringArgumentType.getString(c, "id"), true)))))
            .then(CommandManager.literal("revoke").then(CommandManager.argument("player", net.minecraft.command.argument.EntityArgumentType.player())
                .then(CommandManager.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word())
                    .executes(c -> grant(c.getSource(), net.minecraft.command.argument.EntityArgumentType.getPlayer(c, "player"),
                        com.mojang.brigadier.arguments.StringArgumentType.getString(c, "id"), false)))))
            .then(CommandManager.literal("equip").then(CommandManager.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word())
                .executes(c -> {
                    ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
                    String id = com.mojang.brigadier.arguments.StringArgumentType.getString(c, "id");
                    if (!pr(p).techniques.contains(id)) return 0;
                    pr(p).technique = id;
                    AotRpg.PROFILES.save(p.getUuid());
                    return 1;
                }))));
    }

    private static int grant(ServerCommandSource src, ServerPlayerEntity p, String id, boolean on) {
        Profile pr = pr(p);
        for (Tech t : TECHS) {
            if (!id.equals("all") && !t.id().equals(id)) continue;
            if (on) pr.techniques.add(t.id());
            else pr.techniques.remove(t.id());
        }
        if (on && (pr.technique == null || pr.technique.isEmpty()) && !pr.techniques.isEmpty()) pr.technique = pr.techniques.iterator().next();
        AotRpg.PROFILES.save(p.getUuid());
        src.sendFeedback(() -> Text.literal((on ? "Granted " : "Revoked ") + id + " for " + pr.name), true);
        return 1;
    }
}
