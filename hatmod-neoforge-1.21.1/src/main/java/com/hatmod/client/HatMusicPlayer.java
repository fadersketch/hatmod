package com.hatmod.client;

import com.hatmod.HatMod;
import com.hatmod.HatMusic;
import com.hatmod.HatSounds;
import com.hatmod.HatType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客户端这边**自己播放**所有帽子 BGM，并按「听者」决定哪一条听得见。
 *
 * <p>规则（{@link ClientHatSettings#ownBgmPriority()} 开启时，默认开启）：
 * <ul>
 *   <li><b>自己的 BGM 绝对优先</b>：自己正在放，就只听得见自己那条；</li>
 *   <li>自己没在放时，<b>只留最后响起的那一条</b>（后手覆盖）。</li>
 * </ul>
 * 关闭该开关时全部照播（原版那种会重叠的行为）。
 *
 * <p>实现上不是"停掉其余的"，而是**把其余的音量压到 0 继续无声播放** —— 这样轮到它时
 * 直接放开音量就能接着响，不用从头重播。之所以能做到，是因为音效引擎对可 tick 的声音
 * 每刻都会重读音量，详见 {@link HatMusicInstance}。
 *
 * <p>为什么必须由客户端自己播：原版的声音包只带"某个位置在响某个音效"，不带
 * "这条音乐属于哪个实体"，服务端广播下来客户端根本分不清哪条是自己的。
 * 所以服务端改成发 {@code HatNetwork.MusicPayload}（谁的 BGM 开始/停了），
 * 客户端在这里给每个戴帽者建一条自己的实例。
 *
 * <p><b>「服务端说到停才停」</b>：{@link #TRACKS} 记的是**「应该响着的 BGM」**，不是
 * 「此刻恰好活着的声道」。声音实例被掐掉（曲终、音效引擎重载、声道被别的音效挤掉、
 * 暂停期间的心跳误判……）时不是把这一条丢掉，而是**原地重建再接着放**；
 * 只有服务端明确发来「停了」、或者戴帽者实体已经不在客户端世界里，才会真正移除。
 * 这样「玩着玩着 BGM 突然没了」就不会再发生 —— 也是「玩家的 BGM 优先级最高」的落点：
 * 自己的那一条无论声道出什么事都一直在名单里，{@link #applyVolumes} 里就永远轮不到
 * 别人来顶它的位置。
 */
@EventBusSubscriber(modid = HatMod.MOD_ID, value = Dist.CLIENT)
public final class HatMusicPlayer {

    /** 心跳多久没跳就认为"这条声道已经没了"（刻）。留得宽一点，避免把暂停/卡顿误判成死亡。 */
    private static final int HEARTBEAT_GRACE = 20;

    /** 重建之后的冷却（刻）：期间不再重试，免得播放失败时每刻都在建实例。 */
    private static final int RESTART_COOLDOWN = 20;

    /** 戴帽者实体 id -> 它那条 BGM。{@link LinkedHashMap} 的插入顺序 = 响起顺序。 */
    private static final Map<Integer, Track> TRACKS = new LinkedHashMap<>();

    /** 客户端刻计数，给 {@link HatMusicInstance} 记心跳用。 */
    private static int ticks;

    /** 一条 BGM 的全部信息：跟着谁、放哪首、以及此刻正在响的那个声音实例。 */
    private static final class Track {
        private final Entity wearer;
        private final HatType type;
        private HatMusicInstance sound;
        private int retryAt;

        private Track(Entity wearer, HatType type) {
            this.wearer = wearer;
            this.type = type;
        }
    }

    private HatMusicPlayer() {
    }

    static int ticks() {
        return ticks;
    }

    /** 服务端通知：某个戴帽者的 BGM 开始 / 停止了。 */
    public static void handle(int wearerId, int ordinal, boolean playing) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        Track track = TRACKS.get(wearerId);
        if (!playing) {
            if (track != null) {
                stop(minecraft, track);
                TRACKS.remove(wearerId);
            }
            applyVolumes(minecraft);
            return;
        }

        HatType[] types = HatType.values();
        HatType type = types[ordinal >= 0 && ordinal < types.length ? ordinal : 0];
        Entity wearer = minecraft.level.getEntity(wearerId);
        if (wearer == null) {
            // 客户端世界里没有这个实体（离得远没同步过来）。这时本来也听不见，直接不播。
            return;
        }

        // 同一轮循环重开会再发一次开始：先把上一条换掉，免得两条叠在一起
        if (track != null) {
            stop(minecraft, track);
        }
        track = new Track(wearer, type);
        TRACKS.put(wearerId, track);
        start(minecraft, track);
    }

    /** 建一条声道并起播（该被压住的那条靠 {@code canStartSilent} 也能正常建）。 */
    private static void start(Minecraft minecraft, Track track) {
        track.sound = new HatMusicInstance(HatSounds.forType(track.type), track.wearer);
        track.retryAt = ticks + RESTART_COOLDOWN;
        // 先定音量再起播，免得"一出生就该被压住"的那条响出一声再被压下去
        applyVolumes(minecraft);
        minecraft.getSoundManager().play(track.sound);
    }

    private static void stop(Minecraft minecraft, Track track) {
        if (track.sound != null) {
            minecraft.getSoundManager().stop(track.sound);
            track.sound = null;
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (TRACKS.isEmpty()) {
            return;
        }

        // 暂停（Esc / 失焦 / 单人暂停）时**不能**推进心跳、也不能判定死亡：
        // 原版在暂停时不 tick 声音引擎（Minecraft.tick -> SoundManager.tick(paused) ->
        // SoundEngine 直接跳过 tickNonPaused），所以声音实例的 tick() 不会跑、心跳停在原地；
        // 而 ClientTickEvent 暂停时照常触发。两边一叠加，暂停几刻之后所有 BGM 都会被
        // 误判成"心跳停了" —— 这就是最早那版「玩着玩着 BGM 突然没了」的来源。
        // 暂停期间原样保留，恢复后声音引擎接着 tick，心跳自然跟上。
        if (minecraft.isPaused()) {
            return;
        }
        ticks++;

        Iterator<Map.Entry<Integer, Track>> iterator = TRACKS.entrySet().iterator();
        while (iterator.hasNext()) {
            Track track = iterator.next().getValue();
            // 戴帽者已经不在这个世界了（死了 / 卸载 / 换维度）：服务端不会再发停止包，
            // 这里自己收掉。这是**唯一**会因为"声道死了"而移除名单的情况。
            if (track.wearer.isRemoved()) {
                stop(minecraft, track);
                iterator.remove();
                continue;
            }
            if (isAlive(track) || ticks < track.retryAt) {
                continue;
            }
            // 服务端还说要响，但这声道已经没了（曲终 / 音效引擎重载 / 被别的音效挤掉）：
            // 原地重建，接着放。宁可重来一遍，也不要静默下去。
            stop(minecraft, track);
            start(minecraft, track);
        }
        applyVolumes(minecraft);
    }

    /**
     * 这条 BGM 现在是不是真的在响。
     *
     * <p>判据是「引擎还在每刻 tick 我」——{@link HatMusicInstance#tick()} 每刻留一次心跳，
     * 心跳一旦停了就说明这条声道已经不在引擎手里了（不管是因为播完了还是被踢了）。
     */
    private static boolean isAlive(Track track) {
        return track.sound != null && !track.sound.isStopped()
                && ticks - track.sound.lastHeartbeat() <= HEARTBEAT_GRACE;
    }

    /** 每刻重算「该听见谁」，其余一律压到 0。 */
    private static void applyVolumes(Minecraft minecraft) {
        if (TRACKS.isEmpty()) {
            return;
        }
        boolean priority = ClientHatSettings.ownBgmPriority();
        Track winner = null;
        if (priority && minecraft.player != null) {
            // 自己的那一条只要还在名单里就永远是赢家 —— 哪怕它的声道此刻正在重建
            // （见类注释：这是"自己的 BGM 优先级最高"最要紧的一半）。
            Track own = TRACKS.get(minecraft.player.getId());
            winner = own != null ? own : newestInRange(minecraft);
        }
        for (Track track : TRACKS.values()) {
            if (track.sound != null) {
                track.sound.setMuted(priority && track != winner);
            }
        }
    }

    /**
     * 听得见的那些里**最后响起**的一条（{@code LinkedHashMap} 迭代顺序 = 响起顺序，
     * 所以不停覆盖就行）；一条都听不见就返回 null。
     *
     * <p>要卡距离是因为服务端是"开始那一刻"按 48 格筛选的：之后戴帽者可能走远，
     * 那一条已经听不见了，不该再压着近处那条不让响。
     */
    private static Track newestInRange(Minecraft minecraft) {
        if (minecraft.player == null) {
            return null;
        }
        double radiusSq = HatMusic.HEAR_RADIUS * HatMusic.HEAR_RADIUS;
        Track newest = null;
        for (Track track : TRACKS.values()) {
            double dx = track.wearer.getX() - minecraft.player.getX();
            double dy = track.wearer.getY() - minecraft.player.getY();
            double dz = track.wearer.getZ() - minecraft.player.getZ();
            if (dx * dx + dy * dy + dz * dz <= radiusSq) {
                newest = track;
            }
        }
        return newest;
    }
}
