package com.hatmod;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /hatmod} 命令：调参器的<b>兜底路径</b>。
 *
 * <p>界面走的是本模组自己的网络包；命令走原版
 * {@code ServerboundChatCommandPacket}，任何整合包里都不会被挡。
 * 两边改的是同一份 {@link HatSettings}、落盘到同一个 {@code config/hatmod.json}。
 * 所以哪怕自定义网络包在这个整合包里失灵，参数照样改得动、也验证得了。
 *
 * <pre>
 *   /hatmod                                       列出四顶帽子当前的参数（含「全」的路线）
 *   /hatmod tune &lt;hat&gt; &lt;蓄力&gt; &lt;照射&gt; &lt;固定伤害&gt; &lt;附加%&gt; &lt;下限%&gt; [伤害类型]
 *   /hatmod reset &lt;hat&gt;                           恢复出厂值
 *   /hatmod route [random|black|white|red]        查看 / 设置「全」的强制路线（不带参数=查看）
 *   /hatmod routedamage &lt;黑&gt; &lt;白&gt; &lt;红&gt;            设置「全」三条路线的帧伤
 *   /hatmod reload                                丢掉内存里那份，重新读 config/hatmod.json
 * </pre>
 *
 * <p>{@code <hat>} 写 {@code black} / {@code white} / {@code red} / {@code all} 或带 {@code _hat} 的完整名都行。
 * 权限要求与原版调试命令一致（权限等级 2）。
 */
@EventBusSubscriber(modid = HatMod.MOD_ID)
public final class HatCommand {

    private static final SuggestionProvider<CommandSourceStack> HAT_SUGGESTIONS = (context, builder) -> {
        builder.suggest("black");
        builder.suggest("white");
        builder.suggest("red");
        builder.suggest("all");
        return builder.buildFuture();
    };

    /** 路线建议：随机 + 三顶原色帽。 */
    private static final SuggestionProvider<CommandSourceStack> ROUTE_SUGGESTIONS = (context, builder) -> {
        builder.suggest(HatSettings.ROUTE_RANDOM);
        builder.suggest("black");
        builder.suggest("white");
        builder.suggest("red");
        return builder.buildFuture();
    };

