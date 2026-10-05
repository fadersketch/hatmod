package com.hatmod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 模组的网络包，一共六种：
 *
 * <ul>
 *   <li>{@code BeamTargetPayload}（S2C）：光柱锁的是哪个敌人。</li>
 *   <li>{@code SettingsSyncPayload}（S2C）：把 {@link HatSettings} 的当前值发给客户端，
 *       界面要拿它显示当前值。玩家一登录就发一次。</li>
 *   <li>{@code SettingsUpdatePayload}（C2S）：调参界面点了「保存」，请求服务端改参数。</li>
 *   <li>{@code MusicPayload}（S2C）：某个戴帽者的 BGM 开始/停止了。</li>
 *   <li>{@code ForesightPayload}（S2C）：黑帽「预知」发动，开始一段「整段持续黑白渲染」的画面。</li>
 *   <li>{@code GreenHeartsPayload}（S2C）：某个玩家当前有多少「绿心」，
 *       客户端的 HUD 拿它画血条上方那一排绿心。</li>
 * </ul>
 */
public final class HatNetwork {

    /** 同步包的 feedback 字段：纯同步，不用给玩家任何提示。 */
    public static final int FEEDBACK_NONE = 0;
    /** 改动已生效。 */
    public static final int FEEDBACK_OK = 1;
    /** 改动被拒（没权限）。 */
    public static final int FEEDBACK_DENIED = 2;

    private HatNetwork() {
    }

