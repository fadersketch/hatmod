package com.hatmod.client;

import com.hatmod.HatAbilities;
import com.hatmod.HatMod;
import com.hatmod.Teto;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Teto 在岗时的「怨灵拦截」客户端侧：卡掉 winefox delight 的恐怖配乐和惊吓音，
 * 顶回它们起乐时压低的 MUSIC / RECORDS 音量（我们的帽子 BGM 独自放行）；
 * 鬼图本体在 {@code com.hatmod.guard.mixin} 的两个 client mixin 里拦
 * （{@code GhostLethalScareClient.trigger} 整个吞掉）。
 *
 * <p>触发条件：怨灵 / 凝视者靠近本地玩家（24 格），且玩家身边有 Teto 在 ——
 * 「Teto 遇见了它」才算数；她不在场时一切照旧。
 */
@EventBusSubscriber(modid = HatMod.MOD_ID, value = Dist.CLIENT)
public final class GhostWarden {

    private static final ResourceLocation GHOST_MUSIC =
            ResourceLocation.fromNamespaceAndPath("maid_dismemberment", "ghost_horror_music");
    private static final ResourceLocation GHOST_SCARE =
            ResourceLocation.fromNamespaceAndPath("maid_dismemberment", "ghost_lethal_scare");
    private static final ResourceLocation GHOST_CRY_SOB =
            ResourceLocation.fromNamespaceAndPath("maid_dismemberment", "ghost_cry_sobbing");
    private static final ResourceLocation GHOST_CRY_WAIL =
            ResourceLocation.fromNamespaceAndPath("maid_dismemberment", "ghost_cry_wail");

    /** 怨灵离玩家多近才进入拦截范围（格）。 */
    private static final double GHOST_RANGE = 24.0D;
    /** 玩家身边有 Teto 在岗，才算「Teto 遇见了它」。 */
    private static final double TETO_RANGE = 24.0D;

    private static boolean suppressing;

    private GhostWarden() {
    }

    /** 现在 Teto 是不是正压着一场怨灵跳脸。两个 client mixin 读这个决定拦不拦。 */
    public static boolean isSuppressing() {
        return suppressing;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        suppressing = false;
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null || minecraft.isPaused()) {
            return;
        }
        boolean ghostNear = !level.getEntitiesOfClass(LivingEntity.class,
                player.getBoundingBox().inflate(GHOST_RANGE), HatAbilities::isGhostMob).isEmpty();
        if (!ghostNear) {
            return;
        }
        boolean tetoNear = !level.getEntitiesOfClass(Teto.class,
                player.getBoundingBox().inflate(TETO_RANGE),
                teto -> teto.isAlive() && !teto.isRemoved()).isEmpty();
        if (!tetoNear) {
            return;
        }
        suppressing = true;
        // 卡掉它们的配乐和惊吓音（每刻停一次：它们重播也压得住）
        minecraft.getSoundManager().stop(GHOST_MUSIC, null);
        minecraft.getSoundManager().stop(GHOST_SCARE, null);
        // 哭声是定向广播，混在 HOSTILE 音源里每刻重播：每刻 stop 压住已在播的实例，
        // 新发起的由 GhostSoundBlockMixin 在播放层拦
        minecraft.getSoundManager().stop(GHOST_CRY_SOB, null);
        minecraft.getSoundManager().stop(GHOST_CRY_WAIL, null);
        // 同时放行自己的 BGM：把怨灵起乐时压低的 MUSIC / RECORDS 顶回玩家设置的音量
        minecraft.getSoundManager().updateSourceVolume(SoundSource.MUSIC,
                minecraft.options.getSoundSourceVolume(SoundSource.MUSIC));
        minecraft.getSoundManager().updateSourceVolume(SoundSource.RECORDS,
                minecraft.options.getSoundSourceVolume(SoundSource.RECORDS));
    }
}
