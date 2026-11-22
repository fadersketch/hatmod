package com.hatmod;

import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 「脑蚀」的**打拍**（QTE）：跟着 BGM 的真实鼓点按拍子，越连得多，光柱越痛。
 *
 * <p>拍点表在 {@link BrainrotBeats} —— 那是从音频里分析出来的 238 个真实鼓点，
 * 不是按「每 N 刻一拍」算出来的（8.86 刻不是整数，取整到曲末会漂 1.7 秒）。
 *
 * <p>规则（服务端权威，客户端只发「我按了一下」）：
 * <ul>
 *   <li>离最近一拍 ±2 刻内按 = PERFECT，±4 刻内 = GOOD，两者都算「踩上了」，连击 +1；</li>
 *   <li>一拍只判一次；完全打偏（±4 刻之外）不扣也不涨，等下一拍；</li>
 *   <li>某拍过窗还没人按 = MISS：**连击立刻清零**（漏一拍就断）；</li>
 *   <li>连击每 +1，光柱伤害 +2%（无上限），固定伤害和百分比一起吃；</li>
 *   <li>连击 ≥8 给抗性 I、≥16 给生命恢复 I（隐形短效，靠打拍维持）；</li>
 *   <li>每 **10 连击**，四只 Teto 各回 5% 最大生命；</li>
 *   <li>每一拍回血：GOOD 半颗心、PERFECT 一颗心 —— 自保跟着节奏走；</li>
 *   <li>涌动期间光柱每一击附带**目标当前生命**的百分比伤害（重音，见 BrainrotCycle.fire）——
 *       这顶帽子本体没有百分比伤害，这块就是专门留给打拍补的。</li>
 * </ul>
 *
 * <p>刻意**不新增任何时间点**：拍点全部吸附在已有的鼓点上，所以不管玩家打不打拍，
 * 曲子的卡点节奏（冲击波、定身、No too late……）一点都不会变 —— 打拍只是在这张
 * 固定的时间表上「再挣一层奖励」，不是新的阶段开关。
 */
final class BrainrotQte {

    /** PERFECT 判定窗（刻）。 */
    private static final int PERFECT_TICKS = 3;
    /** GOOD 判定窗（刻）：放宽到 ±6，右键/网络延迟那点误差不再直接判空。 */
    private static final int GOOD_TICKS = 6;
    // 连击现在是「漏一拍就清零」（见 tick 里的 MISS 分支），不再需要连击次数门槛。
    /**
     * 每层连击给光柱加多少伤害（0.004 = +0.4%）。
     *
     * <p>以前是 +2%/层，压到 +0.4%/层 —— 但**仍然不封顶**：连得越久越强，
     * 上限交给操作时长去决定（10 连击 ×1.04、40 连击 ×1.16、100 连击 ×1.40）。
     * 这个倍率同时作用在固定伤害和百分比伤害上（见 {@code BrainrotCycle.fire}）；
     * 百分比本身的基数（每刻 0.5% × 脑蚀百分比倍率）没有动。
     */
    private static final float BONUS_PER_COMBO = 0.004F;
    /** 连击到多少给抗性 I。 */
    private static final int COMBO_RESISTANCE = 8;
    /** 连击到多少给生命恢复 I。 */
    private static final int COMBO_REGENERATION = 16;
    /** 每隔多少连击给 Teto 治疗一次。 */
    private static final int COMBO_PER_HEAL = 10;
    /** 节拍回血：GOOD 一拍回半颗心（1 点生命）。 */
    private static final float HEAL_GOOD = 1.0F;
    /** 节拍回血：PERFECT 一拍回一颗心（2 点生命）。 */
    private static final float HEAL_PERFECT = 2.0F;
    /** 每次 Teto 治疗量（最大生命的百分比）。 */
    private static final float TETO_HEAL_RATIO = 0.05F;
    /** 打拍 buff 的刷新长度（刻）：比续发间隔（10 刻）长一点，中间不会闪断。 */
    private static final int BUFF_TICKS = 45;

    // 打中一拍的「涌动」：光柱瞬间更长更粗，随剩余时间缩回去。PERFECT 比 GOOD 猛。
    /** PERFECT 的涌动持续（刻）。 */
    static final int SURGE_TICKS_PERFECT = 10;
    /** PERFECT 时光柱长度 / 粗细的峰值倍率。 */
    static final float SURGE_LEN_PERFECT = 1.6F;
    static final float SURGE_RADIUS_PERFECT = 1.8F;
    /** GOOD 的涌动持续（刻）。 */
    static final int SURGE_TICKS_GOOD = 6;
    /** GOOD 时的峰值倍率。 */
    static final float SURGE_LEN_GOOD = 1.3F;
    static final float SURGE_RADIUS_GOOD = 1.4F;

    /** 判定结果：也同步给客户端画 HUD。 */
    static final int RATING_NONE = 0;
    static final int RATING_PERFECT = 1;
    static final int RATING_GOOD = 2;
    static final int RATING_MISS = 3;

    /** 每个戴帽者一份打拍状态。 */
    private static final class State {
        int combo;
        /** 已经判过的最后一拍（过窗判 MISS 也会推它）。 */
        int lastBeat = -1;
        /** 玩家**真正按中**的最后一拍：连点去重用，和 {@link #lastBeat} 分开。 */
        int lastHit = -1;
        /** 最近一次被判 MISS 的是哪一拍（-1 = 没有）：晚按补回来时撤掉那一记 MISS。 */
        int lastMissed = -1;
        /** 那一记 MISS 判在什么时候（刻）。 */
        int lastMissedAt = -1;
        /** 那一记 MISS 之前的连击数：补回来时接回去。 */
        int comboBeforeMiss;
        int missStreak;
        int rating = RATING_NONE;
        int ratingAt = -1000;
        /** 涌动到哪一刻为止（{@link Integer#MIN_VALUE} = 没在涌动）。 */
        int surgeEnd = Integer.MIN_VALUE;
        /** 这一次涌动的总长（刻），拿来换算「还剩多少」。 */
        int surgeTicks;
        /** 涌动峰值：光柱长度 / 粗细的倍率。 */
        float surgeLen = 1.0F;
        float surgeRadius = 1.0F;
        /** 六个口白键的命中位（第 i 位 = {@link BrainrotBeats#speechBit(int)}）。 */
        int speechMask;
        /** 攒闪避的打拍计数（0..BONUS_PER_DODGE）：伴奏层和主旋律共用一个，只在光柱期间涨。 */
        int acc;
        /** 伴奏层最后处理过的那颗键的下标（防止同一颗重复计数）。 */
        int accLast = -1;
        /** 最近一次**真踩上**的长音头拍（刻）；{@link Integer#MIN_VALUE} = 还没踩上过。 */
        int holdHeadHit = Integer.MIN_VALUE;
        /** 玩家此刻有没有按住（打拍键 / 右键）：长音只认**按住的那几刻**。 */
        boolean holding;
        /** 当前这根长音里，真正按住了几刻（换一根就归零）。 */
        int holdTicks;
        /** {@link #holdTicks} 记的是第几根长音（-1 = 还没进过）。 */
        int holdTicksHold = -1;
        /** 本轮得分（PERFECT 100 / GOOD 50）。 */
        int score;
        /** 本轮 MISS 了几拍。 */
        int misses;
        /** 本轮 PERFECT / GOOD 各几拍（面板上分开显示）。 */
        int perfects;
        int goods;
        /** 本轮一共踩对了几拍（PERFECT / GOOD 都算）。 */
        int hits;
        /** 本轮的连击峰值（曲终评价用）。 */
        int bestCombo;
        /** 连续 PERFECT 数（GOOD / MISS 归零；攒到 50 她会说一句）。 */
        int perfectStreak;
        /** 最后一次**踩中**的刻（{@code -1} = 还没踩中过；「手冷了？」用）。 */
        int lastHitAt = -1;
    }

