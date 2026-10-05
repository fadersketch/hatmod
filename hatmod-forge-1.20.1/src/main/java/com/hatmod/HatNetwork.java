package com.hatmod;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 模组的网络包，一共七种：
 *
 * <ul>
 *   <li>{@code BeamTargetMessage}（S2C）：光柱锁的是哪个敌人。</li>
 *   <li>{@code SettingsSyncMessage}（S2C）：把 {@link HatSettings} 的当前值发给客户端，
 *       界面要拿它显示当前值。玩家一登录就发一次。</li>
 *   <li>{@code SettingsUpdateMessage}（C2S）：调参界面点了「保存」，请求服务端改参数。</li>
 *   <li>{@code MusicMessage}（S2C）：某个戴帽者的 BGM 开始/停止了。</li>
 *   <li>{@code ForesightMessage}（S2C）：黑帽「预知」发动，开始一段「整段持续黑白渲染」的画面。</li>
 *   <li>{@code GreenHeartsMessage}（S2C）：某个玩家当前有多少「绿心」，
 *       客户端的 HUD 拿它画血条上方那一排绿心。</li>
 *   <li>{@code RouteMessage}（S2C）：「全」这一轮走的是哪条路线（黑/白/红），
 *       客户端据此把帽子渲染成那个颜色。</li>
 * </ul>
 */
