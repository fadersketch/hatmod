package com.hatmod.client;

import com.hatmod.HatMod;
import com.hatmod.Teto;
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
 * 把「后室 Teto」画成一块**始终面向相机的平面贴图**（和 friends &amp; wine 里的舞者同一个路子）。
 *
 * <p>贴图 {@code textures/entity/teto.png} 是整段动作视频用绿幕抠出来的序列帧：
 * 4042×4056，43 列 × 24 行、每格 94×169、共 **992 帧**（视频 104.4 秒，去掉最后 5.2 秒黑场）；
 * 按每秒 10 帧播放（每帧 2 刻），从出生那一帧开始播，播完从头循环。
 *
 * <p>这里不用 {@code MobRenderer}，因为 Teto 没有立方体模型也没有护甲层 ——
 * 它就是一格四边形，四个角直接按当前帧的 UV 取像素。
 */
public class TetoRenderer extends EntityRenderer<Teto> {


    /**
     * 序列帧排布：整段动作 992 帧，43 列 x 24 行，每格 94x169（贴图 4042x4056）。
     *
     * <p>源视频全长 104.4 秒，最后 5.2 秒是黑场（片尾），已经切掉 —— 留着会让 Teto
     * 变成一块黑方块再重来。
     *
     * <p>每格大小是在「992 帧 × 单张贴图两个方向都不超过 4096」的前提下能给的最大值
     * （≈47 像素/格）。想再清楚只有两条路：少放几帧（掉帧率），或者把 Teto 缩小。
     */
    private static final int COLUMNS = 43;
    private static final int ROWS = 24;
    private static final int FRAME_COUNT = 992;
    /** 每帧占几刻（源视频 10 帧/秒 → 2 刻/帧，992 帧 = 99.2 秒，播完从头循环）。 */
    private static final int FRAME_TICKS = 2;
    /** 每格像素尺寸（整张贴图 4042x4056）。 */
    private static final int CELL_W = 94;
    private static final int CELL_H = 169;

    /**
     * 四边形的实际尺寸（格）。宽度按格子 64:115 的比例算出来，
     * 这样人不会被拉扁；和 {@code HatSummons.TETO} 的判定箱一致。
     */
    private static final float WIDTH = 1.33F;
    private static final float HEIGHT = 2.4F;

    public TetoRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.5F;
    }

    @Override
    public ResourceLocation getTextureLocation(Teto entity) {
        return TetoRenderSettings.texture();
    }

    @Override
    public void render(Teto entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        // 帧率与画质都是客户端可调的（调参器「脑蚀」页 / config/hatmod-client.json）
        int frame = TetoRenderSettings.frameIndex(entity.tickCount);
        int column = frame % COLUMNS;
        int row = frame / COLUMNS;

        // 半像素内缩：相邻帧就贴在旁边，正好取到格子边界的话双线性采样会渗一条边过去。
        // 低分辨率图的 1 像素 = 源图好几像素，所以内缩值跟着画质放大。
        float inset = TetoRenderSettings.uvInset();
        float u0 = (column * CELL_W + inset) / (float) (COLUMNS * CELL_W);
        float u1 = ((column + 1) * CELL_W - inset) / (float) (COLUMNS * CELL_W);
        // 贴图 v=0 在最上面，所以格子的「上边」用 row 的起点
        float v0 = (row * CELL_H + inset) / (float) (ROWS * CELL_H);
        float v1 = ((row + 1) * CELL_H - inset) / (float) (ROWS * CELL_H);

        poseStack.pushPose();
        // 整块四边形贴着相机：相机怎么转，它怎么转
        poseStack.mulPose(this.entityRenderDispatcher.cameraOrientation());
        Matrix4f matrix = poseStack.last().pose();
        VertexConsumer vertices = buffer.getBuffer(RenderType.entityTranslucent(TetoRenderSettings.texture()));

        float half = WIDTH / 2.0F;
        // 左下 → 右下 → 右上 → 左上
        vertex(vertices, matrix, -half, 0.0F, u0, v1, packedLight);
        vertex(vertices, matrix, half, 0.0F, u1, v1, packedLight);
        vertex(vertices, matrix, half, HEIGHT, u1, v0, packedLight);
        vertex(vertices, matrix, -half, HEIGHT, u0, v0, packedLight);

        poseStack.popPose();
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
    }


    private static void vertex(VertexConsumer vertices, Matrix4f matrix,
                               float x, float y, float u, float v, int packedLight) {
        // 顶点格式和光柱一样是 NEW_ENTITY：位置 -> 颜色 -> UV0 -> overlay -> 亮度 -> 法线
        vertices.vertex(matrix, x, y, 0.0F)
                .color(1.0F, 1.0F, 1.0F, 1.0F)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(packedLight)
                .normal(0.0F, 0.0F, 1.0F)
                .endVertex();
    }
}
