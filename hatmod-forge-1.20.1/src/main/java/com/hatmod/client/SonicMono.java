package com.hatmod.client;

import com.hatmod.BrainrotCycle;
import com.hatmod.HatMod;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 尖叫那一瞬的**黑白画面**：不用「黑暗」压视野（看不见路太伤），改成黑帽「预知」同款的后处理链
 * （{@link ForesightFilter}，整屏褪色成黑白）—— 威力一样是"世界忽然褪色"，但路还看得见。
 *
 * <p>时长由 {@link BrainrotCycle#sonicMonoWindow} 决定（和原来那一下黑暗同长）。
 * 每刻续两刻让它自己过期，窗口一过不留残影。
 */
@Mod.EventBusSubscriber(modid = HatMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class SonicMono {

    /** 每刻续多长：比一个刻多一点点，让链在窗口结束时干净地掉掉。 */
    private static final int REFRESH_TICKS = 2;

    private SonicMono() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !BrainrotQteClient.active()) {
            return;
        }
        int age = BrainrotQteClient.age();
        if (age < 0 || !BrainrotCycle.sonicMonoWindow(age)) {
            return;
        }
        ForesightFilter.start(REFRESH_TICKS);
    }
}
