package com.hatmod.client;

import com.hatmod.HatMusic;
import com.hatmod.HatNetwork;
import net.minecraft.client.resources.sounds.EntityBoundSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;

/**
 * 一条由本模组自己管理的帽子 BGM：跟着戴帽者走、按距离衰减，但**音量随时可调**。
 *
 * <p>原版 {@code EntityBoundSoundInstance} 的音量在创建时就定死了；这里继承它、只改写
 * {@link #getVolume()}，于是「压低 / 放开」变成每刻可做的小事。
 *
 * <p>之所以可行：音效引擎对**可 tick** 的声音每刻都会重新读一次音量并推给声道
 * （{@code SoundEngine.tickNonPaused} 里 {@code calculateVolume(instance)} → {@code channel.setVolume}）。
 * 所以被压到 0 的那条并不是被停掉，而是**无声地继续播**；等轮到它时把音量放回去，
 * 它就从原来的位置接着响 —— 这正是「第 2 个死了要换回第 1 个」想要的效果（不用从头重播）。
 */
public class HatMusicInstance extends EntityBoundSoundInstance {

    private final float baseVolume;
    private boolean muted;
    private int lastHeartbeat;
    /** 这条 BGM 属于谁：谱面的钟要等这条真的开始响再走（见 BrainrotQteClient）。 */
    private final int wearerId;
    /** 「真的响起来了」只回执一次：音效引擎第一次 tick 到我就是它排上了。 */
    private boolean acknowledged;

    public HatMusicInstance(SoundEvent sound, Entity wearer) {
        super(sound, SoundSource.PLAYERS, HatMusic.MUSIC_VOLUME, 1.0F, wearer, wearer.getId());
        this.baseVolume = HatMusic.MUSIC_VOLUME;
        this.wearerId = wearer.getId();
    }

    void setMuted(boolean value) {
        this.muted = value;
    }

    /** 这条 BGM 此刻是不是被压成了无声（别的那条占了位置）。 */
    boolean isMuted() {
        return this.muted;
    }

    int lastHeartbeat() {
        return this.lastHeartbeat;
    }

    /**
     * 允许"以 0 音量起播"。
     *
     * <p>不这样的话，一条"一出生就该被压住"的 BGM（比如自己正在响、别人又开始了）会被引擎
     * 直接跳过、压根不建声道，之后想放开也就没东西可放了。
     */
    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public float getVolume() {
        if (this.muted) {
            return 0.0F;
        }
        // sound 要等引擎 resolve 之后才非空；没解析出来就先用基础音量兜着
        return this.sound != null ? super.getVolume() : this.baseVolume;
    }

    @Override
    public void tick() {
        super.tick();
        // 心跳：引擎每刻都会 tick 我，一旦停了就说明这条已经播完 / 实体没了
        this.lastHeartbeat = HatMusicPlayer.ticks();
        // 引擎真的把我排上了（声音已经响起来）：告诉服务端「这一首从现在开始算」——
        // 白帽的蓄力等这一下才倒数，光柱才能咬住这一轮音乐的拍子（第二轮也一样）
        if (!this.acknowledged) {
            this.acknowledged = true;
            HatNetwork.sendMusicStarted(this.wearerId);
        }
        // 引擎真的把我排上了（声音已经响起来）：自己的谱面钟到这一刻才开始走
        BrainrotQteClient.onSoundRunning(this.wearerId);
    }
}
