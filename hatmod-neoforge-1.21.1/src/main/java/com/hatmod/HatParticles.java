package com.hatmod;

import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 手电筒光柱所用的自定义粒子类型：白色散射光。 */
public final class HatParticles {
    public static final DeferredRegister<ParticleType<?>> PARTICLES =
            DeferredRegister.create(Registries.PARTICLE_TYPE, HatMod.MOD_ID);

    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> FLASH_LIGHT =
            PARTICLES.register("flash_light", () -> new SimpleParticleTypeImpl(true));

    private HatParticles() {
    }

    public static void register(IEventBus modBus) {
        PARTICLES.register(modBus);
    }

    /** SimpleParticleType 的构造器在部分版本里是 protected，用子类把它暴露出来最稳妥。 */
    private static final class SimpleParticleTypeImpl extends SimpleParticleType {
        private SimpleParticleTypeImpl(boolean alwaysShow) {
            super(alwaysShow);
        }
    }
}
