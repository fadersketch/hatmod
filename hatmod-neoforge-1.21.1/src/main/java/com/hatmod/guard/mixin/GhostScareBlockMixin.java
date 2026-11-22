package com.hatmod.guard.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * winefox delight 的「致死惊吓」客户端入口（{@code trigger}）：怨灵的鬼图 + 惊吓音
 * 都从这一下放出来。Teto 在岗时（{@code GhostWarden#isSuppressing}）整个吞掉 ——
 * 鬼图到不了玩家的屏幕上。
 *
 * <p>目标是**别的模组的类**：{@code remap = false}（它不在混淆表里），
 * 那个模组不在时目标找不到、静默跳过（{@code require = 0}）。
 */
@Mixin(targets = "cn.blockforge.generated.mod3bd0b4a0.GhostLethalScareClient", remap = false)
public abstract class GhostScareBlockMixin {

    @Inject(method = "trigger()V", at = @At("HEAD"), cancellable = true,
            expect = 0, require = 0, remap = false)
    private void hatmod$blockGhostScare(CallbackInfo ci) {
        if (com.hatmod.client.GhostWarden.isSuppressing()) {
            ci.cancel();
        }
    }
}
