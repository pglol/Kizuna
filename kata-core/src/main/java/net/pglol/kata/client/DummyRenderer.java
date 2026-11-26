package net.pglol.kata.client;

import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.util.Identifier;
import net.pglol.kata.Dummy;

/** The training dummy: a person-shaped figure (the default skin) with the full Kata rig. */
public class DummyRenderer extends BipedEntityRenderer<Dummy, PlayerEntityModel<Dummy>> {
    private static final java.util.Map<Integer, net.minecraft.util.math.Vec3d> SHOWN = new java.util.HashMap<>();
    private static final java.util.Map<Integer, Long> LAST = new java.util.HashMap<>();
    private static final Identifier SKIN = Identifier.ofVanilla("textures/entity/player/wide/steve.png");

    public DummyRenderer(EntityRendererFactory.Context ctx) {
        super(ctx, new PlayerEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER), false), 0.5f);
    }

    @Override
    public void render(Dummy d, float yaw, float td, net.minecraft.client.util.math.MatrixStack ms, net.minecraft.client.render.VertexConsumerProvider vcp, int light) {
        // Its whole body bends like a player's: guard, strikes, reactions, going down and getting up.
        Rig.current = DummyAnim.pose(d, td);
        // Smooth out the server's small position corrections (the game moves it ahead on its velocity,
        // then the server puts it back): a quick slide, never a snap. Real jumps, and the ragdoll (which
        // is placed in the world itself), are left exact.
        net.minecraft.util.math.Vec3d at = d.getLerpedPos(td);
        net.minecraft.util.math.Vec3d shown = SHOWN.get(d.getId());
        long now = System.nanoTime();
        Long last = LAST.get(d.getId());
        float dt = last == null ? 0 : Math.min(0.1f, (now - last) / 1e9f);
        LAST.put(d.getId(), now);
        if (shown == null || shown.squaredDistanceTo(at) > 4 || d.down() > 0) shown = at;
        else shown = shown.lerp(at, 1 - Math.exp(-dt * 14));
        SHOWN.put(d.getId(), shown);
        if (SHOWN.size() > 128) {
            SHOWN.clear();
            LAST.clear();
        }
        ms.push();
        ms.translate(shown.x - at.x, shown.y - at.y, shown.z - at.z);
        try {
            super.render(d, yaw, td, ms, vcp, light);
        } finally {
            ms.pop();
            Rig.current = null;
        }
    }

    @Override
    public Identifier getTexture(Dummy entity) {
        return SKIN;
    }
}
