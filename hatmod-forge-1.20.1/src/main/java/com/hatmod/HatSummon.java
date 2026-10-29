package com.hatmod;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/**
 * 「全」帽在蓄力完成那一刻召唤出来的**虚影**（临时分身）。
 *
 * <p>它<b>不戴帽子</b>，所以不会被 {@link HatAbilities} 的状态机当成戴帽者 —— 也就不会蓄力、
 * 不会发射光柱。它做的事只有三件：
 * <ol>
 *   <li>绕着最近的敌人<b>匀速圆周运动</b>（线速度 = 玩家潜行速度）（{@link #ORBIT_SPEED} 弧度/刻）；</li>
 *   <li>对周围的敌对生物<b>嘲讽</b>，把它们的攻击目标拉到自己身上；</li>
 *   <li>戴帽者的闪避次数用完后再挨打时，<b>和它交换位置</b>，由它吃掉那一击
 *       （见 {@code HatAbilities.onIncomingDamage}）。</li>
 * </ol>
 *
 * <p>寿命由 {@code life} 决定（召唤时传入，等于主人这一轮的照射刻数），到点自己 {@code discard()}。
 * 位置全部由服务端每刻直接写入（{@code noPhysics} + {@code noAi}），不走 AI 寻路。
 *
 * <p>做成独立顶层类的原因见 {@link HatState} 的注释（延迟类加载 + 内存优化模组）。
 */
public class HatSummon extends PathfinderMob implements OwnableEntity {

    /** 召唤者 UUID：客户端据此判断归属，服务端据此判定「自己人」。 */
    private static final EntityDataAccessor<Optional<UUID>> DATA_OWNER =
            SynchedEntityData.defineId(HatSummon.class, EntityDataSerializers.OPTIONAL_UUID);

    /** 绕圈半径（格）。 */
    private static final double ORBIT_RADIUS = 4.0D;
    /**
     * 绕圈线速度 = 玩家潜行速度（1.3 格/秒 = 0.065 格/刻）。
     *
     * <p>角速度由它除以半径得来。以前是 0.4 弧度/刻（≈ 32 格/秒），4 个一起转像在瞬移。
     */
    private static final double ORBIT_LINEAR_SPEED = 0.065D;
    private static final double ORBIT_SPEED = ORBIT_LINEAR_SPEED / ORBIT_RADIUS;
    /** 嘲讽半径（格）。 */
    private static final double TAUNT_RADIUS = 20.0D;
    /** 多久重新挑一次「绕谁」，免得目标死了还绕着空气转。 */
    private static final int RETARGET_INTERVAL = 10;

    /** 4 个虚影在圆周上均匀铺开用的相位。 */
    private float orbitPhase;
    /** 剩余寿命（刻）。 */
    private int life;
    /** 当前圆周角度。 */
    private double orbitAngle;
    /** 当前绕的敌人；失效时每 {@link #RETARGET_INTERVAL} 刻重挑。 */
    @Nullable
    private LivingEntity orbitTarget;

