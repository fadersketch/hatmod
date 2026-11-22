package com.hatmod;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 手持「脑蚀」右键**她本人**：叫她走。
 *
 * <p>以前她只有「曲终留一只，然后谁也清不掉」这一条路 —— 散场 / 溜走那几句
 * 于是永远听不到。现在给玩家一个明确的动作：手里拿着脑蚀，对着她右键。
 *
 * <ul>
 *   <li>她正在场上跳（这一轮没完）：算**散场** —— 说散场那句，这一只走掉；</li>
 *   <li>只是曲终留下、跟着你的那只：算**溜走** —— 说完就散。</li>
 * </ul>
 *
 * <p>只对**自己名下**的那只生效：别人的 Teto 不归你管，右键她跟没按一样。
 * 手里拿的不是脑蚀时这一下完全不拦，换舞步 / 留守 / 喂食照旧。
 */
@EventBusSubscriber(modid = HatMod.MOD_ID)
public final class TetoDismiss {

    /** 说话半径（格）。 */
    private static final double RANGE = 32.0D;

    /**
     * 刚用帽子点过她的玩家（UUID -> 当时的游戏刻）。
     *
     * <p>右键她这一下之后，接下来 {@link #SUPPRESS_TICKS} 刻里的「使用物品」如果还是帽子，
     * 一并拦掉 —— 见 {@link #onRightClickItem}。
     */
    private static final java.util.Map<java.util.UUID, Long> HAT_SUPPRESS =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 压制窗口（刻）。 */
    private static final int SUPPRESS_TICKS = 2;

    private TetoDismiss() {
    }

    /**
     * 右键她之后紧跟着的那一下「使用物品」，只要是帽子就吞掉。
     *
     * <p>原版在实体交互没接住时会继续走物品自己的 {@code use}，而帽子是头盔类 ——
     * 那一步就是「戴到自己头上」。所以我们叫她走，就得把这一下也拦住。
     * 客户端也拦，免得客户端先戴上、再被服务端打回来闪一下。
     */
    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (!HatItems.isHat(event.getItemStack())) {
            return;
        }
        Long when = HAT_SUPPRESS.get(event.getEntity().getUUID());
        if (when == null || Math.abs(event.getLevel().getGameTime() - when) > SUPPRESS_TICKS) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof Teto teto) || !teto.isOwnedBy(event.getEntity())) {
            return;
        }
        // 双手各发一次包：只认主手（同 TetoAction / TetoSitting）
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        if (!event.getItemStack().is(HatItems.BRAIN_HAT.get())) {
            return;
        }
        Player player = event.getEntity();
        // **两端都要吞掉**：客户端不吞的话，这一下会被当成「没接住」，接着走物品自己的 use ——
        // 帽子是头盔类，一「用」就戴到自己头上。于是变成「叫她走，帽子却跑到自己头上」。
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        HAT_SUPPRESS.put(player.getUUID(), event.getLevel().getGameTime());
        if (!(event.getLevel() instanceof ServerLevel level)
                || !(player instanceof net.minecraft.server.level.ServerPlayer owner)) {
            return;
        }
        // 台上那只 = 散场；只是跟着你的那只 = 溜走
        boolean performing = teto.isSummoned() && !teto.isSitting();
        String key = TetoSpeech.pick(level.random, TetoSpeech.summonsOf(owner.getUUID()),
                performing ? "hatmod.teto.leave" : "hatmod.teto.free.vanish",
                performing ? 2 : 3, performing ? 1 : 2);
        TetoTalk.sayNear(level, teto, RANGE, key);
        if (performing) {
            // 链路中途：**一次收 4 只**（本体 + 光柱分身）。只收一只的话，另外三只还在，
            // 链路就当作「这一轮还活着」，等于没按。
            for (Teto other : Teto.ownedBy(owner)) {
                if (!other.isSummoned() || other.isSitting()) {
                    continue;               // 散养 / 留守的不属于这一轮
                }
                ServerLevel at = other.level() instanceof ServerLevel own ? own : level;
                other.disappear(at, "玩家拿脑蚀把这一轮收掉");
            }
            // 立刻收场（不等「全部阵亡」那 3 次判定），顺便上 3 秒静默：
            // 不收场的话下一拍就重开一轮，看着像根本没关掉。
            BrainrotCycle.dismissByPlayer(level, owner);
            return;
        }
        // 非链路：只收被瞄准的那一只
        teto.disappear(level, "玩家拿脑蚀叫她走");
    }
}
