package com.hatmod;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Holder;

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
 *  43.2s        额外放一次监守者同款声波（无音效，动画 1 秒）
 *  57s          完全恢复正常、停火，获得漂浮 + 黑暗
 *  70s          再次弹出「No too late」
 *  71s          解除漂浮/黑暗，获得 13 秒缓降，继续自转与照射
 *  100s         获得缓慢 + 黑暗：继续自转，但**停止照射**，直到曲末
 *  105.7s       曲终：全部解除；附近还有敌人就进入下一轮
 * </pre>
 *
 * <p>和其它帽子一样，这里只管服务端：光柱的「正在照射 / 道数 / 锁定谁」走
 * {@code HatNetwork.BeamTargetPayload}，自转走 {@code BrainrotSpinPayload}，
 * 屏幕文字走 {@code BrainrotTextPayload}。
 */
@EventBusSubscriber(modid = HatMod.MOD_ID)
public final class BrainrotCycle {

    /** 曲子长度：1:45.67 → 2114 刻（取整到刻）。整轮循环就按这个长度走。 */
    public static final int CYCLE_TICKS = 2114;

    /** 0 秒到 12 秒：黑暗。 */
    private static final int DARKNESS_END = 12 * 20;
    /** 13 秒：第一次「No too late」。 */
    /** 260 刻（13 秒）：三个词 260 / 269 / 278，和 READY / SET / GO 同刻。 */
    private static final int TEXT_FIRST = 260;
    /** 14 秒：开始自转、迅捷 I、减速圈开始扩大。 */
    private static final int SPIN_START = 14 * 20;
    /** 28.5 秒：减速圈到最大半径，开始照射。 */
    private static final int FIRE_START = 570;
    /**
     * 43.2 秒（864 刻）：声波 —— 原曲里那一「声」。
     *
     * <p>原曲 64.15–64.45 秒先把乐器整个抽掉（全曲只有两处这样的硬停，另一处就在
     * 「Nope too late」前面），紧接着一层从弱到强的高频尖啸，64.74 到顶，65.05 接上人声。
     * 862 刻 = 原曲 64.46 秒：乐器砸回来、尖啸刚起的那一下 —— 声波和随之而来的定身
     * 都从这里算。早先的 850（42.5 秒）只是大致落在这一段里。
     */
    private static final int SONIC_AT = 862;
    /** 57 秒：停火 + 漂浮 + 黑暗。 */
    private static final int PAUSE_START = 57 * 20;
    /** 70 秒：第二次「No too late」。 */
    /** 1398 刻：同理，1398 / 1407 / 1416（原曲 91.26 / 91.70 / 92.16 秒）。 */
    private static final int TEXT_SECOND = 1398;
    /** 71 秒：恢复自转与照射（3 道），缓降 13 秒。 */
    private static final int FIRE2_START = 1420;
    /** 84 秒：缓降结束（71 + 13）。 */
    private static final int SLOW_FALL_END = 1680;
    /**
     * 光柱道数：**始终两道**。
     *
     * <p>「AoE」这里有两层：一是选中的敌人和它**后面**被同一道光扫到的一起受伤，
     * 二是判定范围就是**画出来的那道光锥本身**（见 {@link #fire}）。
     */
    private static final int BEAM_LANES = 2;

    // 光锥判定：必须和客户端 BeamRenderer 的绘制尺寸一模一样，
    // 否则会出现「明明看着在光里却不掉血」。
    /** 灯口半宽（格），对应 {@code BeamRenderer.LENS_RADIUS}。 */
    private static final double BEAM_LENS_RADIUS = 0.16D;
    /** 末端半宽（格），对应 {@code BeamRenderer.BRAINROT_END_RADIUS}。 */
    private static final double BEAM_END_RADIUS = 12.0D;
    /** 光锥长度（格），对应 {@code BeamRenderer.BRAINROT_RANGE}。 */
    private static final double BEAM_LENGTH = 40.0D;
    /** 判定下限半宽（格）：光锥在灯口附近太细，留一点余量免得贴着目标还判空。 */
    private static final double BEAM_MIN_RADIUS = 1.0D;
    /** 100 秒：缓慢 + 黑暗，直到曲末。 */
    private static final int SLOW_DARK_AT = 100 * 20;

    /** 减速圈最大半径。 */
    private static final double RING_MAX_RADIUS = 10.0D;
    /** 照射范围（格）：「脑蚀」的光柱比普通光柱长得多，取敌人也放到这么远。 */
    private static final double FIRE_RANGE = 40.0D;
    /** 声波动画时长。 */
    private static final int SONIC_TICKS = 20;
    /** 声波基础伤害。 */
    private static final float SONIC_DAMAGE = 18.0F;
    /** 再按目标最大生命的这个比例加一段。 */
    private static final float SONIC_HEALTH_RATIO = 0.05F;
    /** 单次声波伤害上限。 */
    private static final float SONIC_DAMAGE_CAP = 60.0F;
    /** 扇形扩散的半边张角（度）：一共 80° 的喇叭口。 */
    private static final double SONIC_CONE_HALF_DEGREES = 40.0D;
    /** 扇形最远距离。 */
    private static final double SONIC_RANGE = 30.0D;
    /** 尖叫之后光束收起来多少刻（0.5 秒）。 */
    private static final int SONIC_BEAM_PAUSE = 10;
    /** 声波命中的「易伤」持续多久（刻）：10 秒。 */
    private static final int SONIC_VULNERABLE_TICKS = 200;
    /** 被尖叫打中的敌人抖多久（刻）：1 秒。 */
    private static final int SONIC_SHAKE_TICKS = 20;
    /** 声波带控多久（刻）：20 秒定身，和光柱的强控同一套。 */
    private static final int SONIC_HOLD_TICKS = 400;
    /** 开场冲击波：半径（格）。 */
    private static final double SHOCK_RADIUS = 15.0D;
    /** 开场冲击波：一共几次（第一次在链路开始那一刻，最后一次正好是「No」出声）。 */
    private static final int SHOCK_COUNT = 8;
    /** 冲击波的横向推速（格/刻，贴脸时）。 */
    private static final double SHOCK_POWER = 2.4D;
    /** 冲击波的向上分量（格/刻，贴脸时）。 */
    private static final double SHOCK_LIFT = 0.45D;

