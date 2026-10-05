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
 * 三顶牛仔帽，外加三顶合一的「全」。机制相同，但蓄力/照射时长、每刻伤害和专属效果各不相同，
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
    private static final DeferredHolder<ArmorMaterial, ArmorMaterial> ALL_MATERIAL = material("all_hat");

    public static final DeferredHolder<Item, Item> BLACK_HAT = hat("black_hat", BLACK_MATERIAL);
    public static final DeferredHolder<Item, Item> WHITE_HAT = hat("white_hat", WHITE_MATERIAL);
    public static final DeferredHolder<Item, Item> RED_HAT = hat("red_hat", RED_MATERIAL);
    /** 「神之牛仔帽（全）」：三顶合一，始终带附魔光效（见 {@link #hat} 的 isFoil）。 */
    public static final DeferredHolder<Item, Item> ALL_HAT = hat("all_hat", ALL_MATERIAL);

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

            /**
             * 渲染时用哪张护甲贴图。
             *
             * <p>返回 {@code null} = 用默认那张（由 {@link ArmorMaterial.Layer} 决定），
             * 三顶原色帽都走这条默认路。只有「全」会随本轮路线换色：从 {@link HatRouteState}
             * 取它这一轮的路线，拼出黑/白/红对应的贴图路径。
             *
             * <p>1.21 起护甲贴图走「装备资源」体系，但 NeoForge 21.1 仍保留了这个
             * 逐 ItemStack / 逐实体的覆盖钩子，由 {@code HumanoidArmorLayer} 调用。
             */
            @Override
            public net.minecraft.resources.ResourceLocation getArmorTexture(
                    ItemStack stack, net.minecraft.world.entity.Entity entity,
                    net.minecraft.world.entity.EquipmentSlot slot,
                    ArmorMaterial.Layer layer, boolean innerModel) {
                if (entity == null || this != ALL_HAT.get()) {
                    return null;
                }
                HatType route = HatRouteState.routeOf(entity.getId(), HatType.ALL);
                return HatMod.id("textures/models/armor/"
                        + HatRouteState.textureName(route) + "_layer_1.png");
            }

            @Override
            public <T extends net.minecraft.world.entity.LivingEntity> int damageItem(
                    ItemStack stack, int amount, T entity, java.util.function.Consumer<Item> onBroken) {
                // 自定义耐久玩法：3 秒内最多磨损 1 点（见 HatDurability）
                return HatDurability.limitDamage(stack, amount, entity);
            }

            @Override
            public boolean isFoil(ItemStack stack) {
                // 只有「全」这一顶：不附魔也始终带附魔光效（它是三顶合一的传说物品）。
                // 其余三顶照原版规矩走（有附魔才有光效），否则一眼分不出谁附过魔。
                if (this == ALL_HAT.get()) {
                    return true;
                }
                return super.isFoil(stack);
            }

            /**
             * 物品名的颜色：只有「全」这一顶固定橙色，且不受附魔影响。
             *
             * <p>原版显示名字时是 {@code Component.empty().append(getHoverName())}
             * 再把「稀有度颜色」套在外层（见 {@code ItemStack#getTooltipLines}），而
             * {@code ItemStack#getRarity} 在附魔后会提升一档（COMMON/UNCOMMON→RARE、
             * RARE→EPIC），名字就会被染成水蓝 / 淡紫。这里给名字本体显式设上金色，
             * 渲染时子级样式优先于外层，所以附魔改不动它。
             *
             * <p>{@link ChatFormatting#GOLD}（#FFAA00）就是原版那一档橙金。
             */
            @Override
            public Component getName(ItemStack stack) {
                Component name = super.getName(stack);
                if (this == ALL_HAT.get()) {
                    return name.copy().withStyle(ChatFormatting.GOLD);
                }
                return name;
            }

            @Override
            public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                        List<Component> tooltip, TooltipFlag flag) {
                // 标语：「全」用专属的「新神已至」，三顶原色帽是「神本无相」
                if (this == ALL_HAT.get()) {
                    tooltip.add(Component.translatable("item.hatmod.all_hat.tagline")
                            .withStyle(ChatFormatting.GOLD, ChatFormatting.ITALIC));
                    // 「全」再补一行说明它到底「全」在哪：三顶合一 + 全专属附魔 + 自带光效
                    tooltip.add(Component.translatable("item.hatmod.all_hat.desc")
                            .withStyle(ChatFormatting.LIGHT_PURPLE));
                } else {
                    tooltip.add(Component.translatable("item.hatmod.hat.desc")
                            .withStyle(ChatFormatting.GOLD, ChatFormatting.ITALIC));
                }
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
        if (item == ALL_HAT.get()) {
            return HatType.ALL;
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