    private static final Map<UUID, State> STATES = new HashMap<>();

    /**
     * 伴奏层**全自动**：谱子每走到一颗牛仔帽，就算替玩家按了一下。
     *
     * <p>现在它只负责往同一个 0/10 里 +1，而且**光柱之外不累计** —— 前奏、停火那段、
     * 收尾的伴奏都不算数，挂着机攒不出闪避。和玩家手打的主旋律共用
     * {@link #addDodgeProgress}：每拍 +1，攒满换 1 次闪避并立刻清零、从头再攒。
     */
    private static void autoAccompaniment(ServerPlayer player, State state, int age) {
        if (!BrainrotBeats.inBeam(age)) {
            return;
        }
        int index = BrainrotBeats.bonusIndexAt(age, 0);
        if (index < 0 || index <= state.accLast) {
            return;
        }
        state.accLast = index;
        addDodgeProgress(player, state);
        // 只更新闪避 / 计数，不带判定：否则会把上一拍的 PERFECT/GOOD 再弹一次
        HatNetwork.sendQteState(player, state.combo, RATING_NONE, dodgesOf(player.getUUID()));
    }

    /**
     * 攒闪避的打拍计数 +1：伴奏层自动打的和主旋律手打的**共用一个 0/10**。
     *
     * <p>攒满 {@link BrainrotBeats#BONUS_PER_DODGE} 就换 1 次闪避、计数立刻清零，
     * 从头再攒 —— 玩家在奖励面板上看得见它在涨。闪避已经存满 {@link #MAX_DODGES}
     * 时不再计数：攒了也没处放。
     */
    private static void addDodgeProgress(ServerPlayer player, State state) {
        if (dodgesOf(player.getUUID()) >= MAX_DODGES) {
            return;
        }
        state.acc++;
        if (state.acc < BrainrotBeats.BONUS_PER_DODGE) {
            return;
        }
        state.acc = 0;
        DODGES.merge(player.getUUID(), 1, Integer::sum);
    }


    /**
     * 「节奏大师」的一轮记录：从链路起手一直跟到曲终。
     *
     * <p>只算**接敌自然起手**的那一轮（P 键强制开的不算）；整首曲子**连击一次都没断**
     * （没掉到 0），而且**全程周边一直得有敌人**（打空场不算），曲终才发成就。
     * GOOD 只加 1 层、不扣连击，所以偶尔慢半拍不影响；断了才会作废。
     */
    private static final class Round {
        final boolean natural;
        /** 连击有没有断过（掉到 0）。 */
        boolean comboBroken;
        /** 这一轮有没有用过「自动打拍」—— 用过就不发成就。 */
        boolean assisted;
        /** 整首曲子里有没有出现过「周边一个敌人都没有」的空档。 */
        boolean inCombat = true;

        Round(boolean natural) {
            this.natural = natural;
        }
    }

    private static final Map<UUID, Round> ROUNDS = new HashMap<>();

    /** 连击攒出来的闪避次数（黑帽同款：挨打时这一击完全落空 + 瞬移）。 */
    private static final Map<UUID, Integer> DODGES = new HashMap<>();
    /** 下次攒闪避的连击门槛。 */
    private static final Map<UUID, Integer> NEXT_DODGE_AT = new HashMap<>();
    /** 每多少连击攒一次闪避；闪避最多同时存几次（连击攒的和打拍计数攒的共用这个上限）。 */
    private static final int COMBO_PER_DODGE = 10;
    private static final int MAX_DODGES = 3;
    /** 单拍得分：PERFECT / GOOD（MISS 0 分，只计数）。 */
    private static final int SCORE_PERFECT = 100;
    private static final int SCORE_GOOD = 50;
    /** 曲终评价的分档（按总分；247 拍全 PERFECT 约 24700）。 */
    private static final int SCORE_S = 20000;
    private static final int SCORE_A = 14000;
    private static final int SCORE_B = 8000;
    /**
     * 长音里每多少刻涨一层连击（以前是每刻 +1，一首歌下来能白送几百层）。
     *
     * <p>谱面里的长音大多只有 5–15 刻，8 刻一层的话短的长音一层都涨不到 ——
     * 收到 5 刻（0.25 秒），「按住这段时间在涨连击」才看得见。
     */
    private static final int HOLD_COMBO_TICKS = 5;
    /** 「手冷了？」的门槛：连续多少刻一拍都没踩中（10 秒）。 */
    private static final int COLD_TICKS = 200;

    private BrainrotQte() {
    }

