package com.hatmod.client;

import com.hatmod.BrainrotBeats;
import com.hatmod.HatAbilities;
import com.hatmod.HatItems;
import com.hatmod.HatMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * 「脑蚀」照射期的**整屏辉光**：屏幕四边一圈紫色光晕，按鼓点呼吸。
 *
 * <p>光柱出现的那几段本来就是整首曲子的高光时刻，但光柱本身在画面中央、越远越淡，
 * 观感上还是「不够响」。这一层把整个画面一起点着：
 * <ul>
 *   <li>基础强度约 0.35，踩到拍点（离拍越近越亮）升到 1.0 —— 和判定条用同一个拍点时钟；</li>
 *   <li>打中一拍（涌动）时再叠一层，PERFECT 比 GOOD 更亮，像一击打实了的余波；</li>
 *   <li>只在「自己戴着脑蚀 + 自己的脑蚀 BGM 在放 + 光柱正在照射」时出现，其他时候完全不画。</li>
 * </ul>
 *
 * <p>用多层边缘色带做渐隐（越靠外越亮），不碰后处理链，任何光影包/其他模组都叠得上。
 */
@EventBusSubscriber(modid = HatMod.MOD_ID, value = Dist.CLIENT)
public final class BrainrotGlow {

    /** 辉光的主色（紫红）。 */
    private static final int GLOW_RGB = 0xB03CFF;
    /** 踩到拍点时的峰值透明度。 */
    private static final int GLOW_MAX_ALPHA = 70;
    /** 边缘色带的层数与每层厚度（像素）。 */
    private static final int BANDS = 5;
    private static final int BAND_STEP = 12;

    private BrainrotGlow() {
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        render(event.getGuiGraphics());
    }

    public static void render(GuiGraphics graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !BrainrotQteClient.active()) {
            return;
        }
        if (!HatAbilities.hatStack(minecraft.player).is(HatItems.BRAIN_HAT.get())) {
            return;
        }
        // 只在光柱真的在照射时亮起来（服务端每刻同步这个状态）
        if (!BeamTargets.isBeaming(minecraft.player.getId())) {
            return;
        }
        int age = BrainrotQteClient.age();
        if (age < 0) {
            return;
        }
        // 和判定条同一个拍点时钟：离拍越近越亮
        int[] near = new int[1];
        BrainrotBeats.nearest(age, near);
        float pulse = 1.0F - Math.min(1.0F, Math.abs(near[0]) / 8.86F);
        float[] surge = new float[2];
        BrainrotQteClient.surgeScale(surge);
        // 基础 0.35 + 拍点 0.65 + 涌动（打中一拍）额外
        float strength = 0.35F + 0.65F * pulse + (surge[1] - 1.0F) * 0.9F;
        int alpha = Math.min(180, Math.round(GLOW_MAX_ALPHA * strength));
        vignette(graphics, GLOW_RGB, alpha);

        // 打中那一刻：整屏再罩一层很淡的紫，像被打了一记
        if (surge[1] > 1.0F) {
            int wash = Math.min(30, Math.round((surge[1] - 1.0F) * 70.0F));
            if (wash > 0) {
                graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(),
                        (wash << 24) | 0x7A2BD8);
            }
        }
    }

    /** 多层边缘色带：越靠外越亮，向内渐隐（不碰后处理，叠得上任何光影）。 */
    private static void vignette(GuiGraphics graphics, int rgb, int alpha) {
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        for (int i = 0; i < BANDS; i++) {
            int layerAlpha = alpha * (BANDS - i) / (BANDS * 3);
            if (layerAlpha <= 0) {
                continue;
            }
            int thickness = BAND_STEP * (i + 1);
            int color = (layerAlpha << 24) | rgb;
            graphics.fill(0, 0, width, thickness, color);
            graphics.fill(0, height - thickness, width, height, color);
            graphics.fill(0, 0, thickness, height, color);
            graphics.fill(width - thickness, 0, width, height, color);
        }
    }
}