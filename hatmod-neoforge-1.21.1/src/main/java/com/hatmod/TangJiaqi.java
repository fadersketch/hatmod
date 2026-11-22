package com.hatmod;

import com.hatmod.guard.HatGuard;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 唐嘉琦（1）—— 「脑蚀」链路里和 {@link Teto} 一起被放出来的同伴。
 *
 * <p>定位是**跟着玩家走的友方单位**：
 * <ul>
 *   <li>20 血 / 2 护甲，靠的是<b>100 次闪避</b>：每次挨打先扣一次闪避、这一下完全免疫，
 *       并把**等量伤害原样弹回**出手者；闪避用光之后就按普通生物结算。</li>
 *   <li>仇恨等级同村民：敌对生物会像看见村民一样盯上它（但已经在追玩家的不抢）。</li>
 *   <li>链路的光柱段里和玩家一起放光柱：两道，数值 / 伤害类型跟白板黑帽完全一致；
 *       玩家停火的那一刻它也一起停。</li>
 *   <li>链路结束（曲终 / 摘帽 / 断链）立刻清除 —— 它不是散养宠物，没有刷怪蛋。</li>
 * </ul>
 *
 * <p>渲染和 Teto 一样是面向相机的平面贴图（见 {@code client/TangJiaqiRenderer}）。
 */
public class TangJiaqi extends PathfinderMob implements OwnableEntity {

    /** 召唤者 UUID。 */
    private static final EntityDataAccessor<Optional<UUID>> DATA_OWNER =
            SynchedEntityData.defineId(TangJiaqi.class, EntityDataSerializers.OPTIONAL_UUID);

    /** 一共能闪几次（用完就正常挨打）。 */
    public static final int DODGE_CHARGES = 100;

    /** 跟随时离主人多近就停下（格）。 */
    private static final double FOLLOW_STOP = 3.0D;
    /** 每刻最多挪多远（格）。 */
    private static final double FOLLOW_SPEED = 0.30D;
    /** 超过这个距离直接传送到主人身边（格）。 */
    private static final double FOLLOW_TELEPORT = 28.0D;

    /** 招仇恨：多久扫一次周围（刻）。 */
    private static final int AGGRO_PERIOD = 20;
    /** 招仇恨：敌对生物多近才会锁它（格）—— 和村民一个量级。 */
    private static final double AGGRO_RADIUS = 16.0D;

    /** 看见多近的敌人就往它那边靠（格）。 */
    private static final double CHASE_RADIUS = 12.0D;
    /** 追敌时最远离主人多少格（格）。 */
    private static final double CHASE_LEASH = 16.0D;

    /** 光柱道数：和白板黑帽一样的 2 道。 */
    private static final int BEAM_LANES = HatAbilities.BASE_BEAM_COUNT;

    /** 链路已经收场，连续这么多刻就走人（兜底，正常由 clear 收）。 */
    private static final int ORPHAN_GRACE_TICKS = 40;

    /** 还剩几次闪避。 */
    private int dodges = DODGE_CHARGES;
    /** 「链路已经没了」连续出现了几刻。 */
    private int orphanTicks;
    /** 这一刻是不是在放光柱（用来只在变化时发包）。 */
    private boolean beaming;

    /** 现在是不是正在放光柱（GhostGuard 拿它决定「按住鬼」还是「就地清除」）。 */
    boolean isBeaming() {
        return this.beaming;
    }
    /** 上一次播闪避音效的服务器刻（连击时别刷屏）。 */
    private long lastDodgeSound = Long.MIN_VALUE / 2;