    private HatCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("hatmod")
                .requires(source -> source.hasPermission(2))
                .executes(HatCommand::show)
                .then(Commands.literal("show").executes(HatCommand::show))
                .then(Commands.literal("reload").executes(HatCommand::reload))
                .then(Commands.literal("unfreeze")
                        .executes(context -> unfreeze(context, 32))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 128))
                                .executes(context -> unfreeze(context,
                                        IntegerArgumentType.getInteger(context, "radius")))))
                .then(Commands.literal("reset")
                        .then(Commands.argument("hat", StringArgumentType.word())
                                .suggests(HAT_SUGGESTIONS)
                                .executes(HatCommand::reset)))
                .then(Commands.literal("route")
                        .executes(HatCommand::showRoute)
                        .then(Commands.argument("route", StringArgumentType.word())
                                .suggests(ROUTE_SUGGESTIONS)
                                .executes(HatCommand::setRoute)))
                .then(Commands.literal("routedamage")
                        .executes(HatCommand::showRouteDamage)
                        .then(Commands.argument("black",
                                        FloatArgumentType.floatArg(HatSettings.MIN_DAMAGE, HatSettings.MAX_DAMAGE))
                                .then(Commands.argument("white",
                                                FloatArgumentType.floatArg(HatSettings.MIN_DAMAGE, HatSettings.MAX_DAMAGE))
                                        .then(Commands.argument("red",
                                                        FloatArgumentType.floatArg(HatSettings.MIN_DAMAGE, HatSettings.MAX_DAMAGE))
                                                .executes(HatCommand::setRouteDamage)))))
                .then(Commands.literal("summons").executes(HatCommand::showSummons))
                .then(Commands.literal("guard").executes(HatCommand::showGuard))
                .then(tuneBranch()));
    }

    /**
     * {@code tune <hat> <蓄力> <照射> <固定伤害> <附加%> <下限%> [伤害类型]}。
     *
     * <p>从里往外一层层套着建：这样每层只占一行，不会写成一长串嵌套的 {@code .then(...)}。
     */
    private static LiteralArgumentBuilder<CommandSourceStack> tuneBranch() {
        RequiredArgumentBuilder<CommandSourceStack, String> typeArg =
                Commands.argument("type", StringArgumentType.greedyString())
                        .executes(context -> tune(context, StringArgumentType.getString(context, "type")));
        RequiredArgumentBuilder<CommandSourceStack, Float> floorArg =
                Commands.argument("floor", FloatArgumentType.floatArg(0.0F, HatSettings.MAX_RATIO))
                        .then(typeArg)
                        .executes(context -> tune(context, null));
        RequiredArgumentBuilder<CommandSourceStack, Float> ratioArg =
                Commands.argument("ratio", FloatArgumentType.floatArg(0.0F, HatSettings.MAX_RATIO))
                        .then(floorArg);
        RequiredArgumentBuilder<CommandSourceStack, Float> damageArg =
                Commands.argument("damage", FloatArgumentType.floatArg(HatSettings.MIN_DAMAGE, HatSettings.MAX_DAMAGE))
                        .then(ratioArg);
        RequiredArgumentBuilder<CommandSourceStack, Integer> flashArg =
                Commands.argument("flashTicks", IntegerArgumentType.integer(HatSettings.MIN_TICKS, HatSettings.MAX_TICKS))
                        .then(damageArg);
        RequiredArgumentBuilder<CommandSourceStack, Integer> chargeArg =
                Commands.argument("chargeTicks", IntegerArgumentType.integer(HatSettings.MIN_TICKS, HatSettings.MAX_TICKS))
                        .then(flashArg);
        return Commands.literal("tune")
                .then(Commands.argument("hat", StringArgumentType.word())
                        .suggests(HAT_SUGGESTIONS)
                        .then(chargeArg));
    }

    /** {@code /hatmod summons}：列出当前世界里的 Teto / 虚影和它们的归属。 */
    private static int showSummons(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        int tetos = 0;
        int summoned = 0;
        int sitting = 0;
        int shades = 0;
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (ServerLevel level : source.getServer().getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof Teto teto) {
                    tetos++;
                    if (teto.isSummoned()) {
                        summoned++;
                    }
                    if (teto.isSitting()) {
                        sitting++;
                    }
                    if (lines.size() < 12) {
                        lines.add(String.format("  %s [%s] owner=%s %s%s",
                                teto.getName().getString(), level.dimension().location(),
                                ownerName(source.getServer(), teto.getOwnerUUID()),
                                teto.isSummoned() ? "summoned" : "free",
                                teto.isSitting() ? " sitting" : ""));
                    }
                } else if (entity instanceof HatSummon shade) {
                    shades++;
                    if (lines.size() < 12) {
                        lines.add(String.format("  %s [%s] owner=%s life=%d",
                                shade.getName().getString(), level.dimension().location(),
                                ownerName(source.getServer(), shade.getOwnerUUID()),
                                shade.lifeLeft()));
                    }
                }
            }
        }
        for (String line : lines) {
            String printed = line;
            source.sendSuccess(() -> Component.literal(printed), false);
        }
        int tetoTotal = tetos;
        int shadeTotal = shades;
        int summonedTotal = summoned;
        int sittingTotal = sitting;
        source.sendSuccess(() -> Component.literal(String.format(
                "[HatMod] Teto %d（召唤 %d / 留守 %d），虚影 %d，删不掉登记表 %d",
                tetoTotal, summonedTotal, sittingTotal, shadeTotal,
                HatAbilities.watchedCount())), true);
        return tetos + shades;
    }

    private static String ownerName(MinecraftServer server, java.util.UUID id) {
        if (id == null) {
            return "无";
        }
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        return player != null ? player.getName().getString() : id.toString();
    }

    /** {@code /hatmod guard}：破防相关的账本（清算名册 / 类型封禁 / 登记表）。 */
    private static int showGuard(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        for (String line : HatAbilities.guardLines(source.getServer())) {
            String printed = line;
            source.sendSuccess(() -> Component.literal("[HatMod] " + printed), false);
        }
        return 1;
    }

    private static int show(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        for (HatType type : HatType.values()) {
            HatSettings.Entry entry = HatSettings.get(type);
            source.sendSuccess(() -> Component.literal(String.format(
                    "[HatMod] %s：蓄力 %dt，照射 %dt，固定伤害 %s，附加 %s，下限 %s，类型 %s",
                    type.id(), entry.chargeTicks, entry.flashTicks,
                    trim(entry.damagePerTick), trim(entry.healthDamageRatio),
                    trim(entry.healthDamageFloor), entry.damageType)), false);
        }
        source.sendSuccess(() -> Component.literal(String.format(
                "[HatMod] 「全」路线帧伤：黑 %s / 白 %s / 红 %s；强制路线 %s",
                trim(HatSettings.routeDamage(HatType.BLACK)), trim(HatSettings.routeDamage(HatType.WHITE)),
                trim(HatSettings.routeDamage(HatType.RED)), HatSettings.forcedRoute())), false);
        return HatType.values().length;
    }

    /** {@code /hatmod route}：只看当前强制路线。 */
    private static int showRoute(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal(
                "[HatMod] 「全」当前强制路线：" + HatSettings.forcedRoute()
                        + "（random = 正常随机；black / white / red = 每轮都走那一条）"), false);
        return 1;
    }

    /** {@code /hatmod route <random|black|white|red>}：设强制路线。 */
    private static int setRoute(CommandContext<CommandSourceStack> context) {
        String raw = StringArgumentType.getString(context, "route");
        HatSettings.setForcedRoute(raw);
        HatNetwork.broadcastSettings();
        context.getSource().sendSuccess(() -> Component.literal(
                "[HatMod] 「全」强制路线已设为：" + HatSettings.forcedRoute()), true);
        return 1;
    }

    /** {@code /hatmod routedamage}：只看当前三条路线帧伤。 */
    private static int showRouteDamage(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal(String.format(
                "[HatMod] 「全」路线帧伤：黑 %s / 白 %s / 红 %s",
                trim(HatSettings.routeDamage(HatType.BLACK)), trim(HatSettings.routeDamage(HatType.WHITE)),
                trim(HatSettings.routeDamage(HatType.RED)))), false);
        return 1;
    }

    /** {@code /hatmod routedamage <黑> <白> <红>}：设三条路线帧伤。 */
    private static int setRouteDamage(CommandContext<CommandSourceStack> context) {
        HatSettings.setRouteDamage(
                FloatArgumentType.getFloat(context, "black"),
                FloatArgumentType.getFloat(context, "white"),
                FloatArgumentType.getFloat(context, "red"));
        HatNetwork.broadcastSettings();
        context.getSource().sendSuccess(() -> Component.literal(String.format(
                "[HatMod] 「全」路线帧伤已更新：黑 %s / 白 %s / 红 %s",
                trim(HatSettings.routeDamage(HatType.BLACK)), trim(HatSettings.routeDamage(HatType.WHITE)),
                trim(HatSettings.routeDamage(HatType.RED)))), true);
        return 1;
    }

    private static int reset(CommandContext<CommandSourceStack> context) {
        HatType type = hat(context);
        if (type == null) {
            return unknownHat(context);
        }
        HatSettings.resetToDefaults(type);
        HatNetwork.broadcastSettings();
        context.getSource().sendSuccess(
                () -> Component.literal("[HatMod] " + type.id() + " 已恢复出厂值"), true);
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> context) {
        HatSettings.reload();
        HatNetwork.broadcastSettings();
        context.getSource().sendSuccess(
                () -> Component.literal("[HatMod] 已从 config/hatmod.json 重新读取"), true);
        return 1;
    }

    /**
     * {@code /hatmod unfreeze [半径]}：把周围「被这个模组关掉 AI、却没还回去」的生物救回来。
     *
     * <p>1.5.92 修掉了「定身解除时没人打开 {@code NoAI}」—— 但在修好之前被钉过的怪，
     * 已经带着 {@code NoAI:1} 写进存档了，光靠改代码救不回来（登记表只在内存里）。
     * 这条命令就是留给那些老存档的：只动现在既没被光柱按着、也没被钉着的普通生物，
     * 铁托 / 虚影这类本来就该没 AI 的单位一律不碰。
     */
    private static int unfreeze(CommandContext<CommandSourceStack> context, int radius) {
        CommandSourceStack source = context.getSource();
        Vec3 center = source.getPosition();
        int cleared = 0;
        for (Mob mob : source.getLevel().getEntitiesOfClass(Mob.class, new AABB(center, center).inflate(radius))) {
            if (mob instanceof Teto || mob instanceof HatSummon || mob instanceof TangJiaqi || mob instanceof TangJiaqiWhite) {
                continue;
            }
            if (!mob.isNoAi() || HatAbilities.isUnderControl(mob) || BrainrotCycle.isPinned(mob)) {
                continue;
            }
            mob.setNoAi(false);
            cleared++;
        }
        int total = cleared;
        source.sendSuccess(() -> Component.literal(
                String.format("[HatMod] %d 格内解冻了 %d 只生物", radius, total)), true);
        return cleared;
    }

    private static int tune(CommandContext<CommandSourceStack> context, String damageTypeOverride) {
        HatType type = hat(context);
        if (type == null) {
            return unknownHat(context);
        }
        String damageType = damageTypeOverride != null
                ? damageTypeOverride.trim()
                : HatSettings.damageTypeId(type);
        HatSettings.apply(type,
                IntegerArgumentType.getInteger(context, "chargeTicks"),
                IntegerArgumentType.getInteger(context, "flashTicks"),
                FloatArgumentType.getFloat(context, "damage"),
                FloatArgumentType.getFloat(context, "ratio"),
                FloatArgumentType.getFloat(context, "floor"),
                damageType);
        HatNetwork.broadcastSettings();
        context.getSource().sendSuccess(() -> Component.literal(String.format(
                "[HatMod] %s 已更新：蓄力 %dt，照射 %dt，固定伤害 %s，附加 %s，下限 %s，类型 %s",
                type.id(), HatSettings.chargeTicks(type), HatSettings.flashTicks(type),
                trim(HatSettings.damagePerTick(type)), trim(HatSettings.healthDamageRatio(type)),
                trim(HatSettings.healthDamageFloor(type)), HatSettings.damageTypeId(type))), true);
        return 1;
    }

    /** black / white / red / all，或 black_hat / white_hat / red_hat / all_hat；其它返回 null。 */
    private static HatType hat(CommandContext<CommandSourceStack> context) {
        String raw = StringArgumentType.getString(context, "hat").toLowerCase(java.util.Locale.ROOT);
        for (HatType type : HatType.values()) {
            if (type.id().equals(raw) || type.id().equals(raw + "_hat")) {
                return type;
            }
        }
        return null;
    }

    private static int unknownHat(CommandContext<CommandSourceStack> context) {
        context.getSource().sendFailure(Component.literal("[HatMod] 没有这顶帽子："
                + StringArgumentType.getString(context, "hat") + "（black / white / red / all）"));
        return 0;
    }

    /** 20.0 显示成 20、0.05 保持 0.05，省得满屏没用的尾数。 */
    private static String trim(float value) {
        if (value == Math.round(value)) {
            return Integer.toString(Math.round(value));
        }
        return Float.toString(value);
    }
}
