package com.hatmod;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 牛仔帽调参器：创造模式专属物品（只放在创造物品栏，没有合成表）。
 *
 * <p>右键打开界面，可以逐顶帽子调整光柱的蓄力/照射时长、每刻固定伤害、
 * 附加百分比伤害及其下限，以及<b>用哪种伤害类型</b>。改动由服务端落盘到
 * {@code config/hatmod.json}。
 *
 * <p>界面本身是纯客户端的东西，所以服务端这里只做「挥手」这一个动作。
 */
public class HatTunerItem extends Item {

    public HatTunerItem() {
        super(new Item.Properties().stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide()) {
            openScreen();
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    /**
     * 只有客户端会走到这里（上面用 {@code isClientSide} 挡过了），所以这个方法体里引用
     * 客户端专属的类不会在服务端被加载。
     */
    private static void openScreen() {
        net.minecraft.client.Minecraft.getInstance().setScreen(
                new com.hatmod.client.HatTunerScreen());
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.hatmod.tuner.desc")
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
    }
}
