package com.hatmod.client;

import com.hatmod.HatMod;
import com.hatmod.HatMusic;
import com.hatmod.HatSounds;
import com.hatmod.Teto;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.EntityBoundSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Teto「动作 2」的舞曲：客户端跟着那只 Teto 放同一段视频里的 15.77 秒音频。
 *
 * <p><b>音乐是钟，画面从钟上取帧。</b>以前画面按实体自己的 {@code tickCount} 循环
 * （158 帧 × 2 刻 = 15.8 秒），音乐按自己的长度放（15.77 秒），两边各走各的，
 * 再加上声音起播那点延迟，几轮下来就对不上了。现在反过来：
 * <ul>
 *   <li>音乐起播那一刻，画面回到第 0 帧；</li>
 *   <li>每刻相位 +1，画面照旧按「每帧几刻」换帧；</li>
 *   <li>走到最后一帧就**停在那儿**（相位不再涨），等这一段音乐放完；</li>
 *   <li>音乐一停，立刻重开一段、相位归零 —— 这就是下一轮。</li>
 * </ul>
 *
 * <p><b>为什么在客户端放</b>：音量要能随时压到 0 再放开（木棍右键），原版声音包一发包就管不了了。
 * 做法和帽子 BGM 一样（见 {@link HatMusicInstance}）—— 引擎对可 tick 的声音每刻都会重读音量，
 * 所以「静音」不是停掉，而是压到 0 继续无声播：放开时从原处接着响，舞步的钟也不会断。
 *
 * <p>静音开关是**按玩家**的，而「木棍右键」在客户端收不到（两边的交互事件都不发），
 * 所以由服务端收交互、再发一条包回来（见 {@code HatNetwork#sendTetoMusicToggle}）。
 *
 * <p>同一时间只跟最近的那一只 —— 几只 Teto 一起跳、各自放各自的，只会叠成一团。
 */
@Mod.EventBusSubscriber(modid = HatMod.MOD_ID, value = Dist.CLIENT)
public final class TetoDanceMusic {

    /** 会跟着放的范围（格）：她平时就在玩家身边几格转，走远了自然就停。 */
    private static final double HEAR_RADIUS = 32.0D;

    /** 停在最后一帧最多再等多久（刻）：万一这条声道出了状况再也不报「放完了」，别卡死。 */
    private static final int HOLD_GUARD = 60;

    /** 心跳多久没跳就认为「这条声道已经没了」（刻）。和帽子 BGM 同一套判据。 */
    private static final int HEARTBEAT_GRACE = 2;

    /** 客户端刻计数，给 {@link Instance} 记心跳用。 */
    private static int clientTicks;

    /** 正在跟的那只 Teto。 */
    private static Teto target;
    /** 正在跟的是哪一套舞步（同一只换了动作也要重开一段）。 */
    private static int danceAction = -1;
    /** 此刻那条声道。 */
    private static Instance instance;
    /**
     * 这一轮从第 0 帧开始走了多少刻。
     *
     * <p>由**音乐**推进：音乐重开就归零，所以画面和声音永远只有一个来源。
     */
    private static int phase;
    /** 静音开关；{@code null} = 还没从 config/hatmod-client.json 读过。 */
    private static Boolean muted;

    private TetoDanceMusic() {
    }

    /** 现在是不是静音（木棍右键切换）。 */
    public static boolean muted() {
        if (muted == null) {
            muted = TetoRenderSettings.danceMuted();
        }
        return muted;
    }

    /** 静音 ⇄ 恢复，顺手存盘。 */
    private static void toggleMuted() {
        muted = !muted();
        TetoRenderSettings.setDanceMuted(muted);
    }

    /**
     * 服务端发来的「木棍右键了」：切换静音并提示一句。
     *
     * <p>跑在客户端主线程（网络包那边 {@code enqueueWork} 过），所以可以直接动声音和聊天栏。
     */
    public static void onServerToggle() {
        toggleMuted();
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.literal(muted()
                    ? "[Teto] 舞曲已静音（木棍再右击一次恢复）"
                    : "[Teto] 舞曲已恢复"), true);
        }
    }

    /**
     * 这只 Teto 当前的舞步相位（刻）；**不是正在跟的那只**返回 -1 ——
     * 渲染那边就照旧按实体自己的刻数循环。
     */
    static int phaseOf(Entity entity) {
        return entity == target ? phase : -1;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        tick();
    }

    private static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            clear();
            return;
        }
        // 暂停时声音引擎也停着，两边一起停才对得上 —— 别在这里把声道收掉再重开
        if (minecraft.isPaused()) {
            return;
        }
        clientTicks++;
        Teto next = pick(minecraft);
        if (next == null) {
            clear();
            return;
        }
        if (next != target || next.actionIndex() != danceAction) {
            // 换了一只、或者同一只换了舞步：从头开始跳，声音也从头开始
            stop(minecraft);
            target = next;
            danceAction = next.actionIndex();
            phase = 0;
            instance = null;
        }
        if (instance == null) {
            play(minecraft, next);
            return;
        }
        int loop = Math.max(1, TetoRenderSettings.loopTicks(next.actionIndex()));
        if (!isAlive() || phase > loop + HOLD_GUARD) {
            // 这一段放完了（正常循环），或者声道卡住了：重开一段，画面回到第 0 帧
            phase = 0;
            play(minecraft, next);
            return;
        }
        // 相位推进：走到最后一帧就停在那儿，等这段音乐放完再一起重开
        if (phase < loop - 1) {
            phase++;
        }
        // 连跳版不再中途换曲：音轨本身已经按音频对齐拼成一整条
        // （见 HatSounds.TETO_DANCE_JOINED），画面和声音从头到尾同一个来源
    }

    /** 挑要跟的那只：当前这只还在跳就一直跟，不在了再找最近的。 */
    private static Teto pick(Minecraft minecraft) {
        double rangeSq = HEAR_RADIUS * HEAR_RADIUS;
        if (target != null && !target.isRemoved() && isDance(target.actionIndex())
                && target.distanceToSqr(minecraft.player) <= rangeSq) {
            return target;
        }
        Teto best = null;
        double bestSq = rangeSq;
        for (Teto teto : minecraft.level.getEntitiesOfClass(Teto.class,
                minecraft.player.getBoundingBox().inflate(HEAR_RADIUS))) {
            if (teto.isRemoved() || !isDance(teto.actionIndex())) {
                continue;
            }
            double distanceSq = teto.distanceToSqr(minecraft.player);
            if (distanceSq <= bestSq) {
                bestSq = distanceSq;
                best = teto;
            }
        }
        return best;
    }

    /** 这套动作是不是「带舞曲的那几套」（原版那套跟着谱子放，不走这里）。 */
    private static boolean isDance(int action) {
        return action != TetoRenderSettings.ACTION_ORIGINAL;
    }

    /** 每套舞步配自己的曲子；连跳版是拼好的一整条，不用中途换。 */
    private static SoundEvent soundFor(int action) {
        if (action == TetoRenderSettings.ACTION_JOINED) {
            return HatSounds.TETO_DANCE_JOINED.get();
        }
        return action == TetoRenderSettings.ACTION_ACT3
                ? HatSounds.TETO_DANCE3.get()
                : HatSounds.TETO_DANCE.get();
    }

    /**
     * 这条声道现在是不是真的还在放。
     *
     * <p>判据和 {@code HatMusicPlayer} 一样：**引擎还在每刻 tick 我**。只看
     * {@code isStopped()} 靠不住 —— 声道被别的音效挤掉时它不一定会被标成 stopped，
     * 那样舞步就会一直停在最后一帧等一个永远不会来的「放完了」。
     */
    private static boolean isAlive() {
        return instance != null && !instance.isStopped()
                && clientTicks - instance.heartbeat <= HEARTBEAT_GRACE;
    }

    private static void play(Minecraft minecraft, Teto teto) {
        stop(minecraft);
        instance = new Instance(soundFor(teto.actionIndex()), teto);
        minecraft.getSoundManager().play(instance);
    }

    private static void stop(Minecraft minecraft) {
        if (instance != null) {
            minecraft.getSoundManager().stop(instance);
            instance = null;
        }
    }

    private static void clear() {
        stop(Minecraft.getInstance());
        target = null;
        danceAction = -1;
        phase = 0;
    }

    /** 一条跟着 Teto 的舞曲声道：音量每刻现算，静音就是压到 0。 */
    private static final class Instance extends EntityBoundSoundInstance {

        /** 引擎上一次 tick 我是什么时候（客户端刻）。 */
        private int heartbeat;

        private Instance(SoundEvent sound, Entity teto) {
            super(sound, SoundSource.PLAYERS, HatMusic.MUSIC_VOLUME, 1.0F, teto, teto.getId());
            // 从「刚建好」开始计心跳：引擎还没来得及 tick 的前几刻不算它死了
            this.heartbeat = clientTicks;
        }

        @Override
        public void tick() {
            super.tick();
            this.heartbeat = clientTicks;
        }

        /** 允许「以 0 音量起播」：静音状态下也得先把声道建起来，之后放开才有东西可放。 */
        @Override
        public boolean canStartSilent() {
            return true;
        }

        @Override
        public float getVolume() {
            return muted() ? 0.0F : super.getVolume();
        }
    }
}
