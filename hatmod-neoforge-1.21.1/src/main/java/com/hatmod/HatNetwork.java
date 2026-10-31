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
        // 版本 "5"：Settings 的同步/更新包都加了「脑蚀每帧伤害倍率」字段。
        // 版本 "6"：加了「打拍」的两个包（QteTapPayload / QteStatePayload）。
        // 版本 "8"：加了闪避通知包（QteDodgePayload）。
        // 版本 "10"：加了「自动打拍」调试开关（AutoTapPayload）。
        // 版本 "11"：同一个包加了反向（S2C），进服时把开关状态同步回客户端。
        // 版本不同时 NeoForge 会拒绝握手，而不是拿错值跑。
        // 以后只要改动任何包的字段布局，都要把这个版本号加一。
        var registrar = event.registrar("11");
        registrar.playToClient(
                BeamTargetPayload.TYPE,
                BeamTargetPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.hatmod.client.BeamTargets.set(
                                payload.wearerId(), payload.beaming(), payload.targetIds(),
                                payload.beamCount())));
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
        registrar.playToClient(
                RoutePayload.TYPE,
                RoutePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> HatRouteState.setClient(payload.wearerId(), payload.routeOrdinal())));
        registrar.playToClient(
                BrainrotSpinPayload.TYPE,
                BrainrotSpinPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.hatmod.client.BrainrotSpin.set(payload.wearerId(), payload.spin())));
        registrar.playToServer(
                SettingsUpdatePayload.TYPE,
                SettingsUpdatePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> handleUpdate(payload, context)));
        // 「打拍」：客户端报「我按了一下」（带 HUD 钟走到几刻），服务端判 PERFECT/GOOD/白按
        registrar.playToServer(
                QteTapPayload.TYPE,
                QteTapPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        BrainrotQte.tap(player, payload.clientAge());
                    }
                }));
        // 「强制开始链路」：空包，服务端自己看玩家戴的是哪顶帽子
        registrar.playToServer(
                ForceStartPayload.TYPE,
                ForceStartPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        handleForceStart(player);
                    }
                }));
        // 「自动打拍」调试开关（调参器里点）：C2S，一个布尔
        registrar.playToServer(
                AutoTapPayload.TYPE,
                AutoTapPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        BrainrotQte.setAutoTap(player, payload.on());
                    }
                }));
        // 进服时服务端告诉客户端「你这个（落盘的）自动打拍开关还开着」。
        // 必须是**另一个** payload：NeoForge 不允许同一个 type 在两个方向各注册一次。
        registrar.playToClient(
                AutoTapStatePayload.TYPE,
                AutoTapStatePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.hatmod.client.BrainrotQteClient.setAutoTap(payload.on())));
        // 「闪避落空」：只发给被闪掉这一击的玩家，HUD 画紫边 + 大字
        registrar.playToClient(
                QteDodgePayload.TYPE,
                QteDodgePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.hatmod.client.BrainrotQteClient.onDodge()));
        // 「打拍」的状态回包：连击数 + 最近一次判定，HUD 拿它画判定条
        registrar.playToClient(
                QteStatePayload.TYPE,
                QteStatePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.hatmod.client.BrainrotQteClient.onState(
                                payload.combo(), payload.rating(), payload.dodges())));
    }

    /** 通知客户端某个戴帽者「开始/停止照射」，以及它锁定了谁（每道光柱一个 id）。 */
    public static void sendBeamTarget(LivingEntity wearer, boolean beaming, int[] targetIds) {
        sendBeamTarget(wearer, beaming, targetIds, 0);
    }

    /**
     * 同上，但显式指定「画几道光柱」。{@code beamCount <= 0} 表示让客户端按帽子自己算。
     */
    public static void sendBeamTarget(LivingEntity wearer, boolean beaming, int[] targetIds, int beamCount) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                wearer, new BeamTargetPayload(wearer.getId(), targetIds, beaming, beamCount));
    }

    /** 「脑蚀」的旋转状态（只影响渲染，纯视觉）。 */
    public static void sendBrainrotSpin(LivingEntity wearer, boolean spin) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                wearer, new BrainrotSpinPayload(wearer.getId(), spin));
    }

    /** 「打拍」的最新判定（连击 / PERFECT-GOOD-MISS）：只发给戴帽者自己，HUD 用它画判定字。 */
    public static void sendQteState(ServerPlayer player, int combo, int rating, int dodges) {
        PacketDistributor.sendToPlayer(player, new QteStatePayload(combo, rating, dodges));
    }

    /** 客户端：按了一下打拍（带 HUD 的钟走到几刻）。 */
    /** 「闪避落空」：只告诉挨打的那一个玩家，让他的屏幕闪一下紫边。 */
    public static void sendQteDodge(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new QteDodgePayload());
    }

    /** 客户端：按 P 请求强制开一轮（不看附近有没有敌人）。 */
    public static void sendForceStart() {
        PacketDistributor.sendToServer(new ForceStartPayload());
    }

    /**
     * 「强制开始链路」：戴着脑蚀帽就开脑蚀那一轮（音乐 + Teto + 时间线），
     * 否则按牛仔帽直接进蓄力。两样都没戴就什么也不发生。
     */
    private static void handleForceStart(ServerPlayer player) {
        if (player == null) {
            return;
        }
        if (!BrainrotCycle.forceStart(player)) {
            HatAbilities.forceStart(player);
        }
    }

    public static void sendQteTap(int clientAge) {
        PacketDistributor.sendToServer(new QteTapPayload(clientAge));
    }

    /** 客户端：调参器里切换「自动打拍」。 */
    public static void sendAutoTap(boolean on) {
        PacketDistributor.sendToServer(new AutoTapPayload(on));
    }

    /** 服务端：把「自动打拍」的当前状态告诉某个玩家（进服时同步，界面显示才对）。 */
    public static void sendAutoTapTo(ServerPlayer player, boolean on) {
        PacketDistributor.sendToPlayer(player, new AutoTapStatePayload(on));
    }

    /** 只发给某一个玩家：某个实体此刻的自转状态（玩家刚进入视野时补发）。 */
    public static void sendBrainrotSpinTo(ServerPlayer player, int wearerId, boolean spin) {
        PacketDistributor.sendToPlayer(player, new BrainrotSpinPayload(wearerId, spin));
    }

    /** 只发给某一个玩家：某戴帽者当前的光柱状态（玩家刚进入视野时补发）。 */
    public static void sendBeamTargetTo(ServerPlayer player, LivingEntity wearer, int[] targetIds, int beamCount) {
        PacketDistributor.sendToPlayer(player, new BeamTargetPayload(wearer.getId(), targetIds, true, beamCount));
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
                routeSnapshot(), HatSettings.brainrotDamageScale(), feedback));
    }

    /** 广播给所有人（feedback = 无提示）。 */
    public static void broadcastSettings() {
        PacketDistributor.sendToAllPlayers(new SettingsSyncPayload(snapshot(), HatSettings.ownBgmPriority(),
                routeSnapshot(), HatSettings.brainrotDamageScale(), FEEDBACK_NONE));
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
                                          String forcedRoute, float brainrotDamageScale) {
        // 客户端也留一行日志：排查「点了保存没生效」时，先看日志里有没有这一行，
        // 就能分清到底是「按钮没点到」还是「包没到服务端」。
        HatMod.LOGGER.info("[HatMod] 发出调参请求：{} 蓄力 {}t，照射 {}t，固定伤害 {}，附加 {}，下限 {}，类型 {}，"
                        + "自己的BGM优先 {}；路线帧伤 黑{} 白{} 红{}，强制路线 {}；脑蚀倍率 {}",
                type.id(), chargeTicks, flashTicks, damagePerTick, healthDamageRatio, healthDamageFloor, damageType,
                ownBgmPriority, routeBlack, routeWhite, routeRed, forcedRoute, brainrotDamageScale);
        PacketDistributor.sendToServer(new SettingsUpdatePayload(type.ordinal(), chargeTicks, flashTicks,
                damagePerTick, healthDamageRatio, healthDamageFloor, damageType,
                ownBgmPriority, routeBlack, routeWhite, routeRed, forcedRoute, brainrotDamageScale));
    }

    /** 「全」路线相关的只读快照：三条帧伤 + 强制路线。 */
    private static RouteSnapshot routeSnapshot() {
        return new RouteSnapshot(HatSettings.routeDamage(HatType.BLACK), HatSettings.routeDamage(HatType.WHITE),
                HatSettings.routeDamage(HatType.RED), HatSettings.forcedRoute());
    }

    /** 「全」三条路线帧伤 + 强制路线的只读快照。 */
    public record RouteSnapshot(float black, float white, float red, String forcedRoute) {
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
        // 「全」的三条路线帧伤 + 强制路线，也在同一个「保存」里生效
        HatSettings.setRouteDamage(payload.routeBlack(), payload.routeWhite(), payload.routeRed());
        HatSettings.setForcedRoute(payload.forcedRoute());
        HatSettings.setBrainrotDamageScale(payload.brainrotDamageScale());
        HatMod.LOGGER.info("[HatMod] {} 参数已更新：蓄力 {}t，照射 {}t，固定伤害 {}，附加 {}，下限 {}，类型 {}；"
                        + "自己的BGM优先 {}；路线帧伤 黑{} 白{} 红{}，强制路线 {}；脑蚀倍率 {}",
                type.id(), HatSettings.chargeTicks(type), HatSettings.flashTicks(type),
                HatSettings.damagePerTick(type), HatSettings.healthDamageRatio(type),
                HatSettings.healthDamageFloor(type), HatSettings.damageTypeId(type),
                HatSettings.ownBgmPriority(), HatSettings.routeDamage(HatType.BLACK),
                HatSettings.routeDamage(HatType.WHITE), HatSettings.routeDamage(HatType.RED),
                HatSettings.forcedRoute(), HatSettings.brainrotDamageScale());
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
     * 通知客户端：戴帽者 {@code wearer} 这一轮走的是哪条路线（{@code route}）。
     *
     * <p>只有「全」有意义 —— 客户端据此把帽子渲染成路线对应的颜色（黑/白/红）。
     */
    public static void sendRoute(LivingEntity wearer, HatType hatType, HatType route) {
        if (hatType == null || route == null) {
            return;
        }
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                wearer, new RoutePayload(wearer.getId(), route.ordinal()));
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
        PacketDistributor.sendToPlayer(player, new RoutePayload(wearer.getId(), route.ordinal()));
    }

    /**
     * 一个戴帽者这一轮走哪条路线（「全」用；帽子颜色、光柱淡色都跟着它）。
     */
    public record RoutePayload(int wearerId, int routeOrdinal) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<RoutePayload> TYPE =
                new CustomPacketPayload.Type<>(HatMod.id("route"));

        public static final StreamCodec<RegistryFriendlyByteBuf, RoutePayload> STREAM_CODEC =
                StreamCodec.of(RoutePayload::write, RoutePayload::read);

        private static void write(RegistryFriendlyByteBuf buf, RoutePayload payload) {
            buf.writeVarInt(payload.wearerId());
            buf.writeVarInt(payload.routeOrdinal());
        }

        private static RoutePayload read(RegistryFriendlyByteBuf buf) {
            return new RoutePayload(buf.readVarInt(), buf.readVarInt());
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * 一条光柱锁定信息：戴帽者、它锁定的目标 id 列表（每道光柱一个）、是否在照射，
     * 以及**当前应该画几道**。
     *
     * <p>红帽附魔「光柱」会同时打多道光柱，所以目标是一个数组而不是单个 id：
     * 客户端按顺序给每道光柱画一条，指向各自的敌人。没有目标时是空数组。
     *
     * <p>{@code beamCount <= 0} 表示「按帽子算」（基础 2 道 + 附魔加成），这是老行为；
     * 「脑蚀」的光柱道数会随时间变化（2 → 3 → 4），由服务端直接给数字。
     */
    public record BeamTargetPayload(int wearerId, int[] targetIds, boolean beaming,
                                    int beamCount) implements CustomPacketPayload {

        // 注意：createType(String) 只接收 path，会自动补上 minecraft: 命名空间，
        // 传 "hatmod:beam_target" 会变成非法的 minecraft:hatmod:beam_target。这里直接用完整 id。
        public static final CustomPacketPayload.Type<BeamTargetPayload> TYPE =
                new CustomPacketPayload.Type<>(HatMod.id("beam_target"));

        public static final StreamCodec<RegistryFriendlyByteBuf, BeamTargetPayload> STREAM_CODEC =
                StreamCodec.of(BeamTargetPayload::write, BeamTargetPayload::read);

        private static void write(RegistryFriendlyByteBuf buf, BeamTargetPayload payload) {
            buf.writeVarInt(payload.wearerId());
            buf.writeBoolean(payload.beaming());
            buf.writeVarInt(payload.beamCount());
            int[] ids = payload.targetIds();
            buf.writeVarInt(ids.length);
            for (int id : ids) {
                buf.writeVarInt(id);
            }
        }

        private static BeamTargetPayload read(RegistryFriendlyByteBuf buf) {
            int wearerId = buf.readVarInt();
            boolean beaming = buf.readBoolean();
            int beamCount = buf.readVarInt();
            int size = buf.readVarInt();
            int[] ids = new int[size];
            for (int i = 0; i < size; i++) {
                ids[i] = buf.readVarInt();
            }
            return new BeamTargetPayload(wearerId, ids, beaming, beamCount);
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

    public record SettingsSyncPayload(List<Snapshot> snapshots, boolean ownBgmPriority,
                                      RouteSnapshot route, float brainrotDamageScale, int feedback)
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
            buf.writeFloat(payload.route().black());
            buf.writeFloat(payload.route().white());
            buf.writeFloat(payload.route().red());
            buf.writeUtf(payload.route().forcedRoute());
            buf.writeFloat(payload.brainrotDamageScale());
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
            RouteSnapshot route = new RouteSnapshot(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readUtf());
            float brainrotDamageScale = buf.readFloat();
            int size = buf.readVarInt();
            List<Snapshot> list = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                list.add(new Snapshot(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                        buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readUtf()));
            }
            return new SettingsSyncPayload(list, ownBgmPriority, route, brainrotDamageScale, feedback);
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
                                        boolean ownBgmPriority,
                                        float routeBlack, float routeWhite, float routeRed,
                                        String forcedRoute, float brainrotDamageScale)
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
            buf.writeFloat(payload.routeBlack());
            buf.writeFloat(payload.routeWhite());
            buf.writeFloat(payload.routeRed());
            buf.writeUtf(payload.forcedRoute());
            buf.writeFloat(payload.brainrotDamageScale());
        }

        private static SettingsUpdatePayload read(RegistryFriendlyByteBuf buf) {
            return new SettingsUpdatePayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readUtf(),
                    buf.readBoolean(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readUtf(),
                    buf.readFloat());
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

    /**
     * 「脑蚀」的旋转状态：某个戴帽者此刻该不该原地打转（纯渲染，不影响任何数值）。
     */
    public record BrainrotSpinPayload(int wearerId, boolean spin) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<BrainrotSpinPayload> TYPE =
                new CustomPacketPayload.Type<>(HatMod.id("brainrot_spin"));

        public static final StreamCodec<RegistryFriendlyByteBuf, BrainrotSpinPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, BrainrotSpinPayload::wearerId,
                        ByteBufCodecs.BOOL, BrainrotSpinPayload::spin,
                        BrainrotSpinPayload::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 「自动打拍」（调参器里的调试开关）：纯调试，用过的一轮不发成就。 */
    public record AutoTapPayload(boolean on) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<AutoTapPayload> TYPE =
                new CustomPacketPayload.Type<>(HatMod.id("auto_tap"));

        public static final StreamCodec<RegistryFriendlyByteBuf, AutoTapPayload> STREAM_CODEC =
                StreamCodec.composite(ByteBufCodecs.BOOL, AutoTapPayload::on, AutoTapPayload::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * 同一个开关的 <b>服务端 → 客户端</b> 方向：进服时把落盘的状态同步过来。
     *
     * <p>为什么不能复用 {@link AutoTapPayload}：NeoForge 的 payload 注册表是
     * 「一个 type 只能注册一次」，C2S 注册过了再注册 S2C 会直接
     * {@code Cannot register payload ... as it is already registered}（启动即崩）。
     */
    public record AutoTapStatePayload(boolean on) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<AutoTapStatePayload> TYPE =
                new CustomPacketPayload.Type<>(HatMod.id("auto_tap_state"));

        public static final StreamCodec<RegistryFriendlyByteBuf, AutoTapStatePayload> STREAM_CODEC =
                StreamCodec.composite(ByteBufCodecs.BOOL, AutoTapStatePayload::on, AutoTapStatePayload::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * 「打拍」按下的一下（C2S）：{@code clientAge} 是客户端 HUD 的钟走到几刻 ——
     * 玩家照着 HUD 按的，就按 HUD 的钟判，服务端只做漂移校验（差太多就用自己的钟）。
     */
    public record QteTapPayload(int clientAge) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<QteTapPayload> TYPE =
                new CustomPacketPayload.Type<>(HatMod.id("qte_tap"));

        public static final StreamCodec<RegistryFriendlyByteBuf, QteTapPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, QteTapPayload::clientAge,
                        QteTapPayload::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * 「打拍」的最新状态（S2C）：连击数 + 最近一次判定（PERFECT / GOOD / MISS）。
     * 只在判定发生（或 MISS）时发一次，HUD 拿它画判定字。
     */
    public record QteStatePayload(int combo, int rating, int dodges) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<QteStatePayload> TYPE =
                new CustomPacketPayload.Type<>(HatMod.id("qte_state"));

        public static final StreamCodec<RegistryFriendlyByteBuf, QteStatePayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, QteStatePayload::combo,
                        ByteBufCodecs.VAR_INT, QteStatePayload::rating,
                        ByteBufCodecs.VAR_INT, QteStatePayload::dodges,
                        QteStatePayload::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * 「闪避落空」的通知（S2C，空包）：只发给被闪掉这一击的玩家，
     * HUD 收到就画一整屏紫边 + 大字「闪避」。
     */
    public record QteDodgePayload() implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<QteDodgePayload> TYPE =
                new CustomPacketPayload.Type<>(HatMod.id("qte_dodge"));

        public static final StreamCodec<RegistryFriendlyByteBuf, QteDodgePayload> STREAM_CODEC =
                StreamCodec.unit(new QteDodgePayload());

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 「强制开始链路」请求（C2S，空包）：服务端按玩家戴的帽子决定开哪一轮。 */
    public record ForceStartPayload() implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<ForceStartPayload> TYPE =
                new CustomPacketPayload.Type<>(HatMod.id("force_start"));

        public static final StreamCodec<RegistryFriendlyByteBuf, ForceStartPayload> STREAM_CODEC =
                StreamCodec.unit(new ForceStartPayload());

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
