package com.hatmod;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 「/kill @e」这种一锅端：她说的话要换一套。
 *
 * <p>指令把整个世界一起清掉的时候，普通那句「这不是正常的死法吧」就太轻了 ——
 * 她要吐槽的是「你刚才输入了什么」。这里只做一件事：看出这次指令是不是
 * {@code kill} 一类的「@e 全选」，把接下来 2 刻标成「一锅端」。
 *
 * <p>只认 @e 开头（{@code @e[...]} 也算）；针对单个实体的 /kill 不受影响。
 */
@Mod.EventBusSubscriber(modid = HatMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TetoMassKill {

    /** 标到哪一刻（游戏刻）：这一锅里的所有指令击杀都算。 */
    private static long activeUntil = Long.MIN_VALUE;

    private TetoMassKill() {
    }

    @SubscribeEvent
    public static void onCommand(CommandEvent event) {
        String raw = event.getParseResults().getReader().getString();
        if (raw == null || !looksLikeKillAll(raw)) {
            return;
        }
        var source = event.getParseResults().getContext().getSource();
        if (source.getServer() == null) {
            return;
        }
        activeUntil = source.getLevel().getGameTime() + 2L;
        speakMass(source);
    }

    /**
     * 指令层直接把「一锅端」那句说出来。
     *
     * <p>以前全靠 {@code Teto#die}：可 {@code /kill @e} 会把在场所有人一起清掉 ——
     * 谁先死、链路收场会不会先把她收走、她死时玩家还在不在，全都不一定，
     * 靠死亡事件就会「有时候有、有时候没有」。这里在命令执行**之前**就说，
     * {@code die()} 里那句同一刻会被同一种冷却压掉，不会说两遍。
     */
    private static void speakMass(net.minecraft.commands.CommandSourceStack source) {
        if (HatSettings.forceBreak()) {
            return;
        }
        ServerLevel level = source.getLevel();
        ServerPlayer player = source.getPlayer();
        Teto speaker = null;
        if (player != null) {
            for (Teto teto : Teto.ownedBy(player)) {
                if (!teto.isRemoved() && !teto.isExtra() && teto.level() == level) {
                    speaker = teto;
                    break;
                }
            }
        }
        if (speaker == null) {
            return;                     // 她不在这一层：没什么好说的
        }
        int summons = TetoSpeech.summonsOf(
                player != null ? player.getUUID() : speaker.getOwnerUUID());
        // 半径给足：一锅端是「全世界的事」，站在几十格外也该听得见
        TetoTalk.sayCooled(level, speaker, 128.0D, "kill", 60, TetoSpeech.pick(level.random,
                summons, "hatmod.teto.kill.mass", 2, 1));
    }

    /** 这一刻的指令击杀算不算「一锅端」。 */
    static boolean massKill(long now) {
        return now <= activeUntil;
    }

    /** 是不是 {@code kill @e} / {@code minecraft:kill @e[...]} 这种全选。 */
    private static boolean looksLikeKillAll(String raw) {
        String cmd = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (cmd.startsWith("/")) {
            cmd = cmd.substring(1).trim();
        }
        int space = cmd.indexOf(' ');
        if (space < 0) {
            return false;
        }
        String root = cmd.substring(0, space);
        if (!root.equals("kill") && !root.equals("minecraft:kill")) {
            return false;
        }
        return cmd.substring(space + 1).trim().startsWith("@e");
    }
}
