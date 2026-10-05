package com.hatmod;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageType;

/**
 * 自定义伤害类型。数据包文件 data/hatmod/damage_type/flash.json 定义它，
 * 并通过 data/minecraft/tags/damage_type/bypasses_cooldown.json 加入
 * minecraft:bypasses_cooldown 标签 —— 这是关键：原版对同一实体连续受伤有 10 刻无敌帧，
 * 只有带该标签的伤害类型才能每刻都真正结算。
 * 总伤害 = 每刻伤害 × 照射刻数，具体数值见 {@link HatType}。
 */
public final class HatDamageTypes {
    public static final ResourceKey<DamageType> FLASH =
            ResourceKey.create(Registries.DAMAGE_TYPE, HatMod.id("flash"));

    private HatDamageTypes() {
    }
}
