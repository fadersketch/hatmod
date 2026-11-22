package com.hatmod.guard.mixin;

import com.hatmod.client.GhostWarden;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Teto 在岗压制期间，把 winefox delight 的**定向广播音效**在客户端播放层按
 * 命名空间拦掉 —— 典型的是怨灵哭声（{@code ghost_cry_sobbing / ghost_cry_wail}）：
 * 服务端 {@code GhostCryEvents} 按玩家定向发包，客户端在
 * {@code ClientLevel#playSeededSound} 就地播放。这条路径绕开了实体的
 * silent 标志（服务端那层见 {@code HatAbilities#pinInPlace}），只能在这一层拦。
 *
 * <p>两处注入分别对应「按坐标」和「跟随实体」两种广播；{@code Level} 的所有
 * {@code playSound} 变体最终都汇到这两个方法。压制期间只有**这个模组**的声音
 * 被拦，别的照常。双方法名（官名 + SRG）兼容 1.20.1 运行时；
 * {@code require = 0}，对不上时静默跳过。
 */
@Mixin(net.minecraft.client.multiplayer.ClientLevel.class)
public abstract class GhostSoundBlockMixin {

    /** 是不是正在压制（Teto 在岗 + 鬼在场）的、来自 winefox delight 的声音。 */
    private static boolean hatmod$isBlockedSound(Holder<SoundEvent> sound) {
        if (sound == null) {
            return false;
        }
        SoundEvent event = sound.value();
        if (event == null) {
            return false;
        }
        ResourceLocation id = event.getLocation();
        return id != null && "maid_dismemberment".equals(id.getNamespace());
    }

    @Inject(method = {
            "playSeededSound(Lnet/minecraft/world/entity/player/Player;DDD"
                    + "Lnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
            "m_262808_(Lnet/minecraft/world/entity/player/Player;DDD"
                    + "Lnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V"},
            at = @At("HEAD"), cancellable = true, expect = 0, require = 0)
    private void hatmod$silenceGhostBroadcast(Player player, double x, double y, double z,
                                              Holder<SoundEvent> sound, SoundSource source,
                                              float volume, float pitch, long seed,
                                              CallbackInfo ci) {
        if (GhostWarden.isSuppressing() && hatmod$isBlockedSound(sound)) {
            ci.cancel();
        }
    }

    @Inject(method = {
            "playSeededSound(Lnet/minecraft/world/entity/player/Player;"
                    + "Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/Holder;"
                    + "Lnet/minecraft/sounds/SoundSource;FFJ)V",
            "m_213890_(Lnet/minecraft/world/entity/player/Player;"
                    + "Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/Holder;"
                    + "Lnet/minecraft/sounds/SoundSource;FFJ)V"},
            at = @At("HEAD"), cancellable = true, expect = 0, require = 0)
    private void hatmod$silenceGhostBroadcastOnEntity(Player player, Entity entity,
                                                      Holder<SoundEvent> sound,
                                                      SoundSource source,
                                                      float volume, float pitch, long seed,
                                                      CallbackInfo ci) {
        if (GhostWarden.isSuppressing() && hatmod$isBlockedSound(sound)) {
            ci.cancel();
        }
    }
}
