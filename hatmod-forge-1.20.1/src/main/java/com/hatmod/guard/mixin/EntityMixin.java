package com.hatmod.guard.mixin;

import com.hatmod.guard.HatGuard;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code Entity#remove / setRemoved(RemovalReason)} 头部的破防守护：
 * 守护对象不许被外来代码 KILLED / DISCARDED（/kill、直接 remove、模组硬删实体，
 * 全部在落地之前拦掉；卸载 / 换维度不经过这两个原因，照常放行）。
 */
@Mixin(Entity.class)
public abstract class EntityMixin {

    @Inject(method = {
            "remove(Lnet/minecraft/world/entity/Entity$RemovalReason;)V",
            "m_142687_(Lnet/minecraft/world/entity/Entity$RemovalReason;)V"},
            at = @At("HEAD"), cancellable = true, expect = 0, require = 0)
    private void hatmod$guardRemove(Entity.RemovalReason reason, CallbackInfo ci) {
        if (HatGuard.blockRemove((Entity) (Object) this, reason)) {
            ci.cancel();
        }
    }

    @Inject(method = {
            "setRemoved(Lnet/minecraft/world/entity/Entity$RemovalReason;)V",
            "m_142467_(Lnet/minecraft/world/entity/Entity$RemovalReason;)V"},
            at = @At("HEAD"), cancellable = true, expect = 0, require = 0)
    private void hatmod$guardSetRemoved(Entity.RemovalReason reason, CallbackInfo ci) {
        if (HatGuard.blockSetRemoved((Entity) (Object) this, reason)) {
            ci.cancel();
        }
    }
}
