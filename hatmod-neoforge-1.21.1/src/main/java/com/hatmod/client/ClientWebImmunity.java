package com.hatmod.client;

import com.hatmod.HatAbilities;
import com.hatmod.HatItems;
import com.hatmod.HatMod;
import com.hatmod.WebImmunity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 客户端侧的「链路期间不被蛛网粘住」。
 *
 * <p>服务端那边（{@code BrainrotCycle.unwebNearby}）把 {@code stuckSpeedMultiplier} 清零，
 * 只能让**服务端**不减速；玩家自己看到的那个身体是客户端预测出来的，粘不粘人由客户端这份实体决定。
 * 所以两边都要清一次，否则表现为「人被拽回来、走一步顿一下」。
 *
 * <p>条件和服务端一致：附近 24 格内有人戴脑蚀。
 */
@EventBusSubscriber(modid = HatMod.MOD_ID, value = Dist.CLIENT)
public final class ClientWebImmunity {

    /** 和 {@code BrainrotCycle.WEB_IMMUNITY_RANGE} 保持一致（24 格）。 */
    private static final double RANGE = 24.0D;

    private ClientWebImmunity() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        clearTrap();
    }

    /** 每 5 秒最多打一行日志，用来确认这条路径真的跑到了。 */
    private static long lastLogTick;

    /** 每刻看一眼自己：附近有人戴脑蚀、自己又在网里，就把「被粘」清掉。 */
    private static void clearTrap() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer self = minecraft.player;
        if (self == null || minecraft.level == null) {
            return;
        }
        // 真正的免粘由 mixin 取消 WebBlock#entityInside 来完成，这里只负责告诉它「我算不算自己人」
        boolean sheltered = isSheltered(self);
        WebImmunity.setClientProtected(sheltered);
        if (!sheltered) {
            return;
        }
        BlockState state = minecraft.level.getBlockState(self.blockPosition());
        if (!state.is(Blocks.COBWEB)) {
            return;
        }
        self.makeStuckInBlock(state, Vec3.ZERO);
        if (self.tickCount - lastLogTick > 100) {
            lastLogTick = self.tickCount;
            HatMod.LOGGER.info("[HatMod] 蛛网免疫（客户端）：{} 在网里，已清掉减速", self.getName().getString());
        }
    }

    /**
     * 自己戴着脑蚀就算 —— 蛛网只可能由这顶帽子产生，不需要再去找「附近谁戴着」。
     *
     * <p>另外保留一条：24 格内有别人戴着脑蚀时，自己也能免粘（队友跟着一起走）。
     */
    private static boolean isSheltered(LocalPlayer self) {
        if (HatAbilities.hatStack(self).is(HatItems.BRAIN_HAT.get())) {
            return true;
        }
        double rangeSq = RANGE * RANGE;
        for (Player other : self.level().players()) {
            if (other != self && other.distanceToSqr(self) <= rangeSq
                    && HatAbilities.hatStack(other).is(HatItems.BRAIN_HAT.get())) {
                return true;
            }
        }
        return false;
    }
}
