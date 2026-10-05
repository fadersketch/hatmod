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
 *   <tr><td>全</td><td>随路线</td><td>随路线</td><td>随路线</td>
 *       <td>三顶合一：同时具备上面三种专属效果（瞬移闪避 + 力量 + 永久迅捷/跳跃），
 *           并兼容全部 9 个专属附魔；不附魔也自带附魔光效。
 *           <b>每轮从黑/白/红里随机取一条路线</b>（随机不重复）：蓄力/照射时长、BGM、
 *           粒子颜色、光柱帧伤（黑 12 / 白 10 / 红 15）与帽子渲染颜色都跟着路线走，
 *           相当于这轮「变成了那顶帽子」</td></tr>
 * </table>
 *
 * <!-- TUNE:NOTE:BEGIN（本段由 hatmod-tools/tune.py 自动生成，别手改） -->
 * <p><b>当前开火点</b>：黑 440/160、白 364/129、红 588/152（蓄力 / 照射，刻）。
 * <p><b>一轮总长</b>：黑 600、白 493、红 740 刻 —— 刻意比各自的音乐略长一点（留一点空档）；
 * 但**绝不能短于音乐长度**：短了上一遍还没播完下一遍就开始，会两遍叠着响。
 * <!-- TUNE:NOTE:END -->
 * <p><b>「全」没有自己的固定时长</b>：它每轮随机取一条路线（黑/白/红），时长、BGM、
 * 帧伤、颜色全按那条路线走 —— 所以这里 {@code ALL} 的蓄力/照射数值只是占位，
 * 真正生效的是路线那顶帽子的值（见 {@code HatAbilities.startCharge}）。
 * <p>「掌控」附魔**不会**改蓄力时长 —— 那样会打断音乐的节奏，见 {@link HatEnchants}。
 *
 * <p>光柱每刻的总伤害 = <b>目标的当前生命值</b> + 每刻附加伤害，见
 * {@code HatAbilities#damageBeam}。
 *
 * <p>每顶帽子还各有三个**专属附魔**（只有这顶帽子能附上），会进一步强化上面的数值，
 * 见 {@link HatEnchants}。其中「全」对所有 9 个专属附魔都开放。
 */
public enum HatType {
    BLACK("black_hat", 440, 160, 10.0F, Trait.BLINK),
    WHITE("white_hat", 364, 129, 10.0F, Trait.PERMANENT_BUFFS),
    RED("red_hat", 588, 152, 10.0F, Trait.STRENGTH),
    /**
     * 「神之牛仔帽（全）」：把三顶帽子在工作台合到一起得到。
     *
     * <p>三种特性**同时且始终**生效（{@link Trait#BLINK} + {@link Trait#STRENGTH} +
     * {@link Trait#PERMANENT_BUFFS}），并且对全部 9 个专属附魔开放。
     *
     * <p>它没有自己的固定时长/帧伤，而是**每轮从黑/白/红里随机取一条路线**
     * （随机不重复，三条洗完再重洗）：蓄力/照射时长、BGM、力场粒子颜色、光柱帧伤
     * （黑 12 / 白 10 / 红 15，见 {@link #routeDamage}）以及帽子渲染出来的颜色，都跟着
     * 那条路线走 —— 也就是这一轮它「变成了那顶帽子」。三种专属特性不跟路线走。
     *
     * <p>这里的蓄力/照射/帧伤数值只是占位（给调参界面显示用），实际不参与光柱结算。
     * 追加在枚举**最后**：{@link #ordinal()} 会随网络包一起发出去，插在中间会让老存档/老客户端
     * 认错帽子。
     */
    ALL("all_hat", 588, 152, 15.0F, Trait.BLINK, Trait.STRENGTH, Trait.PERMANENT_BUFFS);

    /** 每顶帽子除了数值之外独有的效果。「全」可以同时拥有多个。 */
    public enum Trait {
        /** 蓄力期间受到伤害 -> 瞬移到安全地点，并让这一击完全落空（免伤）；每轮最多 3 次。 */
        BLINK,
        /** 照射期间自身获得力量 I。 */
        STRENGTH,
        /** 戴着就一直有迅捷 I + 跳跃 I。 */
        PERMANENT_BUFFS
    }

    /**
     * 「全」每轮随机取一条路线时，三条路线各自的**光柱帧伤**。
     *
     * <p>只给「全」用，出厂值黑 12 / 白 10 / 红 15；三顶原色帽本身仍是各自的
     * {@link #damagePerTick}（统一 10，可用调参器改），不受这里影响。
     *
     * <p>实际数值现在由 {@link HatSettings#routeDamage} 提供（可在调参器「全帽」页或
     * {@code /hatmod routedamage} 改、落盘到 {@code config/hatmod.json}）。这个方法保留为
     * **出厂默认值**的来源，配置缺失时按它走。
     *
     * <p>刻意写成 if/else 而不是 {@code switch}/{@code EnumMap}：对枚举做 switch 会让 javac
     * 多生成一个合成类，而本模组每个类都要登记进 {@link HatPreloader}（见那里的注释）。
     */
    public static float routeDamage(HatType route) {
        if (route == BLACK) {
            return 12.0F;
        }
        if (route == RED) {
            return 15.0F;
        }
        // 白（以及任何意外的入参）走 10
        return 10.0F;
    }

    /**
     * 「全」的路线洗牌袋要洗的是哪三条 —— 固定就是黑 / 白 / 红，与「全」自己无关。
     *
     * <p>返回一个新数组，调用方随洗随用；三条路线一轮内不重复，取空再重洗
     * （见 {@code HatAbilities.nextRoute}）。
     */
    public static HatType[] routeChoices() {
        return new HatType[]{BLACK, WHITE, RED};
    }

    private final String id;
    private final int chargeTicks;
    private final int flashTicks;
    private final float damagePerTick;
    private final Trait[] traits;

    HatType(String id, int chargeTicks, int flashTicks, float damagePerTick, Trait... traits) {
        this.id = id;
        this.chargeTicks = chargeTicks;
        this.flashTicks = flashTicks;
        this.damagePerTick = damagePerTick;
        this.traits = traits;
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

    /**
     * 这顶帽子有没有某个特性。
     *
     * <p>刻意写成 for 循环而不是 {@code switch}/{@code EnumSet}：对枚举做 switch 会让 javac
     * 额外生成一个合成类，而本模组每个类都要登记进 {@link HatPreloader} 才不会被
     * 「关了 jar 句柄」的优化模组坑到（见那个类的注释）—— 少一个类少一处要维护的。
     */
    public boolean has(Trait trait) {
        for (Trait candidate : this.traits) {
            if (candidate == trait) {
                return true;
            }
        }
        return false;
    }
}
