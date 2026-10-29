package com.hatmod.client;

import com.hatmod.HatMod;
import com.hatmod.HatSummon;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * 给「虚影」戴一顶**假帽子**。
 *
 * <p>只是把牛仔帽模型画在它头上 —— 虚影身上**没有**真正的帽子物品（有的话
 * {@code HatAbilities} 的状态机会把它当成戴帽者，它就会自己蓄力开火）。
 * 画法照抄 {@link CurioHatRenderer}：把父模型「头」的姿态抄过来，在同一坐标空间里直接渲染。
 */
public class ShadeHatLayer extends RenderLayer<HatSummon, PlayerModel<HatSummon>> {

    private static final ResourceLocation TEXTURE = HatMod.id("textures/models/armor/all_hat_layer_1.png");

    private CowboyHatModel model;

    public ShadeHatLayer(RenderLayerParent<HatSummon, PlayerModel<HatSummon>> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, HatSummon entity,
                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                       float netHeadYaw, float headPitch) {
        if (entity.isInvisible()) {
            return;
        }
        CowboyHatModel hat = model();
        // 这个模型是单独烘出来的，AgeableListModel.young 默认是 true —— 不关掉的话
        // renderToBuffer 会走「幼年生物」分支：整体缩到 0.75 再往下挪一格，帽子就掉到肚子上去了。
        hat.young = false;
        hat.head.copyFrom(getParentModel().head);
        VertexConsumer consumer = buffer.getBuffer(RenderType.armorCutoutNoCull(TEXTURE));
        hat.renderToBuffer(poseStack, consumer, packedLight, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
    }

    private CowboyHatModel model() {
        if (model == null) {
            model = new CowboyHatModel(
                    Minecraft.getInstance().getEntityModels().bakeLayer(CowboyHatModel.LAYER));
        }
        return model;
    }
}
