package com.hatmod;

import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 三顶帽子各自的循环音乐。
 *
 * <p>音效本体是 {@code assets/hatmod/sounds/music_*.ogg}，由 {@code sounds.json} 登记。
 * 用 {@link SoundEvent#createVariableRangeEvent} 创建，这样声音会随距离衰减，
 * 和玩家自己发出的声音一样是"世界里的一个发声点"，而不是全局背景音乐。
 */
public final class HatSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, HatMod.MOD_ID);

    public static final RegistryObject<SoundEvent> BLACK_MUSIC = register("music_black");
    public static final RegistryObject<SoundEvent> WHITE_MUSIC = register("music_white");
    public static final RegistryObject<SoundEvent> RED_MUSIC = register("music_red");

    private HatSounds() {
    }

    private static RegistryObject<SoundEvent> register(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(HatMod.id(name)));
    }

    /** 这顶帽子的音乐。 */
    public static SoundEvent forType(HatType type) {
        return switch (type) {
            case BLACK -> BLACK_MUSIC.get();
            case WHITE -> WHITE_MUSIC.get();
            case RED -> RED_MUSIC.get();
        };
    }

    public static void register(IEventBus modBus) {
        SOUNDS.register(modBus);
    }
}
