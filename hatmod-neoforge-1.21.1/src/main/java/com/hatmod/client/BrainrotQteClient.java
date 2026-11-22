package com.hatmod.client;

import com.hatmod.BrainrotBeats;
import com.hatmod.HatMusic;
import com.hatmod.HatNetwork;
import net.minecraft.client.Minecraft;

/**
 * 打拍的客户端那一半：**本地玩家自己的**脑蚀 BGM 走到几刻了、连击多少。
 *
 * <p>钟的来源是 {@link HatMusicPlayer#ticks()}：服务端在时间线 age=0 的那一刻发
 * 「BGM 开始」包，客户端收到就起播 —— 所以 {@code ticks() - startTick} 就是
 * 「音乐本身的钟」，拿它对 {@link BrainrotBeats} 的拍点，HUD 和耳朵听到的是同一个时间。
 * 服务端判定用的也是客户端报的这个钟（打拍包带上它），玩家照着 HUD 按、就按 HUD 的钟算，
 * 网络延迟只会让连击晚一点到账，不会让「看着踩上了却判 MISS」。
 *
 * <p>连击 / 判定数值本身**只存在服务端**（{@code BrainrotQte}），这里只是收它的回包
 * 给 HUD 画 —— 和绿心同一套做法。打中一拍的「涌动」（光柱瞬间更长更粗）也一样：
 * 服务端按自己的钟撑大判定，这里按回包撑大渲染，两边同一个曲线。
 */
public final class BrainrotQteClient {

    /** 本地玩家的脑蚀 BGM 起播那一刻（音乐刻）；{@link Integer#MIN_VALUE} = 没在放。 */
    private static int startTick = Integer.MIN_VALUE;

    /**
     * 谱子的钟：按**真实时间**走（见 {@link #advanceClock()}），不跟游戏刻。
     *
     * <p>{@code clockNanos} 是上一次推进的时刻（0 = 还没立钟），{@code clockTicks} 是
     * 「BGM 已经放了多少刻」。之所以不用 {@code HatMusicPlayer.ticks() - startTick}：
     * 那个是游戏刻，实体一多、帧率一掉就跑得比真实时间慢，而 BGM 是按真实时间放的。
     */
    private static long clockNanos;
    private static double clockTicks;
    /**
     * 还在等「声音真的响起来」。
     *
     * <p>重进游戏后第一次放歌时，声音引擎要先解析 / 加载音频，真正出声比包到达晚几十甚至
     * 上百毫秒 —— 以前谱面钟在包到达那一刻就走，于是第一段链路听起来就是「键比歌快」。
     * 现在钟等到 {@link HatMusicInstance#tick()} 第一次被调到（= 引擎已经开始播这条）再走。
     */
    private static boolean waitingForSound;
    /** 等声音的上限：到这一刻还没响就自己走，免得音频出问题时整个 HUD 卡在 0。 */
    private static int clockFallbackAt = Integer.MIN_VALUE;

    /** 服务端推来的最新连击 / 判定。 */
    private static int combo;
    private static int rating;
    /** 最近一次判定发生在音乐的哪一刻 —— HUD 拿它算判定字的淡出。 */
    private static int ratingTick = Integer.MIN_VALUE;

    /**
     * 右侧判定字：**每一条独立排队**（新的不会顶掉旧的），环形缓冲 {@link #POPUP_MAX} 条。
     *
     * <p>以前只有一个「最近一次判定」，第二条一来第一条就被覆盖 —— 连打时永远只看得见
     * 一个字。FNF 里判定字是几条各自升上去、各自淡掉的，这里照做。
     */
    private static final int POPUP_MAX = 4;
    private static final int[] POPUP_RATING = new int[POPUP_MAX];
    private static final int[] POPUP_TICK = new int[POPUP_MAX];
    private static int popupNext;
    /** 攒着的闪避次数（服务端同步）。 */
    private static int dodges;
    /** 攒闪避的打拍计数 0..BONUS_PER_DODGE（服务端同步；只在光柱期间会涨）。 */
    private static int acc;
    /** 本轮得分（服务端同步）。 */
    private static int score;
    /** 本轮 MISS 数（服务端同步）。 */
    private static int misses;
    /** 本轮踩对了几拍（服务端同步）。 */
    private static int hits;
    /** 本轮 PERFECT / GOOD 数（服务端同步）。 */
    private static int perfects;
    private static int goods;
    /**
     * 最近一次判定落在**哪一拍**（谱面刻，服务端说；-1 = 这一包没带）。
     *
     * <p>以前 HUD 只能拿「包到达的时刻」就近猜是哪一拍，MISS 常常被画到下一颗键上 ——
     * 那颗键其实还没按，看起来就像「明明踩上了却判 MISS」。
     */
    private static int judgedTick = -1;

