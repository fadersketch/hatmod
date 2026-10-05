package com.hatmod;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AnvilUpdateEvent;

/**
 * 牛仔帽的耐久规则。
 *
 * <p>三顶帽子走一套自定义的耐久玩法，数值集中在下面几个常量里：
 * <ul>
 *   <li><b>基础耐久 {@link #MAX_DAMAGE}（300）</b> —— 比原版皮帽（77）高得多，
 *       因为下面还有一道「限伤」压着，综合下来其实很耐用。</li>
 *   <li><b>限伤：{@link #MIN_INTERVAL_TICKS} 刻（3 秒）内最多掉 {@link #DAMAGE_PER_INTERVAL} 点耐久</b>
 *       —— 挡在 {@link net.minecraft.world.item.ItemStack#hurtAndBreak} 的前面
 *       （钩子是 {@code Item#damageItem}）。挨得再密，3 秒内也只磨损这么一点。</li>
 *   <li><b>修复：1 个皮革修 {@link #REPAIR_PER_LEATHER}（20）点耐久</b>
 *       —— 拦截 {@link AnvilUpdateEvent} 自己算，**不用原版那套**「每个材料修
 *       最大耐久的 1/4」。原版对 300 耐久的帽子就是一个皮革修 75 点，太离谱了。</li>
 * </ul>
 *
 * <p>限伤的「上次扣耐久的时刻」直接存在物品自己的自定义数据里（键 {@value #KEY}），
 * 不依赖任何全局表 —— 这样多人、多顶帽子、进出存档都不会串。
 * 1.21 物品 NBT 走的是 {@link DataComponents#CUSTOM_DATA} 数据组件。
 */
@EventBusSubscriber(modid = HatMod.MOD_ID)
public final class HatDurability {

    /** 帽子的基础耐久。 */
    public static final int MAX_DAMAGE = 300;
    /** 限伤的时间窗：两次数值扣除之间至少要隔这么多刻（60 刻 = 3 秒）。 */
    public static final int MIN_INTERVAL_TICKS = 60;
    /** 一个时间窗内最多掉多少点耐久。 */
    public static final int DAMAGE_PER_INTERVAL = 1;
    /** 铁砧里 1 个皮革能修多少点耐久。 */
    public static final int REPAIR_PER_LEATHER = 20;

    /** 「上次扣耐久是哪一刻」在物品自定义数据里的键。 */
    private static final String KEY = "hatmod_last_drain";

    private HatDurability() {
    }

    /**
     * 限伤本体，由 {@code HatItems} 里匿名 {@code ArmorItem} 的 {@code damageItem} 调用。
     *
     * <p>返回真正要交给原版的扣除量：距上次扣耐久不足 {@link #MIN_INTERVAL_TICKS} 刻就返回 0
     * （这一下完全不吃耐久），否则记下当前时刻并最多扣 {@link #DAMAGE_PER_INTERVAL} 点。
     *
     * <p>{@code entity} 为空时（理论上护甲不会走这条路）不做限伤，直接放行，
     * 免得因为取不到游戏刻而把耐久扣减整个卡死。
     */
    public static <T extends LivingEntity> int limitDamage(ItemStack stack, int amount, T entity) {
        if (amount <= 0 || entity == null) {
            return amount;
        }
        long now = entity.level().getGameTime();
        long last = lastDrain(stack);
        if (last >= 0L && now - last < MIN_INTERVAL_TICKS) {
            return 0;
        }
        setLastDrain(stack, now);
        return Math.min(amount, DAMAGE_PER_INTERVAL);
    }

    /**
     * 铁砧里用皮革修帽子：1 个皮革 = {@link #REPAIR_PER_LEATHER} 点耐久。
     *
     * <p>只认「左槽是帽子、右槽是皮革」，其它组合（附魔书、两顶帽子互修……）一律不碰，
     * 交回原版处理。
     *
     * <p>注意：一旦这里接管，原版那条分支就整个不跑了 —— 包括**改名**。所以下面
     * 必须自己把名字应用回输出，否则「帽子 + 皮革 + 改名」这种操作会静默丢掉名字。
     * 语义照抄原版：空白 / {@code null} 不动或清掉，非空则设置。
     */
    @SubscribeEvent
    public static void onAnvilUpdate(AnvilUpdateEvent event) {
        ItemStack left = event.getLeft();
        ItemStack right = event.getRight();
        if (left.isEmpty() || right.isEmpty() || !HatItems.isHat(left)) {
            return;
        }
        if (!right.is(Items.LEATHER) || !left.isDamaged()) {
            return;
        }

        int damage = left.getDamageValue();
        // 修满需要几个皮革，最多不超过右槽里现有的数量
        int needed = (damage + REPAIR_PER_LEATHER - 1) / REPAIR_PER_LEATHER;
        int consumed = Math.min(needed, right.getCount());
        if (consumed <= 0) {
            return;
        }
        int repaired = Math.min(damage, consumed * REPAIR_PER_LEATHER);

        ItemStack output = left.copy();
        output.setDamageValue(damage - repaired);
        applyName(left, output, event.getName());
        event.setOutput(output);
        event.setMaterialCost(consumed);
        // 一个皮革一级，和原版「按材料数计费」一个路子
        event.setCost(consumed);
    }

    /**
     * 把铁砧里改的名字应用到输出上，语义照抄原版 {@code AnvilMenu#createResult}：
     * <ul>
     *   <li>名字非空、且和当前显示名不同 —— 才设置自定义名。
     *       （这一条「不同」的判断不能省：不加的话，玩家什么都没改、名字框里就是
     *       默认显示名时，也会被固化成自定义名，从此不跟随语言切换。）</li>
     *   <li>名字为空白 / {@code null} —— 原本有自定义名就清掉。</li>
     * </ul>
     */
    private static void applyName(ItemStack input, ItemStack output, String name) {
        if (name != null && !name.isBlank()) {
            if (!name.equals(input.getHoverName().getString())) {
                output.set(DataComponents.CUSTOM_NAME, Component.literal(name));
            }
        } else if (input.has(DataComponents.CUSTOM_NAME)) {
            output.remove(DataComponents.CUSTOM_NAME);
        }
    }

    private static long lastDrain(ItemStack stack) {
        CustomData data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        return data.copyTag().getLong(KEY);
    }

    private static void setLastDrain(ItemStack stack, long now) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putLong(KEY, now));
    }
}
