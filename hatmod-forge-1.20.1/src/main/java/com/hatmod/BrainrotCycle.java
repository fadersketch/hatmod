package com.hatmod;

import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
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
 *  43.2s        额外放一次监守者同款声波（无音效，动画 1 秒）
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
    public static final int CYCLE_TICKS = BrainrotBeats.main(2114);

    /** 0 秒到 12 秒：黑暗。 */
    private static final int DARKNESS_END = BrainrotBeats.main(12 * 20);
    /** 13 秒：第一次「No too late」。 */
    /** 260 刻（13 秒）：三个词 260 / 269 / 278，和 READY / SET / GO 同刻。 */
    private static final int TEXT_FIRST = BrainrotBeats.main(260);
    /** 14 秒：开始自转、迅捷 I、减速圈开始扩大。 */
    private static final int SPIN_START = BrainrotBeats.main(14 * 20);
    /**
     * 29.2 秒（584 刻）：减速圈到最大半径，开始照射。
     *
     * <p>比原曲位置（570）晚 14 刻 = 0.7 秒：光束和「和它一起开始的那些东西」（速度 III、
     * 减速圈长满、Teto 加速/半径呼吸、谱面加密提速）一起后移。**结束时间不变**
     * （还是 {@link #PAUSE_START}），也就是这一段整体短了 0.7 秒。
     */
    private static final int FIRE_START = BrainrotBeats.main(574);
    /**
     * 43.2 秒（864 刻）：声波 —— 原曲里那一「声」。
     *
     * <p>原曲 64.15–64.45 秒先把乐器整个抽掉（全曲只有两处这样的硬停，另一处就在
     * 「Nope too late」前面），紧接着一层从弱到强的高频尖啸，64.74 到顶，65.05 接上人声。
     * 856 刻 = 42.8 秒处那一声 —— 声波和随之而来的定身、黑白、锁视线都从这里算。
     *
     * <p>实测下来整条链路比听到的声音晚约 0.3 秒（6 刻），所以从 862 提前到 856。
     */
    private static final int SONIC_AT = BrainrotBeats.main(856);

    /**
     * 声波要踩中的那一拍：离 {@link #SONIC_AT} 最近的谱面键 —— 1287 刻，正好是一根长音的**头拍**。
     *
     * <p>声波不再自己响：得由玩家在打拍里踩中这一拍才放出来。长音只认头拍那一下，
     * 头拍没踩上就一直没有声波（按住的过程不补触发）。
     */
    private static final int SONIC_NOTE = BrainrotBeats.tickAt(BrainrotBeats.nearest(SONIC_AT, new int[1]));

    /** 这一轮声波放过了没（一轮最多一次）。 */
    private static final Set<UUID> SONIC_FIRED = new HashSet<>();
    /** 57 秒：停火 + 漂浮 + 黑暗。 */
    private static final int PAUSE_START = BrainrotBeats.main(57 * 20);
    /** 70 秒：第二次「No too late」。 */
    /** 1398 刻：同理，1398 / 1407 / 1416（原曲 91.26 / 91.70 / 92.16 秒）。 */
    private static final int TEXT_SECOND = BrainrotBeats.main(1398);
    /** 71 秒：恢复自转与照射（3 道），缓降 13 秒。 */
    private static final int FIRE2_START = BrainrotBeats.main(1420);
    /** 84 秒：缓降结束（71 + 13）。 */
    private static final int SLOW_FALL_END = BrainrotBeats.main(1680);
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
    private static final int SLOW_DARK_AT = BrainrotBeats.main(100 * 20);

    /** 收尾黑暗段给自己多久的完整神隐（刻）：3 秒。 */
    private static final int ENDING_VEIL_TICKS = 60;

    /** 减速圈最大半径。 */
    private static final double RING_MAX_RADIUS = 10.0D;
    /** 照射范围（格）：「脑蚀」的光柱比普通光柱长得多，取敌人也放到这么远。 */
    private static final double FIRE_RANGE = 40.0D;
    /** 声波基础伤害。 */
    private static final float SONIC_DAMAGE = 18.0F;
    /** 再按目标最大生命的这个比例加一段。 */
    private static final float SONIC_HEALTH_RATIO = 0.05F;
    /** 单次声波伤害上限。 */
    private static final float SONIC_DAMAGE_CAP = 60.0F;
    /** 扇形扩散的半边张角（度）：一共 100° 的喇叭口（原来 80°）。 */
    private static final double SONIC_CONE_HALF_DEGREES = 50.0D;
    /** 扇形最远距离（格）：45 格 —— 比原来 30 格明显更大，视觉和判定一起放大。 */
    private static final double SONIC_RANGE = 45.0D;
    /**
     * 尖叫前那一段静默的起点：跟着 {@link #SONIC_AT} 一起提前 6 刻（851 刻）。
     *
     * <p>音频从这一刻开始压到 -26…-29 dB，一直到那一声落下。旧代码是在
     * {@code SONIC_AT} 之后才收光束，等于安静段已经过去才停手 —— 这就是「卡点对不上」的来源。
     */
    private static final int SONIC_PAUSE_START = BrainrotBeats.main(851);

    /** 静默段长度 = 那一声落下之前（851 → 856，5 刻）。 */
    private static final int SONIC_BEAM_PAUSE = SONIC_AT - SONIC_PAUSE_START;
    /** 声波命中的「易伤」持续多久（刻）：10 秒。 */
    private static final int SONIC_VULNERABLE_TICKS = 200;
    /**
     * 尖叫那一刻画面转**黑白**多久（刻）：一瞬（6 刻 = 0.3 秒），就是那一声的闪光。
     *
     * <p>原来是挂「黑暗」，看不见路太伤；现在换成黑帽「预知」同款的黑白后处理，
     * 威力一样是"世界忽然褪色"，但路还看得见。和缓慢同刻开始。
     */
    private static final int SONIC_MONO_TICKS = 6;

    /** 被尖叫打中的敌人抖多久（刻）：1 秒。 */
    private static final int SONIC_SHAKE_TICKS = 20;
    /**
     * 声波带控多久（刻）：**40 秒**定身（原来是 20 秒）。
     *
     * <p>声波特效改成和那一声完全同刻之后，画面不再有「滚出去」的 1 秒预告，
     * 作为补偿把控制翻倍。
     */
    private static final int SONIC_HOLD_TICKS = 800;
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
     * 第二次「No too late」默认钉多久（刻）：25 刻（1.25 秒）。
     *
     * <p>第一次那一句不走这个默认值 —— 它按站定整段算（{@code STILL_RELEASE - STILL_START}），
     * 比动作还原再早 0.2 秒撒手，也不会把定身拖进 1420 刻开始的光束段。
     *
     * <p>写成方法而不是常量：那几个常量在文件后面才声明，字段初始化顺序会让这里的值变成 0。
     */
    private static int stasisTicks() {
        return TEXT_FREEZE_LEAD + TEXT_TICKS + FOCUS_EXTRA_TICKS;
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

    /**
     * 「Nope, too late」和那一声尖叫期间的**升级版硬控**：圈里的生物也一起原地钉死。
     *
     * <p>玩家那条线走 {@link #PINNED}（客户端权威，只能靠 teleport 拉回来，见 {@link #tickPins}）；
     * 生物没有客户端权威，所以除了 {@link Mob#setNoAi} 之外再每刻 {@code setPos} 回原位 ——
     * 击退、坐骑、末地珍珠、别的模组推一把，全都挪不动一寸。
     */
    private static final Map<UUID, Pin> PINNED_LIVING = new HashMap<>();

    /** 这只生物是不是正被「定身」钉在原地（给 {@code /hatmod unfreeze} 用）。 */
    static boolean isPinned(LivingEntity living) {
        return PINNED_LIVING.containsKey(living.getUUID());
    }

    /** 戴帽者 -> 这一轮里**有没有见过** Teto（用来判断「全被打死了」）。 */
    private static final Map<UUID, Boolean> TETO_SEEN = new HashMap<>();

    /** 戴帽者 -> 本轮已经走了多少刻。**键存在** = 这一轮正在进行。 */
    private static final Map<UUID, Integer> AGES = new HashMap<>();
    /** 戴帽者 -> 上一次发给客户端的自转状态，用来去重。 */
    private static final Map<UUID, Boolean> SPIN_SENT = new HashMap<>();
    /** 这一轮是不是 P 键强制起手的（只有这种轮次才绕过对被动生物的攻击保护）。 */
    private static final Set<UUID> FORCED = new HashSet<>();
    /** 戴帽者 -> 上一次发给客户端的光柱道数 / 目标列表（去重）。 */
    private static final Map<UUID, int[]> BEAM_IDS = new HashMap<>();
    private static final Map<UUID, Integer> BEAM_COUNT = new HashMap<>();

    /** 刚进游戏 / 重进的玩家：这段时间内先不启动链路，等客户端世界和声音都准备好。 */
    private static final Map<UUID, Long> JOIN_GRACE = new HashMap<>();
    private static final int REJOIN_GRACE_TICKS = 40;

    /** 被尖叫打中、正在发抖的敌人 -> 还剩几刻。 */
    private static final Map<UUID, Integer> SHAKE_LEFT = new HashMap<>();
    /** 声波：剩余刻数、起点、终点。 */

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
            tickPlayerOnce(player);
        }
    }

    /**
     * 玩家每刻的去重：{@code PlayerTickEvent} / {@code LivingTickEvent} 两个入口共用。
     *
     * <p>键用世界刻号：LivingTickEvent 在 {@code LivingEntity.tick()} 开头发，那时
     * {@code tickCount} 还没自增，拿它比会让同一刻跑两遍。
     */
    private static final Map<UUID, Long> PLAYER_TICK = new HashMap<>();

    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        LivingEntity living = event.getEntity();
        if (!(living.level() instanceof ServerLevel level)) {
            return;
        }
        // 玩家走 PlayerTickEvent.END，这条入口只管其它生物
        if (living instanceof ServerPlayer) {
            return;
        }
        tick(living, level);
    }

    private static void tickPlayerOnce(ServerPlayer player) {
        long age = player.level().getGameTime();
        Long last = PLAYER_TICK.get(player.getUUID());
        if (last != null && last == age) {
            return;
        }
        PLAYER_TICK.put(player.getUUID(), age);
        if (PLAYER_TICK.size() > 64) {
            PLAYER_TICK.clear();
        }
        tick(player, player.serverLevel());
        // 「不请自来」：召唤够多次数的玩家，身边没有 Teto 时她可能自己找上来
        TetoVisit.tick(player);
    }

    /**
     * 进服时把落盘的「自动打拍」开关恢复，并告诉客户端（调参器按钮要显示成开着）。
     *
     * <p>服务端那一份在 {@link BrainrotQte#autoTapping} 里也会按需从配置恢复，
     * 这里主要是让界面跟上；顺便把内存里的集合立起来。
     */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        // 下线时链路状态（AGES）没有收掉：重进后会接着旧进度跑，但音乐不会重新开始，
        // 表现就是音画不同步（摘帽子再戴会从头重开，所以那样能修好）。
        // 这里按「重新进服」收掉，让他下一次靠近敌人时音乐和谱面一起从 0 开始。
        if (AGES.containsKey(player.getUUID())) {
            end(player.serverLevel(), player, "重新进服");
        }
        // 客户端重进之后没有那条 BGM 声道了：把「在响」的记录忘掉，
        // 不然下一轮 start 时会被去重逻辑当成「已经在响」，曲子就再也不播。
        HatMusic.forgetBrainrot(player.getUUID());
        JOIN_GRACE.put(player.getUUID(),
                player.serverLevel().getGameTime() + REJOIN_GRACE_TICKS);
        if (HatSettings.debugAutoTap(player.getUUID())) {
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

        // 这顶帽子只认两种戴帽者：玩家和 Khragg。
        // 别的生物（promaid 女仆、捡了帽子的僵尸、别的模组塞过来的生物……）
        // 戴着它只是装饰：不进脑蚀链路、不召唤 Teto、也不放这首曲子。
        // 以前它们会照跑整条链路 —— 那就是「一只女仆把 Teto 和 BGM 全叫出来了」。
        if (!isAllowedWearer(wearer)) {
            if (stored != null) {
                end(level, wearer, "戴帽者不是玩家/Khragg");
            }
            return;
        }

        if (!wearsBrainrot(wearer) || wearer.isDeadOrDying() || wearer.isSpectator()) {
            if (!wearsBrainrot(wearer)) {
                // 帽子不在身上（摘了 / 换帽 / 从饰品栏拿走）：曲子一定停。
                // 只发一次，代价可以忽略（见 HatMusic#stopBrainrot）。
                HatMusic.stopBrainrot(level, wearer);
            }
            if (stored != null) {
                String why = !wearsBrainrot(wearer) ? "摘帽/换帽"
                        : wearer.isDeadOrDying() ? "戴帽者死亡" : "戴帽者进入观战";
                if (!wearsBrainrot(wearer) && !wearer.isDeadOrDying() && !wearer.isSpectator()) {
                    // 帽子没了、人还在：自己摘的 / 被敌人磨穿的，两套不满各说各的。
                    // 先说话再收场 —— end() 会把她一起清掉，之后就没机会开口了。
                    sayHatOff(level, wearer);
                }
                end(level, wearer, why);
            }
            return;
        }

        if (stored == null) {
            // （重）进游戏后的前 2 秒先不启动：等客户端世界、实体、声音都准备好。
            // 否则音乐包可能先到 / 世界还没建好，谱面钟先走了，音画就不同步。
            Long readyAt = JOIN_GRACE.get(id);
            if (readyAt != null) {
                if (level.getGameTime() < readyAt) {
                    return;
                }
                JOIN_GRACE.remove(id);
            }
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
            // 打拍：把过窗没按的拍记成 MISS、按连击续 buff
            BrainrotQte.tick(level, wearer, at);
            // 调参器里的「自动打拍」：开着就由服务端替他踩拍（这一轮不发成就）
            if (wearer instanceof ServerPlayer autoPlayer && BrainrotQte.autoTapping(wearer)) {
                BrainrotQte.autoTap(autoPlayer, at);
            } else if (wearer instanceof Khragg khragg && khragg.autoBeat()) {
                // Khragg 复活返场：节拍由服务端代打，全程满连击
                BrainrotQte.autoTapMob(khragg, at);
            }
            if (at + 1 >= CYCLE_TICKS) {
                // 整首放完才判「节奏大师」；中途摘帽 / 死亡 / Teto 团灭都不算
                BrainrotQte.finishRound(wearer);
                if (wearer instanceof ServerPlayer player) {
                    // 她记得有人陪她把一整首听完了
                    HatSettings.rememberTeto(player.getUUID(), TetoSpeech.CHAIN_END);
                    TetoMood.adjust(player.getUUID(), 6, level.getGameTime());
                }
                end(level, wearer, "整首放完");
                return;
            }
        }
        AGES.put(id, age + steps);

        // ---- 下面这些和谱面无关：一个服务器刻做一次就够 ----
        unwebNearby(level, wearer);
        tickShakes(level);
        tickPins(level);
        tickPinnedLiving(level);
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
            end(level, wearer, "Teto 全部阵亡，链路中断（非破防模式）");
            // 断链后 3 秒静默期：不然附近还站着敌人，下一刻立刻又起一轮，看着像根本没断
            TETO_BREAK_UNTIL.put(wearer.getUUID(), level.getGameTime() + TETO_RESTART_LOCK);
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
    /** 「一只 Teto 都数不到」连续出现了几次。 */
    private static final Map<UUID, Integer> TETO_MISSING = new HashMap<>();
    /** 这一轮里补过几次 Teto（上限 {@link #TETO_MAX_RESPAWNS}）。 */
    private static final Map<UUID, Integer> TETO_RESPAWNS = new HashMap<>();
    /** 一轮最多补这么多次，防止有人每刻清一次把服务器拖死。 */
    private static final int TETO_MAX_RESPAWNS = 20;
    /** 连空这么多次（每次间隔 10 刻）才认团灭：给拉回轮询留出窗口。 */
    private static final int TETO_MISSING_CHECKS = 3;
    /** Teto 阵亡断链后的静默期（刻）：3 秒内不许重新起手。 */
    private static final int TETO_RESTART_LOCK = 60;
    /** 谁还在断链静默期里（值 = 解禁的游戏刻）。 */
    private static final Map<UUID, Long> TETO_BREAK_UNTIL = new HashMap<>();
    /** 这一轮已经放过同伴（唐嘉琦）的人。 */
    private static final java.util.Set<UUID> TANG_SPAWNED = new java.util.HashSet<>();
    /** 同伴（唐嘉琦）总开关：**雪藏中**，改 true 就恢复「正片开始时召唤一只」。 */
    private static final boolean TANG_ENABLED = false;

    private static boolean checkTetos(ServerLevel level, LivingEntity wearer) {
        UUID id = wearer.getUUID();
        // 数「名下还有几只」走 Teto 自己的 UUID 名册（跨维度、不经过实体查询）。
        // 以前用 getEntitiesOfClass + getEntities().getAll()：实体管理器被模组换掉之后
        // 两条路都可能是空的，于是链路以为团灭、一直补人。
        // 坐着的**不算**：她这一轮不上场，不能因为她还在就认为「场上还有 Teto」。
        // 「还在转的」= 本轮召唤出来的、没留守的、还活着的。
        // 留守的那只不算，散养的也不算 —— 她们都不参与这一轮。
        int alive = 0;
        for (Teto teto : Teto.ownedBy(wearer)) {
            if (teto.isSummoned() && !teto.isSitting() && !teto.isDeadOrDying()) {
                alive++;
            }
        }
        // 诊断用：128 格里「不管属于谁」还剩几只（只进日志，不参与判断）
        int anyNearby = level.getEntitiesOfClass(Teto.class,
                wearer.getBoundingBox().inflate(128.0D)).size();
        if (alive > 0) {
            TETO_SEEN.put(id, Boolean.TRUE);
            TETO_MISSING.remove(id);
            // 有活着的时候把「补人额度」还回去：额度是防死循环的，不是一轮的总量限制。
            // 之前被外部一遍遍抹掉，20 次额度用完就再也不补了，看着像「禁止生成」。
            TETO_RESPAWNS.remove(id);
            return false;
        }
        if (!Boolean.TRUE.equals(TETO_SEEN.get(id))) {
            return false;                       // 还没放出来过，不算「没了」
        }
        if (Teto.hasSitting(wearer)) {
            return false;               // 她留守了：这一轮本来就没有 Teto，不是「被打光了」
        }
        // 非破防模式：会转的那几只**全部**死了 = 这一轮到此为止（调用方负责收场 + 3 秒静默期）。
        // 破防模式不认这条：她本来也死不掉，就算被绕过去也只补新的，链路不中断。
        if (!HatSettings.forceBreak()) {
            HatMod.LOGGER.warn("[hatmod] 脑蚀链路：本轮 Teto 全部阵亡（非破防模式），中断这一轮"
                            + "（128 格内共 {} 只 Teto）", anyNearby);
            return true;
        }
        // 破防模式：不中断链路，被清掉就**补一只**回来（一只就够把这一轮续上）
        int respawns = TETO_RESPAWNS.getOrDefault(id, 0);
        if (respawns >= TETO_MAX_RESPAWNS) {
            return false;
        }
        TETO_RESPAWNS.put(id, respawns + 1);
        HatMod.LOGGER.warn("[hatmod] 脑蚀链路：Teto 被清空，第 {} 次补一只回来"
                        + "（链路不重跑；128 格内共 {} 只 Teto，属于本人的 {} 只）",
                respawns + 1, anyNearby, alive);
        HatSummons.spawnOneTeto(level, wearer);
        // 她说一句「重来」，和开场白分开（一局里可能补好几次）；
        // 3 秒冷却只是防刷屏，不是「不播」—— 每次真的补回来都会说。
        if (wearer instanceof ServerPlayer) {
            TetoTalk.sayCooled(level, wearer, 48.0D, "resing", 60, TetoSpeech.pick(
                    level.random, TetoSpeech.summonsOf(wearer.getUUID()),
                    "hatmod.teto.resing", 2, 1));
        }
        return false;
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

    /**
     * 链路期间帽子没了：她不高兴。
     *
     * <p>两种原因分开说：玩家自己摘 / 换帽（{@code hatoff.self}），
     * 或者被敌人打到耐久磨穿（{@code hatoff.broken}，见 {@code HatSettings#hatBrokeRecently}）。
     * 词按见面次数分层（第一次 / 第二次 / 第三次往后）。
     */
    private static void sayHatOff(ServerLevel level, LivingEntity wearer) {
        if (!(wearer instanceof ServerPlayer player)) {
            return;                     // 生物戴帽子不带台词（和别的触发点一致）
        }
        boolean broken = HatSettings.hatBrokeRecently(player.getUUID(), level.getGameTime());
        TetoTalk.sayTo(player, TetoSpeech.pick(level.random,
                TetoSpeech.summonsOf(player.getUUID()),
                broken ? "hatmod.teto.hatoff.broken" : "hatmod.teto.hatoff.self", 3, 2));
    }

    /**
     * 能不能启动「脑蚀」链路：只有玩家和 Khragg。
     *
     * <p>别的生物戴着这顶帽子时**只是戴着**：不召唤 Teto、不放 BGM、不接管。
     * 刷怪蛋 / 指令直接放出来的 Teto 是另一回事 —— 那是实体本身，不是帽子召唤的。
     */
    private static boolean isAllowedWearer(LivingEntity wearer) {
        return wearer instanceof ServerPlayer || wearer instanceof Khragg;
    }

    private static boolean wearsBrainrot(LivingEntity living) {
        return HatAbilities.hatStack(living).getItem() == HatItems.BRAIN_HAT.get();
    }

    private static void start(ServerLevel level, LivingEntity wearer) {
        start(level, wearer, "附近有敌人", false);
    }

    private static void start(ServerLevel level, LivingEntity wearer, String why) {
        start(level, wearer, why, false);
    }

    private static void start(ServerLevel level, LivingEntity wearer, String why, boolean forced) {
        Long lockUntil = TETO_BREAK_UNTIL.get(wearer.getUUID());
        if (lockUntil != null) {
            if (level.getGameTime() < lockUntil) {
                return;                 // 刚被 Teto 阵亡打断过：3 秒静默期，谁也不许起手
            }
            TETO_BREAK_UNTIL.remove(wearer.getUUID());
        }
        HatMod.LOGGER.warn("[hatmod] 脑蚀链路：起手（{}）", why);
        // 神隐不在这里给：起手那一刻附近还没几个怪，给了基本白给。
        // 改到第一句歌词唱完时（见 applyTimeline），结束时间不变，还是「Nope, too late」那一刻。
        HatMusic.playBrainrot(level, wearer);
        SPIN_SENT.put(wearer.getUUID(), false);
        // 这一轮的后室 Teto：曲子结束或摘帽时会自己发现循环没了，这里只是把它们放出来
        // 附近有散养的 Teto 就直接收编，收编不了才重新召唤。
        // 名下有坐着的（玩家让她原地待命）：这一轮她完全不上场 —— 不收编、不放新的、
        // 也不说开场白（开场白是「她来了」的意思，她没来就别替她说话）。
        if (Teto.hasSitting(wearer)) {
            HatMod.LOGGER.info("[hatmod] 脑蚀链路：{} 名下有一只坐着的 Teto，这一轮不放新的",
                    wearer.getName().getString());
        } else {
            boolean adopted = HatSummons.adoptFreeTeto(level, wearer);
            if (!adopted) {
                // 真放进了世界才算一次**成功召唤**（放失败、被守卫挡下都不算），
                // 台词按这个次数分层（见 TetoSpeech）。
                if (HatSummons.spawnTetos(level, wearer) > 0
                        && wearer instanceof ServerPlayer player) {
                    HatSettings.addTetoSummon(player.getUUID(), level.getGameTime());
                }
            }
            // 开场白**每一轮都要说**：以前收编成功就整段跳过（`else if`），
            // 而曲终必留一只、下一轮必然走收编 —— 结果从第二轮起再也听不到开场白。
            sayOpening(level, wearer, forced, adopted);
        }
        if (wearer instanceof ServerPlayer player && HatSettings.forceBreak()) {
            // 她见过你开破防模式 —— 这个记忆会让她在闲聊时提一句
            HatSettings.rememberTeto(player.getUUID(), TetoSpeech.BREAK_MODE);
        }
        // 开歌：所有 Teto 的动作强制回到原来那套 —— 曲中的动作必须和谱面对齐，
        // 玩家自己切过的在这里统一作废（曲中也不让切，见 TetoAction）。
        for (Teto teto : Teto.ownedBy(wearer)) {
            teto.setActionIndex(Teto.ACTION_ORIGINAL);
            // 每一轮起手都把血补满：上一轮被打掉的伤不该带进这一轮
            teto.setHealth(teto.getMaxHealth());
        }
        SONIC_FIRED.remove(wearer.getUUID());
    }

    /** 开场白每池几条（和语言文件里的 {@code hatmod.teto.start.natural.N} / {@code .forced.N} 对应）。 */
    private static final int OPENING_LINES = 6;

    /** 第二次 / 第三次往后那两套开场白各几条。 */
    private static final int OPENING_LINES_LATER = 3;

    /**
     * Teto 的开场白：接敌起手和 P 键手动起手**各说各的池子**（各六选一，随机）。
     *
     * <p>走聊天栏而不是屏幕大字 —— 开场那一刻画面已经被黑暗和倒计时占满，
     * 再压一行字只会打架。曲终评价同理，都让她「开口说话」。
     */
    private static void sayOpening(ServerLevel level, LivingEntity wearer, boolean forced) {
        sayOpening(level, wearer, forced, false);
    }

    /**
     * @param adopted 这一轮是「就地收编」上一轮留下的那只（不是新放出来的）
     */
    private static void sayOpening(ServerLevel level, LivingEntity wearer, boolean forced,
                                   boolean adopted) {
        // 戴帽子的**不是玩家**（僵尸、别模组塞过来的生物）：这一轮没有台词。
        // 链路本身照跑（光柱、Teto、BGM 都有），只是她不替一只怪说话。
        if (!(wearer instanceof ServerPlayer player)) {
            return;
        }
        // 她记得挨过打：有四成机会换成另一组开场白
        if (HatSettings.tetoHitFresh(player.getUUID(), level.getGameTime())
                && level.random.nextDouble() < 0.4D) {
            TetoTalk.sayNear(level, wearer, 48.0D,
                    TetoTalk.pick(level.random, "hatmod.teto.start.hit", 2));
            return;
        }
        // 这一轮是她「又被叫上场」：三成机会换成收编那组话，其余照旧走开场白
        if (adopted && level.random.nextFloat() < 0.35F) {
            TetoTalk.sayNear(level, wearer, 48.0D, TetoSpeech.pick(level.random,
                    TetoSpeech.summonsOf(player.getUUID()), "hatmod.teto.adopt", 2, 1));
            return;
        }
        String base = forced ? "hatmod.teto.start.forced" : "hatmod.teto.start.natural";
        // 开场白也分层：第一次 / 第二次 / 第三次往后各是一套（见 TetoSpeech#pick）
        TetoTalk.sayNear(level, wearer, 48.0D, TetoSpeech.pick(level.random,
                TetoSpeech.summonsOf(player.getUUID()), base, OPENING_LINES, OPENING_LINES_LATER));
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
        if (!isAllowedWearer(wearer)) {
            return false;               // 白名单外的生物：P 键也开不起来
        }
        if (!wearsBrainrot(wearer) || wearer.isDeadOrDying() || wearer.isSpectator()) {
            return false;
        }
        if (AGES.containsKey(wearer.getUUID())) {
            return false;
        }
        Long lockUntil = TETO_BREAK_UNTIL.get(wearer.getUUID());
        if (lockUntil != null && level.getGameTime() < lockUntil) {
            return false;               // 断链静默期里 P 键也不许起手
        }
        start(level, wearer, "P 键强行开", true);
        BrainrotQte.beginRound(wearer, false);
        AGES.put(wearer.getUUID(), 0);
        // 记下「这一轮是 P 键强行开起来的」：这期间才允许打被动 / 无害生物
        FORCED.add(wearer.getUUID());
        return true;
    }

    /** 曲终 / 摘帽 / 死亡：把这一轮留下的东西全部收掉。 */
    private static void end(ServerLevel level, LivingEntity wearer) {
        end(level, wearer, "未说明");
    }

    /**
     * 玩家自己拿脑蚀把她叫走（见 {@code TetoDismiss}）：立刻收场。
     *
     * <p>不走「Teto 全部阵亡」那条（要等 3 × 10 刻才判定），也不留那一只散养的 ——
     * 玩家明确说了要她走。静默期照旧给：不然下一刻附近还有敌人，链路马上又开一轮。
     */
    static void dismissByPlayer(ServerLevel level, LivingEntity wearer) {
        if (!AGES.containsKey(wearer.getUUID())) {
            return;
        }
        end(level, wearer, "玩家拿脑蚀叫她走");
        TETO_BREAK_UNTIL.put(wearer.getUUID(), level.getGameTime() + TETO_RESTART_LOCK);
    }

    /**
     * 收场，并记下**是谁**结束的这一轮。
     *
     * <p>「重唱」到底是曲终正常重开，还是 {@code checkTetos} 判了团灭、还是摘帽 / 死亡 ——
     * 以前这三种在日志里长得一模一样，只有把原因写出来才追得下去。
     */
    private static void end(ServerLevel level, LivingEntity wearer, String why) {
        UUID id = wearer.getUUID();
        Integer age = AGES.get(id);
        if (HatMod.LOGGER.isWarnEnabled()) {
            HatMod.LOGGER.warn("[hatmod] 脑蚀链路：收场（{}，age={}，本轮补过 Teto {} 次）",
                    why, age == null ? "?" : age,
                    TETO_RESPAWNS.getOrDefault(id, 0));
        }
        AGES.remove(id);
        STEP_NANOS.remove(id);
        STEP_CARRY.remove(id);
        SPIN_SENT.remove(id);
        FORCED.remove(id);
        BEAM_IDS.remove(id);
        BEAM_COUNT.remove(id);
        TETO_SEEN.remove(id);
        TETO_MISSING.remove(id);
        TETO_RESPAWNS.remove(id);
        TANG_SPAWNED.remove(id);
        // 静默期过期的条目顺手清掉（还在期内的留着：断链那一刻是「先 end 再上锁」）
        TETO_BREAK_UNTIL.entrySet().removeIf(entry -> entry.getValue() <= level.getGameTime());
        SONIC_FIRED.remove(id);
        BrainrotQte.clear(wearer);
        // 曲终/摘帽时还钉着的人要立刻放掉，别留着一条永远到不了期的 Pin
        PINNED.clear();
        releaseAllShots();

        // 曲终 / 摘帽：留一只散养的继续跟着玩家；死亡 / 观战不留（那会儿她找不到主人，
        // 会改认旁边的玩家，反而乱）。下一轮她会就地被收编，不会多出新的。
        HatSummons.clearTetos(level, wearer, wearer.isAlive() && !wearer.isSpectator());
        // 同伴没有「留下」这种待遇：这一轮结束就跟着走
        HatSummons.clearTang(level, wearer);
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
        // 前奏（0 – 427 刻）：不给任何加成，能做的只有按拍击退（见 BrainrotQte）。
        // 注意这里**不再挂 Darkness 药水**：开局的黑不是「挖掉视野」，而是客户端在画面上
        // 叠的一层冷色 + 暗角（QteHud#renderIntroDim），世界还看得见，只是暗。
        if (age < BrainrotBeats.INTRO_END) {
            return;
        }

        // 0 – 12 秒：黑暗（+ 抗性提升 II / 生命恢复 II）
        if (age < DARKNESS_END) {
            refreshDarknessShelter(wearer);
        }

        // 第一句歌词唱完那一刻才开始神隐（整段清仇恨，一直到「Nope, too late」）
        int lastBefore = BrainrotLyrics.lastIndexBefore(TEXT_FIRST);
        if (lastBefore >= 0 && age == BrainrotLyrics.endTick(lastBefore) && TEXT_FIRST > age) {
            HatAbilities.grantFullVeil(wearer, TEXT_FIRST - age);
        }

        // 「Nope, too late」那一段戴帽者脚下发沉：第一人称还会被扳视线，第三人称就只有这份沉重感。
        // 只续 8 刻 —— 窗口一过两秒内自己掉干净，不会拖到后面的光束段。
        if (age >= STILL_START && age < STILL_RELEASE) {
            wearer.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 8, 1, false, false, false));
        }

        // 0 – 13 秒：开场冲击波，约每 2 秒一次、共 8 次，最后一次正好落在「No」出声那一刻
        if (isShockTick(age)) {
            shockwave(level, wearer);
        }

        // 和 Teto 站定同一段：就在它切进那段特有动作的那一刻定住，一直定到站定收尾 ——
        // 三个词（260 / 269 / 278）全在这段里，词落下来的时候场上已经是静止的；
        // 一直压到 Teto 重新动起来的前一刻，和镜头一起放。
        if (age == STILL_START) {
            stasisNearby(level, wearer, STILL_RELEASE - STILL_START);
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
            double radius = RING_MAX_RADIUS * progress;
            slowRing(level, wearer, radius);
            // 光圈从出现到长满这段时间，圈里所有生物一起「原地转 + 定住」
            // （friendswine 那个玩偶的观感：只转不走，挨打也不退出）
            danceInRing(level, wearer, radius);
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

        // 42.85 秒：静默段开始 —— 光束收起，50 格内所有人原地定住，
        // 一直到 43.08 秒那一声落下为止（实测的安静段就这么长）。
        if (age == SONIC_PAUSE_START) {
            stasisNearby(level, wearer, SONIC_BEAM_PAUSE);
        }
        // 同伴（唐嘉琦）：**前奏结束、正片开始**的那一刻才造出来。
        // age 是每刻按真实时间推进的（掉帧时一次可能走 2~3 刻），所以不能写 `age == INTRO_END`，
        // 用一张 Set 记「这一轮已经放过了」，到点之后第一次满足就放，且只放一次。
        // 【雪藏】唐嘉琦暂时不上场：代码、贴图、行为都留着，把 TANG_ENABLED 改成 true 就回来。
        if (TANG_ENABLED && age >= BrainrotBeats.INTRO_END && TANG_SPAWNED.add(wearer.getUUID())) {
            HatSummons.spawnTang(level, wearer);
        }
        // 光柱期间补 3 个 Teto 分身（无台词）；两段光柱各收一次
        if (age == FIRE_START || age == FIRE2_START) {
            HatSummons.spawnBeamExtras(level, wearer);
        }
        if (age == PAUSE_START || age == SLOW_DARK_AT) {
            HatSummons.clearBeamExtras(level, wearer);
        }

        // 43.08 秒：那一声落下 —— 但声波**不再自己放**：得玩家踩中 SONIC_NOTE 那一拍
        // （见 onNoteHit）。定身仍在同一刻解除，那只是「把舞台让给这一声」。

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
            // 收尾的黑暗段给自己 3 秒完整神隐：这一段什么都看不见，最容易白挨打
            HatAbilities.grantFullVeil(wearer, ENDING_VEIL_TICKS);
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
     * Teto 提前多少刻定住：**「No」刚要出声之前 2 刻（0.1 秒）**。
     *
     * <p>文字是 13 秒整才开始画的。原来是提前 6 刻（0.3 秒），但定视线/定身/缓慢都挂在这个
     * 提前量上，整段就显得起步太早 —— 现在统一延后 0.2 秒，收在「词刚砸下来」那一刻。
     */
    private static final int TEXT_FREEZE_LEAD = 2;

    /**
     * 定住多久（刻）：从提前量之后起算 **16 刻（0.8 秒）**，整段 1.1 秒 ——
     * 比三个词弹完（18 刻）短一点，收在「late」刚砸下来之后。
     *
     * <p>客户端的文字本身还会继续停到 58 刻（见 {@code client.BrainrotText.TOTAL_TICKS}），
     * 那一段 Teto 已经在继续转了 —— 想改就改这两个数。
     */
    private static final int TEXT_TICKS = 16;

    /**
     * 第一次「Nope, too late」那一下，Teto 的**特有动作**落在第几刻 —— 量出来的，不是估的。
     *
     * <p>把 {@code teto.png} 的 284 帧按剪影逐帧比对：循环段（帧 143–283）能复现前奏
     * 0–125 帧的每一个姿态，唯独 **帧 126–141** 一帧都对不上 —— 126 是切镜（相对前一帧
     * 突变 38.3），128 起姿态安定，141 和 140 的差只有 0.005（等于定格），142 才切回舞蹈。
     * 按每帧 {@code TetoRenderSettings} 的 2 刻换算，就是下面这三个数。
     *
     * <p>三个词（260 / 269 / 278）是按原曲人声的开口时刻定的，不能动 ——
     * 它们本来就全落在这段站定里（260 在站定里第 8 刻，278 在结束前 4 刻）。
     * 所以这里统一拿「站定」当锚：定身、停转、缓慢吃整段，定视线再往前留出手臂转动的时间。
     */
    private static final int STILL_START = BrainrotBeats.main(252);
    private static final int STILL_SETTLE = BrainrotBeats.main(254);
    private static final int STILL_END = BrainrotBeats.main(282);

    /** 站定那段用的序列帧里最后一帧的编号：126–141 是站定，141 收尾。 */
    private static final int STILL_LAST_FRAME = 141;
    /** 序列图每帧占几刻 —— 和 {@code TetoRenderSettings.DEFAULT_FRAME_TICKS} 一致。 */
    private static final int STILL_FRAME_TICKS = 2;
    /**
     * 序列图的相位补偿（刻）：第 0 帧比正片起点晚 7 刻才播
     * （见 {@code TetoRenderSettings.DEFAULT_FRAME_OFFSET}）。
     */
    private static final int STILL_FRAME_OFFSET = 0;

    /**
     * 站定整段的真正收尾：**最后一帧播完、下一帧（切回舞蹈）出现的前一刻**。
     *
     * <p>126–141 这 16 帧就是那段站定。按每帧 {@link #STILL_FRAME_TICKS} 刻、再加上
     * {@link #STILL_FRAME_OFFSET} 刻的补偿，最后一帧占正片段第 {@code 7 + 141 × 2 = 289}
     * 刻到第 {@code 290} 刻，下一帧从 {@code 7 + 142 × 2 = 291} 刻开始 ——
     * 换成链路年龄就是 {@code main(291)}。
     *
     * <p>{@link #STILL_END}（{@code main(282)}）是只按「141 帧 × 2 刻」算的，漏了那 7 刻，
     * 比实际动作早 9 刻撒手：镜头一松，Teto 还在摆最后两帧，所以才看不清。
     * 这里改按序列图自己的帧边界算，窗口收在它重新动起来的前一刻，不多不少。
     */
    private static final int STILL_HOLD_END = BrainrotBeats.main(
            STILL_FRAME_OFFSET + (STILL_LAST_FRAME + 1) * STILL_FRAME_TICKS);

    /**
     * 撒手点：镜头 / 定身 / 缓慢比「动作还原」**再提前 0.2 秒（4 刻）**收回。
     *
     * <p>以前一直咬到最后一帧播完，可最后那几刻她其实已经在往普通动作上摆了 ——
     * 玩家视线还被扳着、身上还挂着缓慢，看着就是「她都不动了，镜头还没松」。
     */
    private static final int STILL_RELEASE_LEAD = 4;
    private static final int STILL_RELEASE = STILL_HOLD_END - STILL_RELEASE_LEAD;

    /** 站定开始那一刻（客户端定视线要对准到这一只）。 */
    public static int stillStart() {
        return STILL_START;
    }

    /**
     * Teto 现在要不要停转：**和「定视线 + 缓慢」同一个窗口**。
     *
     * <p>停在圆周上的当前位置、只跟着戴帽者平移（与玩家相对静止）。以前这里收在字幕刚弹完
     * （{@code TEXT_FIRST + TEXT_TICKS}），可镜头还要再咬 2 秒 —— 人正盯着它，它却先转起来了，
     * 所以整段对齐到 {@link #tetoFocusWindow}：镜头撒手的那一刻，Teto 才重新开始转。
     */
    static boolean tetoOrbitFrozen(LivingEntity wearer) {
        return tetoFreezeWindow(ageOf(wearer));
    }

    /**
     * 是不是「黑暗期」—— 57–71 秒（漂浮 + 黑暗）和 100 秒到曲末（缓慢 + 黑暗）。
     * 减速圈在这两段里收起来，其它时候一旦长到最大就一直留着。
     */
    /** 光束正在放的那两段（28.5–57 秒、71–100 秒）。 */
    static boolean beamActive(int age) {
        return (age >= FIRE_START && age < PAUSE_START) || (age >= FIRE2_START && age < SLOW_DARK_AT);
    }

    /**
     * Teto 的绕圈倍率：光束在放的时候转两倍快。
     *
     * <p>「停在正前方」那段对齐在 {@link #firstTextTick()} 之前就结束了（还没进光束，
     * 倍率是 1），所以这里的 2 倍不会把那一圈算歪。
     */
    /** 光束期 Teto 绕圈半径的鼓胀幅度：±35%（4 格 → 2.6–5.4 格）。 */
    private static final double ORBIT_BREATH = 0.35D;
    /** 一个来回 80 刻（4 秒）。 */
    private static final double ORBIT_BREATH_RATE = Math.PI / 40.0D;

    /**
     * Teto 的绕圈半径倍率：光束期一边转一边扩缩（半径按正弦呼吸）。
     * 进出光束段用 {@link BrainrotBeats#beamBlend} 平滑，不会在切段那一刻跳一下。
     */
    static double orbitRadiusScale(LivingEntity wearer) {
        int age = ageOf(wearer);
        double blend = BrainrotBeats.beamBlend(age);
        if (blend <= 0.0D) {
            return 1.0D;
        }
        return 1.0D + ORBIT_BREATH * blend * Math.sin(age * ORBIT_BREATH_RATE);
    }

    static double orbitSpeedScale(LivingEntity wearer) {
        return beamActive(ageOf(wearer)) ? 2.0D : 1.0D;
    }

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

    /** 光圈里的生物：钉住 + 原地转，refresh 只续 3 刻，长出圈外自然停。 */
    private static void danceInRing(ServerLevel level, LivingEntity wearer, double radius) {
        if (radius < 0.5D) {
            return;
        }
        long until = level.getServer().getTickCount() + 3;
        double rangeSq = radius * radius;
        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class,
                wearer.getBoundingBox().inflate(radius))) {
            if (target == wearer || !target.isAlive() || target.distanceToSqr(wearer) > rangeSq) {
                continue;
            }
            if (!HatAbilities.isEnemy(target, wearer)) {
                continue;
            }
            pinLiving(target, until);
        }
    }

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
        // × 打拍连击倍率（每连击 +0.4%，不封顶）× 力量倍率
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
        // 连击倍率同样乘在百分比上（每层 +0.4%、不封顶）；涌动那层也在里面
        // 百分比那一层再乘一个倍率：调参器「脑蚀」页可调，出厂 0.5（= 原来的一半）
        final double accentRatio = (0.005D + (surge[1] - 1.0D) * 0.03D)
                * HatSettings.brainrotPercentScale()
                * BrainrotQte.damageMultiplier(wearer) * power;
        // 每 5 秒把「连击 / 倍率 / 这一刻的帧伤和百分比」写一行日志，
        // 调伤害的时候直接看这行，不用猜是哪一层在涨。
        if (level.getGameTime() % 100 == 0L) {
            HatMod.LOGGER.info("[hatmod] 脑蚀光柱：连击 {}，倍率 ×{}，帧伤 {}，百分比 {}/刻（目标 {}）",
                    BrainrotQte.comboOf(wearer),
                    String.format("%.2f", BrainrotQte.damageMultiplier(wearer)),
                    String.format("%.1f", flat),
                    String.format("%.3f%%", accentRatio * 100.0D),
                    targets.size());
        }
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
                if (HatSettings.forceBreak()) {
                    // 破防模式：和牛仔帽走**同一条结算管线** —— 百分比写真血（世界变量 /
                    // 自有字段 / 原版）、固定伤害拆段破「每击上限」、打不动再压最大生命兜底、
                    // 破防记账、仇恨记录。控制只给被这道光选中的那个（holdTarget=false）。
                    HatAbilities.damageBeamTick(level, wearer, HatType.BLACK, source,
                            flat, (float) accentRatio, false, target);
                } else {
                    // 普通模式：维持原来的简单结算（hurt 固定伤害 + 直接 setHealth 的百分比）
                    target.invulnerableTime = 0;
                    target.hurt(source, flat);
                    float health = target.getHealth();
                    if (health > 0.0F && accentRatio > 0.0D) {
                        target.setHealth(health - (float) (health * Math.min(accentRatio, 1.0D)));
                    }
                    wearer.setLastHurtMob(target);
                }
                target.setDeltaMovement(velocity);
                target.hurtMarked = true;
            }
        }
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

    /**
     * 打拍踩中一拍：如果正好是「那一声」那一拍，就把声波放出去。
     *
     * <p>长音只认头拍 —— QTE 在长音里本来就只在头拍算一次（之后是按住涨连击），
     * 所以这里不需要再判断「是不是长音的头」。
     */
    static void onNoteHit(LivingEntity wearer, int tick) {
        if (tick != SONIC_NOTE) {
            return;
        }
        if (!(wearer.level() instanceof ServerLevel level) || !isRunning(wearer)) {
            return;
        }
        if (!SONIC_FIRED.add(wearer.getUUID())) {
            return;
        }
        beginSonic(level, wearer);
        // 黑白闪 + 锁视线跟着这一包走：没放出声波就一样都不给
        if (wearer instanceof ServerPlayer player) {
            HatNetwork.sendSonicFlash(player, SONIC_MONO_TICKS, SONIC_LOCK_TICKS);
        }
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
        LivingEntity target = HatAbilities.findNearestEnemy(wearer);
        Vec3 from = wearer.position().add(0.0D, wearer.getBbHeight() * 0.6D, 0.0D);
        Vec3 to = target != null
                ? target.getEyePosition()
                : from.add(wearer.getViewVector(1.0F).normalize().scale(16.0D));
        // 声波和那一声**完全同刻**：整只喇叭口在同一刻铺满、同一刻消失。
        // 以前是 20 刻从嘴边滚出去，画面比声音晚整整 1 秒，卡点永远差一拍。
        sonicBurst(level, from, to);
        // 伤害 / 缓慢 / 易伤 也在同一刻落下（黑暗已换成客户端的黑白后处理，见 sonicMonoWindow）
        resolveSonic(level, wearer, from, to);
    }

    /** 一次性把整个喇叭口画满：没有逐刻推进，所以出现和结束都在同一刻。 */
    private static void sonicBurst(ServerLevel level, Vec3 from, Vec3 to) {
        Vec3 axis = to.subtract(from);
        if (axis.lengthSqr() < 1.0E-4D) {
            return;
        }
        Vec3 direction = axis.normalize();
        int rays = 11;
        int steps = 18;
        for (int r = 0; r < rays; r++) {
            double degrees = (r / (double) (rays - 1) - 0.5D) * SONIC_CONE_HALF_DEGREES * 2.0D;
            double radians = Math.toRadians(degrees);
            double cos = Math.cos(radians);
            double sin = Math.sin(radians);
            Vec3 ray = new Vec3(direction.x * cos - direction.z * sin, direction.y,
                    direction.x * sin + direction.z * cos);
            for (int i = 0; i < steps; i++) {
                double f = (i + 1) / (double) steps;
                Vec3 point = from.add(ray.scale(f * SONIC_RANGE));
                level.sendParticles(ParticleTypes.SONIC_BOOM,
                        point.x, point.y, point.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
            }
        }
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
        // 判定用音波自己的射程收人：光柱的 FIRE_RANGE 只有 40 格，够不到放大后的喇叭口
        List<LivingEntity> candidates = new ArrayList<>(HatAbilities.recentAggressors(wearer));
        for (LivingEntity extra : enemiesNear(level, wearer, SONIC_RANGE)) {
            if (!candidates.contains(extra)) {
                candidates.add(extra);
            }
        }
        for (LivingEntity target : candidates) {
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
            float before = target.getHealth();
            target.invulnerableTime = 0;
            // 定身 + 易伤都必须在 hurt 之前挂：hurt 之后目标可能已经被击退 / 打死了
            HatAbilities.applySonicHold(level, target, SONIC_HOLD_TICKS);
            // 光是 NoAI + 缓慢还能被击退推着走，所以再钉一层：位置也按回去
            pinLiving(target, level.getServer().getTickCount() + SONIC_HOLD_TICKS);
            target.addEffect(new MobEffectInstance(HatEffects.VULNERABLE.get(),
                    SONIC_VULNERABLE_TICKS, 0, false, false, true));
            // 黑色发光：挨了这一声的敌人被标出来（轮廓色取自队伍颜色，见 HatAbilities#markBlackGlow）
            // 发光一直挂到定身结束（和 40 秒的钉子同长），不是只有 10 秒的易伤
            HatAbilities.markGlow(target, SONIC_HOLD_TICKS);
            if (target.hurt(source, damage)) {
                target.push(direction.x * 2.5D, 0.5D, direction.z * 2.5D);
            }
            // 破防记账：尖叫也进同一本账
            HatAbilities.settleForce(target, Math.max(0.0F, before - target.getHealth()), damage);
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
                    || Teto.isBulletAngel(target)
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
        // 圈里的**生物**也一起钉死（升级版硬控）：玩家靠 teleport 拉回来，生物直接按回原位
        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class,
                wearer.getBoundingBox().inflate(STASIS_RADIUS))) {
            if (target == wearer || target instanceof ServerPlayer || !target.isAlive()) {
                continue;
            }
            if (target.distanceToSqr(wearer) > rangeSq || !HatAbilities.isEnemy(target, wearer)) {
                continue;
            }
            pinLiving(target, until);
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

    /** 把一只生物钉在原地到 {@code until} 刻（速度清零 + 缓慢 255 + 每刻按回原位）。 */
    private static void pinLiving(LivingEntity living, long until) {
        living.setDeltaMovement(Vec3.ZERO);
        living.hurtMarked = true;
        living.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20, 255, false, false, false));
        if (living instanceof Mob mob) {
            mob.setTarget(null);
            mob.getNavigation().stop();
            // 关 AI 统一走 HatAbilities：到点由那份登记按 UUID 还回去。
            // 以前这里是裸的 setNoAi(true)，解除时没人打开 —— 被钉过的怪会一直停在原地，
            // 漂在空中的就永远挂在半空（区块一卸载还会把 NoAI:1 写进 NBT）。
            HatAbilities.suppressAi(mob, until);
        }
        // 已经在钉的话沿用原来的锚点：不然每刷一次就「以当前位置为新原位」，
        // 被推一下就把锚点也带走了
        Pin previous = PINNED_LIVING.get(living.getUUID());
        Vec3 anchor = previous == null ? living.position() : previous.pos;
        long end = previous == null ? until : Math.max(until, previous.until);
        PINNED_LIVING.put(living.getUUID(), new Pin(anchor, end));
    }

    /** 跳舞的节奏：蹦多高（格）/ 每刻转多少度 / 蹦的频率。 */
    private static final double DANCE_HOP = 0.5D;
    private static final float DANCE_SPIN = 22.0F;
    private static final double DANCE_TEMPO = 0.28D;

    /**
     * 每刻维护被钉住的生物：**原地跳舞**到点解除。
     *
     * <p>照 friendswine 那个玩偶的观感：只站在原地弹跳 + 转圈，位置一步都不挪。
     * 挨打也不会退出 —— 钉子和 {@code NoAI} 都不因为伤害解除，所以它挨完打还是接着跳。
     */
    private static void tickPinnedLiving(ServerLevel level) {
        if (PINNED_LIVING.isEmpty()) {
            return;
        }
        long now = level.getServer().getTickCount();
        java.util.Iterator<Map.Entry<UUID, Pin>> iterator = PINNED_LIVING.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Pin> entry = iterator.next();
            Pin pinned = entry.getValue();
            Entity entity = level.getEntity(entry.getKey());
            if (!(entity instanceof LivingEntity living) || living.isRemoved()
                    || !living.isAlive() || now >= pinned.until) {
                iterator.remove();
                continue;
            }
            // 原地跳舞：只蹦 + 转圈，横坐标始终钉在锚点上 ——
            // 所以怎么蹦、怎么被击退、怎么被推，都离不开那一个点。
            double phase = ((entry.getKey().hashCode() & 0xFFFF) / 65536.0D) * Math.PI * 2.0D;
            double hop = Math.abs(Math.sin(now * DANCE_TEMPO + phase)) * DANCE_HOP;
            living.setPos(pinned.pos.x, pinned.pos.y + hop, pinned.pos.z);
            float spin = living.getYRot() + DANCE_SPIN;
            living.setYRot(spin);
            living.yBodyRot = spin;
            living.yHeadRot = spin;
            living.setDeltaMovement(Vec3.ZERO);
            living.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 10, 255, false, false, false));
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

    /** 尖叫**之前**那一段静默：光束收起来，把焦点让给接下来这一声。 */
    private static boolean inSonicPause(int age) {
        return age >= SONIC_PAUSE_START && age < SONIC_AT;
    }

    /**
     * Teto 摆动作时**定格用哪一帧**：定格在姿态安定的那一刻（{@link #STILL_SETTLE}）——
     * 切镜那一帧还在动（帧 126），定住会是个半截姿态；安定的那帧（127）才是那段站定的样子。
     */
    public static int tetoPoseTick() {
        return STILL_SETTLE;
    }

    /**
     * 客户端用：按 Teto 自己的 {@code tickCount} 判断它是不是正处在「停转摆动作」那一段。
     *
     * <p>Teto 是链路开始时召唤的，{@code tickCount} 和链路年龄基本同一条线，
     * 所以客户端不走网络也能自己算出来。
     *
     * <p>这里只到 {@link #STILL_END}，那是「帧号 × 2 刻」直接算的粗刻度；序列图还有
     * {@link #STILL_FRAME_OFFSET} 刻相位补偿，实际要摆到 {@link #STILL_HOLD_END} 前一刻。
     * 镜头 / 定身 / 缓慢都跟着后一个走。
     */
    public static boolean tetoPoseWindow(int tickCount) {
        return tickCount >= STILL_SETTLE && tickCount < STILL_END;
    }

    /**
     * 定视角 / 缓慢要比摆动作那段多留多久（刻）：0.35 秒（视角本身转过去要时间）。
     *
     * <p>Teto 停转、玩家定身都用这个数，改一处五处一起动。
     */
    private static final int FOCUS_EXTRA_TICKS = 7;

    /**
     * 定视线比「站定 / 定身 / 停转」再早多少刻开始：**10 刻（0.5 秒）**。
     *
     * <p>镜头是每刻转掉剩余角度的 35%（见 {@code TetoFocus.TURN_PER_TICK}），到第 10 刻只剩
     * 1.3%，等于完全到位。原来是 6 刻，可那 6 刻正好吃掉站定开头的 0.3 秒 —— 玩家还在把镜头
     * 扳过去，那段特有动作已经开始了。现在提前到站定前 0.5 秒，站定那一刻人已经盯着它。
     */
    private static final int FOCUS_LEAD_EARLY = 10;

    /**
     * 客户端强制视线的窗口：收在 {@link #STILL_RELEASE}（动作还原前 0.2 秒），
     * 另外**提前 {@link #FOCUS_LEAD_EARLY} 刻开始**。
     *
     * <p>镜头是慢慢扳过去的，扳过去本身要 0.3 秒左右 —— 动作一结束就撒手，
     * 人只会看到"还没转到就先弹回来了"。所以两头都留足：前面 0.5 秒把镜头转过去，
     * 后面一直咬到它真正重新动起来的前一刻，不多不少。
     */
    public static boolean tetoFocusWindow(int tickCount) {
        return tickCount >= STILL_START - FOCUS_LEAD_EARLY && tickCount < STILL_RELEASE;
    }

    /** 定身 / Teto 停转 / 缓慢共用的窗口（比定视线晚 0.3 秒开始，收在下一帧动作出现之前）。 */
    public static boolean tetoFreezeWindow(int tickCount) {
        return tickCount >= STILL_START && tickCount < STILL_RELEASE;
    }

    /** 尖叫锁视线的时长（刻）：1 秒 —— 方向看一眼就够了，锁 40 秒没法玩。 */
    private static final int SONIC_LOCK_TICKS = 20;

    // 锁视线同理：声波没放出来就不锁（见 client/SonicFlash）。

    // 黑白闪 / 锁视线的窗口不再按刻数自己算：声波放出来时由
    // {@code HatNetwork.sendSonicFlash} 通知客户端（见 client/SonicFlash）。

    /** 第一次「Nope, too late」落在第几刻（给 Teto 的轨道相位对齐用）。 */
    static int firstTextTick() {
        // Teto 在站定开始那一刻（STILL_START）就不再绕圈了，停下来的那个位置才是
        // 「那一下看到的位置」—— 相位对齐要按这一刻算，否则四只会停在斜向之外。
        return STILL_START;
    }

    /** 给 {@code HatAbilities} 之类的地方查询「这个戴帽者现在是不是在脑蚀循环里」。 */
    /** 这个戴帽者当前的脑蚀轮次是不是 P 键强制起手的（见 {@link #forceStart}）。 */
    static boolean isForcedRound(LivingEntity wearer) {
        return FORCED.contains(wearer.getUUID());
    }

    /**
     * 链路是不是已经走到**尾声**（收尾那一段）。
     *
     * <p>破防的处决只在这之后才允许发生 —— 债照记、血量快照照比，
     * 但真正动手要等链路收尾，不在光束刚亮起来的时候就开始抹人。
     */
    static boolean inFinishWindow(LivingEntity wearer) {
        int age = ageOf(wearer);
        if (age >= SLOW_DARK_AT) {
            return true;
        }
        // 破防模式：牛仔帽的光柱在**每一轮照射的最后 40 刻**就能出尾杀；脑蚀帽同理 ——
        // 每一段照射的收尾也算尾声。不然一首曲子里只有最后 5.7 秒能动手，
        // 碰到 21 亿血那种 Boss 根本等不到。
        if (HatSettings.forceBreak() && beamActive(age)) {
            if (age >= PAUSE_START - FINISH_TAIL_TICKS && age < PAUSE_START) {
                return true;
            }
            if (age >= SLOW_DARK_AT - FINISH_TAIL_TICKS && age < SLOW_DARK_AT) {
                return true;
            }
        }
        return false;
    }

    /** 破防模式下「尾杀」在每段照射最后多少刻开放（和牛仔帽的 HAT_FINISH_WINDOW_TICKS 一致）。 */
    private static final int FINISH_TAIL_TICKS = 40;

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

    /**
     * 戴帽者**现在是不是正在放光柱**。
     *
     * <p>给同伴（{@link TangJiaqi}）用：它要和玩家同一刻开火、同一刻收手，所以这里把
     * 时间线里所有「停火」的门都算进去 —— 两段照射（28.5 秒起 / 71 秒起）、
     * 中间那次尖叫的 0.3 秒空档、以及 57 秒到 71 秒、100 秒之后的黑暗期。
     *
     * <p>和 {@code applyTimeline} 里 {@code fire()} 的两处调用点严格一一对应：
     * 那边改了窗口，这里必须跟着改，否则同伴的光柱会和玩家错开。
     */
    public static boolean beamWindow(LivingEntity wearer) {
        int age = ageOf(wearer);
        if (age < 0) {
            return false;
        }
        if (age >= FIRE_START && age < PAUSE_START) {
            return !inSonicPause(age);
        }
        return age >= FIRE2_START && age < SLOW_DARK_AT;
    }
}