    public HatSummon(EntityType<? extends HatSummon> type, Level level) {
        super(type, level);
        // 绕圈时不要被方块、其它实体推来推去，穿墙也无所谓（它本来就是影子）
        this.noPhysics = true;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0D)
                .add(Attributes.FOLLOW_RANGE, 32.0D);
    }

    /** 召唤初始化：归属、寿命、初始相位。 */
    void init(LivingEntity owner, int lifeTicks, double phase) {
        setOwnerUUID(owner.getUUID());
        this.life = Math.max(1, lifeTicks);
        this.orbitPhase = (float) phase;
        // 相位只在 orbit() 里加一次；这里再赋一遍会让 4 个虚影两两重合
        this.orbitAngle = 0.0D;
        this.setNoAi(true);
        this.setPersistenceRequired();
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_OWNER, Optional.empty());
    }

    @Nullable
    @Override
    public UUID getOwnerUUID() {
        return this.entityData.get(DATA_OWNER).orElse(null);
    }

    public void setOwnerUUID(@Nullable UUID uuid) {
        this.entityData.set(DATA_OWNER, Optional.ofNullable(uuid));
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        UUID owner = getOwnerUUID();
        if (owner != null) {
            tag.putUUID("Owner", owner);
        }
        tag.putInt("Life", this.life);
        tag.putFloat("Phase", this.orbitPhase);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID("Owner")) {
            setOwnerUUID(tag.getUUID("Owner"));
        }
        this.life = tag.getInt("Life");
        this.orbitPhase = tag.getFloat("Phase");
        this.orbitAngle = 0.0D;
    }

    @Override
    protected void registerGoals() {
    }

    // ------------------------------------------------------------------
    // 每刻：寿命、绕圈、嘲讽
    // ------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            return;
        }
        if (this.life > 0) {
            this.life--;
            if (this.life <= 0) {
                // 到期消失
                this.discard();
                return;
            }
        }
        this.tauntNearby();
        this.orbit();
    }

    /**
     * 绕最近的敌人高速转圈；附近没有敌人就绕召唤者，连召唤者都不在线就原地不动。
     */
    private void orbit() {
        ServerLevel level = (ServerLevel) this.level();
        if (this.orbitTarget == null || this.orbitTarget.isRemoved() || !this.orbitTarget.isAlive()
                || this.tickCount % RETARGET_INTERVAL == 0) {
            this.orbitTarget = HatAbilities.findNearestEnemy(this);
        }

        Vec3 center;
        if (this.orbitTarget != null) {
            center = new Vec3(this.orbitTarget.getX(),
                    this.orbitTarget.getY() + this.orbitTarget.getBbHeight() * 0.5D,
                    this.orbitTarget.getZ());
        } else {
            LivingEntity owner = ownerEntity(level);
            if (owner == null) {
                this.setDeltaMovement(Vec3.ZERO);
                return;
            }
            center = new Vec3(owner.getX(), owner.getY() + owner.getBbHeight() * 0.5D, owner.getZ());
        }

        this.orbitAngle += ORBIT_SPEED;
        double angle = this.orbitAngle + this.orbitPhase;
        double x = center.x + Math.cos(angle) * ORBIT_RADIUS;
        double z = center.z + Math.sin(angle) * ORBIT_RADIUS;
        // 上下浮一点，看起来像飘着的影子而不是在走地面
        double y = center.y - this.getBbHeight() * 0.5D + Math.sin(this.tickCount * 0.25D + this.orbitPhase) * 0.15D;
        this.setPos(x, y, z);
        this.setDeltaMovement(Vec3.ZERO);
        // 始终面朝圆心
        float yaw = (float) (Mth.atan2(center.z - z, center.x - x) * (180.0D / Math.PI)) - 90.0F;
        this.setYRot(yaw);
        this.yHeadRot = yaw;
        this.yBodyRot = yaw;
    }

    /**
     * 嘲讽：把周围敌对生物的攻击目标拉到自己身上。
     *
     * <p>只动「本来就敌对」的生物（{@code Enemy} 或者已经有目标的），并且跳过自己的队友——
     * 否则会把驯服的宠物、女仆也一起拉过来打自己的虚影。
     */
    private void tauntNearby() {
        if (this.tickCount % 10 != 0) {
            return;
        }
        AABB area = this.getBoundingBox().inflate(TAUNT_RADIUS);
        for (Entity entity : this.level().getEntities(this, area)) {
            if (!(entity instanceof Mob mob) || mob instanceof HatSummon) {
                continue;
            }
            LivingEntity current = mob.getTarget();
            boolean hostile = mob instanceof Enemy || current != null;
            if (!hostile) {
                continue;
            }
            if (current instanceof HatSummon) {
                continue;
            }
            LivingEntity owner = ownerEntity((ServerLevel) this.level());
            if (owner != null && HatAbilities.isFriendlyTo(owner, mob)) {
                continue;
            }
            mob.setTarget(this);
        }
    }

    @Nullable
    private LivingEntity ownerEntity(ServerLevel level) {
        UUID id = getOwnerUUID();
        if (id == null) {
            return null;
        }
        Entity entity = level.getEntity(id);
        return entity instanceof LivingEntity living && living.isAlive() ? living : null;
    }

    /** 是不是 {@code owner} 的虚影。 */
    public boolean isOwnedBy(LivingEntity owner) {
        return owner != null && owner.getUUID().equals(getOwnerUUID());
    }

    // ------------------------------------------------------------------
    // 「友方单位」判定：照抄原版驯服的狼（TamableAnimal）那三条
    // 虚影和 Teto 一样，都是主人的临时宠物：主人打不到它，
    // 它和主人的盟友互相算自己人（别的模组的索敌逻辑问的就是这些方法）。
    // ------------------------------------------------------------------

    @Override
    public boolean canAttack(LivingEntity target) {
        return this.isOwnedBy(target) ? false : super.canAttack(target);
    }

    @Override
    public Team getTeam() {
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
    // 生存规则：不自然消失、不掉落
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

    /** 影子死掉时撒一团黑烟，不做别的事。 */
    @Override
    public void die(DamageSource source) {
        if (this.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.SQUID_INK,
                    this.getX(), this.getY() + this.getBbHeight() * 0.5D, this.getZ(),
                    12, 0.3D, 0.5D, 0.3D, 0.02D);
        }
        super.die(source);
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.GENERIC_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.GENERIC_DEATH;
    }

    @Nullable
    @Override
    protected SoundEvent getAmbientSound() {
        return null;
    }
}
