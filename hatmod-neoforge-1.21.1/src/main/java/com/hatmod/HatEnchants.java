package com.hatmod;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.Map;

/**
 * 三顶帽子各自的三个专属附魔，一共 9 个。
 *
 * <p>1.21 的附魔是**数据驱动**的：本体是
 * {@code data/hatmod/enchantment/<id>.json}，这个类只负责「读等级 + 把等级翻译成效果数值」，
 * 一个附魔都不注册。JSON 里的 {@code supported_items} 直接写死对应那顶帽子，
 * 所以「专属」是数据层面的硬限制；反过来，三顶帽子对**原版所有头盔附魔**
 * （保护/水下呼吸/耐久/经验修补/诅咒……）完全兼容 —— 靠的是
 * {@code data/minecraft/tags/item/head_armor.json} 把它们并进原版头盔标签，
 * 而原版的 {@code #minecraft:enchantable/*} 全部由这个标签级联而来。
 *
 * <p>九个附魔分成三组，每组对应一顶帽子的三条强化路线：
 *
 * <table>
 *   <tr><th>帽子</th><th>强化核心机制</th><th>强化增益</th><th>强化光柱</th></tr>
 *   <tr><td>黑</td><td>闪避 —— 蓄力期每轮多躲 1 次</td><td>残影 —— 照射期每轮多躲 1 次</td><td>预知 —— 蓄力完成时定住 30 格内的敌人，时长按等级 1/3、2/3、3/3 递进（III 级吃满本轮循环剩余长度）</td></tr>
 *   <tr><td>白</td><td>缓速 —— 力场半径 +1 格</td><td>疾行 —— 迅捷/跳跃等级 +1</td><td>光辉 —— 每刻 +1 伤害，并给**力场圈内**的队友「神隐」（6/8/10 秒）</td></tr>
 *   <tr><td>红</td><td>掌控 —— 蓄力第 15 秒剥夺最近 N 个敌人索敌（随等级最多 15 人），并让它们自相残杀</td><td>吸血 —— 光柱打掉的血按比例吸回自身（每级 +5%），满血时溢出转成绿心</td><td>光柱 —— 每级多发射 1 道光柱（I/II/III = 2/3/4 道），每道各锁一个不同的敌人</td></tr>
 * </table>
 *
 * <p>这几个附魔和原版附魔**同等地位**：能上附魔台、能出在宝箱的附魔书里、能跟村民换到。
 * 1.21 的准入完全走标签，所以本模组加了
 * {@code data/minecraft/tags/enchantment/non_treasure.json} 把 9 个附魔并进
 * {@code #minecraft:non_treasure} —— 附魔台池 {@code #minecraft:in_enchanting_table}、
 * 宝箱池 {@code #minecraft:on_random_loot}、村民交易池 {@code #minecraft:tradeable}
 * 全都从这个标签级联而来，加这一个文件三处就一起生效了。
 * （`on_random_loot` 额外含宝藏附魔，但并进 `non_treasure` 已经足够达到"和普通附魔同等"。）
 *
 * <p>数值都集中在下面那几个常量里，想调直接改（改完记得和 JSON 里的 max_level 对齐）。
 */
public final class HatEnchants {

    /** 每个专属附魔的最高等级（同时也是 JSON 里 max_level 的值）。 */
    public static final int MAX_LEVEL = 3;

    // ------------------------------------------------------------------
    // 九个附魔的数值（每级增量）
    // ------------------------------------------------------------------

