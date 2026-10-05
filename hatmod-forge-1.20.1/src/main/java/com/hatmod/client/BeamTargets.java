package com.hatmod.client;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * 客户端记录"哪些戴帽子的生物正在照射、各自锁定了哪个敌人"。
 *
 * <p>由 {@code HatNetwork.BeamTargetMessage} 写入，{@link BeamRenderer} 读取。
 *
 * <p><b>为什么不用状态效果判断"正在照射"</b>：1.20.1 的原版只会把**生物**的状态效果
 * 同步给它的乘客 —— 只有 {@code ServerPlayer} 重写了那段逻辑、会额外发给自己一份。
 * 所以玩家自己的光柱看得见，女仆/其它生物身上的就永远传不到客户端；1.21.1 改成了
 * 走实体追踪器同步，所以那边没这个问题。两版现在统一改用本模组自己的网络包
 * （{@code TRACKING_ENTITY_AND_SELF}），玩家和生物走同一条路径，行为完全一致。
 *
 * <p>刻意只用一个 {@code Map<Integer, Integer>}、不额外定义内部类：
 * 内部类要跟着 {@link com.hatmod.HatPreloader} 的名单一起预加载才不会踩 jar 句柄被关的坑
 * （见 {@code HatState} 的注释），少一个类就少一处要维护的地方。
 */
public final class BeamTargets {

    /**
     * 戴帽者实体 id -> 它锁定的目标 id 列表（每道光柱一个）。**键存在** = 正在照射。
     *
     * <p>红帽附魔「光柱」会同时打多道光柱，所以这里存的是数组而不是单个 id；
     * 没有锁到敌人时是空数组（光柱仍会沿视线画出去）。
     */
    private static final Map<Integer, int[]> BEAMS = new HashMap<>();

    private BeamTargets() {
    }

    public static void set(int wearerId, boolean beaming, int[] targetIds) {
        if (beaming) {
            BEAMS.put(wearerId, targetIds == null ? new int[0] : targetIds);
        } else {
            BEAMS.remove(wearerId);
        }
    }

    /** 这个戴帽者现在是不是在照射。 */
    public static boolean isBeaming(int wearerId) {
        return BEAMS.containsKey(wearerId);
    }

    /** 它锁定的目标 id 列表（每道光柱一个）；没有目标时是空数组。 */
    public static int[] get(int wearerId) {
        int[] ids = BEAMS.get(wearerId);
        return ids == null ? EMPTY : ids;
    }

    private static final int[] EMPTY = new int[0];

    /** 实体移除时清掉，免得实体 id 被复用后画出一条幽灵光柱。 */
    public static void clear(int wearerId) {
        BEAMS.remove(wearerId);
    }

    /**
     * 剔除掉服务端已经不再存在的实体 —— 比如生物在视距外被卸载、或者玩家退出，
     * 这类情况收不到「停止照射」的包，不清理就会一直留着。
     *
     * @param alive 判断实体 id 现在还在不在世界里
     */
    public static void prune(IntPredicate alive) {
        BEAMS.keySet().removeIf(id -> !alive.test(id));
    }
}
