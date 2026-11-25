package net.pglol.kata.client;

import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.util.Identifier;
import net.pglol.kata.Dummy;

/** The training dummy: a plain person-shaped figure (the default skin). */
public class DummyRenderer extends BipedEntityRenderer<Dummy, PlayerEntityModel<Dummy>> {
    private static final Identifier SKIN = Identifier.ofVanilla("textures/entity/player/wide/steve.png");

    public DummyRenderer(EntityRendererFactory.Context ctx) {
        super(ctx, new PlayerEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER), false), 0.5f);
    }

    @Override
    public Identifier getTexture(Dummy entity) {
        return SKIN;
    }
}
