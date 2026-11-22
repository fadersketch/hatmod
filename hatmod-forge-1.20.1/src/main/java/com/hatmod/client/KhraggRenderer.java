package com.hatmod.client;

import com.hatmod.HatMod;
import com.hatmod.Khragg;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.ResourceLocation;

/**
 * Khragg 的渲染：玩家模型 + 「无敌少侠」皮肤，头顶那顶真「全」帽由
 * {@link ShadeHatLayer} 画（和虚影同一套），身上的黑色皮革套走原版护甲层。
 */
public class KhraggRenderer extends MobRenderer<Khragg, PlayerModel<Khragg>> {

    private static final ResourceLocation TEXTURE = HatMod.id("textures/entity/khragg.png");

    public KhraggRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
        this.addLayer(new ShadeHatLayer<>(this));
        this.addLayer(new HumanoidArmorLayer<Khragg, PlayerModel<Khragg>, HumanoidModel<Khragg>>(this,
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                context.getModelManager()));
    }

    @Override
    public ResourceLocation getTextureLocation(Khragg entity) {
        return TEXTURE;
    }
}
