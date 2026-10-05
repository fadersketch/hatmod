package com.hatmod.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * 「绿心」在画面上的那一半：血条上方多出来的一条**绿色能量条**。
 *
 * <p>绿心是本模组自己的抗伤池（不是原版「吸收」/黄心），所以原版 HUD 不认识它，
 * 这一条得我们自己画：数值由服务端每变一次推一次（{@code HatNetwork.GreenHeartsPayload}），
 * 这里只负责按数值把条画出来。
 *
 * <p>早先是照原版那样一颗颗画心（一行 10 颗、多了往上摞一行）—— 绿心攒多之后会一路
 * 摞到画面顶上，反而看不清还剩多少。改成一条固定长度的能量条，中间写当前是「几颗心」，
 * 多少都一眼能读出来。
 *
 * <p>位置沿用原版血条那一套排布公式：血量可能占好几行（血量 + 吸收一起算行数），
 * 护甲又单独占一行 —— 绿心条就摆在**原版那一块的最上面一行之上**，
 * 所以和原版的红心、黄心、护甲都不会叠在一起。
 *
 * <p>条长按**玩家最大生命**换算：满条 = 一条血那么多的绿心，再多就顶格，
 * 具体数值看中间那个数字（单位是「颗心」，半颗显示 {@code .5}）。
 */
@EventBusSubscriber(modid = com.hatmod.HatMod.MOD_ID, value = Dist.CLIENT)
public final class GreenHeartRender {

    /** 条的尺寸：和原版一条血条同宽（10 颗心 × 8 像素步长 + 最后一颗的 9 像素），高 9 像素。 */
    private static final int BAR_WIDTH = 81;
    private static final int BAR_HEIGHT = 9;
    /** 描边 / 底槽 / 填充色。 */
    private static final int BORDER_COLOR = 0xFF104010;
    private static final int TRACK_COLOR = 0xC0000000;
    private static final int FILL_COLOR = 0xFF3ECF3E;
    /** 中间那个数字的颜色。 */
    private static final int TEXT_COLOR = 0xFFFFFFFF;

    /** 本地玩家当前的绿心点数（1 颗心 = 2 点）。 */
    private static float amount;

    private GreenHeartRender() {
    }

    /** 服务端推来的最新数值。 */
    public static void set(float value) {
        amount = Math.max(0.0F, value);
    }

    /** 整块 HUD 画完之后再叠上绿心条，每帧一次；没有绿心时什么都不画。 */
    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (amount <= 0.0F) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }
        int width = minecraft.getWindow().getGuiScaledWidth();
        int height = minecraft.getWindow().getGuiScaledHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        // 原版血条的排布：先算血量 + 吸收一共占几行，再往上给护甲留一行。
        int absorption = Mth.ceil(player.getAbsorptionAmount());
        float maxHealth = Math.max(player.getMaxHealth(), player.getHealth());
        int rows = Mth.ceil((Mth.ceil(maxHealth) + absorption) / 2.0F / 10.0F);
        int step = Math.max(10 - (rows - 2), 3);
        int topY = height - 39 - (rows - 1) * step;
        int barX = width / 2 - 91;
        int barY = topY - 10 - (player.getArmorValue() > 0 ? 10 : 0);

        // 满条 = 玩家最大生命那么多绿心（至少按 20 点算，免得畸形血量把条压成一根线）
        float full = Math.max(player.getMaxHealth(), 20.0F);
        float ratio = Mth.clamp(amount / full, 0.0F, 1.0F);

        RenderSystem.enableBlend();
        event.getGuiGraphics().fill(barX, barY, barX + BAR_WIDTH, barY + BAR_HEIGHT, BORDER_COLOR);
        event.getGuiGraphics().fill(barX + 1, barY + 1, barX + BAR_WIDTH - 1, barY + BAR_HEIGHT - 1, TRACK_COLOR);
        int filled = Mth.floor((BAR_WIDTH - 2) * ratio);
        if (filled > 0) {
            event.getGuiGraphics().fill(barX + 1, barY + 1, barX + 1 + filled, barY + BAR_HEIGHT - 1, FILL_COLOR);
        }
        RenderSystem.disableBlend();

        Font font = minecraft.font;
        String label = heartsLabel(amount);
        event.getGuiGraphics().drawString(font, label,
                barX + (BAR_WIDTH - font.width(label)) / 2, barY, TEXT_COLOR, true);
    }

    /** 绿心点数 -> 「几颗心」的文字：整颗是整数，半颗补 {@code .5}。 */
    private static String heartsLabel(float value) {
        int half = Mth.ceil(value);
        return half % 2 == 0 ? Integer.toString(half / 2) : (half / 2) + ".5";
    }
}
