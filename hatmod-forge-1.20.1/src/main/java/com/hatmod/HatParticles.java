package com.hatmod;

import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** 手电筒光柱所用的自定义粒子类型：白色散射光。 */
public final class HatParticles {
    public static final DeferredRegister<ParticleType<?>> PARTICLES =
            DeferredRegister.create(ForgeRegistries.PARTICLE_TYPES, HatMod.MOD_ID);

    public static final RegistryObject<SimpleParticleType> FLASH_LIGHT =
            PARTICLES.register("flash_light", () -> new FlashLightParticleType(true));

    private HatParticles() {
    }

    public static void register(IEventBus modBus) {
        PARTICLES.register(modBus);
    }

    /** SimpleParticleType 的构造器在部分版本里是 protected，用子类把它暴露出来最稳妥。 */
    public static final class FlashLightParticleType extends SimpleParticleType {
        public FlashLightParticleType(boolean alwaysShow) {
            super(alwaysShow);
        }
    }
}
