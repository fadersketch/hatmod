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
 * <p>既然它要替主人挡刀，就不能一碰就碎：每次最多掉 20% 最大生命（见
 * {@link #MAX_DAMAGE_PER_HIT}），并且把吃下去的那份**反伤**给出手的生物（见
 * {@link #hurt(DamageSource, float)}）。
 *
 * <p>另外它自己也会**发一道光柱**（{@link #tickBeam(ServerLevel)}）：伤害和正主同源 ——
 * 固定帧伤读「全」这一轮路线的那份配置，百分比伤害也照正主那样强扣。唯一的区别是
 * **不控人、不击退**（光柱照到只掉血），这是分身和正主刻意的分工。
 *
 * <p>寿命由 {@code life} 决定（召唤时传入，固定 10 秒），到点自己 {@code discard()}。
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

    /** 单次最多扣多少血：最大生命的 20%（20 血 → 4 点），再高的爆发也得打五下。 */
    private static final float MAX_DAMAGE_PER_HIT = 4.0F;
    /** 反伤倍率：按**原始伤害值**（不是限伤后的 4 点）算，还给真正出手的生物。 */
    private static final float REFLECT_SCALE = 1.5F;
    /** 反伤下限：小刮擦也至少反 1 点。 */
    private static final float MIN_REFLECT = 1.0F;
    /** 反伤上限：一次最多 80 点（40 颗心）。 */
    private static final float MAX_REFLECT = 80.0F;

    /** 分身一次只打一道（客户端按这个道数画）。 */
    private static final int SHADE_BEAM_COUNT = 1;

    /** 4 个虚影在圆周上均匀铺开用的相位。 */
    private float orbitPhase;
    /** 剩余寿命（刻）。 */
    private int life;
    /** 当前圆周角度。 */
    private double orbitAngle;
    /** 当前绕的敌人；失效时每 {@link #RETARGET_INTERVAL} 刻重挑。 */
    @Nullable
    private LivingEntity orbitTarget;

    /** 光柱此刻锁的是谁（只用于「目标变了才发包」，避免每刻刷网络）。 */
    @Nullable
    private LivingEntity beamTarget;

    /**
     * 召唤时主人这一轮走的是哪条路线（黑/白/红）。
     *
     * <p>分身的帧伤和正主共用一份配置（{@code HatSettings.routeDamage}），所以这条路线必须记住：
     * 掉进 NBT 之后跨存档 / 区块重载也不会丢。
     */
    private HatType route = HatType.WHITE;

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

    /** 召唤初始化：归属、寿命、初始相位、这一轮跟着走的路线。 */
    void init(LivingEntity owner, int lifeTicks, double phase, HatType route) {
        setOwnerUUID(owner.getUUID());
        // 路线决定帧伤（黑 1.2 / 白 1.0 / 红 1.5，正主读的是同一份配置）
        this.route = route == null ? HatType.WHITE : route;
        // 真正的「全」帽子（基础款、无附魔）：不再是画上去的假帽子 ——
        // 它因此能吃帽子的护甲，也和其他戴帽者一样被当帽子渲染。
        this.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,
                new net.minecraft.world.item.ItemStack(HatItems.ALL_HAT.get()));
        this.setDropChance(net.minecraft.world.entity.EquipmentSlot.HEAD, 0.0F);
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
        tag.putString("Route", this.route.name());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID("Owner")) {
            setOwnerUUID(tag.getUUID("Owner"));
        }
        this.life = tag.getInt("Life");
        this.orbitPhase = tag.getFloat("Phase");
        try {
            this.route = HatType.valueOf(tag.getString("Route"));
        } catch (IllegalArgumentException ignored) {
            // 老存档里没有这条（或手改坏了）：退回白路线的帧伤
            this.route = HatType.WHITE;
        }
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
        // 登记进「删不掉」名册：破防模式下被外人删掉会被拉回来
        HatAbilities.watchSummon(this);
        if (this.life > 0) {
            this.life--;
            if (this.life <= 0) {
                // 到期消失：先把光柱收掉，别给客户端留一条幽灵光
                this.stopBeam();
                this.hatmodDismiss();
                return;
            }
        }
        this.tauntNearby();
        this.orbit();
        this.tickBeam((ServerLevel) this.level());
    }

    /**
     * 分身的光柱：锁住它绕着转的那个人，每刻结算一次。
     *
     * <p>伤害完全交给 {@link HatAbilities#damageShadeBeam}（和正主同一套：路线帧伤 +
     * 百分比强扣），所以主人把「全」的帧伤调高、或者换了一条路线，分身的输出也会跟着变。
     *
     * <p>唯一的区别是**不控人**（不调 {@code HatAbilities.hold}）、**不击退**
     * （打之前存动量、打完还原）—— 光柱照到只掉血。
     */
    private void tickBeam(ServerLevel level) {
        LivingEntity target = this.orbitTarget;
        if (target == null || target.isRemoved() || !target.isAlive()
                || !HatAbilities.isEnemy(target, this)) {
            target = HatAbilities.findNearestEnemy(this);
        }
        if (target == null) {
            this.stopBeam();
            return;
        }
        if (target != this.beamTarget) {
            this.beamTarget = target;
            HatNetwork.sendBeamTarget(this, true, new int[]{target.getId()}, SHADE_BEAM_COUNT);
        }
        HatAbilities.damageShadeBeam(level, this, target);
    }

    /** 这一轮跟着走的路线（帧伤按它取，见 {@link HatAbilities#damageShadeBeam}）。 */
    HatType route() {
        return this.route;
    }

    /** 收掉这条光柱的同步（目标没了 / 自己到期 / 死亡）。本来就没在照就什么也不发。 */
    private void stopBeam() {
        if (this.beamTarget == null) {
            return;
        }
        this.beamTarget = null;
        HatNetwork.sendBeamTarget(this, false, new int[0], 0);
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
    LivingEntity ownerEntity(ServerLevel level) {
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

    /**
     * 受伤：**限伤 + 反伤**。
     *
     * <p>虚影只有 20 点血，而后期模组单发动辄几十点 —— 不设上限的话一发就没了，
     * 「替你挡一下」这个设计等于不存在。所以每次最多扣 {@link #MAX_DAMAGE_PER_HIT}
     * （最大生命的 20%），至少能挨五下。
     *
     * <p>反伤按**原始伤害**（限伤之前那个数）乘 {@link #REFLECT_SCALE}、最多
     * {@link #MAX_REFLECT} 点，用 {@code setHealth} 直接扣给真正出手的生物
     * （箭矢、火球算在射手头上）—— 不吃护甲、无敌帧，也不吃 Boss 的伤害桶。
     *
     * <p>不反回去的情形：伤害本身就是 {@code minecraft:thorns}（否则荆棘附魔会互相弹）、
     * 自己人（主人 / 同队 / 其它虚影）。
     */
    /**
     * 破防模式下「打不过就加入」：外部想删我们一律不认（pig2 那招），自己的退场走内部开关。
     *
     * <p>别的模组的 killEntity / discard / 实体表清理最终都是调 {@code setRemoved}，
     * 把这里挡住它们就删不掉。正常死亡（KILLED）不受影响。
     */
    private boolean hatmodSelfRemoval;
    /** 把它打死的那一位：尸体被删前的那一秒用来反噬。 */
    private Entity hatmodKiller;

    @Override
    public void remove(net.minecraft.world.entity.Entity.RemovalReason reason) {
        if (HatSettings.forceBreak() && !this.hatmodSelfRemoval
                && reason != net.minecraft.world.entity.Entity.RemovalReason.KILLED
                && this.level() instanceof ServerLevel) {
            return;
        }
        super.remove(reason);
    }

    /**
     * 帽子本体自己收掉（到期 / 卸下 / 换帽）的那一刻。
     *
     * <p>先把内部退场标记打上（这样 {@link #remove} 的防盗门不会拦自己），
     * 再放一发「尾杀」—— 周围一圈外人陪着一起走，完全照 pig2 的 L4。
     */
    public void hatmodDismiss() {
        if (this.hatmodSelfRemoval) {
            return;
        }
        this.hatmodSelfRemoval = true;
        HatAbilities.forgetSummon(this);
        HatAbilities.retaliate(this, this.hatmodKiller);
        this.discard();
    }

    /**
     * pig2 那招：外部想把我们写死（{@code setHealth(0)}）一律不认。
     *
     * <p>很多 Boss 不走 {@code hurt}，直接 {@code setHealth} 清血；只挡伤害是挡不住的。
     */
    @Override
    public void setHealth(float health) {
        if (HatSettings.forceBreak() && !this.hatmodSelfRemoval && health < this.getHealth()) {
            return;
        }
        super.setHealth(health);
    }

    /** 这一下是不是玩家 / 自己人打的（破防模式下对它们完全免疫）。 */
    private boolean hatmodFriendlyStrike(DamageSource source) {
        Entity attacker = source.getEntity();
        if (attacker instanceof net.minecraft.world.entity.player.Player) {
            return true;
        }
        if (attacker instanceof HatSummon || attacker instanceof Teto) {
            return true;
        }
        LivingEntity owner = this.getOwner();
        return owner != null && attacker instanceof LivingEntity living
                && HatAbilities.isFriendlyTo(owner, living);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.isInvulnerableTo(source)) {
            return false;
        }
        // 破防模式 = pig2 级：完全打不动（尾杀放在「本体自己收掉」的那一刻）
        if (HatSettings.forceBreak()) {
            return false;
        }
        float capped = Math.min(amount, MAX_DAMAGE_PER_HIT);
        boolean hurt = super.hurt(source, capped);
        if (!hurt || source.is(net.minecraft.world.damagesource.DamageTypes.THORNS)) {
            return hurt;
        }
        Entity direct = source.getDirectEntity();
        Entity attacker = source.getEntity();
        LivingEntity shooter = direct instanceof LivingEntity living ? living
                : attacker instanceof LivingEntity living ? living : null;
        if (shooter == null || shooter == this || shooter instanceof HatSummon) {
            return hurt;
        }
        LivingEntity owner = this.getOwner();
        if (owner != null && HatAbilities.isFriendlyTo(owner, shooter)) {
            return hurt;
        }
        // 按**原始伤害**算（不是上面限伤后的 capped），直接用 setHealth 扣血：
        // 不吃护甲、不吃无敌帧、也不吃灾变那类 Boss 挂在 hurt() 里的「单次伤害上限」。
        float reflect = Math.min(MAX_REFLECT, Math.max(MIN_REFLECT, amount * REFLECT_SCALE));
        float health = shooter.getHealth();
        if (health - reflect <= 0.0F) {
            shooter.setHealth(0.0F);
            // setHealth 只改血量、不触发死亡（die() 是 protected），这里补一刀 genericKill
            // 走一次原版死亡流程，让掉落 / 经验 / 击杀统计照常结算
            shooter.hurt(this.damageSources().genericKill(), Float.MAX_VALUE);
        } else {
            shooter.setHealth(health - reflect);
        }
        return hurt;
    }

    /** 影子死掉时撒一团黑烟，不做别的事。 */
    @Override
    public void die(DamageSource source) {
        this.stopBeam();
        if (this.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.SQUID_INK,
                    this.getX(), this.getY() + this.getBbHeight() * 0.5D, this.getZ(),
                    12, 0.3D, 0.5D, 0.3D, 0.02D);
        }
        this.hatmodKiller = source.getEntity();
        super.die(source);
        // 不做 20 刻的「倒地」动画：虚影是一张面片（和 Teto 一样），套史蒂夫那套死亡姿态
        // 只会看到它歪下去不动。直接跳到最后一刻，下一 tick 就 remove。
        this.deathTime = 19;
    }

    /**
     * 尸体即将被删除的那一秒（{@code deathTime == 19}，下一 tick 就 remove）：
     * 破防模式下放 pig2 同款反噬。
     */
    @Override
    protected void tickDeath() {
        if (this.deathTime == 19) {
            HatAbilities.retaliate(this, this.hatmodKiller);
        }
        super.tickDeath();
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
