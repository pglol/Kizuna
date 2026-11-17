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
            case Adrenaline.FX_PINNED -> {
                pinned.put(f.entity(), Util.getMeasuringTimeMs() + f.arg() * 50L);
                if (mc.player != null && f.entity() == mc.player.getId()) EffectFx.quake(0.7f, 10);
            }
            case Adrenaline.FX_VANISH -> {
                if (e != null) streaks(mc, e, f.yaw(), 26, true);
            }
            case Adrenaline.FX_HEAVY -> {
                if (e != null) streaks(mc, e, f.yaw(), 8, false);
            }
            default -> {
                if (e != null) streaks(mc, e, f.yaw(), 6, false);
            }
        }
    }

    /** Wind streaks left behind (and, for a vanish, a pale silhouette where you stood). */
    private static void streaks(MinecraftClient mc, Entity e, float yaw, int n, boolean ghost) {
        double y = Math.toRadians(yaw);
        Vec3d back = new Vec3d(Math.sin(y), 0, -Math.cos(y));
        var r = mc.world.random;
        for (int i = 0; i < n; i++) {
            double h = 0.2 + r.nextDouble() * 1.6;
            mc.world.addParticle(ParticleTypes.CLOUD, e.getX() + (r.nextDouble() - 0.5) * 0.6, e.getY() + h, e.getZ() + (r.nextDouble() - 0.5) * 0.6,
                back.x * (0.4 + r.nextDouble() * 0.5), 0.01, back.z * (0.4 + r.nextDouble() * 0.5));
        }
        if (ghost) silhouette(mc, e.getPos(), new Vector3f(0.92f, 0.95f, 1f));
    }

    /** A body-shaped smear of colour at a spot: an afterimage. */
    private static void silhouette(MinecraftClient mc, Vec3d at, Vector3f col) {
        DustParticleEffect d = new DustParticleEffect(col, 1.1f);
        for (double h = 0.1; h < 1.9; h += 0.18) {
            double w = h > 1.4 ? 0.18 : h > 0.75 ? 0.32 : 0.2;
            mc.world.addParticle(d, at.x - w, at.y + h, at.z, 0, 0, 0);
            mc.world.addParticle(d, at.x + w, at.y + h, at.z, 0, 0, 0);
            mc.world.addParticle(d, at.x, at.y + h, at.z + w * 0.5, 0, 0, 0);
        }
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
            if (mc.world.getTime() % 2 == 0) silhouette(mc, e.getPos(), new Vector3f(0.55f, 0.05f, 0.05f));
        }
        pinned.values().removeIf(u -> Util.getMeasuringTimeMs() > u + 500);
        if (pinned(pl.getId())) return;
        chargeTick(mc, pl);
        startTick(mc, pl);
    }

    private static void chargeTick(MinecraftClient mc, ClientPlayerEntity pl) {
        Entity t = LockOn.target();
        boolean want = false;
        Vec3d to = Vec3d.ZERO;
        if (t != null && t.isAlive() && pl.isSprinting() && pl.input.movementForward > 0.5f && !pl.isUsingItem()) {
            to = t.getPos().subtract(pl.getPos()).multiply(1, 0, 1);
            double d = to.length();
            Vec3d look = pl.getRotationVec(1f).multiply(1, 0, 1);
            want = d > 3.5 && d < 40 && look.lengthSquared() > 1e-4 && look.normalize().dotProduct(to.multiply(1 / d)) > 0.8;
        }
        if (want != charging) {
            charging = want;
            chargeTicks = 0;
            ClientPlayNetworking.send(new Net.Rush(want ? Adrenaline.RUSH_START : Adrenaline.RUSH_STOP, t == null ? -1 : t.getId(), pl.getYaw()));
            if (want) {
                pl.playSound(net.minecraft.sound.SoundEvents.ENTITY_WARDEN_HEARTBEAT, 0.9f, 1.4f);
                punchFov = 8;
            }
        }
        if (!charging) return;
        chargeTicks++;
        if (chargeTicks % 80 == 0) ClientPlayNetworking.send(new Net.Rush(Adrenaline.RUSH_START, t.getId(), pl.getYaw()));
        Vec3d dir = to.normalize();
        Vec3d v = pl.getVelocity();
        double cur = Math.sqrt(v.x * v.x + v.z * v.z);
        // Faster than a run has any right to be; on the gear, a little more of whatever you had.
        double want2 = pl.isOnGround() ? Math.max(cur, 0.5) : Math.min(1.6, Math.max(cur * 1.03, 0.55));
        // Now and then a lurch: a sudden surge that leaves your shape behind.
        if (--lurchIn <= 0) {
            lurchIn = 9 + pl.getRandom().nextInt(7);
            want2 = Math.max(want2, 1.05);
            silhouette(mc, pl.getPos(), new Vector3f(0.55f, 0.05f, 0.05f));
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
        float fwd = pl.input.movementForward, side = pl.input.movementSideways;
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
                push = vanish ? 1.25 : 0.6;
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
        if (vanish) silhouette(mc, pl.getPos(), new Vector3f(0.92f, 0.95f, 1f));
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
