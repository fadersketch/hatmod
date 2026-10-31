package com.hatmod.client;

import com.hatmod.HatMod;
import com.hatmod.HatParticles;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.resources.PlayerSkin;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
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
     * 饰品栏里的帽子**不渲染**。
     *
     * <p>放进 Curios「head」槽的帽子仍然有全部功能（{@code HatAbilities.hatStack} 会读它），
     * 但**不再画在头上** —— 这里刻意不注册 {@link CurioHatRenderer}，那个类保留着，
     * 以后想恢复渲染只需把下面这一行注释放开。
     */
    @SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        // if (ModList.get() != null && ModList.get().isLoaded("curios")) {
        //     CurioHatRenderer.register();
        // }
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // 「全」帽的临时分身：用玩家模型 + 召唤者的皮肤来画
        event.registerEntityRenderer(com.hatmod.HatSummons.SUMMON.get(), HatSummonRenderer::new);
        // 「脑蚀」一轮里的后室 Teto：序列帧平面贴图
        event.registerEntityRenderer(com.hatmod.HatSummons.TETO.get(), TetoRenderer::new);
    }

    @SubscribeEvent
    public static void registerParticleProviders(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(HatParticles.FLASH_LIGHT.get(), FlashLightParticle.Provider::new);
    }

    /** 打拍键（默认 V）和强制开链路键（默认 P）：注册进按键设置，玩家可以自己改。 */
    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(QteHud.BEAT_TAP);
        event.register(QteHud.FORCE_START);
    }

    /**
     * 给玩家补一层「脑蚀」帽冠正面的画像。
     *
     * <p>帽冠前脸在护甲贴图里只有 7×5 个像素，画不下人像，所以单独走一层
     * {@link BrainHatFaceLayer}：它自己绑一张 112×80 的贴图，跟着头的姿态走。
     * 玩家是唯一会戴这顶帽子的（护甲栏 / 饰品栏），所以只挂到玩家渲染器上。
     */
    @SubscribeEvent
    public static void addLayers(EntityRenderersEvent.AddLayers event) {
        for (PlayerSkin.Model skin : event.getSkins()) {
            PlayerRenderer renderer = event.getSkin(skin);
            if (renderer != null) {
                renderer.addLayer(new BrainHatFaceLayer<>(renderer));
            }
        }
    }
}
