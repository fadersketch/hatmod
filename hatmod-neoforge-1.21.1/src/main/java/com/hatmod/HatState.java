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
    /** 剩余蓄力刻数，> 0 表示处于蓄力阶段（此阶段不能攻击、周围有时间差力场）。 */
    int charge;
    /** 剩余照射刻数，> 0 表示正在照射。 */
    int flash;
    /** 黑帽：本轮蓄力还剩几次「瞬移躲伤害」可用（基础次数 + 附魔「闪避」）。 */
    int blinks;
    /** 黑帽：本轮照射还剩几次「瞬移躲伤害」可用（附魔「残影」给）。 */
    int flashBlinks;
    /** 附魔「掌控」本轮蓄力是否已经触发过（每轮只触发一次）。 */
    boolean dominionFired;
    /** 客户端当前认为「在照射」吗（本模组自己的包同步的，不依赖状态效果）。 */
    boolean beamSynced;
    /**
     * 当前光柱锁定的敌人实体 id，**每道光柱一个**（红帽附魔「光柱」会有多道）。
     * 没有目标时是空数组；用于避免重复发包。
     */
    int[] targetIds = new int[0];

    void reset() {
        this.charge = 0;
        this.flash = 0;
        this.blinks = 0;
        this.flashBlinks = 0;
        this.dominionFired = false;
        this.beamSynced = false;
        this.targetIds = new int[0];
    }
}
