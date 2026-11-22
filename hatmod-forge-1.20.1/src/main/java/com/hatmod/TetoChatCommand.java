package com.hatmod;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Locale;
import java.util.Set;

/**
 * 在聊天框里直接跟她说话 —— **不需要戴帽子**，也不用指令。
 *
 * <p>认这几句（空格、大小写、结尾标点都不敏感）：
 * <ul>
 *   <li><b>驱散</b>：{@code teto走开 / teto离开 / teto回去 / 驱散teto / teto bye / teto go away …}</li>
 *   <li><b>定位</b>：{@code teto在哪 / teto位置 / teto坐标 / teto where …}</li>
 *   <li><b>传送</b>：{@code teto来 / teto过来 / teto come / teto tp …}</li>
 * </ul>
 *
 * <p>命中之后这句话**不会进公屏**（等于低声跟她说），只回你一条消息。
 * 驱散会顺手给她一个「别马上回来」的冷却（见 {@link TetoVisit#noteDismissed}）。
 */
@Mod.EventBusSubscriber(modid = HatMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TetoChatCommand {

    private static final Set<String> DISMISS = Set.of(
            "teto走开", "teto离开", "teto回去", "teto散", "teto消失", "teto滚", "teto再见",
            "teto拜拜", "teto走吧", "走开teto", "驱散teto", "让teto走", "teto别跟着我",
            "tetobye", "tetogoaway", "tetodismiss", "tetoleave", "tetoscram", "tetogohome");

    private static final Set<String> WHERE = Set.of(
            "teto在哪", "teto在哪儿", "teto在哪呢", "teto位置", "teto坐标", "teto在什么地方",
            "tetowhere", "tetowhereareyou", "tetolocate", "tetopos", "tetoposition");

    private static final Set<String> COME = Set.of(
            "teto来", "teto过来", "teto回来", "teto到这来", "teto到这", "teto传送",
            "tetocome", "tetocomehere", "tetoteleport", "tetotp");

    /** 落到玩家身边多远（格）。 */
    private static final double ARRIVE_RADIUS = 2.2D;

    private TetoChatCommand() {
    }

    @SubscribeEvent
    public static void onChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        if (player == null) {
            return;
        }
        String key = normalize(event.getMessage().getString());
        boolean dismiss = DISMISS.contains(key);
        boolean where = WHERE.contains(key);
        boolean come = COME.contains(key);
        if (!dismiss && !where && !come) {
            return;
        }
        // 这一句不进公屏：是跟她说的私话
        event.setCanceled(true);
        if (dismiss) {
            dismiss(player);
        } else if (where) {
            locate(player);
        } else {
            come(player);
        }
    }

    /** 去掉空白、转小写、去掉结尾标点，让她认得出这些说法。 */
    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.toLowerCase(Locale.ROOT).replaceAll("[\\s\\u3000]+", "");
        return text.replaceAll("[。，！？!?,.;~～…]+$", "");
    }

    // ------------------------------------------------------------------
    // 驱散
    // ------------------------------------------------------------------

    private static void dismiss(ServerPlayer player) {
        // 链路正跑着的时候不收：那一轮已经排好 4 只，抽掉一只只会让 checkTetos 补一只回来
        if (BrainrotCycle.isRunning(player)) {
            tell(player, Component.translatable("hatmod.teto.chat.dismiss.busy"));
            return;
        }
        ServerLevel level = player.serverLevel();
        boolean any = false;
        for (Teto teto : Teto.ownedBy(player)) {
            if (teto.isRemoved()) {
                continue;
            }
            teto.disappear(level, "主人在聊天框里让她走");
            any = true;
        }
        if (any) {
            TetoVisit.noteDismissed(level, player);
            tell(player, Component.translatable("hatmod.teto.chat.dismiss.done"));
        } else {
            tell(player, Component.translatable("hatmod.teto.chat.where.none"));
        }
    }

    // ------------------------------------------------------------------
    // 定位
    // ------------------------------------------------------------------

    private static void locate(ServerPlayer player) {
        Teto found = first(player);
        if (found == null) {
            tell(player, Component.translatable("hatmod.teto.chat.where.none"));
            return;
        }
        int x = (int) Math.floor(found.getX());
        int y = (int) Math.floor(found.getY());
        int z = (int) Math.floor(found.getZ());
        if (found.level() == player.level()) {
            int distance = (int) Math.round(Math.sqrt(found.distanceToSqr(player)));
            tell(player, Component.translatable("hatmod.teto.chat.where.here",
                    distance, x, y, z));
        } else {
            tell(player, Component.translatable("hatmod.teto.chat.where.other",
                    dimensionLabel(found.level()), x, y, z));
        }
    }

    // ------------------------------------------------------------------
    // 传送
    // ------------------------------------------------------------------

    private static void come(ServerPlayer player) {
        if (BrainrotCycle.isRunning(player)) {
            tell(player, Component.translatable("hatmod.teto.chat.come.busy"));
            return;
        }
        ServerLevel level = player.serverLevel();
        boolean moved = false;
        for (Teto teto : Teto.ownedBy(player)) {
            if (teto.isRemoved()) {
                continue;
            }
            if (teto.level() == level) {
                double angle = player.getRandom().nextDouble() * Math.PI * 2.0D;
                teto.moveTo(player.getX() + Math.cos(angle) * ARRIVE_RADIUS, player.getY(),
                        player.getZ() + Math.sin(angle) * ARRIVE_RADIUS,
                        player.getYRot() + 180.0F, 0.0F);
                teto.setDeltaMovement(0.0D, 0.0D, 0.0D);
                teto.fallDistance = 0.0F;
                level.sendParticles(net.minecraft.core.particles.ParticleTypes.SCULK_SOUL,
                        teto.getX(), teto.getY() + teto.getBbHeight() * 0.5D, teto.getZ(),
                        10, 0.3D, 0.6D, 0.3D, 0.01D);
            } else {
                // 跨维度：旧身体收掉、在主人身边放一只新的（和复活时同一套做法）
                teto.discard();
                spawnFresh(player, level);
            }
            moved = true;
        }
        if (moved) {
            tell(player, Component.translatable("hatmod.teto.chat.come.done"));
        } else {
            tell(player, Component.translatable("hatmod.teto.chat.where.none"));
        }
    }

    private static void spawnFresh(ServerPlayer player, ServerLevel level) {
        Teto teto = HatSummons.TETO.get().create(level);
        if (teto == null) {
            return;
        }
        double angle = player.getRandom().nextDouble() * Math.PI * 2.0D;
        teto.moveTo(player.getX() + Math.cos(angle) * ARRIVE_RADIUS, player.getY(),
                player.getZ() + Math.sin(angle) * ARRIVE_RADIUS, player.getYRot() + 180.0F, 0.0F);
        teto.hatmodVisitArrival(player);
        if (level.addFreshEntity(teto)) {
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.SCULK_SOUL,
                    teto.getX(), teto.getY() + teto.getBbHeight() * 0.5D, teto.getZ(),
                    12, 0.3D, 0.7D, 0.3D, 0.01D);
        }
    }

    // ------------------------------------------------------------------

    private static Teto first(ServerPlayer player) {
        for (Teto teto : Teto.ownedBy(player)) {
            if (!teto.isRemoved()) {
                return teto;
            }
        }
        return null;
    }

    /** 维度名：原版三层用语言文件，模组维度直接显示 id。 */
    private static Component dimensionLabel(Level level) {
        String id = level.dimension().location().toString();
        if (id.equals("minecraft:overworld")) {
            return Component.translatable("hatmod.teto.chat.dim.overworld");
        }
        if (id.equals("minecraft:the_nether")) {
            return Component.translatable("hatmod.teto.chat.dim.nether");
        }
        if (id.equals("minecraft:the_end")) {
            return Component.translatable("hatmod.teto.chat.dim.end");
        }
        return Component.literal(id);
    }

    /** 回一条带 [Teto] 前缀的消息（和日常台词同一个配色）。 */
    private static void tell(ServerPlayer player, Component body) {
        MutableComponent line = Component.literal("[Teto] ").withStyle(ChatFormatting.LIGHT_PURPLE);
        player.sendSystemMessage(line.append(body.copy().withStyle(ChatFormatting.WHITE)));
    }
}
