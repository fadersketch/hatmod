package com.hatmod.mixin;

import com.hatmod.WebImmunity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.WebBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 让「链路期间」的受保护实体**完全无视蛛网**。
 *
 * <p>原版 {@link WebBlock#entityInside} 会调 {@code makeStuckInBlock}，把移动倍率压成
 * {@code (0.25, 0.05, 0.25)}；这一步在 {@code Entity#move} 里执行，客户端和服务端各算一份，
 * 所以「在 tick 里清掉倍率」永远慢半拍（客户端预测先跑）。这里直接在源头上取消：
 * 受保护的实体踩进网里就像踩进空气。
 */
@Mixin(WebBlock.class)
public abstract class WebBlockMixin {

    @Inject(method = "entityInside", at = @At("HEAD"), cancellable = true)
    private void hatmod$skipWebTrap(BlockState state, Level level, BlockPos pos, Entity entity,
                                    CallbackInfo callback) {
        if (WebImmunity.isProtected(entity)) {
            callback.cancel();
        }
    }
}
