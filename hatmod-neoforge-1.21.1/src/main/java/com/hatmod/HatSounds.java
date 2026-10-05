package com.hatmod;

import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 三顶帽子各自的循环音乐。
 *
 * <p>音效本体是 {@code assets/hatmod/sounds/music_*.ogg}，由 {@code sounds.json} 登记。
 * 用 {@link SoundEvent#createVariableRangeEvent} 创建，这样声音会随距离衰减，
 * 和玩家自己发出的声音一样是"世界里的一个发声点"，而不是全局背景音乐。
 *
 * <p>「全」没有自己的曲目：它每轮随机取一条路线，就播**那条路线**的曲子
 * （见 {@link #forType} 与 {@code HatAbilities.startCharge}）。
 */
public final class HatSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(Registries.SOUND_EVENT, HatMod.MOD_ID);

    public static final DeferredHolder<SoundEvent, SoundEvent> BLACK_MUSIC = register("music_black");
    public static final DeferredHolder<SoundEvent, SoundEvent> WHITE_MUSIC = register("music_white");
    public static final DeferredHolder<SoundEvent, SoundEvent> RED_MUSIC = register("music_red");

    private HatSounds() {
    }

    private static DeferredHolder<SoundEvent, SoundEvent> register(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(HatMod.id(name)));
    }

    /**
     * 这顶帽子的音乐。传入的是**路线**（{@code HatType.BLACK/WHITE/RED}）——
     * 「全」在服务端就已经把路线算好，这里拿到的自然是路线本身，所以不需要 ALL 分支。
     *
     * <p>刻意写成 if/else 而不是 switch：对枚举做 switch 会让 javac 额外生成一个合成类，
     * 而本模组每个类都要登记进 {@link HatPreloader}（见那个类的注释）—— 少一个类少一处维护。
     */
    public static SoundEvent forType(HatType type) {
        if (type == HatType.BLACK) {
            return BLACK_MUSIC.get();
        }
        if (type == HatType.WHITE) {
            return WHITE_MUSIC.get();
        }
        return RED_MUSIC.get();
    }

    public static void register(IEventBus modBus) {
        SOUNDS.register(modBus);
    }
}