    /**
     * 每根长音的头拍有没有真踩上（第 i 位 = 第 i 根）。换一轮清零。
     *
     * <p>服务端只会给**踩上了头拍**的长音涨连击；HUD 拿这一位画「过条变黄 / 整条变暗」，
     * 两边说的是同一件事。
     */
    private static long holdHeadMask;

    /** 面板淡出用：最后一次非零连击数，以及它掉到 0 的那一刻（音乐刻）。 */
    private static int panelCombo;
    private static int panelDrop = Integer.MIN_VALUE;

    /** 第几次起播：每开一轮音乐就 +1，用来区分「这一轮的 260 刻」和「上一轮的 260 刻」。 */
    private static int epoch;

    /** 调参器里的「自动打拍」（纯调试；服务端据此把这一轮排除出成就）。 */
    private static boolean autoTap;

    /** 最近一次「闪避落空」在客户端世界时间的哪一刻；{@link Long#MIN_VALUE} = 没闪过。 */
    private static long dodgeAt = Long.MIN_VALUE;
    /** 上一次用掉闪避时的**谱面刻**：奖励面板拿它算「用掉之后又攒了几颗伴奏帽」。 */
    private static int dodgeAge = Integer.MIN_VALUE;

    /** 涌动到哪一刻为止（音乐刻）；{@link Integer#MIN_VALUE} = 没在涌动。 */
    private static int surgeEnd = Integer.MIN_VALUE;
    /** 这一次涌动的总长（刻），拿来换算「还剩多少」。 */
    private static int surgeTicks;
    /** 涌动峰值：光柱长度 / 粗细的倍率（和服务端 BrainrotQte 的常量一致）。 */
    private static float surgeLen = 1.0F;
    private static float surgeRadius = 1.0F;

    private BrainrotQteClient() {
    }

