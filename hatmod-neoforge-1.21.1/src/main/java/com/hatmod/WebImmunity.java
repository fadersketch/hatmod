package com.hatmod;

import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「链路期间不被蛛网粘住」的唯一判据，给 {@code mixin.WebBlockMixin} 用。
 *
 * <p>为什么不是在 tick 里清 {@code stuckSpeedMultiplier}：原版 {@code Entity#move} 开头就会把
 * 上一个刻设好的倍率乘进位移里，客户端预测又比服务端早一步，清起来总有时间差 —— 表现出来就是
 * 「走一步顿一下」。这里改成**在蛛网方块生效之前直接拦掉**，两边都不再被粘。
 *
 * <p>服务端这份名单由 {@code BrainrotCycle.unwebNearby} 每刻更新（戴帽者 + 24 格内玩家 + 己方单位）；
 * 客户端这份只关心本机玩家，由 {@code client.ClientWebImmunity} 每刻更新。
 */
public final class WebImmunity {

    /** 服务端：本轮受保护的实体。 */
    private static final Set<UUID> SERVER_PROTECTED = ConcurrentHashMap.newKeySet();

    /** 客户端：本机玩家这一刻是否受保护。 */
    private static volatile boolean clientProtected;

    private WebImmunity() {
    }

    /** 这个实体现在要不要无视蛛网。 */
    public static boolean isProtected(Entity entity) {
        if (entity.level().isClientSide()) {
            // 客户端只模拟本机玩家，所以一个布尔就够
            return clientProtected;
        }
        return SERVER_PROTECTED.contains(entity.getUUID());
    }

    public static void setClientProtected(boolean value) {
        clientProtected = value;
    }

    public static void protect(Entity entity) {
        SERVER_PROTECTED.add(entity.getUUID());
    }

    public static void clearServer() {
        SERVER_PROTECTED.clear();
    }
}
