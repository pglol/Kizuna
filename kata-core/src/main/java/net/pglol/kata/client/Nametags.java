package net.pglol.kata.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Map;

/**
 * Other players' tags, in place of the vanilla ones: a slim name on a faint see-through strip, a thin
 * health line under it and a hairline of breath under that. Out of the way at range: they fade softly
 * in as you come within about ten blocks and are fully there by six, and fade out as you leave.
 */
public final class Nametags {
    private Nametags() {}

    private static final Map<Integer, Float> SHOWN = new HashMap<>();
    private static long last;
    private static final int LIGHT = LightmapTextureManager.MAX_LIGHT_COORDINATE;

    static void render(MatrixStack ms, VertexConsumerProvider vcp, Camera cam) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null || mc.options.hudHidden) return;
        long now = System.nanoTime();
        float dt = last == 0 ? 0 : Math.min(0.1f, (now - last) / 1e9f);
        last = now;
        float td = mc.getRenderTickCounter().getTickDelta(true);
        Vec3d eye = cam.getPos();
        TextRenderer tr = mc.textRenderer;
        for (PlayerEntity e : mc.world.getPlayers()) {
            if (e == mc.player) continue;
            Vec3d at = e.getLerpedPos(td).add(0, e.getHeight() + 0.42, 0);
            double d = at.distanceTo(eye);
            boolean see = !e.isInvisible() && !e.isSpectator() && !e.isSneaking() && d < 10;
            float want = see ? MathHelper.clamp((float) (10 - d) / 4f, 0, 1) : 0;
            float a = SHOWN.getOrDefault(e.getId(), 0f);
            a += (want - a) * (1 - (float) Math.exp(-dt / 0.35f));
            SHOWN.put(e.getId(), a);
            if (a < 0.03f) continue;

            ms.push();
            ms.translate(at.x - eye.x, at.y - eye.y, at.z - eye.z);
            ms.multiply(cam.getRotation());
            ms.scale(0.022f, -0.022f, 0.022f); // facing the camera (the camera's rotation already turns it round)
            Matrix4f m = ms.peek().getPositionMatrix();

            String name = e.getName().getString();
            float tw = tr.getWidth(name);
            int bw = Math.max(36, (int) tw + 8);
            float x0 = -bw / 2f, x1 = bw / 2f;
            VertexConsumer bg = vcp.getBuffer(RenderLayer.getTextBackground());
            // The strip: faint, see-through, behind name and bars alike.
            quad(bg, m, x0, -2, x1, 14, 0, argb(a * 0.28f, 0x000000));
            // Health: a two-pixel line, red over a darker track.
            float hp = MathHelper.clamp(e.getHealth() / Math.max(1, e.getMaxHealth()), 0, 1);
            float lx0 = x0 + 3, lx1 = x1 - 3, lw = lx1 - lx0;
            quad(bg, m, lx0, 9, lx1, 10.6f, 0, argb(a * 0.45f, 0x2A0E0E));
            quad(bg, m, lx0, 9, lx0 + lw * hp, 10.6f, 0, argb(a * 0.9f, hp < 0.25f ? 0xE8383C : 0xD23A34));
            // Breath: a hairline under it, pale.
            float st = MathHelper.clamp(Fight.poise(e.getId()), 0, 1);
            quad(bg, m, lx0, 11.6f, lx0 + lw * st, 12.3f, 0, argb(a * 0.7f, st < 0.2f ? 0x8A8A8A : 0xF3E7BE));
            tr.draw(name, -tw / 2f, 0, argb(a * 0.92f, 0xF4ECE2), false, m, vcp, TextRenderer.TextLayerType.POLYGON_OFFSET, 0, LIGHT);
            ms.pop();
        }
        if (SHOWN.size() > 128) SHOWN.clear();
    }

    private static void quad(VertexConsumer vc, Matrix4f m, float x0, float y0, float x1, float y1, float z, int c) {
        vc.vertex(m, x0, y0, z).color(c).light(LIGHT);
        vc.vertex(m, x0, y1, z).color(c).light(LIGHT);
        vc.vertex(m, x1, y1, z).color(c).light(LIGHT);
        vc.vertex(m, x1, y0, z).color(c).light(LIGHT);
        // and the back, so it shows from either side whatever the culling
        vc.vertex(m, x1, y0, z).color(c).light(LIGHT);
        vc.vertex(m, x1, y1, z).color(c).light(LIGHT);
        vc.vertex(m, x0, y1, z).color(c).light(LIGHT);
        vc.vertex(m, x0, y0, z).color(c).light(LIGHT);
    }

    private static int argb(float a, int rgb) {
        return ((int) (MathHelper.clamp(a, 0, 1) * 255) << 24) | (rgb & 0xFFFFFF);
    }
}
