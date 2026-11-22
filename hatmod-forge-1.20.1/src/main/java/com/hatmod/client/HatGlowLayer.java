package com.hatmod.client;

import com.hatmod.HatItems;
import com.hatmod.HatMod;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;

/**
 * 光柱照射期间，让**头上的帽子本体**自己发亮。
 *
 * <p>帽子平时是普通护甲外观：黑帽就是一块纯黑，光柱那么亮、它一点反应都没有，
 * 观感上像是「光照在别人身上」。这一层在 {@link BeamTargets#isBeaming(int) 正在照射}
 * 的时候，用同一顶 {@link CowboyHatModel} 再画两遍加色（{@code energy_swirl}）：
 * <ul>
 *   <li>原尺寸一遍：把帽子本身提亮（黑帽会从纯黑抬成灰白，看得出「在发光」）；</li>
 *   <li>放大 12% 一遍：只在轮廓外露出一圈光晕，像帽子边缘漏出来的光。</li>
 * </ul>
 * 亮度按时间缓慢呼吸，不是死亮。
 *
 * <p><b>为什么用一张纯白贴图</b>：加色混合是「贴图颜色 × 顶点颜色」再叠到画面上，
 * 拿帽子自己的贴图去叠，黑色乘出来还是黑色 —— 黑帽永远不会亮。换成纯白贴图后
 * 采样到的颜色恒为 1，模型就变成一块「帽子形状的白光」，什么颜色的帽子都能亮。
 *
 * <p>模型用的是**头部姿态为零**的那份烘焙结果：头部的位移 / 旋转已经由
 * {@link net.minecraft.client.model.geom.ModelPart#translateAndRotate} 提前压进
 * {@code PoseStack} 了，模型内部不能再转一次（转两遍帽子会飞到天上）。
 */
public class HatGlowLayer<T extends LivingEntity, M extends HumanoidModel<T>> extends RenderLayer<T, M> {

    /** 纯白贴图 + 原版加色渲染类型（光影模组认得它，自己造的类型会被跳过）。 */
    private static final RenderType GLOW =
            RenderType.energySwirl(HatMod.id("textures/entity/hat_glow.png"), 0.0F, 0.0F);

    /** 放大的中心：帽檐的高度（模型单位 8 = 0.5 格），放大时帽子不会整体往下掉。 */
    private static final float PIVOT_Y = 8.0F / 16.0F;

    private CowboyHatModel model;

    public HatGlowLayer(RenderLayerParent<T, M> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, T entity,
                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                       float netHeadYaw, float headPitch) {
        if (entity.isInvisible() || !BeamTargets.isBeaming(entity.getId())) {
            return;
        }
        // 只认护甲栏：饰品栏里的帽子本来就不渲染，别凭空冒出一顶发光的
        if (!HatItems.isHat(entity.getItemBySlot(EquipmentSlot.HEAD))) {
            return;
        }
        // 呼吸：约 1.8 秒一个来回，亮起来也不至于晃眼
        float pulse = 0.5F + 0.5F * (float) Math.sin((entity.tickCount + partialTick) * 0.35F);

        poseStack.pushPose();
        // 进「头部坐标系」：帽冠 y = -13 ~ -8、帽檐 y = -8.5 ~ -7.5（1 单位 = 1/16 格）
        this.getParentModel().head.translateAndRotate(poseStack);
        // 本体提亮
        pass(poseStack, buffer, 1.0F, 0.10F + 0.06F * pulse);
        // 帽檐外那一圈光晕
        pass(poseStack, buffer, 1.12F, 0.16F + 0.12F * pulse);
        poseStack.popPose();
    }

    /** 一遍加色绘制：{@code brightness} 直接就是「加多少白」。 */
    private void pass(PoseStack poseStack, MultiBufferSource buffer, float scale, float brightness) {
        if (brightness <= 0.0F) {
            return;
        }
        poseStack.pushPose();
        poseStack.translate(0.0F, -PIVOT_Y, 0.0F);
        poseStack.scale(scale, scale, scale);
        poseStack.translate(0.0F, PIVOT_Y, 0.0F);
        CowboyHatModel hat = model();
        // 这份模型是单独烘出来的，AgeableListModel.young 默认是 true —— 不关掉会走
        // 「幼年生物」分支，整体缩到 0.75 再往下挪一格，光晕就跑到肚子上了。
        hat.young = false;
        VertexConsumer vertices = buffer.getBuffer(GLOW);
        hat.renderToBuffer(poseStack, vertices, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                brightness, brightness, brightness, 1.0F);
        poseStack.popPose();
    }

    private CowboyHatModel model() {
        if (model == null) {
            model = new CowboyHatModel(
                    Minecraft.getInstance().getEntityModels().bakeLayer(CowboyHatModel.LAYER));
        }
        return model;
    }
}
