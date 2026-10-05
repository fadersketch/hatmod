package com.hatmod;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(HatMod.MOD_ID)
public class HatMod {
    public static final String MOD_ID = "hatmod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    /** NeoForge 会把 mod 事件总线直接注入构造器（FMLJavaModLoadingContext 已移除）。 */
    public HatMod(IEventBus modBus) {
        HatItems.register(modBus);
        HatEffects.register(modBus);
        HatParticles.register(modBus);
        HatRecipes.register(modBus);
        HatSounds.register(modBus);
        HatNetwork.register(modBus);
        // 趁 jar 句柄还开着，把本模组的类全部预先加载好（原因见 HatPreloader 的注释）。
        HatPreloader.preloadCommon();
        LOGGER.info("[HatMod] cowboy hats loaded");
    }
}
