package com.hatmod.guard.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * winefox delight 的恐怖配乐调度：每 10 刻扫一次「最近的怨灵」，扫到了就放
 * {@code ghost_horror_music}，顺带把 MUSIC / RECORDS 的音量压下去（压掉别人的 BGM）。
 *
 * <p>Teto 在岗时让它「看不见」怨灵 —— 配乐不起、压音也不发生，我们的 BGM 独自放行。
 * 服务端那边 Teto 已经把鬼按在原地，这里的客户端拦截负责音画两端。
 *
 * <p>目标是**别的模组的类**：{@code remap = false}，模组不在时静默跳过（{@code require = 0}）。
 */
@Mixin(targets = "cn.blockforge.generated.mod3bd0b4a0.GhostMusicClient", remap = false)
public abstract class GhostMusicHideMixin {

    @Inject(method = "nearestGhost(Lnet/minecraft/client/Minecraft;"
                    + "Lnet/minecraft/client/player/LocalPlayer;)"
                    + "Lcn/blockforge/generated/mod3bd0b4a0/KitsuneGhostEntity;",
            at = @At("HEAD"), cancellable = true, expect = 0, require = 0, remap = false)
    private void hatmod$hideGhostFromMusic(CallbackInfoReturnable<Object> cir) {
        if (com.hatmod.client.GhostWarden.isSuppressing()) {
            cir.setReturnValue(null);
        }
    }
}
