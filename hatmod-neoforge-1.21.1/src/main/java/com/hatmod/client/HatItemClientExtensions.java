package com.hatmod.client;

import com.hatmod.HatMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

/**
 * 让帽子使用自定义的牛仔帽模型（带帽檐）。
 *
 * <p>护甲贴图路径由 {@code ArmorMaterial.Layer} 决定，不需要在这里指定；
 * 这里只负责替换模型。
 */
public final class HatItemClientExtensions {
    private static CowboyHatModel hatModel;

    private HatItemClientExtensions() {
    }

    public static void attach(java.util.function.Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {
            @Override
            public HumanoidModel<?> getHumanoidArmorModel(LivingEntity entity, ItemStack stack,
                                                          EquipmentSlot slot, HumanoidModel<?> original) {
                return model();
            }
        });
    }

    private static CowboyHatModel model() {
        if (hatModel == null) {
            hatModel = new CowboyHatModel(
                    Minecraft.getInstance().getEntityModels().bakeLayer(CowboyHatModel.LAYER));
        }
        return hatModel;
    }
}
