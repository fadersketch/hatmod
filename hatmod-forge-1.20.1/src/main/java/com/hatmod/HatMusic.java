package com.hatmod;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/**
 * 帽子 BGM 的开始 / 停止。
 *
 * <p><b>音乐由客户端自己播放，服务端只负责"通知"。</b>这里不再用
 * {@code level.playSound(...)}（那会走原版的声音包，客户端拿到的只是一段匿名声音，
 * 分不清它属于谁），而是把「谁的 BGM 开始了 / 停了」直接发给听得见的玩家。
 * 客户端据此为每个戴帽者建一条自己的音乐实例，才能实现
 * 「自己的 BGM 优先、其余只留最后一条」这种**按听者**计算的规则
 * —— 原版的声音包不带"这条音乐属于哪个实体"这个信息，做不到。
 *
 * <p>顺带修掉一个老毛病：原版 {@code ClientboundStopSoundPacket} 是按「音效 id + 音源」
 * 停止的，两个玩家戴同色帽子时，停其中一个会把另一个的 BGM 一起掐掉。现在停谁就是停谁。
 */
public final class HatMusic {

    /** 听得见音乐的范围（同时也是通知谁）。 */
    public static final double HEAR_RADIUS = 48.0D;

    /**
     * 播放音量。
     *
     * <p>这里和衰减距离是配套的，别随手改：
     * 客户端实际响度 = clamp(volume × 音量滑块, 0..1)，所以 3.0 只是把音量顶满，
     * <b>不会更吵</b>；真正的作用有两个 ——
     * 声道按 {@code max(volume,1) × attenuation_distance} 做线性衰减
     * （3.0 × 16 = 48 格处刚好归零），也就是「听见范围 = 48 格、并在 48 格处平滑归零」。
     */
    public static final float MUSIC_VOLUME = 3.0F;

    private HatMusic() {
    }

    public static void play(ServerLevel level, LivingEntity wearer, HatType type) {
        notify(level, wearer, type, true);
    }

    /** 半路摘帽子 / 死亡 / 换帽子 / 照完没敌人时把还在响的那首掐掉，免得一直放到曲终。 */
    public static void stop(ServerLevel level, LivingEntity wearer, HatType type) {
        notify(level, wearer, type, false);
    }

    /**
     * 把「这个戴帽者的 BGM 开始/停止了」告诉听得见的人。
     *
     * <p>范围判断和原来一致：戴帽者自己 + 48 格内的玩家。
     */
    private static void notify(ServerLevel level, LivingEntity wearer, HatType type, boolean playing) {
        if (type == null) {
            return;
        }
        double radiusSq = HEAR_RADIUS * HEAR_RADIUS;
        int wearerId = wearer.getId();
        int ordinal = type.ordinal();
        for (ServerPlayer player : level.players()) {
            if (player == wearer || player.distanceToSqr(wearer) <= radiusSq) {
                HatNetwork.sendMusic(player, wearerId, ordinal, playing);
            }
        }
    }
}