public final class HatNetwork {
    /**
     * 协议版本。
     *
     * <p>调参同步包（{@code SettingsSyncMessage}/{@code SettingsUpdateMessage}）在加入
     * 「全」的路线帧伤 / 强制路线之后，字段变多了 —— 新旧两端互相解码会读错位置，
     * 所以这里从 {@code "1"} 提到 {@code "2"}：版本不一致时 Forge 直接拒绝连接，
     * 而不是让两边拿错值跑。以后只要改动任何包的字段布局，都要把它加一。
     */
    private static final String PROTOCOL = "2";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            HatMod.id("main"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    /** 同步包的 feedback 字段：纯同步，不用给玩家任何提示。 */
    public static final int FEEDBACK_NONE = 0;
    /** 改动已生效。 */
    public static final int FEEDBACK_OK = 1;
    /** 改动被拒（没权限）。 */
    public static final int FEEDBACK_DENIED = 2;

    private HatNetwork() {
    }

    public static void register() {
        CHANNEL.registerMessage(0, BeamTargetMessage.class,
                BeamTargetMessage::encode, BeamTargetMessage::decode, BeamTargetMessage::handle);
        CHANNEL.registerMessage(1, SettingsSyncMessage.class,
                SettingsSyncMessage::encode, SettingsSyncMessage::decode, SettingsSyncMessage::handle);
        CHANNEL.registerMessage(2, SettingsUpdateMessage.class,
                SettingsUpdateMessage::encode, SettingsUpdateMessage::decode, SettingsUpdateMessage::handle);
        CHANNEL.registerMessage(3, MusicMessage.class,
                MusicMessage::encode, MusicMessage::decode, MusicMessage::handle);
        CHANNEL.registerMessage(4, ForesightMessage.class,
                ForesightMessage::encode, ForesightMessage::decode, ForesightMessage::handle);
        CHANNEL.registerMessage(5, GreenHeartsMessage.class,
                GreenHeartsMessage::encode, GreenHeartsMessage::decode, GreenHeartsMessage::handle);
        CHANNEL.registerMessage(6, RouteMessage.class,
                RouteMessage::encode, RouteMessage::decode, RouteMessage::handle);
    }

    /** 通知客户端某个戴帽者「开始/停止照射」，以及它锁定了谁（每道光柱一个 id）。 */
    public static void sendBeamTarget(LivingEntity wearer, boolean beaming, int[] targetIds) {
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> wearer),
                new BeamTargetMessage(wearer.getId(), targetIds, beaming));
    }

    /**
     * 通知某个玩家：戴帽者 {@code wearerId} 的 BGM 开始/停止了。
     *
     * <p>音乐改成由客户端自己播放（而不是服务端 {@code playSound} 广播），
     * 因为「只听得见一条 BGM」是**每个听者各自**的事，客户端必须拿得到
     * “这条音乐属于谁”才能判断哪条是自己的 —— 原版的声音包不带这个信息。
     */
    public static void sendMusic(ServerPlayer player, int wearerId, int ordinal, boolean playing) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new MusicMessage(wearerId, ordinal, playing));
    }

    /**
     * 通知某个玩家：开始一段黑白「预知」画面，持续 {@code ticks} 刻。
     *
     * <p>只发给**发动者自己** —— 黑白的是他的视角（黑帽的「我早已料到」），
     * 旁边的人看到的还是正常画面。
     */
    public static void sendForesight(ServerPlayer player, int ticks) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ForesightMessage(ticks));
    }

    /**
     * 通知某个玩家：他现在有多少「绿心」（点数，1 颗心 = 2 点）。
     *
     * <p>只发给**本人** —— 绿心是画在自己血条上面的，别人的绿心我们既不显示也无从知道
     * （数值只存在服务端）。每次绿心变多 / 变少（吸血、自然衰减、挨打、清零）都会发一条，
     * 所以客户端拿到的永远是最新值。
     */
    public static void sendGreenHearts(ServerPlayer player, float amount) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new GreenHeartsMessage(amount));
    }

    /** 把当前参数发给一个玩家（登录时、以及改完参数后广播）。 */
    public static void sendSettings(ServerPlayer player, int feedback) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new SettingsSyncMessage(snapshot(), HatSettings.ownBgmPriority(), routeSnapshot(), feedback));
    }

    /** 广播给所有人（feedback = 无提示）。 */
    public static void broadcastSettings(MinecraftServer server) {
        if (server == null) {
            return;
        }
        CHANNEL.send(PacketDistributor.ALL.noArg(),
                new SettingsSyncMessage(snapshot(), HatSettings.ownBgmPriority(), routeSnapshot(), FEEDBACK_NONE));
    }

    /**
     * 客户端「保存」按钮：请求服务端改这顶帽子的参数、全局开关「自己的 BGM 优先」，
     * 以及「全」的三条路线帧伤 / 强制路线。
     */
    public static void sendSettingsUpdate(HatType type, int chargeTicks, int flashTicks,
                                          float damagePerTick, float healthDamageRatio,
                                          float healthDamageFloor, String damageType,
                                          boolean ownBgmPriority,
                                          float routeBlack, float routeWhite, float routeRed,
                                          String forcedRoute) {
        // 客户端也留一行日志：排查「点了保存没生效」时，先看日志里有没有这一行，
        // 就能分清到底是「按钮没点到」还是「包没到服务端」。
        HatMod.LOGGER.info("[HatMod] 发出调参请求：{} 蓄力 {}t，照射 {}t，固定伤害 {}，附加 {}，下限 {}，类型 {}，"
                        + "自己的BGM优先 {}；路线帧伤 黑{} 白{} 红{}，强制路线 {}",
                type.id(), chargeTicks, flashTicks, damagePerTick, healthDamageRatio, healthDamageFloor, damageType,
                ownBgmPriority, routeBlack, routeWhite, routeRed, forcedRoute);
        CHANNEL.sendToServer(new SettingsUpdateMessage(type, chargeTicks, flashTicks,
                damagePerTick, healthDamageRatio, healthDamageFloor, damageType,
                ownBgmPriority, routeBlack, routeWhite, routeRed, forcedRoute));
    }

    /** 「全」路线相关的只读快照：三条帧伤 + 强制路线。 */
    private static RouteSnapshot routeSnapshot() {
        return new RouteSnapshot(HatSettings.routeDamage(HatType.BLACK), HatSettings.routeDamage(HatType.WHITE),
                HatSettings.routeDamage(HatType.RED), HatSettings.forcedRoute());
    }

    /** 「全」三条路线帧伤 + 强制路线的只读快照。 */
    public record RouteSnapshot(float black, float white, float red, String forcedRoute) {
    }

    private static List<SettingsSyncMessage.Snapshot> snapshot() {
        List<SettingsSyncMessage.Snapshot> list = new ArrayList<>();
        for (HatType type : HatType.values()) {
            HatSettings.Entry entry = HatSettings.get(type);
            list.add(new SettingsSyncMessage.Snapshot(type.ordinal(), entry.chargeTicks, entry.flashTicks,
                    entry.damagePerTick, entry.healthDamageRatio, entry.healthDamageFloor, entry.damageType));
        }
        return list;
    }

    // ------------------------------------------------------------------

    /**
     * 通知客户端：戴帽者 {@code wearer} 这一轮走的是哪条路线（{@code route}）。
     *
     * <p>只有「全」有意义 —— 客户端据此把帽子渲染成路线对应的颜色（黑/白/红）。
     * 三顶原色帽直接按自己的颜色渲染，这条包对它们只是同步一个「等于自身」的值。
     */
    public static void sendRoute(LivingEntity wearer, HatType hatType, HatType route) {
        if (hatType == null || route == null) {
            return;
        }
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> wearer),
                new RouteMessage(wearer.getId(), route.ordinal()));
    }

    /**
     * 只发给**某一个**玩家：戴帽者 {@code wearer} 这一轮走的是哪条路线。
     *
     * <p>给「这个玩家刚开始追踪那个戴帽者」用（{@code PlayerEvent.StartTracking}）：
     * 路线包平时只在**循环开始**时广播，玩家如果在循环中途才走进视野，就收不到那一刻的包，
     * 帽子的颜色会一直停在兜底的「全」三色贴图上 —— 这里补发一条，颜色立刻对上当前路线。
     */
    public static void sendRouteTo(ServerPlayer player, LivingEntity wearer, HatType route) {
        if (player == null || wearer == null || route == null) {
            return;
        }
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new RouteMessage(wearer.getId(), route.ordinal()));
    }

    /** 一个戴帽者这一轮走哪条路线（「全」用；颜色跟着变）。 */
    public record RouteMessage(int wearerId, int routeOrdinal) {

        public static void encode(RouteMessage msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.wearerId());
            buf.writeVarInt(msg.routeOrdinal());
        }

        public static RouteMessage decode(FriendlyByteBuf buf) {
            return new RouteMessage(buf.readVarInt(), buf.readVarInt());
        }

        public static void handle(RouteMessage msg, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context context = ctx.get();
            context.enqueueWork(() -> HatRouteState.setClient(
                    msg.wearerId(), msg.routeOrdinal()));
            context.setPacketHandled(true);
        }
    }

    /**
     * 一条光柱锁定信息：戴帽者、它锁定的目标 id 列表（每道光柱一个）、是否在照射。
     *
     * <p>红帽附魔「光柱」会同时打多道光柱，所以目标是一个数组而不是单个 id：
     * 客户端按顺序给每道光柱画一条，指向各自的敌人。没有目标时是空数组。
     */
    public record BeamTargetMessage(int wearerId, int[] targetIds, boolean beaming) {

        public static void encode(BeamTargetMessage msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.wearerId());
            buf.writeBoolean(msg.beaming());
            int[] ids = msg.targetIds();
            buf.writeVarInt(ids.length);
            for (int id : ids) {
                buf.writeVarInt(id);
            }
        }

        public static BeamTargetMessage decode(FriendlyByteBuf buf) {
            int wearerId = buf.readVarInt();
            boolean beaming = buf.readBoolean();
            int size = buf.readVarInt();
            int[] ids = new int[size];
            for (int i = 0; i < size; i++) {
                ids[i] = buf.readVarInt();
            }
            return new BeamTargetMessage(wearerId, ids, beaming);
        }

        public static void handle(BeamTargetMessage msg, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context context = ctx.get();
            context.enqueueWork(() -> com.hatmod.client.BeamTargets.set(
                    msg.wearerId(), msg.beaming(), msg.targetIds()));
            context.setPacketHandled(true);
        }
    }

    public record SettingsSyncMessage(List<Snapshot> snapshots, boolean ownBgmPriority,
                                      RouteSnapshot route, int feedback) {

        /** 一顶帽子的参数快照；ordinal 对应 {@link HatType} 的序号。 */
        public record Snapshot(int ordinal, int chargeTicks, int flashTicks, float damagePerTick,
                               float healthDamageRatio, float healthDamageFloor, String damageType) {
        }

        public static void encode(SettingsSyncMessage msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.feedback());
            buf.writeBoolean(msg.ownBgmPriority());
            buf.writeFloat(msg.route().black());
            buf.writeFloat(msg.route().white());
            buf.writeFloat(msg.route().red());
            buf.writeUtf(msg.route().forcedRoute());
            buf.writeVarInt(msg.snapshots().size());
            for (Snapshot snapshot : msg.snapshots()) {
                buf.writeVarInt(snapshot.ordinal());
                buf.writeVarInt(snapshot.chargeTicks());
                buf.writeVarInt(snapshot.flashTicks());
                buf.writeFloat(snapshot.damagePerTick());
                buf.writeFloat(snapshot.healthDamageRatio());
                buf.writeFloat(snapshot.healthDamageFloor());
                buf.writeUtf(snapshot.damageType());
            }
        }

        public static SettingsSyncMessage decode(FriendlyByteBuf buf) {
            int feedback = buf.readVarInt();
            boolean ownBgmPriority = buf.readBoolean();
            RouteSnapshot route = new RouteSnapshot(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readUtf());
            int size = buf.readVarInt();
            List<Snapshot> list = new ArrayList<>(size);
            HatType[] types = HatType.values();
            for (int i = 0; i < size; i++) {
                int ordinal = buf.readVarInt();
                list.add(new Snapshot(
                        ordinal >= 0 && ordinal < types.length ? ordinal : 0,
                        buf.readVarInt(), buf.readVarInt(), buf.readFloat(), buf.readFloat(),
                        buf.readFloat(), buf.readUtf()));
            }
            return new SettingsSyncMessage(list, ownBgmPriority, route, feedback);
        }

        public static void handle(SettingsSyncMessage msg, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context context = ctx.get();
            context.enqueueWork(() -> com.hatmod.client.ClientHatSettings.handleSync(msg));
            context.setPacketHandled(true);
        }
    }

    public record SettingsUpdateMessage(HatType type, int chargeTicks, int flashTicks,
                                        float damagePerTick, float healthDamageRatio,
                                        float healthDamageFloor, String damageType,
                                        boolean ownBgmPriority,
                                        float routeBlack, float routeWhite, float routeRed,
                                        String forcedRoute) {

        public static void encode(SettingsUpdateMessage msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.type().ordinal());
            buf.writeVarInt(msg.chargeTicks());
            buf.writeVarInt(msg.flashTicks());
            buf.writeFloat(msg.damagePerTick());
            buf.writeFloat(msg.healthDamageRatio());
            buf.writeFloat(msg.healthDamageFloor());
            buf.writeUtf(msg.damageType());
            buf.writeBoolean(msg.ownBgmPriority());
            buf.writeFloat(msg.routeBlack());
            buf.writeFloat(msg.routeWhite());
            buf.writeFloat(msg.routeRed());
            buf.writeUtf(msg.forcedRoute());
        }

        public static SettingsUpdateMessage decode(FriendlyByteBuf buf) {
            HatType[] types = HatType.values();
            int ordinal = buf.readVarInt();
            HatType type = types[ordinal >= 0 && ordinal < types.length ? ordinal : 0];
            return new SettingsUpdateMessage(type, buf.readVarInt(), buf.readVarInt(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readUtf(),
                    buf.readBoolean(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readUtf());
        }

        public static void handle(SettingsUpdateMessage msg, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context context = ctx.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player == null) {
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
                HatSettings.apply(msg.type(), msg.chargeTicks(), msg.flashTicks(),
                        msg.damagePerTick(), msg.healthDamageRatio(), msg.healthDamageFloor(), msg.damageType());
                // 全局开关跟着一起落盘：这样「保存」一个按钮就把界面上看到的都生效了
                HatSettings.setOwnBgmPriority(msg.ownBgmPriority());
                // 「全」的三条路线帧伤 + 强制路线，也在同一个「保存」里生效
                HatSettings.setRouteDamage(msg.routeBlack(), msg.routeWhite(), msg.routeRed());
                HatSettings.setForcedRoute(msg.forcedRoute());
                HatMod.LOGGER.info("[HatMod] {} 参数已更新：蓄力 {}t，照射 {}t，固定伤害 {}，附加 {}，下限 {}，类型 {}；"
                                + "自己的BGM优先 {}；路线帧伤 黑{} 白{} 红{}，强制路线 {}",
                        msg.type().id(), HatSettings.chargeTicks(msg.type()), HatSettings.flashTicks(msg.type()),
                        HatSettings.damagePerTick(msg.type()), HatSettings.healthDamageRatio(msg.type()),
                        HatSettings.healthDamageFloor(msg.type()), HatSettings.damageTypeId(msg.type()),
                        HatSettings.ownBgmPriority(), HatSettings.routeDamage(HatType.BLACK),
                        HatSettings.routeDamage(HatType.WHITE), HatSettings.routeDamage(HatType.RED),
                        HatSettings.forcedRoute());
                broadcastSettings(player.getServer());
                sendSettings(player, FEEDBACK_OK);
            });
            context.setPacketHandled(true);
        }
    }

    /**
     * 一条帽子 BGM 的开始 / 停止。
     *
     * <p>{@code ordinal} 是 {@link HatType} 的序号，客户端据此拿回自己的音效事件；
     * {@code playing=false} 表示这个戴帽者的 BGM 该停了。
     */
    public record MusicMessage(int wearerId, int ordinal, boolean playing) {

        public static void encode(MusicMessage msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.wearerId());
            buf.writeVarInt(msg.ordinal());
            buf.writeBoolean(msg.playing());
        }

        public static MusicMessage decode(FriendlyByteBuf buf) {
            return new MusicMessage(buf.readVarInt(), buf.readVarInt(), buf.readBoolean());
        }

        public static void handle(MusicMessage msg, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context context = ctx.get();
            context.enqueueWork(() -> com.hatmod.client.HatMusicPlayer.handle(
                    msg.wearerId(), msg.ordinal(), msg.playing()));
            context.setPacketHandled(true);
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
    public record ForesightMessage(int ticks) {

        public static void encode(ForesightMessage msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.ticks());
        }

        public static ForesightMessage decode(FriendlyByteBuf buf) {
            return new ForesightMessage(buf.readVarInt());
        }

        public static void handle(ForesightMessage msg, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context context = ctx.get();
            context.enqueueWork(() -> com.hatmod.client.ForesightFilter.start(msg.ticks()));
            context.setPacketHandled(true);
        }
    }

    /**
     * 某个玩家当前的「绿心」点数（1 颗心 = 2 点）。
     *
     * <p>绿心是本模组自己的抗伤池（不是原版「吸收」），所以客户端不可能自己算出来 ——
     * 服务端每变一次就推一次，客户端只存下来画血条。
     */
    public record GreenHeartsMessage(float amount) {

        public static void encode(GreenHeartsMessage msg, FriendlyByteBuf buf) {
            buf.writeFloat(msg.amount());
        }

        public static GreenHeartsMessage decode(FriendlyByteBuf buf) {
            return new GreenHeartsMessage(buf.readFloat());
        }

        public static void handle(GreenHeartsMessage msg, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context context = ctx.get();
            context.enqueueWork(() -> com.hatmod.client.GreenHeartRender.set(msg.amount()));
            context.setPacketHandled(true);
        }
    }
}
