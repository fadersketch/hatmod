package com.hatmod;

/**
 * 三顶牛仔帽各自的数值。
 *
 * <p>数值单位都是「刻」（1 秒 = 20 刻），想微调直接改这里的常量即可。
 *
 * <p>这里的 {@code damagePerTick} 是**三顶帽子统一的默认值 10**：光柱真正的压力来自
 * 「每刻扣目标当前生命的 {@link HatSettings#DEFAULT_HEALTH_DAMAGE_RATIO}（5%）」那一部分，
 * 固定值只是收尾。光柱改成单体、又砍掉「蓄力期禁攻击」之后，战斗力下滑明显，
 * 所以把固定值从黑 1 / 白 2 / 红 3 统一抬到 10 补回来。想要更高就用创造模式的调参器改，会写进
 * {@code config/hatmod.json} —— 注意**一旦那个文件存在，这里改默认值就不再生效**，
 * 游戏会一直读文件里的旧值（删掉文件或调参器点「恢复默认」才会重新跟随这里）。
 *
 * <table>
 *   <tr><th>帽子</th><th>蓄力</th><th>照射</th><th>每刻附加伤害</th><th>专属效果</th></tr>
 *   <tr><td>黑</td><td>22 秒 (440t)</td><td>8 秒 (160t)</td><td>10</td><td>蓄力期间挨打会瞬移躲开那一击（免伤），每轮最多 3 次</td></tr>
 *   <tr><td>白</td><td>18.2 秒 (364t)</td><td>6.45 秒 (129t)</td><td>10</td><td>永久迅捷 I + 跳跃 I</td></tr>
 *   <tr><td>红</td><td>29.4 秒 (588t)</td><td>7.6 秒 (152t)</td><td>10</td><td>照射期间力量 I</td></tr>
 * </table>
 *
 * <!-- TUNE:NOTE:BEGIN（本段由 hatmod-tools/tune.py 自动生成，别手改） -->
 * <p><b>当前开火点</b>：黑 440/160、白 364/129、红 588/152（蓄力 / 照射，刻）。
 * <p><b>一轮总长</b>：黑 600、白 493、红 740 刻 —— 刻意比各自的音乐略长一点（留一点空档）；
 * 但**绝不能短于音乐长度**：短了上一遍还没播完下一遍就开始，会两遍叠着响。
 * <!-- TUNE:NOTE:END -->
 * <p>「掌控」附魔**不会**改蓄力时长 —— 那样会打断音乐的节奏，见 {@link HatEnchants}。
 *
 * <p>光柱每刻的总伤害 = <b>目标的当前生命值</b> + 这里的每刻附加伤害，见
 * {@code HatAbilities#beamDamage}。
 *
 * <p>每顶帽子还各有三个**专属附魔**（只有这一顶帽子能附上），会进一步强化上面的数值，
 * 见 {@link HatEnchants}。
 */
public enum HatType {
    BLACK("black_hat", 440, 160, 10.0F, Trait.BLINK),
    WHITE("white_hat", 364, 129, 10.0F, Trait.PERMANENT_BUFFS),
    RED("red_hat", 588, 152, 10.0F, Trait.STRENGTH);

    /** 每顶帽子除了数值之外独有的效果。 */
    public enum Trait {
        /** 蓄力期间受到伤害 -> 瞬移到安全地点，并让这一击完全落空（免伤）；每轮最多 3 次。 */
        BLINK,
        /** 照射期间自身获得力量 I。 */
        STRENGTH,
        /** 戴着就一直有迅捷 I + 跳跃 I。 */
        PERMANENT_BUFFS
    }

    private final String id;
    private final int chargeTicks;
    private final int flashTicks;
    private final float damagePerTick;
    private final Trait trait;

    HatType(String id, int chargeTicks, int flashTicks, float damagePerTick, Trait trait) {
        this.id = id;
        this.chargeTicks = chargeTicks;
        this.flashTicks = flashTicks;
        this.damagePerTick = damagePerTick;
        this.trait = trait;
    }

    /** 对应物品的注册名。 */
    public String id() {
        return this.id;
    }

    /** 蓄力时长（刻）。 */
    public int chargeTicks() {
        return this.chargeTicks;
    }

    /** 照射时长（刻）。 */
    public int flashTicks() {
        return this.flashTicks;
    }

    /** 光柱每刻在「目标当前生命值」之上额外造成的伤害。 */
    public float damagePerTick() {
        return this.damagePerTick;
    }

    public boolean has(Trait trait) {
        return this.trait == trait;
    }
}
