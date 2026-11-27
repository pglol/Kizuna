package net.pglol.kata.client;

import net.minecraft.client.model.ModelPart;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

import java.util.HashMap;
import java.util.Map;

/**
 * The striking limb lights up, as in Absolver: it glows as the strike winds up, brightest at contact,
 * fading as it's drawn back. Lights glow a cool blue, heavies a fiery orange that builds while charged
 * (gold at full charge). Drawn as a slightly larger glowing shell over the limb, for everyone watching.
 */
public final class StrikeGlow extends FeatureRenderer<AbstractClientPlayerEntity, PlayerEntityModel<AbstractClientPlayerEntity>> {
    public static final int RIGHT_ARM = 0, LEFT_ARM = 1, RIGHT_LEG = 2, LEFT_LEG = 3;
    private static final Identifier WHITE = Identifier.ofVanilla("textures/misc/white.png");

    private record Glow(int limb, float alpha, int rgb) { }

    private static final Map<Integer, Glow> GLOWS = new HashMap<>();

    public StrikeGlow(FeatureRendererContext<AbstractClientPlayerEntity, PlayerEntityModel<AbstractClientPlayerEntity>> ctx) {
        super(ctx);
    }

    /** No strike going for this one this frame. */
    static void clear(int id) {
        GLOWS.remove(id);
    }

    /** This frame's glow on someone's striking limb: k is the strike's progress (negative loading), wind a heavy's charge. */
    static void set(int id, int limb, boolean heavy, float k, float wind, float power) {
        float a;
        if (wind > 0) a = 0.3f + 0.45f * wind + 0.1f * MathHelper.sin((System.nanoTime() % 6_283_185_307L) / 1e9f * 9);
        else a = k < 0 ? 0.25f + 0.5f * Math.min(1, -k / 0.4f) : 0.75f * Math.max(0, k) + 0.05f;
        if (a < 0.04f) {
            GLOWS.remove(id);
            return;
        }
        float charge = wind > 0 ? wind : power;
        int rgb = !heavy ? 0x6FD2FF : charge >= 0.98f ? 0xFFD54A : 0xFF6A26;
        GLOWS.put(id, new Glow(limb, MathHelper.clamp(a, 0, 0.85f), rgb));
        if (GLOWS.size() > 256) GLOWS.clear();
    }

    @Override
    public void render(MatrixStack ms, VertexConsumerProvider vcp, int light, AbstractClientPlayerEntity e, float limbAngle, float limbDistance,
                       float tickDelta, float animationProgress, float headYaw, float headPitch) {
        Glow g = GLOWS.get(e.getId());
        if (g == null || e.isInvisible()) return;
        PlayerEntityModel<AbstractClientPlayerEntity> model = getContextModel();
        ModelPart part = switch (g.limb) {
            case RIGHT_ARM -> model.rightArm;
            case LEFT_ARM -> model.leftArm;
            case RIGHT_LEG -> model.rightLeg;
            default -> model.leftLeg;
        };
        VertexConsumer vc = vcp.getBuffer(RenderLayer.getEntityTranslucentEmissive(WHITE));
        int color = ((int) (g.alpha * 255) << 24) | g.rgb;
        // A shell a touch bigger than the limb, scaled about its joint.
        float px = part.pivotX / 16f, py = part.pivotY / 16f, pz = part.pivotZ / 16f;
        for (float grow : new float[] {1.12f, 1.25f}) {
            ms.push();
            ms.translate(px, py, pz);
            ms.scale(grow, grow, grow);
            ms.translate(-px, -py, -pz);
            int c = grow > 1.2f ? ((int) (g.alpha * 0.4f * 255) << 24) | g.rgb : color;
            part.render(ms, vc, 0xF000F0, OverlayTexture.DEFAULT_UV, c);
            ms.pop();
        }
    }
}
