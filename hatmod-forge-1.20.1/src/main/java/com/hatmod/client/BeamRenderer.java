package com.hatmod.client;

import com.hatmod.HatAbilities;
import com.hatmod.HatItems;
import com.hatmod.HatType;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 绘制"手电筒"光锥。
 *
 * <p>关键：光束主体是**几何圆台**（锥壳：只有侧面、没有端盖），不是粒子。
 * 从**胯部**（{@link HatAbilities#beamOrigin}）出发沿光柱方向，用 {@link #CONE_LAYERS} 层嵌套的圆台
 * 叠出一个**漏斗状的体积光**：每层灯口半径相同、末端半径不同，加色混合后中间最亮、向外渐弱 ——
 * 看着就是一束照出去的光。
 *
 * <p>起点放在胯部而不是眼睛，是**第一人称能不能看见光柱的关键**：
 * 相机坐在锥体轴线上时，锥壳的投影会缩成一圈<b>空心</b>的环（正中间是空的），
 * 所以第一人称几乎什么都看不到；相机挪到轴线外之后，看到的是锥体侧面，一目了然。
 *
 * <p>两条观感上的死线，改代码时别踩：
 * <ul>
 *   <li><b>必须加色混合</b>。普通 alpha 混合叠出来是一层灰雾，不是光。</li>
 *   <li><b>灯口最亮、越远越淡</b>。早先那版反过来（末端反而最不透明、最亮），
 *       于是整根光锥看着像喷出去的一团雾 —— 那是观感崩掉的主因。</li>
 * </ul>
 *
 * <p>方向：优先指向服务端同步过来的锁定敌人（{@link BeamTargets}），
 * 这样才有"锁最近的敌人、会转火"的效果；没有目标时退化为沿视线。
 *
 * <p>对**所有**正在照射的戴帽生物都画一遍（由 {@link BeamTargets} 标记），
 * 所以车万女仆之类的生物戴帽子照射时，玩家也能看见。
 */
@Mod.EventBusSubscriber(modid = com.hatmod.HatMod.MOD_ID, value = Dist.CLIENT)
public final class BeamRenderer {
    /** 射程 */
    public static final double RANGE = 24.0D;
    /**
     * 第一人称下光锥顶点前移量（格）。
     *
     * <p>起点本身已经在胯部（见 {@link HatAbilities#beamOrigin}），相机在它上方约 0.9 格，
     * 所以不再是「从锥体内部往外看」。这里只往前挪一点点，避免锥体近端插进自己身体里。
     */
    private static final double FIRST_PERSON_FORWARD = 0.5D;

    /** 圆台横截面分段数：越高越圆滑 */
    private static final int SEGMENTS = 20;
    /** 光锥层数：一层就是一个圆台壳，嵌套得越多、径向的明暗过渡越平滑 */
    private static final int CONE_LAYERS = 8;
    /** 灯口（近端）半宽（格）。所有层共用同一个灯口，光锥才像是从一点照出来的 */
    private static final float LENS_RADIUS = 0.16F;
    /** 最外层圆台在末端的半宽（格）＝ 散射范围 */
    private static final float END_RADIUS = 3.4F;
    /**
     * 灯口处的亮度（0~1）。
     *
     * <p>加色混合是 {@code ONE,ONE}，透明度不参与叠加，所以亮度只能写在顶点的颜色里。
     * 八层叠起来轴向大约到 1.0（刚好过曝成白芯），别单看这一层的数值。
     */
    private static final float LAYER_BRIGHTNESS = 0.075F;
    /** 最外层相对最内层的亮度比例：外层压暗一点，光锥外缘才不是一道硬边 */
    private static final float OUTER_BRIGHTNESS_SCALE = 0.62F;
    /** 末端残余亮度比例：手电筒是"灯口最亮、越远越淡" */
    private static final float TIP_BRIGHTNESS_SCALE = 0.18F;
    /** 贴图沿光柱方向的重复密度（每前进 1 格前进多少 uv） */
    private static final float V_SCALE = 0.075F;
    /** 贴图沿光柱方向的滚动速度（每单位动画时间前进多少 uv），让光锥表面有流动感 */
    private static final float V_SCROLL = 0.22F;

    /** 轴线亮芯：起点距离（格） */
    private static final double CORE_START = 0.5D;
    /**
     * 轴线亮芯：采样间距（格）。
     *
     * <p><b>必须远小于光斑直径（{@link #CORE_HALF}×2），让相邻圆片大幅重叠。</b>
     * 早先是 0.35、和直径 0.44 只勉强相切，于是 1.20.1 的渲染管线下看起来是
     * <b>一串离散的圆球</b>（每片正对相机 → 从任何角度看都是圆的）。压到 0.12 后
     * 相邻约叠 3 层，加色叠起来就是一条连续亮芯。
     */
    private static final double CORE_STEP = 0.12D;
    /** 轴线亮芯：每片半径（格） */
    private static final float CORE_HALF = 0.18F;
    /** 轴线亮芯：每片亮度（加色，会互相叠加；重叠加深后要相应调低单片亮度） */
    private static final float CORE_BRIGHTNESS = 0.075F;

    /**
     * 每顶帽子的光柱淡色（R/G/B 系数，乘在亮度上）。
     *
     * <p>刻意做得很淡：白帽就是纯白，黑帽偏一点点冷紫、红帽偏一点点暖橙 ——
     * 远看还是一束白光，只在颜色上分得出是哪顶帽子打出来的。
     */
    private static final float[] TINT_BLACK = {0.92F, 0.94F, 1.10F};
    private static final float[] TINT_WHITE = {1.00F, 1.00F, 1.00F};
    private static final float[] TINT_RED = {1.10F, 0.95F, 0.90F};

    private BeamRenderer() {
    }

    /** 帽子 -> 光柱淡色；认不出帽子（还没拿到头盔物品）就按白色。 */
    private static float[] beamTint(HatType type) {
        if (type == HatType.BLACK) {
            return TINT_BLACK;
        }
        if (type == HatType.RED) {
            return TINT_RED;
        }
        return TINT_WHITE;
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }

        float partialTick = event.getPartialTick();
        Vec3 cam = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();

        // 服务端已经不在世界里的戴帽者（被卸载/退出，收不到「停止照射」包）先剔掉，
        // 否则会一直留一条幽灵光柱。
        BeamTargets.prune(id -> mc.level.getEntity(id) != null);

        boolean any = false;
        for (Entity entity : mc.level.entitiesForRendering()) {            if (!(entity instanceof LivingEntity living)) {
                continue;
            }
            // 「正在照射」由本模组自己的包同步（BeamTargets），**不看状态效果**：
            // 1.20.1 原版只把生物的状态效果同步给乘客，女仆身上的光柱靠效果永远传不过来。
            if (!BeamTargets.isBeaming(living.getId())) {
                continue;
            }

            // 红帽附魔「光柱」会同时打多道光柱：服务端把每道光锁定的目标 id 一起发来，
            // 这里逐个画一条指过去。没有锁到敌人时列表为空，退化成「沿视线一道」。
            int[] targetIds = BeamTargets.get(living.getId());
            int beams = Math.max(1, targetIds.length);
            // 光柱的淡色取自戴帽者身上那顶帽子（头盔槽或 Curios 饰品栏，客户端都会同步过来）
            float[] tint = beamTint(HatItems.typeOf(HatAbilities.hatStack(living)));
            float time = (living.tickCount + partialTick) * 0.35F;

            for (int i = 0; i < beams; i++) {
                int targetId = i < targetIds.length ? targetIds[i] : -1;
                Vec3 aim = beamDirection(mc, living, targetId, partialTick);
                if (aim.lengthSqr() < 1.0E-6D) {
                    continue;
                }
                // 多道光柱按和客户端一致的散射角张开，看得清是「几道」而不是一束
                Vec3 dir = HatAbilities.fanDirection(aim, i, beams).normalize();

                // 起点在胯部；第一人称下再往前挪一小段，别让锥体近端插进身体
                double forward = living == mc.player ? FIRST_PERSON_FORWARD : 0.0D;
                Vec3 origin = HatAbilities.beamOrigin(living, partialTick)
                        .add(dir.scale(forward))
                        .subtract(cam);

                poseStack.pushPose();
                poseStack.translate(origin.x, origin.y, origin.z);
                float length = (float) (RANGE - forward);
                // 多道光柱用小角度错开，免得完全重合时分不出是几条
                float spin = beams > 1 ? (float) (Math.PI * 2.0 * i / beams) : 0.0F;

                // 先画轴线贴片：此时矩阵只有平移、坐标轴与世界一致，方便算"正对相机"的四边形。
                // 必须在下面 mulPose 之前画完——矩阵是就地修改的。
                drawCore(buffers, poseStack.last().pose(), origin, dir, length, tint);

                // 再让圆台的局部 +Z 轴对齐光柱方向
                poseStack.mulPose(new Quaternionf().rotationTo(
                        new Vector3f(0.0F, 0.0F, 1.0F),
                        new Vector3f((float) dir.x, (float) dir.y, (float) dir.z)));

                Matrix4f matrix = poseStack.last().pose();

                float v0 = -time * V_SCROLL + spin;
                float v1 = v0 + length * V_SCALE;
                // 由内到外叠圆台：内层细（贴着轴线的芯），外层粗（散射范围）。
                // 每层都是从同一个灯口张开到自己的末端半径，加色叠起来就是一团体积光。
                for (int layer = 0; layer < CONE_LAYERS; layer++) {
                    float k = (float) layer / (CONE_LAYERS - 1);
                    float brightness = LAYER_BRIGHTNESS * (1.0F + (OUTER_BRIGHTNESS_SCALE - 1.0F) * k);
                    float endRadius = END_RADIUS * (layer + 1) / (float) CONE_LAYERS;
                    drawFrustum(buffers, matrix, length, brightness, endRadius, v0, v1, tint);
                }

                poseStack.popPose();
                any = true;
            }
        }

        if (any) {
            buffers.endBatch(BeamRenderTypes.beamCore());
            buffers.endBatch(BeamRenderTypes.beam());
        }
    }

    /**
     * 沿光锥轴线撒一串<b>正对相机</b>的圆形光斑，叠成一条连续的亮芯。
     *
     * <p>圆台壳是"只有面、没有体积"的曲面，顺着它的轴线看过去，整个曲面的投影会缩成
     * 一圈很窄的环 —— 正中间反而空着。贴上永远正对相机的光斑后，顺着光柱看时
     * 中心也有一条实心的亮芯。
     *
     * <p><b>间距必须远小于光斑直径</b>（见 {@link #CORE_STEP}）：1.20.1 的渲染管线下
     * 间距偏大时，这些正对相机的圆片会显现成<b>一串离散的圆球</b>；压小后大幅重叠，
     * 加色叠起来就是一条平滑的连续亮芯。
     */
    private static void drawCore(MultiBufferSource buffers, Matrix4f matrix,
                                 Vec3 origin, Vec3 dir, float length, float[] tint) {
        VertexConsumer consumer = buffers.getBuffer(BeamRenderTypes.beamCore());
        for (double t = CORE_START; t <= length; t += CORE_STEP) {
            // 平移后的局部坐标系里相机在 -origin 处，所以"相机 -> 采样点"的方向是 origin + dir*t
            Vec3 v = origin.add(dir.scale(t));
            if (v.lengthSqr() < 1.0E-6D) {
                continue;
            }
            Vec3 n = v.normalize();
            Vec3 upRef = Math.abs(n.y) > 0.99D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
            Vec3 right = n.cross(upRef).normalize().scale(CORE_HALF);
            Vec3 up = right.cross(n).normalize().scale(CORE_HALF);

            Vec3 p = dir.scale(t);
            Vec3 a = p.subtract(right).subtract(up);
            Vec3 b = p.add(right).subtract(up);
            Vec3 c = p.add(right).add(up);
            Vec3 d = p.subtract(right).add(up);

            float brightness = CORE_BRIGHTNESS * falloff((float) (t / length));
            vertex(consumer, matrix, (float) a.x, (float) a.y, (float) a.z, brightness, 0.0F, 0.0F, tint);
            vertex(consumer, matrix, (float) b.x, (float) b.y, (float) b.z, brightness, 1.0F, 0.0F, tint);
            vertex(consumer, matrix, (float) c.x, (float) c.y, (float) c.z, brightness, 1.0F, 1.0F, tint);
            vertex(consumer, matrix, (float) d.x, (float) d.y, (float) d.z, brightness, 0.0F, 1.0F, tint);
        }
    }

    /**
     * 光柱方向：若服务端锁定了敌人，就指过去（每帧按目标当前位置重算，
     * 所以目标移动时光柱会平滑跟随，换目标时立刻指到新的敌人）；否则沿视线。
     *
     * @param targetId 这道光柱锁定的实体 id；-1 表示没有目标（沿视线）
     */
    private static Vec3 beamDirection(Minecraft mc, LivingEntity living, int targetId, float partialTick) {
        Vec3 origin = HatAbilities.beamOrigin(living, partialTick);
        if (targetId >= 0) {
            Entity target = mc.level.getEntity(targetId);
            if (target != null) {
                Vec3 to = target.getBoundingBox().getCenter().subtract(origin);
                if (to.lengthSqr() > 1.0E-6D) {
                    return to;
                }
            }
        }
        return living.getViewVector(partialTick);
    }

    /**
     * 画一个从 z=0 到 z=length 的圆台壳（只有侧面，没有端盖）。
     *
     * <p>灯口半径固定为 {@link #LENS_RADIUS}、末端半径由调用方给，
     * 所以同灯口、不同末端半径的几层嵌套起来，就是一把张开的光锥（＝体积光）。
     *
     * <p>亮度沿长度从 {@code brightness} 线性衰减到 {@code brightness * TIP_BRIGHTNESS_SCALE}：
     * 手电筒是灯口最亮、越远越淡。
     *
     * @param brightness 灯口处的亮度（0~1）
     * @param endRadius  末端的截面半宽（格）
     * @param v0         贴图沿光柱方向的起点坐标
     * @param v1         贴图沿光柱方向的终点坐标
     * @param tint       帽子对应的淡色系数（R,G,B）
     */
    private static void drawFrustum(MultiBufferSource buffers, Matrix4f matrix, float length,
                                    float brightness, float endRadius, float v0, float v1,
                                    float[] tint) {
        VertexConsumer consumer = buffers.getBuffer(BeamRenderTypes.beam());
        float tipBrightness = brightness * TIP_BRIGHTNESS_SCALE;

        for (int i = 0; i < SEGMENTS; i++) {
            float a0 = (float) (Math.PI * 2.0 * i / SEGMENTS);
            float a1 = (float) (Math.PI * 2.0 * (i + 1) / SEGMENTS);

            float cos0 = Mth.cos(a0);
            float sin0 = Mth.sin(a0);
            float cos1 = Mth.cos(a1);
            float sin1 = Mth.sin(a1);

            // 横向按圆周铺、纵向沿光柱滚动，光锥表面就有了"能量在流"的纹路
            float u0 = (float) i / SEGMENTS;
            float u1 = (float) (i + 1) / SEGMENTS;

            vertex(consumer, matrix, cos0 * LENS_RADIUS, sin0 * LENS_RADIUS, 0.0F, brightness, u0, v0, tint);
            vertex(consumer, matrix, cos1 * LENS_RADIUS, sin1 * LENS_RADIUS, 0.0F, brightness, u1, v0, tint);
            vertex(consumer, matrix, cos1 * endRadius, sin1 * endRadius, length, tipBrightness, u1, v1, tint);
            vertex(consumer, matrix, cos0 * endRadius, sin0 * endRadius, length, tipBrightness, u0, v1, tint);
        }
    }

    /** 灯口到末端之间的亮度系数：0 = 在灯口，1 = 在末端。 */
    private static float falloff(float progress) {
        return 1.0F + (TIP_BRIGHTNESS_SCALE - 1.0F) * Mth.clamp(progress, 0.0F, 1.0F);
    }

    private static void vertex(VertexConsumer consumer, Matrix4f matrix,
                               float x, float y, float z,
                               float brightness, float u, float v, float[] tint) {
        // 顶点格式是能量旋涡用的 DefaultVertexFormat.NEW_ENTITY：
        // 位置 -> 颜色 -> 贴图(UV0) -> overlay(UV1) -> 亮度(UV2) -> 法线，一个都不能少、顺序也不能乱。
        // 少写一个顶点就收不了尾，BufferBuilder / Embeddium 都会在 endVertex() 抛异常。
        // 透明度固定 1.0：energy_swirl 的着色器会把 alpha < 0.1 的片元整个丢掉，
        // 想靠透明度做衰减的话，大半个光锥会直接消失；亮度只能写在颜色里。
        // 亮度只能写在颜色里，再乘上帽子对应的淡色（见 beamTint）。
        consumer.vertex(matrix, x, y, z)
                .color(brightness * tint[0], brightness * tint[1], brightness * tint[2], 1.0F)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(0xF000F0)
                .normal(0.0F, 1.0F, 0.0F)
                .endVertex();
    }
}
