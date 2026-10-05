package com.hatmod.client;

import com.hatmod.HatMod;
import com.hatmod.HatParticles;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterParticleProvidersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = HatMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class HatModClient {

    static {
        // 这个类只在客户端被加载，正好用来把客户端专属的那批类也提前加载掉。
        com.hatmod.HatPreloader.preloadClient();
    }

    private HatModClient() {
    }

    @SubscribeEvent
    public static void registerLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(CowboyHatModel.LAYER, CowboyHatModel::createBodyLayer);
    }

    /**
     * 装了 Curios 就把三顶帽子登记给它的渲染器 —— 这样放进饰品栏「head」槽的帽子
     * 也会画在头上（见 {@link CurioHatRenderer}）。
     *
     * <p>{@link CurioHatRenderer} 整个类都引用 Curios 的 API，所以进来之前必须先问
     * {@code isLoaded}：没装 Curios 时这个类不会被加载，也就不会 NoClassDefFoundError。
     * 能放进饰品栏靠的是物品标签 {@code curios:head}，跟这里无关 —— 没装 Curios 时
     * 那个标签只是没人查。
     */
    @SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        if (ModList.get() != null && ModList.get().isLoaded("curios")) {
            CurioHatRenderer.register();
        }
    }

    @SubscribeEvent
    public static void registerParticleProviders(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(HatParticles.FLASH_LIGHT.get(), FlashLightParticle.Provider::new);
    }

    /**
     * 「绿心」那条能量条叠在所有原版 HUD 之上（注册为 above all 的自定义图层，每帧恰好画一次）。
     * 1.20.1 没有 NeoForge 那种「HUD 画完」的统一事件，走注册图层是唯一稳妥的做法。
     *
     * <p>「预知」的画面不在这里 —— 它换的是原版后处理链的开关，不往画面上画东西，
     * 见 {@link ForesightFilter}。
     */
    @SubscribeEvent
    public static void registerGuiOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("green_hearts",
                (gui, graphics, partialTick, width, height) -> GreenHeartRender.render(graphics, width, height));
    }
}
