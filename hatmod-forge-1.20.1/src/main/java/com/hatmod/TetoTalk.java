package com.hatmod;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Teto 的台词：统一配色、统一投递、同一种话各自冷却。
 *
 * <p>以前开场白和曲终评价值接写在两个类里，颜色也是各写一份（{@code ChatFormatting}
 * 里没有品红，只能给 RGB）。现在都走这里：<b>[Teto]</b> 标签亮品红，正文淡品红，
 * 同一个色系里分层，看起来像同一个人一直在说话。
 *
 * <p>触发点变多之后最怕的是刷屏 —— 连击里程碑、三连 MISS、闪避、声波、刷怪蛋……
 * 所以 {@link #sayCooled} 按「台词种类」给冷却，同一种话短时间内只说一次。
 */
final class TetoTalk {

    /** 「[Teto]」标签色（亮品红）。 */
    private static final Style NAME = Style.EMPTY.withColor(TextColor.fromRgb(0xFF33CC));
    /** 正文色（淡品红）。 */
    private static final Style TEXT = Style.EMPTY.withColor(TextColor.fromRgb(0xFFA8EE));
    /**
     * meta / 彩蛋台词的颜色（青蓝）。
     *
     * <p>这些是「她其实知道自己是个模组角色」那类话，和日常对白不是一回事，
     * 换个颜色一眼分得出来。
     */
    private static final Style META_TEXT = Style.EMPTY.withColor(TextColor.fromRgb(0x7FD4FF));

    /** 玩家 -> （台词种类 -> 上次说的游戏刻）。 */
    private static final Map<UUID, Map<String, Long>> LAST = new HashMap<>();

    private TetoTalk() {
    }

    /** 对单个玩家说一句（聊天栏）。 */
    static void sayTo(ServerPlayer player, String key, Object... args) {
        player.sendSystemMessage(Component.literal("[Teto] ")
                .withStyle(NAME)
                .append(Component.translatable(key, args).withStyle(styleOf(key))));
    }

    /** meta / 彩蛋台词用另一个颜色，日常对白还是淡品红。 */
    private static Style styleOf(String key) {
        if (key != null && (key.startsWith("hatmod.teto.meta.")
                || key.startsWith("hatmod.teto.egg."))) {
            return META_TEXT;
        }
        return TEXT;
    }

    /** 对 {@code center} 周围 {@code radius} 格内的所有玩家说一句。 */
    static void sayNear(ServerLevel level, LivingEntity center, double radius,
                        String key, Object... args) {
        double rangeSqr = radius * radius;
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(center) <= rangeSqr) {
                sayTo(player, key, args);
            }
        }
    }

    /**
     * 带冷却的说话：{@code kind} 相同的话，每人 {@code ticks} 刻内只说一次。
     *
     * <p>冷却按玩家分别记 —— 两个人一起戴帽子，不该互相把对方的话吃掉。
     */
    static void sayCooled(ServerLevel level, LivingEntity center, double radius,
                          String kind, int ticks, String key, Object... args) {
        long now = level.getGameTime();
        double rangeSqr = radius * radius;
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(center) > rangeSqr) {
                continue;
            }
            Map<String, Long> per = LAST.computeIfAbsent(player.getUUID(), id -> new HashMap<>());
            Long last = per.get(kind);
            if (last != null && now - last < ticks) {
                continue;
            }
            per.put(kind, now);
            sayTo(player, key, args);
        }
    }

    /**
     * 从 {@code base.1 .. base.count} 里随机挑一个语言键。
     *
     * <p>台词池全靠它扩：同一触发点在语言文件里放几条，说的时候就几选一，
     * 玩家重开才不会一直听同一句。
     */
    static String pick(net.minecraft.util.RandomSource random, String base, int count) {
        return base + "." + (1 + random.nextInt(count));
    }

    /** 玩家退出时清掉冷却表，别把人记一辈子。 */
    static void forget(UUID player) {
        LAST.remove(player);
    }
}
