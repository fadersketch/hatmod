package com.hatmod.client;

import com.hatmod.HatSettings;
import com.hatmod.HatType;

import java.util.EnumMap;
import java.util.Map;

/**
 * 客户端这边的参数副本：服务端通过 {@code HatNetwork} 发过来，界面只读它。
 *
 * <p>没收到同步之前先按 {@link HatType} 的出厂数值显示，免得界面一片空白。
 */
public final class ClientHatSettings {

    private static final Map<HatType, Snapshot> SNAPSHOTS = new EnumMap<>(HatType.class);

    /** 全局开关「自己的 BGM 优先」，随服务端同步过来；没同步前按出厂默认（开启）。 */
    private static boolean ownBgmPriority = HatSettings.DEFAULT_OWN_BGM_PRIORITY;

    /** 「全」三条路线的帧伤，随服务端同步过来；没同步前按出厂值（12/10/15）。 */
    private static float routeDamageBlack = HatSettings.DEFAULT_ROUTE_DAMAGE_BLACK;
    private static float routeDamageWhite = HatSettings.DEFAULT_ROUTE_DAMAGE_WHITE;
    private static float routeDamageRed = HatSettings.DEFAULT_ROUTE_DAMAGE_RED;

    /** 「强制路线」：{@code random} 或 black/white/red。 */
    private static String forcedRoute = HatSettings.ROUTE_RANDOM;

    private ClientHatSettings() {
    }

    public static boolean ownBgmPriority() {
        return ownBgmPriority;
    }

    public static void setOwnBgmPriority(boolean value) {
        ownBgmPriority = value;
    }

    public static float routeDamage(HatType route) {
        if (route == HatType.BLACK) {
            return routeDamageBlack;
        }
        if (route == HatType.RED) {
            return routeDamageRed;
        }
        return routeDamageWhite;
    }

    public static String forcedRoute() {
        return forcedRoute;
    }

    public static void setRoute(float black, float white, float red, String forced) {
        routeDamageBlack = black;
        routeDamageWhite = white;
        routeDamageRed = red;
        forcedRoute = forced == null ? HatSettings.ROUTE_RANDOM : forced;
    }

    /** 一份只读参数（就是个值对象，字段 public 方便界面直接读）。 */
    public static final class Snapshot {
        public int chargeTicks;
        public int flashTicks;
        public float damagePerTick;
        public float healthDamageRatio;
        public float healthDamageFloor;
        public String damageType;

        Snapshot(int chargeTicks, int flashTicks, float damagePerTick,
                 float healthDamageRatio, float healthDamageFloor, String damageType) {
            this.chargeTicks = chargeTicks;
            this.flashTicks = flashTicks;
            this.damagePerTick = damagePerTick;
            this.healthDamageRatio = healthDamageRatio;
            this.healthDamageFloor = healthDamageFloor;
            this.damageType = damageType;
        }
    }

    public static Snapshot get(HatType type) {
        Snapshot snapshot = SNAPSHOTS.get(type);
        if (snapshot == null) {
            // 还没同步过：拿出厂值凑合显示
            snapshot = new Snapshot(type.chargeTicks(), type.flashTicks(), type.damagePerTick(),
                    HatSettings.DEFAULT_HEALTH_DAMAGE_RATIO, HatSettings.DEFAULT_HEALTH_DAMAGE_FLOOR,
                    HatSettings.DEFAULT_DAMAGE_TYPE);
        }
        return snapshot;
    }

    public static void set(HatType type, int chargeTicks, int flashTicks, float damagePerTick,
                           float healthDamageRatio, float healthDamageFloor, String damageType) {
        SNAPSHOTS.put(type, new Snapshot(chargeTicks, flashTicks, damagePerTick,
                healthDamageRatio, healthDamageFloor, damageType));
    }

    /**
     * 收到服务端同步：更新副本，并按 feedback 给玩家一句提示。
     *
     * <p>这是**纯客户端**逻辑（服务端不会收到 {@code SettingsSyncPayload}），
     * 所以这里引用 {@code Minecraft} 是安全的。
     */
    public static void handleSync(com.hatmod.HatNetwork.SettingsSyncPayload msg) {
        HatType[] types = HatType.values();
        for (com.hatmod.HatNetwork.SettingsSyncPayload.Snapshot snapshot : msg.snapshots()) {
            int ordinal = snapshot.ordinal();
            if (ordinal < 0 || ordinal >= types.length) {
                continue;
            }
            set(types[ordinal], snapshot.chargeTicks(), snapshot.flashTicks(), snapshot.damagePerTick(),
                    snapshot.healthDamageRatio(), snapshot.healthDamageFloor(), snapshot.damageType());
        }
        ownBgmPriority = msg.ownBgmPriority();
        setRoute(msg.route().black(), msg.route().white(), msg.route().red(), msg.route().forcedRoute());

        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        if (msg.feedback() == com.hatmod.HatNetwork.FEEDBACK_OK) {
            minecraft.player.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable("hatmod.tuner.saved"), true);
        } else if (msg.feedback() == com.hatmod.HatNetwork.FEEDBACK_DENIED) {
            minecraft.player.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable("hatmod.tuner.denied")
                            .withStyle(net.minecraft.ChatFormatting.RED),
                    true);
        }
    }
}
