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
 *   /hatmod                                       列出三顶帽子当前的参数
 *   /hatmod tune &lt;hat&gt; &lt;蓄力&gt; &lt;照射&gt; &lt;固定伤害&gt; &lt;附加%&gt; &lt;下限%&gt; [伤害类型]
 *   /hatmod reset &lt;hat&gt;                           恢复出厂值
 *   /hatmod reload                                丢掉内存里那份，重新读 config/hatmod.json
 * </pre>
 *
 * <p>{@code <hat>} 写 {@code black} / {@code white} / {@code red} 或带 {@code _hat} 的完整名都行。
 * 权限要求与原版调试命令一致（权限等级 2）。
 */
@EventBusSubscriber(modid = HatMod.MOD_ID)
public final class HatCommand {

    private static final SuggestionProvider<CommandSourceStack> HAT_SUGGESTIONS = (context, builder) -> {
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
                .then(Commands.literal("reset")
                        .then(Commands.argument("hat", StringArgumentType.word())
                                .suggests(HAT_SUGGESTIONS)
                                .executes(HatCommand::reset)))
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
        return HatType.values().length;
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

    /** black / white / red，或 black_hat / white_hat / red_hat；其它返回 null。 */
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
                + StringArgumentType.getString(context, "hat") + "（black / white / red）"));
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
