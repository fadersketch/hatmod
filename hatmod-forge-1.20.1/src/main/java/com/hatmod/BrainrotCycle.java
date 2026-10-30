package com.hatmod;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 「脑蚀」的整条时间线（一轮 = 一首 MONTAGEM ALQUIMIA，约 105.7 秒）。
 *
 * <p>这顶帽子**不进** {@code HatAbilities} 的「蓄力 → 照射」状态机（那个状态机是围绕
 * {@link HatType} 的三条路线写死的），它有一套自己的、按刻推进的完整剧本：
 *
 * <pre>
 *   0s          黑暗
 *  12s          黑暗结束
 *  13s          「No too late」逐个弹出（0.3 秒一个）
 *  14s          开始原地自转 + 迅捷 I；脚下减速圈从 0 开始逐刻扩大
 *  28.5s        减速圈扩到半径 10，开始照射（迅捷 II）
 *  42.5s        额外放一次监守者同款声波（无音效，动画 1 秒）
 *  57s          完全恢复正常、停火，获得漂浮 + 黑暗
 *  70s          再次弹出「No too late」
 *  71s          解除漂浮/黑暗，获得 13 秒缓降，继续自转与照射
 *  100s         获得缓慢 + 黑暗：继续自转，但**停止照射**，直到曲末
 *  105.7s       曲终：全部解除；附近还有敌人就进入下一轮
 * </pre>
 *
 * <p>和其它帽子一样，这里只管服务端：光柱的「正在照射 / 道数 / 锁定谁」走
 * {@code HatNetwork.BeamTargetMessage}，自转走 {@code BrainrotSpinMessage}，
 * 屏幕文字走 {@code BrainrotTextMessage}。
 */
