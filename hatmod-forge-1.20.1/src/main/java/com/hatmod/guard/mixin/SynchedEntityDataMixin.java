package com.hatmod.guard.mixin;

import com.hatmod.guard.HatGuard;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code SynchedEntityData#set} 头部的破防守护：守护对象的**血量同步数据**
 * 不许被外来代码直接写 —— 这条路径绕开所有 setHealth 覆写，是最阴的一招。
 *
 * <p>两个重载（普通 / 带强制位的）都拦；客户端的同步走 {@code assignValues} 不经过
 * {@code set}，而且守护只在服务端生效，不会造成显示层面的不同步。
 */
@Mixin(SynchedEntityData.class)
public abstract class SynchedEntityDataMixin {

    @Inject(method = {
            "set(Lnet/minecraft/network/syncher/EntityDataAccessor;Ljava/lang/Object;)V",
            "m_135381_(Lnet/minecraft/network/syncher/EntityDataAccessor;Ljava/lang/Object;)V"},
            at = @At("HEAD"), cancellable = true, expect = 0, require = 0)
    private void hatmod$guardDataSet(EntityDataAccessor<?> key, Object value, CallbackInfo ci) {
        if (HatGuard.blockHealthData((SynchedEntityData) (Object) this, key, value)) {
            ci.cancel();
        }
    }

    @Inject(method = {
            "set(Lnet/minecraft/network/syncher/EntityDataAccessor;Ljava/lang/Object;Z)V",
            "m_276349_(Lnet/minecraft/network/syncher/EntityDataAccessor;Ljava/lang/Object;Z)V"},
            at = @At("HEAD"), cancellable = true, expect = 0, require = 0)
    private void hatmod$guardDataSetForced(EntityDataAccessor<?> key, Object value,
            boolean force, CallbackInfo ci) {
        if (HatGuard.blockHealthData((SynchedEntityData) (Object) this, key, value)) {
            ci.cancel();
        }
    }
}
