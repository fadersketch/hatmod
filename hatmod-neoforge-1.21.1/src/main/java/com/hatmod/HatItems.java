package com.hatmod;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.ArmorMaterial.Layer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Item.Properties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item.TooltipContext;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredRegister.Items;

/**
 * 三顶牛仔帽，外加三顶合一的「全」。机制相同，但蓄力/照射时长、每刻伤害和专属效果各不相同，
 * 具体数值集中在 {@link HatType}。
 *
 * <p>1.21.1 的护甲材质是数据驱动的 {@link ArmorMaterial} record：护甲贴图路径由材质注册名决定
 * （{@code hatmod:black_hat} 会去加载 {@code hatmod:textures/models/armor/black_hat_layer_1.png}），
 * 所以四顶帽子各注册一份材质，只是名字不同。「全」的贴图随本轮路线在
 * {@code getArmorTexture} 里现算。
 */
public final class HatItems {

    /**
     * 帽子的护甲值（原版头盔那一档）。
     *
     * <p>戴在**头盔槽**时由 {@code ArmorItem} 自己给；放在 **Curios 饰品栏**时 Curios 不结算
     * 物品自带的护甲属性，由 {@code HatAbilities.applyCurioArmor} 按这个数补一条。
     */
    public static final int HAT_ARMOR = 2;

    /**
     * 两顶帽子自带的基础**盔甲韧性**：40 —— 一整套下界合金也才 12。
     *
     * <p>帽子本身只有 2 点护甲，真正扛大伤害的是这一条：韧性压的是「穿甲 / 破甲」那部分
     * （高单发、法术穿透、Boss 真伤都从这里走）。护甲值那 2 点在穿甲面前基本等于没有，
     * 所以把韧性拉得很高，帽子才是「一件硬得离谱的布」。
     */
    public static final float HAT_TOUGHNESS = 40.0F;

    public static final DeferredRegister<ArmorMaterial> ARMOR_MATERIALS =
            DeferredRegister.create(Registries.ARMOR_MATERIAL, HatMod.MOD_ID);
    public static final Items ITEMS = DeferredRegister.createItems(HatMod.MOD_ID);

    private static final DeferredHolder<ArmorMaterial, ArmorMaterial> BLACK_MATERIAL =
            material("black_hat");
    private static final DeferredHolder<ArmorMaterial, ArmorMaterial> WHITE_MATERIAL =
            material("white_hat");
    private static final DeferredHolder<ArmorMaterial, ArmorMaterial> RED_MATERIAL =
            material("red_hat");
    private static final DeferredHolder<ArmorMaterial, ArmorMaterial> ALL_MATERIAL =
            material("all_hat");
    private static final DeferredHolder<ArmorMaterial, ArmorMaterial> BRAIN_MATERIAL =
            material("brain_hat");

    public static final DeferredHolder<Item, Item> BLACK_HAT = hat("black_hat", BLACK_MATERIAL);
    public static final DeferredHolder<Item, Item> WHITE_HAT = hat("white_hat", WHITE_MATERIAL);
    public static final DeferredHolder<Item, Item> RED_HAT = hat("red_hat", RED_MATERIAL);
    /** 「神之牛仔帽（全）」：三顶合一，始终带附魔光效（见 {@link #hat} 的 isFoil）。 */
    public static final DeferredHolder<Item, Item> ALL_HAT = hat("all_hat", ALL_MATERIAL);

    /** 创造模式专属的调参器：没有合成表，只能从创造物品栏里拿。 */
    public static final DeferredHolder<Item, Item> TUNER = ITEMS.register("tuner", HatTunerItem::new);

    /**
     * 「神秘红毛钻头」：脑蚀的**中间产物**，没有自己的机制，纯粹是价格的一环。
     *
     * <p>四个摆成帽子形状才是脑蚀 —— 也就是说一顶脑蚀 = 四份「皮革 + 回响碎片 + 下界之星 +
     * 幽匿催发体」。它不是帽子：{@link #isHat} 里不能放行它，否则戴上它会顶掉真正的帽子。
     */
    public static final DeferredHolder<Item, Item> MYSTERIOUS_DRILL =
            ITEMS.register("mysterious_drill", id -> new Item(new Properties()) {
                @Override
                public void appendHoverText(ItemStack stack, TooltipContext context,
                                            List<Component> tooltip, TooltipFlag flag) {
                    tooltip.add(Component.translatable("hatmod.drill.desc")
                            .withStyle(ChatFormatting.GRAY));
                    tooltip.add(Component.translatable("hatmod.drill.usage")
                            .withStyle(ChatFormatting.GRAY));
                }
            });

