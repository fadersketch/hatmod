package com.hatmod;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotResult;

/**
 * 与 Curios 饰品栏对接的那一小块。
 *
 * <p>Curios 对本模组是**软依赖**：没装照样能跑，只是帽子只能戴在头盔槽里。
 * 所以这个类**只有在装了 Curios 时才会被加载** —— 调用它的地方前面都挡着
 * {@code ModList.isLoaded("curios")}（见 {@link HatAbilities#hatStack}），
 * 没装时这个类根本不会被解析，也就不会因为找不到 Curios 的类而报错。
 *
 * <p>「帽子能放进饰品栏」不是靠这里，而是靠物品标签
 * {@code data/curios/tags/items/head.json}（1.21 起目录改名成 {@code tags/item}）：
 * Curios 的 head 槽校验器 {@code curios:tag} 会去查这个标签。这里只管**读**。
 */
final class CuriosCompat {

    private CuriosCompat() {
    }

    /** 饰品栏里的第一顶本模组的帽子；没有就返回空栈。 */
    static ItemStack findHat(LivingEntity entity) {
        // Forge 返回 LazyOptional、NeoForge 返回 Optional，两者都有 map/orElse，写法一致。
        return CuriosApi.getCuriosInventory(entity)
                .map(handler -> handler.findFirstCurio(HatItems::isHat)
                        .map(SlotResult::stack)
                        .orElse(ItemStack.EMPTY))
                .orElse(ItemStack.EMPTY);
    }
}
