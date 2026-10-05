package com.hatmod;

import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 本模组自己的配方序列化器。
 *
 * <p>目前只有一个：{@link HatFusionRecipe}（三顶合一）。它继承
 * {@link net.minecraft.world.item.crafting.CustomRecipe}，用原版的
 * {@link SimpleCraftingRecipeSerializer} 就够了 —— 那个序列化器只负责读
 * {@code category} 字段、再调 {@code new HatFusionRecipe(id, category)}，
 * 具体逻辑全在配方类里。数据包文件是
 * {@code data/hatmod/recipes/all_hat.json}（1.21 起目录改名成 {@code recipe}）。
 */
public final class HatRecipes {

    public static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
            DeferredRegister.create(ForgeRegistries.RECIPE_SERIALIZERS, HatMod.MOD_ID);

    /** 注册名同时也是数据包里 {@code "type"} 字段要写的值。 */
    public static final RegistryObject<RecipeSerializer<?>> FUSION_SERIALIZER =
            SERIALIZERS.register("hat_fusion", () -> new SimpleCraftingRecipeSerializer<>(HatFusionRecipe::new));

    private HatRecipes() {
    }

    public static void register(IEventBus modBus) {
        SERIALIZERS.register(modBus);
    }
}
