package com.hatmod.client;

import com.hatmod.HatMod;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;

/**
 * 白色散射光粒子：贴图是一张柔和的白色圆形光斑，
 * 用半透明渲染层并随时间淡出，大量叠加起来即「手电筒光柱」的观感。
 */
public class FlashLightParticle extends TextureSheetParticle {
    private final SpriteSet sprites;

    protected FlashLightParticle(ClientLevel level, double x, double y, double z,
                                 double velocityX, double velocityY, double velocityZ,
                                 SpriteSet sprites) {
        super(level, x, y, z, velocityX, velocityY, velocityZ);
        this.sprites = sprites;
        this.setSpriteFromAge(sprites);
        this.lifetime = 8 + this.random.nextInt(9);
        this.quadSize = 0.30F + this.random.nextFloat() * 0.40F;
        this.gravity = 0.0F;
        this.hasPhysics = false;
        this.setColor(1.0F, 1.0F, 1.0F);
        this.alpha = 1.0F;
    }

    @Override
    public void tick() {
        super.tick();
        this.setSpriteFromAge(this.sprites);
        float progress = (float) this.age / (float) this.lifetime;
        this.alpha = Math.max(0.0F, 1.0F - progress);
        this.quadSize *= 1.03F;
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    public static class Provider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                       double x, double y, double z,
                                       double velocityX, double velocityY, double velocityZ) {
            return new FlashLightParticle(level, x, y, z, velocityX, velocityY, velocityZ, this.sprites);
        }
    }
}
