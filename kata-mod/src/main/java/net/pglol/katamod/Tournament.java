package net.pglol.katamod;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.pglol.kata.Dummy;
import net.pglol.kata.Fighting;
import net.pglol.kata.KataEntities;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Karate bouts on the Budokan's mat (Shobu Sanbon: first to three points).
 *
 * Step onto the mat. If someone else steps on too, it's the two of you; alone for ten seconds, and a
 * fighter bot comes out to meet you. The referee calls it: "Shobu sanbon, hajime!"
 *
 * Scoring, as in a real kumite, for clean, controlled strikes that land (a blocked one scores nothing):
 *  - Yuko (1): a punch, palm or elbow to the head or body
 *  - Waza-ari (2): a kick to the body
 *  - Ippon (3): a kick to the head, or a strike that puts them down
 * Each point: "Yame!", both back to their marks, "Tsuzukete hajime!".
 *
 * Fouls (the referee watches for them; a foul scores nothing):
 *  - striking the legs or joints (a sweep that takes them down is legal, and scores nothing itself)
 *  - striking the back of the head
 *  - striking after "Yame"
 *  - jogai: leaving the mat (and it's reset)
 * Penalties: a warning (chukoku), then keikoku and hansoku-chui (each gives the other a point), then
 * hansoku: disqualified.
 *
 * It ends at three points, a knockout (nobody dies on the mat: a finishing blow leaves them on their
 * feet's last breath, and the bout is theirs who threw it), a tap-out (hold crouch still for three
 * seconds, or /tapout), a disqualification, someone leaving, or three minutes (most points; level is a
 * draw, hikiwake).
 */
public final class Tournament {
    private Tournament() {}

    static final int CX = Town.lx(1) + 43, CZ = Town.lz(2) + 43, G = Town.G;
    /** The mat, inclusive blocks (the red-bordered blue square inside the wooden rim). */
    static final int X0 = CX - 6, X1 = CX + 5, Z0 = CZ - 6, Z1 = CZ + 5;
    static final Vec3d AKA_MARK = new Vec3d(CX - 2.5, G + 1, CZ), AO_MARK = new Vec3d(CX + 2.5, G + 1, CZ);
    static final int BOUT_TICKS = 3 * 60 * 20, WAIT_TICKS = 200;

    enum Phase { IDLE, WAITING, READY, FIGHT, BREAK, DONE }

    static final class Fighter {
        final LivingEntity e;
        final boolean bot;
        final String side;
        int score, fouls, still;

        Fighter(LivingEntity e, boolean bot, String side) {
            this.e = e;
            this.bot = bot;
            this.side = side;
        }

        String name() {
            return bot ? "the bot" : e.getName().getString();
        }
    }

    private static Phase phase = Phase.IDLE;
    private static int until, started;
    private static Fighter aka, ao;
    private static VillagerEntity ref;
    private static ServerPlayerEntity waiting;
    private static int waitStart;
    /** Fought just now: they step off the mat before they can be matched again. */
    private static final Set<UUID> RESTING = new HashSet<>();

    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(Tournament::tick);
        Fighting.LANDED.add(Tournament::landed);
        Fighting.spare = Tournament::fighting;
        ServerLivingEntityEvents.ALLOW_DEATH.register((e, src, amount) -> {
            if (!fighting(e)) return true;
            e.setHealth(1);
            Fighter f = of(e);
            if (f != null && (phase == Phase.FIGHT || phase == Phase.BREAK)) finish(other(f), "Knockout", e.getName().getString() + " is knocked out.");
            return false;
        });
        CommandRegistrationCallback.EVENT.register((d, reg, env) -> d.register(CommandManager.literal("tapout").executes(c -> {
            ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
            Fighter f = of(p);
            if (f == null || phase == Phase.DONE) {
                c.getSource().sendFeedback(() -> Text.literal("You're not in a bout."), false);
                return 0;
            }
            finish(other(f), "Kiken", f.name() + " taps out.");
            return 1;
        })));
    }

    static boolean fighting(LivingEntity e) {
        return (aka != null && aka.e == e) || (ao != null && ao.e == e);
    }

    private static Fighter of(Entity e) {
        return aka != null && aka.e == e ? aka : ao != null && ao.e == e ? ao : null;
    }

    private static Fighter other(Fighter f) {
        return f == aka ? ao : aka;
    }

    static boolean onMat(Entity e) {
        double x = e.getX(), z = e.getZ(), y = e.getY();
        return x >= X0 && x < X1 + 1 && z >= Z0 && z < Z1 + 1 && y >= G + 0.5 && y < G + 5;
    }

    private static ServerWorld world(MinecraftServer s) {
        return s.getWorld(Town.WORLD);
    }

    // ---- the referee's voice: a title for the two on the mat, a line for everyone watching ----

    private static void call(ServerWorld w, String title, String sub, Formatting col) {
        for (ServerPlayerEntity p : w.getPlayers()) {
            if (p.squaredDistanceTo(CX, G + 1, CZ) > 48 * 48) continue;
            boolean in = fighting(p);
            if (in) {
                p.networkHandler.sendPacket(new TitleFadeS2CPacket(2, 28, 8));
                p.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal(sub).formatted(Formatting.GRAY)));
                p.networkHandler.sendPacket(new TitleS2CPacket(Text.literal(title).formatted(col, Formatting.BOLD)));
            }
            p.sendMessage(Text.literal("[Referee] ").formatted(Formatting.DARK_GRAY).append(Text.literal(title).formatted(col)).append(Text.literal(sub.isEmpty() ? "" : "  " + sub).formatted(Formatting.GRAY)), false);
        }
        if (ref != null) {
            w.spawnParticles(ParticleTypes.NOTE, ref.getX(), ref.getY() + 2.3, ref.getZ(), 1, 0, 0, 0, 0);
            ref.swingHand(net.minecraft.util.Hand.MAIN_HAND);
        }
        w.playSound(null, CX, G + 1, CZ, SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), SoundCategory.PLAYERS, 0.9f, title.startsWith("Yame") ? 0.8f : 1.2f);
    }

    private static void scoreboard(ServerWorld w) {
        if (aka == null || ao == null) return;
        int left = Math.max(0, BOUT_TICKS - (w.getServer().getTicks() - started)) / 20;
        Text t = Text.literal("AKA ").formatted(Formatting.RED, Formatting.BOLD).append(Text.literal(aka.name() + "  " + aka.score).formatted(Formatting.WHITE))
            .append(Text.literal(pens(aka)).formatted(Formatting.GOLD))
            .append(Text.literal("     " + left / 60 + ":" + String.format("%02d", left % 60) + "     ").formatted(Formatting.GRAY))
            .append(Text.literal(ao.score + "  " + ao.name() + " ").formatted(Formatting.WHITE)).append(Text.literal(pens(ao)).formatted(Formatting.GOLD))
            .append(Text.literal(" AO").formatted(Formatting.BLUE, Formatting.BOLD));
        for (ServerPlayerEntity p : w.getPlayers()) if (p.squaredDistanceTo(CX, G + 1, CZ) < 40 * 40) p.sendMessage(t, true);
    }

    private static String pens(Fighter f) {
        return f.fouls == 0 ? "" : " " + "!".repeat(f.fouls);
    }

    // ---- the bout ----

    static void tick(MinecraftServer server) {
        ServerWorld w = world(server);
        if (w == null) return;
        int now = server.getTicks();
        RESTING.removeIf(id -> {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
            return p == null || !onMat(p);
        });
        switch (phase) {
            case IDLE, WAITING -> lookForFighters(w, now);
            case READY, BREAK -> {
                if (!present(w)) return;
                hold(w);
                if (now >= until) {
                    phase = Phase.FIGHT;
                    call(w, phase == Phase.FIGHT && aka.score + ao.score + aka.fouls + ao.fouls > 0 ? "Tsuzukete hajime!" : "Shobu sanbon, hajime!", "", Formatting.WHITE);
                }
            }
            case FIGHT -> {
                if (!present(w)) return;
                if (now % 10 == 0) scoreboard(w);
                if (now - started > BOUT_TICKS) {
                    Fighter win = aka.score > ao.score ? aka : ao.score > aka.score ? ao : null;
                    if (win == null) draw(w);
                    else finish(win, "Time", "Time is up.");
                    return;
                }
                for (Fighter f : new Fighter[] {aka, ao}) {
                    if (f.bot && f.e instanceof Dummy d && now % 20 == 0) d.engage(other(f).e, 40);
                    // Jogai: off the mat (once they've come down off it).
                    if (!onMat(f.e) && (f.e.isOnGround() || f.e.getY() < G + 0.9)) {
                        foul(w, f, "Jogai", "out of the mat");
                        if (phase == Phase.FIGHT) breakAndReset(w, 50);
                        return;
                    }
                    // A tap-out: holding crouch, still, for three seconds.
                    if (f.e instanceof ServerPlayerEntity p && p.isSneaking() && p.getVelocity().horizontalLengthSquared() < 0.002) {
                        if (++f.still >= 60) {
                            finish(other(f), "Kiken", f.name() + " taps out.");
                            return;
                        }
                    } else f.still = 0;
                }
            }
            case DONE -> {
                if (now >= until) cleanup();
            }
        }
    }

    private static void lookForFighters(ServerWorld w, int now) {
        if (now % 5 != 0) return;
        List<ServerPlayerEntity> on = w.getPlayers(p -> onMat(p) && p.isAlive() && !p.isSpectator() && !RESTING.contains(p.getUuid()));
        if (on.size() >= 2) {
            begin(w, on.get(0), on.get(1));
            return;
        }
        if (on.size() == 1) {
            ServerPlayerEntity p = on.get(0);
            if (phase != Phase.WAITING || waiting != p) {
                phase = Phase.WAITING;
                waiting = p;
                waitStart = now;
                p.sendMessage(Text.literal("On the mat. Waiting for an opponent; a bot comes out in 10 seconds.").formatted(Formatting.GRAY), true);
            } else if (now - waitStart >= WAIT_TICKS) {
                Dummy bot = new Dummy(KataEntities.DUMMY, w);
                bot.refreshPositionAndAngles(AO_MARK.x, AO_MARK.y, AO_MARK.z, 90, 0);
                bot.setCustomName(Text.literal("Kata bot"));
                bot.setHome(BlockPos.ofFloored(AO_MARK));
                w.spawnEntity(bot);
                begin(w, p, bot);
            } else if ((now - waitStart) % 20 == 0) {
                p.sendMessage(Text.literal("Waiting for an opponent... " + (WAIT_TICKS - (now - waitStart)) / 20).formatted(Formatting.GRAY), true);
            }
        } else if (phase == Phase.WAITING) {
            phase = Phase.IDLE;
            waiting = null;
        }
    }

    private static void begin(ServerWorld w, LivingEntity a, LivingEntity b) {
        aka = new Fighter(a, a instanceof Dummy, "AKA");
        ao = new Fighter(b, b instanceof Dummy, "AO");
        waiting = null;
        if (ref == null || ref.isRemoved()) {
            ref = EntityType.VILLAGER.create(w);
            if (ref != null) {
                ref.refreshPositionAndAngles(CX + 0.5, G + 1, Z1 + 1.5, 180, 0);
                ref.setAiDisabled(true);
                ref.setInvulnerable(true);
                ref.setSilent(true);
                ref.setCustomName(Text.literal("Referee"));
                ref.setCustomNameVisible(true);
                w.spawnEntity(ref);
            }
        }
        for (Fighter f : new Fighter[] {aka, ao}) f.e.setHealth(f.e.getMaxHealth());
        started = w.getServer().getTicks();
        reset(w);
        phase = Phase.READY;
        until = started + 60;
        call(w, "Shomen ni rei", aka.name() + " (aka) and " + ao.name() + " (ao): to your marks", Formatting.GOLD);
    }

    /** Both still there, in this world, alive: otherwise the one who stayed wins. */
    private static boolean present(ServerWorld w) {
        for (Fighter f : new Fighter[] {aka, ao}) {
            boolean gone = f.e.isRemoved() || !f.e.isAlive() || f.e.getWorld() != w || (f.e instanceof ServerPlayerEntity p && (p.isDisconnected() || p.isSpectator()));
            if (gone) {
                finish(other(f), "Kiken", f.name() + " left the bout.");
                return false;
            }
        }
        return true;
    }

    /** Between calls: both stay at their marks, and the bot holds off. */
    private static void hold(ServerWorld w) {
        for (Fighter f : new Fighter[] {aka, ao}) if (f.bot && f.e instanceof Dummy d) d.calm();
    }

    private static void reset(ServerWorld w) {
        place(aka.e, AKA_MARK, -90);
        place(ao.e, AO_MARK, 90);
    }

    private static void place(LivingEntity e, Vec3d at, float yaw) {
        if (e instanceof ServerPlayerEntity p) p.teleport((ServerWorld) p.getWorld(), at.x, at.y, at.z, yaw, 0);
        else {
            e.refreshPositionAndAngles(at.x, at.y, at.z, yaw, 0);
            e.setHeadYaw(yaw);
            e.setBodyYaw(yaw);
        }
        e.setVelocity(Vec3d.ZERO);
        e.velocityModified = true;
    }

    private static void breakAndReset(ServerWorld w, int ticks) {
        phase = Phase.BREAK;
        until = w.getServer().getTicks() + ticks;
        hold(w);
        reset(w);
    }

    /** A strike landed (Kata's own hook): the referee judges it. */
    static void landed(LivingEntity by, LivingEntity t, Fighting.Move m, Fighting.Part part, float power, boolean down) {
        Fighter a = of(by), b = of(t);
        if (a == null || b == null || a == b || !(by.getWorld() instanceof ServerWorld w)) return;
        if (phase == Phase.READY || phase == Phase.BREAK) {
            foul(w, a, "Foul", "striking after yame");
            return;
        }
        if (phase != Phase.FIGHT) return;
        boolean kick = m.leg();
        // From behind: the attacker is behind the one struck.
        Vec3d face = Vec3d.fromPolar(0, t.getYaw()), to = by.getPos().subtract(t.getPos()).multiply(1, 0, 1);
        boolean behind = to.lengthSquared() > 1e-4 && to.normalize().dotProduct(face) < -0.35;
        boolean sweep = m.path() == Fighting.Path.LOW_KICK && down;
        if (part == Fighting.Part.LEG && !sweep) {
            foul(w, a, "Foul", "striking the legs");
            return;
        }
        if (part == Fighting.Part.HEAD && behind) {
            foul(w, a, "Foul", "striking the back of the head");
            return;
        }
        if (part == Fighting.Part.ARM || sweep) return; // caught on the arms, or a sweep: no score
        int pts;
        String call;
        if (down || (kick && part == Fighting.Part.HEAD)) {
            pts = 3;
            call = "Ippon";
        } else if (kick) {
            pts = 2;
            call = "Waza-ari";
        } else {
            pts = 1;
            call = "Yuko";
        }
        a.score += pts;
        // Out on their feet: a knockout.
        if (t.getHealth() <= t.getMaxHealth() * 0.15f && !(t instanceof Dummy)) {
            finish(a, "Knockout", b.name() + " can't go on.");
            return;
        }
        if (a.score >= 3) {
            finish(a, call, a.name() + " (" + a.side.toLowerCase() + ") " + call.toLowerCase() + ": " + a.score + " points.");
            return;
        }
        breakAndReset(w, 50);
        call(w, "Yame! " + (a == aka ? "Aka" : "Ao") + ", " + call, aka.score + " - " + ao.score, a == aka ? Formatting.RED : Formatting.BLUE);
    }

    private static void foul(ServerWorld w, Fighter f, String what, String why) {
        f.fouls++;
        Fighter o = other(f);
        String pen;
        switch (f.fouls) {
            case 1 -> pen = "Chukoku (warning)";
            case 2 -> {
                pen = "Keikoku: a point to " + o.name();
                o.score++;
            }
            case 3 -> {
                pen = "Hansoku-chui: a point to " + o.name();
                o.score++;
            }
            default -> {
                finish(o, "Hansoku", f.name() + " is disqualified (" + why + ").");
                return;
            }
        }
        if (o.score >= 3) {
            finish(o, "Shobu ari", f.name() + " fouled out of it.");
            return;
        }
        breakAndReset(w, 50);
        call(w, "Yame! " + what, f.name() + ": " + why + ". " + pen, Formatting.YELLOW);
    }

    private static void finish(Fighter win, String how, String why) {
        if (win == null || phase == Phase.DONE) return;
        ServerWorld w = (ServerWorld) win.e.getWorld();
        phase = Phase.DONE;
        until = w.getServer().getTicks() + 80;
        hold(w);
        call(w, "Shobu ari! " + (win == aka ? "Aka" : "Ao") + " no kachi", how + ": " + why, win == aka ? Formatting.RED : Formatting.BLUE);
        w.playSound(null, CX, G + 1, CZ, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 0.8f, 1f);
    }

    private static void draw(ServerWorld w) {
        phase = Phase.DONE;
        until = w.getServer().getTicks() + 80;
        hold(w);
        call(w, "Hikiwake", "Time, and level: a draw. " + aka.score + " - " + ao.score, Formatting.GRAY);
    }

    private static void cleanup() {
        for (Fighter f : new Fighter[] {aka, ao}) {
            if (f == null) continue;
            if (f.bot) f.e.discard();
            else {
                f.e.setHealth(f.e.getMaxHealth());
                RESTING.add(f.e.getUuid());
            }
        }
        if (ref != null) ref.discard();
        ref = null;
        aka = ao = null;
        phase = Phase.IDLE;
    }
}
