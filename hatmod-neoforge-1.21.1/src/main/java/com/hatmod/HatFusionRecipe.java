package com.hatmod;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * 「三顶合一」合成：黑帽 + 白帽 + 红帽（无定形、位置随意）-> 神之牛仔帽（全）。
 *
 * <p>做成 {@link CustomRecipe} 而不是普通 shapeless：合成结果**不写死在 JSON 里**，
 * 而是由 {@link #assemble} 现算 —— 才能把三顶帽子上各自的附魔**全部继承**到产物上。
 * 普通配方的结果是固定物品栈，做不到这一点。
 *
 * <p>继承规则：
 * <ul>
 *   <li>三顶帽子的附魔**合并**，同一附魔取**最高等级**（不是相加，避免反复合成刷等级）；</li>
 *   <li>专属附魔也一并继承 —— 「全」对全部 9 个专属开放，所以黑帽的「闪避」等照样跟着走；</li>
 *   <li>耐久**取三顶里最完好的那一顶**（损坏值最小），既不惩罚也不奖励。</li>
 * </ul>
 *
 * <p>配方是 {@code isSpecial()}（由 {@link CustomRecipe} 继承而来返回 true）：
 * 不显示在配方书里，但工作台照常认得。
 */
public class HatFusionRecipe extends CustomRecipe {

    public HatFusionRecipe(CraftingBookCategory category) {
        super(category);
    }

    /** 三个位置各放一顶不同的帽子（黑 / 白 / 红），数量不限、位置随意。 */
    @Override
    public boolean matches(CraftingInput input, Level level) {
        return findHats(input) != null;
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        List<ItemStack> hats = findHats(input);
        if (hats == null) {
            return ItemStack.EMPTY;
        }
        ItemStack result = new ItemStack(HatItems.ALL_HAT.get());

        // 附魔合并：1.21 走数据组件，用 Mutable 累加、同一附魔取最高等级
        ItemEnchantments.Mutable merged = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        for (ItemStack hat : hats) {
            for (var entry : EnchantmentHelper.getEnchantmentsForCrafting(hat).entrySet()) {
                merged.upgrade(entry.getKey(), entry.getIntValue());
            }
        }
        EnchantmentHelper.setEnchantments(result, merged.toImmutable());

        // 耐久：沿用三顶里最完好的那顶
        int bestDamage = Integer.MAX_VALUE;
        for (ItemStack hat : hats) {
            if (hat.isDamaged()) {
                bestDamage = Math.min(bestDamage, hat.getDamageValue());
            } else {
                bestDamage = 0;
            }
        }
        if (bestDamage != Integer.MAX_VALUE && bestDamage > 0) {
            result.setDamageValue(bestDamage);
        }
        return result;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= 3;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return HatRecipes.FUSION_SERIALIZER.get();
    }

    /**
     * 从合成格子里挑出「三顶不同的帽子」。凑不齐就返回 null（配方不成立）。
     *
     * <p>只认黑 / 白 / 红三顶原色帽，且**每色恰好一顶**：用「全」再合只会白费
     * （它已经是最好的了），两顶同色也合不出第四色。
     */
    private static List<ItemStack> findHats(CraftingInput input) {
        ItemStack black = null;
        ItemStack white = null;
        ItemStack red = null;
        for (int i = 0; i < input.size(); i++) {
            ItemStack stack = input.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            HatType type = HatItems.typeOf(stack);
            if (type == null || type == HatType.ALL) {
                return null; // 有非帽子物品，或者拿了「全」来合 —— 都不成立
            }
            if (type == HatType.BLACK) {
                if (black != null) {
                    return null;
                }
                black = stack;
            } else if (type == HatType.WHITE) {
                if (white != null) {
                    return null;
                }
                white = stack;
            } else {
                if (red != null) {
                    return null;
                }
                red = stack;
            }
        }
        if (black == null || white == null || red == null) {
            return null;
        }
        List<ItemStack> hats = new ArrayList<>(3);
        hats.add(black);
        hats.add(white);
        hats.add(red);
        return hats;
    }
}
