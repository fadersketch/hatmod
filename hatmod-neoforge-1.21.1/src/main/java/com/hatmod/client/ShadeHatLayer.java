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
 * 把虚影头上那顶**真帽子**（{@code HatSummon.init} 给的「全」）画出来。
 *
 * <p>虚影确实戴着帽子物品，但 {@code HatAbilities} 的状态机会跳过 {@code HatSummon}，
 * 所以它不会自己蓄力开火 —— 这顶帽子只负责护甲和外观。
 * 画法照抄 {@link CurioHatRenderer}：把父模型「头」的姿态抄过来，在同一坐标空间里直接渲染。
 */
public class ShadeHatLayer extends RenderLayer<HatSummon, PlayerModel<HatSummon>> {

    /** 当前这一帧要用的护甲贴图（按头上那顶帽子的颜色走）。 */
    private ResourceLocation texture = HatMod.id("textures/models/armor/all_hat_layer_1.png");

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
        // 读它真正戴的那顶：帽子被取下/打碎之后这里自然什么都不画
        net.minecraft.world.item.ItemStack worn =
                entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD);
        if (!com.hatmod.HatItems.isHat(worn)) {
            return;
        }
        this.texture = HatMod.id("textures/models/armor/"
                + com.hatmod.HatRouteState.textureName(com.hatmod.HatItems.typeOf(worn)) + "_layer_1.png");
        CowboyHatModel hat = model();
        // 这个模型是单独烘出来的，AgeableListModel.young 默认是 true —— 不关掉的话
        // renderToBuffer 会走「幼年生物」分支：整体缩到 0.75 再往下挪一格，帽子就掉到肚子上去了。
        hat.young = false;
        hat.head.copyFrom(getParentModel().head);
        VertexConsumer consumer = buffer.getBuffer(RenderType.armorCutoutNoCull(this.texture));
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
