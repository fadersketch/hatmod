package com.hatmod;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.entity.monster.Slime;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 「脑蚀」那套「原版全部负面效果」会顺手爆出**史莱姆**（渗液 / OOZING）和**蠹虫**（虫蚀 / INFESTED）。
 *
 * <p>它们是货真价实的敌对生物：会打玩家、会连锁触发别的效果，一圈打下来场面完全失控。
 * 所以这里在它们**刚进世界**的那一刻把它们记成「脑蚀主人的小怪」：
 * <ul>
 *   <li>不再锁定主人和主人的盟友（每 5 刻清一次目标）；</li>
 *   <li>对主人一方打不出任何伤害（{@link LivingHurtEvent} 直接取消）；</li>
 *   <li>{@code isFriendlyTo} 认得它们 —— 主人的光柱、减速圈、负面场都不会误伤；</li>
 *   <li>曲子结束 / 摘帽时一起清掉，不留一地史莱姆。</li>
 * </ul>
 *
 * <p>1.20.1 没有 OOZING / INFESTED 这两个效果，这个类在那边基本是空转；
 * 但「附近有脑蚀循环时新刷出来的史莱姆/蠹虫归主人」这条规则仍然生效。
 */
@Mod.EventBusSubscriber(modid = HatMod.MOD_ID)
public final class BrainrotMinions {

    /** 新刷出来的小怪离戴帽者多近才认主。 */
    private static final double CAPTURE_RANGE = 24.0D;
    /** 维护（清目标）的距离，超过就不再管它。 */
    private static final double UPKEEP_RANGE = 48.0D;

    /** 小怪 -> 主人。 */
    private static final Map<UUID, UUID> OWNERS = new HashMap<>();

    /** 被主人「坐下」命令定住的小怪。 */
    private static final Set<UUID> SITTING = new HashSet<>();

    /** 离主人超过这个距离就开始往过走（格）。 */
    private static final double FOLLOW_BEGIN = 5.0D;
    /** 离主人超过这个距离直接传送过去（和驯服的狼一样，跑丢了会自己回来）。 */
    private static final double TELEPORT_AT = 24.0D;

    private BrainrotMinions() {
    }

    // ------------------------------------------------------------------

    /** 史莱姆（含岩浆怪之外的普通史莱姆）和蠹虫 —— 就是这两个效果的产物。 */
    private static boolean isSpawnedMinion(Entity entity) {
        return entity instanceof Slime || entity instanceof Silverfish;
    }

    @SubscribeEvent
    public static void onJoinLevel(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        // 没人在脑蚀循环里就完全不做任何事（世界加载时实体一大堆，先挡掉）
        if (!BrainrotCycle.anyRunning()) {
            return;
        }
        Entity entity = event.getEntity();
        if (!isSpawnedMinion(entity)) {
            return;
        }
        LivingEntity owner = nearestWearer(level, entity);
        if (owner == null) {
            return;
        }
        OWNERS.put(entity.getUUID(), owner.getUUID());
        if (entity instanceof Mob mob) {
            mob.setPersistenceRequired();
            mob.setTarget(null);
        }
    }