@Mod.EventBusSubscriber(modid = HatMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BrainrotCycle {

    /** 曲子长度：1:45.67 → 2114 刻（取整到刻）。整轮循环就按这个长度走。 */
    public static final int CYCLE_TICKS = 2114;

    /** 0 秒到 12 秒：黑暗。 */
    private static final int DARKNESS_END = 12 * 20;
    /** 13 秒：第一次「No too late」。 */
    private static final int TEXT_FIRST = 13 * 20;
    /** 14 秒：开始自转、迅捷 I、减速圈开始扩大。 */
    private static final int SPIN_START = 14 * 20;
    /** 28.5 秒：减速圈到最大半径，开始照射。 */
    private static final int FIRE_START = 570;
    /** 42.5 秒：声波。 */
    private static final int SONIC_AT = 850;
    /** 57 秒：停火 + 漂浮 + 黑暗。 */
    private static final int PAUSE_START = 57 * 20;
    /** 70 秒：第二次「No too late」。 */
    private static final int TEXT_SECOND = 70 * 20;
    /** 71 秒：恢复自转与照射（3 道），缓降 13 秒。 */
    private static final int FIRE2_START = 1420;
    /** 84 秒：缓降结束（71 + 13）。 */
    private static final int SLOW_FALL_END = 1680;
    /**
     * 光柱道数：**始终两道**。
     *
     * <p>「AoE」不是「多放几道光」，而是「一道光是一条线」—— 选中的敌人和它后面
     * 被同一条光扫到的敌人一起受伤（见 {@link #fire}）。
     */
    private static final int BEAM_LANES = 2;
    /** 光柱的截面半宽（格）：判定「在光里」用的水平距离。 */
    private static final double PIERCE_RADIUS = 2.0D;
    /** 100 秒：缓慢 + 黑暗，直到曲末。 */
    private static final int SLOW_DARK_AT = 100 * 20;

    /** 减速圈最大半径。 */
    private static final double RING_MAX_RADIUS = 10.0D;
    /** 照射范围（和普通光柱一致）。 */
    private static final double FIRE_RANGE = 24.0D;
    /** 声波动画时长。 */
    private static final int SONIC_TICKS = 20;
    /** 声波结算半径（离线段这么近的敌人都会被扫到）。 */
    private static final double SONIC_HIT_RADIUS = 1.6D;
    /** 声波伤害（和监守者一致）。 */
    private static final float SONIC_DAMAGE = 10.0F;
    /** 声波带控多久（刻）：3 秒定身，和光柱的强控同一套。 */
    private static final int SONIC_HOLD_TICKS = 60;
    /** 戴帽者 -> 本轮已经走了多少刻。**键存在** = 这一轮正在进行。 */
    private static final Map<UUID, Integer> AGES = new HashMap<>();
    /** 戴帽者 -> 上一次发给客户端的自转状态，用来去重。 */
    private static final Map<UUID, Boolean> SPIN_SENT = new HashMap<>();
    /** 戴帽者 -> 上一次发给客户端的光柱道数 / 目标列表（去重）。 */
    private static final Map<UUID, int[]> BEAM_IDS = new HashMap<>();
    private static final Map<UUID, Integer> BEAM_COUNT = new HashMap<>();
    /** 声波：剩余刻数、起点、终点。 */
    private static final Map<UUID, Integer> SONIC_LEFT = new HashMap<>();
    private static final Map<UUID, Vec3> SONIC_FROM = new HashMap<>();
    private static final Map<UUID, Vec3> SONIC_TO = new HashMap<>();

    private static final int[] NO_IDS = new int[0];

    private BrainrotCycle() {
    }

    // ------------------------------------------------------------------
    // 事件入口：和 HatAbilities 一样，玩家与非玩家生物各走一条
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (event.player instanceof ServerPlayer player) {
            tick(player, player.serverLevel());
        }
    }

    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        LivingEntity living = event.getEntity();
        if (!(living instanceof ServerPlayer) && living.level() instanceof ServerLevel level) {
            tick(living, level);
        }
    }

    /** 玩家刚被别的玩家看到时补一条自转状态（中途走进视野不会漏）。 */
    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (!(event.getTarget() instanceof LivingEntity living) || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        UUID id = living.getUUID();
        if (!AGES.containsKey(id)) {
            return;
        }
        HatNetwork.sendBrainrotSpinTo(player, living.getId(), SPIN_SENT.getOrDefault(id, false));
        int[] ids = BEAM_IDS.getOrDefault(id, NO_IDS);
        if (ids.length > 0 || BEAM_COUNT.containsKey(id)) {
            HatNetwork.sendBeamTargetTo(player, living, ids, BEAM_COUNT.getOrDefault(id, 0));
        }
    }

    // ------------------------------------------------------------------
    // 主循环
    // ------------------------------------------------------------------

    private static void tick(LivingEntity wearer, ServerLevel level) {
        UUID id = wearer.getUUID();
        Integer stored = AGES.get(id);

        if (!wearsBrainrot(wearer) || wearer.isDeadOrDying() || wearer.isSpectator()) {
            if (stored != null) {
                end(level, wearer);
            }
            return;
        }

        if (stored == null) {
            // 和别的帽子一致：附近没有合法目标就不开新的一轮
            if (HatAbilities.findNearestEnemy(wearer) == null) {
                return;
            }
            start(level, wearer);
            AGES.put(id, 0);
            return;
        }

        int age = stored;
        applyTimeline(level, wearer, age);
        unwebNearby(level, wearer);
        if (age % 20 == 0) {
            purgePlayerDebuffs(level, wearer);
        }
        tickingSonic(level, wearer);
        // 负面效果爆出来的史莱姆/蠹虫：每 5 刻收一次目标
        if (age % 5 == 0) {
            BrainrotMinions.upkeep(level, wearer);
        }
        int next = age + 1;
        if (next >= CYCLE_TICKS) {
            end(level, wearer);
            return;
        }
        AGES.put(id, next);
    }

    /** 链路期间，蛛网免疫的半径（格）：戴帽者、这个范围内的玩家和己方单位都不会被粘住。 */
    private static final double WEB_IMMUNITY_RANGE = 24.0D;

    /**
     * 链路期间给玩家 / 附近的友方「蛛网免疫」。
     *
     * <p>网**照旧留在世界里**（不再像以前那样把网割掉），只是把「被粘住」的移动惩罚清掉：
     * 原版 {@code WebBlock.entityInside} 每刻把 {@code Entity#stuckSpeedMultiplier}
     * 设成 {@code (0.25, 0.05, 0.25)}，下一次 {@code move()} 开头吃掉这个值才会变慢；
     * 我们在实体 tick 之后把它清零，所以网还在、但人照常走过去。
     */
    private static void unwebNearby(ServerLevel level, LivingEntity wearer) {
        clearWebTrap(wearer);
        double rangeSq = WEB_IMMUNITY_RANGE * WEB_IMMUNITY_RANGE;
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(wearer) <= rangeSq) {
                clearWebTrap(player);
            }
        }
        for (LivingEntity ally : level.getEntitiesOfClass(LivingEntity.class,
                wearer.getBoundingBox().inflate(WEB_IMMUNITY_RANGE),
                entity -> entity != wearer && !(entity instanceof ServerPlayer)
                        && HatAbilities.isFriendlyTo(wearer, entity))) {
            clearWebTrap(ally);
        }
    }

    /**
     * 「定期清负面」只清这个时长以内的效果（刻 = 30 秒）。
     *
     * <p>传染类效果（深渊腐蚀之类）时长通常在 10–30 秒，都会被清掉；
     * 玩家自己喝的长效药水（动辄几分钟）不动。
     */
    private static final int PURGE_MAX_DURATION = 600;

    /**
     * 负面场会顺着敌人「传染」到玩家身上（神秘佳肴的深渊腐蚀就是典型：敌人带着它碰你一下，
     * 你也会被染上），所以链路期间每 20 刻给 **戴帽者 + 24 格内的玩家** 清一次短时负面效果。
     *
     * <p>链路自己挂的那几个（黑暗 / 漂浮 / 缓降 / 缓慢 / 迅捷）跳过 —— 它们是剧本的一部分，
     * 不是「外来的脏东西」。
     */
    private static void purgePlayerDebuffs(ServerLevel level, LivingEntity wearer) {
        purgeShortDebuffs(wearer);
        double rangeSq = WEB_IMMUNITY_RANGE * WEB_IMMUNITY_RANGE;
        for (ServerPlayer player : level.players()) {
            if (player != wearer && player.distanceToSqr(wearer) <= rangeSq) {
                purgeShortDebuffs(player);
            }
        }
    }

    private static void purgeShortDebuffs(LivingEntity target) {
        for (MobEffect effect : BuiltInRegistries.MOB_EFFECT) {
            if (effect.isBeneficial() || effect.isInstantenous()) {
                continue;
            }
            if (effect == MobEffects.DARKNESS || effect == MobEffects.LEVITATION
                    || effect == MobEffects.SLOW_FALLING || effect == MobEffects.MOVEMENT_SLOWDOWN
                    || effect == MobEffects.MOVEMENT_SPEED) {
                continue;
            }
            MobEffectInstance instance = target.getEffect(effect);
            if (instance != null && instance.getDuration() <= PURGE_MAX_DURATION) {
                target.removeEffect(effect);
            }
        }
    }

    /** 站在蛛网里就把「被粘」的移动惩罚清掉。 */
    private static void clearWebTrap(LivingEntity entity) {
        net.minecraft.world.level.block.state.BlockState state =
                entity.level().getBlockState(entity.blockPosition());
        if (state.is(net.minecraft.world.level.block.Blocks.COBWEB)) {
            // stuckSpeedMultiplier 是 protected，外面改不了；makeStuckInBlock 是公开入口，
            // 传一个全零倍率进去，下一次 move() 就不会乘上「被粘」的那个 0.25。
            entity.makeStuckInBlock(state, Vec3.ZERO);
        }
    }

    private static boolean wearsBrainrot(LivingEntity living) {
        return HatAbilities.hatStack(living).getItem() == HatItems.BRAIN_HAT.get();
    }

    private static void start(ServerLevel level, LivingEntity wearer) {
        HatMusic.playBrainrot(level, wearer);
        SPIN_SENT.put(wearer.getUUID(), false);
        // 这一轮的后室 Teto：曲子结束或摘帽时会自己发现循环没了，这里只是把它们放出来
        HatSummons.spawnTetos(level, wearer);
    }

    /** 曲终 / 摘帽 / 死亡：把这一轮留下的东西全部收掉。 */
    private static void end(ServerLevel level, LivingEntity wearer) {
        UUID id = wearer.getUUID();
        AGES.remove(id);
        SPIN_SENT.remove(id);
        BEAM_IDS.remove(id);
        BEAM_COUNT.remove(id);
        SONIC_LEFT.remove(id);
        SONIC_FROM.remove(id);
        SONIC_TO.remove(id);

        HatSummons.clearTetos(level, wearer);
        BrainrotMinions.clear(level, wearer);
        HatNetwork.sendBeamTarget(wearer, false, NO_IDS, 0);
        HatNetwork.sendBrainrotSpin(wearer, false);
        HatMusic.stopBrainrot(level, wearer);
        clearShortEffect(wearer, MobEffects.DARKNESS);
        clearShortEffect(wearer, MobEffects.LEVITATION);
        clearShortEffect(wearer, MobEffects.SLOW_FALLING);
        clearShortEffect(wearer, MobEffects.MOVEMENT_SLOWDOWN);
        clearShortEffect(wearer, MobEffects.MOVEMENT_SPEED);
        clearShortEffect(wearer, MobEffects.DAMAGE_RESISTANCE);
        clearShortEffect(wearer, MobEffects.REGENERATION);
    }

    /** 只清掉「快到期」的那份，不会误删玩家自己喝的长效药水。 */
    private static void clearShortEffect(LivingEntity living, MobEffect effect) {
        MobEffectInstance instance = living.getEffect(effect);
        if (instance != null && instance.getDuration() <= 60) {
            living.removeEffect(effect);
        }
    }

    // ------------------------------------------------------------------
    // 时间线
    // ------------------------------------------------------------------

    private static void applyTimeline(ServerLevel level, LivingEntity wearer, int age) {
        // 0 – 12 秒：黑暗（+ 抗性提升 II / 生命恢复 II）
        if (age < DARKNESS_END) {
            refreshDarknessShelter(wearer);
        }

        // 13 秒：第一次「No too late」
        if (age == TEXT_FIRST && wearer instanceof ServerPlayer player) {
            HatNetwork.sendBrainrotText(player);
        }

        // 14 秒起：原地自转 + 迅捷 I
        if (age >= SPIN_START && age < PAUSE_START) {
            setSpin(level, wearer, true);
        }
        if (age >= SPIN_START && age < FIRE_START) {
            refresh(wearer, MobEffects.MOVEMENT_SPEED, 0);
        }

        // 14 – 28.5 秒：脚下的减速圈逐刻扩大；**长到最大之后一直保留**
        if (age >= SPIN_START && age < FIRE_START) {
            double progress = (double) (age - SPIN_START) / (double) (FIRE_START - SPIN_START);
            slowRing(level, wearer, RING_MAX_RADIUS * progress);
        } else if (age >= FIRE_START && !inDarkness(age)) {
            // 已经到最大半径：后面每一刻都按最大半径续着，不再撤销。
            // 只有两段黑暗期（57–71 秒的漂浮+黑暗、100 秒到曲末的缓慢+黑暗）先收起来。
            slowRing(level, wearer, RING_MAX_RADIUS);
        }

        // 28.5 – 57 秒：照射（迅捷 II）
        if (age >= FIRE_START && age < PAUSE_START) {
            refresh(wearer, MobEffects.MOVEMENT_SPEED, 1);
            fire(level, wearer);
        }

        // 42.5 秒：声波
        if (age == SONIC_AT) {
            beginSonic(level, wearer);
        }

        // 57 秒：停火、停转；漂浮 + 黑暗
        if (age == PAUSE_START) {
            setSpin(level, wearer, false);
            HatNetwork.sendBeamTarget(wearer, false, NO_IDS, 0);
            BEAM_IDS.remove(wearer.getUUID());
            BEAM_COUNT.remove(wearer.getUUID());
            clearShortEffect(wearer, MobEffects.MOVEMENT_SPEED);
        }
        if (age >= PAUSE_START && age < FIRE2_START) {
            refresh(wearer, MobEffects.LEVITATION, 0);
            refreshDarknessShelter(wearer);
        }

        // 70 秒：第二次「No too late」
        if (age == TEXT_SECOND && wearer instanceof ServerPlayer player) {
            HatNetwork.sendBrainrotText(player);
        }

        // 71 秒：解除漂浮 / 黑暗；缓降 13 秒；继续自转与照射（3 道）
        if (age == FIRE2_START) {
            clearShortEffect(wearer, MobEffects.LEVITATION);
            clearShortEffect(wearer, MobEffects.DARKNESS);
        }
        if (age >= FIRE2_START && age < SLOW_DARK_AT) {
            setSpin(level, wearer, true);
            // 照射期一律带迅捷 II：和 28.5–57 秒那一段保持一致，光束在放就一定有加速
            refresh(wearer, MobEffects.MOVEMENT_SPEED, 1);
            if (age < SLOW_FALL_END) {
                refresh(wearer, MobEffects.SLOW_FALLING, 0);
            }
            fire(level, wearer);
        }
        // 100 秒起的黑暗期：继续转，但**不再放光束**
        if (age == SLOW_DARK_AT) {
            setSpin(level, wearer, true);
            HatNetwork.sendBeamTarget(wearer, false, NO_IDS, 0);
            BEAM_IDS.remove(wearer.getUUID());
            BEAM_COUNT.remove(wearer.getUUID());
            // 光束停了，照射期的迅捷 II 也一起撤掉（别让它跟缓慢叠在一起）
            clearShortEffect(wearer, MobEffects.MOVEMENT_SPEED);
        }

        // 100 秒 – 曲末：缓慢 + 黑暗（+ 抗性提升 II / 生命恢复 II）
        if (age >= SLOW_DARK_AT) {
            refresh(wearer, MobEffects.MOVEMENT_SLOWDOWN, 0);
            refreshDarknessShelter(wearer);
        }
    }

    /**
     * 黑暗期的自保包：黑暗 + **抗性提升 II + 生命恢复 II**。
     *
     * <p>黑暗阶段什么都看不见，57–71 秒还叠着漂浮、100 秒之后叠着缓慢 —— 是最容易白白挨打的几段。
     * 戴上这顶帽子等于把血条交给曲子，所以这三段统一补上抗性和回血。
     */
    private static void refreshDarknessShelter(LivingEntity wearer) {
        refresh(wearer, MobEffects.DARKNESS, 0);
        refresh(wearer, MobEffects.DAMAGE_RESISTANCE, 1);
        refresh(wearer, MobEffects.REGENERATION, 1);
    }

    /** 这一轮已经走到第几刻；没有在跑返回 -1。 */
    static int ageOf(LivingEntity wearer) {
        Integer age = AGES.get(wearer.getUUID());
        return age == null ? -1 : age;
    }

    /**
     * Teto 提前多少刻定住：**「No」刚要出声之前 6 刻（0.3 秒）**。
     *
     * <p>文字是 13 秒整才开始画的，提前一点定住，看上去才是「影子先僵住、词再砸下来」，
     * 而不是词都出来了它们才反应过来。
     */
    private static final int TEXT_FREEZE_LEAD = 6;

    /**
     * 定住多久（刻）：从提前量之后起算 **16 刻（0.8 秒）**，整段 1.1 秒 ——
     * 比三个词弹完（18 刻）短一点，收在「late」刚砸下来之后。
     *
     * <p>客户端的文字本身还会继续停到 58 刻（见 {@code client.BrainrotText.TOTAL_TICKS}），
     * 那一段 Teto 已经在继续转了 —— 想改就改这两个数。
     */
    private static final int TEXT_TICKS = 16;

    /**
     * Teto 现在要不要停转：只在**第一次「No too late」弹字的那几秒**里停。
     *
     * <p>停在圆周上的当前位置、只跟着戴帽者平移（与玩家相对静止）；文字一淡出就接着转。
     */
    static boolean tetoOrbitFrozen(LivingEntity wearer) {
        int age = ageOf(wearer);
        return age >= TEXT_FIRST - TEXT_FREEZE_LEAD && age < TEXT_FIRST + TEXT_TICKS;
    }

    /**
     * 是不是「黑暗期」—— 57–71 秒（漂浮 + 黑暗）和 100 秒到曲末（缓慢 + 黑暗）。
     * 减速圈在这两段里收起来，其它时候一旦长到最大就一直留着。
     */
    private static boolean inDarkness(int age) {
        return (age >= PAUSE_START && age < FIRE2_START) || age >= SLOW_DARK_AT;
    }

    /** 每刻续一次（40 刻的余量），避免效果在临界刻闪断。 */
    private static void refresh(LivingEntity living, MobEffect effect, int amplifier) {
        living.addEffect(new MobEffectInstance(effect, 40, amplifier, false, false, true));
    }

    private static void setSpin(ServerLevel level, LivingEntity wearer, boolean spin) {
        UUID id = wearer.getUUID();
        Boolean last = SPIN_SENT.get(id);
        if (last != null && last == spin) {
            return;
        }
        SPIN_SENT.put(id, spin);
        HatNetwork.sendBrainrotSpin(wearer, spin);
    }

    // ------------------------------------------------------------------
    // 减速圈
    // ------------------------------------------------------------------

    private static void slowRing(ServerLevel level, LivingEntity wearer, double radius) {
        if (radius <= 0.05D) {
            return;
        }
        // 圈内的敌人减速（缓慢 V），每刻续 10 刻
        for (LivingEntity target : enemiesNear(level, wearer, radius)) {
            target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 10, 4, false, false, true));
        }
        // 圈本身：半径处一圈紫色粒子
        //
        // 圈现在是「长到最大就一直画到曲末」，整圈 60 来个点每刻都发一遍就是每刻 60 个包、
        // 每秒 1200 个包 —— 撑不住。巫婆粒子能活 10 刻，所以拆成三份轮着发：
        // 每刻只发三分之一，三刻转完一整圈，画面上仍旧是连续的一整圈，包数只剩三分之一。
        double y = wearer.getY() + 0.1D;
        int points = Math.max(16, (int) (radius * 6.0D));
        int phase = wearer.tickCount % 3;
        for (int i = phase; i < points; i += 3) {
            double angle = Math.PI * 2.0D * i / points;
            level.sendParticles(ParticleTypes.WITCH,
                    wearer.getX() + Math.cos(angle) * radius, y, wearer.getZ() + Math.sin(angle) * radius,
                    1, 0.02D, 0.05D, 0.02D, 0.0D);
        }
    }

    // ------------------------------------------------------------------
    // 照射：AoE，没有百分比伤害
    // ------------------------------------------------------------------

    /**
     * 照射：**始终两道**光柱。
     *
     * <p>AoE 的含义是「一道光是一条线」，不是「射程内所有人一起掉血」：
     * 每道光柱先选一个敌人，然后沿**同一条射线**，从它开始一直到更远处、
     * 水平距离在 {@link #PIERCE_RADIUS} 以内的敌人全部算被这道光照到。
     *
     * <p>同一刻同一个敌人只会被打一次（两道光重叠也不叠加），
     * 也就是「不再额外调用」—— 伤害只来自那两道光的判定本身。
     */
    private static void fire(ServerLevel level, LivingEntity wearer) {
        List<LivingEntity> targets = combatTargets(level, wearer);
        syncBeam(wearer, targets, BEAM_LANES);
        if (targets.isEmpty()) {
            return;
        }
        DamageSource source = HatSettings.damageSource(level, wearer, HatType.BLACK);
        // 帧伤 = 黑帽那份配置 × 脑蚀倍率（默认 1.5，可在调参器「脑蚀」页改）
        float flat = HatSettings.damagePerTick(HatType.BLACK) * HatSettings.brainrotDamageScale();
        Vec3 origin = HatAbilities.beamOrigin(wearer, 1.0F);
        Set<UUID> hitThisTick = new HashSet<>();
        int beams = Math.min(BEAM_LANES, targets.size());
        for (int i = 0; i < beams; i++) {
            LivingEntity selected = targets.get(i);
            Vec3 aim = selected.getBoundingBox().getCenter().subtract(origin);
            double selectedDistance = aim.length();
            if (selectedDistance < 1.0E-3D) {
                continue;
            }
            Vec3 direction = aim.scale(1.0D / selectedDistance);
            for (LivingEntity target : targets) {
                Vec3 offset = target.getBoundingBox().getCenter().subtract(origin);
                double along = offset.dot(direction);
                // 只打「被选中的那个」和「它后面」的敌人（0.5 格余量，防浮点卡掉选中目标本身）
                if (along < selectedDistance - 0.5D) {
                    continue;
                }
                double perpendicularSq = offset.lengthSqr() - along * along;
                if (perpendicularSq > PIERCE_RADIUS * PIERCE_RADIUS) {
                    continue;
                }
                if (!hitThisTick.add(target.getUUID())) {
                    continue;
                }
                // 和普通光柱一样按住：AoE 结算不走 damageBeam，强控得在这里补
                HatAbilities.applyBeamHold(level, target);
                // 绕过原版受击无敌帧；不做百分比伤害，只结算固定伤害
                target.invulnerableTime = 0;
                target.hurt(source, flat);
                // 同样算戴帽者本人打的，宠物/小怪会跟着上
                wearer.setLastHurtMob(target);
            }
        }
    }

    /** 只在「目标列表 / 道数」变化时才发包。 */
    private static void syncBeam(LivingEntity wearer, List<LivingEntity> targets, int beams) {
        UUID id = wearer.getUUID();
        int shown = Math.min(beams, targets.size());
        int[] ids = new int[shown];
        for (int i = 0; i < shown; i++) {
            ids[i] = targets.get(i).getId();
        }
        int[] previous = BEAM_IDS.get(id);
        Integer previousCount = BEAM_COUNT.get(id);
        if (previous != null && java.util.Arrays.equals(previous, ids)
                && previousCount != null && previousCount == beams) {
            return;
        }
        BEAM_IDS.put(id, ids);
        BEAM_COUNT.put(id, beams);
        HatNetwork.sendBeamTarget(wearer, true, ids, beams);
    }

    /**
     * 开火 / 声波统一的目标池：**刚打过戴帽者的**（不管多远）排在前面，再接射程内的敌人。
     *
     * <p>减速圈不走这里 —— 它是以脚下为圆心的一个圆，按圈半径算才对。
     */
    private static List<LivingEntity> combatTargets(ServerLevel level, LivingEntity wearer) {
        List<LivingEntity> targets = new ArrayList<>(HatAbilities.recentAggressors(wearer));
        for (LivingEntity target : enemiesNear(level, wearer, FIRE_RANGE)) {
            if (!targets.contains(target)) {
                targets.add(target);
            }
        }
        return targets;
    }

    /** 射程内的所有合法敌人，按距离从近到远。 */
    private static List<LivingEntity> enemiesNear(ServerLevel level, LivingEntity wearer, double range) {
        List<LivingEntity> found = new ArrayList<>();
        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class,
                wearer.getBoundingBox().inflate(range))) {
            if (target != wearer && target.isAlive() && HatAbilities.isEnemy(target, wearer)) {
                found.add(target);
            }
        }
        found.sort(Comparator.comparingDouble(wearer::distanceToSqr));
        return found;
    }

    // ------------------------------------------------------------------
    // 声波（监守者同款，无音效）
    // ------------------------------------------------------------------

    private static void beginSonic(ServerLevel level, LivingEntity wearer) {
        UUID id = wearer.getUUID();
        LivingEntity target = HatAbilities.findNearestEnemy(wearer);
        Vec3 from = wearer.position().add(0.0D, wearer.getBbHeight() * 0.6D, 0.0D);
        Vec3 to = target != null
                ? target.getEyePosition()
                : from.add(wearer.getViewVector(1.0F).normalize().scale(16.0D));
        SONIC_LEFT.put(id, SONIC_TICKS);
        SONIC_FROM.put(id, from);
        SONIC_TO.put(id, to);
    }

    private static void tickingSonic(ServerLevel level, LivingEntity wearer) {
        UUID id = wearer.getUUID();
        Integer left = SONIC_LEFT.get(id);
        if (left == null) {
            return;
        }
        Vec3 from = SONIC_FROM.get(id);
        Vec3 to = SONIC_TO.get(id);
        if (from == null || to == null) {
            SONIC_LEFT.remove(id);
            return;
        }
        // 一秒把整条线画完：每刻推进一段
        int step = SONIC_TICKS - left;
        double f0 = (double) step / SONIC_TICKS;
        double f1 = (double) (step + 1) / SONIC_TICKS;
        Vec3 delta = to.subtract(from);
        for (int i = 0; i < 8; i++) {
            double f = f0 + (f1 - f0) * i / 8.0D;
            Vec3 point = from.add(delta.scale(f));
            level.sendParticles(ParticleTypes.SONIC_BOOM, point.x, point.y, point.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
        if (left <= 1) {
            resolveSonic(level, wearer, from, to);
            SONIC_LEFT.remove(id);
            SONIC_FROM.remove(id);
            SONIC_TO.remove(id);
            return;
        }
        SONIC_LEFT.put(id, left - 1);
    }

    private static void resolveSonic(ServerLevel level, LivingEntity wearer, Vec3 from, Vec3 to) {
        DamageSource source = level.damageSources().sonicBoom(wearer);
        Vec3 delta = to.subtract(from);
        double lengthSq = delta.lengthSqr();
        if (lengthSq < 1.0E-4D) {
            return;
        }
        for (LivingEntity target : combatTargets(level, wearer)) {
            Vec3 center = target.getBoundingBox().getCenter();
            double t = Math.max(0.0D, Math.min(1.0D, center.subtract(from).dot(delta) / lengthSq));
            Vec3 closest = from.add(delta.scale(t));
            if (closest.distanceToSqr(center) > SONIC_HIT_RADIUS * SONIC_HIT_RADIUS) {
                continue;
            }
            target.invulnerableTime = 0;
            // 声波带控：缓慢 255 + 虚弱 255 + 挖掘疲劳 255 + 强制 NoAI，定住 3 秒。
            // 必须在 hurt 之前挂 —— hurt 之后目标可能已经被击退/打死了。
            HatAbilities.applySonicHold(level, target, SONIC_HOLD_TICKS);
            if (target.hurt(source, SONIC_DAMAGE)) {
                Vec3 push = delta.normalize();
                target.push(push.x * 2.5D, 0.5D, push.z * 2.5D);
            }
            // 声波也算戴帽者打的，宠物/小怪会跟着上
            wearer.setLastHurtMob(target);
        }
    }

    /** 给 {@code HatAbilities} 之类的地方查询「这个戴帽者现在是不是在脑蚀循环里」。 */
    public static boolean isRunning(LivingEntity living) {
        return AGES.containsKey(living.getUUID());
    }

    /** 世界里现在有没有人正在跑脑蚀循环（给实体加入世界那种高频事件做快速挡板）。 */
    public static boolean anyRunning() {
        return !AGES.isEmpty();
    }

    /** 调试/文档用：这一轮走了多少刻，没在循环里返回 -1。 */
    public static int ageOf(Entity entity) {
        Integer age = AGES.get(entity.getUUID());
        return age == null ? -1 : age;
    }
}