    public TangJiaqi(EntityType<? extends TangJiaqi> type, Level level) {
        super(type, level);
        // 和 Teto 一样是「每刻直接摆到主人身边」的贴图生物：不走碰撞、不受重力，
        // 否则站在地上会被重力一路拽穿方块。
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.ARMOR, 2.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0D)
                .add(Attributes.FOLLOW_RANGE, 32.0D);
    }

    /** 召唤：认主、关掉自带 AI（移动完全由 tick 接管）。 */
    void init(LivingEntity owner) {
        setOwnerUUID(owner.getUUID());
        this.setNoAi(true);
        this.setPersistenceRequired();
        this.dodges = DODGE_CHARGES;
        this.orphanTicks = 0;
        this.beaming = false;
    }

    // ------------------------------------------------------------------

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_OWNER, Optional.empty());
    }

    @Nullable
    @Override
    public UUID getOwnerUUID() {
        return this.entityData.get(DATA_OWNER).orElse(null);
    }

    public void setOwnerUUID(@Nullable UUID uuid) {
        this.entityData.set(DATA_OWNER, Optional.ofNullable(uuid));
        rememberOwner(this.getUUID(), uuid);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        UUID owner = getOwnerUUID();
        if (owner != null) {
            tag.putUUID("Owner", owner);
        }
        tag.putInt("Dodges", this.dodges);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID("Owner")) {
            setOwnerUUID(tag.getUUID("Owner"));
        }
        this.dodges = tag.contains("Dodges") ? tag.getInt("Dodges") : DODGE_CHARGES;
    }

    @Override
    protected void registerGoals() {
    }

    public boolean isOwnedBy(LivingEntity owner) {
        return owner != null && owner.getUUID().equals(getOwnerUUID());
    }

    /** 还剩几次闪避（调参 / 调试用）。 */
    public int dodgeCharges() {
        return this.dodges;
    }

    @Nullable
    public LivingEntity ownerEntity(ServerLevel level) {
        UUID id = getOwnerUUID();
        if (id == null) {
            return null;
        }
        Entity entity = level.getEntity(id);
        return entity instanceof LivingEntity living && living.isAlive() ? living : null;
    }

    // ------------------------------------------------------------------
    // 名册（和 Teto 一样按 UUID 查，跨维度、不经过实体查询）
    // ------------------------------------------------------------------

    private static final Map<UUID, UUID> OWNER_OF = new HashMap<>();

    private static void rememberOwner(@Nullable UUID self, @Nullable UUID owner) {
        if (self == null) {
            return;
        }
        if (owner == null) {
            OWNER_OF.remove(self);
        } else {
            OWNER_OF.put(self, owner);
        }
    }

    /** 某个人名下还活着的唐嘉琦。 */
    static List<TangJiaqi> ownedBy(LivingEntity owner) {
        List<TangJiaqi> found = new ArrayList<>();
        if (owner == null || OWNER_OF.isEmpty()) {
            return found;
        }
        UUID ownerId = owner.getUUID();
        List<ServerLevel> levels = new ArrayList<>();
        if (owner.level() instanceof ServerLevel here && here.getServer() != null) {
            for (ServerLevel level : here.getServer().getAllLevels()) {
                levels.add(level);
            }
        } else if (owner.level() instanceof ServerLevel here) {
            levels.add(here);
        }
        Iterator<Map.Entry<UUID, UUID>> it = OWNER_OF.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, UUID> entry = it.next();
            if (!ownerId.equals(entry.getValue())) {
                continue;
            }
            TangJiaqi alive = null;
            for (ServerLevel level : levels) {
                if (level.getEntity(entry.getKey()) instanceof TangJiaqi tang && !tang.isRemoved()) {
                    alive = tang;
                    break;
                }
            }
            if (alive == null) {
                it.remove();
                continue;
            }
            found.add(alive);
        }
        return found;
    }

    /** 名下有没有唐嘉琦（链路起手时用来避免重复召唤）。 */
    static boolean anyOwnedBy(LivingEntity owner) {
        return !ownedBy(owner).isEmpty();
    }

    // ------------------------------------------------------------------
    // 友方判定：和 Teto / 驯服的狼同一条路子
    // ------------------------------------------------------------------

    @Override
    public boolean canAttack(LivingEntity target) {
        return this.isOwnedBy(target) ? false : super.canAttack(target);
    }

    @Override
    public PlayerTeam getTeam() {
        LivingEntity owner = this.getOwner();
        return owner != null ? owner.getTeam() : super.getTeam();
    }

    @Override
    public boolean isAlliedTo(Entity entity) {
        LivingEntity owner = this.getOwner();
        if (entity == owner) {
            return true;
        }
        if (owner != null) {
            return owner.isAlliedTo(entity);
        }
        return super.isAlliedTo(entity);
    }

    // ------------------------------------------------------------------
    // 每刻
    // ------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            return;
        }
        ServerLevel level = (ServerLevel) this.level();
        LivingEntity owner = ownerEntity(level);
        if (owner == null) {
            disappear(level, "主人已不在这个世界");
            return;
        }
        if (owner.isDeadOrDying() || owner.isSpectator()) {
            disappear(level, "主人已经死亡");
            return;
        }
        // 链路没了：兜底清场（正常由 BrainrotCycle.end 调 clear）
        if (!BrainrotCycle.isRunning(owner)) {
            stopBeams(level);
            if (++this.orphanTicks >= ORPHAN_GRACE_TICKS) {
                disappear(level, "脑蚀链路已经收场");
                return;
            }
            follow(level, owner);
            return;
        }
        this.orphanTicks = 0;

        // 怨灵管制：光束段把鬼按在光束下（收手时尾杀），平时贴太近直接移除
        GhostGuard.watchForTang(level, this);
        this.tickBeams(level, owner);
        // 光柱段里站着放光柱，不乱跑；其余时候跟着主人 / 往敌人那边靠
        if (this.beaming) {
            this.setDeltaMovement(Vec3.ZERO);
            this.faceTowards(owner.getX(), owner.getZ());
        } else {
            this.roam(level, owner);
        }
        this.drawAggro(level, owner);
    }

    // ------------------------------------------------------------------
    // 光柱
    // ------------------------------------------------------------------

    /**
     * 和玩家**同一时刻**开火、同一时刻收手（窗口由 {@link BrainrotCycle#beamWindow} 给出）。
     *
     * <p>两道，每道各自锁一个目标；数值 / 伤害类型都取白板黑帽那一份：
     * 帧伤 {@code damagePerTick(BLACK) × BEAM_DAMAGE_SCALE}、附带
     * {@code healthDamageRatio(BLACK)} 的当前生命伤害，命中还带控。
     */
    private void tickBeams(ServerLevel level, LivingEntity owner) {
        if (!BrainrotCycle.beamWindow(owner)) {
            stopBeams(level);
            return;
        }
        List<LivingEntity> targets = new ArrayList<>();
        for (LivingEntity candidate : HatAbilities.findNearestEnemies(this, BEAM_LANES)) {
            // 玩家永远不打：锁敌设置里「玩家也算敌人」是给戴帽玩家用的，同伴不能跟着打人
            if (candidate instanceof Player) {
                continue;
            }
            targets.add(candidate);
        }
        int[] ids = new int[targets.size()];
        for (int i = 0; i < targets.size(); i++) {
            ids[i] = targets.get(i).getId();
        }
        HatNetwork.sendBeamTarget(this, true, ids, BEAM_LANES);
        this.beaming = true;
        if (targets.isEmpty()) {
            return;
        }
        DamageSource source = HatSettings.damageSource(level, this, HatType.BLACK);
        float flat = HatSettings.damagePerTick(HatType.BLACK) * HatAbilities.BEAM_DAMAGE_SCALE;
        float ratio = HatSettings.healthDamageRatio(HatType.BLACK);
        for (LivingEntity target : targets) {
            HatAbilities.damageBeamTick(level, this, HatType.BLACK, source, flat, ratio, true, target);
        }
    }

    /** 收光柱（只在「本来在放」的时候发包）；收手那一刻对怨灵来一次尾杀。 */
    private void stopBeams(ServerLevel level) {
        if (!this.beaming) {
            return;
        }
        this.beaming = false;
        HatNetwork.sendBeamTarget(this, false, new int[0], 0);
        // 尾杀：光束收手，光束扫过的怨灵一并清除（见 GhostGuard#beamFinisher）
        GhostGuard.beamFinisher(level, this);
    }

    // ------------------------------------------------------------------
    // 移动
    // ------------------------------------------------------------------

    /** 跟着主人走；附近有敌人就往敌人那边靠近（但不离主人太远）。 */
    private void roam(ServerLevel level, LivingEntity owner) {
        LivingEntity enemy = nearestEnemy(level, owner);
        if (enemy != null) {
            double dx = enemy.getX() - owner.getX();
            double dz = enemy.getZ() - owner.getZ();
            double away = Math.sqrt(dx * dx + dz * dz);
            double tx = enemy.getX();
            double tz = enemy.getZ();
            if (away > CHASE_LEASH) {
                // 敌人太远：只站到「主人朝敌人方向 leash 格」的位置，不把同伴拉出战场
                tx = owner.getX() + dx / away * CHASE_LEASH;
                tz = owner.getZ() + dz / away * CHASE_LEASH;
            }
            stepToward(tx, enemy.getY(), tz, 2.2D);
            faceTowards(enemy.getX(), enemy.getZ());
            return;
        }
        follow(level, owner);
    }

    /** 贴身的跟随：站在主人侧后方，超过 28 格直接传送。 */
    private void follow(ServerLevel level, LivingEntity owner) {
        double distance = this.distanceTo(owner);
        if (distance > FOLLOW_TELEPORT) {
            double yaw = Math.toRadians(owner.getYRot());
            double side = 1.4D;
            this.moveTo(owner.getX() - Math.sin(yaw) * 1.8D + Math.cos(yaw) * side,
                    owner.getY(),
                    owner.getZ() + Math.cos(yaw) * 1.8D + Math.sin(yaw) * side,
                    owner.getYRot(), 0.0F);
            return;
        }
        if (distance <= FOLLOW_STOP) {
            return;
        }
        double yaw = Math.toRadians(owner.getYRot());
        // 稍微偏在主人右手边，别和 Teto 的绕圈轨道重叠
        double tx = owner.getX() - Math.sin(yaw) * 1.8D + Math.cos(yaw) * 0.9D;
        double tz = owner.getZ() + Math.cos(yaw) * 1.8D + Math.sin(yaw) * 0.9D;
        stepToward(tx, owner.getY(), tz, FOLLOW_STOP * 0.5D);
        faceTowards(owner.getX(), owner.getZ());
    }

    /** 朝目标点挪一步（每刻最多 {@link #FOLLOW_SPEED} 格），y 直接跟目标高度。 */
    private void stepToward(double x, double y, double z, double stop) {
        double dx = x - this.getX();
        double dz = z - this.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length <= stop || length < 1.0E-4D) {
            this.setPos(this.getX(), y, this.getZ());
            this.setDeltaMovement(Vec3.ZERO);
            return;
        }
        double step = Math.min(FOLLOW_SPEED, length - stop);
        this.setPos(this.getX() + dx / length * step, y, this.getZ() + dz / length * step);
        this.setDeltaMovement(Vec3.ZERO);
    }

    private void faceTowards(double x, double z) {
        float yaw = (float) (Mth.atan2(z - this.getZ(), x - this.getX()) * (180.0D / Math.PI)) - 90.0F;
        this.setYRot(yaw);
        this.yHeadRot = yaw;
        this.yBodyRot = yaw;
    }

    /** 附近最近的合法敌人（离主人/自己都不算太远）。 */
    @Nullable
    private LivingEntity nearestEnemy(ServerLevel level, LivingEntity owner) {
        LivingEntity best = null;
        double bestDistance = CHASE_RADIUS * CHASE_RADIUS;
        AABB area = this.getBoundingBox().inflate(CHASE_RADIUS);
        for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, area)) {
            if (candidate == this || candidate == owner || candidate.isRemoved()
                    || candidate.isDeadOrDying() || candidate instanceof Player) {
                continue;
            }
            if (!HatAbilities.isEnemy(candidate, this)) {
                continue;
            }
            double distance = this.distanceToSqr(candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * 招仇恨：敌对生物会像看见村民一样锁它。
     *
     * <p>村民的索敌优先级比玩家低，所以**已经在追玩家的不抢**，已经锁着别的目标的也不抢 ——
     * 它只是「顺路能打就打」的一个肉盾，不该把整片仇恨吸走。
     */
    private void drawAggro(ServerLevel level, LivingEntity owner) {
        if (this.tickCount % AGGRO_PERIOD != 0) {
            return;
        }
        for (Mob mob : level.getEntitiesOfClass(Mob.class, this.getBoundingBox().inflate(AGGRO_RADIUS))) {
            if (!(mob instanceof Enemy) || HatAbilities.isFriendlyTo(owner, mob)) {
                continue;
            }
            LivingEntity current = mob.getTarget();
            if (current instanceof Player) {
                continue;
            }
            if (current != null && current != this) {
                continue;
            }
            if (mob.distanceToSqr(this) > AGGRO_RADIUS * AGGRO_RADIUS || !mob.hasLineOfSight(this)) {
                continue;
            }
            mob.setTarget(this);
        }
    }

    // ------------------------------------------------------------------
    // 闪避：免疫这一下 + 等量反弹
    // ------------------------------------------------------------------

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.isRemoved() || amount <= 0.0F) {
            return false;
        }
        if (this.level().isClientSide) {
            return false;
        }
        Entity attacker = source.getEntity();
        LivingEntity owner = this.getOwner();
        // 自己人（主人和主人的盟友）打它：不扣闪避、不掉血、也不反弹 ——
        // 它是个同伴，不是训练桩，对着它挥剑不该有反馈。
        boolean friendly = attacker instanceof LivingEntity living
                && living != this
                && (owner != null && (living == owner || HatAbilities.isFriendlyTo(owner, living)));
        if (friendly) {
            return false;
        }
        if (this.dodges > 0) {
            this.dodges--;
            ServerLevel level = (ServerLevel) this.level();
            Vec3 pos = this.position().add(0.0D, this.getBbHeight() * 0.6D, 0.0D);
            level.sendParticles(ParticleTypes.CLOUD, pos.x, pos.y, pos.z,
                    10, 0.3D, 0.4D, 0.3D, 0.05D);
            level.sendParticles(ParticleTypes.END_ROD, pos.x, pos.y, pos.z,
                    6, 0.25D, 0.35D, 0.25D, 0.02D);
            long now = level.getGameTime();
            if (now - this.lastDodgeSound >= 4L) {
                this.lastDodgeSound = now;
                level.playSound(null, this.getX(), this.getY(), this.getZ(),
                        SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 0.55F, 1.6F);
            }
            // 等量反弹：原样弹回出手者。玩家不反弹 —— 玩家自己挨自己同伴的伤害没道理
            if (attacker instanceof LivingEntity living && living != this
                    && !(living instanceof Player)) {
                living.hurt(this.damageSources().thorns(this), amount);
            }
            return false;                       // 这一下完全免疫
        }
        return super.hurt(source, amount);
    }

    // ------------------------------------------------------------------
    // 生存规则
    // ------------------------------------------------------------------

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public void checkDespawn() {
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    /** 消失：撒一团白烟走人，并把自己从名册里划掉。 */
    public void disappear(ServerLevel level) {
        disappear(level, "未说明");
    }


    private boolean hatmodSelfRemoval;

    /**
     * 破防模式：外来代码不许把我 KILLED / DISCARDED（自己收场走 hatmodSelfRemoval 放行，
     * 再由注入在原版 remove/setRemoved 里的守护检查放行 token）。
     */
    @Override
    public void remove(net.minecraft.world.entity.Entity.RemovalReason reason) {
        if (HatSettings.forceBreak() && !this.hatmodSelfRemoval
                && reason != net.minecraft.world.entity.Entity.RemovalReason.KILLED
                && this.level() instanceof ServerLevel) {
            return;
        }
        HatGuard.beginApprovedRemoval();
        try {
            super.remove(reason);
        } finally {
            HatGuard.endApprovedRemoval();
        }
    }

    void disappear(ServerLevel level, String why) {
        if (this.isRemoved()) {
            return;
        }
        stopBeams(level);
        HatMod.LOGGER.info("[hatmod] 唐嘉琦退场（{}，剩余闪避 {}）", why, this.dodges);
        level.sendParticles(ParticleTypes.CLOUD,
                this.getX(), this.getY() + this.getBbHeight() * 0.5D, this.getZ(),
                16, 0.35D, 0.7D, 0.35D, 0.03D);
        rememberOwner(this.getUUID(), null);
        this.hatmodSelfRemoval = true;
        this.discard();
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.PLAYER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.PLAYER_DEATH;
    }

    @Nullable
    @Override
    protected SoundEvent getAmbientSound() {
        return null;
    }
}
