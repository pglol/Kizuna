package net.pglol.kata.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;

/**
 * Who's watching you (see Awareness): over each watcher a mark that fills as they grow aware, a grey
 * eye, a yellow "?", a red "!". Crouched, under the crosshair: HIDDEN, SEEN (how many) or DISCOVERED.
 */
public final class AwarenessFx {
    private AwarenessFx() {}

    private record Watcher(float level, boolean sees, boolean alerted, long at) { }

    private static final Map<Integer, Watcher> WATCHERS = new HashMap<>();

    static void heard(int entity, int value) {
        float level = (value & 0xFF) / 100f;
        boolean sees = (value & 1 << 8) != 0, alerted = (value & 1 << 9) != 0;
        if (level <= 0 && !sees) WATCHERS.remove(entity);
        else WATCHERS.put(entity, new Watcher(level, sees, alerted, System.nanoTime()));
        if (WATCHERS.size() > 128) WATCHERS.clear();
    }

    private static int color(Watcher w) {
        if (w.alerted) return 0xE04A3A;
        if (w.level >= 0.3f) return 0xF2C14E;
        return 0xB8B2A2;
    }

    static void render(MatrixStack ms, VertexConsumerProvider vcp, Camera cam) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || WATCHERS.isEmpty()) return;
        long now = System.nanoTime();
        WATCHERS.values().removeIf(w -> now - w.at > 1_500_000_000L);
        float td = mc.getRenderTickCounter().getTickDelta(true);
        Vec3d eye = cam.getPos();
        TextRenderer tr = mc.textRenderer;
        for (var en : WATCHERS.entrySet()) {
            Watcher w = en.getValue();
            if (w.level < 0.05f) continue;
            Entity e = mc.world.getEntityById(en.getKey());
            if (e == null) continue;
            Vec3d at = e.getLerpedPos(td).add(0, e.getHeight() + 0.6, 0);
            ms.push();
            ms.translate(at.x - eye.x, at.y - eye.y, at.z - eye.z);
            ms.multiply(cam.getRotation());
            ms.scale(0.035f, -0.035f, 0.035f);
            String mark = w.alerted ? "!" : w.level >= 0.3f ? "?" : "◉";
            float x = -tr.getWidth(mark) / 2f;
            var m = ms.peek().getPositionMatrix();
            // Faint, then filling in as they grow aware.
            int a = (int) (90 + 165 * MathHelper.clamp(w.level, 0, 1));
            tr.draw(mark, x, 0, (a << 24) | color(w), false, m, vcp, TextRenderer.TextLayerType.SEE_THROUGH, 0, LightmapTextureManager.MAX_LIGHT_COORDINATE);
            ms.pop();
        }
    }

    static void hud(DrawContext c) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden || mc.currentScreen != null || !mc.player.isSneaking() || WATCHERS.isEmpty()) return;
        int seeing = 0;
        boolean alert = false;
        for (Watcher x : WATCHERS.values()) {
            if (x.sees) seeing++;
            if (x.alerted) alert = true;
        }
        String t = seeing == 0 ? "HIDDEN" : alert ? "DISCOVERED" : "SEEN  ·  " + seeing;
        int col = seeing == 0 ? 0xFF9AB8D8 : alert ? 0xFFE04A3A : 0xFFF2C14E;
        float pulse = seeing > 0 ? (float) (0.8 + 0.2 * Math.sin(System.nanoTime() / 1.5e8)) : 1f;
        int w = c.getScaledWindowWidth(), h = c.getScaledWindowHeight();
        TextRenderer tr = mc.textRenderer;
        String e = "◉";
        c.getMatrices().push();
        c.getMatrices().translate(w / 2f, h / 2f + 14, 0);
        c.getMatrices().scale(1.2f * pulse, 1.2f * pulse, 1);
        c.drawText(tr, e, -tr.getWidth(e) / 2, -4, col, true);
        c.getMatrices().pop();
        c.getMatrices().push();
        c.getMatrices().translate(w / 2f, h / 2f + 26, 0);
        c.getMatrices().scale(0.7f, 0.7f, 1);
        c.drawText(tr, t, -tr.getWidth(t) / 2, 0, col, true);
        c.getMatrices().pop();
    }
}