    /**
     * 每刻跑一次：把「过窗还没按」的拍记成 MISS，并按连击续 buff。
     *
     * <p>MISS 判定用服务端自己的 {@code age}（和时间线同一个时钟）；玩家按键那一下
     * 用的是客户端报上来的 {@code age}（玩家照着 HUD 按的，就该按 HUD 的钟判）。
     */
    static void tick(ServerLevel level, LivingEntity wearer, int age) {
        // 「Nope / too / late」这三拍由服务端替玩家按：这 1.4 秒眼睛该看的是字幕和
        // Teto 的动作，不该在看不见的判定条上抢拍。
        if (wearer instanceof ServerPlayer speechPlayer) {
            autoTapSpeech(speechPlayer, age);
        }
        // 判定用「服务端钟 + 客户端偏差」：和玩家按键那一下用同一个钟，
        // 才不会出现「按的明明踩上了、服务端却判 MISS」
        int judgeAge = age + skewOf(wearer.getUUID());
        State state = STATES.get(wearer.getUUID());
        if (state != null) {
            // 按顺序判：永远从「最后一拍之后那一拍」开始，谁先过窗谁先记。
            // 以前是拿最近的一拍往回退一格 —— 晚按宽限拉长之后，隔壁那拍会先过期，
            // 中间那拍就永远等不到自己的判定（整拍漏记 MISS）。
            int judged;
            if (state.lastBeat < 0) {
                // 刚开始 / 中途才加入：从当前时间的前一拍起判，别把前面的整首补成 MISS
                int[] probe = new int[1];
                int at = BrainrotBeats.nearest(judgeAge, probe);
                judged = Math.max(0, probe[0] > 0 ? at - 1 : at);
            } else {
                judged = state.lastBeat + 1;
            }
            // 过窗用的是「晚按窗」：早按还是 ±窗口，晚按多给 LATE_GRACE 刻 ——
            // 正好把「踩上了、只是手慢一两刻」的人从 MISS 里捞出来
            if (judged < BrainrotBeats.count()
                    && judged > state.lastBeat
                    && judgeAge > BrainrotBeats.tickAt(judged) + BrainrotBeats.lateWindowAt(judged)) {
                state.lastBeat = judged;
                state.lastMissed = judged;
                // 记在「客户端钟」上：打拍包带的是客户端刻，两边得同一个钟才好比
                state.lastMissedAt = judgeAge;
                // 前奏也算 MISS、也计分：整首的判定数要连起来，成就要按整首算
                // （只有最后那段黑暗之后的收尾拍不罚）
                if (BrainrotBeats.scores(judgeAge) || BrainrotBeats.inIntro(judgeAge)) {
                    state.missStreak++;
                    state.misses++;
                    state.perfectStreak = 0;
                    // 漏一拍 = 连击直接中断（以前是 −1、连续 3 次才清零）
                    boolean brokeCombo = state.combo > 0;
                    state.comboBeforeMiss = state.combo;
                    state.combo = 0;
                    if (brokeCombo && !silenced(wearer.getUUID())) {
                        TetoTalk.sayCooled(level, wearer, 48.0D, "miss", 240, TetoSpeech.pick(
                                level.random, TetoSpeech.summonsOf(wearer.getUUID()),
                                "hatmod.teto.miss", 2, 2));
                    }
                    // 「节奏大师」：连击断了这一轮就作废。
                    // 前奏没有连击可断（打拍不加层），所以要单独把前奏的 MISS 也算上。
                    Round round = ROUNDS.get(wearer.getUUID());
                    if (round != null && (brokeCombo || BrainrotBeats.inIntro(judgeAge))) {
                        round.comboBroken = true;
                    }
                    state.rating = RATING_MISS;
                    state.ratingAt = age;
                    if (wearer instanceof ServerPlayer player) {
                        HatNetwork.sendQteState(player, state.combo, state.rating,
                                dodgesOf(player.getUUID()), BrainrotBeats.tickAt(judged));
                    }
                }
            }
        }

        // 伴奏层全自动：走到牛仔帽拍点就替玩家按一下，够数自动送闪避（不参与判定）
        if (state != null && wearer instanceof ServerPlayer accPlayer) {
            autoAccompaniment(accPlayer, state, age);
        }
        // 连续 10 秒一拍都没踩中（一局一次，只在计分段）：她替你着急
        if (state != null && !silenced(wearer.getUUID()) && state.lastHitAt >= 0
                && judgeAge - state.lastHitAt >= COLD_TICKS
                && BrainrotBeats.scores(judgeAge)
                && once(wearer.getUUID(), "cold")
                && wearer instanceof ServerPlayer coldPlayer
                && wearer.level() instanceof ServerLevel coldLevel) {
            TetoTalk.sayCooled(coldLevel, coldPlayer, 48.0D, "cold", 200, TetoSpeech.pick(
                    coldPlayer.getRandom(), TetoSpeech.summonsOf(coldPlayer.getUUID()),
                    "hatmod.teto.cold", 2, 2));
        }

        // 长音（1:08–1:10）：头拍踩上了就一直在「按住」状态 ——
        // 每 8 刻涨一层连击（持续得分），并让光柱一直保持在涌动态（一直是粗的、吃百分比加成）。
        int hold = BrainrotBeats.holdAt(age);
        if (state != null && wearer instanceof ServerPlayer holder
                && hold >= 0
                && BrainrotBeats.scores(age)
                && state.holdHeadHit == BrainrotBeats.holdStart(hold)) {
            // 长音的效果：**头拍真踩上了**，而且**真的按住了**才算数。
            // 攒的是「按住的那几刻」（state.holdTicks），不是墙上时间 ——
            // 松手就停、再按接着攒，每攒满 HOLD_COMBO_TICKS 刻涨一层（每层 +2%）。
            // 以前走到长音里就自动涨，等于「按没按都一样」。
            if (state.holdTicksHold != hold) {
                state.holdTicksHold = hold;
                state.holdTicks = 0;
            }
            // 代打（调参器里的「自动打拍」）等于玩家一直按着：不然长音在代打里永远不涨
            boolean held = state.holding || autoTapping(holder);
            if (held) {
                state.holdTicks++;
                if (state.holdTicks % HOLD_COMBO_TICKS == 0) {
                    state.combo++;
                    state.bestCombo = Math.max(state.bestCombo, state.combo);
                    HatNetwork.sendQteState(holder, state.combo, RATING_PERFECT,
                            dodgesOf(holder.getUUID()));
                }
                if (state.surgeEnd < age + 2) {
                    state.surgeTicks = SURGE_TICKS_GOOD;
                    state.surgeEnd = age + SURGE_TICKS_GOOD;
                    state.surgeLen = SURGE_LEN_GOOD;
                    state.surgeRadius = SURGE_RADIUS_GOOD;
                }
            }
        }

        // 打拍 buff 走「短效 + 每隔 10 刻续」，断了立刻掉，不会在曲终后残留
        if (age % 10 == 0 && wearer instanceof ServerPlayer && wearer.isAlive()) {
            int combo = comboOf(wearer);
            if (combo >= COMBO_RESISTANCE) {
                wearer.addEffect(new MobEffectInstance(
                        MobEffects.DAMAGE_RESISTANCE, BUFF_TICKS, 0, true, false, true));
            }
            if (combo >= COMBO_REGENERATION) {
                wearer.addEffect(new MobEffectInstance(
                        MobEffects.REGENERATION, BUFF_TICKS, 0, true, false, true));
            }
            // 血量掉到 30% 以下：她说一句「别死」（一局一次；代打开着时静默）
            if (state != null && !silenced(wearer.getUUID())
                    && wearer.getHealth() < wearer.getMaxHealth() * 0.3F
                    && wearer.level() instanceof ServerLevel lowLevel
                    && once(wearer.getUUID(), "lowhp")) {
                TetoTalk.sayCooled(lowLevel, wearer, 48.0D, "lowhp", 200, TetoSpeech.pick(
                        wearer.getRandom(), TetoSpeech.summonsOf(wearer.getUUID()),
                        "hatmod.teto.lowhp", 1, 1));
            }
        }
    }

