package com.pglol.aotrpg.client;

import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.util.Identifier;

/**
 * Glowing shapes that hold up under shader packs. They're drawn as the game draws a spider's or an
 * enderman's eyes (shaders treat that as light itself: full bright, never washed out or lost in
 * the fog), additively, with each colour scaled by its alpha so soft edges still fade to nothing.
 * Under a shader pack everything is turned up a little more, since packs darken and bloom.
 */
public final class Glow {
    private Glow() {}

    private static final Identifier WHITE = Identifier.of("aot_rpg", "textures/misc/white.png");

    /** A buffer to draw glowing position+colour shapes into (vertex(...).color(...) per vertex). */
    public static VertexConsumer buffer(VertexConsumerProvider vc) {
        return new Writer(vc.getBuffer(RenderLayer.getEyes(WHITE)), ShaderCompat.packInUse() ? 1.7f : 1f);
    }

    private record Writer(VertexConsumer to, float boost) implements VertexConsumer {
        @Override
        public VertexConsumer vertex(float x, float y, float z) {
            to.vertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            float k = Math.min(1f, a / 255f * boost);
            to.color((int) (r * k), (int) (g * k), (int) (b * k), 255);
            to.texture(0.5f, 0.5f);
            to.overlay(OverlayTexture.DEFAULT_UV);
            to.light(0xF000F0);
            to.normal(0, 1, 0);
            return this;
        }

        @Override
        public VertexConsumer texture(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer overlay(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer light(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            return this;
        }
    }
}