    /**
     * 由 {@link HatMusicPlayer#handle} 转发：只有「本地玩家自己的」脑蚀 BGM 才立钟。
     * 别人的脑蚀也听得见，但打拍条只该出现在戴帽者的屏幕上。
     */
    static void onMusic(int wearerId, int ordinal, boolean playing) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null
                || wearerId != minecraft.player.getId()
                || ordinal != HatMusic.BRAINROT_ORDINAL) {
            return;
        }
        if (playing) {
            startTick = HatMusicPlayer.ticks();
            clockNanos = 0L;
            clockTicks = 0.0D;
            waitingForSound = true;
            clockFallbackAt = HatMusicPlayer.ticks() + 20;
            epoch++;
            combo = 0;
            rating = 0;
            ratingTick = Integer.MIN_VALUE;
            holdHeadMask = 0L;
        } else {
            reset();
        }
    }

    /** 服务端发来的最新判定。打中一拍（PERFECT / GOOD）顺手把「涌动」立起来。 */
    public static void onState(int newCombo, int newRating, int newDodges, int newAcc,
                               int newScore, int newMisses, int newHits,
                               int newPerfects, int newGoods, int newJudgedTick) {
        combo = newCombo;
        rating = newRating;
        dodges = newDodges;
        acc = newAcc;
        score = newScore;
        misses = newMisses;
        hits = newHits;
        perfects = newPerfects;
        goods = newGoods;
        judgedTick = newJudgedTick;
        // 长音头拍：这一记判在这根条的开头、而且不是 MISS —— 这根条「有效」了
        if (newJudgedTick >= 0 && (newRating == 1 || newRating == 2)) {
            int hold = BrainrotBeats.holdAt(newJudgedTick);
            if (hold >= 0 && hold < 64 && BrainrotBeats.holdStart(hold) == newJudgedTick) {
                holdHeadMask |= 1L << hold;
            }
        }
        // 连击还在就往面板上记一份；掉到 0 就记下时刻，面板靠它淡出
        if (newCombo > 0) {
            panelCombo = newCombo;
            panelDrop = Integer.MIN_VALUE;
        } else if (panelCombo > 0 && panelDrop == Integer.MIN_VALUE) {
            panelDrop = age();
        }
        ratingTick = age();
        // 判定字单独排队：新的不会把旧的顶掉（连打时几个字先后升上去）
        int at = age();
        if (newRating != 0 && at >= 0) {
            int[] nearOut = new int[1];
            int beatTick = BrainrotBeats.tickAt(BrainrotBeats.nearest(at, nearOut));
            // 口白三键由那排大字自己亮；长音里每 5 刻攒满一层也会弹 PERFECT ——
            // 这就是「按住也算评分」，一路按到底就是一路 PERFECT 刷上去
            if (BrainrotBeats.speechIndex(beatTick) < 0) {
                POPUP_RATING[popupNext] = newRating;
                POPUP_TICK[popupNext] = at;
                popupNext = (popupNext + 1) % POPUP_MAX;
            }
        }
        if (newRating == 1 || newRating == 2) {
            boolean perfect = newRating == 1;
            surgeTicks = perfect ? 10 : 6;
            surgeLen = perfect ? 1.6F : 1.3F;
            surgeRadius = perfect ? 1.8F : 1.4F;
            surgeEnd = Math.max(0, age()) + surgeTicks;
        }
    }

    /** 服务端通知：刚刚吃掉一次闪避（这一击完全落空）—— HUD 画紫边 + 大字。 */
    public static void onDodge() {
        Minecraft minecraft = Minecraft.getInstance();
        dodgeAt = minecraft.level == null ? Long.MIN_VALUE : minecraft.level.getGameTime();
        int now = age();
        dodgeAge = now >= 0 ? now : Integer.MIN_VALUE;
    }

    /** 上一次用掉闪避时的谱面刻；还没用过就是 {@link Integer#MIN_VALUE}。 */
    static int dodgeAge() {
        return dodgeAge;
    }

    /** 距上次闪避过了几刻；没闪过就是 {@link Long#MAX_VALUE}（HUD 看到它直接不画）。 */
    static long sinceDodge() {
        Minecraft minecraft = Minecraft.getInstance();
        if (dodgeAt == Long.MIN_VALUE || minecraft.level == null) {
            return Long.MAX_VALUE;
        }
        return minecraft.level.getGameTime() - dodgeAt;
    }

    /**
     * 按了一下打拍（输入事件调用）。没在放就当无事发生。
     *
     * <p>先把钟推到**这一刻**：{@code clockTicks} 平时每刻才推一次，按下时读到的最多是
     * 一刻前的值，等于把玩家的手往前挪了最多 50ms —— 判定窗本来就窄，这一下很要命。
     */
    static void tap() {
        advanceClock();
        int age = age();
        if (age >= 0) {
            HatNetwork.sendQteTap(age);
        }
    }

    /** 曲终 / 摘帽：整条打拍状态清掉。 */
    static void reset() {
        startTick = Integer.MIN_VALUE;
        clockNanos = 0L;
        clockTicks = 0.0D;
        waitingForSound = false;
        clockFallbackAt = Integer.MIN_VALUE;
        epoch++;
        combo = 0;
        rating = 0;
        dodges = 0;
        acc = 0;
        score = 0;
        misses = 0;
        hits = 0;
        perfects = 0;
        goods = 0;
        judgedTick = -1;
        holdHeadMask = 0L;
        ratingTick = Integer.MIN_VALUE;
        surgeEnd = Integer.MIN_VALUE;
        surgeTicks = 0;
        dodgeAt = Long.MIN_VALUE;
        dodgeAge = Integer.MIN_VALUE;
        panelCombo = 0;
        panelDrop = Integer.MIN_VALUE;
        for (int i = 0; i < POPUP_MAX; i++) {
            POPUP_RATING[i] = 0;
            POPUP_TICK[i] = 0;
        }
        popupNext = 0;
    }

    static boolean active() {
        return startTick != Integer.MIN_VALUE;
    }

    /** 音乐自己的钟走到几刻（≈ 服务端时间线的 age，可能差个网络延迟）。 */
    static int age() {
        return startTick == Integer.MIN_VALUE ? -1 : (int) clockTicks;
    }

    /** 带小数的谱子时刻（渲染可以拿它做插值，目前只给需要平滑的地方用）。 */
    static float ageF() {
        return startTick == Integer.MIN_VALUE ? -1.0F : (float) clockTicks;
    }

    /**
     * 客户端每刻推进「谱子的钟」：按真实流逝时间累加，掉帧、卡顿也照样走。
     *
     * <p>一刻按 50ms 算。单次最多补 4 刻 —— 再多说明是在读盘 / 传送，硬补会让画面一下子冲过去。
     * 暂停时不调用（调用点在 {@link HatMusicPlayer#onClientTick} 的暂停判断之后），
     * 声音引擎暂停时 BGM 也停着，所以钟跟着停才对得上。
     */
    static void advanceClock() {
        if (startTick == Integer.MIN_VALUE) {
            return;
        }
        // 声音还没真响起来：钟先别走（最多等 1 秒，之后自己走）
        if (waitingForSound) {
            if (HatMusicPlayer.ticks() < clockFallbackAt) {
                return;
            }
            waitingForSound = false;
        }
        long now = System.nanoTime();
        if (clockNanos == 0L) {
            clockNanos = now;
            return;
        }
        double elapsed = (now - clockNanos) / 50_000_000.0D;
        clockNanos = now;
        clockTicks += Math.min(elapsed, 4.0D);
    }

    /**
     * 声音引擎开始播「某个戴帽者的 BGM」了（由 {@link HatMusicInstance#tick()} 调）。
     * 只有本地玩家自己那条才有意义：它就是谱面钟的起跑令。
     */
    static void onSoundRunning(int wearerId) {
        if (!waitingForSound) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.player.getId() != wearerId) {
            return;
        }
        waitingForSound = false;
        clockNanos = 0L;    // 下一 tick 的 advanceClock 把它立起来，从这一刻开始计时
    }

    /** 第几轮音乐（给 HUD 区分同名拍点在两轮里的归属）。 */
    static int epoch() {
        return epoch;
    }

    public static boolean autoTap() {
        return autoTap;
    }

    /** 服务端同步过来的「自动打拍」状态（进服时用；这里不再回发）。 */
    public static void setAutoTap(boolean on) {
        autoTap = on;
    }

    /** 切换「自动打拍」并立刻通知服务端。 */
    public static void toggleAutoTap() {
        autoTap = !autoTap;
        HatNetwork.sendAutoTap(autoTap);
    }

    static int combo() {
        return combo;
    }

    static int rating() {
        return rating;
    }

    static int ratingTick() {
        return ratingTick;
    }

    /** 最近一次判定落在哪一拍（谱面刻）；{@code -1} = 服务端没带，退回就近猜。 */
    static int judgedTick() {
        return judgedTick;
    }

    /** 第 {@code hold} 根长音的头拍踩上了没有（踩上了 = 这根条有效）。 */
    static boolean holdHeadHit(int hold) {
        return hold >= 0 && hold < 64 && (holdHeadMask & (1L << hold)) != 0L;
    }

    /** 判定字一共几个槽位（HUD 遍历用）。 */
    static int popupCount() {
        return POPUP_MAX;
    }

    /** 第 {@code i} 个槽位里的判定（0 = 空槽）。 */
    static int popupRating(int i) {
        return POPUP_RATING[i];
    }

    /** 第 {@code i} 个槽位那次判定落在音乐的哪一刻（空槽是 0，HUD 靠判定值为 0 挡掉）。 */
    static int popupTick(int i) {
        return POPUP_TICK[i];
    }

    /** HUD 用：当前攒着几次闪避。 */
    static int dodges() {
        return dodges;
    }

    /** HUD 用：攒闪避的打拍计数（0..BONUS_PER_DODGE）。 */
    static int acc() {
        return acc;
    }

    /** HUD 用：本轮得分。 */
    static int score() {
        return score;
    }

    /** HUD 用：本轮 MISS 数。 */
    static int misses() {
        return misses;
    }

    /** HUD 用：本轮踩对了几拍。 */
    static int hits() {
        return hits;
    }

    /** HUD 用：本轮 PERFECT 数。 */
    static int perfects() {
        return perfects;
    }

    /** HUD 用：本轮 GOOD 数。 */
    static int goods() {
        return goods;
    }

    /** 面板上该显示的连击数：服务端报 0 之后还留着上一次的值，好让它淡出。 */
    static int panelCombo() {
        return panelCombo;
    }

    /** 连击掉到 0 之后面板还剩多少透明度（10 刻淡出）。 */
    static float panelFade(int age) {
        if (panelDrop == Integer.MIN_VALUE) {
            return panelCombo > 0 ? 1.0F : 0.0F;
        }
        int since = age - panelDrop;
        if (since < 0 || since > 10) {
            return 0.0F;
        }
        return 1.0F - since / 10.0F;
    }

    /**
     * 光柱此刻的「涌动」倍率：{@code out[0]} = 长度、{@code out[1]} = 粗细。
     *
     * <p>没在涌动时两个都是 1.0；打中的那一下最猛（PERFECT 长度 ×1.6 / 粗细 ×1.8，
     * GOOD ×1.3 / ×1.4），随后按剩余时间线性缩回 1 —— 和服务端判定同一条曲线，
     * 看到的光和挨的打才是一回事。BeamRenderer 直接拿它放大。
     */
    public static void surgeScale(float[] out) {
        int a = age();
        if (a < 0 || surgeEnd == Integer.MIN_VALUE || a > surgeEnd) {
            out[0] = 1.0F;
            out[1] = 1.0F;
            return;
        }
        float amount = surgeTicks <= 0 ? 0.0F : (float) (surgeEnd - a) / surgeTicks;
        amount = Math.max(0.0F, Math.min(1.0F, amount));
        out[0] = 1.0F + (surgeLen - 1.0F) * amount;
        out[1] = 1.0F + (surgeRadius - 1.0F) * amount;
    }
}