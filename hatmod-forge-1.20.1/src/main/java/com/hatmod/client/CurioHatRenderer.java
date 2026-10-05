package com.hatmod.client;

import com.hatmod.HatItems;
import com.hatmod.HatMod;
import com.hatmod.HatRouteState;
import com.hatmod.HatType;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.client.CuriosRendererRegistry;
import top.theillusivec4.curios.api.client.ICurioRenderer;

/**
 * 把帽子画在 Curios 饰品栏「head」槽对应的**头上** —— 放进饰品栏的帽子和戴在头盔槽里
 * 长得一模一样（同一个 {@link CowboyHatModel}、同一张贴图，连宽帽檐都在）。
 *
 * <p>画法是照抄原版 {@code HumanoidArmorLayer} 渲染头盔那一段：把父模型「头」这一节
 * 的姿态 {@link net.minecraft.client.model.geom.ModelPart#copyFrom 抄过来}，然后在**同一个
 * 坐标空间**里直接渲染。部件自己的 {@code translateAndRotate} 由 {@code renderToBuffer}
 * 内部完成，这里不能再转一次 —— 转两遍帽子会飞到天上。
 *
 * <p><b>贴图按本轮路线取</b>：护甲槽那条路走的是 {@code IForgeItem#getArmorTexture}，
 * 而这里绕开了原版护甲层、自己直接画，所以必须**单独**查一次路线
 * （{@link HatRouteState}）——否则饰品栏里的「全」不会跟着变色。
 *
 * <p>只在装了 Curios 时才会被加载：注册入口 {@link #register()} 由客户端初始化
 * 用 {@code ModList.isLoaded("curios")} 挡着（见 {@code HatModClient}）。
 */
public final class CurioHatRenderer implements ICurioRenderer {

    /** 这顶帽子自己的颜色名（三顶原色帽用它；「全」会被本轮路线覆盖）。 */
    private final String colorName;
    private CowboyHatModel model;

    private CurioHatRenderer(String colorName) {
        this.colorName = colorName;
    }

    /** 把四顶帽子登记给 Curios；只有装了 Curios 时才允许调用。 */
    public static void register() {
        CuriosRendererRegistry.register(HatItems.BLACK_HAT.get(),
                () -> new CurioHatRenderer("black_hat"));
        CuriosRendererRegistry.register(HatItems.WHITE_HAT.get(),
                () -> new CurioHatRenderer("white_hat"));
        CuriosRendererRegistry.register(HatItems.RED_HAT.get(),
                () -> new CurioHatRenderer("red_hat"));
        CuriosRendererRegistry.register(HatItems.ALL_HAT.get(),
                () -> new CurioHatRenderer("all_hat"));
    }

    /** 和护甲槽用的是同一张贴图（{@code ArmorMaterial.getName()} 定位的那张）。 */
    private static ResourceLocation armorTexture(String colorName) {
        return HatMod.id("textures/models/armor/" + colorName + "_layer_1.png");
    }

    @Override
    public <T extends LivingEntity, M extends EntityModel<T>> void render(
            ItemStack stack, SlotContext slotContext, PoseStack poseStack,
            RenderLayerParent<T, M> renderLayerParent, MultiBufferSource buffer, int packedLight,
            float limbSwing, float limbSwingAmount, float partialTicks, float ageInTicks,
            float netHeadYaw, float headPitch) {
        if (!(renderLayerParent.getModel() instanceof HumanoidModel<?> humanoid)) {
            return;
        }
        // 「全」随本轮路线换色：从实体 id 查路线；查不到就用帽子原色。
        HatType own = HatItems.typeOf(stack);
        HatType route = own == HatType.ALL
                ? HatRouteState.routeOf(slotContext.entity().getId(), HatType.ALL)
                : own;
        String name = own == HatType.ALL ? HatRouteState.textureName(route) : this.colorName;

        CowboyHatModel hat = model();
        hat.head.copyFrom(humanoid.head);
        VertexConsumer consumer = buffer.getBuffer(RenderType.armorCutoutNoCull(armorTexture(name)));
        hat.renderToBuffer(poseStack, consumer, packedLight, OverlayTexture.NO_OVERLAY,
                1.0F, 1.0F, 1.0F, 1.0F);
    }

    /** 模型按需烘焙；图层定义由 {@code HatModClient} 注册，晚于它的第一次渲染才会走到这里。 */
    private CowboyHatModel model() {
        if (model == null) {
            model = new CowboyHatModel(
                    Minecraft.getInstance().getEntityModels().bakeLayer(CowboyHatModel.LAYER));
        }
        return model;
    }
}
