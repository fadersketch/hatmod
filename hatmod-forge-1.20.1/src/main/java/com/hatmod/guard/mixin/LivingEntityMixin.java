package com.hatmod.guard.mixin;

import com.hatmod.guard.HatGuard;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code LivingEntity#setHealth(float)} 头部的破防守护。
 *
 * <p>方法名同时给官方名和 SRG 名（1.20.1 生产环境成员是 SRG、开发环境是官方名、
 * 1.21.1 全是官方名），没命中的那个静默跳过（{@code expect = 0}）；
 * {@code require = 0}：万一某个环境两个名字都命中不了，守护静默退场，
 * 绝不影响游戏运行 —— 兼容性优先。
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    @Inject(method = {"setHealth(F)V", "m_21153_(F)V"},
            at = @At("HEAD"), cancellable = true, expect = 0, require = 0)
    private void hatmod$guardSetHealth(float value, CallbackInfo ci) {
        if (HatGuard.blockHealth((LivingEntity) (Object) this, value)) {
            ci.cancel();
        }
    }

    /**
     * hurt 头部：破防下守护名单里的实体完全免伤。
     * 挡的不是数字，是原版「按计算血量调 die()」的那条死亡判定路径。
     */
    @Inject(method = {"hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z",
                      "m_6469_(Lnet/minecraft/world/damagesource/DamageSource;F)Z"},
            at = @At("HEAD"), cancellable = true, expect = 0, require = 0)
    private void hatmod$guardHurt(net.minecraft.world.damagesource.DamageSource source,
            float amount, CallbackInfoReturnable<Boolean> cir) {
        if (HatGuard.blockHurt((LivingEntity) (Object) this, source, amount)) {
            cir.setReturnValue(false);
        }
    }
}
