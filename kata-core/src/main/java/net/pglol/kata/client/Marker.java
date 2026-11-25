package net.pglol.kata.client;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Where your hands will go on a ledge: two small brackets on its edge, a hand's width either side of
 * you. White when a jump would reach it, green when tapping space grabs it now (and while hanging).
 */
public final class Marker {
    private Marker() {}

    static void render(WorldRenderContext ctx) {
        Vec3d m = Parkour.marker;
        if (m == null || ctx.consumers() == null) return;
        Direction d = Parkour.markerDir;
        Vec3d cam = ctx.camera().getPos();
        MatrixStack ms = ctx.matrixStack();
        if (ms == null) return;
        ms.push();
        ms.translate(-cam.x, -cam.y, -cam.z);
        VertexConsumer lines = ctx.consumers().getBuffer(RenderLayer.getLines());
        float pulse = 0.65f + 0.35f * (float) Math.sin(System.nanoTime() / 2.5e8);
        float r = Parkour.markerReady ? 0.35f : 1f, g = 1f, b = Parkour.markerReady ? 0.5f : 1f, a = Parkour.markerReady ? 0.95f : 0.55f * pulse + 0.2f;
        // A bracket at each hand's spot (they slide in from ends and corners, so they're always on stone).
        Vec3d[] hands = Parkour.markerHands;
        if (hands == null) hands = new Vec3d[] {m};
        double ax = Math.abs(d.getOffsetZ()), az = Math.abs(d.getOffsetX());
        for (Vec3d h : hands) {
            if (h == null) continue;
            double hw = 0.07, depth = 0.05;
            Box box = new Box(h.x - ax * hw - az * depth, h.y, h.z - az * hw - ax * depth,
                h.x + ax * hw + az * depth, h.y + 0.03, h.z + az * hw + ax * depth);
            WorldRenderer.drawBox(ms, lines, box, r, g, b, a);
        }
        ms.pop();
    }
}
