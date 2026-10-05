package com.hatmod.client;

import com.hatmod.HatMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

/**
 * 让帽子使用自定义的牛仔帽模型（带帽檐）。
 *
 * <p>护甲贴图路径默认由 {@code ArmorMaterial.getName()} 决定；「全」随路线换色那部分
 * 在 {@code HatItems} 里重写 {@code getArmorTexture} 实现（见 {@code HatRouteState}），
 * 这里只管模型。
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
