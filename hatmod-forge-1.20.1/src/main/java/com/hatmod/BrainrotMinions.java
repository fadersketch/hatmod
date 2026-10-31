package com.hatmod;

import net.minecraft.core.BlockPos;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
    /**
     * 单个主人最多认领多少只召唤物。
     *
     * <p>负面场挂上「渗液 / 虫蚀」之后，史莱姆会不断分裂、蠹虫会不断冒出来；
     * 不加盖子的话十几秒就能堆出一大群、卡到掉帧。到上限之后新冒出来的**直接抹掉**，
     * 而不是放着当敌人。
     */
    private static final int MAX_MINIONS_PER_OWNER = 8;

    /**
     * 落在世界里的召唤物标记。
     *
     * <p>这些虫子和史莱姆带 {@code setPersistenceRequired()}，会跟着存档留下来；
     * 而「谁是主人」只记在内存的 {@link #OWNERS} 里 —— 重进游戏就没了，
     * 它们于是变成一群无主的敌对单位。所以给它们打个标签，
     * **服务器一启动就把带标签的全清掉**。
     */
    private static final String MINION_TAG = "hatmod_brainrot_minion";

    /** 等着被删的召唤物（下一轮 upkeep 删，避开「加入即删」的幽灵包问题）。 */
    private static final List<UUID> PENDING_REMOVAL = new ArrayList<>();

    /** 小怪 -> 主人。 */
    private static final Map<UUID, UUID> OWNERS = new HashMap<>();

    /** 被主人「坐下」命令定住的小怪。 */
    private static final Set<UUID> SITTING = new HashSet<>();

    /** 离主人超过这个距离就开始往过走（格）。 */
    private static final double FOLLOW_BEGIN = 5.0D;
    /** 离主人超过这个距离直接传送过去（和驯服的狼一样，跑丢了会自己回来）。 */
    private static final double TELEPORT_AT = 24.0D;
    /** 史莱姆只会原地蹦，寻路对它无效，所以靠传送跟人 —— 门槛放低一点，不然会落太远。 */
    private static final double SLIME_TELEPORT_AT = 12.0D;

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
        // 到上限了：这一只直接抹掉，别让场上越滚越多
        if (countOwned(level, owner.getUUID()) >= MAX_MINIONS_PER_OWNER) {
            // **不能**在这里 discard：同一 tick 内「加入 + 丢弃」时 ServerEntity 还没建起来，
            // 客户端会收到生成包、却永远收不到移除包 —— 表现就是「悬在空中不动的幽灵」。
            // 记进待删表，等下一轮 upkeep（5 刻后、实体已被跟踪）再删。
            PENDING_REMOVAL.add(entity.getUUID());
            return;
        }
        OWNERS.put(entity.getUUID(), owner.getUUID());
        // 打上标签：万一下次启动时它还在世界里，按这个标签直接清掉
        entity.addTag(MINION_TAG);
        if (entity instanceof Mob mob) {
            mob.setPersistenceRequired();
            mob.setTarget(null);
            normalize(mob);
        }
    }

    /**
     * 服务器一启动就把上一局留下的召唤物清掉。
     *
     * <p>它们的主人信息只存在内存里，重进游戏后这群虫子/史莱姆会**变成普通的敌对生物**
     * （史莱姆甚至会继续分裂）。与其让玩家自己打，不如在加载完的那一刻直接抹掉。
     */
    @SubscribeEvent
    public static void onServerStarted(net.minecraftforge.event.server.ServerStartedEvent event) {
        int removed = 0;
        for (ServerLevel level : event.getServer().getAllLevels()) {
            List<Entity> doomed = new ArrayList<>();
            for (Entity entity : level.getAllEntities()) {
                // 上一局的召唤物 和 上一局的 Teto 一起清掉：它们的「主人/这一轮」记录都在内存里，
                // 重进游戏就失效了 —— 留着只会变成无主敌对单位，或者让下一轮多叠 4 只
                if (entity instanceof Teto || entity.getTags().contains(MINION_TAG)) {
                    doomed.add(entity);
                }
            }
            for (Entity entity : doomed) {
                entity.discard();
                removed++;
            }
        }
        if (removed > 0) {
            HatMod.LOGGER.info("[HatMod] 清理上一局残留的脑蚀召唤物：{} 个", removed);
        }
    }

    /** 这个主人现在名下还有几只召唤物（顺手把已经不在世界里的记录清掉）。 */
    private static int countOwned(ServerLevel level, UUID ownerId) {
        int count = 0;
        // 快照遍历：循环里会顺手 OWNERS.remove，原表上直接走会 CME
        for (Map.Entry<UUID, UUID> entry : new ArrayList<>(OWNERS.entrySet())) {
            if (!ownerId.equals(entry.getValue())) {
                continue;
            }
            if (level.getEntity(entry.getKey()) == null) {
                OWNERS.remove(entry.getKey());
                continue;
            }
            count++;
        }
        return count;
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
        // 待删表：到这一轮再删，客户端一定收得到移除包
        if (!PENDING_REMOVAL.isEmpty()) {
            // 先快照再清空：discard 史莱姆会分裂出小史莱姆，那些又会触发
            // EntityJoinLevel -> 往 PENDING_REMOVAL 里加东西，直接在原表上遍历会 CME。
            List<UUID> pending = new ArrayList<>(PENDING_REMOVAL);
            PENDING_REMOVAL.clear();
            for (UUID id : pending) {
                Entity doomed = level.getEntity(id);
                if (doomed != null) {
                    doomed.discard();
                }
            }
        }
        // 附近**所有**史莱姆/蠹虫（不管有没有登记成召唤物）都恢复重力、清悬浮 ——
        // 有些是在没人的时候爆出来的，从没进过 OWNERS，光靠登记表管不到
        for (Entity nearby : level.getEntitiesOfClass(Entity.class,
                owner.getBoundingBox().inflate(UPKEEP_RANGE), BrainrotMinions::isSpawnedMinion)) {
            if (nearby instanceof Mob mob) {
                normalize(mob);
            }
        }
        if (OWNERS.isEmpty()) {
            return;
        }
        UUID ownerId = owner.getUUID();
        double rangeSq = UPKEEP_RANGE * UPKEEP_RANGE;
        int alive = 0;
        // 快照遍历：循环体里 discard 史莱姆会分裂出小史莱姆（EntityJoinLevel 反过来
        // 改 OWNERS），自己也会 OWNERS.remove，用迭代器必然 CME。
        for (Map.Entry<UUID, UUID> entry : new ArrayList<>(OWNERS.entrySet())) {
            if (!ownerId.equals(entry.getValue())) {
                continue;
            }
            Entity entity = level.getEntity(entry.getKey());
            if (!(entity instanceof Mob mob) || mob.isRemoved()) {
                OWNERS.remove(entry.getKey());
                continue;
            }
            // 硬上限：超过 MAX_MINIONS_PER_OWNER 的直接抹掉（入世界时的拦截可能漏掉，
            // 比如效果一次爆出好几只、或者上限是在已有怪物之后才降下来的）
            if (++alive > MAX_MINIONS_PER_OWNER) {
                mob.discard();
                OWNERS.remove(entry.getKey());
                continue;
            }
            if (mob.distanceToSqr(owner) > rangeSq) {
                normalize(mob);
                continue;
            }
            // 兜底：别让它们卡在半空（渗液/虫蚀是在目标死亡的位置爆出来的，
            // 目标被定身/浮空时小怪就出生在空中；也可能继承到悬浮类效果）
            normalize(mob);
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
            // 没在打架就跟着主人走
            if (mob.getTarget() == null && mob.level() == owner.level()) {
                double distanceSq = mob.distanceToSqr(owner);
                // 史莱姆没有可用的寻路（只会原地蹦），靠传送跟人；其它（蠹虫）正常走位
                double teleportAt = mob instanceof Slime ? SLIME_TELEPORT_AT : TELEPORT_AT;
                if (distanceSq > teleportAt * teleportAt) {
                    teleportNear(mob, owner);
                } else if (!(mob instanceof Slime) && distanceSq > FOLLOW_BEGIN * FOLLOW_BEGIN) {
                    mob.getNavigation().moveTo(owner, 1.0D);
                }
            }
        }
    }

    /**
     * 传送到主人身边（带一点随机偏移，免得几只叠在同一个点上）。
     *
     * <p>落点**必须踩得到地**：早先直接落在 {@code owner.getY()}，主人站在桥上/崖边时
     * 史莱姆会被每 5 刻传送回半空再掉下去，看起来就是「悬空卡死」。现在从主人脚上方
     * 往下找第一块能站的地面才落；找不到（悬崖正上方）就这一次不传送，让它们自己待着。
     */
    private static void teleportNear(Mob mob, LivingEntity owner) {
        double x = owner.getX() + (mob.getRandom().nextDouble() - 0.5D) * 4.0D;
        double z = owner.getZ() + (mob.getRandom().nextDouble() - 0.5D) * 4.0D;
        BlockPos floor = findFloor(mob.level(), BlockPos.containing(x, owner.getY() + 1.0D, z));
        if (floor == null) {
            return;
        }
        mob.teleportTo(floor.getX() + 0.5D, floor.getY(), floor.getZ() + 0.5D);
        mob.setDeltaMovement(0.0D, 0.0D, 0.0D);
        mob.getNavigation().stop();
    }

    /**
     * 把召唤物掰回一个正常状态：恢复重力、清掉悬浮/缓降。
     *
     * <p>渗液（史莱姆）和虫蚀（蠹虫）是在**目标死亡的位置**爆出来的 —— 目标要是正被
     * 光柱定身、或者正飘在半空，小怪就出生在空中；再加上它们可能继承到悬浮类效果，
     * 就会表现成「悬空卡死」。这里每 5 刻兜一次：有重力就恢复、有悬浮就清掉，
     * 悬着不动又没有目标的，直接放回它脚下最近的地面。
     */
    private static void normalize(Mob mob) {
        if (mob.isNoGravity()) {
            mob.setNoGravity(false);
        }
        mob.removeEffect(net.minecraft.world.effect.MobEffects.LEVITATION);
        mob.removeEffect(net.minecraft.world.effect.MobEffects.SLOW_FALLING);
        // 静止悬空（没目标、速度几乎为零、又不在地面上）→ 放回地面
        if (!mob.onGround() && mob.getTarget() == null
                && mob.getDeltaMovement().lengthSqr() < 1.0E-4D) {
            BlockPos floor = findFloor(mob.level(), BlockPos.containing(
                    mob.getX(), mob.getY() + 1.0D, mob.getZ()));
            if (floor != null) {
                mob.teleportTo(floor.getX() + 0.5D, floor.getY(), floor.getZ() + 0.5D);
                mob.setDeltaMovement(0.0D, 0.0D, 0.0D);
            }
        }
    }

    /** 从 {@code from} 往下最多找 16 格，第一块「脚下实心、自己站得进去」的位置。 */
    private static BlockPos findFloor(net.minecraft.world.level.Level level, BlockPos from) {
        BlockPos.MutableBlockPos pos = from.mutable();
        for (int i = 0; i < 16; i++) {
            BlockPos below = pos.below();
            if (level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                    && !level.getBlockState(below).getCollisionShape(level, below).isEmpty()) {
                return pos.immutable();
            }
            pos.move(net.minecraft.core.Direction.DOWN);
        }
        return null;
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
        // 同上：discard 可能分裂出新怪再回头改 OWNERS，先快照
        for (Map.Entry<UUID, UUID> entry : new ArrayList<>(OWNERS.entrySet())) {
            if (!ownerId.equals(entry.getValue())) {
                continue;
            }
            Entity entity = level.getEntity(entry.getKey());
            if (entity != null) {
                entity.discard();
            }
            SITTING.remove(entry.getKey());
            OWNERS.remove(entry.getKey());
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
