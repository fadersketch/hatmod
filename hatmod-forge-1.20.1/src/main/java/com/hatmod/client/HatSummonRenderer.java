package com.hatmod.client;

import com.hatmod.HatMod;
import com.hatmod.HatSummon;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;

/**
 * 把「虚影」画成一个**半透明的影子剪影**。
 *
 * <p>用的是玩家模型（大小、姿态和戴帽者一致），但贴图是一张整张同色的暗紫色半透明图
 * （{@code textures/entity/shade.png}），于是渲染出来就是一个「人形黑影」。
 * 渲染类型换成 {@link RenderType#entityTranslucent}（不剔除背面），贴图自带的 alpha 直接让
 * 模型半透明 —— 不需要自定义顶点管线。
 *
 * <p>虚影不戴帽子（不戴帽才不会被 {@code HatAbilities} 的状态机当成戴帽者，
 * 也就不会发射光柱），所以这里不需要护甲层。
 */
public class HatSummonRenderer extends MobRenderer<HatSummon, PlayerModel<HatSummon>> {

    private static final ResourceLocation SHADE_TEXTURE = HatMod.id("textures/entity/shade.png");

    public HatSummonRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.3F);
        // 假帽子：只画模型，虚影身上没有真正的帽子物品
        this.addLayer(new ShadeHatLayer(this));
    }

    @Override
    public ResourceLocation getTextureLocation(HatSummon entity) {
        return SHADE_TEXTURE;
    }

    @Override
    protected RenderType getRenderType(HatSummon entity, boolean bodyVisible, boolean translucent, boolean glowing) {
        if (!bodyVisible && !translucent) {
            return null;
        }
        return RenderType.entityTranslucent(SHADE_TEXTURE);
    }
}
