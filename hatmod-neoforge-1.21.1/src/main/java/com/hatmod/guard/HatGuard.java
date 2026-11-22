package com.hatmod.guard;

import com.hatmod.HatAbilities;
import com.hatmod.HatSettings;
import com.hatmod.HatSummon;
import com.hatmod.TangJiaqi;
import com.hatmod.TangJiaqiWhite;
import com.hatmod.Teto;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;

/**
 * 破防守护的运行时判断（由 {@code com.hatmod.guard.mixin} 里的三个 Mixin 在原版方法头部调用）。
 *
 * <p>四个入口，全部只做三件事：看开关（{@code ACTIVE}，随 {@link HatSettings#forceBreak()}
 * 同步）、看对象是不是守护名单里的、看这次写入是不是「致命」的 ——
 * 普通掉血照旧放行，破防不改游戏性，只把「绕过事件和覆写、直接弄死守护对象」的那几条路堵死：
 * <ul>
 *   <li>{@code LivingEntity#setHealth}：守护对象不许被写成 NaN / 负数 / 0；</li>
 *   <li>{@code SynchedEntityData#set}：血量的**同步数据**不许被外来代码直接写
 *       （这条路径绕开所有 setHealth 覆写）；</li>
 *   <li>{@code Entity#remove / setRemoved}：守护对象不许被外来代码 KILLED / DISCARDED ——
 *       /kill、直接 remove、模组硬删实体，全在这层落地之前拦掉。</li>
 * </ul>
 *
 * <p>守护名单：戴帽玩家（{@link HatAbilities#isGuardedPlayer}）+ 召唤物
 * （Teto / HatSummon / 唐嘉琦系列）。正常生物 / 其它模组的实体（含枪弹天使）都不在名单里，
 * 一切行为与原版一致 —— 破防的兼容性就守在这里。
 *
 * <p>它们自己的 {@code remove / setHealth} 覆写在放行自收场时用
 * {@link #beginApprovedRemoval} / {@link #beginApprovedHealth} 包住，注入的检查看到
 * token 就放行 —— 双向不打架。守护全部只在**服务端**生效，客户端不同步、不拦截。
 */
public final class HatGuard {
    private static final Logger LOGGER = LogManager.getLogger("HatMod/Guard");

    /** 破防开关的镜像：注入的检查只读这个 static，随 HatSettings 同步。 */
    public static volatile boolean ACTIVE;

    private static final ThreadLocal<Boolean> APPROVED_REMOVAL = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> APPROVED_HEALTH = ThreadLocal.withInitial(() -> false);

    /** LivingEntity.DATA_HEALTH_ID（官方名 / SRG f_20961_），懒解析；拿不到则数据层守护自动停用。 */
    private static volatile Object healthAccessor;
    private static boolean healthAccessorFailed;

    /** SynchedEntityData 的实体回引字段（官方名 entity / SRG f_135344_），懒解析。 */
    private static volatile Field dataOwnerField;
    private static boolean dataOwnerFailed;

    /** 已经打过几条拦截日志（每个入口限几条，防刷屏）。 */
    private static int removeLogged;
    private static int healthLogged;

    private HatGuard() {
    }

    /** 开关同步：HatSettings 变化处 + 每个服务端刻调用。 */
    public static void syncSwitch() {
        ACTIVE = HatSettings.forceBreak();
    }

    // ------------------------------------------------------------------
    // Mixin 调用的检查
    // ------------------------------------------------------------------

    public static boolean blockHealth(LivingEntity entity, float value) {
        if (!ACTIVE || APPROVED_HEALTH.get()) {
            return false;
        }
        try {
            if (entity == null || entity.level() == null || entity.level().isClientSide()
                    || !isProtected(entity)) {
                return false;
            }
            boolean blocked = isLethalWrite(value, entity.getHealth());
            if (blocked) {
                logHealth(entity, value);
            }
            return blocked;
        } catch (Throwable ignored) {
            // 这几行跑在原版方法**头部**：一旦抛异常，原版流程就断了 ——
            // 登录阶段会被 placeNewPlayer 包成 multiplayer.disconnect.invalid_player_data，
            // 看起来像存档损坏。守护检查无论如何都不许把异常抛回去。
            return false;
        }
    }