    /** 附近正在跑脑蚀循环的戴帽者。 */
    @Nullable
    private static LivingEntity nearestWearer(ServerLevel level, Entity entity) {
        LivingEntity best = null;
        double bestDistance = CAPTURE_RANGE * CAPTURE_RANGE;
        for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class,
                entity.getBoundingBox().inflate(CAPTURE_RANGE))) {
            if (!BrainrotCycle.isRunning(candidate) || !HatAbilities.isWearingHat(candidate)) {
                continue;
            }
            double distance = candidate.distanceToSqr(entity);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * 是不是 {@code wearer} 名下的、由「脑蚀」放出来的小怪。
     *
     * <p>{@code wearer} 不一定是玩家本人 —— 帽子也可能戴在女仆、虚影、Teto 头上，
     * 那时候按「它的主人」算：同一个主人的东西互相都是自己人。
     */
    static boolean isMinionOf(Entity entity, LivingEntity wearer) {
        if (wearer == null) {
            return false;
        }
        UUID minionOwner = OWNERS.get(entity.getUUID());
        if (minionOwner == null) {
            return false;
        }
        if (minionOwner.equals(wearer.getUUID())) {
            return true;
        }
        return wearer instanceof OwnableEntity ownable && minionOwner.equals(ownable.getOwnerUUID());
    }

    /** 每 5 刻由 {@code BrainrotCycle} 调一次：让它们别去打主人和主人的盟友。 */
    static void upkeep(ServerLevel level, LivingEntity owner) {
        if (OWNERS.isEmpty()) {
            return;
        }
        UUID ownerId = owner.getUUID();
        double rangeSq = UPKEEP_RANGE * UPKEEP_RANGE;
        Iterator<Map.Entry<UUID, UUID>> iterator = OWNERS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, UUID> entry = iterator.next();
            if (!ownerId.equals(entry.getValue())) {
                continue;
            }
            Entity entity = level.getEntity(entry.getKey());
            if (!(entity instanceof Mob mob) || mob.isRemoved()) {
                iterator.remove();
                continue;
            }
            if (mob.distanceToSqr(owner) > rangeSq) {
                continue;
            }
            // 听指挥坐下：原地待着，不跟随也不主动打人
            if (SITTING.contains(mob.getUUID())) {
                mob.setTarget(null);
                mob.getNavigation().stop();
                continue;
            }
            LivingEntity target = mob.getTarget();
            if (target != null && HatAbilities.isFriendlyTo(owner, target)) {
                mob.setTarget(null);
            }
            if (mob.getTarget() == null) {
                LivingEntity wanted = ownerTarget(owner);
                if (wanted != null) {
                    mob.setTarget(wanted);
                }
            }
            // 没在打架就跟着主人走：超过 5 格走过来，超过 24 格直接传过去
            if (mob.getTarget() == null && mob.level() == owner.level()) {
                double distanceSq = mob.distanceToSqr(owner);
                if (distanceSq > TELEPORT_AT * TELEPORT_AT) {
                    teleportNear(mob, owner);
                } else if (distanceSq > FOLLOW_BEGIN * FOLLOW_BEGIN) {
                    // 史莱姆不认寻路、只认 MoveControl，所以两边都设一遍
                    mob.getNavigation().moveTo(owner, 1.0D);
                    mob.getMoveControl().setWantedPosition(owner.getX(), owner.getY(), owner.getZ(), 1.0D);
                }
            }
        }
    }

    /** 传送到主人身边（带一点随机偏移，免得几只叠在同一个点上）。 */
    private static void teleportNear(Mob mob, LivingEntity owner) {
        double x = owner.getX() + (mob.getRandom().nextDouble() - 0.5D) * 4.0D;
        double z = owner.getZ() + (mob.getRandom().nextDouble() - 0.5D) * 4.0D;
        mob.teleportTo(x, owner.getY(), z);
        mob.getNavigation().stop();
    }

    /**
     * 像**驯服的狼**那样选目标：优先「刚打过主人的那个」，其次「主人刚打的 那个」
     * （原版就是 {@code OwnerHurtByTargetGoal} + {@code OwnerHurtTargetGoal} 两个目标）。
     * 已经是自己人的不打。
     */
    @Nullable
    private static LivingEntity ownerTarget(LivingEntity owner) {
        LivingEntity attacker = owner.getLastHurtByMob();
        if (isValidTarget(owner, attacker)) {
            return attacker;
        }
        LivingEntity victim = owner.getLastHurtMob();
        return isValidTarget(owner, victim) ? victim : null;
    }

    private static boolean isValidTarget(LivingEntity owner, @Nullable LivingEntity candidate) {
        return candidate != null
                && candidate.isAlive()
                && !candidate.isRemoved()
                && !HatAbilities.isFriendlyTo(owner, candidate);
    }

    /** 曲终 / 摘帽：把这些小怪一起收掉。 */
    static void clear(ServerLevel level, LivingEntity owner) {
        if (OWNERS.isEmpty()) {
            return;
        }
        UUID ownerId = owner.getUUID();
        Iterator<Map.Entry<UUID, UUID>> iterator = OWNERS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, UUID> entry = iterator.next();
            if (!ownerId.equals(entry.getValue())) {
                continue;
            }
            Entity entity = level.getEntity(entry.getKey());
            if (entity != null) {
                entity.discard();
            }
            SITTING.remove(entry.getKey());
            iterator.remove();
        }
    }

    /** 命令：潜行 + 右键自家小怪 = 坐下 / 起立（和狗一样）。 */
    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (OWNERS.isEmpty() || !(event.getTarget() instanceof Mob mob)) {
            return;
        }
        UUID ownerId = OWNERS.get(mob.getUUID());
        Entity interactor = event.getEntity();
        if (ownerId == null || !(interactor instanceof Player player)
                || !ownerId.equals(player.getUUID())) {
            return;
        }
        // 只有主人在潜行时右键才算命令，平时右键还是原版行为
        if (!player.isShiftKeyDown()) {
            return;
        }
        if (!SITTING.remove(mob.getUUID())) {
            SITTING.add(mob.getUUID());
            mob.getNavigation().stop();
            mob.setTarget(null);
        }
        event.setCanceled(true);
    }

    /** 召唤物不掉落任何东西：一地史莱姆球既吵又没人捡。 */
    @SubscribeEvent
    public static void onDrops(LivingDropsEvent event) {
        if (OWNERS.isEmpty()) {
            return;
        }
        if (OWNERS.containsKey(event.getEntity().getUUID())) {
            event.getDrops().clear();
        }
    }

    /**
     * 自家小怪打不出伤害 —— 史莱姆是靠碰人扣血的，光清目标拦不住，得在这里直接取消。
     * 只取消「打在主人一方身上」的那部分，它们打敌人照常生效。
     */
    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        if (OWNERS.isEmpty()) {
            return;
        }
        Entity attacker = event.getSource().getEntity();
        if (!(attacker instanceof LivingEntity) || !OWNERS.containsKey(attacker.getUUID())) {
            return;
        }
        LivingEntity victim = event.getEntity();
        UUID ownerId = OWNERS.get(attacker.getUUID());
        if (ownerId == null) {
            return;
        }
        if (ownerId.equals(victim.getUUID())) {
            event.setCanceled(true);
            return;
        }
        if (victim.level() instanceof ServerLevel level
                && level.getEntity(ownerId) instanceof LivingEntity owner
                && HatAbilities.isFriendlyTo(owner, victim)) {
            event.setCanceled(true);
        }
    }
}
