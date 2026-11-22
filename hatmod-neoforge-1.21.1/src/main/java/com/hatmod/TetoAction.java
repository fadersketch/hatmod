package com.hatmod;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 空手右键切换 Teto 的动作（动画序列）。
 *
 * <p>潜行 + 空手是「留在原地」的手势（见 {@code TetoSitting}），拿食物是喂（见
 * {@code TetoFeeding}），所以空手、不潜行这一下正好空着，用来换动作。
 *
 * <p>规矩：
 * <ul>
 *   <li>只有主人能换（别人的 Teto 不归你管）；</li>
 *   <li>曲子正在放的时候**整个按键无效** —— 不切、不出声、不冒粒子，
 *       就当没按到（链路开始那一刻服务端也会把所有 Teto 的动作强制回到原来那套）；</li>
 *   <li>切换状态存在实体上（{@code DATA_ACTION}），存档、重进都还在。</li>
 * </ul>
 */
@EventBusSubscriber(modid = HatMod.MOD_ID)
public final class TetoAction {

    private TetoAction() {
    }

    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof Teto teto)) {
            return;
        }
        // 双手各发一次，只认主手（同 TetoSitting）
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        Player player = event.getEntity();
        // 木棍右键：静音 / 恢复动作 2 的舞曲。
        // 音乐是在**客户端**放的（音量要能随时压到 0），所以这里只负责通知本人一声 ——
        // 静音是按玩家自己的，服务端不记这个状态。
        if (event.getItemStack().is(Items.STICK)) {
            // 两端都吞（客户端也吞，别让木棍走一遍物品的 use）
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            if (player instanceof ServerPlayer serverPlayer) {
                HatNetwork.sendTetoMusicToggle(serverPlayer);
            }
            return;
        }
        // 手里拿什么都没关系（以前要求空手，太挑）—— 只有「喂食」那一样要让给它：
        // 拿着食物右键是喂，不是换动作。
        // 手里拿的是脑蚀：那是「叫她走」（见 TetoDismiss），不是换舞步
        if (player.isShiftKeyDown() || !teto.isOwnedBy(player)
                || event.getItemStack().is(HatItems.BRAIN_HAT.get())
                || TetoFeeding.isSnack(event.getItemStack())) {
            return;
        }
        if (!teto.canToggleAction()) {
            // 曲中：判定为无效按键 —— 什么都不做（连声音都没有）
            return;
        }
        // 两端都吞：换舞步这一下别让手里的东西跟着被「用」掉
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        // 循环换：原版 → 动作 1 → 动作 2 → 原版
        int next = (teto.actionIndex() + 1) % Teto.ACTION_COUNT;
        teto.setActionIndex(next);
        if (next != Teto.ACTION_ORIGINAL && player instanceof ServerPlayer serverPlayer) {
            // 她记得你让她换过舞步
            HatSettings.rememberTeto(serverPlayer.getUUID(), TetoSpeech.DANCE);
            // 也记得你选的是哪一套：换个存档再叫她出来，她直接摆好那支舞（见 TetoRecall）
            TetoRecall.noteAction(serverPlayer.getUUID(), next);
        }
        level.sendParticles(ParticleTypes.NOTE,
                teto.getX(), teto.getY() + teto.getBbHeight() * 0.75D, teto.getZ(),
                6, 0.35D, 0.25D, 0.35D, 0.6D);
        level.playSound(null, teto.getX(), teto.getY(), teto.getZ(),
                SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.6F,
                next == Teto.ACTION_ORIGINAL ? 0.9F : (next == 1 ? 1.4F : 1.75F));
    }
}
