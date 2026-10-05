package com.hatmod;

import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 把四顶帽子 + 调参器放进原版的「战斗 / 装备」创造模式物品栏。 */
@Mod.EventBusSubscriber(modid = HatMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class HatCreativeTab {

    private HatCreativeTab() {
    }

    @SubscribeEvent
    public static void buildContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.COMBAT) {
            event.accept(HatItems.BLACK_HAT.get());
            event.accept(HatItems.WHITE_HAT.get());
            event.accept(HatItems.RED_HAT.get());
            event.accept(HatItems.ALL_HAT.get());
            event.accept(HatItems.TUNER.get());
        }
    }
}