    /** 每次「No too late」出现时，多大范围内的人会被钉住（格）。 */
    private static final double STASIS_RADIUS = 50.0D;
    /**
     * 钉住多久（刻）＝ **和 Teto 停转完全同一段**：提前 {@link #TEXT_FREEZE_LEAD} 刻开始，
     * 一共 {@code TEXT_FREEZE_LEAD + TEXT_TICKS} 刻（0.3 秒前起，1.1 秒）。
     *
     * <p>写成方法而不是常量：那两个常量在文件后面才声明，字段初始化顺序会让这里的值变成 0。
     */
    private static int stasisTicks() {
        return TEXT_FREEZE_LEAD + TEXT_TICKS;
    }
    /**
     * 被钉住的玩家 -> 钉住的位置与到期刻。
     *
     * <p>玩家是客户端权威的，光改服务端速度拦不住 —— 所以这里记住位置，
     * 每刻发现人偏出去就 {@code teleportTo} 拉回来，跳也跳不走。
     */
    private static final Map<UUID, Pin> PINNED = new HashMap<>();

    /** 一次「定住」：原地不动到 {@code until} 刻。 */
    private static final class Pin {
        private final Vec3 pos;
        private final long until;

        private Pin(Vec3 pos, long until) {
            this.pos = pos;
            this.until = until;
        }
    }

    /** 戴帽者 -> 这一轮里**有没有见过** Teto（用来判断「全被打死了」）。 */
    private static final Map<UUID, Boolean> TETO_SEEN = new HashMap<>();