    public static boolean blockHealthData(SynchedEntityData data, EntityDataAccessor<?> key,
                                          Object value) {
        if (!ACTIVE || APPROVED_HEALTH.get()) {
            return false;
        }
        try {
            if (data == null || key == null || key != healthAccessor()) {
                return false;
            }
            Entity owner = dataOwner(data);
            if (!(owner instanceof LivingEntity living) || living.level() == null
                    || living.level().isClientSide() || !isProtected(living)) {
                return false;
            }
            boolean blocked = isLethalWrite(value instanceof Float number ? number : Float.NaN,
                    living.getHealth());
            if (blocked) {
                logHealth(living, value instanceof Float number ? number : Float.NaN);
            }
            return blocked;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * {@code LivingEntity#hurt} 头部的守护：破防下守护名单里的实体**完全免伤**。
     *
     * <p>这一层堵的是一条缝：原版 hurt 是「先算 {@code f1 = 血量 - 伤害}，
     * 写血，再按 {@code f1 <= 0} 调 {@code die()}」—— 只拦 setHealth 的话，
     * 写血确实被拦了，但 {@code die()} 仍会按**计算值**触发，
     * 于是出现「血量没掉、死亡事件却响了」的 5 秒死亡循环（1.5.203 实测）。
     * 在头部直接取消，整条伤害管线（含死亡判定）都不进。
     *
     * <p>召唤物自己的 {@code hurt} 覆写（台词 / 闪避 / 反击）照常先跑 ——
     * 这里拦的是基类里的**伤害落地**，不是它们的反应逻辑。
     */
    public static boolean blockHurt(LivingEntity entity, DamageSource source, float amount) {
        if (!ACTIVE) {
            return false;
        }
        try {
            if (entity == null || entity.level() == null || entity.level().isClientSide()) {
                return false;
            }
            return isProtected(entity);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean blockRemove(Entity entity, Entity.RemovalReason reason) {
        return blockRemoval(entity, reason, "remove");
    }

    public static boolean blockSetRemoved(Entity entity, Entity.RemovalReason reason) {
        return blockRemoval(entity, reason, "setRemoved");
    }

    private static boolean blockRemoval(Entity entity, Entity.RemovalReason reason, String entry) {
        if (!ACTIVE || APPROVED_REMOVAL.get()) {
            return false;
        }
        try {
            // 卸载 / 换维度是正常流程；只挡「弄死 / 直接清除」这两个原因
            if (reason != Entity.RemovalReason.KILLED && reason != Entity.RemovalReason.DISCARDED) {
                return false;
            }
            if (entity == null || entity.level() == null || entity.level().isClientSide()) {
                return false;
            }
            boolean blocked;
            if (entity instanceof ServerPlayer player) {
                // 已死的玩家放行 —— 死亡后的正常流程（尸体移除 / 重生换实体）不能卡住
                blocked = HatAbilities.isGuardedPlayer(player) && player.getHealth() > 0.0F;
            } else {
                blocked = entity instanceof Teto || entity instanceof HatSummon
                        || entity instanceof TangJiaqi || entity instanceof TangJiaqiWhite;
            }
            if (blocked && removeLogged++ < 5) {
                LOGGER.warn("[hatmod] 破防：拦下 {} 的 {}({})，调用方 {}",
                        entity.getType().toShortString(), entry, reason,
                        HatAbilities.foreignCaller());
            }
            return blocked;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 自收场放行 token（Teto / HatSummon / 唐嘉琦系列的覆写在放行时包住 super 调用）
    // ------------------------------------------------------------------

    public static void beginApprovedRemoval() {
        APPROVED_REMOVAL.set(true);
    }

    public static void endApprovedRemoval() {
        APPROVED_REMOVAL.set(false);
    }

    public static void beginApprovedHealth() {
        APPROVED_HEALTH.set(true);
    }

    public static void endApprovedHealth() {
        APPROVED_HEALTH.set(false);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static boolean isProtected(Entity entity) {
        if (entity instanceof ServerPlayer player) {
            return HatAbilities.isGuardedPlayer(player);
        }
        return entity instanceof Teto || entity instanceof HatSummon
                || entity instanceof TangJiaqi || entity instanceof TangJiaqiWhite;
    }

    /** 「致命写入」：NaN / 负数 / 归零。普通掉血（0 < 新值 < 旧值）放行，游戏性不变。 */
    private static boolean isLethalWrite(float value, float current) {
        return Float.isNaN(value) || value < 0.0F || (value <= 0.0F && current > 0.0F);
    }

    private static void logHealth(Entity entity, float value) {
        if (healthLogged++ < 5) {
            LOGGER.warn("[hatmod] 破防：拦下对 {} 的写血 {}（当前 {}），调用方 {}",
                    entity.getType().toShortString(),
                    Float.isNaN(value) ? "NaN" : Float.toString(value),
                    entity instanceof LivingEntity living ? Float.toString(living.getHealth()) : "?",
                    HatAbilities.foreignCaller());
        }
    }

    private static Object healthAccessor() {
        Object resolved = healthAccessor;
        if (resolved != null || healthAccessorFailed) {
            return resolved;
        }
        synchronized (HatGuard.class) {
            if (healthAccessor != null || healthAccessorFailed) {
                return healthAccessor;
            }
            for (String name : new String[]{"DATA_HEALTH_ID", "f_20961_"}) {
                try {
                    Field field = LivingEntity.class.getDeclaredField(name);
                    field.setAccessible(true);
                    healthAccessor = field.get(null);
                    return healthAccessor;
                } catch (NoSuchFieldException ignored) {
                    // 换一个名字
                } catch (Throwable throwable) {
                    break;
                }
            }
            healthAccessorFailed = true;
            LOGGER.warn("[hatmod] 破防：找不到 LivingEntity.DATA_HEALTH_ID，血量数据层守护停用"
                    + "（setHealth / remove 层仍然生效）");
            return null;
        }
    }

    /** SynchedEntityData 里的实体回引（1.20.1 生产环境是 SRG 名，按名字 + 类型双重校验）。 */
    private static Entity dataOwner(SynchedEntityData data) {
        Field field = dataOwnerField;
        if (field != null) {
            try {
                return (Entity) field.get(data);
            } catch (Throwable ignored) {
                return null;
            }
        }
        if (dataOwnerFailed) {
            return null;
        }
        synchronized (HatGuard.class) {
            if (dataOwnerField != null) {
                try {
                    return (Entity) dataOwnerField.get(data);
                } catch (Throwable ignored) {
                    return null;
                }
            }
            if (dataOwnerFailed) {
                return null;
            }
            for (String name : new String[]{"entity", "f_135344_"}) {
                try {
                    Field candidate = SynchedEntityData.class.getDeclaredField(name);
                    if (candidate.getType() != Entity.class) {
                        continue;
                    }
                    candidate.setAccessible(true);
                    dataOwnerField = candidate;
                    return (Entity) candidate.get(data);
                } catch (NoSuchFieldException ignored) {
                    // 换一个名字
                } catch (Throwable throwable) {
                    break;
                }
            }
            dataOwnerFailed = true;
            LOGGER.warn("[hatmod] 破防：找不到 SynchedEntityData 的实体回引，血量数据层守护停用"
                    + "（setHealth / remove 层仍然生效）");
            return null;
        }
    }
}
