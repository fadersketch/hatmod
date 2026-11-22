package com.hatmod;

/**
 * 某个戴帽子的生物当前所处的技能阶段。
 *
 * <p>刻意做成**独立的顶层类**而不是 {@code HatAbilities} 的内部类：
 * 内部类的类文件只有在第一次用到时才会被加载，而某些内存优化模组
 * （例如 AllTheLeaks 会强制关闭 mod 的 jar 文件句柄）在这之后会让
 * 延迟加载直接失败并抛出 {@code NoClassDefFoundError}。
 * 独立顶层类 + {@link HatPreloader} 的预加载一起，避免了这个坑。
 */
final class HatState {
    /** 本轮循环用的是哪顶帽子（中途换帽子会重置整个循环）。 */
    HatType type;
    /**
     * 本轮循环「按哪顶帽子走」。
     *
     * <p>三顶原色帽就是 {@link #type} 本身；「全」每一轮从黑/白/红里**随机但不重复**地取一条，
     * 这条路线的蓄力/照射时长、BGM、粒子颜色、光柱帧伤（12/10/15）与帽子渲染颜色都跟着它走。
     * 「全」的三种专属特性不跟路线走（始终同时生效），所以特性判定仍旧读 {@link #type}。
     */
    HatType route;
    /** 「全」的路线洗牌袋：三条路线打乱后依次取，取空重洗（见 {@code HatAbilities.nextRoute}）。 */
    HatType[] routeBag;
    /** {@link #routeBag} 里取到第几个了。 */
    int routeBagIndex;
    /** 剩余蓄力刻数，> 0 表示处于蓄力阶段（此阶段不能攻击、周围有时间差力场）。 */
    int charge;
    /** 剩余照射刻数，> 0 表示正在照射。 */
    int flash;
    /** 黑帽：本轮蓄力还剩几次「瞬移躲伤害」可用（基础次数 + 附魔「闪避」）。 */
    int blinks;
    /** 「神本无相」：本轮蓄力里已经真的闪掉几次。 */
    int chargeBlinks;
    /** 「身轻如燕」：这一次照射里用光柱击杀几个。 */
    int flashKills;
    /** 「不动如山」：这次蓄力里有没有挨到过真伤。 */
    boolean hitWhileCharging;
    /** 黑帽：本轮照射还剩几次「瞬移躲伤害」可用（附魔「残影」给）。 */
    int flashBlinks;
    /** 附魔「掌控」本轮蓄力是否已经触发过（每轮只触发一次）。 */
    boolean dominionFired;
    /** 客户端当前认为「在照射」吗（本模组自己的包同步的，不依赖状态效果）。 */
    boolean beamSynced;
    /**
     * 本轮蓄力在等客户端回执「BGM 真的响起来了」再开始倒数。
     *
     * <p>不等的话，发包那一刻就开始数 18.2 秒，客户端起播只要慢半拍，光柱就提前于音乐；
     * 第一轮往往没事（引擎闲着），第二轮开始就容易对不上。见 {@code HatAbilities#noteMusicStarted}。
     */
    boolean musicPending;
    /** 已经等了几刻：客户端一直不回执（关声音 / 没装客户端）就按 {@code MUSIC_ACK_TIMEOUT} 兜底。 */
    int musicWait;
    /**
     * 当前光柱锁定的敌人实体 id，**每道光柱一个**（红帽附魔「光柱」会有多道）。
     * 没有目标时是空数组；用于避免重复发包。
     */
    int[] targetIds = new int[0];

    void reset() {
        this.route = null;
        this.routeBag = null;
        this.routeBagIndex = 0;
        this.charge = 0;
        this.flash = 0;
        this.blinks = 0;
        this.chargeBlinks = 0;
        this.flashKills = 0;
        this.hitWhileCharging = false;
        this.flashBlinks = 0;
        this.dominionFired = false;
        this.beamSynced = false;
        this.musicPending = false;
        this.musicWait = 0;
        this.targetIds = new int[0];
    }
}
