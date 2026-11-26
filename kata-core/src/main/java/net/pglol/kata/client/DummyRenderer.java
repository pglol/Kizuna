package net.pglol.kata.client;

import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.util.Identifier;
import net.pglol.kata.Dummy;

/** The training dummy: a person-shaped figure (the default skin) with the full Kata rig. */
public class DummyRenderer extends BipedEntityRenderer<Dummy, PlayerEntityModel<Dummy>> {
    private static final Identifier SKIN = Identifier.ofVanilla("textures/entity/player/wide/steve.png");

    public DummyRenderer(EntityRendererFactory.Context ctx) {
        super(ctx, new PlayerEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER), false), 0.5f);
    }

    @Override
    public void render(Dummy d, float yaw, float td, net.minecraft.client.util.math.MatrixStack ms, net.minecraft.client.render.VertexConsumerProvider vcp, int light) {
        // Its whole body bends like a player's: guard, strikes, reactions, going down and getting up.
        Rig.current = DummyAnim.pose(d, td);
        try {
            super.render(d, yaw, td, ms, vcp, light);
        } finally {
            Rig.current = null;
        }
    }

    @Override
    public Identifier getTexture(Dummy entity) {
        return SKIN;
    }
}
