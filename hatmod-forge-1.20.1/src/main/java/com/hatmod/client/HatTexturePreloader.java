package com.hatmod.client;

import com.hatmod.HatMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Unit;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 每次资源重载之后，立刻把三顶帽子的**护甲贴图**读进显存。
 *
 * <p>为什么需要这一步：护甲贴图是**独立贴图**（不参与图集拼接），走的是
 * {@code TextureManager.getTexture(...)} 的按需加载 —— 也就是**第一次有人戴帽子时**才去读 jar。
 * 而原版把加载失败的结果缓存下来就不再重试：一次读不到，这顶帽子整局都是
 * 「紫黑错乱贴图」，怎么换怎么摘都不会自己好。
 *
 * <p>整合包里会关掉模组 jar 句柄的工具（AllTheLeaks 一类）正好踩中这一点：
 * 启动结束后才被加载的东西有可能读不到自己的资源。所以在**资源重载时**（那一刻所有资源包
 * 都确定是开着的）先把这三张贴图点一遍，之后渲染时就只是查缓存，不再碰 jar
 * ——和 {@link com.hatmod.HatPreloader} 提前加载类是同一个思路，只是一个管类、一个管贴图。
 *
 * <p>点一遍不会多占资源：这四张图本来第一次戴帽子也要加载，这里只是把时机提前到"肯定读得到"的时候。
 */
@Mod.EventBusSubscriber(modid = HatMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class HatTexturePreloader {

    /** 四顶帽子的护甲贴图。路径由 {@code ArmorMaterial} 的名字决定，见 {@code HatItems}。 */
    private static final ResourceLocation[] ARMOR_TEXTURES = {
            HatMod.id("textures/models/armor/black_hat_layer_1.png"),
            HatMod.id("textures/models/armor/white_hat_layer_1.png"),
            HatMod.id("textures/models/armor/red_hat_layer_1.png"),
            HatMod.id("textures/models/armor/all_hat_layer_1.png"),
    };

    private HatTexturePreloader() {
    }

    @SubscribeEvent
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new PreparableReloadListener() {
            @Override
            public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager resources,
                                                  ProfilerFiller preparationProfiler, ProfilerFiller reloadProfiler,
                                                  Executor backgroundExecutor, Executor gameExecutor) {
                // 没有要"准备"的数据，等前面那些真正的加载器走完再动手（那时资源包开着）
                return barrier.wait(Unit.INSTANCE).thenRunAsync(HatTexturePreloader::loadArmorTextures, gameExecutor);
            }
        });
    }

    private static void loadArmorTextures() {
        TextureManager textures = Minecraft.getInstance().getTextureManager();
        for (ResourceLocation location : ARMOR_TEXTURES) {
            // getTexture 就是渲染时用的那一个入口：不在缓存里就当场加载并注册
            textures.getTexture(location);
        }
    }
}
