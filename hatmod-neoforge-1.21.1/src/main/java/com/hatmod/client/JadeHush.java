package com.hatmod.client;

import com.hatmod.BrainrotCycle;
import com.hatmod.HatMod;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.lang.reflect.Method;

/**
 * 「Nope, too late」锁视线那几秒，把 **Jade（玉）** 的实体信息面板暂时收起来。
 *
 * <p>锁视线是把镜头扳到 Teto 身上，可 Jade 正好会在准星指着的实体旁边弹出那一块属性面板 ——
 * 位置压得比 Teto 还低，整只 Teto 直接被挡住。
 *
 * <p>做法是反射调 {@code IWailaConfig.get().getOverlay().setAlpha(0)}，只把它的透明度归零，
 * 退出时把原来的值还回去（不是改配置、也不写盘）。Jade 没装就直接什么也不做。
 */
@EventBusSubscriber(modid = HatMod.MOD_ID, value = Dist.CLIENT)
public final class JadeHush {

    /** 已经把 Jade 调成透明了吗。 */
    private static boolean hushed;
    /** 藏起来之前它原本的透明度。 */
    private static float savedAlpha;

    private static Method getOverlay;
    private static Method getAlpha;
    private static Method setAlpha;
    private static boolean unavailable;

    private JadeHush() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean want = minecraft.player != null
                && BrainrotQteClient.active()
                && BrainrotCycle.tetoFocusWindow(BrainrotQteClient.age());
        setHushed(want);
    }

    private static void setHushed(boolean want) {
        if (unavailable || want == hushed) {
            return;
        }
        try {
            if (getOverlay == null) {
                Class<?> api = Class.forName("snownee.jade.api.config.IWailaConfig");
                Object config = api.getMethod("get").invoke(null);
                getOverlay = api.getMethod("getOverlay");
                Object overlay = getOverlay.invoke(config);
                getAlpha = overlay.getClass().getMethod("getAlpha");
                setAlpha = overlay.getClass().getMethod("setAlpha", float.class);
            }
            Class<?> api = Class.forName("snownee.jade.api.config.IWailaConfig");
            Object overlay = getOverlay.invoke(api.getMethod("get").invoke(null));
            if (want) {
                savedAlpha = (Float) getAlpha.invoke(overlay);
                setAlpha.invoke(overlay, 0.0F);
            } else {
                setAlpha.invoke(overlay, savedAlpha);
            }
            hushed = want;
        } catch (Throwable ignored) {
            // 没装 Jade / 版本对不上：直接放弃，别每刻重试
            unavailable = true;
            hushed = false;
        }
    }
}