    /** 「闪避」每级给黑帽多几次**蓄力期**的瞬移闪避（基础 3 次，III 级就是 6 次）。 */
    public static final int FORMLESS_BLINKS_PER_LEVEL = 1;
    /** 「残影」每级给黑帽多几次**照射期**的瞬移闪避（I/II/III = 1/2/3 次）。 */
    public static final int AFTERIMAGE_BLINKS_PER_LEVEL = 1;
    /**
     * 「预知」点名 / 定身的半径（格）。
     *
     * <p>比光柱射程（24 格）还大一截：这一下扫的是**整片战场**，不是光柱那一条线。
     * 时长不再是固定值，而是「从蓄力完成的那一刻释放，一直到本轮循环结束」，并且
     * **按附魔等级递进**（I/II/III 各取最大时长的 1/3、2/3、3/3），
     * 见 {@link #soulReapTicks(ItemStack, int)}。
     */
    public static final double SOUL_REAP_RADIUS = 30.0D;
    /** 「缓速」每级把时间差力场半径加多少格。 */
    public static final double REQUIEM_RADIUS_PER_LEVEL = 1.0D;
    /** 「疾行」每级把永久迅捷/跳跃的等级加多少。 */
    public static final int BENEDICTION_AMPLIFIER_PER_LEVEL = 1;
    /** 「疾行」每级把等级加多少（和上面分开写只是为了可读性，数值一致）。 */
    public static final int BENEDICTION_JUMP_PER_LEVEL = 1;

    // ------------------------------------------------------------------
    // 「掌控」：蓄力到第 15 秒时，剥夺最近敌人的索敌目标
    // ------------------------------------------------------------------

    /**
     * 「掌控」在蓄力开始后第几刻触发（300 刻 = 第 15 秒）。
     *
     * <p>刻意**不去改蓄力时长**：一轮的总长是和音乐严格对齐的，缩短蓄力会打乱节奏。
     * 这个附魔只是在一个固定的时间点上插一脚。
     */
    public static final int DOMINION_TRIGGER_TICK = 300;
    /** 「掌控」剥夺索敌的基础时长（5 秒）。 */
    public static final int DOMINION_LOSE_TARGET_BASE_TICKS = 100;
    /** 「掌控」每高一级多加的时长（每级 +5 秒）。 */
    public static final int DOMINION_LOSE_TARGET_PER_LEVEL_TICKS = 100;
    // 目标身上的光灵标记（发光描边）不再是单独的常量：它和上面算出来的剥夺索敌时长
    // 完全一致 —— 「控多久，光标就亮多久」，见 HatAbilities#maybeFireDominion。

    /**
     * 「光柱」每级**多发射一道光柱**（基础 1 道，I/II/III 级 = 2/3/4 道）。
     *
     * <p>取代了早先的「每级每刻 +1 点固定伤害」：红帽的定位是纯输出，而固定伤害在
     * 限伤（伤害上限/伤害桶）满天飞的环境里几乎看不见收益 —— 多一道光等于多整套光柱判定
     * （固定伤害 + 百分比），那才是「输出」该有的样子。
     *
     * <p>原先是每级 +2 道（3/5/7），而且和「吸血」绑在同一个附魔上 —— 那样一个附魔同时给
     * 两样，太超模了。现在拆成两本：「光柱」只管加光柱（每级 +1，满级 4 道），
     * 吸血另立一本「吸血」。
     *
     * <p>每道光**各自锁一个不同的敌人**：光柱数多于敌人时只发敌人那么多道，
     * 所以单挑一个 Boss 时仍旧只有一道光（见 {@code HatAbilities.beamTargets}）。
     */
    public static final int CONFLAGRATION_BEAMS_PER_LEVEL = 1;

    /** 「掌控」每级能剥夺索敌的目标数（每级 +5：I 级 5 人、II 级 10 人、III 级 15 人）。 */
    public static final int DOMINION_TARGETS_PER_LEVEL = 5;

    /** 「掌控」一次最多能控住几个人（III 级正好 15）。 */
    public static final int DOMINION_MAX_TARGETS = 15;

