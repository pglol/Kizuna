package net.pglol.kata.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.pglol.kata.rig.Pose;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Afterimages: glowing, translucent copies of a body left where it just was, in the pose it had, fading
 * out. A parry bursts a bright one out of the parrier (swelling as it fades) and throws a red one off
 * whoever was parried; an evade leaves a trail of them along where the strike went through; a dodge
 * leaves a faint one behind. Drawn through the same rig as the body, so every bend is there.
 */
public final class Afterimages {
    private Afterimages() {}

    /** Where a body was and how it stood, a moment ago. */
    private record Snap(Vec3d pos, float bodyYaw, float headYaw, float pitch, Pose pose, long at) { }

    private record Ghost(int entity, Snap snap, long born, float life, int rgb, float alpha, float grow, Vec3d drift) { }

    private static final Map<Integer, ArrayDeque<Snap>> HISTORY = new HashMap<>();
    private static final List<Ghost> GHOSTS = new ArrayList<>();

    /** Remember how a body stands this frame (called as it's posed; a few dozen ms apart). */
    static void record(LivingEntity e, Pose pose, float td) {
        ArrayDeque<Snap> h = HISTORY.computeIfAbsent(e.getId(), k -> new ArrayDeque<>());
        long now = System.nanoTime();
        if (!h.isEmpty() && now - h.peekLast().at < 25_000_000L) return;
        float body = net.minecraft.util.math.MathHelper.lerpAngleDegrees(td, e.prevBodyYaw, e.bodyYaw);
        float head = net.minecraft.util.math.MathHelper.wrapDegrees(net.minecraft.util.math.MathHelper.lerpAngleDegrees(td, e.prevHeadYaw, e.headYaw) - body);
        h.addLast(new Snap(e.getLerpedPos(td), body, head, e.getPitch(td), pose.copy(), now));
        while (h.size() > 12) h.removeFirst();
        if (HISTORY.size() > 128) HISTORY.clear();
    }

    /** The snapshot from about `ago` seconds back (or the oldest kept). */
    private static Snap back(int id, float ago) {
        ArrayDeque<Snap> h = HISTORY.get(id);
        if (h == null || h.isEmpty()) return null;
        long want = System.nanoTime() - (long) (ago * 1e9);
        Snap best = h.peekFirst();
        for (Snap s : h) if (s.at <= want) best = s;
        return best;
    }

    private static void add(int id, float ago, float life, int rgb, float alpha, float grow, Vec3d drift) {
        Snap s = back(id, ago);
        if (s != null) GHOSTS.add(new Ghost(id, s, System.nanoTime(), life, rgb, alpha, grow, drift));
        if (GHOSTS.size() > 64) GHOSTS.remove(0);
    }

    /** A parry: a bright burst out of the parrier, swelling as it fades, and two quick echoes. */
    public static void parry(int id) {
        add(id, 0, 0.45f, 0xBFF4FF, 0.75f, 0.18f, Vec3d.ZERO);
        add(id, 0.05f, 0.3f, 0x7FD8FF, 0.45f, 0.08f, Vec3d.ZERO);
        add(id, 0.1f, 0.25f, 0x7FD8FF, 0.3f, 0.04f, Vec3d.ZERO);
    }

    /** Parried: a red ghost thrown back off the attacker. */
    public static void parried(int id, Vec3d back) {
        add(id, 0, 0.4f, 0xFF4A3A, 0.55f, 0.05f, back.multiply(0.9));
    }

    /** An evade: a trail of pale ghosts where the body was as the strike went through. */
    public static void evade(int id) {
        for (int i = 0; i < 4; i++) add(id, 0.03f + i * 0.04f, 0.5f - i * 0.06f, 0xCFE6FF, 0.55f - i * 0.1f, 0, Vec3d.ZERO);
    }

    /** A dodge: one faint ghost left behind. */
    public static void dodge(int id) {
        add(id, 0.02f, 0.3f, 0xCFE6FF, 0.3f, 0, Vec3d.ZERO);
    }

    /** Draw them all (after the world's entities). */
    static void render(MatrixStack ms, VertexConsumerProvider vcp, Vec3d cam) {
        if (GHOSTS.isEmpty()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return;
        long now = System.nanoTime();
        for (Iterator<Ghost> it = GHOSTS.iterator(); it.hasNext(); ) {
            Ghost g = it.next();
            float age = (now - g.born) / 1e9f, u = age / g.life;
            if (u >= 1) {
                it.remove();
                continue;
            }
            var e = mc.world.getEntityById(g.entity);
            PlayerEntityModel<?> model;
            Identifier tex;
            if (e instanceof AbstractClientPlayerEntity pl && mc.getEntityRenderDispatcher().getRenderer(pl) instanceof net.minecraft.client.render.entity.PlayerEntityRenderer r) {
                model = r.getModel();
                tex = pl.getSkinTextures().texture();
            } else if (e instanceof net.pglol.kata.Dummy d && mc.getEntityRenderDispatcher().getRenderer(d) instanceof DummyRenderer r) {
                model = r.getModel();
                tex = Identifier.ofVanilla("textures/entity/player/wide/steve.png");
            } else continue;
            float a = g.alpha * (1 - u) * (1 - u);
            float scale = 1 + g.grow * u;
            Snap s = g.snap;
            Vec3d at = s.pos.add(g.drift.multiply(u)).subtract(cam);
            ms.push();
            ms.translate(at.x, at.y, at.z);
            ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180 - s.bodyYaw));
            // Swell from the middle of the body, not the feet.
            ms.translate(0, 0.9, 0);
            ms.scale(scale, scale, scale);
            ms.translate(0, -0.9, 0);
            ms.scale(-1, -1, 1);
            ms.scale(0.9375f, 0.9375f, 0.9375f);
            ms.translate(0, -1.501, 0);
            Rig.current = s.pose;
            try {
                @SuppressWarnings({"unchecked", "rawtypes"})
                PlayerEntityModel<LivingEntity> m = (PlayerEntityModel) model;
                m.sneaking = false;
                m.riding = false;
                m.child = false;
                m.setAngles((LivingEntity) e, 0, 0, e.age, s.headYaw, s.pitch);
                int col = ((int) (a * 255) << 24) | g.rgb;
                m.render(ms, vcp.getBuffer(RenderLayer.getEntityTranslucentEmissive(tex)), 0xF000F0, OverlayTexture.DEFAULT_UV, col);
            } finally {
                Rig.current = null;
                ms.pop();
            }
        }
    }
}
