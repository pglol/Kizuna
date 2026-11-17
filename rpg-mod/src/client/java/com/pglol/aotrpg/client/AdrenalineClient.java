package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Adrenaline;
import com.pglol.aotrpg.Builds;
import com.pglol.aotrpg.Net;
import com.pglol.aotrpg.Stat;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.Entity;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The fight's pulse on your side: running down whoever you're locked on to (faster than you
 * should be able to, in lurching surges, trailing afterimages), breaking from a standstill the
 * way your build does, the lens and the edges of the screen tightening with it, and bodies
 * hanging limp where they were slammed into walls.
 */
public final class AdrenalineClient {
    private AdrenalineClient() {}

    private static boolean charging, wasSprinting;
    private static int chargeTicks, stillTicks, lurchIn = 10;
    private static long startCdUntil, huntedUntil;
    private static float chargeFov, punchFov;
    private static final Set<Integer> chargers = new HashSet<>();
    /** Bodies pinned to walls: entity -> until (ms). */
    private static final Map<Integer, Long> pinned = new HashMap<>();

    public static Builds build() {
        Net.Sync s = ClientState.profile;
        return s == null ? Builds.BALANCED : Builds.of(s.total(), s.cls());
    }

    private static int agility() {
        Net.Sync s = ClientState.profile;
        return s == null || s.total() == null || s.total().length <= Stat.AGILITY.ordinal() ? 0 : s.total()[Stat.AGILITY.ordinal()];
    }

    /** Bodies mid-vanish: entity -> {until ms, from x, y, z}. Not drawn at all until then. */
    private static final Map<Integer, double[]> vanishing = new HashMap<>();

    public static boolean vanished(int entity) {
        double[] v = vanishing.get(entity);
        return v != null && Util.getMeasuringTimeMs() < v[0];
    }

    public static long burstReadyAt() {
        return startCdUntil;
    }

    /** A kick to the lens (negative snaps it in). */
    public static void punch(float deg) {
        punchFov = deg;
    }

    private static void vanish(Entity e, long ms) {
        vanishing.put(e.getId(), new double[] {Util.getMeasuringTimeMs() + ms, e.getX(), e.getY(), e.getZ(), 0});
        Afterimages.leave(e, 0xCFE4FF, 320);
    }

    /** Face down in the ground: entity -> {from ms, until ms}. */
    private static final Map<Integer, long[]> faceplants = new HashMap<>();

    /** How flat on the ground a body is (0 .. 1): slams in fast, eases back up at the end. */
    public static float faceplant(int entity) {
        long[] f = faceplants.get(entity);
        if (f == null) return 0;
        long now = Util.getMeasuringTimeMs();
        if (now > f[1]) return 0;
        float in = Math.min(1, (now - f[0]) / 90f), out = Math.min(1, (f[1] - now) / 250f);
        return Math.min(in, out);
    }

    public static boolean pinned(int entity) {
        Long u = pinned.get(entity);
        return u != null && Util.getMeasuringTimeMs() < u;
    }