    // ------------------------------------------------------------------
    // 附魔各自的专属机制
    //
    // 早先这几个只是同一行加法（每级 +1 伤害），彼此毫无区别 —— 那样不如打成一本
    // 通用附魔书。现在每个都换成**只属于自己帽子的机制**，都不再加光柱伤害：
    //   预知（黑）—— 定身静止：蓄力完成的那一刻定住周围所有敌人，时长按等级递进到本轮循环结束。
    //   光辉（白）—— 神隐支援：照射期间自己与**力场圈内**的队友获得强化隐身（6/8/10 秒）
    //   吸血（红）—— 吸血：光柱打掉的血量按比例回给自己（每级 +5%），满血时溢出转成绿心
    //   光柱（红）—— 多道光柱：每级多 1 道（各锁不同敌人）
    //
    // 所以**光柱的每刻伤害不再吃任何附魔加成**：白帽「光辉」只给神隐、黑帽「预知」只给定身、
    // 红帽「光柱」只加道数。三顶帽子的光柱基础伤害一律是各自 damagePerTick（默认 10）。
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // 「光辉」给的「神隐」：时长
    //
    // 范围没有自己的常量：**直接用时间差力场的半径**（HatAbilities.slowRadius），
    // 所以「缓速」把力场撑大时，神隐的覆盖范围跟着一起变大。
    // ------------------------------------------------------------------

    /** 「神隐」的基础时长（6 秒）。 */
    public static final int VEIL_DURATION_BASE_TICKS = 120;
    /** 「神隐」每高一级多加的时长（每级 +2 秒 → 6/8/10 秒，上限 10 秒）。 */
    public static final int VEIL_DURATION_PER_LEVEL_TICKS = 40;

    /**
     * 「光辉」这次给的「神隐」持续多久（刻）：I 级 120（6 秒）、II 级 160（8 秒）、
     * III 级 200（10 秒）—— 封顶 10 秒。
     *
     * <p>不再是「照多久挂多久」的刷新式短时长 —— 那样状态栏上的剩余时间一直在跳，
     * 玩家根本不知道自己还剩多久。现在照射期间照常每刻刷新，但每次刷新都写足时长，
     * 所以**照完还会再持续这么久**，状态栏上读到的是真实剩余时间
     * （见 {@code HatAbilities.applyRadianceVeil}）。
     */
    public static int veilDurationTicks(ItemStack hat) {
        int level = level(hat, Kind.RADIANCE);
        return level <= 0 ? 0
                : VEIL_DURATION_BASE_TICKS + (level - 1) * VEIL_DURATION_PER_LEVEL_TICKS;
    }

    /**
     * 「吸血」吸血溢出的治疗转成**绿心**的效率：100%（无损耗）。
     *
     * <p>满血时溢出的治疗原样变成绿心，一点都不折 —— 打掉多少伤害就换来多少绿心。
     * 只影响「治疗 -> 绿心」这一步，实际回血那部分不受影响。
     *
     * <p>绿心怎么来、怎么掉，见 {@code HatAbilities}（无限时长、掉落速度随颗数指数增长、基础 1 秒/颗，
     * 而且**优先级高过黄心**：受伤先扣绿心，扣完才轮到黄心与血量）。
     */
    public static final float BLOODTHIRST_ABSORPTION_EFFICIENCY = 1.0F;
    /**
     * 「吸血」：每级把光柱造成伤害的多少比例转成治疗（5% / 10% / 15%）。
     *
     * <p>刻意**不设回血上限**。原本担心「当前生命 × 5%/刻」那部分强制百分比伤害对高血量 Boss
     * 太猛、按比例吸回来会变成无敌，但现在的整合包里限伤（伤害上限/伤害桶）满天飞，
     * 百分比那部分本来就是专门用来穿限伤的；玩家自己也能把血量堆到几千。
     * 在那种环境下给吸血封顶反而让它变成个鸡肋 —— 所以照实吸，打多少吸多少。
     */
    public static final float BLOODTHIRST_LIFESTEAL_PER_LEVEL = 0.05F;

    private HatEnchants() {
    }