    public static void register(IEventBus modBus) {
        modBus.addListener(HatNetwork::onRegisterPayloads);
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToClient(
                BeamTargetPayload.TYPE,
                BeamTargetPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.hatmod.client.BeamTargets.set(
                                payload.wearerId(), payload.beaming(), payload.targetIds())));
        registrar.playToClient(
                SettingsSyncPayload.TYPE,
                SettingsSyncPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.hatmod.client.ClientHatSettings.handleSync(payload)));
        registrar.playToClient(
                MusicPayload.TYPE,
                MusicPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.hatmod.client.HatMusicPlayer.handle(
                                payload.wearerId(), payload.ordinal(), payload.playing())));
        registrar.playToClient(
                ForesightPayload.TYPE,
                ForesightPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.hatmod.client.ForesightFilter.start(payload.ticks())));
        registrar.playToClient(
                GreenHeartsPayload.TYPE,
                GreenHeartsPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.hatmod.client.GreenHeartRender.set(payload.amount())));
        registrar.playToServer(
                SettingsUpdatePayload.TYPE,
                SettingsUpdatePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> handleUpdate(payload, context)));
    }

    /** 通知客户端某个戴帽者「开始/停止照射」，以及它锁定了谁（每道光柱一个 id）。 */
    public static void sendBeamTarget(LivingEntity wearer, boolean beaming, int[] targetIds) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                wearer, new BeamTargetPayload(wearer.getId(), targetIds, beaming));
    }

    /**
     * 通知某个玩家：戴帽者 {@code wearerId} 的 BGM 开始/停止了。
     *
     * <p>音乐改成由客户端自己播放（而不是服务端 {@code playSound} 广播），
     * 因为「只听得见一条 BGM」是**每个听者各自**的事，客户端必须拿得到
     * “这条音乐属于谁”才能判断哪条是自己的 —— 原版的声音包不带这个信息。
     */
    public static void sendMusic(ServerPlayer player, int wearerId, int ordinal, boolean playing) {
        PacketDistributor.sendToPlayer(player, new MusicPayload(wearerId, ordinal, playing));
    }

    /**
     * 通知某个玩家：开始一段黑白「预知」画面，持续 {@code ticks} 刻。
     *
     * <p>只发给**发动者自己** —— 黑白的是他的视角（黑帽的「我早已料到」），
     * 旁边的人看到的还是正常画面。
     */
    public static void sendForesight(ServerPlayer player, int ticks) {
        PacketDistributor.sendToPlayer(player, new ForesightPayload(ticks));
    }

    /**
     * 通知某个玩家：他现在有多少「绿心」（点数，1 颗心 = 2 点）。
     *
     * <p>只发给**本人** —— 绿心是画在自己血条上面的，别人的绿心我们既不显示也无从知道
     * （数值只存在服务端）。每次绿心变多 / 变少（吸血、自然衰减、挨打、清零）都会发一条，
     * 所以客户端拿到的永远是最新值。
     */
    public static void sendGreenHearts(ServerPlayer player, float amount) {
        PacketDistributor.sendToPlayer(player, new GreenHeartsPayload(amount));
    }

    /** 把当前参数发给一个玩家（登录时、以及改完参数后给编辑者回执）。 */
    public static void sendSettings(ServerPlayer player, int feedback) {
        PacketDistributor.sendToPlayer(player, new SettingsSyncPayload(snapshot(), HatSettings.ownBgmPriority(),
                feedback));
    }

    /** 广播给所有人（feedback = 无提示）。 */
    public static void broadcastSettings() {
        PacketDistributor.sendToAllPlayers(new SettingsSyncPayload(snapshot(), HatSettings.ownBgmPriority(),
                FEEDBACK_NONE));
    }

    /**
     * 客户端「保存」按钮：请求服务端改这顶帽子的参数，以及全局开关「自己的 BGM 优先」。
     */
    public static void sendSettingsUpdate(HatType type, int chargeTicks, int flashTicks,
                                          float damagePerTick, float healthDamageRatio,
                                          float healthDamageFloor, String damageType,
                                          boolean ownBgmPriority) {
        // 客户端也留一行日志：排查「点了保存没生效」时，先看日志里有没有这一行，
        // 就能分清到底是「按钮没点到」还是「包没到服务端」。
        HatMod.LOGGER.info("[HatMod] 发出调参请求：{} 蓄力 {}t，照射 {}t，固定伤害 {}，附加 {}，下限 {}，类型 {}，自己的BGM优先 {}",
                type.id(), chargeTicks, flashTicks, damagePerTick, healthDamageRatio, healthDamageFloor, damageType,
                ownBgmPriority);
        PacketDistributor.sendToServer(new SettingsUpdatePayload(type.ordinal(), chargeTicks, flashTicks,
                damagePerTick, healthDamageRatio, healthDamageFloor, damageType,
                ownBgmPriority));
    }

    private static void handleUpdate(SettingsUpdatePayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            HatMod.LOGGER.warn("[HatMod] 收到调参请求但拿不到发送者，已忽略");
            return;
        }
        // 创造模式玩家、或者有 op 权限的：调参本来就是为了调试，别让普通生存玩家改服务器平衡
        if (!player.isCreative() && !player.hasPermissions(2)) {
            HatMod.LOGGER.warn("[HatMod] 玩家 {} 既不是创造模式也没有 OP 权限，已拒绝调参请求",
                    player.getGameProfile().getName());
            sendSettings(player, FEEDBACK_DENIED);
            return;
        }
        HatType[] types = HatType.values();
        int ordinal = payload.ordinal();
        HatType type = types[ordinal >= 0 && ordinal < types.length ? ordinal : 0];
        HatSettings.apply(type, payload.chargeTicks(), payload.flashTicks(),
                payload.damagePerTick(), payload.healthDamageRatio(), payload.healthDamageFloor(),
                payload.damageType());
        // 全局开关跟着一起落盘：这样「保存」一个按钮就把界面上看到的都生效了
        HatSettings.setOwnBgmPriority(payload.ownBgmPriority());
        HatMod.LOGGER.info("[HatMod] {} 参数已更新：蓄力 {}t，照射 {}t，固定伤害 {}，附加 {}，下限 {}，类型 {}；自己的BGM优先 {}",
                type.id(), HatSettings.chargeTicks(type), HatSettings.flashTicks(type),
                HatSettings.damagePerTick(type), HatSettings.healthDamageRatio(type),
                HatSettings.healthDamageFloor(type), HatSettings.damageTypeId(type),
                HatSettings.ownBgmPriority());
        broadcastSettings();
        sendSettings(player, FEEDBACK_OK);
    }

    private static List<SettingsSyncPayload.Snapshot> snapshot() {
        List<SettingsSyncPayload.Snapshot> list = new ArrayList<>();
        for (HatType type : HatType.values()) {
            HatSettings.Entry entry = HatSettings.get(type);
            list.add(new SettingsSyncPayload.Snapshot(type.ordinal(), entry.chargeTicks, entry.flashTicks,
                    entry.damagePerTick, entry.healthDamageRatio, entry.healthDamageFloor, entry.damageType));
        }
        return list;
    }

    // ------------------------------------------------------------------

    /**
     * 一条光柱锁定信息：戴帽者、它锁定的目标 id 列表（每道光柱一个）、是否在照射。
     *
     * <p>红帽附魔「光柱」会同时打多道光柱，所以目标是一个数组而不是单个 id：
     * 客户端按顺序给每道光柱画一条，指向各自的敌人。没有目标时是空数组。
     */
    public record BeamTargetPayload(int wearerId, int[] targetIds, boolean beaming) implements CustomPacketPayload {

        // 注意：createType(String) 只接收 path，会自动补上 minecraft: 命名空间，
        // 传 "hatmod:beam_target" 会变成非法的 minecraft:hatmod:beam_target。这里直接用完整 id。
        public static final CustomPacketPayload.Type<BeamTargetPayload> TYPE =
                new CustomPacketPayload.Type<>(HatMod.id("beam_target"));

        public static final StreamCodec<RegistryFriendlyByteBuf, BeamTargetPayload> STREAM_CODEC =
                StreamCodec.of(BeamTargetPayload::write, BeamTargetPayload::read);

        private static void write(RegistryFriendlyByteBuf buf, BeamTargetPayload payload) {
            buf.writeVarInt(payload.wearerId());
            buf.writeBoolean(payload.beaming());
            int[] ids = payload.targetIds();
            buf.writeVarInt(ids.length);
            for (int id : ids) {
                buf.writeVarInt(id);
            }
        }

        private static BeamTargetPayload read(RegistryFriendlyByteBuf buf) {
            int wearerId = buf.readVarInt();
            boolean beaming = buf.readBoolean();
            int size = buf.readVarInt();
            int[] ids = new int[size];
            for (int i = 0; i < size; i++) {
                ids[i] = buf.readVarInt();
            }
            return new BeamTargetPayload(wearerId, ids, beaming);
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * 一整段「预知」：从释放到本轮循环结束的总时长（刻）。
     *
     * <p>黑白后处理链**整段都挂着**，客户端整段让它保持生效
     * （见 {@code client.ForesightFilter}），
     * 这里只给总时长。也只是「开始」的通知，没有结束包 —— 客户端自己数着刻，到点收尾。
     * 这样服务器卡一下、玩家掉线重连，都不会在客户端留下一张永久黑白的画面。
     */
    public record ForesightPayload(int ticks) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<ForesightPayload> TYPE =
                new CustomPacketPayload.Type<>(HatMod.id("foresight"));

        public static final StreamCodec<RegistryFriendlyByteBuf, ForesightPayload> STREAM_CODEC =
                StreamCodec.of(ForesightPayload::write, ForesightPayload::read);

        private static void write(RegistryFriendlyByteBuf buf, ForesightPayload payload) {
            buf.writeVarInt(payload.ticks());
        }

        private static ForesightPayload read(RegistryFriendlyByteBuf buf) {
            return new ForesightPayload(buf.readVarInt());
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * 某个玩家当前的「绿心」点数（1 颗心 = 2 点）。
     *
     * <p>绿心是本模组自己的抗伤池（不是原版「吸收」），所以客户端不可能自己算出来 ——
     * 服务端每变一次就推一次，客户端只存下来画血条。
     */
    public record GreenHeartsPayload(float amount) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<GreenHeartsPayload> TYPE =
                new CustomPacketPayload.Type<>(HatMod.id("green_hearts"));

        public static final StreamCodec<RegistryFriendlyByteBuf, GreenHeartsPayload> STREAM_CODEC =
                StreamCodec.of(GreenHeartsPayload::write, GreenHeartsPayload::read);

        private static void write(RegistryFriendlyByteBuf buf, GreenHeartsPayload payload) {
            buf.writeFloat(payload.amount());
        }

        private static GreenHeartsPayload read(RegistryFriendlyByteBuf buf) {
            return new GreenHeartsPayload(buf.readFloat());
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SettingsSyncPayload(List<Snapshot> snapshots, boolean ownBgmPriority, int feedback)
            implements CustomPacketPayload {

        /** 一顶帽子的参数快照；ordinal 对应 {@link HatType} 的序号。 */
        public record Snapshot(int ordinal, int chargeTicks, int flashTicks, float damagePerTick,
                               float healthDamageRatio, float healthDamageFloor, String damageType) {
        }

        public static final CustomPacketPayload.Type<SettingsSyncPayload> TYPE =
                new CustomPacketPayload.Type<>(HatMod.id("settings_sync"));

        public static final StreamCodec<RegistryFriendlyByteBuf, SettingsSyncPayload> STREAM_CODEC =
                StreamCodec.of(SettingsSyncPayload::write, SettingsSyncPayload::read);

        private static void write(RegistryFriendlyByteBuf buf, SettingsSyncPayload payload) {
            buf.writeVarInt(payload.feedback());
            buf.writeBoolean(payload.ownBgmPriority());
            buf.writeVarInt(payload.snapshots().size());
            for (Snapshot snapshot : payload.snapshots()) {
                buf.writeVarInt(snapshot.ordinal());
                buf.writeVarInt(snapshot.chargeTicks());
                buf.writeVarInt(snapshot.flashTicks());
                buf.writeFloat(snapshot.damagePerTick());
                buf.writeFloat(snapshot.healthDamageRatio());
                buf.writeFloat(snapshot.healthDamageFloor());
                buf.writeUtf(snapshot.damageType());
            }
        }

        private static SettingsSyncPayload read(RegistryFriendlyByteBuf buf) {
            int feedback = buf.readVarInt();
            boolean ownBgmPriority = buf.readBoolean();
            int size = buf.readVarInt();
            List<Snapshot> list = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                list.add(new Snapshot(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                        buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readUtf()));
            }
            return new SettingsSyncPayload(list, ownBgmPriority, feedback);
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 客户端只发 ordinal，不依赖枚举本身跨端一致（同一版本下当然一致，这里只是更省心）。 */
    public record SettingsUpdatePayload(int ordinal, int chargeTicks, int flashTicks,
                                        float damagePerTick, float healthDamageRatio,
                                        float healthDamageFloor, String damageType,
                                        boolean ownBgmPriority)
            implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<SettingsUpdatePayload> TYPE =
                new CustomPacketPayload.Type<>(HatMod.id("settings_update"));

        public static final StreamCodec<RegistryFriendlyByteBuf, SettingsUpdatePayload> STREAM_CODEC =
                StreamCodec.of(SettingsUpdatePayload::write, SettingsUpdatePayload::read);

        private static void write(RegistryFriendlyByteBuf buf, SettingsUpdatePayload payload) {
            buf.writeVarInt(payload.ordinal());
            buf.writeVarInt(payload.chargeTicks());
            buf.writeVarInt(payload.flashTicks());
            buf.writeFloat(payload.damagePerTick());
            buf.writeFloat(payload.healthDamageRatio());
            buf.writeFloat(payload.healthDamageFloor());
            buf.writeUtf(payload.damageType());
            buf.writeBoolean(payload.ownBgmPriority());
        }

        private static SettingsUpdatePayload read(RegistryFriendlyByteBuf buf) {
            return new SettingsUpdatePayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readUtf(),
                    buf.readBoolean());
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * 一条帽子 BGM 的开始 / 停止。
     *
     * <p>{@code ordinal} 是 {@link HatType} 的序号，客户端据此拿回自己的音效事件；
     * {@code playing=false} 表示这个戴帽者的 BGM 该停了。
     */
    public record MusicPayload(int wearerId, int ordinal, boolean playing) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<MusicPayload> TYPE =
                new CustomPacketPayload.Type<>(HatMod.id("music"));

        public static final StreamCodec<RegistryFriendlyByteBuf, MusicPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, MusicPayload::wearerId,
                        ByteBufCodecs.VAR_INT, MusicPayload::ordinal,
                        ByteBufCodecs.BOOL, MusicPayload::playing,
                        MusicPayload::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
