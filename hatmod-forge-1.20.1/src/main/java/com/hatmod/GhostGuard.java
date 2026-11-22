package com.hatmod;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;

import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Teto / 唐嘉琦 对「winefox delight 的鬼」（怨灵 {@code kitsune_ghost} / 凝视者
 * {@code kitsune_watcher}）的拦截，见 {@link HatAbilities#isGhostMob}。
 *
 * <ul>
 *   <li><b>Teto</b>：把半径内的鬼「锁死在原地」（同光柱强控：缓慢 255 + 虚弱 + NoAI），
 *       而且**按过一次就永不恢复** —— 她走了也照旧按着；鬼贴到主人身边时顺手安抚一句。
 *       配乐和鬼图在客户端拦，见 {@code com.hatmod.client.GhostWarden} 和
 *       {@code com.hatmod.guard.mixin} 里的两个 client mixin —— 我们的 BGM 同时放行。</li>
 *   <li><b>唐嘉琦</b>：不商量 —— 平时贴太近直接移除（抢在鬼图之前）；光束段改把
 *       范围内的鬼按在光束下，收手那一刻一并清除（尾杀）。</li>
 * </ul>
 *
 * <p>鬼按注册 id 认（{@code maid_dismemberment:kitsune_ghost / kitsune_watcher}），
 * 不依赖那个模组的类；模组不在时这些路径整条空转。
 */
final class GhostGuard {

    /** Teto 把鬼按住的半径（格）：她绕着主人转，这个圈基本就是主人的安全区。 */
    private static final double TETO_PIN_RANGE = 24.0D;
    /** 唐嘉琦直接移除的半径（格）：怨灵要贴脸才放鬼图，这个圈足够抢在前面。 */
    private static final double TANG_REMOVE_RANGE = 24.0D;
    /** 鬼贴到主人多近才值一句安抚（格）。 */
    private static final double COMFORT_RANGE = 16.0D;
    /** 每按一次续多久（刻）：唐嘉琦光束段每刻续，收手时由尾杀结算。 */
    private static final int PIN_TICKS = 60;
    /** 安抚台词的冷却（刻）：同一个玩家 30 秒内只安抚一次。 */
    private static final int COMFORT_COOLDOWN = 600;

    private static final Map<UUID, Long> COMFORTED = new HashMap<>();

    private GhostGuard() {
    }

    /** Teto 值班：把半径内的鬼全部按住，鬼贴到主人身边时安抚一句。 */
    static void watchForTeto(ServerLevel level, Teto teto) {
        List<LivingEntity> ghosts = level.getEntitiesOfClass(LivingEntity.class,
                teto.getBoundingBox().inflate(TETO_PIN_RANGE), HatAbilities::isGhostMob);
        for (LivingEntity ghost : ghosts) {
            HatAbilities.pinInPlaceForever(level, ghost);
            comfort(level, teto, ghost);
        }
    }

    /** 唐嘉琦光束段按住 / 收手尾杀的半径（格）：比贴脸清除圈大一倍，光束扫到哪就管到哪。 */
    private static final double TANG_BEAM_RANGE = 48.0D;

    /**
     * 唐嘉琦值班：光束段把范围内的鬼都按在光束下动弹不得（临时按住，每刻续），
     * 收手那一刻由 {@link #beamFinisher} 一并清除；平时贴太近直接移除，抢在鬼图之前。
     */
    static void watchForTang(ServerLevel level, TangJiaqi tang) {
        if (tang.isBeaming()) {
            List<LivingEntity> held = level.getEntitiesOfClass(LivingEntity.class,
                    tang.getBoundingBox().inflate(TANG_BEAM_RANGE), HatAbilities::isGhostMob);
            for (LivingEntity ghost : held) {
                HatAbilities.pinInPlace(level, ghost, PIN_TICKS);
            }
            return;
        }
        List<LivingEntity> ghosts = level.getEntitiesOfClass(LivingEntity.class,
                tang.getBoundingBox().inflate(TANG_REMOVE_RANGE), HatAbilities::isGhostMob);
        for (LivingEntity ghost : ghosts) {
            level.sendParticles(ParticleTypes.LARGE_SMOKE,
                    ghost.getX(), ghost.getY() + ghost.getBbHeight() * 0.5D, ghost.getZ(),
                    16, 0.3D, 0.5D, 0.3D, 0.02D);
            level.playSound(null, ghost.getX(), ghost.getY(), ghost.getZ(),
                    SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.7F, 0.6F);
            ghost.discard();
        }
    }

    /**
     * 尾杀：光束收手的那一刻，把光束扫过的怨灵一并清除。
     *
     * <p>演出比平时的就地消散大一档：魂魄炸开 + 一声 sonic boom —— 光柱收尾的最后一响。
     */
    static void beamFinisher(ServerLevel level, TangJiaqi tang) {
        List<LivingEntity> ghosts = level.getEntitiesOfClass(LivingEntity.class,
                tang.getBoundingBox().inflate(TANG_BEAM_RANGE), HatAbilities::isGhostMob);
        for (LivingEntity ghost : ghosts) {
            double x = ghost.getX();
            double y = ghost.getY() + ghost.getBbHeight() * 0.5D;
            double z = ghost.getZ();
            level.sendParticles(ParticleTypes.SOUL, x, y, z, 24, 0.4D, 0.8D, 0.4D, 0.06D);
            level.sendParticles(ParticleTypes.LARGE_SMOKE, x, y, z, 16, 0.3D, 0.5D, 0.3D, 0.02D);
            level.playSound(null, x, y, z,
                    SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 0.8F, 1.3F);
            ghost.discard();
        }
    }

    /** 拦截成功后的安抚：鬼已经被按住、又贴着主人，才说这一句（30 秒冷却）。 */
    private static void comfort(ServerLevel level, Teto teto, LivingEntity ghost) {
        UUID owner = teto.getOwnerUUID();
        if (owner == null) {
            return;
        }
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(owner);
        if (player == null || player.distanceToSqr(ghost) > COMFORT_RANGE * COMFORT_RANGE) {
            return;
        }
        long now = level.getGameTime();
        Long last = COMFORTED.get(owner);
        if (last != null && now - last < COMFORT_COOLDOWN) {
            return;
        }
        if (COMFORTED.size() > 256) {
            COMFORTED.clear();
        }
        COMFORTED.put(owner, now);
        TetoTalk.sayTo(player, TetoTalk.pick(level.random, "hatmod.teto.guard", 3));
    }

    // ------------------------------------------------------------------
    // 贴脸生成的防护（指令 / 刷怪蛋硬塞到玩家身上的鬼）
    // ------------------------------------------------------------------

    /** 贴脸生成的判定半径（格）：自然鬼不会落在玩家身上，落在 8 格内的只可能是手动塞的。 */
    private static final double FACEHUG_RANGE = 8.0D;
    /** 生成日志条数上限（防刷屏）。 */
    private static int joinLogged;

    /**
     * 生成事件（实体第一刻 tick 之前）：落点在玩家身边（{@link #FACEHUG_RANGE}）的
     * **新鲜**鬼只可能是玩家手动塞的 —— 在这里处理能抢在它的 {@code strike()} 之前，
     * 跳脸（全屏鬼图 + 2 秒后强制死亡）一次都出不来。
     *
     * <p>附近没有 Teto 时照常放行生成，交给值班逻辑（Teto 靠近就按住、
     * 唐嘉琦在场直接移除）。存档里读出来的旧鬼（坟位上的）也不归这管。
     */
    static void handleGhostJoin(ServerLevel level, LivingEntity ghost) {
        Player nearest = level.getNearestPlayer(
                ghost.getX(), ghost.getY(), ghost.getZ(), FACEHUG_RANGE, false);
        if (nearest == null) {
            return;                         // 不是贴脸生成：交给 Teto / 唐嘉琦的值班逻辑
        }
        boolean tetoNear = !level.getEntitiesOfClass(Teto.class,
                ghost.getBoundingBox().inflate(TETO_PIN_RANGE),
                teto -> teto.isAlive() && !teto.isRemoved()).isEmpty();
        if (tetoNear) {
            HatAbilities.pinInPlaceForever(level, ghost);
            if (joinLogged++ < 5) {
                HatMod.LOGGER.info("[hatmod] 贴脸生成的怨灵已被 Teto 就地按住：{}",
                        ghost.getType().toShortString());
            }
            return;
        }
        if (joinLogged++ < 5) {
            HatMod.LOGGER.info("[hatmod] 贴脸生成的怨灵（附近无 Teto）放行，交给值班接管：{}",
                    ghost.getType().toShortString());
        }
    }
}
