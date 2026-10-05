package com.hatmod.client;

import com.hatmod.HatMod;
import com.hatmod.HatParticles;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;

@EventBusSubscriber(modid = HatMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
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
}
