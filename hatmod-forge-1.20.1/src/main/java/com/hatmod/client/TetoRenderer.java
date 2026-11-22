package com.hatmod.client;

import com.hatmod.BrainrotBeats;
import com.hatmod.BrainrotCycle;
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
 * 3840×4608，24 列 × 16 行、每格 160×288、共 **373 个姿势**
 * （重新从 720×1296 的清晰源视频抠了一遍，每格比原来的 94×169 大一倍多；
 * 序列按独有姿势出图：「前奏 183 个 + 循环 190 个」，见 TetoRenderSettings）；
 * 按每秒 10 帧播放（每帧 2 刻），从出生那一帧开始播，播完从头循环。
 *
 * <p>动作有三套：{@code entity.actionIndex()} 选贴图（0 = 原来那套，1 / 2 = 另外两段舞），
 * 空中右键切换；曲子播放期间服务端会把它强制归零。
 *
 * <p>这里不用 {@code MobRenderer}，因为 Teto 没有立方体模型也没有护甲层 ——
 * 它就是一格四边形，四个角直接按当前帧的 UV 取像素。
 */
public class TetoRenderer extends EntityRenderer<Teto> {


    /*
     * 序列帧的排布 / 帧数 / 贴图现在有**四套**（动作 0 = 原来那套，动作 1 = 「Let's get it!!」那套，
     * 动作 2 = 第三段舞，动作 3 = 1+2 连跳版），
     * 统一下沉到 {@link TetoRenderSettings#sheet(int)}：这里只按选中的那套取 UV。
     *   · 动作 0：373 个姿势、24 列 × 16 行、每格 160×288（3840×4608）；
     *   · 动作 1：157 帧、24 列 × 7 行、每格 210×306（5040×2142）；
     *   · 动作 2：189 帧、24 列 × 8 行、每格 210×306（5040×2448）；
     *   · 动作 3：325 帧、24 列 × 14 行、每格 210×306（5040×4284）。
     */

    /**
     * 四边形的**高**（格）。宽度不再写死：按所选贴图「格宽:格高」的比例现算
     * （动作 0 是 160:288，后三套是 210:306 —— 后者格子更宽是为了容纳手臂完全张开的帧），
     * 这样人物永远不会被拉扁。
     */
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
        //
        // 取帧的时钟不是 Teto 自己的 tickCount，而是**链路年龄**：
        //   · 前奏（0 – 427）：走同一段 141 帧循环。实测前奏素材（21.3 秒绿幕舞）从第 142 帧
        //     起和正片循环段逐帧一致，起点也是循环的第 0 帧 —— 它不是另一段舞，
        //     只是**起点比谱子晚 0.355 秒**（≈7 刻），这个差值由 frameOffset 统一补掉；
        //   · 正片：从 0 重新数（age - INTRO_END），特殊动作正好落在 STILL_START = main(252)，
        //     和停转 / 定镜头那套窗口严格对齐。
        // 动作：曲子里强制原版（服务端开歌时也会把所有 Teto 归零，这里再兜一层，
        // 免得曲子刚开始那几刻客户端的旧值还没同步过来）
        int action = BrainrotQteClient.active()
                ? TetoRenderSettings.ACTION_ORIGINAL
                : entity.actionIndex();
        TetoRenderSettings.Sheet sheet = TetoRenderSettings.sheet(action);

        int frame;
        if (action != TetoRenderSettings.ACTION_ORIGINAL) {
            // 另一套动作：整段循环，和曲子无关，从出生那一刻开始一直跳
            frame = TetoRenderSettings.actionFrameIndex(action, entity);
        } else if (BrainrotQteClient.active()) {
            // 时钟用**带小数**的谱面时刻：一刻 = 1.5 帧，加上 partialTick 之后画面能按原视频
            // 30fps 的节奏换姿势，而不是被 20 刻/秒的整数拍死（那会让每个姿势多停/少停半刻）。
            int songAge = BrainrotQteClient.age();
            float age = Math.max(0.0F, BrainrotQteClient.ageF() + partialTick);
            frame = songAge < BrainrotBeats.INTRO_END
                    ? TetoRenderSettings.loopFrame(age)
                    : TetoRenderSettings.chainFrame(age - BrainrotBeats.INTRO_END);
        } else {
            frame = TetoRenderSettings.frameIndex(entity.tickCount);
        }
        int column = frame % sheet.columns;
        int row = frame / sheet.columns;

        // 半像素内缩：相邻帧就贴在旁边，正好取到格子边界的话双线性采样会渗一条边过去。
        // 低分辨率图的 1 像素 = 源图好几像素，所以内缩值跟着画质放大。
        float inset = TetoRenderSettings.uvInset();
        float u0 = (column * sheet.cellW + inset) / (float) (sheet.columns * sheet.cellW);
        float u1 = ((column + 1) * sheet.cellW - inset) / (float) (sheet.columns * sheet.cellW);
        // 左右镜像：贴图本身是照源视频抠的、一帧没翻（剪影比对 IoU 0.94 对 0.39），
        // 但这条绘制路径画出来会左右反着 —— 对着镜头看到的一面正好和视频互为镜像。
        // 所以在这里把 U 掉个个儿，贴图一个字节都不用动。
        float swapU = u0;
        u0 = u1;
        u1 = swapU;
        // 贴图 v=0 在最上面，所以格子的「上边」用 row 的起点
        float v0 = (row * sheet.cellH + inset) / (float) (sheet.rows * sheet.cellH);
        float v1 = ((row + 1) * sheet.cellH - inset) / (float) (sheet.rows * sheet.cellH);

        poseStack.pushPose();
        // 整块四边形贴着相机：相机怎么转，它怎么转
        poseStack.mulPose(this.entityRenderDispatcher.cameraOrientation());
        Matrix4f matrix = poseStack.last().pose();
        VertexConsumer vertices = buffer.getBuffer(RenderType.entityTranslucent(sheet.texture));

        // 四套贴图的人物都在生成时统一过世界尺寸（原版 216/288 ≈ 新三套 229/306，
        // 脚底离格底的距离也一样），所以 quadScale 恒为 1，直接按格子纵横比算宽高即可。
        float quadScale = sheet.contentScale;
        float aspect = sheet.cellW / (float) sheet.cellH;
        float half = HEIGHT * quadScale * aspect / 2.0F;
        float top = HEIGHT * quadScale;
        // 左下 → 右下 → 右上 → 左上
        vertex(vertices, matrix, -half, 0.0F, u0, v1, packedLight);
        vertex(vertices, matrix, half, 0.0F, u1, v1, packedLight);
        vertex(vertices, matrix, half, top, u1, v0, packedLight);
        vertex(vertices, matrix, -half, top, u0, v0, packedLight);

        poseStack.popPose();
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
    }


    private static void vertex(VertexConsumer vertices, Matrix4f matrix,
                               float x, float y, float u, float v, int packedLight) {
        vertex(vertices, matrix, x, y, u, v, packedLight, 1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void vertex(VertexConsumer vertices, Matrix4f matrix,
                               float x, float y, float u, float v, int packedLight,
                               float r, float g, float b, float a) {
        // 顶点格式和光柱一样是 NEW_ENTITY：位置 -> 颜色 -> UV0 -> overlay -> 亮度 -> 法线
        vertices.vertex(matrix, x, y, 0.0F)
                .color(r, g, b, a)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(packedLight)
                .normal(0.0F, 0.0F, 1.0F)
                .endVertex();
    }
}