    /** 玩家按了一下（包来自客户端，带的是 HUD 用的那个钟走到几刻了）。 */
    static void tap(ServerPlayer player, int clientAge) {
        int serverAge = BrainrotCycle.ageOf(player);
        if (serverAge < 0) {
            return;
        }
        // 前奏：没有连击、没有回血、没有任何加成 —— 按中一拍就把周围推开，
        // 同时**无条件**回一条判定给 HUD：就算旁边一个敌人都没有，按对了也要有反馈。
        if (BrainrotBeats.inIntro(serverAge)) {
            int rating = introKnockback(player, clientAge);
            if (rating != RATING_NONE) {
                // 前奏也计分、也计判定数 —— 成就要看整首，前奏糊过去就没意义了。
                // 但前奏依旧**不给连击 / 回血 / 加成**（连击是光柱倍率，白送会超模）。
                State state = STATES.computeIfAbsent(player.getUUID(), key -> new State());
                // 前奏命中也要推进「最后一拍」：过窗判定只认 lastBeat，
                // 不推的话每一下都会在 6 刻后被判成 MISS（代打在前奏「失误」就是这个）
                int[] introOut = new int[1];
                int introIndex = BrainrotBeats.nearest(clientAge, introOut);
                state.lastBeat = Math.max(state.lastBeat, introIndex);
                state.lastHit = Math.max(state.lastHit, introIndex);
                // 前奏的长音也记「头拍踩上了」：光柱期才涨连击，这里只是让那根条亮起来
                if (isHoldHeadTick(BrainrotBeats.tickAt(introIndex))) {
                    state.holdHeadHit = BrainrotBeats.tickAt(introIndex);
                }
                state.hits++;
                if (rating == RATING_PERFECT) {
                    state.perfects++;
                } else {
                    state.goods++;
                }
                state.perfectStreak = rating == RATING_PERFECT ? state.perfectStreak + 1 : 0;
                state.score += rating == RATING_PERFECT ? SCORE_PERFECT : SCORE_GOOD;
                state.rating = rating;
                state.ratingAt = clientAge;
                HatNetwork.sendQteState(player, state.combo, state.rating,
                        dodgesOf(player.getUUID()), BrainrotBeats.tickAt(introIndex));
            }
            return;
        }
        // 压根没到计分段：按了也白按
        if (!BrainrotBeats.scores(serverAge)) {
            return;
        }
        // 顺手量一下两边时钟的偏差（平滑），MISS 判定那边也用这个数
        int rawSkew = clientAge - serverAge;
        if (Math.abs(rawSkew) <= MAX_SKEW) {
            Integer prev = SKEW.get(player.getUUID());
            SKEW.put(player.getUUID(), prev == null ? rawSkew
                    : Math.round(prev * 0.5F + rawSkew * 0.5F));
        }
        // 玩家照着 HUD 按的，就按 HUD 的钟判；只有漂到离谱（> 3 秒）才退回服务端钟
        int age = clientAge;
        if (age < 0 || Math.abs(clientAge - serverAge) > MAX_SKEW) {
            age = serverAge;
        }
        if (!BrainrotBeats.scores(age)) {
            return;
        }
        State state = STATES.computeIfAbsent(player.getUUID(), k -> new State());
        int[] out = new int[1];
        int index = BrainrotBeats.nearest(age, out);
        int delta = Math.abs(out[0]);
        // 口白三连只隔 5 刻：按「最近的一拍」判，慢一点就会被隔壁那个键抢走。
        // 先认附近「还没按过的最早一个口白键」，按中的先后可以乱，但每个键只算一次。
        int speechTick = BrainrotBeats.earliestUnhitSpeech(age, state.speechMask, GOOD_TICKS);
        boolean speechTap = speechTick >= 0;
        boolean speechOnNote = false;
        if (speechTap) {
            // 口白是写死在固定时刻的白送奖励：谱面在这一刻有键就顺手打掉，
            // 没有就只给口白自己的奖励，别去吃掉三十多刻外那个键（会把它白判成 MISS）。
            index = BrainrotBeats.nearest(speechTick, out);
            speechOnNote = Math.abs(BrainrotBeats.tickAt(index) - speechTick) <= GOOD_TICKS + 2;
            delta = 0;
        }
        if (!speechTap) {
            // 最近那一拍已经按过了，而这一下已经进了**下一拍**的窗：连点比谱面间距还快，
            // 这一下是在抢下一拍。吞掉的话下一拍就会白跑一趟、过窗判 MISS。
            if (index <= state.lastHit) {
                int ahead = state.lastHit + 1;
                if (ahead < BrainrotBeats.count()) {
                    int aheadDelta = age - BrainrotBeats.tickAt(ahead);
                    if (-aheadDelta <= BrainrotBeats.windowAt(ahead)
                            && aheadDelta <= BrainrotBeats.lateWindowAt(ahead)) {
                        index = ahead;
                        // 和 nearest 同一个约定：out[0] = 拍点 - age
                        out[0] = -aheadDelta;
                    }
                }
            }
            // 完全打偏：不记 MISS（MISS 只由过窗判定），但也白按。
            // 窗口按这一拍自己的邻居间距算；**晚按那一侧多给 LATE_GRACE 刻**。
            int offset = -out[0];
            if (-offset > BrainrotBeats.windowAt(index)
                    || offset > BrainrotBeats.lateWindowAt(index)) {
                return;
            }
            // 这一拍已经按中过：连点不重复计
            if (index <= state.lastHit) {
                return;
            }
            // 比最后一拍还早：那是更早的拍，早就过去了，白按
            if (index < state.lastBeat) {
                return;
            }
            // 长音的头拍：过窗就作废 —— 失去不补。条子会暗下去，
            // 也不能靠「晚了再补一下」让整根条重新变成有效（那样连击就白赚）。
            if (index <= state.lastBeat && isHoldHeadTick(BrainrotBeats.tickAt(index))) {
                return;
            }
            // 晚按补判：这一拍刚刚才被判过 MISS（就差这一两刻），按回来就把它撤掉 ——
            // MISS 不扣分、连击接回去、面板改记成命中。
            if (index == state.lastMissed && state.lastMissedAt >= 0
                    && age - state.lastMissedAt <= BrainrotBeats.LATE_GRACE + 2
                    && !isHoldHeadTick(BrainrotBeats.tickAt(index))) {
                state.lastMissed = -1;
                state.lastMissedAt = -1;
                if (state.misses > 0) {
                    state.misses--;
                }
                if (state.missStreak > 0) {
                    state.missStreak--;
                }
                state.combo = Math.max(state.combo, state.comboBeforeMiss);
            }
            delta = Math.abs(out[0]);
        }
        // 落在最后一拍上（它已经被过窗判成 MISS 了）：放行，补记成命中。
        // 玩家只是慢了半拍、或者两边时钟差了几刻 —— 不放行的话整首都会按不上。
        // 纯白送的口白不推进谱面进度，否则会把后面的键提前吃掉
        if (!speechTap || speechOnNote) {
            state.lastBeat = Math.max(state.lastBeat, index);
            state.lastHit = Math.max(state.lastHit, index);
        }
        state.missStreak = 0;
        // 长音的头拍：这一刻**真踩上了**才允许后面那段「按住」继续涨连击
        int holdHead = BrainrotBeats.holdAt(BrainrotBeats.tickAt(index));
        if (holdHead >= 0 && BrainrotBeats.holdStart(holdHead) == BrainrotBeats.tickAt(index)) {
            state.holdHeadHit = BrainrotBeats.tickAt(index);
        }
        if (speechTap) {
            state.speechMask |= 1 << BrainrotBeats.speechBit(speechTick);
        }
        // 「NOPE / too / late」那三个白键：按到就算 PERFECT，不看偏移
        boolean perfect = speechTap || BrainrotBeats.isSpeechKey(BrainrotBeats.tickAt(index))
                || delta <= Math.min(PERFECT_TICKS, BrainrotBeats.windowAt(index));
        // 连击**一拍只涨一层**：PERFECT 以前涨 2 层，等于凭空多报一层 —— 面板上的倍率、
        // 曲终的连击数都是虚的。现在只看「这一拍踩上没有」。
        state.combo++;
        state.hits++;
        if (perfect) {
            state.perfects++;
        } else {
            state.goods++;
        }
        state.perfectStreak = perfect ? state.perfectStreak + 1 : 0;
        state.lastHitAt = age;
        state.bestCombo = Math.max(state.bestCombo, state.combo);
        // 踩中的这一拍如果是「那一声」，就把声波放出去（见 BrainrotCycle.onNoteHit）
        BrainrotCycle.onNoteHit(player, BrainrotBeats.tickAt(index));
        // 连击里程碑：每 50 连她说一句，同一种话 5 秒内不重复
        if (!silenced(player.getUUID())
                && state.combo > 0 && state.combo % 100 == 0
                && player.level() instanceof ServerLevel talkLevel) {
            TetoTalk.sayCooled(talkLevel, player, 48.0D, "combo", 100, TetoSpeech.pick(
                    player.getRandom(), TetoSpeech.summonsOf(player.getUUID()),
                    "hatmod.teto.combo", 2, 2), state.combo);
        }
        // 连续 50 拍全 PERFECT（一局一次）：纯手感的夸法，和连击里程碑分开
        if (!silenced(player.getUUID()) && state.perfectStreak >= 50
                && player.level() instanceof ServerLevel cleanLevel
                && once(player.getUUID(), "clean")) {
            TetoTalk.sayCooled(cleanLevel, player, 48.0D, "clean", 200, TetoSpeech.pick(
                    player.getRandom(), TetoSpeech.summonsOf(player.getUUID()),
                    "hatmod.teto.clean", 2, 1));
        }
        // 得分：PERFECT 100 / GOOD 50；MISS 不扣分，只记数（曲终评价用）
        state.score += perfect ? SCORE_PERFECT : SCORE_GOOD;
        state.rating = perfect ? RATING_PERFECT : RATING_GOOD;
        state.ratingAt = age;
        // 打中一拍：光柱「涌动」一下 —— 瞬间更长更粗，随剩余时间缩回去
        state.surgeTicks = perfect ? SURGE_TICKS_PERFECT : SURGE_TICKS_GOOD;
        state.surgeEnd = age + state.surgeTicks;
        state.surgeLen = perfect ? SURGE_LEN_PERFECT : SURGE_LEN_GOOD;
        state.surgeRadius = perfect ? SURGE_RADIUS_PERFECT : SURGE_RADIUS_GOOD;
        // 节拍回血（自保）：**每一拍**都回一小口 —— GOOD 半颗心、PERFECT 一颗心。
        // 每拍都回的用意是「节奏稳住就死不了」，但每口都很小，不会盖过 Boss 的输出。
        if (player.isAlive()) {
            player.heal(perfect ? HEAL_PERFECT : HEAL_GOOD);
        }
        // 连击奖励：每 10 连击攒一次「闪避」（黑帽同款），最多 3 次
        int need = NEXT_DODGE_AT.getOrDefault(player.getUUID(), COMBO_PER_DODGE);
        if (state.combo >= need) {
            NEXT_DODGE_AT.put(player.getUUID(), need + COMBO_PER_DODGE);
            // 存满了就不再给：连击攒的和打拍计数攒的共用同一个上限
            int held = dodgesOf(player.getUUID());
            if (held < MAX_DODGES) {
                DODGES.put(player.getUUID(), held + 1);
            }
        }
        // 每 10 连击：四只 Teto 各回一口血
        if (state.combo % COMBO_PER_HEAL == 0) {
            healTetos(player);
        }
        // 光柱期间踩上一拍：攒闪避的计数 +1（伴奏层和主旋律共用同一个 0/10）
        if (BrainrotBeats.inBeam(age)) {
            addDodgeProgress(player, state);
        }
        HatNetwork.sendQteState(player, state.combo, state.rating,
                dodgesOf(player.getUUID()), BrainrotBeats.tickAt(index));
    }

