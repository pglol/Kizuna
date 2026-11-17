package com.pglol.aotrpg.client;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.feature.ArmorFeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;

/**
 * The dye on what you wear: armour layers (vanilla, Danny's uniforms, anything drawing armour)
 * are drawn through a tint of the dye's colour, softened so leather stays leather.
 */
public final class DyeTint {
    private DyeTint() {}

    public static VertexConsumerProvider wrap(FeatureRenderer<?, ?> feature, Entity e, VertexConsumerProvider vc) {
        if (!(e instanceof PlayerEntity p)) return vc;
        if (!(feature instanceof ArmorFeatureRenderer) && !feature.getClass().getName().toLowerCase(java.util.Locale.ROOT).contains("armor")) return vc;
        String dye = CosmeticFx.worn(p.getUuid(), "dye");
        if (dye.isEmpty() || dye.equals("dye_none")) return vc;
        int col = OutfitLayer.dye(dye, "");
        // Two parts dye to one part the piece's own colour.
        float k = 0.7f;
        float r = 1 - k + k * ((col >> 16) & 255) / 255f, g = 1 - k + k * ((col >> 8) & 255) / 255f, b = 1 - k + k * (col & 255) / 255f;
        return layer -> new Tinted(vc.getBuffer(layer), r, g, b);
    }

    private record Tinted(VertexConsumer in, float r, float g, float b) implements VertexConsumer {
        @Override public VertexConsumer vertex(float x, float y, float z) { in.vertex(x, y, z); return this; }
        @Override public VertexConsumer color(int red, int green, int blue, int alpha) {
            in.color((int) (red * r), (int) (green * g), (int) (blue * b), alpha);
            return this;
        }
        @Override public VertexConsumer texture(float u, float v) { in.texture(u, v); return this; }
        @Override public VertexConsumer overlay(int u, int v) { in.overlay(u, v); return this; }
        @Override public VertexConsumer light(int u, int v) { in.light(u, v); return this; }
        @Override public VertexConsumer normal(float x, float y, float z) { in.normal(x, y, z); return this; }
    }

    @SuppressWarnings("unused")
    private static RenderLayer unused() { return null; }
}
