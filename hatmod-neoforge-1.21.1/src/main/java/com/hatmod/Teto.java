package com.hatmod;

import net.minecraft.core.Holder;
import com.hatmod.guard.HatGuard;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
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
 * 见 {@code client/TetoRenderer}），和 friends & wine 的舞者是一个路子。
 */
public class Teto extends PathfinderMob implements OwnableEntity {

    /** 召唤者 UUID。 */
    private static final EntityDataAccessor<Optional<UUID>> DATA_OWNER =
            SynchedEntityData.defineId(Teto.class, EntityDataSerializers.OPTIONAL_UUID);

    /**
     * 当前动作序号（0 = 原来那套；1 = 「Let's get it!!」那套；2 = 第三段舞）。
     *
     * <p>放在同步数据里，客户端渲染直接读；服务端是唯一写入方（右键 / 开歌强制归零）。
     */
    private static final EntityDataAccessor<Integer> DATA_ACTION =
            SynchedEntityData.defineId(Teto.class, EntityDataSerializers.INT);

    /** 动作 0：原本那套（曲子编排的那段舞）。 */
    static final int ACTION_ORIGINAL = 0;

    /** 一共几套动作：0 原版 / 1 「Let's get it!!」/ 2 第三段（微信视频那段）。 */
    static final int ACTION_COUNT = 4;

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
    /** 上负面效果的时长（刻）：比周期长一点，不会闪断。 */
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
    /** 刷怪蛋放出来的那一只：认主时说过话了没。 */
    private boolean eggGreeted;
    /** 光柱期间补出来的分身：不带台词，光柱一收就散。 */
    private boolean extra;
    /**
     * 「留在原地」：停在原地待命 —— 不跟、不闲聊、不招仇恨。
     *
     * <p>口语上玩家会叫它「坐下」，但她并没有坐这个动作，只是不再跟着走；对外台词也按
     * 「留下来」写。潜行 + 空手右键切换（见 {@code TetoSitting}）。链路正在跑时不让停下；
     * 反过来，名下只要有一只留在原地的，链路开启时**一只都不放**（连收编、光柱分身都不做）。
     */
    private boolean sitting;
    /** 散养（刷怪蛋/指令）时闲聊的计时器。 */
    private int freeChatTicks;
    /** 「这一轮已经结束了」连续出现了几刻（宽限期内不散场，见 tick）。 */
    private int hatmodOrphanTicks;
    /** 宽限多少刻才真的散场（3 秒）。 */
    private static final int ORPHAN_GRACE_TICKS = 60;

    /** 限伤：单次受击最多扣这么多血（点）。 */
    private static final float TETO_HIT_CAP = 10.0F;

    /** 散养的 Teto 多久聊一句（刻）：30 秒。 */
    private static final int FREE_CHAT_PERIOD = 600;

    /** 散养的跟随：离主人多近就停下（格）。 */
    private static final double FREE_FOLLOW_STOP = 3.0D;
    /** 散养的跟随：每刻最多挪多远（格）。 */
    private static final double FREE_FOLLOW_SPEED = 0.34D;
    /** 散养的跟随：超过这个距离直接传送过来（格）。 */
    private static final double FREE_FOLLOW_TELEPORT = 28.0D;

    /** 散养的招仇恨：多久扫一次周围（刻）。 */
    private static final int AGGRO_PERIOD = 20;
    /** 散养的招仇恨：敌对生物多近才会锁她（格）—— 和村民的索敌范围一个量级。 */
    private static final double AGGRO_RADIUS = 16.0D;

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

    /**
     * 曲子响起来了：把散养的这只**收编**成本轮本体（不重放一只新的）。
     *
     * <p>动作不用额外重置：链路里渲染取帧走的是曲子年龄，收编那一刻年龄正好归零，
     * 她会从第 0 帧重新跳；这里只要把轨道相位按「站定在正前方」重新算一遍。
     */
    void hatmodAdopt(LivingEntity owner, double phase) {
        this.summoned = true;
        this.extra = false;
        this.sitting = false;          // 被收编 = 上场了，先站起来
        this.eggGreeted = true;
        this.freeChatTicks = 0;
        this.hatmodOrphanTicks = 0;
        setOwnerUUID(owner.getUUID());
        this.orbitPhase = (float) phase;
        this.orbitAngle = 0.0D;
    }

    /**
     * 「不请自来」到场的初始化：散养状态、直接认主、不再说初次见面的招呼。
     *
     * <p>和 {@link #hatmodAdopt} 的区别：那是把散养的**收编进链路**（summoned = true），
     * 这里反过来 —— 她自己找上门，是散养的那只。
     */
    void hatmodVisitArrival(LivingEntity owner) {
        setOwnerUUID(owner.getUUID());
        this.summoned = false;
        this.extra = false;
        this.sitting = false;
        this.eggGreeted = true;             // 招呼由调用方说「不请自来」那一组
        this.freeChatTicks = 0;
        this.hatmodOrphanTicks = 0;
        this.orbitAngle = 0.0D;
        this.orbitPhase = this.random.nextFloat() * (float) (Math.PI * 2.0D);
        this.setPersistenceRequired();
    }

    /**
     * 这一轮结束了，但把她**留下**：变回散养状态 —— 跟着主人走、会闲聊、会挡仇恨，
     * 不再是这一轮的「主唱」（不绕圈、不放负面场、不吃曲终清场）。
     *
     * <p>下一轮开始时她会被 {@code HatSummons.adoptFreeTeto} 就地收编，不会多出一只新的。
     */
    void hatmodRelease(@Nullable LivingEntity owner) {
        this.summoned = false;
        this.extra = false;
        this.sitting = false;
        this.eggGreeted = true;             // 已经见过面了：不再说初次见面的那两句
        this.freeChatTicks = 0;
        this.hatmodOrphanTicks = 0;
        this.setNoGravity(false);
        this.orbitAngle = 0.0D;
        this.orbitPhase = this.random.nextFloat() * (float) (Math.PI * 2.0D);
        if (owner != null) {
            setOwnerUUID(owner.getUUID());
        }
    }

    /** 光柱期间的 3 个分身：和本体一样会跳，只是不开口。 */
    void initExtra(LivingEntity owner, double phase) {
        init(owner, phase);
        this.extra = true;
    }

    /** 是不是光柱期间补出来的分身。 */
    boolean isExtra() {
        return this.extra;
    }

    /**
     * 轨道半径（格）。召唤 / 收编时摆位用它，别写死 2 格 ——
     * 摆得比轨道近，第一刻 orbit() 会把她一把拽到圆上，看着像闪了一下。
     */
    static double orbitRadius() {
        return ORBIT_RADIUS;
    }

    /** 轨道相位（弧度）—— 光柱分身按它 +90° 排开用。 */
    double orbitPhase() {
        return this.orbitPhase;
    }

    /** 这一只**此刻**在轨道上的角度：orbitAngle + orbitPhase。 */
    double orbitAngleNow() {
        return this.orbitAngle + this.orbitPhase;
    }

