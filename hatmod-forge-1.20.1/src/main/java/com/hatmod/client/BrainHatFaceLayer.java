package com.hatmod.client;

import com.hatmod.HatItems;
import com.hatmod.HatMod;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;

/**
 * 「脑蚀」帽冠**正面**贴的那张像：把 {@code textures/entity/brain_hat_face.png}
 * 画在帽冠前脸的位置上。
 *
 * <p>为什么不直接画进护甲贴图：帽冠是一个 7×5×7 的方块，前脸在贴图里就是
 * 7×5 个像素（模型坐标 1 单位 = 1 贴图像素），人脸到那儿只剩一团颜色。
 * 所以这里改成一层独立的 {@link RenderLayer}：跟着头的姿态走，自己绑一张 **1024×1024** 的
 * 高清贴图，清晰度和护甲贴图（128×64）完全无关。
 *
 * <p>坐标沿用玩家模型：头朝 -Z，帽冠前脸在 z = -3.5、y 从 -13 到 -8（1 单位 = 1/16 格）。
 */
public class BrainHatFaceLayer<T extends LivingEntity, M extends HumanoidModel<T>> extends RenderLayer<T, M> {

    private static final ResourceLocation TEXTURE = HatMod.id("textures/entity/brain_hat_face.png");

    /**
     * 前脸的实际可视范围。
     *
     * <p>模型坐标里 **Y 向下**（和方块模型一致），所以「上沿」是更小的数；
     * 帽檐是一块 y = -8.5 ~ -7.5 的实心板，帽冠底下那半格插进帽檐里，
     * 画像只能用到 -8.5 以下。贴图是 1024×1024 的正方形（高清原图），所以四边形按正方形给，
     * 人像不会被压扁；比帽冠正面窄一点，两侧留出黑色的帽体。
     */
    private static final float Y_TOP = -12.95F;
    private static final float Y_BOTTOM = -8.55F;
    private static final float HALF_WIDTH = (Y_BOTTOM - Y_TOP) * 0.5F;
    /** 比帽冠前脸（z = -3.5）再往外挪一点，避免 z-fighting。 */
    private static final float Z = -3.6F;

    public BrainHatFaceLayer(RenderLayerParent<T, M> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, T entity,
                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                       float netHeadYaw, float headPitch) {
        if (entity.isInvisible()) {
            return;
        }
        // 只认护甲栏：饰品栏里的帽子和帽子本体一样是**不渲染**的，画像自然也不该出现
        if (!entity.getItemBySlot(EquipmentSlot.HEAD).is(HatItems.BRAIN_HAT.get())) {
            return;
        }

        poseStack.pushPose();
        // 跟帽子一样跟着头转
        this.getParentModel().head.translateAndRotate(poseStack);
        PoseStack.Pose pose = poseStack.last();
        VertexConsumer vertices = buffer.getBuffer(RenderType.entityTranslucent(TEXTURE));

        // UV 照抄原版方块 NORTH 面：模型 Y 向下，所以「上沿」（-12.95）对贴图 v=0、
        // 「下沿」对 v=1；x 按 -X→u=0、+X→u=1。写反了整张像会上下颠倒。
        vertex(vertices, pose, -HALF_WIDTH, Y_TOP, 0.0F, 0.0F, packedLight);
        vertex(vertices, pose, HALF_WIDTH, Y_TOP, 1.0F, 0.0F, packedLight);
        vertex(vertices, pose, HALF_WIDTH, Y_BOTTOM, 1.0F, 1.0F, packedLight);
        vertex(vertices, pose, -HALF_WIDTH, Y_BOTTOM, 0.0F, 1.0F, packedLight);

        poseStack.popPose();
    }

    private static void vertex(VertexConsumer vertices, PoseStack.Pose pose,
                               float x, float y, float u, float v, int packedLight) {
        // 模型坐标是 1/16 格，写进去之前要自己缩
        vertices.vertex(pose.pose(), x / 16.0F, y / 16.0F, Z / 16.0F)
                .color(1.0F, 1.0F, 1.0F, 1.0F)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(packedLight)
                .normal(pose.normal(), 0.0F, 0.0F, -1.0F)
                .endVertex();
    }
}
