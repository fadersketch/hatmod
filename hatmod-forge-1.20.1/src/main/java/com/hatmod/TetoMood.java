package com.hatmod;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Teto 的「心情」：0（憋屈）..100（雀跃），出厂 50，按玩家记、随配置落盘。
 *
 * <p>加减规则（都是瞬时事件）：
 * <ul>
 *   <li>喂到好吃的 +2，喂到难吃的 −3，链路里救急的一口 +1；</li>
 *   <li>被**主人本人**打一下 −8；</li>
 *   <li>她在一轮里倒下 −10；</li>
 *   <li>有人陪她把一整首听完 +6。</li>
 * </ul>
 *
 * <p>每过一个游戏日，心情向 50 回落 1/8 —— 再大的情绪也会被时间磨平。
 * 心情只影响说话的语气（开场白和闲话池），不影响任何战斗数值。
 */
final class TetoMood {

    static final int MIN = 0;
    static final int MAX = 100;
    static final int NEUTRAL = 50;

    /** 心情档：≤25 憋屈，≥75 雀跃，其余正常。 */
    static final int UPSET = 0;
    static final int NORMAL = 1;
    static final int HAPPY = 2;

    /** 懒结算的衰减单位：一个游戏日（刻）。 */
    private static final long DAY_TICKS = 24000L;
    /** 每个游戏日向 50 回落的比例（1/8）。 */
    private static final int DECAY_DIVISOR = 8;
    /** 一次结算最多按多少个游戏日算（防手改系统时间炸循环）。 */
    private static final long MAX_DAYS = 64L;

    private static final Map<UUID, Integer> MOOD = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> UPDATED_AT = new ConcurrentHashMap<>();

    private TetoMood() {
    }

    /** 当前心情（0..100）：顺手把攒下的天数衰减掉。 */
    static synchronized int mood(UUID player, long now) {
        int value = MOOD.getOrDefault(player, NEUTRAL);
        long last = UPDATED_AT.getOrDefault(player, now);
        long days = Math.max(0L, (now - last) / DAY_TICKS);
        if (days > 0L) {
            for (long i = 0; i < Math.min(days, MAX_DAYS); i++) {
                value += (NEUTRAL - value) / DECAY_DIVISOR;
            }
            value = clamp(value);
            MOOD.put(player, value);
            UPDATED_AT.put(player, now);
        }
        return value;
    }

    /** 加减一笔心情（正 = 开心，负 = 憋屈）。 */
    static synchronized void adjust(UUID player, int delta, long now) {
        if (player == null || delta == 0) {
            return;
        }
        int value = clamp(mood(player, now) + delta);
        MOOD.put(player, value);
        UPDATED_AT.put(player, now);
    }

    /** 心情档：{@link #UPSET} / {@link #NORMAL} / {@link #HAPPY}。 */
    static int tier(int mood) {
        if (mood <= 25) {
            return UPSET;
        }
        return mood >= 75 ? HAPPY : NORMAL;
    }

    private static int clamp(int value) {
        return Math.max(MIN, Math.min(MAX, value));
    }

    /** 落盘用：心情快照（{@code UUID -> 值}）。 */
    static synchronized java.util.Map<String, Integer> snapshot() {
        java.util.Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<UUID, Integer> entry : MOOD.entrySet()) {
            out.put(entry.getKey().toString(), entry.getValue());
        }
        return out;
    }

    /** 落盘用：上次结算时刻快照（{@code UUID -> 游戏刻}）。 */
    static synchronized java.util.Map<String, Long> snapshotTimes() {
        java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<UUID, Long> entry : UPDATED_AT.entrySet()) {
            out.put(entry.getKey().toString(), entry.getValue());
        }
        return out;
    }

    /** 开局恢复（键坏了就丢掉那一条）。 */
    static synchronized void restore(java.util.Map<String, Integer> moods,
                                     java.util.Map<String, Long> times) {
        MOOD.clear();
        UPDATED_AT.clear();
        if (moods == null) {
            return;
        }
        for (java.util.Map.Entry<String, Integer> entry : moods.entrySet()) {
            try {
                UUID id = UUID.fromString(entry.getKey());
                Integer value = entry.getValue();
                if (value != null) {
                    MOOD.put(id, clamp(value));
                    Long when = times == null ? null : times.get(entry.getKey());
                    UPDATED_AT.put(id, when == null ? 0L : when);
                }
            } catch (IllegalArgumentException ignored) {
                // 手改坏了就当没这一条
            }
        }
    }
}
