package com.hatmod.client;

import com.hatmod.HatMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 黑帽附魔「预知」发动后，戴帽者眼前那一段画面：**整段持续黑白渲染**。
 *
 * <p>刻意**不是**往画面上盖亮/暗色块 —— 那样等于把屏幕闪白/闪黑，很刺眼。这里换的是
 * 原版后处理链的开关：
 *
 * <ul>
 *   <li>整段预知期间，黑白那条链（{@code assets/hatmod/shaders/post/foresight_bw.json}，
 *       复用的就是原版 {@code color_convolve}）**一直挂着、一直生效**；</li>
 *   <li>链被别人顶掉时（原版爬行者/蜘蛛滤镜也会走 {@code loadEffect}）每刻补挂回来；
 *       整段结束就把链整个摘掉，不会在客户端留下一张永久黑白的画面。</li>
 * </ul>
 *
 * <p>早先这里是「双闪」（黑白 / 正常高频交替），现在改成**持续黑白** —— 一眼「我早已料到」。
 */
@Mod.EventBusSubscriber(modid = HatMod.MOD_ID, value = Dist.CLIENT)
public final class ForesightFilter {

    /** 后处理链的位置（{@code assets/hatmod/shaders/post/foresight_bw.json}）。 */
    private static final ResourceLocation EFFECT =
            new ResourceLocation(HatMod.MOD_ID, "shaders/post/foresight_bw.json");

    /** 整段预知还剩几刻；0 = 没在放。 */
    private static int remaining;
    /** 我们挂上去的那条链。用它判断「链还在不在」——被别的效果顶掉时好补回来。 */
    private static PostChain loaded;

    private ForesightFilter() {
    }

    /** 服务端通知：开始一整段 {@code ticks} 刻的预知。 */
    public static void start(int ticks) {
        if (ticks <= 0) {
            return;
        }
        remaining = ticks;
        ensureBlackWhite();
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || remaining <= 0) {
            return;
        }
        // 暂停时不推进：画面都停在原地了，倒计时不该偷偷跑完（和 BGM 心跳一个道理）。
        if (Minecraft.getInstance().isPaused()) {
            return;
        }
        remaining--;
        if (remaining <= 0) {
            stop();
            return;
        }
        // 持续黑白：每刻确认链挂着且生效（被别的滤镜顶掉就补回来）。
        ensureBlackWhite();
    }

    /**
     * 确认黑白链挂着且处于生效状态（持续黑白，不交替）。
     *
     * <p>要是链被别人顶掉了（原版那种爬行者 / 蜘蛛滤镜也会走 {@code loadEffect}），就先把它挂回来。
     * {@code loadEffect} 挂上就是「生效」状态，所以这里不需要再翻 {@code effectActive} 开关。
     */
    private static void ensureBlackWhite() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            // 已经不在世界里了：原版会把链收掉，我们只要别记着它
            loaded = null;
            return;
        }
        if (loaded != null && minecraft.gameRenderer.currentEffect() == loaded) {
            return; // 已经是我们的链、且生效
        }
        try {
            minecraft.gameRenderer.loadEffect(EFFECT);
            loaded = minecraft.gameRenderer.currentEffect();
        } catch (Exception e) {
            // 资源包把原版着色器换掉了之类：画面效果没了就没了吧，绝不能因为它崩客户端。
            loaded = null;
            HatMod.LOGGER.warn("[hatmod] 黑白后处理加载失败，已跳过：{}", e.toString());
        }
    }

    /** 整段结束：把链整个摘掉，连它占的渲染目标一起释放。 */
    private static void stop() {
        Minecraft minecraft = Minecraft.getInstance();
        if (loaded != null && minecraft.gameRenderer.currentEffect() == loaded) {
            minecraft.gameRenderer.shutdownEffect();
        }
        loaded = null;
    }
}
