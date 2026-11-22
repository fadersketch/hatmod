package com.hatmod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 「第一次」的里程碑呓语（思路来自「神秘遗物」的莫测之眼）。
 *
 * <p>莫测之眼会在这些时刻开口：第一次到下界、第一次进末地、第一次打龙 / 屠龙、
 * 第一次召唤 / 击杀凋灵、第一次复活龙……每种只说一次，记在玩家身上。
 * 这里照同一套补：
 *
 * <ul>
 *   <li>第一次到下界 / 第一次到末地；</li>
 *   <li>第一次召唤凋灵；</li>
 *   <li>第一次对末影龙动手 / 第 1、2、3 次屠龙 / 第一次复活末影龙；</li>
 *   <li>第一次击败凋灵 / 监守者；</li>
 *   <li>第一次经折跃门到外岛（末地里一瞬之间跨出去很远）。</li>
 * </ul>
 *
 * <p>只对**见过她**的玩家（召唤次数 ≥ 1）生效；每种里程碑每个玩家只说一次
 * （存在 {@code HatSettings} 的「说过的台词」里，跟记忆台词同一张表）。
 * 她在旁边就从她那儿说；不在（例如换维度时她跟不过来）就直接说给玩家听。
 *
 * <p>「击败」的判定：最后一刀是**玩家本人**打的（宠物、环境伤害、别的玩家补的刀都不算）；
 * 不要求单杀 —— 一群人围殴、你补最后一刀也算。
 */
@Mod.EventBusSubscriber(modid = HatMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TetoMilestones {

    /** 「在旁边」的判定半径（格）。 */
    private static final double NEAR = 24.0D;

    /** 每种里程碑几条台词。 */
    private static final int VARIANTS = 2;

    /** 折跃门判定的最小位移（格）：末地里一瞬跨出这么远，只可能是折跃门 / 传送。 */
    private static final double GATEWAY_JUMP = 200.0D;

    /** 玩家 -> 上一次在末地看到的坐标 [x, z]。 */
    private static final Map<UUID, double[]> LAST_END_POS = new HashMap<>();

    private TetoMilestones() {
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        // 先像原版宠物那样把她带过来：不然换维度那一刻她还在原来那层，
        // 「初次到下界 / 末地」这两句永远等不到她在场。
        if (player.level() instanceof ServerLevel here) {
            Teto.followThroughDimension(player, here);
        }
        ResourceKey<Level> to = event.getTo();
        if (Level.NETHER.equals(to)) {
            fire(player, "mile.nether", "nether");
        } else if (Level.END.equals(to)) {
            LAST_END_POS.remove(player.getUUID());
            fire(player, "mile.end", "end");
        }
    }

    /** 凭空多出来一只 Boss：凋灵被召唤 / 末影龙被复活。 */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Entity entity = event.getEntity();
        if (entity instanceof WitherBoss) {
            ServerPlayer near = nearestPlayer(level, entity, 32.0D);
            if (near != null) {
                fire(near, "mile.wither_spawn", "wither_spawn");
            }
        } else if (entity instanceof EnderDragon) {
            // 之前已经屠过一条 = 这次是复活出来的
            ServerPlayer near = nearestPlayer(level, entity, 64.0D);
            if (near != null && HatSettings.tetoSpoken(near.getUUID(), "mile.dragon")) {
                fire(near, "mile.dragon_revive", "dragon_revive");
            }
        }
    }

    /** 第一次对末影龙动手。 */
    @SubscribeEvent
    public static void onDragonHit(LivingHurtEvent event) {
        if (event.getEntity() instanceof EnderDragon
                && event.getSource().getEntity() instanceof ServerPlayer player) {
            fire(player, "mile.dragon_attack", "dragon_attack");
        }
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (!(event.getSource().getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (victim instanceof EnderDragon) {
            // 第 1 / 2 / 3 次各一套；第 4 次往后不再说
            if (fire(player, "mile.dragon", "dragon")) {
                return;
            }
            if (fire(player, "mile.dragon2", "dragon2")) {
                return;
            }
            fire(player, "mile.dragon3", "dragon3");
            return;
        }
        if (victim instanceof WitherBoss) {
            fire(player, "mile.wither", "wither");
            return;
        }
        if (victim instanceof Warden) {
            fire(player, "mile.warden", "warden");
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            trackGateway(event.player);
        }
    }

    /** 末地里一瞬之间跨出去很远 = 走了折跃门。 */
    private static void trackGateway(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)
                || HatSettings.tetoSummons(player.getUUID()) <= 0
                || !Level.END.equals(player.level().dimension())) {
            return;
        }
        double[] last = LAST_END_POS.get(player.getUUID());
        LAST_END_POS.put(player.getUUID(), new double[]{player.getX(), player.getZ()});
        if (LAST_END_POS.size() > 64) {
            LAST_END_POS.clear();
        }
        if (last == null) {
            return;
        }
        double dx = player.getX() - last[0];
        double dz = player.getZ() - last[1];
        if (dx * dx + dz * dz >= GATEWAY_JUMP * GATEWAY_JUMP) {
            fire(serverPlayer, "mile.end_gateway", "end_gateway");
        }
    }

    /** 离某个实体最近的玩家（没有就 null）。 */
    private static ServerPlayer nearestPlayer(ServerLevel level, Entity entity, double radius) {
        return level.getNearestPlayer(entity, radius) instanceof ServerPlayer player ? player : null;
    }

    /**
     * 说一句里程碑台词。
     *
     * @return 这次真的说了（没说过、她认识你）
     */
    private static boolean fire(ServerPlayer player, String spokenTag, String lineTag) {
        if (HatSettings.tetoSummons(player.getUUID()) <= 0) {
            return false;                   // 还不认识她，轮不到她插嘴
        }
        if (HatSettings.tetoSpoken(player.getUUID(), spokenTag)) {
            return false;
        }
        ServerLevel level = player.serverLevel();
        Teto speaker = null;
        for (Teto teto : Teto.ownedBy(player)) {
            if (!teto.isRemoved() && teto.level() == level
                    && teto.distanceToSqr(player) <= NEAR * NEAR) {
                speaker = teto;
                break;
            }
        }
        if (speaker == null) {
            // 她不在场：不说，也**不算说过了** —— 下次同类事件（再屠一条龙、再打倒一只监守者）
            // 她还在你身边时才会开口。以前这里会直接 sayTo 说给玩家听，世界里根本没有她的时候
            // 也会冒出「你居然还赢了」这种话，看着就是 bug。
            return false;
        }
        HatSettings.markTetoSpoken(player.getUUID(), spokenTag);
        String key = "hatmod.teto.mile." + lineTag + "." + (1 + level.random.nextInt(VARIANTS));
        TetoTalk.sayNear(level, speaker, 32.0D, key);
        return true;
    }
}
