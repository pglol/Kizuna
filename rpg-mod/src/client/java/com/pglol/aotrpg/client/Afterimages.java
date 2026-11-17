package com.pglol.aotrpg.client;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.util.Util;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Afterimages: real copies of a body left hanging in the air where it just was, tinted and
 * fading (the speed of a vanish, the dread of a charge). Drawn with the player's own model and
 * skin, in the pose they had.
 */
public final class Afterimages {
    private Afterimages() {}

    private record Ghost(int entity, Vec3d pos, float bodyYaw, float headYaw, float pitch, float limbPos, float limbSpeed, int argb, long at, long life) { }

    private static final List<Ghost> ghosts = new ArrayList<>();

    /** Leaves an image of this body where it stands, in colour (rgb), for life ms. */
    public static void leave(Entity e, int rgb, long life) {
        if (!(e instanceof AbstractClientPlayerEntity p)) return;
        if (ghosts.size() > 40) ghosts.remove(0);
        ghosts.add(new Ghost(p.getId(), p.getPos(), p.bodyYaw, p.headYaw, p.getPitch(), p.limbAnimator.getPos(), p.limbAnimator.getSpeed(),
            rgb & 0xFFFFFF, Util.getMeasuringTimeMs(), life));
    }

    /** A line of them from a spot to where the body is now (a blur across the gap). */
    public static void smear(Entity e, Vec3d from, int rgb, int count, long life) {
        if (!(e instanceof AbstractClientPlayerEntity p)) return;
        Vec3d to = p.getPos();
        for (int i = 0; i < count; i++) {
            double k = i / (double) Math.max(1, count);
            Vec3d at = from.lerp(to, k);
            ghosts.add(new Ghost(p.getId(), at, p.bodyYaw, p.headYaw, p.getPitch(), p.limbAnimator.getPos(), p.limbAnimator.getSpeed(),
                rgb & 0xFFFFFF, Util.getMeasuringTimeMs() - (long) ((1 - k) * life * 0.5), life));
        }
        while (ghosts.size() > 40) ghosts.remove(0);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void render(WorldRenderContext ctx) {
        if (ghosts.isEmpty()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || ctx.consumers() == null) return;
        long now = Util.getMeasuringTimeMs();
        ghosts.removeIf(g -> now - g.at() > g.life());
        MatrixStack ms = ctx.matrixStack();
        if (ms == null) ms = new MatrixStack();
        Vec3d cam = ctx.camera().getPos();
        for (Ghost g : ghosts) {
            Entity e = mc.world.getEntityById(g.entity());
            if (!(e instanceof AbstractClientPlayerEntity p)) continue;
            if (!(mc.getEntityRenderDispatcher().getRenderer(p) instanceof LivingEntityRenderer lr)) continue;
            if (!(lr.getModel() instanceof PlayerEntityModel model)) continue;
            float f = (now - g.at()) / (float) g.life();
            if (f < 0) continue;
            int a = (int) (150 * (1 - f) * (1 - f));
            if (a < 4) continue;
            ms.push();
            ms.translate(g.pos().x - cam.x, g.pos().y - cam.y, g.pos().z - cam.z);
            ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180 - g.bodyYaw()));
            // A ghost swells a touch as it fades.
            float s = 0.9375f * (1 + f * 0.08f);
            ms.scale(-s, -s, s);
            ms.translate(0, -1.501f, 0);
            model.child = false;
            model.sneaking = false;
            model.setAngles(p, g.limbPos(), g.limbSpeed(), p.age, g.headYaw() - g.bodyYaw(), g.pitch());
            var vc = ctx.consumers().getBuffer(RenderLayer.getEntityTranslucentEmissive(p.getSkinTextures().texture()));
            model.render(ms, vc, 0xF000F0, OverlayTexture.DEFAULT_UV, (a << 24) | g.argb());
            ms.pop();
        }
    }
}
