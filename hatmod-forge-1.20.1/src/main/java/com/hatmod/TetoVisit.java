package com.hatmod;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;

/**
 * 「不请自来」：召唤过她足够多次之后，她会在你身边没有 Teto 的时候自己找上来。
 *
 * <p>规矩：
 * <ul>
 *   <li>只有**召唤次数 ≥ {@link #SUMMONS_REQUIRED}** 的玩家才有这一出；</li>
 *   <li>每 {@link #CHECK_PERIOD} 刻掷一次骰子，{@link #CHANCE} 的概率；</li>
 *   <li>名下**一只 Teto 都不能有**（含坐着待命的、光柱期间的分身）—— 核心原则是
 *       「一个玩家手下最多常驻一只」，她自己来也一样；</li>
 *   <li>链路正在跑的时候不来（那一轮已经有主唱了）。</li>
 * </ul>
 *
 * <p>来的是**散养**的那只（不是链路召唤物）：认主、跟着走、会闲聊，主人死了才散。
 * 这一次不算玩家的「召唤次数」—— 是她自己要来的。
 */
final class TetoVisit {

    /** 召唤过这么多次，她才会自己找上来。 */
    static final int SUMMONS_REQUIRED = 10;

    /** 每 600 刻（30 秒）掷一次骰子。 */
    private static final int CHECK_PERIOD = 600;

    /**
     * 每次 0.5%。
     *
     * <p>以前是「每 5 秒 1%」——平均八分钟她就冒出来一次，太吵了。
     * 现在是「每 30 秒 0.5%」，平均一个多小时才来一次，而且被赶走之后还有冷静期。
     */
    private static final double CHANCE = 0.005D;

    /** 被聊天框赶走之后，多久不再自己找上来（刻）：一个游戏日。 */
    private static final int DISMISS_COOLDOWN = 24000;

    /** 玩家 -> 上次被赶走的时间（游戏刻）。只放内存：重进就当忘了。 */
    private static final java.util.Map<java.util.UUID, Long> DISMISSED_AT = new java.util.HashMap<>();

    /** 台词池有几条（和语言文件里的 {@code hatmod.teto.visit.N} 对应）。 */
    private static final int LINES = 4;

    /** 落在离玩家多远的地方（格）。 */
    private static final double ARRIVE_RADIUS = 2.6D;

    private TetoVisit() {
    }

    /** 每刻调一次（玩家 tick 里）：到点就掷骰子。 */
    static void tick(ServerPlayer player) {
        if (player.level() instanceof ServerLevel level) {
            tick(player, level);
        }
    }

    /** 主人在聊天框里让她走了：记一笔，接下来一个游戏日她都自己不来。 */
    static void noteDismissed(ServerLevel level, ServerPlayer player) {
        DISMISSED_AT.put(player.getUUID(), level.getGameTime());
    }

    private static void tick(ServerPlayer player, ServerLevel level) {
        if (level.getGameTime() % CHECK_PERIOD != 0) {
            return;
        }
        if (player.isDeadOrDying() || player.isSpectator()) {
            return;
        }
        if (HatSettings.tetoSummons(player.getUUID()) < SUMMONS_REQUIRED) {
            return;
        }
        // 刚被主人在聊天框里赶走：给她一个游戏日的冷静期（"teto走开"）
        Long dismissed = DISMISSED_AT.get(player.getUUID());
        if (dismissed != null && level.getGameTime() - dismissed < DISMISS_COOLDOWN) {
            return;
        }
        // 名下已经有一只（含分身 / 坐着的）：不请自来不成立
        if (!Teto.ownedBy(player).isEmpty()) {
            return;
        }
        // 链路正在跑：这一轮已经有主唱，别去抢位置
        if (BrainrotCycle.isRunning(player)) {
            return;
        }
        if (player.getRandom().nextDouble() >= CHANCE) {
            return;
        }
        arrive(level, player);
    }

    /** 她来了：落在玩家身边，直接认主，说一句「不请自来」的台词。 */
    private static void arrive(ServerLevel level, ServerPlayer player) {
        EntityType<Teto> type = HatSummons.TETO.get();
        Teto teto = type.create(level);
        if (teto == null) {
            return;
        }
        double angle = player.getRandom().nextDouble() * Math.PI * 2.0D;
        teto.moveTo(player.getX() + Math.cos(angle) * ARRIVE_RADIUS, player.getY(),
                player.getZ() + Math.sin(angle) * ARRIVE_RADIUS, player.getYRot() + 180.0F, 0.0F);
        teto.hatmodVisitArrival(player);
        if (!level.addFreshEntity(teto)) {
            return;
        }
        level.sendParticles(ParticleTypes.SCULK_SOUL,
                teto.getX(), teto.getY() + teto.getBbHeight() * 0.5D, teto.getZ(),
                14, 0.3D, 0.7D, 0.3D, 0.01D);
        level.playSound(null, teto.getX(), teto.getY(), teto.getZ(),
                SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.PLAYERS, 0.6F, 1.5F);
        TetoTalk.sayNear(level, teto, 24.0D, TetoTalk.pick(level.random, "hatmod.teto.visit", LINES));
    }
}