    void init(LivingEntity owner, double phase) {
        setOwnerUUID(owner.getUUID());
        this.orbitPhase = (float) phase;
        // 相位只在 orbit() 里加一次；这里再赋一遍会让 4 只两两重合（0/π/2π/3π/2 翻倍成 0/π/0/π）
        this.orbitAngle = 0.0D;
        this.setNoAi(true);
        this.setPersistenceRequired();
        this.summoned = true;
        this.sitting = false;
    }

    // ------------------------------------------------------------------

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_OWNER, Optional.empty());
        builder.define(DATA_ACTION, ACTION_ORIGINAL);
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
        tag.putFloat("Phase", this.orbitPhase);
        tag.putDouble("OrbitAngle", this.orbitAngle);
        tag.putBoolean("Summoned", this.summoned);
        tag.putBoolean("Extra", this.extra);
        tag.putBoolean("Sitting", this.sitting);
        tag.putBoolean("Greeted", this.eggGreeted);
        tag.putInt("Action", actionIndex());
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
        this.extra = tag.getBoolean("Extra");
        this.sitting = tag.getBoolean("Sitting");
        // 说过招呼没有也要存：以前没存，重进游戏后她又把自己当成「第一次见面」。
        // 老存档没有这个标记：有主人的一律当「已经见过」，别再补一句「又见面了」。
        this.eggGreeted = tag.contains("Greeted") ? tag.getBoolean("Greeted") : tag.hasUUID("Owner");
        setActionIndex(tag.getInt("Action"));
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

    /** 当前动作序号（0 = 原来那套，1 / 2 = 另外两套）。 */
    public int actionIndex() {
        return this.entityData.get(DATA_ACTION);
    }

    /** 换动作。服务端调用；越界值一律按原动作处理。 */
    public void setActionIndex(int action) {
        this.entityData.set(DATA_ACTION,
                action >= 0 && action < ACTION_COUNT ? action : ACTION_ORIGINAL);
    }

    /**
     * 现在能不能换动作：曲子放着的时候不行（那一下算无效按键）。
     *
     * <p>「曲子正在放」问的是主人那一轮：链路里她是主唱，动作必须和谱面同步。
     */
    boolean canToggleAction() {
        LivingEntity owner = this.getOwner();
        return owner == null || !BrainrotCycle.isRunning(owner);
    }

    /** 坐着（原地待命）？ */
    boolean isSitting() {
        return this.sitting;
    }

    /**
     * 让她留在原地 / 继续跟着。
     *
     * <p>留在原地 = 待命：不跟随、不闲聊、不招仇恨，主人走远也不传送过去。
     * 继续跟着时什么都不用恢复 —— 下一拍 tick 自己接上。
     */
    void setSitting(boolean value) {
        this.sitting = value;
        this.freeChatTicks = 0;
        if (value) {
            this.getNavigation().stop();
            this.setDeltaMovement(0.0D, 0.0D, 0.0D);
            this.setPersistenceRequired();
            // 她整套移动都是「每刻直接摆到轨道 / 主人身边」，自己从不受重力管；
            // 坐下之后没人摆位了，`noPhysics = true` 下重力会一路把她拽穿地面、掉出世界。
            // 坐着期间干脆关掉重力，起身再打开。
            this.setNoGravity(true);
        } else {
            this.setNoGravity(false);
        }
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
    // 「谁名下有哪些 Teto」的名册（按 UUID 查，不经过实体的 getEntities）
    // ------------------------------------------------------------------

    /**
     * Teto 自己记的名册：{@code 自己 UUID -> 主人 UUID}。
     *
     * <p>「这个玩家名下还有几只」原来是 {@code level.getEntitiesOfClass(...)}，那要经过
     * 实体管理器的过滤（{@code EntityTickList} / {@code ServerLevel} 的那几个方法）。
     * 有模组把这一层换掉之后，查询会说「一只都没有」：链路以为团灭、光柱也锁不到她。
     * 这里只存 UUID，查的时候走 {@code Level#getEntity(UUID)}（纯查表），绕开那一层。
     *
     * <p>只存 UUID 不存实体引用：世界卸载 / 实体被删之后名册不会把人留在内存里 ——
     * {@link #ownedBy} 顺手把查不到的条目剔掉。
     */
    private static final Map<UUID, UUID> OWNER_OF = new HashMap<>();

    /** {@link #ownedBy} 的短缓存：名册不动的话，10 刻内直接复用上一次的结果。 */
    private record OwnedCache(long until, List<Teto> list) {
    }

    private static final Map<UUID, OwnedCache> OWNED_CACHE = new HashMap<>();

    /** 记进名册（{@link #setOwnerUUID} 是唯一入口，换主人会自动跟着改）。 */
    private static void rememberOwner(@Nullable UUID teto, @Nullable UUID owner) {
        if (teto == null) {
            return;
        }
        if (owner == null) {
            OWNER_OF.remove(teto);
        } else {
            OWNER_OF.put(teto, owner);
        }
        OWNED_CACHE.clear();
    }

    /**
     * 某个人名下还活着的 Teto（**跨维度**，按 UUID 一个个取实体）。
     *
     * <p>查不到的直接从名册里剔掉 —— 被别的模组硬删掉的那只不会永远占着一个名额。
     */
    static List<Teto> ownedBy(LivingEntity owner) {
        List<Teto> found = new ArrayList<>();
        if (owner == null || OWNER_OF.isEmpty()) {
            return found;
        }
        UUID ownerId = owner.getUUID();
        long cacheNow = owner.level() instanceof ServerLevel server
                ? server.getGameTime() : Long.MIN_VALUE;
        if (cacheNow != Long.MIN_VALUE) {
            OwnedCache cached = OWNED_CACHE.get(ownerId);
            if (cached != null && cacheNow < cached.until) {
                return cached.list;
            }
        }
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
            Teto alive = null;
            for (ServerLevel level : levels) {
                if (level.getEntity(entry.getKey()) instanceof Teto teto && !teto.isRemoved()) {
                    alive = teto;
                    break;
                }
            }
            if (alive == null) {
                it.remove();
                continue;
            }
            found.add(alive);
        }
        if (cacheNow != Long.MIN_VALUE) {
            OWNED_CACHE.put(ownerId, new OwnedCache(cacheNow + 10L, found));
        }
        return found;
    }

    /**
     * 别的牛仔帽放出光柱的那一刻（见 {@code HatAbilities#tickingCharge}）：
     * 旁边属于他的那只 Teto 会评价一句。
     *
     * <p>只有「她就在你旁边」才说 —— 20 格内、没留守、还在场上。她不在就当没看见。
     */
    static void commentOnBeam(LivingEntity wearer, ServerLevel level) {
        for (Teto teto : ownedBy(wearer)) {
            if (teto.isRemoved() || teto.isSitting() || teto.level() != level) {
                continue;
            }
            if (teto.distanceToSqr(wearer) > 20.0D * 20.0D) {
                continue;
            }
            TetoTalk.sayCooled(level, teto, 24.0D, "beam", 200, TetoSpeech.pick(level.random,
                    TetoSpeech.summonsOf(wearer.getUUID()), "hatmod.teto.beam", 4, 2));
            return;                     // 一只说就够了
        }
    }

    /**
     * 玩家换上了**另一顶**牛仔帽（黑 / 白 / 红 / 全 —— 相对她自己那顶脑蚀帽说的「别的帽子」）：
     * 旁边属于他的那只 Teto 会评价一句。
     *
     * <p>每顶帽子各有三句、按顺序说（第一、二、三次戴这顶），说完就不再提 —— 见
     * {@link TetoSpeech#wearLine}。和光柱那句一样：只有「她就在你旁边」才说。
     */
    static void commentOnHat(LivingEntity wearer, ServerLevel level, HatType type) {
        for (Teto teto : ownedBy(wearer)) {
            if (teto.isRemoved() || teto.isSitting() || teto.level() != level) {
                continue;
            }
            if (teto.distanceToSqr(wearer) > 20.0D * 20.0D) {
                continue;
            }
            // 先记下「他常戴哪顶」：第 4 次戴同一顶时她会说一句「又是这顶」（跨存档，一辈子一次）
            String loyal = TetoRecall.noteHat(wearer.getUUID(), type);
            if (loyal != null) {
                TetoTalk.sayNear(level, teto, 24.0D, loyal);
                return;
            }
            String key = TetoSpeech.wearLine(wearer.getUUID(), type);
            if (key != null) {
                TetoTalk.sayNear(level, teto, 24.0D, key);
            }
            return;                     // 一只说就够了
        }
    }

    /** 名下有没有「坐着」的 Teto（坐着 = 原地待命；有的话链路开启时一只都不放）。 */
    static boolean hasSitting(LivingEntity owner) {
        for (Teto teto : ownedBy(owner)) {
            if (teto.sitting) {
                return true;
            }
        }
        return false;
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
        if (this.sitting) {
            return false;               // 留在原地的时候谁都不打，也不该被谁打
        }
        return this.isOwnedBy(target) ? false : super.canAttack(target);
    }

    /**
     * 留在原地的时候「看不见」。
     *
     * <p>怪物的索敌（{@code Monster.isPrey} / 感知）会先问这一句：返回 false 就不会把她
     * 算成可攻击目标，连仇恨都建立不起来。已经锁上的（坐下之前就在打她的）由
     * {@code clearMobTargets} 每 10 刻清一次。
     */
    @Override
    public boolean canBeSeenAsEnemy() {
        return !this.sitting && super.canBeSeenAsEnemy();
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

    /** Promaid 彩蛋的后半句：第一句说完隔 3 秒补上（世界刻；-1 = 没有排着的话）。 */
    private long eggFollowUpAt = -1L;
    /** 彩蛋后半句隔多久（6 秒 —— 3 秒的话玩家上一句还没读完）。 */
    private static final int EGG_FOLLOW_UP_DELAY = 120;

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            return;
        }
        if (HatSettings.forceBreak()) {
            HatAbilities.watchSummon(this);
        }
        // 再把实体管理器的回调包一层：绕过 remove()/setRemoved() 的删除也要拦得住
        hatmodGuardLevelCallback();
        ServerLevel level = (ServerLevel) this.level();
        // 彩蛋是「一句 + 停顿 + 一句」：前半句说完 3 秒，补上后半句
        if (eggFollowUpAt >= 0 && level.getGameTime() >= eggFollowUpAt) {
            eggFollowUpAt = -1L;
            TetoTalk.sayNear(level, this, 24.0D, "hatmod.teto.egg.promaid.after.1");
        }
        // 四只 Teto 各 tick 一次，tickTrails 内部按服务器刻去重，每刻只推进一次
        tickTrails(level);
        // 附近有 Promaid 的「枪弹天使」时，她的话分三种场面（见 watchBulletAngel）
        watchBulletAngel(level);
        // 附近有 winefox delight 的怨灵：把它们按在原地（配乐 / 鬼图在客户端拦，见 GhostWarden）
        GhostGuard.watchForTeto(level, this);

        // 不再逐刻掉血：寿命完全由「曲子还在不在放」决定（下面那条）。
        // 血量上限是 1024（原版属性上限），所以它是一块 1024 血的移动肉盾，只可能被打死。
        LivingEntity owner = ownerEntity(level);
        if (owner == null) {
            if (this.sitting) {
                // 坐着的那只：主人下线 / 换维度也原地待着，不换认别人
                return;
            }
            if (this.summoned) {
                // 召唤出来的 Teto 一旦找不到主人（下线 / 换维度 / 重进存档）就自己散掉。
                // 以前这里会「改认最近的玩家」，结果旧 Teto 挂到别人身上、越积越多。
                // 散场前打一声招呼；四只同一刻走，冷却能把重复的话压成一句
                TetoTalk.sayCooled(level, this, 24.0D, "leave", 100, TetoSpeech.pick(level.random,
                        TetoSpeech.summonsOf(getOwnerUUID()), "hatmod.teto.leave", 2, 1));
                disappear(level, "主人已不在这个世界（下线/换维度）");
                return;
            }
            if (getOwnerUUID() != null) {
                // 有主人、但主人这会儿不在（死了 / 下线 / 换了维度）：散养的她原地等，
                // **不重新认别人**。以前这里会认最近的玩家 —— 于是「/kill @e 把自己打死」
                // 的那一刻，她会把同一个（正在死的）玩家重新认一遍，顺口说一句「又见面了」。
                return;
            }
            // 刷怪蛋 / 指令放出来的没有主人：认最近的玩家当主人（相位随手给一个，免得叠在一起）
            owner = level.getNearestPlayer(this, 32.0D);
            if (owner == null) {
                disappear(level, "32 格内没有可跟随的玩家");
                return;
            }
            // 曲子正在放：散养的让位（她不属于这一轮，留下会跟召唤出来的抢位置）
            if (BrainrotCycle.isRunning(owner)) {
                TetoTalk.sayNear(level, this, 24.0D, TetoSpeech.pick(level.random,
                        TetoSpeech.summonsOf(owner.getUUID()), "hatmod.teto.free.leave", 1, 1));
                disappear(level, "曲子已经响起来了，散养的 Teto 让位");
                return;
            }
            setOwnerUUID(owner.getUUID());
            this.orbitPhase = this.random.nextFloat() * (float) (Math.PI * 2.0D);
            // 一个玩家只留一只散养的：新放的顶掉旧的
            for (Entity other : level.getAllEntities()) {
                if (other instanceof Teto teto && teto != this && !teto.isSummoned()
                        && !teto.isSitting() && teto.isOwnedBy(owner)) {
                    teto.hatmodDismiss();
                }
            }
            if (!this.eggGreeted) {
                this.eggGreeted = true;
                // 认了主才算**一次成功召唤**（放出来又没认主的、被刷怪守卫挡下的都不算）
                int summons = HatSettings.addTetoSummon(owner.getUUID(), level.getGameTime());
                // 「又见面了？」只对真的见过她的人说（按次数分层，见 TetoSpeech）；
                // 之前挨过打的，一半机会换成「你这次不会又打我吧」那一组。
                // 换存档 / 久别重逢 / 死过几次 / 记住的那支舞 这类跨存档的旧账优先，
                // 她说了这些就不再补普通的招呼（见 TetoRecall）。
                String greeting = HatSettings.tetoHitFresh(owner.getUUID(), level.getGameTime())
                        && level.random.nextBoolean()
                        ? TetoTalk.pick(level.random, "hatmod.teto.greet.hit", 2)
                        : TetoSpeech.pickGreeting(level.random, summons,
                                TetoMood.tier(TetoMood.mood(owner.getUUID(), level.getGameTime())));
                if (!TetoRecall.onMeet(level, this, owner)) {
                    TetoTalk.sayNear(level, this, 24.0D, greeting);
                }
                HatSettings.markTetoMet(owner.getUUID());
            }
        }
        if (this.sitting) {
            // 留在原地 = 待命：不绕圈、不跟随、不闲聊、不放负面场，
            // 也不走下面任何一条「该散场了」的判定（曲终 / 摘帽 / 断链都不关她的事）。
            // 但位置得每刻钉住：`noPhysics` 下不钉就是自由落体，会直接掉出世界。
            if (!this.isNoGravity()) {
                this.setNoGravity(true);
            }
            this.setDeltaMovement(0.0D, 0.0D, 0.0D);
            if (this.tickCount % 10 == 0) {
                clearMobTargets(level);
            }
            return;
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
            // 主人死了不等于她也死：她留在原地等着（死亡地点和复活点往往不是一个地方）。
            // 玩家一复活，她会被挪到复活点旁边 —— 见 {@code TetoReaction#onRespawn}。
            this.setDeltaMovement(0.0D, 0.0D, 0.0D);
            this.fallDistance = 0.0F;
            return;
        }

        if (this.summoned) {
            this.orbit(owner);
            this.tauntNearby(level, owner);
        } else {
            // 散养的那只不绕圈：跟在你后面走（离太远就传送过来）
            this.follow(owner);
            this.freeChat(level);
            this.drawAggro(level, owner);
        }
        // 「Nope, too late」那一段画面上只该有那三个词和 Teto 本人：
        // 负面场、光环增益、打拍烟花全部停手 —— 火花和粒子都会糊住那一下小动作。
        boolean calm = BrainrotBeats.speechAnchor(this.tickCount) >= 0;
        // 散养的不放负面场、也不给人上 buff —— 她只是跟着你走、陪你说话
        if (this.summoned && !calm && this.tickCount % AURA_PERIOD == 0) {
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
        private final List<Holder<MobEffect>> effects;
        private final int color;
        private final boolean buff;
        private final Vec3 from;
        private final Vec3 to;
        private int age;

        private Trail(LivingEntity target, List<Holder<MobEffect>> effects, int color,
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
    private void fireTrail(LivingEntity target, List<Holder<MobEffect>> effects, int color, boolean buff) {
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
        for (Holder<MobEffect> effect : trail.effects) {
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
        int chainAge = this.summoned ? BrainrotCycle.ageOf(owner) : -1;
        int stillTick = BrainrotCycle.firstTextTick();
        if (this.summoned && chainAge >= 0 && chainAge <= stillTick) {
            // 开场这一段的角度**直接由链路年龄算**：从 0 到「站定」那一刻正好转满一整圈，
            // 于是第一次 Nope, too late 出现时她刚好回到正前方 —— 四只的相位差是严格的 90°，
            // 于是必然是一前、一右、一后、一左，不需要再花时间做任何「归位」修正。
            // 为什么按年龄算而不是每刻加固定角度：服务器掉 TPS、链路追帧的时候，
            // 累计角度会差出十几度 —— 偏偏就是那一秒，她会停在斜向之外。
            this.orbitAngle = Math.PI * 2.0D * chainAge / stillTick;
        } else if (!(this.summoned && BrainrotCycle.tetoOrbitFrozen(owner))) {
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
            if (isBulletAngel(mob)) {
                continue;                       // 不招她：这事玩家自己处理（何况她也打不动）
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

    /** 留在原地期间：把「正锁着我」的怪物目标清掉（已经建立的仇恨不会自己消）。 */
    private void clearMobTargets(ServerLevel level) {
        for (LivingEntity other : level.getEntitiesOfClass(LivingEntity.class,
                this.getBoundingBox().inflate(48.0D), entity -> entity instanceof Mob)) {
            Mob mob = (Mob) other;
            if (mob.getTarget() == this) {
                mob.setTarget(null);
            }
        }
    }

    /**
     * 散养的跟随：保持 3 格左右跟在后面，超过 28 格直接传送到主人身边。
     *
     * <p>她是 {@code setNoAi} 的平面贴图，没有寻路可用，所以直接改坐标 —— 和绕圈
     * 用的是同一套做法，不会去撞方块。
     */
    /**
     * 像原版宠物一样跟着主人换维度：散养的她直接搬过去，落在主人身边。
     *
     * <p>召唤出来的那几只不搬 —— 它们属于「这一轮」，换维度就照旧散场；
     * 留守（坐着）的那只也不搬，她本来就是「原地等」。
     */
    static void followThroughDimension(ServerPlayer owner, ServerLevel to) {
        for (Teto teto : ownedBy(owner)) {
            if (teto.isRemoved() || teto.isSitting() || teto.isSummoned()
                    || teto.level() == to || !(teto.level() instanceof ServerLevel)) {
                continue;
            }
            UUID id = teto.getUUID();
            TetoSpawnGuard.beginDimensionTransfer(id);
            try {
                Entity moved = teto.changeDimension(new net.minecraft.world.level.portal.DimensionTransition(
                        to, teto.position(), Vec3.ZERO, teto.getYRot(), teto.getXRot(),
                        net.minecraft.world.level.portal.DimensionTransition.DO_NOTHING));
                if (moved instanceof Teto copy) {
                    double angle = owner.getRandom().nextDouble() * Math.PI * 2.0D;
                    copy.moveTo(owner.getX() + Math.cos(angle) * 2.4D, owner.getY(),
                            owner.getZ() + Math.sin(angle) * 2.4D, owner.getYRot(), 0.0F);
                }
            } finally {
                TetoSpawnGuard.endDimensionTransfer(id);
            }
        }
    }

    private void follow(LivingEntity owner) {
        double distance = this.distanceTo(owner);
        if (distance > FREE_FOLLOW_TELEPORT) {
            double angle = this.random.nextDouble() * Math.PI * 2.0D;
            this.moveTo(owner.getX() + Math.cos(angle) * 2.0D, owner.getY(),
                    owner.getZ() + Math.sin(angle) * 2.0D, owner.getYRot(), 0.0F);
            return;
        }
        if (distance <= FREE_FOLLOW_STOP) {
            return;
        }
        double dx = owner.getX() - this.getX();
        double dz = owner.getZ() - this.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-4D) {
            return;
        }
        double step = Math.min(FREE_FOLLOW_SPEED, length - FREE_FOLLOW_STOP);
        this.setPos(this.getX() + dx / length * step, owner.getY(), this.getZ() + dz / length * step);
    }

    /**
     * 招仇恨：让敌对生物像看到村民一样锁她。
     *
     * <p>原版的索敌目标表里只有玩家 / 村民这几类，Teto 不在里面，所以放着不管的话
     * 怪物会完全无视她。这里每 20 刻扫一次周围的敌对生物：
     * <ul>
     *   <li>只挑 {@link Enemy}（会主动打村民的那一类）；</li>
     *   <li>已经在追玩家的**不抢**——村民的索敌优先级本来就比玩家低；</li>
     *   <li>已经追着别的目标（不是 Teto）的也不抢，避免把整片仇恨全吸过来；</li>
     *   <li>要有视线、在 16 格内。</li>
     * </ul>
     */
    private void drawAggro(ServerLevel level, LivingEntity owner) {
        if (this.tickCount % AGGRO_PERIOD != 0) {
            return;
        }
        for (Mob mob : level.getEntitiesOfClass(Mob.class,
                this.getBoundingBox().inflate(AGGRO_RADIUS))) {
            if (!(mob instanceof Enemy) || HatAbilities.isFriendlyTo(owner, mob)
                    || isBulletAngel(mob)) {
                continue;
            }
            LivingEntity current = mob.getTarget();
            if (current instanceof net.minecraft.world.entity.player.Player) {
                continue;
            }
            if (current != null && !(current instanceof Teto)) {
                continue;
            }
            if (mob.distanceToSqr(this) > AGGRO_RADIUS * AGGRO_RADIUS
                    || !mob.hasLineOfSight(this)) {
                continue;
            }
            mob.setTarget(this);
        }
    }

    /** 这一轮闲聊已经说了几句（meta 要等前 {@link #META_MIN_IDLE} 句说完才允许出现）。 */
    private int idleSaid;
    /** meta 台词最早排在第几句之后。 */
    private static final int META_MIN_IDLE = 5;

    /** 洗牌袋：把台词编号打乱后依次抽，抽完再洗，避免同一句反复出现。 */
    private int[] freeDeck;
    private int freeDeckPos;
    /** 袋子是按哪一层洗的：换层要重洗。 */
    private int freeDeckTier = -1;

    /**
     * 从这一层的池子里抽下一句（一轮之内不重复）。
     *
     * <p>袋子洗的是**池子的下标**：池子换了（第一次 20 条 / 第二次 26 条 / 第三次 26 条）
     * 就重新洗。以前洗的是 1..N 的编号，换层时编号和实际台词对不上，
     * 会取到不存在的那几行。
     */
    private String nextIdleLine(java.util.List<String> pool, int tier) {
        if (this.freeDeck == null || this.freeDeckTier != tier
                || this.freeDeckPos >= this.freeDeck.length) {
            this.freeDeckTier = tier;
            this.freeDeck = new int[pool.size()];
            for (int i = 0; i < pool.size(); i++) {
                this.freeDeck[i] = i;
            }
            for (int i = pool.size() - 1; i > 0; i--) {
                int j = this.random.nextInt(i + 1);
                int tmp = this.freeDeck[i];
                this.freeDeck[i] = this.freeDeck[j];
                this.freeDeck[j] = tmp;
            }
            this.freeDeckPos = 0;
        }
        return pool.get(this.freeDeck[this.freeDeckPos++]);
    }

    /** 散养的 Teto：每 30 秒聊一句（按「第几次被她陪」分层，见 {@link TetoSpeech}）。 */
    private void freeChat(ServerLevel level) {
        // 坐着 = 待命：既不闲聊，也不会「先走了」。tick 里坐着就早退了，这里再兜一道，
        // 以后谁改了调用顺序也不会让她坐着坐着消失。
        if (this.sitting) {
            return;
        }
        if (++this.freeChatTicks < FREE_CHAT_PERIOD) {
            return;
        }
        this.freeChatTicks = 0;
        // 她在跟谁说这句话：台词的分层（第几次见面 / 记得哪些事）按**这个人**算。
        // 主人不是玩家（生物戴帽子召唤出来的那种）：她**一句话都不说**。
        ServerPlayer listener = listenerOf(level);
        if (listener == null) {
            return;
        }
        // 小概率：这一次她不说闲话，而是「我有点事，先走了」—— 当着你的面散场。
        // 概率在调参器「脑蚀」页可调（出厂 1%），调试时拉到 1 就能每 30 秒复现一次。
        if (this.random.nextDouble() < HatSettings.tetoVanishChance()) {
            if (listener != null) {
                // 她记得自己溜过一次 —— 下次再见面会说「上次是骗你的」
                HatSettings.rememberTeto(listener.getUUID(), TetoSpeech.VANISH);
            }
            TetoTalk.sayNear(level, this, 24.0D, TetoSpeech.pick(level.random,
                    TetoSpeech.summonsOf(listener.getUUID()), "hatmod.teto.free.vanish", 3, 2));
            disappear(level, "闲聊时自己先走了（几率消失）");
            return;
        }
        String shadeLine = TetoSpeech.shadeLine(level, listener);
        if (shadeLine != null) {
            TetoTalk.sayCooled(level, this, 24.0D, "shade", 1200, shadeLine);
            this.idleSaid++;
            return;
        }
        long moodNow = level.getGameTime();
        int summons = HatSettings.tetoSummons(listener.getUUID());
        // 「只属于此刻」的话：第二次见面那句、或者某段还没说过的记忆
        String special = TetoSpeech.specialLine(level, listener.getUUID(), summons);
        if (special != null) {
            TetoTalk.sayNear(level, this, 24.0D, special,
                    TetoSpeech.memoryArgs(listener.getUUID(), special));
            this.idleSaid++;            // 记忆台词也算「这一轮已经说过的话」
            return;
        }
        int tier = TetoSpeech.tierOf(summons);
        // 一次对话的前 5 句不说 meta：她先像个正常人，聊开了才露出「我知道自己是模组角色」
        boolean allowMeta = this.idleSaid >= META_MIN_IDLE;
        java.util.List<String> pool = TetoSpeech.idlePool(summons, listener.getUUID(), allowMeta);
        pool.addAll(TetoSpeech.moodPool(TetoMood.tier(TetoMood.mood(listener.getUUID(), moodNow))));
        String idle = nextIdleLine(pool,
                tier * 10000 + pool.size() * 100 + TetoSpeech.metaCount(summons, allowMeta));
        this.idleSaid++;
        if (idle.startsWith("hatmod.teto.egg.")) {
            HatSettings.markTetoSpoken(listener.getUUID(), idle);   // 彩蛋一人只说一次
            eggFollowUpAt = level.getGameTime() + EGG_FOLLOW_UP_DELAY;   // 6 秒后补后半句
        } else if (idle.startsWith("hatmod.teto.boss.")) {
            // 枪弹天使的机制 / 初衷：一人只说一次，而且整个话题只开一遍
            HatSettings.markTetoSpoken(listener.getUUID(), idle);
            HatSettings.markTetoSpoken(listener.getUUID(), TetoSpeech.ANGEL_TOPIC);
        }
        TetoTalk.sayNear(level, this, 24.0D, idle);
    }

    /**
     * 「她在跟谁说话」：只有**玩家名下**的 Teto 才说话。
     *
     * <p>主人是生物（戴帽子的僵尸、别模组塞过来的）就当没有台词 —— 以前这里会退到
     * 「最近的玩家」，结果一只僵尸的 Teto 会对着路过的人闲聊，看着很怪。
     */
    private ServerPlayer listenerOf(ServerLevel level) {
        return ownerEntity(level) instanceof ServerPlayer player ? player : null;
    }

    // ------------------------------------------------------------------
    // 和 Promaid 的联动：附近有「枪弹天使」时她会惊呼
    // ------------------------------------------------------------------

    /** Promaid 的枪弹天使在游戏里的注册 id（那个模组本体叫 maid_smart）。 */
    private static final String BULLET_ANGEL_ID = "maid_smart:bullet_angel";
    /** 多远之内算「看见了她」（格）。 */
    private static final double ANGEL_SIGHT = 24.0D;

    /** 三种场面各说一次，说完就记进配置，一辈子不再提。 */
    private static final String ANGEL_MEET = "hatmod.teto.angel.meet";
    private static final String ANGEL_DUEL = "hatmod.teto.angel.duel";
    private static final String ANGEL_MOBS = "hatmod.teto.angel.mobs";

    /**
     * 附近有 Promaid 的「枪弹天使」时，她的话分三种场面，各说一次：
     * <ol>
     *   <li>{@code hatmod.teto.angel.meet.*} —— 刚撞见她（那句「这事你自己处理吧」）；</li>
     *   <li>{@code hatmod.teto.angel.duel.*} —— 玩家真的和她打起来了；</li>
     *   <li>{@code hatmod.teto.angel.mobs.*} —— 她转身去清别的怪了。</li>
     * </ol>
     *
     * <p>以前是一句「惊呼」每 60 秒循环一次，听两遍就腻了。现在同一件事只开一次口：
     * 记在 {@link HatSettings#markTetoSpoken} 里，换存档也还是说过。她本来就不掺和
     * 那个天使的事（帽子也不会把天使当敌人），所以台词只是「表态」，不是战况播报。
     *
     * <p>**不硬依赖那个模组** —— 只按实体注册 id 认人（{@code maid_smart:bullet_angel}），
     * 没装的时候这一段永远不会触发，也不会因为没装而崩。
     */
    private void watchBulletAngel(ServerLevel level) {
        if (this.tickCount % 40 != 0 || this.extra) {
            return;                       // 两秒查一次，不必每刻扫
        }
        ServerPlayer listener = listenerOf(level);
        if (listener == null) {
            return;
        }
        java.util.UUID player = listener.getUUID();
        LivingEntity angel = nearestAngel(level, listener);
        if (angel == null) {
            return;
        }
        // 1) 第一次撞见：这句里就带着「我不插手」的态度，所以排在最前面
        if (!HatSettings.tetoSpoken(player, ANGEL_MEET)) {
            HatSettings.markTetoSpoken(player, ANGEL_MEET);
            TetoTalk.sayNear(level, this, 24.0D,
                    TetoTalk.pick(level.random, ANGEL_MEET, 3));
            return;
        }
        // 2) 玩家和她动手了：只提醒一次，之后不再插嘴
        if (!HatSettings.tetoSpoken(player, ANGEL_DUEL) && isDueling(angel, listener)) {
            HatSettings.markTetoSpoken(player, ANGEL_DUEL);
            TetoTalk.sayNear(level, this, 24.0D,
                    TetoTalk.pick(level.random, ANGEL_DUEL, 3));
            return;
        }
        // 3) 她自己跑去清别的怪了：这时候她顾不上玩家这边，说一句「这边归你」
        if (!HatSettings.tetoSpoken(player, ANGEL_MOBS) && isBusyWithMobs(angel)) {
            HatSettings.markTetoSpoken(player, ANGEL_MOBS);
            TetoTalk.sayNear(level, this, 24.0D,
                    TetoTalk.pick(level.random, ANGEL_MOBS, 3));
        }
    }

    /** 玩家附近最近的那只枪弹天使（没有就是 null）。 */
    @Nullable
    private static LivingEntity nearestAngel(ServerLevel level, ServerPlayer player) {
        LivingEntity best = null;
        double bestDist = ANGEL_SIGHT * ANGEL_SIGHT;
        for (Entity entity : level.getEntitiesOfClass(Entity.class,
                player.getBoundingBox().inflate(ANGEL_SIGHT))) {
            if (!isBulletAngel(entity) || entity.distanceToSqr(player) > bestDist) {
                continue;
            }
            if (entity instanceof LivingEntity living) {
                bestDist = entity.distanceToSqr(player);
                best = living;
            }
        }
        return best;
    }

    /** 玩家是不是真的在跟她打：她锁着玩家，或者两个人最近互相打过。 */
    private static boolean isDueling(LivingEntity angel, ServerPlayer player) {
        if (angel instanceof Mob mob && mob.getTarget() == player) {
            return true;
        }
        if (angel.getLastHurtByMob() == player) {
            return true;
        }
        return player.getLastHurtMob() == angel;
    }

    /** 她是不是跑去清别的怪了（锁着一个不是玩家的活物）。 */
    private static boolean isBusyWithMobs(LivingEntity angel) {
        if (!(angel instanceof Mob mob)) {
            return false;
        }
        LivingEntity target = mob.getTarget();
        return target != null && target != angel
                && !(target instanceof net.minecraft.world.entity.player.Player);
    }

    /** 这个实体是不是 Promaid 的枪弹天使（按注册 id 认，不需要那个模组的类）。 */
    static boolean isBulletAngel(Entity entity) {
        if (entity == null) {
            return false;
        }
        net.minecraft.resources.ResourceLocation id =
                net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return id != null && BULLET_ANGEL_ID.equals(id.toString());
    }

    /**
     * 这一下伤害是不是枪弹天使打的。
     *
     * <p>她自己挥的那几下直接就能认出来；枪械 / 法术走的是弹射物、滞留云这类
     * **带着主人**的实体，得顺着 {@code getOwner()} 往上找一层，不然只免疫本体、
     * 子弹照样打得进去。
     */
    static boolean fromBulletAngel(DamageSource source) {
        if (source == null) {
            return false;
        }
        if (isBulletAngel(source.getEntity()) || isBulletAngel(source.getDirectEntity())) {
            return true;
        }
        Entity direct = source.getDirectEntity();
        if (direct instanceof net.minecraft.world.entity.projectile.Projectile shot) {
            return isBulletAngel(shot.getOwner());
        }
        if (direct instanceof net.minecraft.world.entity.AreaEffectCloud cloud) {
            return isBulletAngel(cloud.getOwner());
        }
        return false;
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
        // 越往后挂得越多：开局 3 条，之后每 4 秒 +1 条，最多挂满整张表。
        // 这样一戴上不会被瞬间糊满，曲子推进到后半段才真正"毒起来"。
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
            List<Holder<MobEffect>> pending = new ArrayList<>();
            // 每个目标每轮最多冒几团颜色，别一次刷出几百个粒子
            int puffed = 0;
            for (int index = 0; index < count; index++) {
                Holder<MobEffect> effect = effects.get(index);
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
    private static List<Holder<MobEffect>> beneficial;

    /**
     * 把一条效果的颜色变成粉尘喷在目标身上 ——「给了谁、给了什么」直接看得见：
     * 粒子颜色就是这条效果自己的图标颜色。
     */
    private static void puff(ServerLevel level, LivingEntity target, Holder<MobEffect> effect) {
        int color = effectColor(effect);
        level.sendParticles(new DustParticleOptions(
                        new Vector3f(((color >> 16) & 0xFF) / 255.0F,
                                ((color >> 8) & 0xFF) / 255.0F,
                                (color & 0xFF) / 255.0F), 1.4F),
                target.getX(), target.getY() + target.getBbHeight() * 0.7D, target.getZ(),
                3, 0.3D, 0.35D, 0.3D, 0.0D);
    }

    /** 效果的颜色；模组效果没填颜色（默认 0）就给个紫色兜底，别喷一团纯黑。 */
    private static int effectColor(Holder<MobEffect> effect) {
        int color = effect.value().getColor();
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
    private static List<Holder<MobEffect>> beneficialEffects() {
        if (beneficial == null) {
            List<Holder<MobEffect>> found = new ArrayList<>();
            for (Holder<MobEffect> holder : BuiltInRegistries.MOB_EFFECT.holders().toList()) {
                MobEffect effect = holder.value();
                if (effect.isBeneficial() && !effect.isInstantenous() && isRealBuff(effect)) {
                    found.add(holder);
                }
            }
            beneficial = found;
        }
        return beneficial;
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

    private int hatmodTeleportLogged;

    private boolean hatmodSelfRemoval;
    /** 上一次「坐下 / 起身」手势的服务器刻（右键实体双手会各发一次，用它压成一次）。 */
    private long lastSitCommand = Long.MIN_VALUE / 2;

    /** 这一次右键算不算数：4 刻内只认第一次。 */
    boolean hatmodAcceptSitCommand(long now) {
        if (now - this.lastSitCommand < 4) {
            return false;
        }
        this.lastSitCommand = now;
        return true;
    }

    private int hatmodRemovalLogged;
    private Entity hatmodKiller;

    @Override
    public void remove(net.minecraft.world.entity.Entity.RemovalReason reason) {
        // 自己收场 / 区块卸载：放行（后者不放行会卡住存档和维度切换）
        if (this.hatmodSelfRemoval
                || reason == net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK
                || reason == net.minecraft.world.entity.Entity.RemovalReason.CHANGED_DIMENSION
                || !(this.level() instanceof ServerLevel)) {
            HatGuard.beginApprovedRemoval();
            try {
                super.remove(reason);
            } finally {
                HatGuard.endApprovedRemoval();
            }
            return;
        }
        if (!HatSettings.forceBreak()) {
            HatGuard.beginApprovedRemoval();
            try {
                super.remove(reason);
            } finally {
                HatGuard.endApprovedRemoval();
            }
            return;
        }
        if (hatmodRemovalLogged++ < 5) {
            HatMod.LOGGER.warn("[hatmod] 拦下对 Teto 的 remove({})，调用方 {}",
                    reason, hatmodForeignCaller() == null ? "原版流程" : hatmodForeignCaller());
        }
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
                    || reason == net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK
                    || reason == net.minecraft.world.entity.Entity.RemovalReason.CHANGED_DIMENSION) {
                original.onRemove(reason);
                return;
            }
            if (!HatSettings.forceBreak()) {
                original.onRemove(reason);
                return;
            }
            if (this.hatmodBlocked++ < 5) {
                HatMod.LOGGER.warn("[hatmod] 拦下对 Teto 的外部删除（{}），破防模式下不认账",
                        reason);
            }
        }
    }




    public void hatmodDismiss() {
        if (!this.hatmodSelfRemoval) {
            this.hatmodSelfRemoval = true;
            rememberOwner(this.getUUID(), null);   // 散场了：从名册里划掉
            HatAbilities.forgetSummon(this);
            HatAbilities.retaliate(this, this.hatmodKiller);
        }
        this.discard();
        if (!this.isRemoved()) {
            HatAbilities.forceRemoveEntity(this);
        }
    }


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
    @Override
    public boolean isAlive() {
        if (HatSettings.forceBreak() && !this.hatmodSelfRemoval) {
            return true;
        }
        return super.isAlive();
    }

    @Override
    public void setHealth(float health) {
        if (SummonDefense.healthWriteBlocked(this.hatmodSelfRemoval, health, this.getHealth())) {
            if (hatmodHitLogged++ < 5) {
                String caller = hatmodForeignCaller();
                HatMod.LOGGER.warn("[hatmod] Teto 被写血 {} -> {}（调用方 {}），破防模式下不认账",
                        Math.round(this.getHealth()), Math.round(health),
                        caller == null ? "原版流程" : caller);
            }
            return;
        }
        com.hatmod.guard.HatGuard.beginApprovedHealth();
        try {
            super.setHealth(health);
        } finally {
            com.hatmod.guard.HatGuard.endApprovedHealth();
        }
    }

    /**
     * 这一下是不是「自己人」打的（主人的召唤物 / 同类 Teto / 主人的盟友）。
     *
     * <p>和 {@link #hatmodFriendlyStrike} 的区别：玩家**不算**自己人 —— 玩家打的正是
     * 她最该回一句的那种。这里只用来决定「挨打那句话要不要说」。
     */
    private boolean hatmodAllyStrike(DamageSource source) {
        Entity attacker = source.getEntity();
        if (attacker == null) {
            return false;
        }
        if (attacker instanceof HatSummon || attacker instanceof Teto) {
            return true;
        }
        // 玩家**不算**自己人 —— 哪怕是她自己的主人。玩家打她正是她最该回一句的那种，
        // 以前这里没挡住 isFriendlyTo(主人, 主人) == true，主人的那一下整段台词都被跳过了。
        if (attacker instanceof net.minecraft.world.entity.player.Player) {
            return false;
        }
        LivingEntity owner = this.getOwner();
        return owner != null && attacker instanceof LivingEntity living
                && HatAbilities.isFriendlyTo(owner, living);
    }

    private boolean hatmodFriendlyStrike(DamageSource source) {
        return SummonDefense.friendlyStrike(source, this.getOwner());
    }

    /** 「挨打」那句话的冷却（刻）：10 秒。被一群怪围着也只 10 秒一句。 */
    private static final int TETO_HIT_TALK_TICKS = 200;

    @Override
    public boolean hurt(DamageSource source, float amount) {
        // 枪弹天使打不动她 —— 本体来的、子弹 / 法术来的（弹射物记着主人）都算。
        // 这是设定不是数值：她不掺和，也不会被卷进去。
        if (fromBulletAngel(source)) {
            return false;
        }
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
        // 限伤：单次最多吃这么多。310 血 + 每下封顶，一群小怪围着也磨得死，
        // 但不会被一发高伤直接带走。
        // 指令击杀（/kill、/kill @e）不受单次限伤：那不是战斗，是「外面有人直接把她抹掉」。
        // 不放过的话 /kill 只扣 10 点，她根本死不了，die() 里那句吐槽也就永远说不出来。
        if (!"genericKill".equals(source.getMsgId())) {
            amount = Math.min(amount, TETO_HIT_CAP);
        }
        // 放歌期间：玩家打不动她（她是这一轮的主唱，不该在曲子中间被敲）
        boolean playerHit = source.getEntity() instanceof net.minecraft.server.level.ServerPlayer;
        if (playerHit && this.summoned && this.level() instanceof ServerLevel level) {
            LivingEntity owner = ownerEntity(level);
            if (owner != null && BrainrotCycle.isRunning(owner)) {
                return false;
            }
        }
        boolean hurt = super.hurt(source, amount);
        // 受击反馈：原版「打到人」的那团暗红粒子（Player.attack 同款同参数），
        // 挨打看得见，不会再「默默掉血」了
        if (hurt && this.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.DAMAGE_INDICATOR,
                    this.getX(), this.getY() + this.getBbHeight() * 0.5D, this.getZ(),
                    15, this.getBbWidth() * 0.5D, this.getBbHeight() * 0.25D,
                    this.getBbWidth() * 0.5D, 0.1D);
            // 挨打的那句话：**玩家打的和别的东西打的都算**。
            // 早先这段整个包在「攻击者是玩家」里面，还额外要求「不是召唤来的那只」——
            // 于是被怪围、被箭射、被光柱扫到一声都没有，看着就像「受击语音不触发」。
            // 玩家打的仍是原来那两句；别的东西打的用另一组（她分得清是谁在打她）。
            // 挨打的话只在「真的被谁打了」的时候说：摔伤、着火、溺水、饥饿这些**没有攻击者**
            // 的伤害一律不吭声。箭 / 雪球 / 三叉戟和近战共用同一组通用台词 ——
            // 不按武器分池子（以前单拎出一句「连箭都冲我来」，箭没来的时候也会冒出来）。
            // 坐着待命的时候被打照样吭声：被打就是被打。
            if (source.getEntity() != null && !hatmodAllyStrike(source)) {
                LivingEntity hitOwner = ownerEntity(level);
                // 主人是生物（Khragg 这类）时她不开口：台词是给玩家听的
                if (hitOwner != null && !(hitOwner instanceof ServerPlayer)) {
                    return hurt;
                }
                int summons = hitOwner == null ? 0 : TetoSpeech.summonsOf(hitOwner.getUUID());
                String key = playerHit
                        ? TetoSpeech.pick(level.random, summons, "hatmod.teto.free.hit", 2, 2)
                        : TetoSpeech.pick(level.random, summons, "hatmod.teto.hit.other", 3, 2);
                TetoTalk.sayCooled(level, this, 24.0D, "hit", TETO_HIT_TALK_TICKS, key);
                // 她记得「**你**打过她」：之后的开场白 / 见面招呼会因此换一组（见 TetoSpeech.HIT）。
                // 只认她主人本人动手 —— 被怪打、被宠物误伤、被别的玩家打都不记，
                // 不然围殴过一轮之后，下次见面就会莫名其妙来一句「你这次不会又打我吧」。
                if (hitOwner instanceof ServerPlayer bitten && source.getEntity() == bitten) {
                    HatSettings.markTetoHit(bitten.getUUID(), level.getGameTime());
                    TetoMood.adjust(bitten.getUUID(), -8, level.getGameTime());
                }
            }
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
        boolean commandKill = "genericKill".equals(source.getMsgId());
        if (commandKill) {
            if (!HatSettings.forceBreak() && this.level() instanceof ServerLevel killLevel) {
                // 「/kill @e」这种一锅端：不说普通的死法，改吐槽这一下（见 TetoMassKill）。
                // 几只 Teto 同一刻一起死也只会剩一句（sayCooled 按玩家去重）。
                boolean mass = TetoMassKill.massKill(killLevel.getGameTime());
                TetoTalk.sayCooled(killLevel, this, 128.0D, "kill", 60, TetoSpeech.pick(
                        killLevel.random, TetoSpeech.summonsOf(getOwnerUUID()),
                        mass ? "hatmod.teto.kill.mass" : "hatmod.teto.kill", 2, mass ? 1 : 2));
            }
        } else if (this.summoned && !this.extra && this.level() instanceof ServerLevel level) {
            LivingEntity owner = ownerEntity(level);
            if (owner instanceof ServerPlayer player) {
                // 她记得自己在一轮里倒下过 —— 下次见面会说「这次别再让我倒下了」
                HatSettings.rememberTeto(player.getUUID(), TetoSpeech.CHAIN_DIED);
                TetoMood.adjust(player.getUUID(), -10, level.getGameTime());
            }
            if (owner instanceof ServerPlayer && BrainrotCycle.isRunning(owner)) {
                TetoTalk.sayCooled(level, this, 48.0D, "die", 400, TetoSpeech.pick(level.random,
                        TetoSpeech.summonsOf(getOwnerUUID()), "hatmod.teto.die", 2, 2));
            }
        }
        // 散养的（刷怪蛋/指令）：死了就当「她跑了」，按凶手分两种说法
        if (!commandKill && !this.summoned && this.level() instanceof ServerLevel freeLevel) {
            boolean byMe = source.getEntity() != null
                    && source.getEntity().getUUID().equals(getOwnerUUID());
            String base = byMe ? "hatmod.teto.free.die.player" : "hatmod.teto.free.die.other";
            TetoTalk.sayNear(freeLevel, this, 24.0D, TetoSpeech.pick(freeLevel.random,
                    TetoSpeech.summonsOf(getOwnerUUID()), base, 2, 1));
        }
        this.hatmodKiller = source.getEntity();
        if (HatMod.LOGGER.isWarnEnabled()) {
            HatMod.LOGGER.warn("[hatmod] Teto 收到死亡事件（{} 造成，{}）{}",
                    source.getEntity() == null ? "未知来源" : source.getEntity().getName().getString(),
                    source.getMsgId(),
                    HatSettings.forceBreak() ? "，破防模式下忽略" : "");
        }
        if (!HatSettings.forceBreak()) {
            super.die(source);
        }
    }

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