    /**
     * 客户端报「打拍键 / 右键按下去了没有」（只在变化时发一次）。
     *
     * <p>长音不再按墙上时间自动涨连击：只有**按住**的那几刻才算数，
     * 松手就停、再按接着攒。HUD 上「过条变黄」的长度用的也是这个数。
     */
    static void setHolding(ServerPlayer player, boolean holding) {
        State state = STATES.computeIfAbsent(player.getUUID(), key -> new State());
        state.holding = holding;
    }

    /** 这一拍是不是某根长音的头拍。 */
    private static boolean isHoldHeadTick(int tick) {
        int hold = BrainrotBeats.holdAt(tick);
        return hold >= 0 && BrainrotBeats.holdStart(hold) == tick;
    }

    /**
     * 前奏的击退：**按中一拍**就把周围的敌人从玩家身上推开（带一点上抛），按空了什么也不发生。
     *
     * <p>前奏不给任何加成，所以这是那 21 秒里唯一的进攻手段 —— 节奏踩得越顺，清得越快。
     *
     * @return 判定结果（{@link #RATING_PERFECT} / {@link #RATING_GOOD} / {@link #RATING_NONE}），
     *         用来给 HUD 回一条反馈；附近没敌人也照回，不能「按了像没按」。
     */
    private static int introKnockback(ServerPlayer player, int age) {
        int[] out = new int[1];
        int index = BrainrotBeats.nearest(age, out);
        int delta = Math.abs(out[0]);
        // 前奏同样「晚按多给几刻」：手慢半拍不该白推
        // （out[0] > 0 = 还没到 / 早按，< 0 = 已经过了 / 晚按）
        if (out[0] > BrainrotBeats.windowAt(index)
                || -out[0] > BrainrotBeats.lateWindowAt(index)) {
            return RATING_NONE;
        }
        // 同一拍只推一次：判定窗有好几刻，连点不该连着推
        Integer lastIndex = INTRO_LAST_HIT.get(player.getUUID());
        if (lastIndex != null && lastIndex >= index) {
            return RATING_NONE;
        }
        INTRO_LAST_HIT.put(player.getUUID(), index);
        if (!(player.level() instanceof ServerLevel level)) {
            return RATING_NONE;
        }
        Vec3 center = player.position();
        boolean any = false;
        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class,
                player.getBoundingBox().inflate(INTRO_PUSH_RADIUS))) {
            if (target == player || target instanceof Teto || target instanceof HatSummon) {
                continue;
            }
            if (!HatAbilities.isEnemy(target, player)) {
                continue;
            }
            Vec3 away = target.position().subtract(center);
            if (away.lengthSqr() < 1.0E-4D) {
                away = new Vec3(0.0D, 1.0D, 0.0D);
            }
            away = away.normalize().scale(INTRO_PUSH_POWER);
            target.push(away.x, INTRO_PUSH_LIFT, away.z);
            target.hurtMarked = true;
            any = true;
        }
        // 踩中的反馈：身边一定冒音符；推到了敌人再叠一圈烟。
        // 这条和「有没有敌人」无关 —— 前奏按拍本来就该有音游的打击感。
        level.sendParticles(ParticleTypes.NOTE,
                center.x, center.y + 1.15D, center.z, 7, 0.75D, 0.35D, 0.75D, 0.08D);
        if (any) {
            level.sendParticles(ParticleTypes.CLOUD,
                    center.x, center.y + 0.9D, center.z, 18, 1.4D, 0.4D, 1.4D, 0.05D);
        }
        return delta <= Math.min(PERFECT_TICKS, BrainrotBeats.windowAt(index))
                ? RATING_PERFECT : RATING_GOOD;
    }

    /** 前奏里每个玩家最后推过的那一拍（同一拍不重复推）。 */
    private static final Map<UUID, Integer> INTRO_LAST_HIT = new HashMap<>();

    /** 前奏击退：半径（格）。 */
    private static final double INTRO_PUSH_RADIUS = 10.0D;
    /** 前奏击退：水平速度（格/刻）。 */
    private static final double INTRO_PUSH_POWER = 1.15D;
    /** 前奏击退：上抛分量。 */
    private static final double INTRO_PUSH_LIFT = 0.35D;

    /** 当前的光柱伤害倍率（1.0 = 没连击；每层 +0.4%，不封顶）。 */
    static float damageMultiplier(LivingEntity wearer) {
        int combo = comboOf(wearer);
        return combo <= 0 ? 1.0F : 1.0F + BONUS_PER_COMBO * combo;
    }

    /**
     * 打中一拍后的「涌动」：光柱此刻的长度 / 粗细倍率。
     *
     * <p>{@code out[0]} = 长度倍率、{@code out[1]} = 粗细倍率，没在涌动时都是 1.0。
     * 倍率随剩余时间从峰值线性缩回 1 —— 打中的那一下最粗，然后 0.3~0.5 秒收回去。
     */
    static void surgeScale(LivingEntity wearer, int age, float[] out) {
        out[0] = 1.0F;
        out[1] = 1.0F;
        State state = STATES.get(wearer.getUUID());
        if (state == null || age < 0 || age > state.surgeEnd) {
            return;
        }
        float amount = state.surgeTicks <= 0
                ? 0.0F : (float) (state.surgeEnd - age) / state.surgeTicks;
        amount = Math.max(0.0F, Math.min(1.0F, amount));
        out[0] = 1.0F + (state.surgeLen - 1.0F) * amount;
        out[1] = 1.0F + (state.surgeRadius - 1.0F) * amount;
    }

    static int comboOf(LivingEntity wearer) {
        State state = STATES.get(wearer.getUUID());
        return state == null ? 0 : state.combo;
    }

    /** 曲终 / 摘帽：连击和闪避一起清零。 */
    static void clear(LivingEntity wearer) {
        ROUNDS.remove(wearer.getUUID());
        STATES.remove(wearer.getUUID());
        DODGES.remove(wearer.getUUID());
        NEXT_DODGE_AT.remove(wearer.getUUID());
        SKEW.remove(wearer.getUUID());
        INTRO_LAST_HIT.remove(wearer.getUUID());
        SAID.remove(wearer.getUUID());
    }

    /**
     * 每个玩家「客户端钟 - 服务端钟」的估计（刻）。
     *
     * <p>玩家是听着音乐、照着 HUD 按的，可 HUD 用的是客户端自己的钟（音乐起播那一刻立的）。
     * 两个钟只要差几刻，判定就会整首偏 —— 表现是「看着踩上了却一直 MISS / 按不上」。
     * 打拍时顺手把偏差量出来（平滑一下），MISS 判定和按键判定都用它对齐。
     */
    private static final Map<UUID, Integer> SKEW = new HashMap<>();
    /** 允许的最大偏差（刻）：再大就认为客户端在瞎报，老老实实按服务端钟判。 */
    private static final int MAX_SKEW = 60;

    /** 这台客户端当前的时钟偏差（还没量出来就是 0）。 */
    private static int skewOf(UUID id) {
        Integer skew = SKEW.get(id);
        return skew == null ? 0 : skew;
    }

    /**
     * 链路起手：记下这一轮是不是「接敌」开的，并把打拍状态提前建好。
     *
     * <p>状态必须提前建：一拍都不按的时候，MISS 判定走不到（它挂在 {@code State} 上），
     * 于是「全程一首都没按」会被误判成满分。
     */
    static void beginRound(LivingEntity wearer, boolean natural) {
        ROUNDS.put(wearer.getUUID(), new Round(natural));
        State state = STATES.computeIfAbsent(wearer.getUUID(), key -> new State());
        // 新的一轮：打拍计数清零
        state.acc = 0;
        state.accLast = -1;
        state.holdHeadHit = Integer.MIN_VALUE;
        state.holding = false;
        state.holdTicks = 0;
        state.holdTicksHold = -1;
        state.score = 0;
        state.misses = 0;
        state.hits = 0;
        state.perfects = 0;
        state.goods = 0;
        state.bestCombo = 0;
        state.perfectStreak = 0;
        state.lastHitAt = -1;
        SAID.remove(wearer.getUUID());
        // 代打开局：先把「这局不算数」说在前头（免得有人拿代打的成绩当战果）
        if (autoTapping(wearer) && wearer instanceof ServerPlayer autoPlayer
                && wearer.level() instanceof ServerLevel autoLevel
                && once(wearer.getUUID(), "auto")) {
            TetoTalk.sayCooled(autoLevel, autoPlayer, 48.0D, "auto", 200, "hatmod.teto.auto.1");
        }
    }

    /**
     * 采样「周边还有没有敌人」：链路每 5 刻问一次，任意一次落空这一轮就作废。
     *
     * <p>和起手判断用的是同一个 {@code HatAbilities.findNearestEnemy}（射程内 + 最近打过我的），
     * 所以「引一群怪开场、后面空场刷拍子」这种打法拿不到成就。
     */
    static void noteCombat(LivingEntity wearer, boolean enemyNearby) {
        Round round = ROUNDS.get(wearer.getUUID());
        if (round != null && !enemyNearby) {
            round.inCombat = false;
        }
    }

    /**
     * 曲终：先让 Teto 说一句评价（按得分 / 连击峰值 / MISS 数），够条件再把「节奏大师」发出去。
     */
    static void finishRound(LivingEntity wearer) {
        Round round = ROUNDS.remove(wearer.getUUID());
        if (!(wearer instanceof ServerPlayer player)) {
            return;
        }
        State state = STATES.get(player.getUUID());
        if (state != null) {
            sayEvaluation(player, state);
        }
        // 打完一轮就记进「玩过」名单（落盘）：下一轮她才可以提「上一次」
        HatSettings.markBrainrotPlayed(player.getUUID());
        if (round == null || !round.natural || round.comboBroken || !round.inCombat || round.assisted) {
            return;
        }
        if (HatAchievements.grant(player, HatAchievements.RHYTHM_MASTER)) {
            // 刚拿到「节奏大师」：她在散场后补一句（走聊天栏，所以人走了话还留得住）
            TetoTalk.sayTo(player, TetoTalk.pick(player.serverLevel().random,
                    "hatmod.teto.achv.rhythm", 2));
        }
    }

    /**
     * Teto 的曲终评价：按总分分四档，把「得分 / 最高连击 / MISS」一起念出来。
     *
     * <p>这句说完她就消失了（{@code BrainrotCycle.end} 紧跟着收掉 Teto），
     * 所以它是这一轮的最后一句台词。
     */
    private static void sayEvaluation(ServerPlayer player, State state) {
        int score = state.score;
        int best = state.bestCombo;
        int misses = state.misses;
        // 代打开过（已经说过「这局不算数」）：走特殊结算，不报分数档次
        if (silenced(player.getUUID())) {
            TetoTalk.sayTo(player, "hatmod.teto.end.assist.1", score, best, misses);
            return;
        }
        String tier;
        if (misses == 0 && score >= SCORE_S) {
            tier = "s";
        } else if (score >= SCORE_A) {
            tier = "a";
        } else if (score >= SCORE_B) {
            tier = "b";
        } else {
            tier = "c";
        }
        // S 档两条、其余三选一
        String key = TetoSpeech.pick(player.getRandom(), TetoSpeech.summonsOf(player.getUUID()),
                "hatmod.teto.end." + tier, tier.equals("s") ? 2 : 3, 2);
        // 「你的手比上一次听话了」只对老玩家说 —— 第一次玩的人没有「上一次」
        if ("hatmod.teto.end.a.3".equals(key) && !HatSettings.brainrotPlayed(player.getUUID())) {
            key = "hatmod.teto.end.a.1";
        }
        TetoTalk.sayTo(player, key, score, best, misses);
    }

    /**
     * 「一局最多说一次」的台词：玩家 -> 这一轮已经说过的标记。
     *
     * <p>躲闪夸奖、低血量提醒、代打声明……都走 {@link #once}；新的一轮在
     * {@link #beginRound} 清空，摘帽 / 曲终在 {@link #clear} 清空。
     */
    private static final Map<UUID, java.util.Set<String>> SAID = new HashMap<>();

    /** 这一轮里这个标记是不是第一次出现（是 = 该说话了）。 */
    private static boolean once(UUID id, String tag) {
        return SAID.computeIfAbsent(id, key -> new java.util.HashSet<>()).add(tag);
    }

    /**
     * 这一轮已经声明过「这局不算数」：**所有打拍台词全部静默**，曲终走特殊结算。
     *
     * <p>用「说过 auto」当标记，而不是 {@code autoTapping()}：中途关掉代打也照样不算数，
     * 不然玩家会以为「关掉就洗干净了」。
     */
    private static boolean silenced(UUID id) {
        java.util.Set<String> said = SAID.get(id);
        return said != null && said.contains("auto");
    }

    /** 「自动打拍」调试模式：开着的玩家由服务端替他踩拍，用过的一轮不发成就。 */
    private static final java.util.Set<UUID> AUTO_TAP = new java.util.HashSet<>();

    static void setAutoTap(ServerPlayer player, boolean on) {
        if (on) {
            AUTO_TAP.add(player.getUUID());
        } else {
            AUTO_TAP.remove(player.getUUID());
        }
        // 中途代打开：同样先声明「这局不算数」（开局的那个在 beginRound）
        if (on && STATES.containsKey(player.getUUID())
                && player.level() instanceof ServerLevel level
                && once(player.getUUID(), "auto")) {
            TetoTalk.sayCooled(level, player, 48.0D, "auto", 200, "hatmod.teto.auto.1");
        }
        // 落盘：退出游戏 / 服务器重启之后这个调试开关还开着（见 HatSettings#setDebugAutoTap）
        HatSettings.setDebugAutoTap(player.getUUID(), on);
    }

    static boolean autoTapping(LivingEntity wearer) {
        UUID id = wearer.getUUID();
        if (!AUTO_TAP.contains(id) && HatSettings.debugAutoTap(id)) {
            // 上次退出前开着：第一次用到时从配置里恢复（进服时的同步包只负责界面显示）
            AUTO_TAP.add(id);
        }
        return AUTO_TAP.contains(id);
    }

    /**
     * 自动打拍：正好踏在拍点上的那一刻替玩家按一下（调参器里的调试开关）。
     *
     * <p>走的是和手动按键完全一样的判定，只是把「准不准」这件事交给服务端 ——
     * 所以这一轮的连击会一直不断；因此 {@code Round#assisted} 会记一笔，曲终不发成就。
     */
    static void autoTap(ServerPlayer player, int age) {
        // 前奏也交给自动打拍：tap() 在里面走的是击退链路，本来就不计分
        if (!BrainrotBeats.scores(age) && !BrainrotBeats.inIntro(age)) {
            return;
        }
        int[] out = new int[1];
        BrainrotBeats.nearest(age, out);
        if (out[0] != 0) {
            return;
        }
        Round round = ROUNDS.get(player.getUUID());
        // 只在前奏之外记「代打」：前奏那几下不计分，不算偷懒
        if (round != null && BrainrotBeats.scores(age)) {
            round.assisted = true;
        }
        tap(player, age);
    }

    /**
     * 「Nope / too / late」三拍**由服务端代按**。
     *
     * <p>走的是和手动按键完全一样的判定（所以连击照涨、也算 PERFECT、该回的血照回），
     * 但**不记** {@code Round#assisted} —— 这一下不是玩家偷懒，是这三拍本来就不该他摁，
     * 不能因此把整轮的「节奏大师」作废。
     */
    static void autoTapSpeech(ServerPlayer player, int age) {
        if (!BrainrotBeats.isSpeechKey(age) || !player.isAlive()) {
            return;
        }
        tap(player, age);
    }

    /**
     * Khragg 的「默认打满拍」：复活返场后由服务端替他踩拍。
     *
     * <p>只推进连击 / 得分这些机制层 —— 没有玩家的 HUD 要回包，也不说代打台词、
     * 不发成就。走的还是同一个谱面钟，所以连击倍率照常折进他的光柱伤害。
     */
    static void autoTapMob(LivingEntity wearer, int age) {
        boolean scoring = BrainrotBeats.scores(age);
        if (!scoring && !BrainrotBeats.inIntro(age)) {
            return;
        }
        int[] out = new int[1];
        int index = BrainrotBeats.nearest(age, out);
        if (out[0] != 0) {
            return;
        }
        State state = STATES.computeIfAbsent(wearer.getUUID(), key -> new State());
        int tick = BrainrotBeats.tickAt(index);
        state.lastBeat = Math.max(state.lastBeat, index);
        state.lastHit = Math.max(state.lastHit, index);
        int holdHead = BrainrotBeats.holdAt(tick);
        if (holdHead >= 0 && BrainrotBeats.holdStart(holdHead) == tick) {
            state.holdHeadHit = tick;
        }
        state.missStreak = 0;
        state.combo++;
        state.hits++;
        state.perfects++;
        state.perfectStreak++;
        state.lastHitAt = age;
        state.bestCombo = Math.max(state.bestCombo, state.combo);
        state.rating = RATING_PERFECT;
        state.ratingAt = age;
        if (BrainrotBeats.inBeam(age)) {
            state.surgeTicks = SURGE_TICKS_PERFECT;
            state.surgeEnd = age + state.surgeTicks;
            state.surgeLen = SURGE_LEN_PERFECT;
            state.surgeRadius = SURGE_RADIUS_PERFECT;
        }
        if (wearer.isAlive()) {
            wearer.heal(HEAL_PERFECT);
        }
    }

    /** 当前存着几次闪避（同步给 HUD 显示）。 */
    private static int dodgesOf(UUID id) {
        Integer charges = DODGES.get(id);
        return charges == null ? 0 : charges;
    }

    /** 当前攒闪避的打拍计数（0..BONUS_PER_DODGE），随状态包一起发。 */
    static int accOf(UUID id) {
        State state = STATES.get(id);
        return state == null ? 0 : state.acc;
    }

    /** 本轮得分（随状态包一起发，给奖励面板用）。 */
    static int scoreOf(UUID id) {
        State state = STATES.get(id);
        return state == null ? 0 : state.score;
    }

    /** 本轮 MISS 数（随状态包一起发）。 */
    static int missesOf(UUID id) {
        State state = STATES.get(id);
        return state == null ? 0 : state.misses;
    }

    /** 本轮踩对了几拍（随状态包一起发）。 */
    static int hitsOf(UUID id) {
        State state = STATES.get(id);
        return state == null ? 0 : state.hits;
    }

    /** 本轮 PERFECT 数（随状态包一起发）。 */
    static int perfectsOf(UUID id) {
        State state = STATES.get(id);
        return state == null ? 0 : state.perfects;
    }

    /** 本轮 GOOD 数（随状态包一起发）。 */
    static int goodsOf(UUID id) {
        State state = STATES.get(id);
        return state == null ? 0 : state.goods;
    }

    /** 吃掉一次闪避（挨打前调）：还有次数就返回 true，这一击应当完全落空。 */
    static boolean consumeDodge(LivingEntity living) {
        Integer charges = DODGES.get(living.getUUID());
        if (charges == null || charges <= 0) {
            return false;
        }
        int left = charges - 1;
        DODGES.put(living.getUUID(), left);
        // 立刻把剩下的次数推给 HUD：否则奖励面板要等到下一拍才更新，看着像没消耗
        State state = STATES.get(living.getUUID());
        if (living instanceof ServerPlayer player && state != null) {
            HatNetwork.sendQteState(player, state.combo, state.rating, left);
            // 攒到次数不算数：这一刻**真的躲掉一击**才值得夸，而且一局只夸一次。
            // 这句**不受**「这局不算数」静默：闪避是真挨打换来的，代打演不出来。
            if (player.level() instanceof ServerLevel level
                    && once(player.getUUID(), "dodge")) {
                TetoTalk.sayCooled(level, player, 48.0D, "dodge", 200, TetoSpeech.pick(
                        player.getRandom(), TetoSpeech.summonsOf(player.getUUID()),
                        "hatmod.teto.dodge", 3, 2));
            }
        }
        return true;
    }

    /** 里程碑奖励：这一轮的四只 Teto 各回 5% 最大生命，飘心给他们作证。 */
    private static void healTetos(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        // 同样走 UUID 名册（实体查询被换掉时 getEntitiesOfClass 会是空的）
        for (Teto teto : Teto.ownedBy(player)) {
            if (teto.isDeadOrDying()) {
                continue;
            }
            teto.heal(teto.getMaxHealth() * TETO_HEAL_RATIO);
            level.sendParticles(ParticleTypes.HEART,
                    teto.getX(), teto.getEyeY() + 0.4D, teto.getZ(),
                    5, teto.getBbWidth() * 0.4D, 0.3D, teto.getBbWidth() * 0.4D, 0.0D);
        }
    }
}