    /**
     * 「后室 Teto」的刷怪蛋：只放在创造物品栏里。
     *
     * <p>放出来的那只是**没有主人**的 —— {@link Teto#tick()} 会认最近 32 格内的玩家当主人，
     * 然后一直绕着它转（不要求戴着脑蚀、也不随曲子消失）；主人死亡或它被打死才会散。
     */
    public static final DeferredHolder<Item, Item> TETO_SPAWN_EGG =
            ITEMS.register("backrooms_teto_spawn_egg",
                    id -> new DeferredSpawnEggItem(
                            HatSummons.TETO::get, 0x2B0B18, 0xD8323C, new Properties()) {
                        @Override
                        public int getColor(int tintIndex) {
                            // 自带专属贴图（红毛钻头），别再让原版按双层颜色染色
                            return 0xFFFFFF;
                        }

                        @Override
                        public void appendHoverText(ItemStack stack, TooltipContext context,
                                                    List<Component> tooltip, TooltipFlag flag) {
                            tooltip.add(Component.translatable("hatmod.teto.desc")
                                    .withStyle(ChatFormatting.LIGHT_PURPLE));
                            addUsageLines(tooltip, "hatmod.teto.usage", 1, 8);
                        }
                    });

    /**
     * 「Khragg」的刷怪蛋：他是开发者的化身，做彩蛋真好玩 —— 只能从这里 / 指令生成。
     */
    public static final DeferredHolder<Item, Item> KHragg_SPAWN_EGG =
            ITEMS.register("khragg_spawn_egg",
                    id -> new DeferredSpawnEggItem(
                            HatSummons.KHragg::get, 0xB03030, 0x274B8F, new Properties()) {
                        @Override
                        public int getColor(int tintIndex) {
                            // 自带专属头像贴图，别再让原版按双层颜色染色
                            return 0xFFFFFF;
                        }

                        @Override
                        public void appendHoverText(ItemStack stack, TooltipContext context,
                                                    List<Component> tooltip, TooltipFlag flag) {
                            tooltip.add(Component.translatable("hatmod.khragg.desc")
                                    .withStyle(ChatFormatting.GOLD));
                            addUsageLines(tooltip, "hatmod.khragg.usage", 1, 3);
                        }
                    });

    /**
     * 唐嘉琦（白色）的刷怪蛋：放出来的是「野生」个体。
     *
     * <p>不认主：自己索敌（同白帽）、自己蓄力照射、自己放白帽的 BGM，节奏和白帽一样；
     * 每照完一轮闪避补满 6 次。贴图直接用她蓄力时那张立绘（见 {@code models/item/}）。
     */
    public static final DeferredHolder<Item, Item> TANG_WHITE_SPAWN_EGG =
            ITEMS.register("tang_white_spawn_egg",
                    id -> new DeferredSpawnEggItem(
                            HatSummons.TANG_WHITE::get, 0xF2F2F2, 0x9AA0A6, new Properties()) {
                        @Override
                        public int getColor(int tintIndex) {
                            // 自带专属贴图（蓄力立绘），别再让原版按双层颜色染色
                            return 0xFFFFFF;
                        }

                        @Override
                        public void appendHoverText(ItemStack stack, TooltipContext context,
                                                    List<Component> tooltip, TooltipFlag flag) {
                            tooltip.add(Component.translatable("hatmod.tang_white.desc")
                                    .withStyle(ChatFormatting.WHITE));
                            addUsageLines(tooltip, "hatmod.tang_white.usage", 1, 3);
                        }
                    });