    public static void on(Net.RushFx f) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return;
        Entity e = mc.world.getEntityById(f.entity());
        switch (f.kind()) {
            case Adrenaline.FX_CHARGE -> chargers.add(f.entity());
            case Adrenaline.FX_CHARGE_END -> chargers.remove(f.entity());
            case Adrenaline.FX_HUNTED -> {
                huntedUntil = Util.getMeasuringTimeMs() + 2200;
                EffectFx.quake(0.12f, 6);
            }
            case Adrenaline.FX_FACEPLANT -> {
                long now = Util.getMeasuringTimeMs();
                faceplants.put(f.entity(), new long[] {now, now + f.arg() * 50L});
                pinned.put(f.entity(), now + f.arg() * 50L);
                if (e != null) FxParticle.spawn(com.pglol.aotrpg.AotParticles.RING, 0xD8C8A8, 3f, e.getX(), e.getY() + 0.05, e.getZ(), 0, 0, 0);
                if (mc.player != null && f.entity() == mc.player.getId()) EffectFx.quake(0.8f, 12);
            }
            case Adrenaline.FX_CHAIN -> MovesHud.chain(f.arg());
            case Adrenaline.FX_METER -> meter = f.arg();
            case Adrenaline.FX_FINISH -> {
                if (e != null) {
                    Afterimages.leave(e, 0xFF3A2A, 400);
                    streaks(mc, e, e.getYaw(), 14, true);
                }
                if (mc.player != null && f.entity() == mc.player.getId()) {
                    MovesHud.popup("FINISHER", 0xFFFF3A2A);
                    punchFov = -14;
                }
            }
            case Adrenaline.FX_STRIKE -> {
                MovesHud.popup("CHARGE STRIKE", 0xFFFF6A4A);
                punchFov = -8;
            }
            case Adrenaline.FX_SURGE -> {
                surging.put(f.entity(), Util.getMeasuringTimeMs() + f.arg() * 50L);
                if (mc.player != null && f.entity() == mc.player.getId()) {
                    if (f.yaw() >= 1) titanSurgeUntil = Util.getMeasuringTimeMs() + f.arg() * 50L;
                    MovesHud.popup(f.yaw() >= 1 ? "TITAN SURGE" : "ADRENALINE SURGE", f.yaw() >= 1 ? 0xFFFFC84A : 0xFFFF3A2A);
                    ImpactFrames.rush(1f, 400);
                    EffectFx.quake(0.4f, 8);
                    punchFov = 14;
                }
            }
            case Adrenaline.FX_AFTER -> {
                if (e != null) {
                    Afterimages.leave(e, f.arg(), 280);
                    streaks(mc, e, e.getYaw(), 4, false);
                }
            }
            case Adrenaline.FX_STUNNED -> {
                stunnedUntil.put(f.entity(), Util.getMeasuringTimeMs() + f.arg() * 50L);
                if (mc.player != null && f.entity() == mc.player.getId()) {
                    EffectFx.quake(0.3f, 6);
                    MovesHud.popup("STUNNED", 0xFFE04A3A);
                }
            }
            case Adrenaline.FX_PINNED -> {
                pinned.put(f.entity(), Util.getMeasuringTimeMs() + f.arg() * 50L);
                if (mc.player != null && f.entity() == mc.player.getId()) EffectFx.quake(0.7f, 10);
            }
            case Adrenaline.FX_VANISH -> {
                if (e != null) {
                    streaks(mc, e, f.yaw(), 26, false);
                    if (!vanished(e.getId())) vanish(e, Math.max(60, f.arg()));
                }
            }
            case Adrenaline.FX_HEAVY -> {
                if (e != null) streaks(mc, e, f.yaw(), 8, false);
            }
            default -> {
                if (e != null) streaks(mc, e, f.yaw(), 6, false);
            }
        }
    }

    /** Wind streaks torn off behind a body, the way it went. */
    private static void streaks(MinecraftClient mc, Entity e, float yaw, int n, boolean red) {
        double y = Math.toRadians(yaw);
        Vec3d back = new Vec3d(Math.sin(y), 0, -Math.cos(y));
        var r = mc.world.random;
        for (int i = 0; i < n; i++) {
            double h = 0.1 + r.nextDouble() * 1.8, sp = 0.5 + r.nextDouble() * 0.7;
            FxParticle.spawn(com.pglol.aotrpg.AotParticles.STREAK, red ? 0xFF3A3A : 0xEAF4FF, 0.5f + r.nextFloat() * 0.5f,
                e.getX() + (r.nextDouble() - 0.5) * 0.8, e.getY() + h, e.getZ() + (r.nextDouble() - 0.5) * 0.8,
                back.x * sp, (r.nextDouble() - 0.5) * 0.05, back.z * sp);
        }
        FxParticle.spawn(com.pglol.aotrpg.AotParticles.RING, red ? 0xFF5A4A : 0xFFFFFF, 2.2f, e.getX(), e.getY() + 0.05, e.getZ(), 0, 0, 0);
    }

    /** Dazed bodies: entity -> until (ms). Stars circle their heads. */
    private static final Map<Integer, Long> stunnedUntil = new HashMap<>();

    public static boolean stunned(int entity) {
        Long u = stunnedUntil.get(entity);
        return u != null && Util.getMeasuringTimeMs() < u;
    }

    public static void tick(MinecraftClient mc) {
        ClientPlayerEntity pl = mc.player;
        chargeFov += ((charging ? 14f : 0f) - chargeFov) * 0.15f;
        punchFov *= 0.82f;
        if (pl == null || mc.world == null) {
            charging = false;
            return;
        }
        // Everyone charging leaves afterimages behind them.
        for (int id : chargers) {
            Entity e = mc.world.getEntityById(id);
            if (e == null) continue;
            if (mc.world.getTime() % 2 == 0) Afterimages.leave(e, 0xC01A1A, 260);
        }
        pinned.values().removeIf(u -> Util.getMeasuringTimeMs() > u + 500);
        faceplants.values().removeIf(u -> Util.getMeasuringTimeMs() > u[1] + 500);
        surging.values().removeIf(u -> Util.getMeasuringTimeMs() > u);
        if (mc.world.getTime() % 3 == 0) for (int id : surging.keySet()) {
            Entity e = mc.world.getEntityById(id);
            if (e != null && e.getVelocity().horizontalLengthSquared() > 0.02) Afterimages.leave(e, 0xFF4A2A, 220);
        }
        stunnedUntil.values().removeIf(u -> Util.getMeasuringTimeMs() > u);
        if (mc.world.getTime() % 2 == 0) for (int id : stunnedUntil.keySet()) {
            Entity e = mc.world.getEntityById(id);
            if (e == null) continue;
            double a = (mc.world.getTime() % 20) / 20.0 * Math.PI * 2;
            for (int k = 0; k < 3; k++) {
                double b = a + k * Math.PI * 2 / 3;
                FxParticle.spawn(com.pglol.aotrpg.AotParticles.SPARK, 0xFFE08A, 0.35f, e.getX() + Math.cos(b) * 0.45, e.getY() + e.getHeight() + 0.25,
                    e.getZ() + Math.sin(b) * 0.45, 0, 0, 0);
            }
        }
        // Back into sight: a blur of images across the gap the vanish crossed.
        long nowMs = Util.getMeasuringTimeMs();
        for (var it = vanishing.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            double[] v = en.getValue();
            if (nowMs < v[0]) continue;
            it.remove();
            Entity e = mc.world.getEntityById(en.getKey());
            if (e == null) continue;
            Afterimages.smear(e, new Vec3d(v[1], v[2], v[3]), 0xCFE4FF, 5, 300);
            streaks(mc, e, e.getYaw(), 10, false);
        }
        sideTaps(mc, pl);
        if (pinned(pl.getId())) return;
        chargeTick(mc, pl);
        startTick(mc, pl);
    }

    /** Your adrenaline (0-100, from the server), and when the next charge may start. */
    private static int meter;
    private static long chargeReadyAt;
    private static int buildUp, lurches;
    /** Everyone surging: entity -> until (ms). */
    private static final Map<Integer, Long> surging = new HashMap<>();

    private static long titanSurgeUntil;

    public static boolean titanSurge() {
        return Util.getMeasuringTimeMs() < titanSurgeUntil;
    }

    /** A burst on a titan surge: a gold wake and streaks behind you. */
    public static void titanTrail(Entity e) {
        Afterimages.leave(e, 0xFFC84A, 320);
        for (int i = 0; i < 8; i++) FxParticle.spawn(com.pglol.aotrpg.AotParticles.STREAK, 0xFFD27A, 0.8f, e.getX(), e.getY() + 1, e.getZ(),
            (Math.random() - 0.5) * 0.8, (Math.random() - 0.5) * 0.5, (Math.random() - 0.5) * 0.8);
    }

    public static int meter() {
        return meter;
    }

    public static long chargeReadyAt() {
        return chargeReadyAt;
    }

    /**
     * The charge: locked on and sprinting straight at them, from a fair way out, with adrenaline to
     * spend. A beat of build-up (the heartbeat climbs), then the rush: faster than a run, a couple
     * of lurches that leave your shape behind. It ends when you're on them, and the first blow
     * lands harder (the server's Charge Strike). Then a cooldown: one charge per approach.
     */
    private static void chargeTick(MinecraftClient mc, ClientPlayerEntity pl) {
        long now = Util.getMeasuringTimeMs();
        Entity t = LockOn.target();
        Vec3d to = Vec3d.ZERO;
        double d = 0, dot = -1;
        boolean running = t != null && t.isAlive() && pl.isSprinting() && pl.input.movementForward > 0.5f && !pl.isUsingItem();
        if (running) {
            to = t.getPos().subtract(pl.getPos()).multiply(1, 0, 1);
            d = to.length();
            Vec3d look = pl.getRotationVec(1f).multiply(1, 0, 1);
            dot = d < 1e-3 || look.lengthSquared() < 1e-4 ? 1 : look.normalize().dotProduct(to.multiply(1 / d));
        }
        if (!charging) {
            // Lining it up: straight at them, far enough out, ready, and enough in you.
            boolean lined = running && d > 7 && d < 40 && dot > 0.88 && now >= chargeReadyAt && meter >= com.pglol.aotrpg.Adrenaline.CHARGE_COST;
            buildUp = lined ? buildUp + 1 : 0;
            if (buildUp == 4) pl.playSound(net.minecraft.sound.SoundEvents.ENTITY_WARDEN_HEARTBEAT, 0.6f, 1.1f);
            if (buildUp < 10) return;
            charging = true;
            chargeTicks = 0;
            lurches = 0;
            lurchIn = 6;
            buildUp = 0;
            chargeReadyAt = now + 7500;
            ClientPlayNetworking.send(new Net.Rush(Adrenaline.RUSH_START, t.getId(), pl.getYaw()));
            pl.playSound(net.minecraft.sound.SoundEvents.ENTITY_WARDEN_HEARTBEAT, 1f, 1.5f);
            MovesHud.popup("CHARGE", 0xFFFF4A3A);
            punchFov = 10;
            return;
        }
        chargeTicks++;
        // Over: on them, turned away, stopped running, or run out.
        if (!running || d < 2.8 || dot < 0.55 || chargeTicks > 55) {
            charging = false;
            ClientPlayNetworking.send(new Net.Rush(Adrenaline.RUSH_STOP, -1, pl.getYaw()));
            return;
        }
        Vec3d dir = to.normalize();
        Vec3d v = pl.getVelocity();
        double cur = Math.sqrt(v.x * v.x + v.z * v.z);
        double want2 = pl.isOnGround() ? Math.max(cur, 0.52) : Math.min(1.6, Math.max(cur * 1.03, 0.55));
        // Two lurches at most: sudden surges that leave your shape behind.
        if (--lurchIn <= 0 && lurches < 2) {
            lurches++;
            lurchIn = 12 + pl.getRandom().nextInt(6);
            want2 = Math.max(want2, 1.05);
            Afterimages.leave(pl, 0xC01A1A, 300);
            streaks(mc, pl, pl.getYaw(), 6, true);
            ImpactFrames.rush(0.5f, 200);
            punchFov = 6;
        }
        pl.setVelocity(dir.x * want2, v.y, dir.z * want2);
    }

    private static void startTick(MinecraftClient mc, ClientPlayerEntity pl) {
        Vec3d v = pl.getVelocity();
        double h = Math.sqrt(v.x * v.x + v.z * v.z);
        boolean sprint = pl.isSprinting();
        if (pl.isOnGround() && h < 0.07 && !sprint) stillTicks = Math.min(stillTicks + 1, 20);
        else if (!sprint) stillTicks = Math.max(0, stillTicks - 1);
        boolean began = sprint && !wasSprinting;
        wasSprinting = sprint;
        if (!began || stillTicks < 3 || !pl.isOnGround() || charging || Util.getMeasuringTimeMs() < startCdUntil || mc.currentScreen != null) return;
        stillTicks = 0;
        burst(mc, pl, pl.input.movementForward, pl.input.movementSideways);
    }

    private static final long[] lastTap = new long[3];
    private static final boolean[] wasDown = new boolean[3];

    /** Double-tapping left, back or right: the same burst W gives, that way. */
    private static void sideTaps(MinecraftClient mc, ClientPlayerEntity pl) {
        var o = mc.options;
        boolean[] down = {o.leftKey.isPressed(), o.backKey.isPressed(), o.rightKey.isPressed()};
        long now = Util.getMeasuringTimeMs();
        for (int i = 0; i < 3; i++) {
            boolean press = down[i] && !wasDown[i];
            wasDown[i] = down[i];
            if (!press || mc.currentScreen != null) continue;
            if (now - lastTap[i] < 260 && pl.isOnGround() && !charging && now >= startCdUntil) {
                lastTap[i] = 0;
                burst(mc, pl, i == 1 ? -1 : 0, i == 0 ? 1 : i == 2 ? -1 : 0);
            } else lastTap[i] = now;
        }
    }

    /** Breaking off the mark the way your build does (forward and side are the input axes). */
    private static void burst(MinecraftClient mc, ClientPlayerEntity pl, float fwd, float side) {
        Vec3d v = pl.getVelocity();
        double yr = Math.toRadians(pl.getYaw());
        Vec3d f = new Vec3d(-Math.sin(yr), 0, Math.cos(yr)), left = new Vec3d(Math.cos(yr), 0, Math.sin(yr));
        Vec3d wish = f.multiply(fwd).add(left.multiply(side));
        if (wish.lengthSquared() < 0.01) return;
        wish = wish.normalize();
        float yaw = (float) Math.toDegrees(Math.atan2(-wish.x, wish.z));
        Builds b = build();
        boolean vanish = false;
        double push;
        switch (b) {
            case SWIFT -> {
                vanish = pl.getRandom().nextFloat() < Math.min(0.75f, 0.3f + agility() * 0.04f);
                // The faster you are, the further the vanish carries you.
                push = vanish ? Math.min(1.9, 1.25 + agility() * 0.035) : 0.6;
                startCdUntil = Util.getMeasuringTimeMs() + 3500;
            }
            case HEAVY -> {
                push = 0.5;
                EffectFx.quake(0.3f, 6);
                startCdUntil = Util.getMeasuringTimeMs() + 2500;
            }
            default -> {
                push = 0.6;
                startCdUntil = Util.getMeasuringTimeMs() + 2500;
            }
        }
        pl.setVelocity(wish.x * push, v.y + (b == Builds.HEAVY ? 0.05 : 0.02), wish.z * push);
        punchFov = vanish ? 18 : b == Builds.HEAVY ? 4 : 8;
        if (vanish) {
            MovesHud.popup("VANISH", 0xFFCFE4FF);
            vanish(pl, Math.min(320, 90 + agility() * 14L));
            ImpactFrames.rush(0.9f, 240);
        } else if (b == Builds.HEAVY) {
            FxParticle.spawn(com.pglol.aotrpg.AotParticles.RING, 0xD8C8A8, 3.5f, pl.getX(), pl.getY() + 0.05, pl.getZ(), 0, 0, 0);
        } else streaks(mc, pl, yaw, 5, false);
        ClientPlayNetworking.send(new Net.Rush(vanish ? Adrenaline.START_VANISH : Adrenaline.START_PLAIN, -1, yaw));
    }

    /** Degrees added to the lens. */
    public static float fov() {
        return chargeFov + punchFov;
    }

    /** The edges of the screen: tight and red while you run someone down, black while someone runs you down. */
    public static void render(DrawContext c, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden) return;
        long now = Util.getMeasuringTimeMs();
        float red = chargeFov / 14f;
        Long su = surging.get(mc.player.getId());
        if (su != null && su > now) red = Math.max(red, 0.7f);
        float dark = huntedUntil > now ? Math.min(1, (huntedUntil - now) / 600f) : 0;
        if (pinned(mc.player.getId())) dark = Math.max(dark, 0.8f);
        if (red < 0.02f && dark < 0.02f) return;
        int w = c.getScaledWindowWidth(), h = c.getScaledWindowHeight();
        float beat = 0.75f + 0.25f * MathHelper.sin(now / 90f);
        int band = Math.max(18, h / 6);
        for (int i = 0; i < band; i += 2) {
            float k = 1 - i / (float) band;
            k = k * k;
            int a1 = (int) (150 * red * k * beat), a2 = (int) (190 * dark * k * beat);
            int col = a2 > a1 ? (a2 << 24) : (a1 << 24) | 0x5A0000;
            if (Math.max(a1, a2) < 3) continue;
            c.fill(0, i, w, i + 2, col);
            c.fill(0, h - i - 2, w, h - i, col);
            c.fill(i, 0, i + 2, h, col);
            c.fill(w - i - 2, 0, w - i, h, col);
        }
    }
}