    /** 戴帽者 -> 本轮已经走了多少刻。**键存在** = 这一轮正在进行。 */
    private static final Map<UUID, Integer> AGES = new HashMap<>();
    /** 戴帽者 -> 上一次发给客户端的自转状态，用来去重。 */
    private static final Map<UUID, Boolean> SPIN_SENT = new HashMap<>();
    /** 戴帽者 -> 上一次发给客户端的光柱道数 / 目标列表（去重）。 */
    private static final Map<UUID, int[]> BEAM_IDS = new HashMap<>();
    private static final Map<UUID, Integer> BEAM_COUNT = new HashMap<>();
    /** 被尖叫打中、正在发抖的敌人 -> 还剩几刻。 */
    private static final Map<UUID, Integer> SHAKE_LEFT = new HashMap<>();
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
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            tick(player, player.serverLevel());
        }
    }

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        if (event.getEntity() instanceof LivingEntity living
                && !(living instanceof ServerPlayer)
                && living.level() instanceof ServerLevel level) {
            tick(living, level);
        }
    }

    /**
     * 进服时把落盘的「自动打拍」开关恢复，并告诉客户端（调参器按钮要显示成开着）。
     *
     * <p>服务端那一份在 {@link BrainrotQte#autoTapping} 里也会按需从配置恢复，
     * 这里主要是让界面跟上；顺便把内存里的集合立起来。
     */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player
                && HatSettings.debugAutoTap(player.getUUID())) {
            BrainrotQte.setAutoTap(player, true);
            HatNetwork.sendAutoTapTo(player, true);
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
            BrainrotQte.beginRound(wearer, true);
            AGES.put(id, 0);
            return;
        }

        int age = stored;
        // ---- 谱子按**真实时间**推进 ----
        // 谱面是按「20 刻 = 1 秒」写的，BGM 也是按真实时间放的；可服务器一旦掉 TPS
        // （实体一多就会），一刻实际要 60~100ms，谱子就会越落越晚 —— 攻击、定身、声波
        // 全都会比听到的音乐慢半拍。这里攒「真实流逝了多少刻」，一个服务器刻里最多连走
        // MAX_CATCH_UP 刻把时间追回来。追的部分也是**一刻一刻按顺序走**的，所以
        // `age == X` 那些一次性触发（声波、停火、第二次黑暗……）一个都不会漏。
        int steps = stepsFor(id);
        for (int i = 0; i < steps; i++) {
            int at = age + i;
            applyTimeline(level, wearer, at);
            tickingSonic(level, wearer);
            // 打拍：把过窗没按的拍记成 MISS、按连击续 buff
            BrainrotQte.tick(level, wearer, at);
            // 调参器里的「自动打拍」：开着就由服务端替他踩拍（这一轮不发成就）
            if (wearer instanceof ServerPlayer autoPlayer && BrainrotQte.autoTapping(wearer)) {
                BrainrotQte.autoTap(autoPlayer, at);
            }
            if (at + 1 >= CYCLE_TICKS) {
                // 整首放完才判「节奏大师」；中途摘帽 / 死亡 / Teto 团灭都不算
                BrainrotQte.finishRound(wearer);
                end(level, wearer);
                return;
            }
        }
        AGES.put(id, age + steps);

        // ---- 下面这些和谱面无关：一个服务器刻做一次就够 ----
        unwebNearby(level, wearer);
        tickShakes(level);
        tickPins(level);
        tickFrozenShots(level);
        if (age % 20 == 0) {
            purgePlayerDebuffs(level, wearer);
        }
        // 「节奏大师」要全程在战斗里：每 5 刻看一眼周边还有没有敌人
        if (age % 5 == 0) {
            BrainrotQte.noteCombat(wearer, HatAbilities.findNearestEnemy(wearer) != null);
        }
        // 每 10 刻看一眼四只 Teto 还在不在：见过、现在一只都不剩 → 这一轮直接作废，
        // 下一刻从头开始（曲子、时间线、蛛网免疫、召唤物全都清掉）
        if (age % 10 == 0 && checkTetos(level, wearer)) {
            end(level, wearer);
            return;
        }
        // 负面效果爆出来的史莱姆/蠹虫：每 5 刻收一次目标
        if (age % 5 == 0) {
            BrainrotMinions.upkeep(level, wearer);
        }
    }

    /** 一次最多补几刻：再多说明是暂停 / 传送 / 读盘，硬补会把伤害挤成一坨。 */
    private static final int MAX_CATCH_UP = 3;

    /** 每个戴帽者上一次走谱的真实时刻、以及攒下的零头（刻）。 */
    private static final Map<UUID, Long> STEP_NANOS = new HashMap<>();
    private static final Map<UUID, Double> STEP_CARRY = new HashMap<>();

    /**
     * 这一服务器刻该走几刻谱：按真实流逝时间算，1 ~ {@link #MAX_CATCH_UP}。
     *
     * <p>正常 20 TPS 时每次正好 1；掉到 15 TPS 就补成 1.33，攒够就多走一刻。
     * 超过 5 秒的空档（单人暂停、区块读盘）直接当作 1 刻，不追 —— 那段时间 BGM 也停着。
     */
    private static int stepsFor(UUID id) {
        long now = System.nanoTime();
        Long last = STEP_NANOS.put(id, now);
        double elapsed = last == null ? 1.0D : (now - last) / 50_000_000.0D;
        if (elapsed > 5.0D) {
            elapsed = 1.0D;
        }
        elapsed = Math.min(elapsed, MAX_CATCH_UP);
        double pending = elapsed + STEP_CARRY.getOrDefault(id, 0.0D);
        int steps = (int) pending;
        if (steps <= 0) {
            steps = 1;
            pending = 1.0D;
        }
        STEP_CARRY.put(id, Math.max(0.0D, pending - steps));
        return Math.min(steps, MAX_CATCH_UP);
    }

    /**
     * 这一轮的 Teto 全死了吗？
     *
     * <p>先记「见过」再判断「没了」：开轮那一帧 Teto 才刚生成，如果一上来就查会误判成
     * 「全死」，链路瞬间自清。只有确实有过、现在又一只不剩，才算团灭。
     *
     * @return true = 团灭，调用方应当清空这一轮
     */
    private static boolean checkTetos(ServerLevel level, LivingEntity wearer) {
        UUID id = wearer.getUUID();
        int alive = level.getEntitiesOfClass(Teto.class,
                wearer.getBoundingBox().inflate(128.0D), teto -> teto.isOwnedBy(wearer)).size();
        if (alive > 0) {
            TETO_SEEN.put(id, Boolean.TRUE);
            return false;
        }
        return Boolean.TRUE.equals(TETO_SEEN.get(id));
    }

    /** 链路期间，蛛网免疫的半径（格）：戴帽者、这个范围内的玩家和己方单位都不会被粘住。 */
    private static final double WEB_IMMUNITY_RANGE = 24.0D;

    /**
     * 链路期间给玩家 / 附近的友方「蛛网免疫」。
     *
     * <p>网**照旧留在世界里**（不再像以前那样把网割掉），只是把「被粘住」的移动惩罚清掉：
     * 原版 {@code WebBlock.entityInside} 每刻把 {@link net.minecraft.world.entity.Entity#stuckSpeedMultiplier}
     * 设成 {@code (0.25, 0.05, 0.25)}，下一次 {@code move()} 开头吃掉这个值才会变慢；
     * 我们在实体 tick 之后把它清零，所以网还在、但人照常走过去。
     */
    private static void unwebNearby(ServerLevel level, LivingEntity wearer) {
        // 真正的免疫靠 mixin 拦截 WebBlock#entityInside：名单里的人踩进网里等于踩进空气
        WebImmunity.protect(wearer);
        double rangeSq = WEB_IMMUNITY_RANGE * WEB_IMMUNITY_RANGE;
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(wearer) <= rangeSq) {
                WebImmunity.protect(player);
            }
        }
        for (LivingEntity ally : level.getEntitiesOfClass(LivingEntity.class,
                wearer.getBoundingBox().inflate(WEB_IMMUNITY_RANGE),
                entity -> entity != wearer && !(entity instanceof ServerPlayer)
                        && HatAbilities.isFriendlyTo(wearer, entity))) {
            WebImmunity.protect(ally);
        }
        // 兜底：万一某个实体在名单加进来之前就已经被粘住了，这里顺手清一下
        clearWebTrap(wearer);
    }

    /** 曲终 / 摘帽：把「免粘」名单也清掉。 */
    static void clearWebImmunity() {
        WebImmunity.clearServer();
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
        for (Holder<MobEffect> holder : BuiltInRegistries.MOB_EFFECT.holders().toList()) {
            MobEffect effect = holder.value();
            if (effect.isBeneficial() || effect.isInstantenous()) {
                continue;
            }
            if (holder.is(MobEffects.DARKNESS) || holder.is(MobEffects.LEVITATION)
                    || holder.is(MobEffects.SLOW_FALLING) || holder.is(MobEffects.MOVEMENT_SLOWDOWN)
                    || holder.is(MobEffects.MOVEMENT_SPEED)) {
                continue;
            }
            MobEffectInstance instance = target.getEffect(holder);
            if (instance != null && instance.getDuration() <= PURGE_MAX_DURATION) {
                target.removeEffect(holder);
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
        return HatAbilities.hatStack(living).is(HatItems.BRAIN_HAT.get());
    }

    private static void start(ServerLevel level, LivingEntity wearer) {
        HatMusic.playBrainrot(level, wearer);
        SPIN_SENT.put(wearer.getUUID(), false);
        // 这一轮的后室 Teto：曲子结束或摘帽时会自己发现循环没了，这里只是把它们放出来
        HatSummons.spawnTetos(level, wearer);
    }

    /**
     * 强制开一轮（P 键）：附近没有敌人也照开。
     *
     * <p>正常流程要等到附近有合法目标才起手，方便「有仗才打」；但玩家有时候想先起手 ——
     * 比如先把音乐放起来、先把 Teto 放出来。已经在跑就什么也不做。
     *
     * @return true = 真的开了一轮
     */
    public static boolean forceStart(LivingEntity wearer) {
        if (!(wearer.level() instanceof ServerLevel level)) {
            return false;
        }
        if (!wearsBrainrot(wearer) || wearer.isDeadOrDying() || wearer.isSpectator()) {
            return false;
        }
        if (AGES.containsKey(wearer.getUUID())) {
            return false;
        }
        start(level, wearer);
        BrainrotQte.beginRound(wearer, false);
        AGES.put(wearer.getUUID(), 0);
        return true;
    }

    /** 曲终 / 摘帽 / 死亡：把这一轮留下的东西全部收掉。 */
    private static void end(ServerLevel level, LivingEntity wearer) {
        UUID id = wearer.getUUID();
        AGES.remove(id);
        STEP_NANOS.remove(id);
        STEP_CARRY.remove(id);
        SPIN_SENT.remove(id);
        BEAM_IDS.remove(id);
        BEAM_COUNT.remove(id);
        SONIC_LEFT.remove(id);
        SONIC_FROM.remove(id);
        SONIC_TO.remove(id);
        TETO_SEEN.remove(id);
        BrainrotQte.clear(wearer);
        // 曲终/摘帽时还钉着的人要立刻放掉，别留着一条永远到不了期的 Pin
        PINNED.clear();
        releaseAllShots();

        HatSummons.clearTetos(level, wearer);
        BrainrotMinions.clear(level, wearer);
        HatNetwork.sendBeamTarget(wearer, false, NO_IDS, 0);
        HatNetwork.sendBrainrotSpin(wearer, false);
        HatMusic.stopBrainrot(level, wearer);
        clearWebImmunity();
        clearShortEffect(wearer, MobEffects.DARKNESS);
        clearShortEffect(wearer, MobEffects.LEVITATION);
        clearShortEffect(wearer, MobEffects.SLOW_FALLING);
        clearShortEffect(wearer, MobEffects.MOVEMENT_SLOWDOWN);
        clearShortEffect(wearer, MobEffects.MOVEMENT_SPEED);
        clearShortEffect(wearer, MobEffects.DAMAGE_RESISTANCE);
        clearShortEffect(wearer, MobEffects.REGENERATION);
    }

    /** 只清掉「快到期」的那份，不会误删玩家自己喝的长效药水。 */
    private static void clearShortEffect(LivingEntity living, Holder<MobEffect> effect) {
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

        // 0 – 13 秒：开场冲击波，约每 2 秒一次、共 8 次，最后一次正好落在「No」出声那一刻
        if (isShockTick(age)) {
            shockwave(level, wearer);
        }

        // 和 Teto 停转同一段：提前 6 刻（「No」刚要出声）开始，50 格内所有人原地定住
        if (age == TEXT_FIRST - TEXT_FREEZE_LEAD) {
            stasisNearby(level, wearer);
        }

        // 14 秒起：原地自转 + 迅捷 I
        if (age >= SPIN_START && age < PAUSE_START) {
            setSpin(level, wearer, true);
        }
        if (age >= SPIN_START && age < FIRE_START) {
            // 非光束阶段：速度 I（只是走位舒服一点，不抢光束阶段的风头）
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

        // 28.5 – 57 秒：照射（迅捷 II）；尖叫之后光束收 0.3 秒，把舞台让给那一声
        if (age >= FIRE_START && age < PAUSE_START) {
            // 光束阶段：速度 III（光柱在放的时候人也要跟得上）
            refresh(wearer, MobEffects.MOVEMENT_SPEED, 2);
            if (inSonicPause(age)) {
                if (age == SONIC_AT + 1) {
                    HatNetwork.sendBeamTarget(wearer, false, NO_IDS, 0);
                    BEAM_IDS.remove(wearer.getUUID());
                    BEAM_COUNT.remove(wearer.getUUID());
                }
            } else {
                fire(level, wearer);
            }
        }

        // 43.2 秒：声波。光束停的那 0.5 秒里，50 格内所有人也吃「No too late」同款定住 ——
        // 整场安静半秒，把舞台完全让给这一声
        if (age == SONIC_AT) {
            beginSonic(level, wearer);
            stasisNearby(level, wearer, SONIC_BEAM_PAUSE);
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

        // 第二次「No too late」：人还是在同一时机钉住
        if (age == TEXT_SECOND - TEXT_FREEZE_LEAD) {
            stasisNearby(level, wearer);
        }

        // 71 秒：解除漂浮 / 黑暗；缓降 13 秒；继续自转与照射（3 道）
        if (age == FIRE2_START) {
            clearShortEffect(wearer, MobEffects.LEVITATION);
            clearShortEffect(wearer, MobEffects.DARKNESS);
        }
        if (age >= FIRE2_START && age < SLOW_DARK_AT) {
            setSpin(level, wearer, true);
            // 照射期一律带速度 III：和 28.5–57 秒那一段保持一致，光束在放就一定有加速
            refresh(wearer, MobEffects.MOVEMENT_SPEED, 2);
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
            // 光束停了，照射期的速度 III 也一起撤掉（别让它跟缓慢叠在一起）
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

    /**
     * 现在是黑暗阶段吗（Teto 的发光跟着这个走）。
     *
     * <p>黑暗段一共三段：0–12 秒、57–71 秒、100 秒到曲末 —— 这几段 Teto 才亮，
     * 其余时间它就是个安安静静的影子。
     */
    public static boolean isDarkPhase(LivingEntity wearer) {
        int age = ageOf(wearer);
        // 注意：{@link #inDarkness} 只管「减速圈要收起来」的那两段，**不含开场 0–12 秒**
        // （那时圈还没长出来，没什么可收的）。但玩家开场就是在黑暗里，
        // 所以这里必须把第一段单独算进来，否则 Teto 前 12 秒不发光。
        return age >= 0 && (age < DARKNESS_END || inDarkness(age));
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
    private static void refresh(LivingEntity living, Holder<MobEffect> effect, int amplifier) {
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
        // 圈内的弹射物也一起慢下来：和牛仔帽「时间差力场」同一个做法（关重力 + 压速，方向不变）
        slowShotsInRing(level, wearer, radius);
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
     * 照射：**始终两道**光柱，每道光按**它自己画出来的那个锥体**结算伤害。
     *
     * <p>每道光柱先选一个敌人当轴，然后：选中的它自己 + 它**后面**的所有敌人，
     * 只要落在光锥截面里就算被照到（半宽随距离张开，末端 3.4 格 —— 和画面里
     * 那团光一样宽），所以一道光过去是一整片，不再是一条 4 格宽的细线。
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
        // 力量/虚弱也作用在光柱上（牛仔帽同款）：红帽的「照射期力量 I」天然 ×1.35
        float power = HatAbilities.beamPowerMultiplier(wearer);
        // 帧伤 = 黑帽那份配置 × 脑蚀倍率（默认 1.5，可在调参器「脑蚀」页改）
        // × 打拍连击倍率（每连击 +10%，封顶 ×4.0）× 力量倍率
        float flat = HatSettings.damagePerTick(HatType.BLACK) * HatSettings.brainrotDamageScale()
                * BrainrotQte.damageMultiplier(wearer) * power;
        // 打中一拍后的「涌动」：光柱这一刻更长更粗，倍率随剩余时间缩回 1
        float[] surge = new float[2];
        BrainrotQte.surgeScale(wearer, ageOf(wearer), surge);
        final double beamLength = BEAM_LENGTH * surge[0];
        final double beamEndRadius = BEAM_END_RADIUS * surge[1];
        // 重音：这一击附带**目标当前生命**的百分比伤害（本体光柱没有百分比，
        // 打高血限伤 Boss 的短板就靠这块补）。两层：
        //   · 基础：不管打不打拍，光柱本身就有 0.5%/刻 的当前生命伤害 ——
        //     这是 Boss 战的底子（没有连击也不至于只剩每刻 1.5 点固定伤害）；
        //   · 连击：连击 ×0.25%/刻（10 连击 2.5%/刻、封顶 40 连击 10%/刻）——
        //     连击一上来立刻见效、不用等涌动，所以「打得快」；PERFECT 一次涨 2 层，成型也快；
        //   · 涌动再加一层：(涌动粗细 - 1) × 3%，PERFECT 峰值再 +2.4%/刻。
        // 用 getHealth（当前值）：越打越少、自动衰减，不会变成秒杀。
        // 连击倍率同样乘在百分比上（无上限、每层 +2%）；涌动那层也在里面
        final double accentRatio = (0.005D + (surge[1] - 1.0D) * 0.03D)
                * BrainrotQte.damageMultiplier(wearer) * power;
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
            // 和客户端完全同一套散射：判定用的轴线就是画出来的那道光的轴线
            Vec3 direction = HatAbilities.fanDirection(aim, i, beams).normalize();
            for (LivingEntity target : targets) {
                Vec3 offset = target.getBoundingBox().getCenter().subtract(origin);
                double along = offset.dot(direction);
                // 只打「被选中的那个」和「它后面」的敌人（0.5 格余量，防浮点卡掉选中目标本身）
                if (along < selectedDistance - 0.5D) {
                    continue;
                }
                // 光锥到 24 格就没了，再远不该被打
                if (along > beamLength) {
                    continue;
                }
                // 这个距离处光锥有多宽（再加目标自己的半个体宽，大体型不至于擦边漏掉）
                double radius = beamRadiusAt(along, beamLength, beamEndRadius) + target.getBbWidth() * 0.5D;
                double perpendicularSq = Math.max(0.0D, offset.lengthSqr() - along * along);
                if (perpendicularSq > radius * radius) {
                    continue;
                }
                if (!hitThisTick.add(target.getUUID())) {
                    continue;
                }
                // 只有**被这道光选中的那个**才吃强控；被同一条光顺带扫到的只扣血，不控制
                if (target == selected) {
                    HatAbilities.applyBeamHold(level, target);
                }
                // 光柱是「持续照射」，不是重击：把这一下的速度记下来，结算完原样放回去，
                // 所以只掉血、不被推走（`hatmod:flash` 也打了 no_knockback 标签，这里是双保险）
                Vec3 velocity = target.getDeltaMovement();
                // 固定伤害走 hurt：死亡流程、仇恨、击退都靠它
                target.invulnerableTime = 0;
                target.hurt(source, flat);
                target.setDeltaMovement(velocity);
                target.hurtMarked = true;
                // 重音（百分比）**不走 hurt**，直接 setHealth —— 灾变那类 Boss 在 hurt() 里挂的
                // 「单次受伤上限 + 每秒伤害桶」会把走 hurt 的百分比全吃掉，这就是之前
                // 打使徒怎么堆连击都刮痧的根因（牛仔帽的百分比一直就是 setHealth 打的）。
                applyAccent(wearer, target, accentRatio);
                // 同样算戴帽者本人打的，宠物/小怪会跟着上
                wearer.setLastHurtMob(target);
            }
        }
    }

    /**
     * 重音（百分比伤害）：直接 {@link LivingEntity#setHealth} 扣血，**不经过 hurt()**。
     *
     * <p>为什么必须绕开：Boss 类模组普遍在 {@code hurt()} 里塞「单次受伤上限」「每秒伤害桶」
     * 这类减伤，走 hurt 的百分比再大也会被削成一两点；setHealth 不参与伤害结算，
     * 这些上限拦不住它 —— 牛仔帽的百分比伤害一直就是这条路。
     *
     * <p>**没有每轮预算**：连击越长，这一击的百分比越大（倍率不封顶），打满一首就是几十倍。
     * 唯一保留的是 5% 地板 —— 目标血量掉到最大生命的 5% 以下就停手，把收尾交给固定伤害
     * （setHealth 不触发 {@code die()}，不设地板会出现「血量 0 却不死」）。
     */
    private static void applyAccent(LivingEntity wearer, LivingEntity target, double ratio) {
        if (ratio <= 0.0D || target.isDeadOrDying()) {
            return;
        }
        float health = target.getHealth();
        // 5% 地板：再往下就交给固定伤害走正常死亡流程
        if (health < target.getMaxHealth() * 0.05F) {
            return;
        }
        float damage = (float) (health * Math.min(ratio, 0.95D));
        if (damage <= 0.0F) {
            return;
        }
        target.setHealth(health - damage);
    }

    /**
     * 光柱在「沿轴 {@code along} 格」处的半宽（格）—— 和客户端画的那个圆台同一个斜率。
     *
     * <p>{@code BeamRenderer} 里每层圆台都是「灯口 {@link #BEAM_LENS_RADIUS} 张开到末端半径」，
     * 最外层末端半径就是 {@link #BEAM_END_RADIUS}；取它当判定边界，看到的光和挨的打才是一回事。
     */
    private static double beamRadiusAt(double along) {
        return beamRadiusAt(along, BEAM_LENGTH, BEAM_END_RADIUS);
    }

    /** 同上，但按「这一刻」的长度 / 末端半径算（打拍涌动时两个值都会涨）。 */
    private static double beamRadiusAt(double along, double beamLength, double beamEndRadius) {
        double t = along / beamLength;
        t = t < 0.0D ? 0.0D : (t > 1.0D ? 1.0D : t);
        double radius = BEAM_LENS_RADIUS + (beamEndRadius - BEAM_LENS_RADIUS) * t;
        return Math.max(BEAM_MIN_RADIUS, radius);
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
        // 一秒把整个喇叭口铺开：每刻沿几条射线各推进一段
        int step = SONIC_TICKS - left;
        double f0 = (double) step / SONIC_TICKS;
        double f1 = (double) (step + 1) / SONIC_TICKS;
        Vec3 axis = to.subtract(from);
        if (axis.lengthSqr() < 1.0E-4D) {
            SONIC_LEFT.remove(id);
            return;
        }
        Vec3 direction = axis.normalize();
        int rays = 7;
        for (int r = 0; r < rays; r++) {
            double degrees = (r / (double) (rays - 1) - 0.5D) * SONIC_CONE_HALF_DEGREES * 2.0D;
            double radians = Math.toRadians(degrees);
            double cos = Math.cos(radians);
            double sin = Math.sin(radians);
            Vec3 ray = new Vec3(direction.x * cos - direction.z * sin, direction.y,
                    direction.x * sin + direction.z * cos);
            for (int i = 0; i < 3; i++) {
                double f = f0 + (f1 - f0) * i / 3.0D;
                Vec3 point = from.add(ray.scale(f * SONIC_RANGE));
                level.sendParticles(ParticleTypes.SONIC_BOOM,
                        point.x, point.y, point.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
            }
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

    /**
     * 尖叫结算：**扇形（喇叭口）扩散**，不再是直线。
     *
     * <p>以「戴帽者胸口 -> 目标」为轴，向两侧各张 40°、最远 30 格，范围内的敌人全吃：
     * 定身（缓慢/虚弱/挖掘疲劳 255 + 强制 NoAI）+ **易伤 10 秒**（受到的所有伤害 ×1.5）
     * + 一段伤害（18 + 目标最大生命 5%，封顶 60）。
     */
    private static void resolveSonic(ServerLevel level, LivingEntity wearer, Vec3 from, Vec3 to) {
        DamageSource source = level.damageSources().sonicBoom(wearer);
        Vec3 axis = to.subtract(from);
        if (axis.lengthSqr() < 1.0E-4D) {
            return;
        }
        Vec3 direction = axis.normalize();
        double cosLimit = Math.cos(Math.toRadians(SONIC_CONE_HALF_DEGREES));
        for (LivingEntity target : combatTargets(level, wearer)) {
            Vec3 center = target.getBoundingBox().getCenter();
            Vec3 offset = center.subtract(from);
            double distance = offset.length();
            if (distance < 1.0E-3D || distance > SONIC_RANGE) {
                continue;
            }
            // 落在喇叭口外的不算
            if (offset.scale(1.0D / distance).dot(direction) < cosLimit) {
                continue;
            }
            float damage = Math.min(SONIC_DAMAGE_CAP,
                    SONIC_DAMAGE + (float) (target.getMaxHealth() * SONIC_HEALTH_RATIO));
            target.invulnerableTime = 0;
            // 定身 + 易伤都必须在 hurt 之前挂：hurt 之后目标可能已经被击退 / 打死了
            HatAbilities.applySonicHold(level, target, SONIC_HOLD_TICKS);
            target.addEffect(new MobEffectInstance(HatEffects.VULNERABLE,
                    SONIC_VULNERABLE_TICKS, 0, false, false, true));
            if (target.hurt(source, damage)) {
                target.push(direction.x * 2.5D, 0.5D, direction.z * 2.5D);
            }
            // 被打中的敌人抖 1 秒（服务端改朝向，客户端自动同步）
            SHAKE_LEFT.put(target.getUUID(), SONIC_SHAKE_TICKS);
            // 声波也算戴帽者打的，宠物/小怪会跟着上
            wearer.setLastHurtMob(target);
        }
    }

    /**
     * 开场冲击波的时间点：第 {@code i} 次落在 {@code i / 7 * TEXT_FIRST} 刻。：第 {@code i} 次落在 {@code i / 7 * TEXT_FIRST} 刻。
     *
     * <p>8 次铺满 0 – 13 秒，间隔 37 刻（约 1.85 秒）—— 名义上的「每两秒一次」，
     * 但要求最后一次必须正好是「No」出声那一刻，所以按 7 等分反推。
     */
    private static boolean isShockTick(int age) {
        for (int i = 0; i < SHOCK_COUNT; i++) {
            if (age == Math.round(i * (double) TEXT_FIRST / (SHOCK_COUNT - 1))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 一圈把人掀开：15 格内**所有非友方单位**（玩家除外）以戴帽者为中心向外弹飞。
     *
     * <p>越近推得越狠（线性衰减到边缘只剩一半），带一点向上分量，
     * 顺手停掉寻路 —— 被掀飞的那一下不该还挂着「正走向玩家」这条任务。
     */
    private static void shockwave(ServerLevel level, LivingEntity wearer) {
        Vec3 origin = wearer.position();
        double rangeSq = SHOCK_RADIUS * SHOCK_RADIUS;
        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class,
                wearer.getBoundingBox().inflate(SHOCK_RADIUS))) {
            if (target == wearer || target instanceof net.minecraft.world.entity.player.Player
                    || !target.isAlive() || HatAbilities.isFriendlyTo(wearer, target)
                    || target.distanceToSqr(origin) > rangeSq) {
                continue;
            }
            Vec3 away = new Vec3(target.getX() - origin.x, 0.0D, target.getZ() - origin.z);
            if (away.lengthSqr() < 1.0E-4D) {
                // 正好站在脚下：随机挑个方向，否则推不动
                double angle = level.random.nextDouble() * Math.PI * 2.0D;
                away = new Vec3(Math.cos(angle), 0.0D, Math.sin(angle));
            }
            double falloff = 1.0D - Math.min(1.0D, away.length() / SHOCK_RADIUS) * 0.55D;
            Vec3 push = away.normalize().scale(SHOCK_POWER * falloff);
            target.setDeltaMovement(push.x, SHOCK_LIFT * falloff, push.z);
            target.hurtMarked = true;
            if (target instanceof net.minecraft.world.entity.Mob mob) {
                mob.getNavigation().stop();
            }
        }
        // 弹射物：像「静止火把」那样把飞进来的东西**原速反弹**回去。
        for (net.minecraft.world.entity.projectile.Projectile projectile
                : level.getEntitiesOfClass(net.minecraft.world.entity.projectile.Projectile.class,
                wearer.getBoundingBox().inflate(SHOCK_RADIUS))) {
            if (projectile.distanceToSqr(origin) > rangeSq) {
                continue;
            }
            Vec3 velocity = projectile.getDeltaMovement();
            if (velocity.lengthSqr() < 1.0E-6D) {
                // 停在原地的：直接朝背离中心的方向弹出去
                Vec3 away = new Vec3(projectile.getX() - origin.x, 0.25D, projectile.getZ() - origin.z);
                projectile.setDeltaMovement(away.normalize().scale(1.2D));
            } else {
                projectile.setDeltaMovement(velocity.scale(-1.1D));
            }
            projectile.hurtMarked = true;
        }
        spawnShockRing(level, wearer);
    }

    /** 冲击波的边界提示：半径 15 格处撒一圈白烟，让人一眼看出这一下罩到多远。 */
    private static void spawnShockRing(ServerLevel level, LivingEntity wearer) {
        int points = 40;
        double y = wearer.getY() + 0.3D;
        for (int i = 0; i < points; i++) {
            double angle = Math.PI * 2.0D * i / points;
            level.sendParticles(ParticleTypes.CLOUD,
                    wearer.getX() + Math.cos(angle) * SHOCK_RADIUS, y,
                    wearer.getZ() + Math.sin(angle) * SHOCK_RADIUS,
                    1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    /** 「No too late」那一刻：50 格内的**所有玩家**（敌我，包括戴帽者本人）原地定住。 */
    private static void stasisNearby(ServerLevel level, LivingEntity wearer) {
        stasisNearby(level, wearer, stasisTicks());
    }

    /** 同上，但自己指定钉多久（尖叫后那 0.5 秒空窗用这个）。 */
    private static void stasisNearby(ServerLevel level, LivingEntity wearer, int ticks) {
        long until = level.getServer().getTickCount() + ticks;
        double rangeSq = STASIS_RADIUS * STASIS_RADIUS;
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(wearer) > rangeSq) {
                continue;
            }
            PINNED.put(player.getUUID(), new Pin(player.position(), until));
            pin(player);
        }
        // 弹射物也一起定住：圈里连箭都停在半空（关重力 + 速度清零），
        // 定身结束 / 飞出圈外时在 tickFrozenShots 里把重力还回去。
        for (net.minecraft.world.entity.projectile.Projectile shot : level.getEntitiesOfClass(
                net.minecraft.world.entity.projectile.Projectile.class,
                wearer.getBoundingBox().inflate(STASIS_RADIUS))) {
            if (shot.isRemoved() || shot.distanceToSqr(wearer) > rangeSq) {
                continue;
            }
            FrozenProjectile frozen = FROZEN_SHOTS.get(shot.getUUID());
            if (frozen == null) {
                frozen = new FrozenProjectile(shot, wearer.position(), STASIS_RADIUS, until);
                FROZEN_SHOTS.put(shot.getUUID(), frozen);
            } else {
                frozen.until = Math.max(frozen.until, until);
            }
            shot.setNoGravity(true);
            shot.setDeltaMovement(Vec3.ZERO);
            shot.hurtMarked = true;
        }
    }

    // ------------------------------------------------------------------
    // 弹射物：定身硬停 + 减速圈慢动作
    //
    // 和牛仔帽的「时间差力场」同一套：**关重力**是关键 —— 只要重力还在每刻往下加，
    // 压得越狠、在空中待得越久，越会一头栽下来。关掉之后，硬停的就是停在原地，
    // 慢放的就沿着原来那条直线慢慢飞。离场/到点时把原本有重力的那份还回去。
    // ------------------------------------------------------------------

    /** 圈里被压慢的速度（和牛仔帽力场同一个值）。 */
    private static final double PROJECTILE_SLOW_SPEED = 0.12D;

    /** 被「No too late」硬停住的弹射物。 */
    private static final Map<UUID, FrozenProjectile> FROZEN_SHOTS = new HashMap<>();
    /** 被紫色减速圈压住的弹射物。 */
    private static final Map<UUID, FieldProjectile> FIELD_SHOTS = new HashMap<>();

    private static final class FrozenProjectile {
        private final net.minecraft.world.entity.projectile.Projectile shot;
        private final Vec3 center;
        private final double radiusSq;
        private long until;
        private final boolean hadGravity;

        private FrozenProjectile(net.minecraft.world.entity.projectile.Projectile shot,
                                 Vec3 center, double radius, long until) {
            this.shot = shot;
            this.center = center;
            this.radiusSq = radius * radius;
            this.until = until;
            this.hadGravity = !shot.isNoGravity();
        }
    }

    private static final class FieldProjectile {
        private final net.minecraft.world.entity.projectile.Projectile shot;
        private final boolean hadGravity;
        private long lastSeen;

        private FieldProjectile(net.minecraft.world.entity.projectile.Projectile shot, long now) {
            this.shot = shot;
            this.hadGravity = !shot.isNoGravity();
            this.lastSeen = now;
        }
    }

    /** 紫色圈里的弹射物：关重力 + 速度压到很低（方向不变），和牛仔帽力场一致。 */
    private static void slowShotsInRing(ServerLevel level, LivingEntity wearer, double radius) {
        long now = level.getServer().getTickCount();
        double radiusSq = radius * radius;
        for (net.minecraft.world.entity.projectile.Projectile shot : level.getEntitiesOfClass(
                net.minecraft.world.entity.projectile.Projectile.class,
                wearer.getBoundingBox().inflate(radius))) {
            if (shot.isRemoved() || shot.distanceToSqr(wearer) > radiusSq) {
                continue;
            }
            // 自己人射出去的箭不压（否则等于把队友的输出一起削了）
            net.minecraft.world.entity.Entity shooter = shot.getOwner();
            if (shooter != null && HatAbilities.isFriendlyTo(wearer, shooter)) {
                continue;
            }
            FieldProjectile field = FIELD_SHOTS.get(shot.getUUID());
            if (field == null) {
                field = new FieldProjectile(shot, now);
                FIELD_SHOTS.put(shot.getUUID(), field);
            }
            field.lastSeen = now;
            if (!shot.isNoGravity()) {
                shot.setNoGravity(true);
            }
            Vec3 velocity = shot.getDeltaMovement();
            double speed = velocity.length();
            if (speed > 1.0E-6D && speed > PROJECTILE_SLOW_SPEED) {
                shot.setDeltaMovement(velocity.scale(PROJECTILE_SLOW_SPEED / speed));
                shot.hurtMarked = true;
            }
        }
    }

    /** 每刻维护这些弹射物：到点 / 离圈 / 被移除就把重力还回去，还在圈里的接着按住。 */
    private static void tickFrozenShots(ServerLevel level) {
        long now = level.getServer().getTickCount();
        java.util.Iterator<Map.Entry<UUID, FrozenProjectile>> frozenIt = FROZEN_SHOTS.entrySet().iterator();
        while (frozenIt.hasNext()) {
            FrozenProjectile frozen = frozenIt.next().getValue();
            net.minecraft.world.entity.projectile.Projectile shot = frozen.shot;
            boolean gone = shot.isRemoved() || !shot.isAlive();
            boolean escaped = shot.position().distanceToSqr(frozen.center) > frozen.radiusSq;
            if (gone || escaped || now > frozen.until) {
                if (!gone && frozen.hadGravity && !FIELD_SHOTS.containsKey(shot.getUUID())) {
                    shot.setNoGravity(false);
                }
                frozenIt.remove();
                continue;
            }
            shot.setDeltaMovement(Vec3.ZERO);
            shot.hurtMarked = true;
        }

        java.util.Iterator<Map.Entry<UUID, FieldProjectile>> fieldIt = FIELD_SHOTS.entrySet().iterator();
        while (fieldIt.hasNext()) {
            FieldProjectile field = fieldIt.next().getValue();
            net.minecraft.world.entity.projectile.Projectile shot = field.shot;
            boolean gone = shot.isRemoved() || !shot.isAlive();
            // 两刻没被圈扫到：已经离圈 / 圈收起 / 曲子结束 —— 还重力
            if (gone || now - field.lastSeen > 2) {
                if (!gone && field.hadGravity && !FROZEN_SHOTS.containsKey(shot.getUUID())) {
                    shot.setNoGravity(false);
                }
                fieldIt.remove();
                continue;
            }
            Vec3 velocity = shot.getDeltaMovement();
            double speed = velocity.length();
            if (speed > 1.0E-6D && speed > PROJECTILE_SLOW_SPEED) {
                shot.setDeltaMovement(velocity.scale(PROJECTILE_SLOW_SPEED / speed));
                shot.hurtMarked = true;
            }
        }
    }

    /** 一轮结束 / 摘帽：把还扣着的重力全还回去（否则箭会永远悬着）。 */
    private static void releaseAllShots() {
        for (FrozenProjectile frozen : FROZEN_SHOTS.values()) {
            if (frozen.hadGravity && !frozen.shot.isRemoved()) {
                frozen.shot.setNoGravity(false);
            }
        }
        for (FieldProjectile field : FIELD_SHOTS.values()) {
            if (field.hadGravity && !field.shot.isRemoved()) {
                field.shot.setNoGravity(false);
            }
        }
        FROZEN_SHOTS.clear();
        FIELD_SHOTS.clear();
    }

    /** 钉住一个人的单刻动作：速度清零 + 缓慢 255（客户端据此也不会再走）。 */
    private static void pin(ServerPlayer player) {
        player.setDeltaMovement(Vec3.ZERO);
        player.hurtMarked = true;
        player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 10, 255, false, false, false));
    }

    /** 每刻维护被钉住的人：偏出原位就拉回来，到点解除。 */
    private static void tickPins(ServerLevel level) {
        if (PINNED.isEmpty()) {
            return;
        }
        long now = level.getServer().getTickCount();
        java.util.Iterator<Map.Entry<UUID, Pin>> iterator = PINNED.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Pin> entry = iterator.next();
            Pin pinned = entry.getValue();
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null || player.isDeadOrDying() || now >= pinned.until) {
                if (player != null) {
                    MobEffectInstance slow = player.getEffect(MobEffects.MOVEMENT_SLOWDOWN);
                    if (slow != null && slow.getAmplifier() == 255) {
                        player.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
                    }
                }
                iterator.remove();
                continue;
            }
            // 跳、被推、坐船都不许离开原地：偏出 5 厘米就拉回去
            if (player.position().distanceToSqr(pinned.pos) > 0.0025D) {
                player.teleportTo(pinned.pos.x, pinned.pos.y, pinned.pos.z);
                player.setDeltaMovement(Vec3.ZERO);
            }
            player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 10, 255, false, false, false));
        }
    }

    /**
     * 让被尖叫打中的敌人**发抖**：每刻给朝向加一点随机偏移。
     *
     * <p>只改朝向、不改坐标 —— 位置一动就会被客户端插值扯来扯去，还会挤进方块；
     * 朝向是每刻同步的，所以看上去就是原地哆嗦。
     */
    private static void tickShakes(ServerLevel level) {
        if (SHAKE_LEFT.isEmpty()) {
            return;
        }
        java.util.Iterator<Map.Entry<UUID, Integer>> iterator = SHAKE_LEFT.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Integer> entry = iterator.next();
            int left = entry.getValue() - 1;
            Entity entity = level.getEntity(entry.getKey());
            if (!(entity instanceof LivingEntity living) || living.isRemoved() || left <= 0) {
                iterator.remove();
                continue;
            }
            float jitter = (level.random.nextFloat() - 0.5F) * 18.0F;
            living.setYRot(living.getYRot() + jitter);
            living.yRotO = living.getYRot();
            living.yBodyRotO = living.yBodyRot;
            living.yBodyRot += jitter;
            living.yHeadRotO = living.yHeadRot;
            living.yHeadRot += jitter;
            entry.setValue(left);
        }
    }

    /** 尖叫之后那 0.3 秒：光束收起来，把焦点让给这一声。 */
    private static boolean inSonicPause(int age) {
        return age > SONIC_AT && age <= SONIC_AT + SONIC_BEAM_PAUSE;
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
