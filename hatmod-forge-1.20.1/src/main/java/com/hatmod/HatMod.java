package com.hatmod;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(HatMod.MOD_ID)
public class HatMod {
    public static final String MOD_ID = "hatmod";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MOD_ID, path);
    }

    /**
     * Forge 1.20.1 要求模组类提供无参构造函数，事件总线通过
     * FMLJavaModLoadingContext 取（构造器注入是 NeoForge 才支持的特性）。
     */
    public HatMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        HatItems.register(modBus);
        HatEnchants.register(modBus);
        HatEffects.register(modBus);
        HatParticles.register(modBus);
        HatSounds.register(modBus);
        HatNetwork.register();
        // 趁 jar 句柄还开着，把本模组的类全部预先加载好（原因见 HatPreloader 的注释）。
        HatPreloader.preloadCommon();
        LOGGER.info("[HatMod] cowboy hats loaded");
    }
}