    // ------------------------------------------------------------------
    // 读取：全部走「帽子 → 专属附魔等级」这一条路，调用方不用关心附魔注册表
    // ------------------------------------------------------------------

    /**
     * 某顶帽子上某个专属附魔的等级；帽子不对或没附魔时返回 0。
     *
     * <p>这里按 {@code ResourceKey} 比对而不是直接拿 Holder 查表：附魔是从物品组件里
     * 反序列化出来的，Holder 实例和我们手上的不一定是同一个，比 key 才不会漏。
     */
    public static int level(ItemStack hat, Kind kind) {
        if (hat.isEmpty() || !kind.accepts(HatItems.typeOf(hat))) {
            return 0;
        }
        ResourceKey<Enchantment> key = kind.key();
        for (Map.Entry<Holder<Enchantment>, Integer> entry : hat.getEnchantments().entrySet()) {
            if (entry.getKey().unwrapKey().filter(key::equals).isPresent()) {
                return entry.getValue();
            }
        }
        return 0;
    }

    /**
     * 「光柱」这次照射一共打几道光柱（基础 1 道 + 每级 1 道 → I/II/III = 2/3/4 道）。
     *
     * <p>没附魔也返回 1 —— 光柱本身是红帽的固有手段，附魔只是让它多几道。
     * 非红帽返回 1，调用方按 1 道处理即可。
     *
     * <p>这是**上限**：实际发出几道还要看敌人有几个 —— 每道光各锁一个不同的敌人，
     * 敌人不够就少发几道（见 {@code HatAbilities.beamTargets}）。
     */
    public static int conflagrationBeams(ItemStack hat) {
        return 1 + level(hat, Kind.CONFLAGRATION) * CONFLAGRATION_BEAMS_PER_LEVEL;
    }

    /**
     * 「掌控」这次能剥夺索敌的目标数（没附魔 0；I 级 5 人，每级 +5，封顶 15 人）。
     */
    public static int dominionTargets(ItemStack hat) {
        int level = level(hat, Kind.DOMINION);
        return level <= 0 ? 0 : Math.min(level * DOMINION_TARGETS_PER_LEVEL, DOMINION_MAX_TARGETS);
    }

    /** 「闪避」额外给的瞬移闪避次数（基础次数见 {@code HatAbilities.BLINKS_PER_CHARGE}）。 */
    public static int extraBlinks(ItemStack hat) {
        return level(hat, Kind.FORMLESS) * FORMLESS_BLINKS_PER_LEVEL;
    }

    /**
     * 「预知」有没有附上（≥ I 级才触发）。
     *
     * <p>等级不再只决定「有没有」：持续时长也按等级递进，见
     * {@link #soulReapTicks(ItemStack, int)}。
     */
    public static boolean hasSoulReap(ItemStack hat) {
        return level(hat, Kind.SOUL_REAP) > 0;
    }

    /**
     * 「预知」持续多久（刻）：按附魔等级取 {@code maxTicks} 的 1/3、2/3、3/3。
     *
     * <p>{@code maxTicks} 是调用方给的「本轮循环还剩多少刻」—— 也就是从蓄力完成释放那一刻，
     * 一直到本轮照射结束。**III 级正好吃满这个最大值（最大值不变）**，I/II 级依次缩短。
     * 没附魔「预知」时返回 0（不触发）。
     */
    public static int soulReapTicks(ItemStack hat, int maxTicks) {
        int level = level(hat, Kind.SOUL_REAP);
        if (level <= 0 || maxTicks <= 0) {
            return 0;
        }
        return Math.max(1, maxTicks * Math.min(level, MAX_LEVEL) / MAX_LEVEL);
    }

    /**
     * 「光辉」有没有附上（≥ I 级就发射击期间的「神隐」支援；等级只决定「神隐」时长，
     * 不再加光柱伤害 —— 见 {@link #veilDurationTicks}）。
     */
    public static boolean hasRadiance(ItemStack hat) {
        return level(hat, Kind.RADIANCE) > 0;
    }