    /**
     * 「脑蚀」：形状和建模同黑帽（材质/贴图直接复用黑帽那张），
     * 但它走自己那套完全独立的时间线（{@link BrainrotCycle}），不参与三帽/「全」的路线系统。
     */
    public static final DeferredHolder<Item, Item> BRAIN_HAT = hat("brain_hat", BRAIN_MATERIAL);

    private HatItems() {
    }

    /**
     * 注册一份护甲材质：名字就是贴图路径
     * （{@code hatmod:black_hat} → {@code textures/models/armor/black_hat_layer_1.png}）。
     *
     * <p>护甲值/韧性数值只对头盔槽有意义（帽子只占头盔槽），其余槽位归零。
     * 修复材料是皮革（和耐久说明里那句「1 个皮革修复 X 点」对应）。
     */
    private static DeferredHolder<ArmorMaterial, ArmorMaterial> material(String colorName) {
        return ARMOR_MATERIALS.register(colorName, () -> new ArmorMaterial(
                new EnumMap<>(Map.of(ArmorItem.Type.HELMET, HAT_ARMOR,
                        ArmorItem.Type.CHESTPLATE, 0, ArmorItem.Type.LEGGINGS, 0,
                        ArmorItem.Type.BOOTS, 0)),
                15,
                SoundEvents.ARMOR_EQUIP_LEATHER,
                () -> Ingredient.of(net.minecraft.world.item.Items.LEATHER),
                List.of(new Layer(HatMod.id(colorName))),
                HAT_TOUGHNESS,
                0.0F
        ));
    }

