package com.pglol.aotrpg.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/** Someone in front of you is down and nearly done: a prompt to finish them. */
public final class Finish {
    private Finish() {}

    /** The finishable foe in front of you, if any. */
    public static LivingEntity target(MinecraftClient mc) {
        var pl = mc.player;
        if (pl == null || mc.world == null) return null;
        var held = pl.getMainHandStack();
        if (!com.pglol.aotrpg.Guard.melee(held)) return null;
        Vec3d look = pl.getRotationVec(1f).multiply(1, 0, 1).normalize();
        LivingEntity best = null;
        double bd = 4.2 * 4.2;
        for (Entity e : mc.world.getOtherEntities(pl, pl.getBoundingBox().expand(4.5, 2, 4.5))) {
            if (!(e instanceof LivingEntity le) || !le.isAlive()) continue;
            if (Registries.ENTITY_TYPE.getId(e.getType()).getPath().contains("titan")) continue;
            boolean down = AdrenalineClient.stunned(e.getId()) || AdrenalineClient.pinned(e.getId()) || AdrenalineClient.faceplant(e.getId()) > 0;
            if (!down) continue;
            float limit = e instanceof PlayerEntity ? 0.25f : 0.2f;
            if (le.getHealth() > le.getMaxHealth() * limit) continue;
            Vec3d to = e.getPos().subtract(pl.getPos()).multiply(1, 0, 1);
            double d = to.lengthSquared();
            if (d > bd || d > 1e-4 && to.normalize().dotProduct(look) < 0.3) continue;
            bd = d;
            best = le;
        }
        return best;
    }

    private static int shown = -1;
    private static long shownAt, maxLeft;
    private static boolean on;

    /** The prompt is up this frame. */
    public static boolean showing() {
        return on;
    }

    /**
     * The prompt sits on the foe themselves: red brackets closing in round them as the window
     * runs out, a bar under them for the time left, and one word. A low chime when it opens.
     */
    public static void render(DrawContext c, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        LivingEntity t = mc.player == null || mc.options.hudHidden || mc.currentScreen != null || FinishScene.mine() ? null : target(mc);
        on = t != null;
        if (t == null) {
            shown = -1;
            return;
        }
        long now = Util.getMeasuringTimeMs();
        long until = AdrenalineClient.downUntil(t.getId());
        if (t.getId() != shown) {
            shown = t.getId();
            shownAt = now;
            maxLeft = Math.max(400, until - now);
            mc.player.playSound(net.minecraft.sound.SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, 0.9f, 0.6f);
            mc.player.playSound(net.minecraft.sound.SoundEvents.ITEM_TRIDENT_RETURN, 0.6f, 0.5f);
        }
        float left = until > now ? MathHelper.clamp((until - now) / (float) maxLeft, 0, 1) : 0;
        int w = c.getScaledWindowWidth(), h = c.getScaledWindowHeight();
        float td = tick.getTickDelta(true);
        Vec3d mid = t.getLerpedPos(td).add(0, t.getHeight() * 0.55, 0);
        float[] o = ImpactFrames.screen(mc, mid, w, h);
        // Size on screen from distance: about the body's height.
        double dist = Math.max(1, mc.gameRenderer.getCamera().getPos().distanceTo(mid));
        double fov = Math.toRadians(mc.options.getFov().getValue());
        float px = (float) (t.getHeight() / dist * (h / 2f) / Math.tan(fov / 2));
        float in = Math.min(1, (now - shownAt) / 120f);
        // Snaps in from wide, then tightens as time runs out.
        float r = MathHelper.clamp(px * 0.6f, 14, 90) * (MathHelper.lerp(in, 2.2f, 1) * (0.75f + 0.25f * left));
        float pulse = 0.75f + 0.25f * MathHelper.sin(now / (left < 0.3f ? 45f : 90f));
        int a = (int) (255 * pulse * in);
        int col = (a << 24) | 0xFF2A1E, shade = ((int) (140 * in) << 24);
        int cx = Math.round(o[0]), cy = Math.round(o[1]), R = Math.round(r), L = Math.max(5, R / 3), T = 2;
        for (int[] q : new int[][] {{-1, -1}, {1, -1}, {-1, 1}, {1, 1}}) {
            int x = cx + q[0] * R, y = cy + q[1] * R;
            // A shadow first, then the bracket.
            c.fill(Math.min(x, x - q[0] * L) + 1, y - (q[1] > 0 ? T : 0) + 1, Math.max(x, x - q[0] * L) + 1, y + (q[1] > 0 ? 0 : T) + 1, shade);
            c.fill(Math.min(x, x - q[0] * L), y - (q[1] > 0 ? T : 0), Math.max(x, x - q[0] * L), y + (q[1] > 0 ? 0 : T), col);
            c.fill(x - (q[0] > 0 ? T : 0), Math.min(y, y - q[1] * L), x + (q[0] > 0 ? 0 : T), Math.max(y, y - q[1] * L), col);
        }
        // The time left, a bar under the foe.
        int bw = Math.max(24, R * 2), bx = cx - bw / 2, by = cy + R + 5;
        c.fill(bx - 1, by - 1, bx + bw + 1, by + 3, 0xA0000000);
        c.fill(bx, by, bx + Math.round(bw * left), by + 2, col);
        String key = mc.options.attackKey.getBoundKeyLocalizedText().getString();
        Ui.text(c, Ui.title("FINISH"), cx, by + 6, 0.9f, col, true);
        Ui.text(c, Ui.heading("[" + key + "]"), cx, by + 17, 0.55f, ((int) (220 * in) << 24) | 0xFFFFFF, true);
    }
}
