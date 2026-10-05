package com.hatmod;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageType;

/**
 * 自定义伤害类型。数据包文件 data/hatmod/damage_type/flash.json 定义它，
 * 目的是让光柱的每刻伤害都真正结算。
 *
 * <p>原版对同一实体连续受伤有 10 刻无敌帧。1.21.1 有 minecraft:bypasses_cooldown
 * 伤害标签可以绕过它；1.20.1 没有这个标签，所以在 {@code HatAbilities#damageBeam} 里
 * 直接把受击者的 invulnerableTime 清零来达到同样效果。
 * 总伤害 = 每刻伤害 × 照射刻数，具体数值见 {@link HatType}。
 */
public final class HatDamageTypes {
    public static final ResourceKey<DamageType> FLASH =
            ResourceKey.create(Registries.DAMAGE_TYPE, HatMod.id("flash"));

    private HatDamageTypes() {
    }

    public static void register() {
    }
}
