package com.hatmod.client;

import com.hatmod.HatMod;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLivingEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 「脑蚀」的原地自转（friends & wine 那种喝完酒打转的观感，但不弹跳）。
 *
 * <p>做法是照抄那边的思路：**不动实体本身，只在渲染时把整个模型绕 Y 轴转一下**。
 * 直接在服务端改 {@code yRot} 的话，玩家自己的镜头会跟着转，而且攻击朝向也会歪；
 * 旋转只发生在画面上，玩法完全不受影响。
 *
 * <p>哪些实体在转由服务端通过 {@code BrainrotSpinPayload} 同步 —— 时间线是服务端的，
 * 客户端不自己推。
 */
@EventBusSubscriber(modid = HatMod.MOD_ID, value = Dist.CLIENT)
public final class BrainrotSpin {

    /** 每秒转一整圈：20 刻 × 18° = 360°。 */
    private static final float DEGREES_PER_TICK = 18.0F;

    /**
     * 起转用多少刻把转速从 0 拉到满速。
     *
     * <p>原来是一上来就 1 秒一圈：人形轮廓在这个转速下是**分不清顺/逆时针**的
     * （经典的「旋转舞者」错觉），而每次「No too late」之后自转都会重新开始，
     * 于是看上去就像每出现一次字幕、转的方向就翻一次。现在起转是渐进的，
     * 头 0.4 秒能看清往哪边转，方向一旦定下来就不会再被重新解读。
     */
    private static final int RAMP_TICKS = 8;

    /** 正在自转的实体 id。 */
    private static final Set<Integer> SPINNING = new HashSet<>();
    /** 实体 id -> 这一次起转的刻；{@link #START_UNKNOWN} 表示还没量过。 */
    private static final Map<Integer, Long> STARTED = new HashMap<>();
    private static final long START_UNKNOWN = Long.MIN_VALUE;

    private BrainrotSpin() {
    }

    public static void set(int wearerId, boolean spin) {
        if (spin) {
            if (SPINNING.add(wearerId)) {
                STARTED.put(wearerId, START_UNKNOWN);
            }
        } else {
            SPINNING.remove(wearerId);
            STARTED.remove(wearerId);
        }
    }

    @SubscribeEvent
    public static void onRenderLiving(RenderLivingEvent.Pre<?, ?> event) {
        LivingEntity entity = event.getEntity();
        if (!SPINNING.contains(entity.getId())) {
            return;
        }
        PoseStack poseStack = event.getPoseStack();
        int id = entity.getId();
        long start = STARTED.getOrDefault(id, START_UNKNOWN);
        if (start == START_UNKNOWN) {
            start = entity.level().getGameTime();
            STARTED.put(id, start);
        }
        // 角度从「起转那一刻」算，不再读 tickCount —— 实体重新加进世界时 tickCount 会归零，
        // 那样模型会在画面上弹一下。
        float ticks = (entity.level().getGameTime() - start) + event.getPartialTick();
        poseStack.mulPose(Axis.YP.rotationDegrees(easedDegrees(ticks)));
    }

    /**
     * 起转曲线：前 {@link #RAMP_TICKS} 刻按二次曲线从 0 加速到满速，之后恒速。
     * 转向永远是同一个正方向，这里只改「多快」，不改「往哪边」。
     */
    private static float easedDegrees(float ticks) {
        if (ticks <= 0.0F) {
            return 0.0F;
        }
        if (ticks < RAMP_TICKS) {
            return DEGREES_PER_TICK * ticks * ticks / (2.0F * RAMP_TICKS);
        }
        return DEGREES_PER_TICK * (ticks - RAMP_TICKS / 2.0F);
    }
}
