package com.hatmod.client;

import com.hatmod.HatMod;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * 「脑蚀」的屏幕文字：**No too late** 逐词弹出，每 0.3 秒一个词，然后淡出。
 *
 * <p>触发由服务端发 {@code BrainrotTextPayload}（13 秒和 70 秒各一次），
 * 这里只负责"什么时候把哪个词画在哪"。计时用世界刻（0.3 秒 = 6 刻）。
 */
@EventBusSubscriber(modid = HatMod.MOD_ID, value = Dist.CLIENT)
public final class BrainrotText {

    private static final String[] WORDS = {"No", "too", "late"};
    /** 每个词之间隔 0.3 秒（6 刻）。 */
    private static final int WORD_INTERVAL = 6;
    /** 整段持续多久（刻）：最后一个词出来后再停 2 秒。 */
    private static final int TOTAL_TICKS = WORD_INTERVAL * WORDS.length + 40;

    /** 开始显示的刻；{@link Long#MIN_VALUE} = 没在显示。 */
    private static long startTick = Long.MIN_VALUE;

    private BrainrotText() {
    }

    /** 收到服务端的「弹字」包。 */
    public static void show() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        startTick = minecraft.level.getGameTime();
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || startTick == Long.MIN_VALUE) {
            return;
        }
        long elapsed = minecraft.level.getGameTime() - startTick;
        if (elapsed < 0 || elapsed > TOTAL_TICKS) {
            startTick = Long.MIN_VALUE;
            return;
        }
        GuiGraphics graphics = event.getGuiGraphics();
        int centerX = graphics.guiWidth() / 2;
        int centerY = graphics.guiHeight() / 2 + 46;

        for (int i = 0; i < WORDS.length; i++) {
            long age = elapsed - (long) i * WORD_INTERVAL;
            if (age < 0) {
                continue;
            }
            // 弹出：前 3 刻从 2.2 倍缩到 1 倍，同时从不透明到完全显现
            float pop = Math.min(1.0F, age / 3.0F);
            float scale = 1.0F + (1.0F - pop) * 1.2F;
            // 最后一个词出来 1 秒之后整段一起淡出
            float fade = Math.max(0.0F, Math.min(1.0F, (TOTAL_TICKS - (float) elapsed) / 12.0F));
            int alpha = (int) (255.0F * Math.min(1.0F, pop + 0.25F) * fade);
            if (alpha <= 0) {
                continue;
            }
            Component text = Component.literal(WORDS[i])
                    .withStyle(ChatFormatting.BOLD, ChatFormatting.WHITE);
            int width = minecraft.font.width(text);
            var pose = graphics.pose();
            pose.pushPose();
            pose.translate(centerX, centerY + i * 18, 0.0F);
            pose.scale(scale, scale, 1.0F);
            graphics.drawString(minecraft.font, text, -width / 2, -minecraft.font.lineHeight / 2,
                    (alpha << 24) | 0xFFFFFF, true);
            pose.popPose();
        }
    }
}