    /** 注册一顶帽子：头盔槽 + 自定义耐久（见 HatDurability）。 */
    private static DeferredHolder<Item, Item> hat(String colorName,
                                                  DeferredHolder<ArmorMaterial, ArmorMaterial> material) {
        return ITEMS.register(colorName, () -> new ArmorItem(
                material,
                ArmorItem.Type.HELMET,
                new Properties().durability(HatDurability.MAX_DAMAGE)
        ) {
            @Override
            public void initializeClient(Consumer<IClientItemExtensions> consumer) {
                com.hatmod.client.HatItemClientExtensions.attach(consumer);
            }

            /**
             * 渲染时用哪张护甲贴图。
             *
             * <p>返回 {@code null} = 用默认那张（由材质名决定），三顶原色帽都走这条默认路。
             * 只有「全」会随本轮路线换色：从 {@link HatRouteState} 取它这一轮的路线，
             * 拼出黑/白/红对应的贴图路径。
             */
            @Override
            public ResourceLocation getArmorTexture(ItemStack stack, Entity entity,
                                                    EquipmentSlot slot, Layer layer,
                                                    boolean innerModel) {
                if (entity == null || this != ALL_HAT.get()) {
                    return null;
                }
                HatType route = HatRouteState.routeOf(entity.getId(), HatType.ALL);
                return HatMod.id("textures/models/armor/"
                        + HatRouteState.textureName(route) + "_layer_1.png");
            }

            @Override
            public <T extends LivingEntity> int damageItem(
                    ItemStack stack, int amount, T entity, Consumer<Item> onBroken) {
                // 自定义耐久玩法：3 秒内最多磨损 1 点（见 HatDurability）
                int real = HatDurability.limitDamage(stack, amount, entity);
                // 这一下会把脑蚀磨穿：记一笔。链路那边按「被敌人弄掉的」说一句（见 BrainrotCycle）
                if (real > 0 && entity != null && stack.is(BRAIN_HAT.get())
                        && stack.getDamageValue() + real >= stack.getMaxDamage()) {
                    HatSettings.markHatBroken(entity.getUUID(), entity.level().getGameTime());
                }
                return real;
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
             * <p>原版显示名字时把「稀有度颜色」套在外层，而 {@code ItemStack#getRarity}
             * 在附魔后会提升一档，名字就会被染成水蓝 / 淡紫。这里给名字本体显式设上金色，
             * 渲染时子级样式优先于外层，所以附魔改不动它。
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
            public void appendHoverText(ItemStack stack, TooltipContext context,
                                        List<Component> tooltip, TooltipFlag flag) {
                // 原版那句「护甲值 +2」在这里被整个覆写掉了（没调 super），自己补一行
                tooltip.add(Component.translatable("item.hatmod.hat.armor", HAT_ARMOR)
                        .withStyle(ChatFormatting.BLUE));
                tooltip.add(Component.translatable("item.hatmod.hat.toughness", (int) HAT_TOUGHNESS)
                        .withStyle(ChatFormatting.BLUE));
                // 标语：「全」用专属的「新神已至」，三顶原色帽是「神本无相」；
                // 脑蚀不摆那句，改标它这一整轮用的 BGM
                if (this == BRAIN_HAT.get()) {
                    tooltip.add(Component.translatable("item.hatmod.brain_hat.desc")
                            .withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.ITALIC));
                    // 曲子来源写在提示里：整轮剧本就是照着这首曲子排的
                    tooltip.add(Component.translatable("item.hatmod.brain_hat.credit")
                            .withStyle(ChatFormatting.DARK_GRAY));
                } else if (this == ALL_HAT.get()) {
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
                // 模组没有说明书：怎么玩全部写在物品上 —— 一条交互一行，触发条件打头
                if (this == BRAIN_HAT.get()) {
                    addUsageLines(tooltip, "item.hatmod.brain_hat.usage", 1, 2);
                    tooltip.add(Component.translatable("item.hatmod.brain_hat.usage3",
                                    com.hatmod.client.QteHud.forceStartKeyName())
                            .withStyle(ChatFormatting.GRAY));
                    tooltip.add(Component.translatable("item.hatmod.brain_hat.usage4",
                                    com.hatmod.client.QteHud.beatKeyName(),
                                    com.hatmod.client.QteHud.useKeyName())
                            .withStyle(ChatFormatting.GRAY));
                    addUsageLines(tooltip, "item.hatmod.brain_hat.usage", 5, 8);
                } else {
                    if (this == ALL_HAT.get()) {
                        addUsageLines(tooltip, "item.hatmod.all_hat.usage", 1, 3);
                        tooltip.add(Component.translatable("item.hatmod.all_hat.usage4",
                                        com.hatmod.client.QteHud.forceStartKeyName())
                                .withStyle(ChatFormatting.GRAY));
                    } else {
                        addUsageLines(tooltip, "item.hatmod.hat.usage", 1, 2);
                        tooltip.add(Component.translatable("item.hatmod.hat.usage3",
                                        com.hatmod.client.QteHud.forceStartKeyName())
                                .withStyle(ChatFormatting.GRAY));
                        if (this == BLACK_HAT.get()) {
                            tooltip.add(Component.translatable("item.hatmod.hat.play.black")
                                    .withStyle(ChatFormatting.GRAY));
                        } else if (this == WHITE_HAT.get()) {
                            tooltip.add(Component.translatable("item.hatmod.hat.play.white")
                                    .withStyle(ChatFormatting.GRAY));
                        } else if (this == RED_HAT.get()) {
                            tooltip.add(Component.translatable("item.hatmod.hat.play.red")
                                    .withStyle(ChatFormatting.GRAY));
                        }
                    }
                }
            }
        });
    }

    /** 连续加 usage{from}..usage{to}（无参数条目）：一条交互一行，GRAY 样式。 */
    private static void addUsageLines(List<Component> tooltip, String base, int from, int to) {
        for (int i = from; i <= to; i++) {
            tooltip.add(Component.translatable(base + i)
                    .withStyle(ChatFormatting.GRAY));
        }
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
        return item == ALL_HAT.get() ? HatType.ALL : null;
    }

    /**
     * 这件东西算不算「可以戴的帽子」。
     *
     * <p>脑蚀也算帽子（它有自己的完整链路）；「神秘红毛钻头」**不算** ——
     * 放行它的话，戴在头盔栏里就会顶掉真正的帽子，链路就断了。
     */
    public static boolean isHat(ItemStack stack) {
        return typeOf(stack) != null || stack.is(BRAIN_HAT.get());
    }

    /** 把物品（和护甲材质）注册进总线。 */
    public static void register(IEventBus modBus) {
        ARMOR_MATERIALS.register(modBus);
        ITEMS.register(modBus);
    }
}
