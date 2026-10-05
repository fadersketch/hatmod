package com.hatmod;

import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

/** 把三个帽子 + 调参器放进原版的「战斗 / 装备」创造模式物品栏。 */
@EventBusSubscriber(modid = HatMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class HatCreativeTab {

    private HatCreativeTab() {
    }

    @SubscribeEvent
    public static void buildContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.COMBAT) {
            event.accept(HatItems.BLACK_HAT.get());
            event.accept(HatItems.WHITE_HAT.get());
            event.accept(HatItems.RED_HAT.get());
            event.accept(HatItems.TUNER.get());
        }
    }
}