    /** 「吸血」：把光柱造成伤害的多少比例转成治疗；没附魔返回 0。 */
    public static float bloodthirstLifesteal(ItemStack hat) {
        return level(hat, Kind.BLOODTHIRST) * BLOODTHIRST_LIFESTEAL_PER_LEVEL;
    }

    /**
     * 「残影」给的**照射期**瞬移闪避次数。
     *
     * <p>和「闪避」是一对：闪避管蓄力（挨打也能安心蓄力），残影管照射
     * （正站着放光柱、最容易被集火的那一段）。躲开的伤害同样**完全落空**，
     * 不只是「先挨一下再传送走」—— 判据和实现都在 {@code HatAbilities#onIncomingDamage}。
     */
    public static int afterimageBlinks(ItemStack hat) {
        return level(hat, Kind.AFTERIMAGE) * AFTERIMAGE_BLINKS_PER_LEVEL;
    }

    /** 「缓速」给时间差力场的半径加成（格）。 */
    public static double slowRadiusBonus(ItemStack hat) {
        return level(hat, Kind.REQUIEM) * REQUIEM_RADIUS_PER_LEVEL;
    }

    /** 「疾行」给永久迅捷/跳跃的等级加成。 */
    public static int permanentBuffBonus(ItemStack hat) {
        return level(hat, Kind.BENEDICTION) * BENEDICTION_AMPLIFIER_PER_LEVEL;
    }

    /** 「掌控」剥夺索敌的时长（刻）；没附魔返回 0，也就是不触发。 */
    public static int dominionTicks(ItemStack hat) {
        int level = level(hat, Kind.DOMINION);
        return level <= 0 ? 0 : DOMINION_LOSE_TARGET_BASE_TICKS + (level - 1) * DOMINION_LOSE_TARGET_PER_LEVEL_TICKS;
    }

    /** 九个附魔的注册名 + 归属。注册名同时也是 JSON 文件名和语言文件的键。 */
    public enum Kind {
        FORMLESS(HatType.BLACK, "formless"),
        AFTERIMAGE(HatType.BLACK, "afterimage"),
        SOUL_REAP(HatType.BLACK, "soul_reap"),
        REQUIEM(HatType.WHITE, "requiem"),
        BENEDICTION(HatType.WHITE, "benediction"),
        RADIANCE(HatType.WHITE, "radiance"),
        DOMINION(HatType.RED, "dominion"),
        BLOODTHIRST(HatType.RED, "bloodthirst"),
        CONFLAGRATION(HatType.RED, "conflagration");

        private final HatType hat;
        private final String id;
        private final ResourceKey<Enchantment> key;

        Kind(HatType hat, String id) {
            this.hat = hat;
            this.id = id;
            this.key = ResourceKey.create(Registries.ENCHANTMENT, HatMod.id(id));
        }

        /** 这个附魔属于哪顶帽子。 */
        public HatType hat() {
            return this.hat;
        }

        /**
         * 这顶帽子能不能附上这个专属附魔。
         *
         * <p>「神之牛仔帽（全）」对**全部 9 个专属附魔**开放 —— 它本来就是把三顶合一，
         * 自然兼容所有专属；这是「全」最核心的价值，也是它唯一的通用性来源。
         * （数据层面靠 JSON 里 {@code supported_items} 写成数组来放开，见
         * {@code data/hatmod/enchantment/*.json}。）
         */
        public boolean accepts(HatType candidate) {
            return candidate == this.hat || candidate == HatType.ALL;
        }

        /** 注册名（不含命名空间），也是 {@code data/hatmod/enchantment/<id>.json} 的文件名。 */
        public String id() {
            return this.id;
        }

        public ResourceKey<Enchantment> key() {
            return this.key;
        }

        public String translationKey() {
            return "enchantment." + HatMod.MOD_ID + "." + this.id;
        }
    }
}
