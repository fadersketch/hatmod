package com.hatmod;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 本模组自己的状态效果。
 *
 * <p>只有「神隐」一个 —— 白帽附魔「光辉」的支援隐身。它**就是原版隐身**：由 {@link HatAbilities}
 * 把这个实体的**隐身标记**直接置上（原版隐身效果内部也只是置这个标记），渲染完全走原版那一套
 * （模型消失，护甲 / 手持物按原版规矩照旧可见）。状态栏上只有「神隐」这一条，不会多出「隐身」。
 *
 * <p>比原版多做的一件事是**索敌**：原版隐身对大部分生物已经是「查无此人」，但**已经锁上来**的
 * 目标不会因为对方变隐身就放手（走得越近越明显），个别 Boss 甚至直接无视隐身。
 * 「定期把已经锁上来的甩掉」在 {@link HatAbilities} 的每刻维护里，新的锁定由
 * {@link HatAbilities} 的索敌拦截处理。
 *
 * <p>效果本体是**纯标记**：真正的逻辑都在 {@code HatAbilities} 里。之所以不写
 * {@code applyEffectTick}，是因为隐身标记得在整个效果表遍历**结束之后**再置 ——
 * 原版会在遍历结束后按效果重推一遍隐身标记（{@code updateInvisibilityStatus}），
 * 写在效果自己的 tick 里会被它当场覆盖掉。索性两件事放在同一处做。
 */
public final class HatEffects {

    /** 效果注册表。翻译键是 {@code effect.hatmod.veil}。 */
    public static final DeferredRegister<MobEffect> EFFECTS =
            DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, HatMod.MOD_ID);

    /** 「神隐」：就是原版隐身，另加「定期甩掉已经锁上来的敌人」（图标颜色是偏冷的苍白）。 */
    public static final RegistryObject<MobEffect> VEIL = EFFECTS.register("veil", VeilEffect::new);

    private HatEffects() {
    }

    public static void register(IEventBus modBus) {
        EFFECTS.register(modBus);
    }

    /**
     * 本体：良性、无粒子、无图标的普通效果（状态栏上的图标由调用方挂的效果实例决定）。
     *
     * <p>写成独立的具名类而不是匿名类，是为了让 {@link HatPreloader} 能把它登记进去 ——
     * 匿名类会变成 {@code HatEffects$1} 这种合成类，同样需要预加载，不如起个名字。
     */
    private static final class VeilEffect extends MobEffect {
        private VeilEffect() {
            super(MobEffectCategory.BENEFICIAL, 0xE9F3FF);
        }
    }
}
