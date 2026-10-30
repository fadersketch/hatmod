package com.hatmod;

import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 「脑蚀」一轮开始时放出来的**后室 Teto**。
 *
 * <p>和「虚影」同源（都是玩家召唤的临时帮手：绕最近的敌人转圈、嘲讽、到期消失），
 * 但职责完全不同 —— 它是个**移动的负面效果场**：
 * <ul>
 *   <li>固定 30 点护甲、1024 生命（原版属性上限，再往上写会被夹掉）；</li>
 *   <li>不再逐刻掉血 —— 寿命完全由「曲子还在不在放」决定，曲子结束 / 摘帽就自己散去；</li>
 *   <li>每 20 刻给周围 12 格内**所有非友方生物**挂上原版**全部的负面效果**（各 I 级，瞬时类的除外）；</li>
 *   <li>曲子结束 / 摘帽 / 主人脱离脑蚀循环 → 自动消失。</li>
 * </ul>
 *
 * <p>渲染成一块**面向相机的平面贴图**（贴图是从动作视频里抠出来的 48 帧序列，
 * 见 {@code client/TetoRenderer}），和 friends & wine 的舞者是一个路子。
 */
public class Teto extends PathfinderMob implements OwnableEntity {

    /** 召唤者 UUID。 */
    private static final EntityDataAccessor<Optional<UUID>> DATA_OWNER =
            SynchedEntityData.defineId(Teto.class, EntityDataSerializers.OPTIONAL_UUID);

    /**
     * 绕圈半径 + 线速度。
     *
     * <p>速度按「玩家潜行」来：潜行是 1.3 格/秒 = 0.065 格/刻，所以角速度 = 线速度 / 半径。
     * 之前那版是 1 格/刻（20 格/秒），4 只一起转看着像瞬移，也容易糊成一团。
     */
    private static final double ORBIT_RADIUS = 4.0D;
    private static final double ORBIT_LINEAR_SPEED = 0.065D;
    private static final double ORBIT_SPEED = ORBIT_LINEAR_SPEED / ORBIT_RADIUS;
    /** 负面效果场半径。 */
    private static final double AURA_RADIUS = 12.0D;
    /** 多久上一次负面效果（刻）。 */
    private static final int AURA_PERIOD = 20;
    /** 上负面效果的时长（刻）：比周期长一点，不会闪断。 */
    private static final int AURA_DURATION = 100;
    /** 剩余时长还高于这个值就不补 —— 一层效果每 2 秒才重挂一次，省掉大量同步包。 */
    private static final int AURA_REFRESH_BELOW = 60;
    /** 嘲讽半径。 */
    private static final double TAUNT_RADIUS = 24.0D;
    /** 永久发光的刷新周期（刻）：每 5 秒补一层。 */
    private static final int GLOW_PERIOD = 100;
    /** 每层发光持续多久（刻）：11 秒，比周期长一倍多，永远不断。 */
    private static final int GLOW_DURATION = 220;
    /** 多久给一次仇恨（刻）：**3 秒一次**，而且只是「给一下」，不是一直按着。
     *
     * <p>中间这段时间怪物的索敌完全交还原版 AI —— 它们会重新挑最近的目标（多半又是主人），
     * 所以嘲讽只是一次次把人往回拉，而不是永久锁死。
     *
     * <p>四只**同一个刻**一起喊。刻意不错峰：错峰的话每 0.75 秒就有一只喊一嗓子，
     * 合起来又变成「一直按着」，等于没削。
     */
    private static final int TAUNT_PERIOD = 60;

    /** 原版全部「非增益、非瞬时」的效果，第一次用到时算一次。 */
    private static List<Holder<MobEffect>> negativeEffects;

    /** 4 只在圆周上均匀铺开用的相位。 */
    private float orbitPhase;
    private double orbitAngle;
    /**
     * 这一只是不是「脑蚀」那一轮召唤出来的。
     *
     * <p>{@code true} = 只活到这一轮结束（曲子停了就散）；{@code false} = 用刷怪蛋/指令放出来的，
     * 认了主人就一直跟着，不要求戴着脑蚀。
     */
    private boolean summoned;

