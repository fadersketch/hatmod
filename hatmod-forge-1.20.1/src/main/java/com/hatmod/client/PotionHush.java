package com.hatmod.client;

import com.hatmod.BrainrotBeats;
import com.hatmod.HatMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;

/**
 * 「Nope / too / late」那一段，把**所有生物**身上的药水粒子（状态效果的螺旋）按掉。
 *
 * <p>药水粒子是客户端自己按 {@code MobEffectInstance#isVisible()} 逐刻生成的，没有事件能拦；
 * 所以这里每刻直接扫一遍 {@code ParticleEngine} 的粒子队列，把「效果粒子」那一类摘掉 ——
 * 它们下一 tick 会重新生成，但生成出来立刻又被摘掉，画面上就等于没有。
 *
 * <p>不认类名就退化成「整段清空粒子」：宁可这一段连光柱碎屑都看不见，也不能让螺旋糊住 Teto。
 */
@Mod.EventBusSubscriber(modid = HatMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class PotionHush {

    /** 连续多少刻一个效果粒子都没认出来，就干脆清空整段。 */
    private static final int BLIND_LIMIT = 20;

    private static Field particlesField;
    private static boolean unavailable;
    /** 认不出类名的累计刻数（只在窗口里累加）。 */
    private static int blindTicks;
    /** 认不出来就整段清空。 */
    private static boolean nukeAll;

    private PotionHush() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !BrainrotQteClient.active()) {
            return;
        }
        int age = BrainrotQteClient.age();
        if (age < 0 || BrainrotBeats.speechAnchor(age) < 0) {
            return;
        }
        sweep(minecraft);
    }

    @SuppressWarnings("unchecked")
    private static void sweep(Minecraft minecraft) {
        if (unavailable) {
            return;
        }
        try {
            if (particlesField == null) {
                Field field = ParticleEngine.class.getDeclaredField("particles");
                field.setAccessible(true);
                particlesField = field;
            }
            Object raw = particlesField.get(minecraft.particleEngine);
            if (!(raw instanceof Map<?, ?> map)) {
                unavailable = true;
                return;
            }
            int removed = 0;
            for (Object value : map.values()) {
                if (!(value instanceof Queue<?> queue)) {
                    continue;
                }
                Queue<Particle> particles = (Queue<Particle>) queue;
                int before = particles.size();
                if (nukeAll) {
                    particles.clear();
                } else {
                    particles.removeIf(PotionHush::isEffectParticle);
                }
                removed += before - particles.size();
            }
            if (nukeAll) {
                return;
            }
            if (removed > 0) {
                blindTicks = 0;
            } else if (++blindTicks > BLIND_LIMIT) {
                // 一直认不出来：不再猜，直接清空
                nukeAll = true;
            }
        } catch (Throwable ignored) {
            unavailable = true;
        }
    }

    /** 药水粒子：类名里带 effect / potion（原版是 {@code MobEffectParticle} 那一类）。 */
    private static boolean isEffectParticle(Particle particle) {
        for (Class<?> type = particle.getClass(); type != null; type = type.getSuperclass()) {
            String name = type.getSimpleName().toLowerCase(Locale.ROOT);
            if (name.contains("effect") || name.contains("potion")) {
                return true;
            }
        }
        return false;
    }
}
