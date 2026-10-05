package com.hatmod;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.List;

/**
 * 三顶牛仔帽，外加三顶合一的「全」。机制相同，但蓄力/照射时长、每刻伤害和专属效果各不相同，
 * 具体数值集中在 {@link HatType}。
 *
 * <p>1.20.1 的护甲贴图路径由 {@link ArmorMaterial#getName()} 决定：
 * 返回 {@code "hatmod:black_hat"} 就会去加载
 * {@code hatmod:textures/models/armor/black_hat_layer_1.png}。
 * 所以四种颜色各自实现一份材质，只是名字不同。
 */
public final class HatItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, HatMod.MOD_ID);

    public static final RegistryObject<Item> BLACK_HAT = create("black_hat");
    public static final RegistryObject<Item> WHITE_HAT = create("white_hat");
    public static final RegistryObject<Item> RED_HAT = create("red_hat");
    /** 「神之牛仔帽（全）」：三顶合一，始终带附魔光效（见 {@link #create} 的 isFoil）。 */
    public static final RegistryObject<Item> ALL_HAT = create("all_hat");

    /** 创造模式专属的调参器：没有合成表，只能从创造物品栏里拿。 */
    public static final RegistryObject<Item> TUNER = ITEMS.register("tuner", HatTunerItem::new);

    private HatItems() {
    }

    private static RegistryObject<Item> create(String colorName) {
        ArmorMaterial material = new HatArmorMaterial(colorName);
        return ITEMS.register(colorName, () -> new ArmorItem(
                material,
                ArmorItem.Type.HELMET,
                new Item.Properties().durability(HatDurability.MAX_DAMAGE)
        ) {
            @Override
            public void initializeClient(java.util.function.Consumer<net.minecraftforge.client.extensions.common.IClientItemExtensions> consumer) {
                com.hatmod.client.HatItemClientExtensions.attach(consumer);
            }

            /**
             * 渲染时用哪张护甲贴图。
             *
             * <p>返回 {@code null} = 用默认那张（由材质名决定），三顶原色帽都走这条默认路。
             * 只有「全」会随本轮路线换色：从 {@link HatRouteState} 取它这一轮的路线，
             * 拼出黑/白/红对应的贴图路径（完整路径，带命名空间与 .png）。
             *
             * @param type Forge 的覆盖层标记；非 {@code null}（染料覆盖层）一律交回默认 ——
             *             本模组帽子不是可染色护甲
             */
            @Override
            public String getArmorTexture(ItemStack stack, net.minecraft.world.entity.Entity entity,
                                          net.minecraft.world.entity.EquipmentSlot slot, String type) {
                if (type != null || entity == null || this != ALL_HAT.get()) {
                    return null;
                }
                HatType route = HatRouteState.routeOf(entity.getId(), HatType.ALL);
                return HatMod.MOD_ID + ":textures/models/armor/"
                        + HatRouteState.textureName(route) + "_layer_1.png";
            }

            @Override
            public <T extends net.minecraft.world.entity.LivingEntity> int damageItem(
                    ItemStack stack, int amount, T entity, java.util.function.Consumer<T> onBroken) {
                // 自定义耐久玩法：3 秒内最多磨损 1 点（见 HatDurability）
                return HatDurability.limitDamage(stack, amount, entity);
            }

            @Override
            public boolean isEnchantable(ItemStack stack) {
                // 原版判定是「不可堆叠 + 有耐久」，ArmorItem 这两条本来就满足；
                // 显式写出来，保证附魔台/铁砧永远认这顶帽子，不会因为以后改材质或属性而失效。
                return stack.getMaxStackSize() == 1;
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
            public void appendHoverText(ItemStack stack, Level level,
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
        ITEMS.register(modBus);
    }

    /** 只在头盔部位提供 2 点护甲，其余为 0；贴图名带命名空间以便定位到 hatmod 自己的贴图。 */
    private record HatArmorMaterial(String colorName) implements ArmorMaterial {
        @Override
        public int getDurabilityForType(ArmorItem.Type type) {
            // 1.20.1 没有 ArmorItem.Type#getDurability，直接用「12 × 头盔系数 11」的结果。
            // 帽子走自定义耐久（HatDurability.MAX_DAMAGE = 300），这里不再用原版系数。
            return type == ArmorItem.Type.HELMET ? HatDurability.MAX_DAMAGE : 0;
        }

        @Override
        public int getDefenseForType(ArmorItem.Type type) {
            return type == ArmorItem.Type.HELMET ? 2 : 0;
        }

        @Override
        public int getEnchantmentValue() {
            return 15;
        }

        @Override
        public SoundEvent getEquipSound() {
            return SoundEvents.ARMOR_EQUIP_LEATHER;
        }

        @Override
        public Ingredient getRepairIngredient() {
            return Ingredient.of(net.minecraft.world.item.Items.LEATHER);
        }

        @Override
        public String getName() {
            // 带命名空间：Forge 会解析成 hatmod:textures/models/armor/<colorName>_layer_1.png
            return HatMod.MOD_ID + ":" + colorName;
        }

        @Override
        public float getToughness() {
            return 0.0F;
        }

        @Override
        public float getKnockbackResistance() {
            return 0.0F;
        }
    }
}