    public Teto(EntityType<? extends Teto> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 1024.0D)
                // 固定 30 点护甲
                .add(Attributes.ARMOR, 30.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0D)
                .add(Attributes.FOLLOW_RANGE, 32.0D);
    }

    void init(LivingEntity owner, double phase) {
        setOwnerUUID(owner.getUUID());
        this.orbitPhase = (float) phase;
        // 相位只在 orbit() 里加一次；这里再赋一遍会让 4 只两两重合（0/π/2π/3π/2 翻倍成 0/π/0/π）
        this.orbitAngle = 0.0D;
        this.setNoAi(true);
        this.setPersistenceRequired();
        this.summoned = true;
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
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        UUID owner = getOwnerUUID();
        if (owner != null) {
            tag.putUUID("Owner", owner);
        }
        tag.putFloat("Phase", this.orbitPhase);
        tag.putDouble("OrbitAngle", this.orbitAngle);
        tag.putBoolean("Summoned", this.summoned);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID("Owner")) {
            setOwnerUUID(tag.getUUID("Owner"));
        }
        this.orbitPhase = tag.getFloat("Phase");
        // 接着上次的角度转：这里原来是 0，重载一次四只会整体跳回起点
        this.orbitAngle = tag.getDouble("OrbitAngle");
        this.summoned = tag.getBoolean("Summoned");
    }

    @Override
    protected void registerGoals() {
    }

    /**
     * **免疫所有负面效果**：自己就是负面效果场，被自己的场或者别的毒/凋零叠上去很荒唐。
     * 增益类照常放行 —— 万一同队的玩家想给它上个抗性。
     *
     * <p>发光（GLOWING）是原版的中性效果，不算增益，但它是自己给自己挂的「永久轮廓」，
     * 所以单独放行。
     */
    @Override
    public boolean canBeAffected(MobEffectInstance effect) {
        if (effect.getEffect().is(MobEffects.GLOWING)) {
            return true;
        }
        return effect.getEffect().value().isBeneficial();
    }

    public boolean isOwnedBy(LivingEntity owner) {
        return owner != null && owner.getUUID().equals(getOwnerUUID());
    }

    /** 是不是「脑蚀」那一轮召唤的（刷怪蛋放出来的返回 false）。 */
    boolean isSummoned() {
        return this.summoned;
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
    // 「友方单位」判定：照抄原版驯服的狼（TamableAnimal）那三条
    //
    // 别的生物 / 别的模组的索敌逻辑问「这是不是自己人」时，看的就是这三个方法：
    //   · canAttack       —— 狼不打自己的主人
    //   · getTeam         —— 狼算在主人的队伍里（名牌颜色、同队判断都跟着走）
    //   · isAlliedTo      —— 主人本人、以及主人所有的盟友，都算自己人
    // 所以 Teto 和虚影都跟驯服宠物一样，不会被主人和主人的盟友当成敌人。
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
    // 每刻：掉血、寿命、绕圈、嘲讽、负面场
    // ------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            return;
        }
        ServerLevel level = (ServerLevel) this.level();

        // 不再逐刻掉血：寿命完全由「曲子还在不在放」决定（下面那条）。
        // 血量上限是 1024（原版属性上限），所以它是一块 1024 血的移动肉盾，只可能被打死。
        LivingEntity owner = ownerEntity(level);
        if (owner == null) {
            // 刷怪蛋 / 指令放出来的没有主人：认最近的玩家当主人（相位随手给一个，免得叠在一起）
            owner = level.getNearestPlayer(this, 32.0D);
            if (owner == null) {
                disappear(level);
                return;
            }
            setOwnerUUID(owner.getUUID());
            this.orbitPhase = this.random.nextFloat() * (float) (Math.PI * 2.0D);
        }
        // 曲子召唤出来的只活到这一轮结束（曲终 / 摘帽）；刷怪蛋放出来的跟着主人，直到主人死亡
        if (this.summoned && !BrainrotCycle.isRunning(owner)) {
            disappear(level);
            return;
        }
        if (owner.isDeadOrDying()) {
            disappear(level);
            return;
        }

        this.orbit(owner);
        this.tauntNearby(level, owner);
        if (this.tickCount % AURA_PERIOD == 0) {
            this.applyAura(level, owner);
        }
        // 永久发光：黑暗阶段（尤其是 57 秒之后那段）也能一眼看见它，隔着墙也有轮廓
        if (this.tickCount % GLOW_PERIOD == 0) {
            this.addEffect(new MobEffectInstance(MobEffects.GLOWING, GLOW_DURATION, 0, false, false, false));
        }
    }

    /** 永远绕**主人**转（不再去追最近的敌人）。 */
    private void orbit(LivingEntity owner) {
        Vec3 center = new Vec3(owner.getX(), owner.getY() + owner.getBbHeight() * 0.5D, owner.getZ());
        // 方向只由这一行的正号决定：角度单调递增，永远不会反着转。
        // 角度本身会写进存档（见 addAdditionalSaveData），所以区块重载、跨维度、
        // 退出重进都不会把它抹回 0 —— 一旦抹回 0，四只就会「跳回起点」，
        // 看上去就像整圈突然反了个方向。
        //
        // 「No too late」弹字期间不推进角度：停在圆周上的当前位置，只跟着主人平移
        // （与玩家相对静止）。上下微浮也挂在同一个角度上，所以会一起定住；文字一淡出就接着转。
        if (!(this.summoned && BrainrotCycle.tetoOrbitFrozen(owner))) {
            this.orbitAngle += ORBIT_SPEED;
        }
        double angle = this.orbitAngle + this.orbitPhase;
        double x = center.x + Math.cos(angle) * ORBIT_RADIUS;
        double z = center.z + Math.sin(angle) * ORBIT_RADIUS;
        // 上下微浮也挂在同一个角度上：不要再用 tickCount，否则 tickCount 一旦重新计数
        // （客户端重新加进世界时会从 0 开始）画面上就会突然弹一下。
        double y = center.y - this.getBbHeight() * 0.5D
                + Math.sin(this.orbitAngle * 9.2D + this.orbitPhase) * 0.2D;
        this.setPos(x, y, z);
        this.setDeltaMovement(Vec3.ZERO);
        float yaw = (float) (net.minecraft.util.Mth.atan2(center.z - z, center.x - x) * (180.0D / Math.PI)) - 90.0F;
        this.setYRot(yaw);
        this.yHeadRot = yaw;
        this.yBodyRot = yaw;
    }

    /**
     * 嘲讽：**每隔 {@link #TAUNT_PERIOD} 刻**把 {@link #TAUNT_RADIUS} 格内的敌对生物拉到自己身上一次。
     *
     * <p>是脉冲式的：喊完这一下，接下来 3 秒完全不干预，索敌交还原版 AI ——
     * 怪物会重新去挑最近的目标（通常又回到主人身上）。所以它只是时不时把人往回拉，
     * 不再像之前那样每 5 刻按一次、等于永久锁死。
     *
     * <p>脉冲那一刻不管它现在在打谁（打主人、打宠物、打别的 Teto 都算）都拉过来，
     * 只要不是「已经在打我」。既不是敌意生物、也没在打人的被动生物一律放过。
     */
    private void tauntNearby(ServerLevel level, LivingEntity owner) {
        if (this.tickCount % TAUNT_PERIOD != 0) {
            return;
        }
        AABB area = this.getBoundingBox().inflate(TAUNT_RADIUS);
        for (Entity entity : level.getEntities(this, area)) {
            if (!(entity instanceof Mob mob) || mob == this
                    || mob instanceof Teto || mob instanceof HatSummon) {
                continue;
            }
            if (HatAbilities.isFriendlyTo(owner, mob)) {
                continue;
            }
            LivingEntity current = mob.getTarget();
            if (current == this) {
                continue;
            }
            boolean hostile = mob instanceof Enemy
                    || current != null
                    || (mob instanceof NeutralMob neutral && neutral.isAngry());
            if (!hostile) {
                continue;
            }
            mob.setTarget(this);
        }
    }

    /**
     * 给周围**真正的敌人**挂上原版全部负面效果（各 I 级）。
     *
     * <p>判据是 {@link HatAbilities#isEnemy}，不是「非友方」—— 所以主人本人、宠物、女仆、
     * 同队玩家、别的玩家，以及村民/动物这类没有敌意的单位**一律不碰**；
     * 只有敌意生物、发怒中的中立生物、正在打人或最近打过人的才会被挂上黑暗/中毒/凋零这些。
     */
    private void applyAura(ServerLevel level, LivingEntity owner) {
        List<Holder<MobEffect>> effects = negativeEffects();
        if (effects.isEmpty()) {
            return;
        }
        AABB area = this.getBoundingBox().inflate(AURA_RADIUS);
        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, area)) {
            if (target == this || target.isRemoved() || target.isDeadOrDying()) {
                continue;
            }
            // 玩家永远不吃负面场：isEnemy 本来就把玩家挡在外面，这里再拦一道死门，
            // 以后谁放宽了 isEnemy 也不会把队友/路人卷进来。
            if (target instanceof net.minecraft.world.entity.player.Player) {
                continue;
            }
            if (!HatAbilities.isEnemy(target, owner)) {
                continue;
            }
            for (Holder<MobEffect> effect : effects) {
                MobEffectInstance current = target.getEffect(effect);
                if (current != null && (current.getAmplifier() > 0
                        || current.getDuration() > AURA_REFRESH_BELOW)) {
                    // 已经有更强的、或者还够久，就不用再挂一次
                    continue;
                }
                MobEffectInstance instance =
                        new MobEffectInstance(effect, AURA_DURATION, 0, false, false, true);
                if (!target.addEffect(instance)) {
                    // 免控的 Boss（凋灵、监守者、各种模组 Boss）会被 canBeAffected 挡掉，
                    // 这里强行挂上 —— 这顶帽子的负面场本来就该盖住所有人。
                    target.forceAddEffect(instance, null);
                }
            }
        }
    }

    /** 原版所有「非增益、非瞬时」效果，只算一次。 */
    private static List<Holder<MobEffect>> negativeEffects() {
        if (negativeEffects == null) {
            List<Holder<MobEffect>> found = new ArrayList<>();
            for (Holder<MobEffect> holder : BuiltInRegistries.MOB_EFFECT.holders().toList()) {
                MobEffect effect = holder.value();
                if (!effect.isBeneficial() && !effect.isInstantenous()) {
                    found.add(holder);
                }
            }
            negativeEffects = found;
        }
        return negativeEffects;
    }

    /** 消失：撒一团黑烟走人。 */
    public void disappear(ServerLevel level) {
        level.sendParticles(ParticleTypes.SQUID_INK,
                this.getX(), this.getY() + this.getBbHeight() * 0.5D, this.getZ(),
                16, 0.4D, 0.8D, 0.4D, 0.03D);
        this.discard();
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

    @Override
    public void die(DamageSource source) {
        if (this.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.SQUID_INK,
                    this.getX(), this.getY() + this.getBbHeight() * 0.5D, this.getZ(),
                    16, 0.4D, 0.8D, 0.4D, 0.03D);
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
