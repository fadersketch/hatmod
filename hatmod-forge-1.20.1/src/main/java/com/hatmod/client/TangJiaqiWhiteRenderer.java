package com.hatmod.client;

import com.hatmod.HatMod;
import com.hatmod.TangJiaqiWhite;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/**
 * 唐嘉琦（白色）：待机 / 发射各一张的平面贴图（黑底源 AI 抠像，白衣服保得住）。
 *
 * <p>两张都是竖版：待机 798×1310、发射 1192×1108 —— 面片高固定 2.2 格，宽按各自比例；
 * 放光柱时切到「发射」那张（实体的 beaming 同步值，见 {@link TangJiaqiWhite#isBeaming}）。
 *
 * <p>没有影子 —— 它是一块面片，不该在地上投出一个方块影子。
 */
public class TangJiaqiWhiteRenderer extends EntityRenderer<TangJiaqiWhite> {

    /** 面片高（格）。 */
    private static final float HEIGHT = 2.2F;
    /** 待机 / 发射两张的面片宽（格）＝ 2.2 × 各自贴图宽高比。 */
    private static final float IDLE_WIDTH = 2.2F * 798.0F / 1310.0F;
    private static final float FIRE_WIDTH = 2.2F * 1192.0F / 1108.0F;

    private static final ResourceLocation IDLE = HatMod.id("textures/entity/tang_white_idle.png");
    private static final ResourceLocation FIRE = HatMod.id("textures/entity/tang_white_fire.png");

    public TangJiaqiWhiteRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.0F;
    }

    @Override
    public ResourceLocation getTextureLocation(TangJiaqiWhite entity) {
        return entity.isBeaming() ? FIRE : IDLE;
    }

    @Override
    public void render(TangJiaqiWhite entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        // 瞬移演出里她是「不在」的（受伤 -> 贴图消失 -> 珍珠飞 -> 终点重现），别把面片画出来
        if (entity.isInvisible()) {
            return;
        }
        boolean firing = entity.isBeaming();
        ResourceLocation texture = firing ? FIRE : IDLE;
        float half = (firing ? FIRE_WIDTH : IDLE_WIDTH) / 2.0F;

        // 受击反馈：和原版生物一样闪一下红（hurtTime 由 hurt() 结算伤害时写入）
        float hurt = entity.hurtTime - partialTick;
        float tintG = 1.0F;
        if (hurt > 0.0F) {
            tintG = 1.0F - Math.min(1.0F, hurt / 10.0F) * 0.55F;
        }
        float tintB = tintG;

        poseStack.pushPose();
        poseStack.mulPose(this.entityRenderDispatcher.cameraOrientation());
        Matrix4f matrix = poseStack.last().pose();
        VertexConsumer vertices = buffer.getBuffer(RenderType.entityCutoutNoCull(texture));

        // 这条绘制路径画出来是背面，把 U 掉个个儿才是正的（和 TangJiaqiRenderer 同一套）
        vertex(vertices, matrix, -half, 0.0F, 1.0F, 1.0F, packedLight, tintG, tintB);
        vertex(vertices, matrix, half, 0.0F, 0.0F, 1.0F, packedLight, tintG, tintB);
        vertex(vertices, matrix, half, HEIGHT, 0.0F, 0.0F, packedLight, tintG, tintB);
        vertex(vertices, matrix, -half, HEIGHT, 1.0F, 0.0F, packedLight, tintG, tintB);

        poseStack.popPose();
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
    }

    private static void vertex(VertexConsumer vertices, Matrix4f matrix,
                               float x, float y, float u, float v, int packedLight,
                               float tintG, float tintB) {
        vertices.vertex(matrix, x, y, 0.0F)
                .color(1.0F, tintG, tintB, 1.0F)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(packedLight)
                .normal(0.0F, 0.0F, 1.0F)
                .endVertex();
    }
}
