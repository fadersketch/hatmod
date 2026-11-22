package com.hatmod.client;

import com.hatmod.HatMod;
import com.hatmod.TangJiaqi;
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
 * 唐嘉琦：一块**播放整段原视频**的平面贴图。
 *
 * <p>贴图 {@code textures/entity/tang.png} 就是把素材视频按 5 帧/秒抽出来的 528 帧原样拼起来
 * （不抠像、不去背景）：32 列 × 17 行、每格 192×108（6144×1836）。
 * 播放时每 4 刻一帧，走到片尾回到开头。
 *
 * <p>没有影子 —— 它是一块视频面片，不该在地上投出一个方块影子。
 */
public class TangJiaqiRenderer extends EntityRenderer<TangJiaqi> {

    /** 序列图一共多少帧（视频 105.6 秒 × 5 帧/秒）。 */
    public static final int FRAME_COUNT = 528;
    private static final int COLUMNS = 32;
    private static final int ROWS = 17;
    private static final int CELL_W = 192;
    private static final int CELL_H = 87;

    /** 面片尺寸（格）：16:9，和源视频一致。 */
    private static final float HEIGHT = 1.0875F;
    private static final float WIDTH = 2.4F;

    /** 每帧占几刻：5 帧/秒 = 4 刻一帧。 */
    private static final int FRAME_TICKS = 4;

    private static final ResourceLocation TEXTURE = HatMod.id("textures/entity/tang.png");

    public TangJiaqiRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.0F;
    }

    @Override
    public ResourceLocation getTextureLocation(TangJiaqi entity) {
        return TEXTURE;
    }

    @Override
    public void render(TangJiaqi entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        int frame = (Math.max(0, entity.tickCount) / FRAME_TICKS) % FRAME_COUNT;
        int column = frame % COLUMNS;
        int row = frame / COLUMNS;

        // 半像素内缩：相邻帧就贴在旁边，正好取到格子边界的话双线性采样会渗一条边过去
        float inset = 0.5F;
        float u0 = (column * CELL_W + inset) / (float) (COLUMNS * CELL_W);
        float u1 = ((column + 1) * CELL_W - inset) / (float) (COLUMNS * CELL_W);
        // 这条绘制路径画出来是背面，把 U 掉个个儿才是正的（字幕才不会左右反）
        float swap = u0;
        u0 = u1;
        u1 = swap;
        float v0 = (row * CELL_H + inset) / (float) (ROWS * CELL_H);
        float v1 = ((row + 1) * CELL_H - inset) / (float) (ROWS * CELL_H);

        poseStack.pushPose();
        poseStack.mulPose(this.entityRenderDispatcher.cameraOrientation());
        Matrix4f matrix = poseStack.last().pose();
        VertexConsumer vertices = buffer.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));

        float half = WIDTH / 2.0F;
        vertex(vertices, matrix, -half, 0.0F, u0, v1, packedLight);
        vertex(vertices, matrix, half, 0.0F, u1, v1, packedLight);
        vertex(vertices, matrix, half, HEIGHT, u1, v0, packedLight);
        vertex(vertices, matrix, -half, HEIGHT, u0, v0, packedLight);

        poseStack.popPose();
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
    }

    private static void vertex(VertexConsumer vertices, Matrix4f matrix,
                               float x, float y, float u, float v, int packedLight) {
        vertices.vertex(matrix, x, y, 0.0F)
                .color(1.0F, 1.0F, 1.0F, 1.0F)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(packedLight)
                .normal(0.0F, 0.0F, 1.0F)
                .endVertex();
    }
}
