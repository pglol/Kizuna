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
        // Along the edge, either side of the centre.
        double ax = -d.getOffsetZ(), az = d.getOffsetX();
        for (int s = -1; s <= 1; s += 2) {
            double cx = m.x + ax * 0.25 * s, cz = m.z + az * 0.25 * s;
            double hw = 0.08, depth = 0.06;
            Box box = new Box(cx - Math.abs(ax) * hw - Math.abs(d.getOffsetX()) * depth, m.y, cz - Math.abs(az) * hw - Math.abs(d.getOffsetZ()) * depth,
                cx + Math.abs(ax) * hw + Math.abs(d.getOffsetX()) * depth, m.y + 0.03, cz + Math.abs(az) * hw + Math.abs(d.getOffsetZ()) * depth);
            WorldRenderer.drawBox(ms, lines, box, r, g, b, a);
        }
        ms.pop();
    }
}
