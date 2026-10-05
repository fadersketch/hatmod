package com.hatmod;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * 「每个戴帽者这一轮走的是哪条路线」的客户端表：服务端选定路线后推给客户端。
 *
 * <p><b>只有「全」会真的用上它。</b>三顶原色帽的路线恒等于它自己，「全」则每轮随机取
 * 黑/白/红中的一条 —— 客户端据此把帽子渲染成路线对应的颜色（护甲贴图、饰品栏渲染、
 * 光柱淡色都读这里）。
 *
 * <p>写入路径：服务端 {@code HatAbilities.startCharge} 选定路线后随
 * {@code HatNetwork.RouteMessage} 推给客户端，客户端收到后调 {@link #setClient}。
 * 服务端自己不读这张表，所以这里没有服务端侧的写入。
 *
 * <p>刻意只用一个 {@code Map<Integer, HatType>}、不额外定义内部类：内部类要跟着
 * {@link HatPreloader} 的名单一起预加载才不会踩 jar 句柄被关的坑（见 {@code HatState} 的注释）。
 */
public final class HatRouteState {

    /** 戴帽者实体 id -> 本轮路线。没有条目 = 还没同步到（按帽子原色兜底）。 */
    private static final Map<Integer, HatType> ROUTES = new HashMap<>();

    private HatRouteState() {
    }

    /** 客户端：收到路线包。 */
    public static void setClient(int wearerId, int routeOrdinal) {
        HatType[] types = HatType.values();
        if (routeOrdinal >= 0 && routeOrdinal < types.length) {
            ROUTES.put(wearerId, types[routeOrdinal]);
        }
    }

    /**
     * 这个戴帽者现在该按哪条路线渲染（＝帽子显示成什么颜色）。
     *
     * @param fallback 还没收到路线包时用哪顶帽子兜底（一般传帽子本身的类型）
     */
    public static HatType routeOf(int wearerId, HatType fallback) {
        HatType route = ROUTES.get(wearerId);
        return route == null ? fallback : route;
    }

    /** 实体移除时清掉，免得实体 id 被复用后串色。 */
    public static void clear(int wearerId) {
        ROUTES.remove(wearerId);
    }

    /** 剔除掉服务端已经不再存在的实体（卸载 / 退出时收不到包）。 */
    public static void prune(IntPredicate alive) {
        ROUTES.keySet().removeIf(id -> !alive.test(id));
    }

    /**
     * 这条路线对应的护甲贴图名（不带命名空间/路径后缀）—— 就是那顶帽子的材质名。
     *
     * <p>三顶原色帽各取自己；{@link HatType#ALL} 返回 {@code all_hat} ——
     * 这正好是「还没收到路线包」时的兜底（先显示「全」自己的三色贴图，收到包再切）。
     */
    public static String textureName(HatType route) {
        if (route == HatType.WHITE) {
            return "white_hat";
        }
        if (route == HatType.RED) {
            return "red_hat";
        }
        if (route == HatType.BLACK) {
            return "black_hat";
        }
        return "all_hat";
    }
}
