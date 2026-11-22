package com.hatmod.client;

import com.hatmod.HatMod;
import com.hatmod.HatParticles;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RegisterParticleProvidersEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
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

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // 「全」帽的临时分身：用玩家模型 + 召唤者的皮肤来画
        event.registerEntityRenderer(com.hatmod.HatSummons.SUMMON.get(), HatSummonRenderer::new);
        // 「脑蚀」一轮里的后室 Teto：序列帧平面贴图
        event.registerEntityRenderer(com.hatmod.HatSummons.TETO.get(), TetoRenderer::new);
        event.registerEntityRenderer(com.hatmod.HatSummons.KHragg.get(), KhraggRenderer::new);
        // 链路里跟着玩家走的同伴（唐嘉琦）：同样是序列帧平面贴图
        event.registerEntityRenderer(com.hatmod.HatSummons.TANG.get(), TangJiaqiRenderer::new);
        event.registerEntityRenderer(com.hatmod.HatSummons.TANG_WHITE.get(), TangJiaqiWhiteRenderer::new);
    }

    /**
     * 饰品栏里的帽子**不渲染**。
     *
     * <p>放进 Curios「head」槽的帽子仍然有全部功能（{@code HatAbilities.hatStack} 会读它），
     * 但**不再画在头上** —— 这里刻意不注册 {@link CurioHatRenderer}，那个类保留着，
     * 以后想恢复渲染只需把下面这几行注释放开。
     */
    @SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        // if (ModList.get() != null && ModList.get().isLoaded("curios")) {
        //     CurioHatRenderer.register();
        // }
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
        // 开局压暗的冷色滤镜：压在原版 HUD 下面，只影响世界画面
        event.registerBelow(VanillaGuiOverlay.VIGNETTE.id(), "brainrot_intro_dim",
                (gui, graphics, partialTick, width, height) -> QteHud.renderIntroDim(graphics));
        event.registerAboveAll("green_hearts",
                (gui, graphics, partialTick, width, height) -> GreenHeartRender.render(graphics, width, height));
        // 判定轨道不注册在这里 —— 注册成 HUD 图层的话，系统消息一多就会被聊天栏盖住，
        // 而这条轨道是要看着按的。改到 RenderGuiEvent.Post 里画，见 QteHud#onRenderGuiPost。
        // 「脑蚀」照射期的整屏紫色辉光（高光时刻的整体画面强化）
        event.registerAboveAll("brainrot_glow",
                (gui, graphics, partialTick, width, height) -> BrainrotGlow.render(graphics));
    }

    /** 打拍键（默认 V）和强制开链路键（默认 P）：注册进按键设置，玩家可以自己改。 */
    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(QteHud.BEAT_TAP);
        event.register(QteHud.FORCE_START);
    }

    /**
     * 给玩家补两层跟帽子有关的渲染。
     *
     * <p>「脑蚀」帽冠正脸在护甲贴图里只有 7×5 个像素，画不下人像，所以单独走一层
     * {@link BrainHatFaceLayer}：它自己绑一张 112×80 的贴图，跟着头的姿态走。
     * 另一个是 {@link HatGlowLayer}：光柱照射期间给帽子本体套一层加色光。
     * 玩家是唯一会戴这顶帽子的（护甲栏 / 饰品栏），所以只挂到玩家渲染器上。
     */
    @SubscribeEvent
    public static void addLayers(EntityRenderersEvent.AddLayers event) {
        for (String skin : event.getSkins()) {
            PlayerRenderer renderer = event.getSkin(skin);
            if (renderer != null) {
                renderer.addLayer(new BrainHatFaceLayer<>(renderer));
                renderer.addLayer(new HatGlowLayer<>(renderer));
            }
        }
    }
}
