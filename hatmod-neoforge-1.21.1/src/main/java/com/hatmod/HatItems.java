package com.hatmod;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.EnumMap;
import java.util.List;

/**
 * 三个牛仔帽。机制相同，但蓄力/照射时长、每刻伤害和专属效果各不相同，
 * 具体数值集中在 {@link HatType}。
 *
 * <p>1.21.1 的护甲贴图由 {@link ArmorMaterial.Layer} 决定：
 * {@code new ArmorMaterial.Layer(hatmod:black_hat)} 会去加载
 * {@code hatmod:textures/models/armor/black_hat_layer_1.png}。
 */
public final class HatItems {
    public static final DeferredRegister<ArmorMaterial> ARMOR_MATERIALS =
            DeferredRegister.create(Registries.ARMOR_MATERIAL, HatMod.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(HatMod.MOD_ID);

    private static final DeferredHolder<ArmorMaterial, ArmorMaterial> BLACK_MATERIAL = material("black_hat");
    private static final DeferredHolder<ArmorMaterial, ArmorMaterial> WHITE_MATERIAL = material("white_hat");
    private static final DeferredHolder<ArmorMaterial, ArmorMaterial> RED_MATERIAL = material("red_hat");

    public static final DeferredHolder<Item, Item> BLACK_HAT = hat("black_hat", BLACK_MATERIAL);
    public static final DeferredHolder<Item, Item> WHITE_HAT = hat("white_hat", WHITE_MATERIAL);
    public static final DeferredHolder<Item, Item> RED_HAT = hat("red_hat", RED_MATERIAL);

    /** 创造模式专属的调参器：没有合成表，只能从创造物品栏里拿。 */
    public static final DeferredHolder<Item, Item> TUNER = ITEMS.register("tuner", HatTunerItem::new);

    private HatItems() {
    }

    private static DeferredHolder<ArmorMaterial, ArmorMaterial> material(String colorName) {
        return ARMOR_MATERIALS.register(colorName, () -> new ArmorMaterial(
                new EnumMap<>(java.util.Map.of(
                        ArmorItem.Type.HELMET, 2,
                        ArmorItem.Type.CHESTPLATE, 0,
                        ArmorItem.Type.LEGGINGS, 0,
                        ArmorItem.Type.BOOTS, 0
                )),
                15,
                SoundEvents.ARMOR_EQUIP_LEATHER,
                () -> Ingredient.of(net.minecraft.world.item.Items.LEATHER),
                List.of(new ArmorMaterial.Layer(HatMod.id(colorName))),
                0.0F,
                0.0F
        ));
    }

    private static DeferredHolder<Item, Item> hat(String colorName,
                                                   DeferredHolder<ArmorMaterial, ArmorMaterial> material) {
        return ITEMS.register(colorName, () -> new ArmorItem(
                material,
                ArmorItem.Type.HELMET,
                new Item.Properties().durability(HatDurability.MAX_DAMAGE)
        ) {
            @Override
            public void initializeClient(java.util.function.Consumer<net.neoforged.neoforge.client.extensions.common.IClientItemExtensions> consumer) {
                com.hatmod.client.HatItemClientExtensions.attach(consumer);
            }

            @Override
            public <T extends net.minecraft.world.entity.LivingEntity> int damageItem(
                    ItemStack stack, int amount, T entity, java.util.function.Consumer<Item> onBroken) {
                // 自定义耐久玩法：3 秒内最多磨损 1 点（见 HatDurability）
                return HatDurability.limitDamage(stack, amount, entity);
            }

            @Override
            public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                        List<Component> tooltip, TooltipFlag flag) {
                tooltip.add(Component.translatable("item.hatmod.hat.desc")
                        .withStyle(ChatFormatting.GOLD, ChatFormatting.ITALIC));
                tooltip.add(Component.translatable("item.hatmod.hat.durability",
                                HatDurability.MAX_DAMAGE,
                                HatDurability.MIN_INTERVAL_TICKS / 20,
                                HatDurability.REPAIR_PER_LEATHER)
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
        });
    }

    /** 这顶帽子对应哪套数值；不是帽子则返回 null。 */
    public static HatType typeOf(ItemStack stack) {
        Item item = stack.getItem();
        if (item == BLACK_HAT.get()) {
            return HatType.BLACK;
        }
        if (item == WHITE_HAT.get()) {
            return HatType.WHITE;
        }
        if (item == RED_HAT.get()) {
            return HatType.RED;
        }
        return null;
    }

    public static boolean isHat(ItemStack stack) {
        return typeOf(stack) != null;
    }

    public static void register(IEventBus modBus) {
        ARMOR_MATERIALS.register(modBus);
        ITEMS.register(modBus);
    }
}
