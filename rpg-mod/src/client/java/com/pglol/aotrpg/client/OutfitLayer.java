package com.pglol.aotrpg.client;

import net.minecraft.client.model.Dilation;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

import java.util.Map;

/**
 * Clothes over the uniform: a jacket, a greatcoat, a scarf, a hooded cape... drawn as a layer
 * just outside the body and the uniform over it (so they move with every limb), in the colour they're dyed.
 */
public final class OutfitLayer extends FeatureRenderer<AbstractClientPlayerEntity, PlayerEntityModel<AbstractClientPlayerEntity>> {
    private final PlayerEntityModel<AbstractClientPlayerEntity> wide, slim;

    /** Each piece's own colour when it isn't dyed. */
    private static final Map<String, Integer> NATURAL = Map.ofEntries(
        Map.entry("outfit_scout_jacket", 0x8A6A44), Map.entry("outfit_garrison_coat", 0x6E6650), Map.entry("outfit_mp_coat", 0x3A3F48),
        Map.entry("outfit_marley_tunic", 0x6A6A48), Map.entry("outfit_officer_sash", 0xA0202A), Map.entry("outfit_hooded_cape", 0x2F4A2A),
        Map.entry("outfit_scarf", 0x9A1A1A), Map.entry("outfit_civilian_vest", 0x5A4632), Map.entry("outfit_bandana", 0x2A3A6A),
        Map.entry("outfit_armband", 0xEDE3C8),
        // The 1 of 1s are painted in their own colours.
        Map.entry("outfit_sp_robe", 0xFFFFFF), Map.entry("outfit_uch", 0xFFFFFF));

    private static int dyeTint = 0x6AC8FF;

    public OutfitLayer(FeatureRendererContext<AbstractClientPlayerEntity, PlayerEntityModel<AbstractClientPlayerEntity>> context) {
        super(context);
        wide = make(false);
        slim = make(true);
    }

    private static PlayerEntityModel<AbstractClientPlayerEntity> make(boolean thin) {
        var data = TexturedModelData.of(PlayerEntityModel.getTexturedModelData(new Dilation(1.12f), thin), 64, 64);
        PlayerEntityModel<AbstractClientPlayerEntity> m = new PlayerEntityModel<>(data.createModel(), thin);
        m.hat.visible = m.jacket.visible = m.leftSleeve.visible = m.rightSleeve.visible = m.leftPants.visible = m.rightPants.visible = false;
        return m;
    }

    public static int dye(String dye, String outfit) {
        if (dye.equals("dye_uch")) return 0x6A5AA8;
        if (dye.equals("dye_sp")) return Showcase.mix(dyeTint, 0xFFFFFF, 0.35f);
        for (String[] d : com.pglol.aotrpg.Cosmetics.DYES) if (d[0].equals(dye) && !d[0].equals("dye_none")) return Integer.parseInt(d[2], 16);
        return NATURAL.getOrDefault(outfit, 0xC8C8C8);
    }

    @Override
    public void render(MatrixStack ms, VertexConsumerProvider vc, int light, AbstractClientPlayerEntity p, float limbAngle, float limbDistance,
                       float tickDelta, float animationProgress, float headYaw, float headPitch) {
        if (p.isInvisible()) return;
        String outfit = CosmeticFx.worn(p.getUuid(), "outfit");
        if (outfit.isEmpty() || outfit.equals("outfit_none")) return;
        boolean thin = p.getSkinTextures().model() == net.minecraft.client.util.SkinTextures.Model.SLIM;
        PlayerEntityModel<AbstractClientPlayerEntity> m = thin ? slim : wide;
        getContextModel().copyBipedStateTo(m);
        dyeTint = OneOfOne.tint(p.getUuid());
        int col = 0xFF000000 | dye(CosmeticFx.worn(p.getUuid(), "dye"), outfit);
        Identifier tex = Identifier.of("aot_rpg", "textures/entity/outfit/" + outfit + ".png");
        m.render(ms, vc.getBuffer(RenderLayer.getEntityTranslucent(tex)), light, OverlayTexture.DEFAULT_UV, col);
        // The robe's trim glows its owner's colour, breathing slowly.
        if (outfit.equals("outfit_sp_robe")) {
            float k = 0.75f + 0.25f * net.minecraft.util.math.MathHelper.sin((p.age + tickDelta) / 9f);
            int g = 0xFF000000 | Showcase.mix(0, dyeTint, k);
            Identifier glow = Identifier.of("aot_rpg", "textures/entity/outfit/outfit_sp_robe_glow.png");
            m.render(ms, vc.getBuffer(RenderLayer.getEyes(glow)), 0xF000F0, OverlayTexture.DEFAULT_UV, g);
        }
    }
}
