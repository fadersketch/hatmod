package com.hatmod;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
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
import net.minecraft.world.scores.Team;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * 见 {@code client/TetoRenderer}），和 friends &amp; wine 的舞者是一个路子。
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

    /** 轨道角速度（弧度/刻）：给召唤时算「停在正前方」的初始相位用。 */
    static double orbitSpeed() {
        return ORBIT_SPEED;
    }
    /** 负面效果场半径。 */
    private static final double AURA_RADIUS = 12.0D;
    /** 多久上一次负面效果（刻）：0.5 秒一轮，比原来快一倍。 */
    private static final int AURA_PERIOD = 10;
    /** 负面场开场先挂几条。 */
    private static final int AURA_EFFECTS_BASE = 5;
    /** 之后每多少刻多挂一条（2 秒）：一轮下来会从 5 条一路涨到整张表挂满。 */
    private static final int AURA_EFFECTS_EVERY = 40;
    /** 上负面效果的时长（刻）：比周期长得多，不会闪断；同时靠它决定「要不要补一次」。 */
    private static final int AURA_DURATION = 100;
    /** 剩余时长还高于这个值就不补 —— 80/100 意味着每轮都续满，效果不会出现空档。 */
    private static final int AURA_REFRESH_BELOW = 80;
    /** 每个目标每轮最多冒几团颜色粒子，免得一次刷出几百个。 */
    private static final int AURA_PUFFS_PER_PULSE = 3;
    /** 每挂上这么多条负面效果，就给友方一次随机增益。 */
    private static final int AURA_BUFF_EVERY = 15;
    /** 增益持续多久（刻）：10 秒。 */
    private static final int AURA_BUFF_DURATION = 200;
    /** 两次增益之间至少隔多久（刻）：15 秒 —— **四只 Teto 共用这一份冷却**。 */
    private static final int AURA_BUFF_COOLDOWN = 300;
    /** 嘲讽半径。 */
    private static final double TAUNT_RADIUS = 24.0D;
    /** 黑暗阶段发光的刷新周期（刻）：每 1 秒补一层，黑暗一来立刻亮起来。 */
    private static final int GLOW_PERIOD = 20;
    /** 每层发光持续多久（刻）：比周期略长，黑暗期间不断档、黑暗一结束就自己掉。 */
    private static final int GLOW_DURATION = 30;
    /**
     * 多久给一次仇恨（刻）：**3 秒一次**，而且只是「给一下」，不是一直按着。
     *
     * <p>中间这段时间怪物的索敌完全交还原版 AI —— 它们会重新挑最近的目标（多半又是主人），
     * 所以嘲讽只是一次次把人往回拉，而不是永久锁死。
     *
     * <p>四只**同一个刻**一起喊。刻意不错峰：错峰的话每 0.75 秒就有一只喊一嗓子，
     * 合起来又变成「一直按着」，等于没削。
     */
    private static final int TAUNT_PERIOD = 60;

    /** 原版全部「非增益、非瞬时」的效果，第一次用到时算一次。 */
    private static List<MobEffect> negativeEffects;

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
    /** 「这一轮已经结束了」连续出现了几刻（宽限期内不散场，见 tick）。 */
    private int hatmodOrphanTicks;
    /** 宽限多少刻才真的散场（3 秒）。 */
    private static final int ORPHAN_GRACE_TICKS = 60;

    public Teto(EntityType<? extends Teto> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                // 310 血 / 31 甲：31 点护甲是满减伤档（约 80%），
                // 所以四只合计的有效生命约 6200 —— 打得掉，但得真打一阵
                .add(Attributes.MAX_HEALTH, 310.0D)
                .add(Attributes.ARMOR, 31.0D)
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
        if (effect.getEffect() == MobEffects.GLOWING) {
            return true;
        }
        return effect.getEffect().isBeneficial();
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
    // 每刻：掉血、寿命、绕圈、嘲讽、负面场
    // ------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            return;
        }
        // 登记进「删不掉」名册：破防模式下被外人删掉会被拉回来
        HatAbilities.watchSummon(this);
        // 再把实体管理器的回调包一层：绕过 remove()/setRemoved() 的删除也要拦得住
        hatmodGuardLevelCallback();
        ServerLevel level = (ServerLevel) this.level();
        // 四只 Teto 各 tick 一次，tickTrails 内部按服务器刻去重，每刻只推进一次
        tickTrails(level);

        // 不再逐刻掉血：寿命完全由「曲子还在不在放」决定（下面那条）。
        // 血量上限是 1024（原版属性上限），所以它是一块 1024 血的移动肉盾，只可能被打死。
        LivingEntity owner = ownerEntity(level);
        if (owner == null) {
            if (this.summoned) {
                // 召唤出来的 Teto 一旦找不到主人（下线 / 换维度 / 重进存档）就自己散掉。
                // 以前这里会「改认最近的玩家」，结果旧 Teto 挂到别人身上、越积越多。
                disappear(level, "主人已不在这个世界（下线/换维度）");
                return;
            }
            // 刷怪蛋 / 指令放出来的没有主人：认最近的玩家当主人（相位随手给一个，免得叠在一起）
            owner = level.getNearestPlayer(this, 32.0D);
            if (owner == null) {
                disappear(level, "32 格内没有可跟随的玩家");
                return;
            }
            setOwnerUUID(owner.getUUID());
            this.orbitPhase = this.random.nextFloat() * (float) (Math.PI * 2.0D);
        }
        // 曲子召唤出来的只活到这一轮结束（曲终 / 摘帽）；刷怪蛋放出来的跟着主人，直到主人死亡。
        // 但**不能一断就走**：链路的 `AGES` 在「本轮结束 / 下一轮刚开」之间会有一拍是空的，
        // 以前就是这一拍把所有 Teto 一起收掉，`checkTetos` 数到 0 判定团灭、于是重唱。
        // 这里给 3 秒宽限：连续 60 刻都不属于任何一轮，才真的散场。
        if (this.summoned && !BrainrotCycle.isRunning(owner)) {
            if (++this.hatmodOrphanTicks >= ORPHAN_GRACE_TICKS) {
                disappear(level, "连续 3 秒不属于任何一轮（链路早就收场了）");
            }
            return;
        }
        this.hatmodOrphanTicks = 0;
        if (owner.isDeadOrDying()) {
            disappear(level, "主人已经死亡");
            return;
        }

        this.orbit(owner);
        this.tauntNearby(level, owner);
        // 「Nope, too late」那一段画面上只该有那三个词和 Teto 本人：
        // 负面场、光环增益、打拍烟花全部停手 —— 火花和粒子都会糊住那一下小动作。
        boolean calm = BrainrotBeats.speechAnchor(this.tickCount) >= 0;
        if (!calm && this.tickCount % AURA_PERIOD == 0) {
            this.applyAura(level, owner);
            this.maybeBuffOwner(level, owner);
            this.maybeComboBuffOwner(level, owner);
        }
        // 只在黑暗阶段发光：那几段什么都看不见，Teto 得有个轮廓；亮着的时候不发光
        if (this.tickCount % GLOW_PERIOD == 0 && BrainrotCycle.isDarkPhase(owner)) {
            this.addEffect(new MobEffectInstance(MobEffects.GLOWING, GLOW_DURATION, 0, false, false, false));
        }
    }

    // ------------------------------------------------------------------
    // 效果连线：Teto 先射出一道火花，**飞到目标身上才真正挂效果**
    // ------------------------------------------------------------------

    /** 同一只 Teto 两次出手之间至少隔多少刻（四只各算各的）。 */
    private static final int TRAIL_GAP = 6;
    /** 火花飞多久（刻）：到点才落地，落地就消失，不留长尾。 */
    private static final int TRAIL_LIFE = 5;
    /** 打敌人：红色。 */
    private static final int TRAIL_RED = 0xE0261E;
    /** 给友方（主人）：绿色。 */
    private static final int TRAIL_GREEN = 0x2FD35A;
    /** 正在飞的火花。 */
    private static final List<Trail> TRAILS = new ArrayList<>();
    /** 这只 Teto 上次出手的刻 —— **每只独立计算**，不跟其它 Teto 抢名额。 */
    private long lastTrailTick = Long.MIN_VALUE / 2;
    /** 上次推进火花的服务器刻：四只 Teto 各 tick 一次，靠它保证每刻只推进一次。 */
    private static long lastTrailUpdateTick = Long.MIN_VALUE / 2;

    /** 一道已经射出、还在飞的「效果」：落到目标身上才结算。 */
    private static final class Trail {
        private final LivingEntity target;
        private final List<MobEffect> effects;
        private final int color;
        private final boolean buff;
        private final Vec3 from;
        private final Vec3 to;
        private int age;

        private Trail(LivingEntity target, List<MobEffect> effects, int color,
                      boolean buff, Vec3 from, Vec3 to) {
            this.target = target;
            this.effects = effects;
            this.color = color;
            this.buff = buff;
            this.from = from;
            this.to = to;
        }
    }

    /** 冷却好了就占掉这次出手名额（每只 Teto 独立算）。 */
    private boolean takeTrailSlot(ServerLevel level) {
        long now = level.getGameTime();
        if (now < this.lastTrailTick + TRAIL_GAP) {
            return false;
        }
        this.lastTrailTick = now;
        return true;
    }

    /** 射出一道火花：效果先挂在火花上，飞行途中不生效。 */
    private void fireTrail(LivingEntity target, List<MobEffect> effects, int color, boolean buff) {
        TRAILS.add(new Trail(target, effects, color, buff,
                this.getEyePosition(), target.getEyePosition()));
    }

    /**
     * 每刻推进所有火花：一路撒很短命的火星 + 同色粉尘，飞到才结算。
     *
     * <p>用 {@code ELECTRIC_SPARK} 而不是 {@code FIREWORK}：后者单颗存活 1 秒以上，
     * 连发几道就像黏在空气里；火花只活 0.2–0.4 秒，落地即灭。
     */
    private static void tickTrails(ServerLevel level) {
        if (TRAILS.isEmpty()) {
            return;
        }
        long now = level.getGameTime();
        if (now == lastTrailUpdateTick) {
            return;
        }
        lastTrailUpdateTick = now;
        java.util.Iterator<Trail> iterator = TRAILS.iterator();
        while (iterator.hasNext()) {
            Trail trail = iterator.next();
            if (trail.target.isRemoved() || trail.target.isDeadOrDying()) {
                iterator.remove();
                continue;
            }
            if (trail.age >= TRAIL_LIFE) {
                // 落地这一刻才真正挂效果、冒颜色、放增益特效
                land(level, trail);
                iterator.remove();
                continue;
            }
            double progress = (double) trail.age / TRAIL_LIFE;
            Vec3 point = trail.from.add(trail.to.subtract(trail.from).scale(progress));
            // 火星 + 三团大颗粒粉尘：粉尘太小在远处基本看不见，尺寸和数量都提上来
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, point.x, point.y, point.z,
                    3, 0.06D, 0.06D, 0.06D, 0.0D);
            level.sendParticles(new DustParticleOptions(colorOf(trail.color), 2.4F),
                    point.x, point.y, point.z, 3, 0.05D, 0.05D, 0.05D, 0.0D);
            trail.age++;
        }
    }

    /** 火花落地：挂效果 + 身上冒颜色（+ 增益的三层特效）。 */
    private static void land(ServerLevel level, Trail trail) {
        for (MobEffect effect : trail.effects) {
            MobEffectInstance instance = trail.buff
                    ? new MobEffectInstance(effect, AURA_BUFF_DURATION, 0, false, true, true)
                    : new MobEffectInstance(effect, AURA_DURATION, 0, false, false, true);
            if (!trail.target.addEffect(instance)) {
                // 免控的 Boss 会被 canBeAffected 挡掉，这里强行挂上
                trail.target.forceAddEffect(instance, null);
            }
            puff(level, trail.target, effect);
        }
        if (trail.buff) {
            buffFlash(level, trail.target);
        }
    }

    /** RGB 整数 -> 粉尘颜色。 */
    private static Vector3f colorOf(int color) {
        return new Vector3f(((color >> 16) & 0xFF) / 255.0F,
                ((color >> 8) & 0xFF) / 255.0F,
                (color & 0xFF) / 255.0F);
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
            // 光束期转两倍快：光柱在放的时候，绕圈的节拍也跟着提上来
            this.orbitAngle += ORBIT_SPEED * (this.summoned ? BrainrotCycle.orbitSpeedScale(owner) : 1.0D);
        }
        double angle = this.orbitAngle + this.orbitPhase;
        // 光束期半径会呼吸：一边绕圈一边往外扩、再收回来
        double radius = this.summoned
                ? ORBIT_RADIUS * BrainrotCycle.orbitRadiusScale(owner) : ORBIT_RADIUS;
        double x = center.x + Math.cos(angle) * radius;
        double z = center.z + Math.sin(angle) * radius;
        // 上下微浮也挂在同一个角度上：不要再用 tickCount，否则 tickCount 一旦重新计数
        // （客户端重新加进世界时会从 0 开始）画面上就会突然弹一下。
        double y = center.y - this.getBbHeight() * 0.5D
                + Math.sin(this.orbitAngle * 9.2D + this.orbitPhase) * 0.2D;
        this.setPos(x, y, z);
        this.setDeltaMovement(Vec3.ZERO);
        float yaw = (float) (Mth.atan2(center.z - z, center.x - x) * (180.0D / Math.PI)) - 90.0F;
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
        List<MobEffect> effects = negativeEffects();
        if (effects.isEmpty()) {
            return;
        }
        // 越往后挂得越多：开局 3 条，之后每 4 秒 +1 条，最多挂满整张表。
        int age = Math.max(0, BrainrotCycle.ageOf(owner));
        int count = Math.min(effects.size(), AURA_EFFECTS_BASE + age / AURA_EFFECTS_EVERY);
        AABB area = this.getBoundingBox().inflate(AURA_RADIUS);
        // 这一轮负面场最多朝**一个**敌人开火：半径里二十个敌人同时中招也只飞一道
        boolean trailFired = false;
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
            // 这个目标走不走「先射火花、飞到才挂」那条路
            boolean shoot = !trailFired && takeTrailSlot(level);
            List<MobEffect> pending = new ArrayList<>();
            // 每个目标每轮最多冒几团颜色，别一次刷出几百个粒子
            int puffed = 0;
            for (int index = 0; index < count; index++) {
                MobEffect effect = effects.get(index);
                MobEffectInstance current = target.getEffect(effect);
                if (current != null && (current.getAmplifier() > 0
                        || current.getDuration() > AURA_REFRESH_BELOW)) {
                    // 已经有更强的、或者还够久，就不用再挂一次
                    continue;
                }
                this.debuffsGiven++;
                if (shoot) {
                    // 先攒着：等火花飞到身上再一起挂上
                    pending.add(effect);
                    continue;
                }
                MobEffectInstance instance =
                        new MobEffectInstance(effect, AURA_DURATION, 0, false, false, true);
                if (!target.addEffect(instance)) {
                    // 免控的 Boss（凋灵、监守者、各种模组 Boss）会被 canBeAffected 挡掉，
                    // 这里强行挂上 —— 这顶帽子的负面场本来就该盖住所有人。
                    target.forceAddEffect(instance, null);
                }
                // 看得见：目标身上冒一团「这条效果自己的颜色」
                if (puffed++ < AURA_PUFFS_PER_PULSE) {
                    puff(level, target, effect);
                }
            }
            if (!pending.isEmpty()) {
                trailFired = true;
                fireTrail(target, pending, TRAIL_RED, false);
            }
        }
    }

    /** 这一轮已经给敌人挂了多少条负面效果；每满 AURA_BUFF_EVERY 就给主人一次随机增益。 */
    private int debuffsGiven;

    /**
     * 主人 UUID -> 下一次允许给增益的服务器刻。
     *
     * <p>**静态**：四只 Teto 共用同一份，所以「15 秒内最多被一只 Teto 增益一次」是硬约束，
     * 不会出现四只同时各发一份的情况。
     */
    private static final Map<UUID, Long> BUFF_READY_AT = new HashMap<>();

    /** 连击增益：多少连击起步。 */
    private static final int COMBO_BUFF_MIN = 10;
    /** 连击增益的**独立**冷却（6 秒）：和上面那条 15 秒的光环增益互不影响。 */
    private static final int COMBO_BUFF_COOLDOWN = 120;
    /** 连击增益：每多少连击升一级（封顶 +2 级）。 */
    private static final int COMBO_PER_BUFF_LEVEL = 20;
    /** 主人 -> 连击增益的下次可用时刻。 */
    private static final Map<UUID, Long> COMBO_BUFF_READY_AT = new HashMap<>();

    /** 原版所有「增益、非瞬时」效果（发奖励用），只算一次。 */
    private static List<MobEffect> beneficial;

    /**
     * 把一条效果的颜色变成粉尘喷在目标身上 ——「给了谁、给了什么」直接看得见：
     * 粒子颜色就是这条效果自己的图标颜色。
     */
    private static void puff(ServerLevel level, LivingEntity target, MobEffect effect) {
        int color = effectColor(effect);
        level.sendParticles(new DustParticleOptions(
                        new Vector3f(((color >> 16) & 0xFF) / 255.0F,
                                ((color >> 8) & 0xFF) / 255.0F,
                                (color & 0xFF) / 255.0F), 1.4F),
                target.getX(), target.getY() + target.getBbHeight() * 0.7D, target.getZ(),
                3, 0.3D, 0.35D, 0.3D, 0.0D);
    }

    /** 效果的颜色；模组效果没填颜色（默认 0）就给个紫色兜底，别喷一团纯黑。 */
    private static int effectColor(MobEffect effect) {
        int color = effect.getColor();
        return color == 0 ? 0x8B5CF6 : color;
    }

    /**
     * 每挂满 {@link #AURA_BUFF_EVERY} 条负面效果，就给**主人**随机挂一条增益。
     *
     * <p>三道闸：
     * <ul>
     *   <li>**只有主人** —— 不再分给队友/宠物/路人；</li>
     *   <li>**15 秒一次，全场共用** —— 冷却按主人 UUID 记在静态表里，四只 Teto 读的是同一份，
     *       所以「每 15 秒最多被一只 Teto 增益一次」；</li>
     *   <li>**绝不叠加** —— 只从主人身上*当前没有*的增益里挑，已有的连碰都不碰。</li>
     * </ul>
     * 冷却期间攒着的条数不会丢，冷却一过立刻能发下一次。
     */
    private void maybeBuffOwner(ServerLevel level, LivingEntity owner) {
        if (this.debuffsGiven < AURA_BUFF_EVERY) {
            return;
        }
        long now = level.getServer().getTickCount();
        Long readyAt = BUFF_READY_AT.get(owner.getUUID());
        if (readyAt != null && now < readyAt) {
            return;
        }
        var candidates = beneficialEffects().stream()
                .filter(effect -> !owner.hasEffect(effect))
                .toList();
        if (candidates.isEmpty()) {
            return;
        }
        this.debuffsGiven -= AURA_BUFF_EVERY;
        var buff = candidates.get(level.random.nextInt(candidates.size()));
        BUFF_READY_AT.put(owner.getUUID(), now + AURA_BUFF_COOLDOWN);
        if (!takeTrailSlot(level)) {
            // 火花刚飞过（冷却中）：这次直接挂上，别把奖励吞了
            owner.addEffect(new MobEffectInstance(buff, AURA_BUFF_DURATION, 0, false, true, true));
            buffFlash(level, owner);
            return;
        }
        // 绿火花飞向主人，落地才算数
        fireTrail(owner, List.of(buff), TRAIL_GREEN, true);
    }

    /**
     * 打拍连击换来的额外增益：连击 ≥ {@link #COMBO_BUFF_MIN} 时，按连击层数给**更高等级的**随机增益。
     *
     * <p>冷却**独立**（{@link #COMBO_BUFF_COOLDOWN}，6 秒）：不吃那条 15 秒光环增益的冷却，
     * 所以打得好的时候两种增益会交替落下来。等级随连击涨（每 20 连击 +1 级，封顶 +2），
     * 时长也跟着等级延长。
     */
    private void maybeComboBuffOwner(ServerLevel level, LivingEntity owner) {
        int combo = BrainrotQte.comboOf(owner);
        if (combo < COMBO_BUFF_MIN) {
            return;
        }
        long now = level.getServer().getTickCount();
        Long readyAt = COMBO_BUFF_READY_AT.get(owner.getUUID());
        if (readyAt != null && now < readyAt) {
            return;
        }
        var candidates = beneficialEffects().stream()
                .filter(effect -> !owner.hasEffect(effect))
                .toList();
        if (candidates.isEmpty()) {
            return;
        }
        var buff = candidates.get(level.random.nextInt(candidates.size()));
        int amplifier = Math.min(2, combo / COMBO_PER_BUFF_LEVEL);
        int duration = AURA_BUFF_DURATION + amplifier * 60;
        COMBO_BUFF_READY_AT.put(owner.getUUID(), now + COMBO_BUFF_COOLDOWN);
        owner.addEffect(new MobEffectInstance(buff, duration, amplifier, false, true, true));
        buffFlash(level, owner);
        // 打拍换来的增益额外放一发绿色烟花：和普通光环增益区分开
        comboFirework(level, owner);
    }

    /** 打拍奖励专属：一圈绿色烟花（往外炸的星点 + 一声升空哨音）。 */
    private static void comboFirework(ServerLevel level, LivingEntity owner) {
        double x = owner.getX();
        double y = owner.getY() + owner.getBbHeight() * 0.9D;
        double z = owner.getZ();
        level.sendParticles(ParticleTypes.FIREWORK, x, y, z, 24, 0.35D, 0.55D, 0.35D, 0.12D);
        for (int i = 0; i < 18; i++) {
            double angle = Math.PI * 2.0D * i / 18.0D;
            level.sendParticles(ParticleTypes.COMPOSTER,
                    x + Math.cos(angle) * 0.9D, y, z + Math.sin(angle) * 0.9D,
                    1, 0.0D, 0.15D, 0.0D, 0.02D);
        }
        level.playSound(null, x, y, z, SoundEvents.FIREWORK_ROCKET_LAUNCH,
                SoundSource.PLAYERS, 0.8F, 1.3F);
    }

    /** 增益落地的三层特效：金色星点 + 一圈图腾光点 + 一声很轻的水晶音。 */
    private static void buffFlash(ServerLevel level, LivingEntity owner) {
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                owner.getX(), owner.getY() + owner.getBbHeight() * 0.8D, owner.getZ(),
                14, 0.45D, 0.6D, 0.45D, 0.0D);
        for (int i = 0; i < 10; i++) {
            double angle = Math.PI * 2.0D * i / 10.0D;
            level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING,
                    owner.getX() + Math.cos(angle) * 0.7D,
                    owner.getY() + 0.15D,
                    owner.getZ() + Math.sin(angle) * 0.7D,
                    1, 0.0D, 0.02D, 0.0D, 0.0D);
        }
        level.playSound(null, owner.getX(), owner.getY(), owner.getZ(),
                SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.7F, 1.45F);
    }
    /**
     * 发增益只用一张白名单。
     *
     * <p>「原版所有 {@code isBeneficial()}」这个挑法会抽到名义上算增益、实战里是负收益的东西：
     * 原版就把**飘浮**登记成增益（{@code MobEffectCategory.BENEFICIAL}），一挂上人就开始往上飘，
     * 近战够不着、光束也打歪；模组效果更没法预判（「飞升」那一类）。所以只认下面这些 ——
     * 都是纯战斗 / 续航用得上、且不会改变站位和朝向的。
     */
    private static final java.util.Set<String> COMBAT_BUFFS = new java.util.HashSet<>(
            java.util.Arrays.asList(
                    "speed", "haste", "strength", "jump_boost", "regeneration", "resistance",
                    "fire_resistance", "absorption", "health_boost", "water_breathing",
                    "night_vision", "luck", "dolphins_grace", "conduit_power", "saturation",
                    "hero_of_the_village", "slow_falling"));

    private static boolean isRealBuff(MobEffect effect) {
        var key = BuiltInRegistries.MOB_EFFECT.getKey(effect);
        return key != null && COMBAT_BUFFS.contains(key.getPath());
    }

    /** 原版所有「增益、非瞬时」效果，只算一次。 */
    private static List<MobEffect> beneficialEffects() {
        if (beneficial == null) {
            List<MobEffect> found = new ArrayList<>();
            for (MobEffect effect : BuiltInRegistries.MOB_EFFECT) {
                if (effect.isBeneficial() && !effect.isInstantenous() && isRealBuff(effect)) {
                    found.add(effect);
                }
            }
            beneficial = found;
        }
        return beneficial;
    }

    /** 原版所有「非增益、非瞬时」效果，只算一次。 */
    private static List<MobEffect> negativeEffects() {
        if (negativeEffects == null) {
            List<MobEffect> found = new ArrayList<>();
            for (MobEffect effect : BuiltInRegistries.MOB_EFFECT) {
                if (!effect.isBeneficial() && !effect.isInstantenous()) {
                    found.add(effect);
                }
            }
            negativeEffects = found;
        }
        return negativeEffects;
    }

    /** 消失：撒一团黑烟走人。 */
    public void disappear(ServerLevel level) {
        disappear(level, "未说明");
    }

    /**
     * 消失，并记下**是谁**让它走的。
     *
     * <p>以前这里一律写「tick 里自己收掉」，可曲终清场（{@code HatSummons.clearTetos}）
     * 走的也是这个方法 —— 日志看上去像 Teto 自己撑不住，实际是链路收场顺手把它收了。
     */
    public void disappear(ServerLevel level, String why) {
        if (this.hatmodSelfRemoval) {
            return;                 // 已经散过场：别再撒粒子、别再刷日志
        }
        if (HatMod.LOGGER.isWarnEnabled()) {
            HatMod.LOGGER.warn("[hatmod] Teto 散场（{}，owner={}，血量 {}/{}）",
                    why,
                    this.getOwner() == null ? "无" : this.getOwner().getName().getString(),
                    this.getHealth(), this.getMaxHealth());
        }
        level.sendParticles(ParticleTypes.SQUID_INK,
                this.getX(), this.getY() + this.getBbHeight() * 0.5D, this.getZ(),
                16, 0.4D, 0.8D, 0.4D, 0.03D);
        this.hatmodDismiss();
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

    /**
     * 破防模式下不接受「位移式删除」。
     *
     * <p>Witheria 的 {@code DeathProcedure} 删实体分两步：先 {@code discard()}（被我们的
     * {@link #remove} 拦下），再把实体 {@code moveTo(x + 10000, -4096, z + 10000)} ——
     * 甩到世界角落的虚空里。那个区块不加载，实体就从实体表里消失，于是
     * {@code checkTetos} 一直数到 0、每 0.5 秒补一批，全堆在虚空里。
     */
    @Override
    public void moveTo(double x, double y, double z) {
        if (this.hatmodRejectTeleport(x, y, z)) {
            return;
        }
        super.moveTo(x, y, z);
    }

    @Override
    public void moveTo(double x, double y, double z, float yRot, float xRot) {
        if (this.hatmodRejectTeleport(x, y, z)) {
            return;
        }
        super.moveTo(x, y, z, yRot, xRot);
    }

    /**
     * 这个落点要不要拒。
     *
     * <p>只拒「被甩出去」那一种：主人还在身边，落点却已经在 96 格以外（或者在世界最低高度
     * 以下）。主人自己传送走了不算 —— 那时候主人不在身边，是我们在追他，必须放行。
     */
    private boolean hatmodRejectTeleport(double x, double y, double z) {
        if (!HatSettings.forceBreak() || this.hatmodSelfRemoval) {
            return false;
        }
        if (!(this.level() instanceof ServerLevel server)) {
            return false;
        }
        LivingEntity owner = this.getOwner();
        boolean ownerNearby = owner != null && this.distanceToSqr(owner) < 128.0D * 128.0D;
        boolean intoVoid = y < server.getMinBuildHeight() - 16.0D;
        boolean farFromOwner = false;
        if (owner != null) {
            double dx = x - owner.getX();
            double dz = z - owner.getZ();
            farFromOwner = dx * dx + dz * dz > 96.0D * 96.0D;
        }
        if (!intoVoid && !(ownerNearby && farFromOwner)) {
            return false;
        }
        if (this.hatmodTeleportLogged++ < 5) {
            HatMod.LOGGER.warn("[hatmod] 拦下对 Teto 的搬移 → ({}, {}, {})"
                            + "（破防模式下不接受位移式删除）",
                    Math.round(x), Math.round(y), Math.round(z));
        }
        return true;
    }

    /** 「拦下搬移」这条日志已经打过几次。 */
    private int hatmodTeleportLogged;

    /**
     * 破防模式下「打不过就加入」：外部想删我们一律不认（pig2 那招），自己的退场走内部开关。
     *
     * <p>别的模组的 killEntity / discard / 实体表清理最终都是调 {@code setRemoved}，
     * 把这里挡住它们就删不掉。正常死亡（KILLED）不受影响。
     */
    private boolean hatmodSelfRemoval;
    /** 已经打过几次「拦下删除」的日志（别刷屏）。 */
    private int hatmodRemovalLogged;
    /** 把它打死的那一位：尸体被删前的那一秒用来反噬。 */
    private Entity hatmodKiller;

    @Override
    public void remove(net.minecraft.world.entity.Entity.RemovalReason reason) {
        // 破防模式：外来的 killEntity / discard / 实体表清理 / setRemoved(KILLED) 一律不认，
        // 只有「自己收场」和「区块卸载」放行（后者不放行会卡住存档和维度切换）。
        // 平时不拦 —— 该被打死就被打死，链路跟着正常收场。
        if (HatSettings.forceBreak() && !this.hatmodSelfRemoval
                && reason != net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK
                && this.level() instanceof ServerLevel) {
            if (hatmodRemovalLogged++ < 5) {
                HatMod.LOGGER.warn("[hatmod] 拦下对 Teto 的 remove({})，调用方 {}",
                        reason, hatmodForeignCaller() == null ? "原版流程" : hatmodForeignCaller());
            }
            return;
        }
        super.remove(reason);
    }
    /** 把实体管理器挂在我们身上的回调包一层（见 {@link #GuardedCallback}）。 */
    private void hatmodGuardLevelCallback() {
        if (!HatSettings.forceBreak() || this.hatmodSelfRemoval) {
            return;
        }
        try {
            java.lang.reflect.Field field = com.hatmod.HatAbilities.fieldOfType(
                    net.minecraft.world.entity.Entity.class,
                    net.minecraft.world.level.entity.EntityInLevelCallback.class);
            if (field == null) {
                return;
            }
            Object current = field.get(this);
            if (current instanceof GuardedCallback) {
                return;
            }
            if (current instanceof net.minecraft.world.level.entity.EntityInLevelCallback original) {
                field.set(this, new GuardedCallback(original));
            }
        } catch (Throwable ignored) {
            // 装不上就算了，remove() 那道门还在
        }
    }

    /**
     * 实体管理器的回调包装。
     *
     * <p>真正把实体从 level 里摘掉的是 {@code EntityInLevelCallback#onRemove} ——
     * `remove()` 和 `setRemoved()` 的最后一步都是它。有的模组（Witheria 那种）绕过这两个
     * 方法直接调它、或者写 removalReason 再调它，所以这一层也必须挡住，破防模式下才算
     * 真的「pig2 级」。自己的退场（{@link #hatmodDismiss}）和区块卸载照常放行。
     */
    private final class GuardedCallback implements net.minecraft.world.level.entity.EntityInLevelCallback {
        private final net.minecraft.world.level.entity.EntityInLevelCallback original;

        private GuardedCallback(net.minecraft.world.level.entity.EntityInLevelCallback original) {
            this.original = original;
        }

        @Override
        public void onMove() {
            original.onMove();
        }

        /** 已经拦过几次（只打前几条日志，避免刷屏）。 */
        private int hatmodBlocked;

        @Override
        public void onRemove(net.minecraft.world.entity.Entity.RemovalReason reason) {
            if (hatmodSelfRemoval
                    || reason == net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK) {
                original.onRemove(reason);
                return;
            }
            if (this.hatmodBlocked++ < 5) {
                HatMod.LOGGER.warn("[hatmod] 拦下对 Teto 的外部删除（{}），破防模式下不认账",
                        reason);
            }
        }
    }




    /**
     * 帽子本体自己收掉（到期 / 卸下 / 换帽）的那一刻。
     *
     * <p>先把内部退场标记打上（这样 {@link #remove} 的防盗门不会拦自己），
     * 再放一发「尾杀」—— 周围一圈外人陪着一起走，完全照 pig2 的 L4。
     */
    public void hatmodDismiss() {
        if (!this.hatmodSelfRemoval) {
            this.hatmodSelfRemoval = true;
            HatAbilities.forgetSummon(this);
            HatAbilities.retaliate(this, this.hatmodKiller);
        }
        // 不再早退：已经标过「散场」的也要**保证**真的离开世界。
        // 以前第二次调用直接 return，第一次要是没删干净，这只就成了永远清不掉的影子。
        this.discard();
        if (!this.isRemoved()) {
            HatAbilities.forceRemoveEntity(this);
        }
    }

    /**
     * pig2 那招：外部想把我们写死（{@code setHealth(0)}）一律不认。
     *
     * <p>很多 Boss 不走 {@code hurt}，直接 {@code setHealth} 清血；只挡伤害是挡不住的。
     */

    /**
     * 照 pig2 那招看调用栈：把这次调用的**外部发起者**找出来。
     *
     * <p>pig2 就是用 {@code isCalledFromOtherModWithinN} 这类栈检查认「是不是别的模组在动我」，
     * 然后直接不认账。我们这边防御本身更狠（破防模式下除了自己的退场全挡），
     * 但「凶手是谁」看不出来 —— 尤其是 {@code setHealth(0)} 这种不带伤害来源的路径。
     * 这个方法只用来打日志定位。
     *
     * @return 第一个非原版 / 非本模组的调用方类名；找不到返回 null
     */
    private static String hatmodForeignCaller() {
        StackTraceElement[] stack = new Throwable().getStackTrace();
        for (int i = 2; i < Math.min(stack.length, 20); i++) {
            String cls = stack[i].getClassName();
            if (cls.startsWith("com.hatmod.") || cls.startsWith("net.minecraft.")
                    || cls.startsWith("net.minecraftforge.") || cls.startsWith("net.neoforged.")
                    || cls.startsWith("java.") || cls.startsWith("jdk.") || cls.startsWith("sun.")) {
                continue;
            }
            return cls;
        }
        return null;
    }

    /** 已经打过几次「挨打 / 死亡」日志（只打前几条）。 */
    private int hatmodHitLogged;
    /**
     * 破防模式下恒为「活着」：血被外部直接写 0、或者对方绕过 hurt 调 die，
     * 也不能让 {@code isAlive()} 变成 false —— 链路一看到它「死了」就会判定团灭重唱。
     */
    @Override
    public boolean isAlive() {
        if (HatSettings.forceBreak() && !this.hatmodSelfRemoval) {
            return true;
        }
        return super.isAlive();
    }

    @Override
    public void setHealth(float health) {
        // 破防模式：只准涨不准跌（很多 Boss 不走 hurt，直接 setHealth 清血）
        if (HatSettings.forceBreak() && !this.hatmodSelfRemoval && health < this.getHealth()) {
            if (hatmodHitLogged++ < 5) {
                String caller = hatmodForeignCaller();
                HatMod.LOGGER.warn("[hatmod] Teto 被写血 {} -> {}（调用方 {}），破防模式下不认账",
                        Math.round(this.getHealth()), Math.round(health),
                        caller == null ? "原版流程" : caller);
            }
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
        // 破防模式 = pig2 级：完全打不动（尾杀放在「本体自己收掉」的那一刻）
        if (HatSettings.forceBreak()) {
            if (hatmodHitLogged++ < 5) {
                String caller = hatmodForeignCaller();
                HatMod.LOGGER.warn("[hatmod] Teto 挨了 {} 的一下（{}，{} 点，调用方 {}），破防模式下免疫",
                        source.getEntity() == null ? "未知来源" : source.getEntity().getName().getString(),
                        source.getMsgId(), Math.round(amount),
                        caller == null ? "原版流程" : caller);
            }
            return false;
        }
        boolean hurt = super.hurt(source, amount);
        // 受击反馈：原版「打到人」的那团暗红粒子（Player.attack 同款同参数），
        // 挨打看得见，不会再「默默掉血」了
        if (hurt && this.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.DAMAGE_INDICATOR,
                    this.getX(), this.getY() + this.getBbHeight() * 0.5D, this.getZ(),
                    15, this.getBbWidth() * 0.5D, this.getBbHeight() * 0.25D,
                    this.getBbWidth() * 0.5D, 0.1D);
        }
        return hurt;
    }

    @Override
    public void die(DamageSource source) {
        if (this.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.SQUID_INK,
                    this.getX(), this.getY() + this.getBbHeight() * 0.5D, this.getZ(),
                    16, 0.4D, 0.8D, 0.4D, 0.03D);
        }
        this.hatmodKiller = source.getEntity();
        if (HatMod.LOGGER.isWarnEnabled()) {
            HatMod.LOGGER.warn("[hatmod] Teto 收到死亡事件（{} 造成，{}）{}",
                    source.getEntity() == null ? "未知来源" : source.getEntity().getName().getString(),
                    source.getMsgId(),
                    HatSettings.forceBreak() ? "，破防模式下忽略" : "");
        }
        // 破防模式：**不调 super.die** —— 它会把 dead 标记置上，别的模组一看「死了」就来删，
        // 而 remove 又被我们挡着，实体就卡成「已死但还在」，链路反而判不出团灭。
        // 这一模式下的退场只走 hatmodDismiss（到期 / 卸帽 / 换帽那一刻）。
        if (!HatSettings.forceBreak()) {
            super.die(source);
        }
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
        // 受击反馈：直接套原版玩家的受伤音效 —— Teto 会「哦」一声，玩家才知道它挨打了
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
