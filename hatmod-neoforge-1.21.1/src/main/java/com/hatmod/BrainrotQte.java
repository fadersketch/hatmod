package com.hatmod;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;

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
 *   <li>某拍过窗还没人按 = MISS：连击 −1，**连续 3 次 MISS 清零**；</li>
 *   <li>连击每 +1，光柱伤害 +2%（无上限；PERFECT 一次涨 2 层），固定伤害和百分比一起吃；</li>
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
    /** 连续多少次 MISS 把连击清零。 */
    private static final int MISS_STREAK_RESET = 3;
    /**
     * 每层连击加的伤害百分比（0.02 = +2%）。
     *
     * <p>**没有上限**：连得越久越强，打满一整首能堆到几十倍；
     * 但每层只有 +2%，涨得很慢 —— 上限交给操作时长去决定，不写死。
     * 这个倍率同时作用在固定伤害和百分比伤害上（见 BrainrotCycle.fire）。
     */
    private static final float BONUS_PER_COMBO = 0.02F;
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
    }

    private static final Map<UUID, State> STATES = new HashMap<>();

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
    /** 每多少连击攒一次闪避、最多存几次。 */
    private static final int COMBO_PER_DODGE = 10;
    private static final int MAX_DODGES = 3;

    private BrainrotQte() {
    }

    /**
     * 每刻跑一次：把「过窗还没按」的拍记成 MISS，并按连击续 buff。
     *
     * <p>MISS 判定用服务端自己的 {@code age}（和时间线同一个时钟）；玩家按键那一下
     * 用的是客户端报上来的 {@code age}（玩家照着 HUD 按的，就该按 HUD 的钟判）。
     */
    static void tick(ServerLevel level, LivingEntity wearer, int age) {
        // 判定用「服务端钟 + 客户端偏差」：和玩家按键那一下用同一个钟，
        // 才不会出现「按的明明踩上了、服务端却判 MISS」
        int judgeAge = age + skewOf(wearer.getUUID());
        State state = STATES.get(wearer.getUUID());
        if (state != null) {
            int[] out = new int[1];
            int index = BrainrotBeats.nearest(judgeAge, out);
            // 最近的一拍还没到（out>0）时，该检查的是它前面那一拍过窗了没有
            int judged = out[0] > 0 ? index - 1 : index;
            if (judged > state.lastBeat
                    && judgeAge > BrainrotBeats.tickAt(judged) + GOOD_TICKS) {
                state.lastBeat = judged;
                // 前奏和收尾不计分：拍照样跳过去，但不罚
                if (BrainrotBeats.scores(judgeAge)) {
                    state.missStreak++;
                    state.combo = Math.max(0, state.combo - 1);
                    if (state.missStreak >= MISS_STREAK_RESET) {
                        state.combo = 0;
                    }
                    // 「节奏大师」：连击掉到 0 就算断了
                    Round round = ROUNDS.get(wearer.getUUID());
                    if (round != null && state.combo <= 0) {
                        round.comboBroken = true;
                    }
                    state.rating = RATING_MISS;
                    state.ratingAt = age;
                    if (wearer instanceof ServerPlayer player) {
                        HatNetwork.sendQteState(player, state.combo, state.rating, dodgesOf(player.getUUID()));
                    }
                }
            }
        }

        // 长音（1:08–1:10）：头拍踩上了就一直在「按住」状态 ——
        // 每 8 刻涨一层连击（持续得分），并让光柱一直保持在涌动态（一直是粗的、吃百分比加成）。
        int hold = BrainrotBeats.holdAt(age);
        if (state != null && wearer instanceof ServerPlayer holder
                && hold >= 0
                && state.lastBeat >= BrainrotBeats.holdHeadIndex(hold)) {
            // 长音的效果：**每刻**都在涨攻击倍率（每层 +2%），按住多久就涨多久
            state.combo++;
            if (age % 4 == 0) {
                HatNetwork.sendQteState(holder, state.combo, RATING_PERFECT, dodgesOf(holder.getUUID()));
            }
            if (state.surgeEnd < age + 2) {
                state.surgeTicks = SURGE_TICKS_GOOD;
                state.surgeEnd = age + SURGE_TICKS_GOOD;
                state.surgeLen = SURGE_LEN_GOOD;
                state.surgeRadius = SURGE_RADIUS_GOOD;
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
        }
    }

    /** 玩家按了一下（包来自客户端，带的是 HUD 用的那个钟走到几刻了）。 */
    static void tap(ServerPlayer player, int clientAge) {
        int serverAge = BrainrotCycle.ageOf(player);
        // 曲子没在放，或者压根没到计分段：按了也白按
        if (serverAge < 0 || !BrainrotBeats.scores(serverAge)) {
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
        if (speechTap) {
            index = BrainrotBeats.nearest(speechTick, out);
            delta = 0;
        } else {
            // 完全打偏：不记 MISS（MISS 只由过窗判定），但也白按
            if (delta > GOOD_TICKS) {
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
        }
        // 落在最后一拍上（它已经被过窗判成 MISS 了）：放行，补记成命中。
        // 玩家只是慢了半拍、或者两边时钟差了几刻 —— 不放行的话整首都会按不上。
        state.lastBeat = Math.max(state.lastBeat, index);
        state.lastHit = Math.max(state.lastHit, index);
        state.missStreak = 0;
        if (speechTap) {
            state.speechMask |= 1 << BrainrotBeats.speechBit(speechTick);
        }
        // 「NOPE / too / late」那三个白键：按到就算 PERFECT，不看偏移
        boolean perfect = speechTap || BrainrotBeats.isSpeechKey(BrainrotBeats.tickAt(index))
                || delta <= PERFECT_TICKS;
        // PERFECT 一次涨 2 层：踩得准的话连击和伤害都起得更快（不用等 30 拍才成型）
        state.combo += perfect ? 2 : 1;
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
            DODGES.merge(player.getUUID(), 1, (a, b) -> Math.min(MAX_DODGES, a + b));
        }
        // 每 10 连击：四只 Teto 各回一口血
        if (state.combo % COMBO_PER_HEAL == 0) {
            healTetos(player);
        }
        HatNetwork.sendQteState(player, state.combo, state.rating, dodgesOf(player.getUUID()));
    }

    /** 当前的光柱伤害倍率（1.0 = 没连击；**不封顶**，每层 +2%）。 */
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
        STATES.computeIfAbsent(wearer.getUUID(), key -> new State());
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

    /** 曲终：够条件就把「节奏大师」发给戴帽者。 */
    static void finishRound(LivingEntity wearer) {
        Round round = ROUNDS.remove(wearer.getUUID());
        if (round == null || !(wearer instanceof ServerPlayer player)) {
            return;
        }
        if (!round.natural || round.comboBroken || !round.inCombat || round.assisted) {
            return;
        }
        HatAchievements.grant(player, HatAchievements.RHYTHM_MASTER);
    }

    /** 「自动打拍」调试模式：开着的玩家由服务端替他踩拍，用过的一轮不发成就。 */
    private static final java.util.Set<UUID> AUTO_TAP = new java.util.HashSet<>();

    static void setAutoTap(ServerPlayer player, boolean on) {
        if (on) {
            AUTO_TAP.add(player.getUUID());
        } else {
            AUTO_TAP.remove(player.getUUID());
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
        if (!BrainrotBeats.scores(age)) {
            return;
        }
        int[] out = new int[1];
        BrainrotBeats.nearest(age, out);
        if (out[0] != 0) {
            return;
        }
        Round round = ROUNDS.get(player.getUUID());
        if (round != null) {
            round.assisted = true;
        }
        tap(player, age);
    }

    /** 当前存着几次闪避（同步给 HUD 显示）。 */
    private static int dodgesOf(UUID id) {
        Integer charges = DODGES.get(id);
        return charges == null ? 0 : charges;
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
        }
        return true;
    }

    /** 里程碑奖励：这一轮的四只 Teto 各回 5% 最大生命，飘心给他们作证。 */
    private static void healTetos(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        for (Teto teto : level.getEntitiesOfClass(Teto.class,
                player.getBoundingBox().inflate(128.0D), t -> t.isOwnedBy(player))) {
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