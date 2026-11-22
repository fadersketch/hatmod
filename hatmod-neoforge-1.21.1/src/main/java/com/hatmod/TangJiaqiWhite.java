package com.hatmod;

import com.hatmod.guard.HatGuard;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
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
 * 唐嘉琦（白色）—— 白帽 / 「全」白路线链路的同伴（和「脑蚀」那只 {@link TangJiaqi} 不是一位）。
 *
 * <p>主人的白帽链路开场时被带出来，链路收场自动散；**每个玩家同时只有一只**。
 *
 * <p>机制同白帽，只有几处不一样：
 * <ul>
 *   <li><b>不放歌</b> —— 它不开自己的循环；</li>
 *   <li><b>没有减速圈那类技能</b> —— 蓄力就只是蓄力；</li>
 *   <li><b>开火节奏跟玩家</b> —— 主人照射它才照射，主人蓄力它就游走
 *       （和唐嘉琦（1）跟着脑蚀光束窗口是同一个思路）；</li>
 *   <li><b>6 次闪避</b> —— 闪避是<b>瞬移</b>：躲开的瞬间闪到出手者侧面 4~7 格的空位，
 *       <b>瞬移路径上的敌人挨一下挥扫</b>（15 点，白帽的伤害类型；主人/盟友/玩家不算），
 *       这一下本身完全免疫；用完按普通生物结算。另有<b>一次性的保命闪避</b>：
 *       闪避次数用完之后，接下「会把它打死」的那一下时额外瞬移闪避一次
 *       —— 整辈子只有这么一回，用掉就没了（同样带路径挥扫）。</li>
 * </ul>
 *
 * <p>开火节奏完全由主人定：主人进照射期的每一刻它都同步开火，主人收手它也收手。
 * 一轮总伤固定 950（照射期内平摊，多个目标就平摊到目标头上；
 * 没有「当前生命百分比」那一层 —— 那样总伤就没边了）。
 *
 * <p>渲染和 {@code TangJiaqiRenderer} 一样是面向相机的平面贴图，
 * 待机 / 发射各一张（见 {@code client/TangJiaqiWhiteRenderer}）。
 */
public class TangJiaqiWhite extends PathfinderMob implements OwnableEntity {

    /** 召唤者 UUID。 */
    private static final EntityDataAccessor<Optional<UUID>> DATA_OWNER =
            SynchedEntityData.defineId(TangJiaqiWhite.class, EntityDataSerializers.OPTIONAL_UUID);
    /** 这一刻是不是在放光柱（客户端拿它切换待机 / 发射贴图）。 */
    private static final EntityDataAccessor<Boolean> DATA_BEAMING =
            SynchedEntityData.defineId(TangJiaqiWhite.class, EntityDataSerializers.BOOLEAN);

    /** 一道光柱一整轮照射的总伤害预算（两道各算各的；照射刻数以调参器里白帽的为准）。 */
    public static final float LANE_DAMAGE = 475.0F;

    /** 闪避瞬移时，路径上每个敌人挨的挥扫伤害。 */
    public static final float DODGE_PATH_DAMAGE = 15.0F;

    /** 两道肩部光柱的起点：贴图上两个红圈的位置（脚底往上 0.78 格 / 中心左右各 0.6 格）。 */
    public static final float SHOULDER_HEIGHT_RATIO = 0.78F / 2.2F;
    public static final double SHOULDER_LATERAL = 0.6D;

    /** 一共能闪几次（用完就正常挨打）。 */
    public static final int DODGE_CHARGES = 6;

    /** 光柱道数：同白帽的 2 道。 */
    private static final int BEAM_LANES = HatAbilities.BASE_BEAM_COUNT;

    /** 跟随时离主人多近就停下（格）。 */
    private static final double FOLLOW_STOP = 3.0D;
    /** 每刻最多挪多远（格）。 */
    private static final double FOLLOW_SPEED = 0.30D;
    /** 超过这个距离直接传送到主人身边（格）。 */
    private static final double FOLLOW_TELEPORT = 28.0D;

    /** 随机游走：多久换一个漫游点（刻）。 */
    private static final int WANDER_PERIOD = 40;
    /** 随机游走：绕主人多大一圈（格）。 */
    private static final double WANDER_RADIUS = 5.0D;

    /** 主人的链路已经没了：连续这么久就走人（摘帽 / 曲终 / 死亡都算）。 */
    private static final int ORPHAN_GRACE_TICKS = 40;

    /** 瞬移距离整体放大（原来 4~7 格，乘 1.5 = 6~10.5 格）。 */
    private static final double BLINK_DISTANCE_SCALE = 1.5D;

    /** 珍珠一直没碎时的兜底：最多等这么多刻就让她在终点重现。 */
    private static final int BLINK_WAIT_TICKS = 20;

    /** 野生循环等客户端「BGM 响起来了」回执的兜底（刻）。 */
    private static final int WILD_MUSIC_ACK_TIMEOUT = 60;

    /** 残影珍珠最多留几刻：位移线一闪就没，几乎瞬间消失（主珍珠另算）。 */
    private static final int GHOST_PEARL_TTL = 3;

    /** 还剩几次闪避。 */
    private int dodges = DODGE_CHARGES;
    /** 保命闪避用掉了没有（整辈子一次，存档）。 */
    private boolean lethalDodgeUsed;
    /** 「链路已经没了」连续出现了几刻。 */
    private int orphanTicks;
    /** 随机游走的当前目标点（主人坐标系外的世界坐标）。 */
    private double wanderX;
    private double wanderZ;
    /** 离下次换漫游点还有几刻。 */
    private int wanderCountdown;
    /** 上一次播闪避音效的服务器刻（连击时别刷屏）。 */
    private long lastDodgeSound = Long.MIN_VALUE / 2;

    // ------------------------------------------------------------------
    // 野生个体（刷怪蛋放出来的）
    // ------------------------------------------------------------------

    /** 刷怪蛋出来的「野生」个体：不认主，自己跑白帽那套蓄力 -> 照射循环。 */
    private boolean wild;
    /** 野生个体的活动中心（刷出来的地方）。 */
    private double homeX;
    private double homeY;
    private double homeZ;
    /** 野生循环：这一轮还剩多少蓄力 / 照射刻。 */
    private int cycleCharge;
    private int cycleFlash;
    /** 野生循环起过头没有（第一次有敌人时才起第一轮）。 */
    private boolean cycleStarted;
    /** 野生循环在等客户端回执「白帽 BGM 真的响起来了」：等到之前蓄力不倒数。 */
    private boolean wildMusicPending;
    /** 已经等了几刻（兜底）。 */
    private int wildMusicWait;

    /** 自己放出去的珍珠 -> 还剩几刻就强制收掉（防止有珍珠永远飘在半空）。 */
    private final java.util.Map<Integer, Integer> spawnedPearls = new java.util.HashMap<>();

    // ------------------------------------------------------------------
    // 瞬移演出（受伤 -> 隐身 -> 珍珠飞 -> 珍珠碎 -> 在终点重现）
    // ------------------------------------------------------------------

    /** 瞬移演出进行中：贴图藏着，等珍珠碎掉再出现在终点。 */
    private boolean blinkPending;
    /** 这次演出是不是那唯一一次的保命闪避（落点额外一发爆炸）。 */
    private boolean blinkLethal;
    /** 还没落地时的保险：最多等这么多刻。 */
    private int blinkWait;
    /** 主珍珠的实体 id（-1 = 没有）。 */
    private int blinkPearl = -1;
    private double blinkX;
    private double blinkY;
    private double blinkZ;

    public TangJiaqiWhite(EntityType<? extends TangJiaqiWhite> type, Level level) {
        super(type, level);
        // 和 Teto / 唐嘉琦（1）一样是「每刻直接摆位」的贴图生物：不走碰撞、不受重力
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 1000.0D)
                .add(Attributes.ARMOR, 0.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0D)
                .add(Attributes.FOLLOW_RANGE, 32.0D);
    }

    /** 召唤：认主、关掉自带 AI（移动完全由 tick 接管）。 */
    void init(LivingEntity owner) {
        setOwnerUUID(owner.getUUID());
        this.wild = false;
        this.setNoAi(true);
        this.setPersistenceRequired();
        this.dodges = DODGE_CHARGES;
        this.lethalDodgeUsed = false;
        this.orphanTicks = 0;
        this.wanderCountdown = 0;
        this.entityData.set(DATA_BEAMING, false);
    }

    /**
     * 刷怪蛋放出来的野生个体：不认主，自己跑白帽的蓄力 / 照射循环。
     *
     * <p>循环节奏、伤害、索敌全和白帽一样（见 {@link #tickWild}），每照完一轮闪避补满 6 次。
     */
    void initStandalone() {
        this.wild = true;
        this.setNoAi(true);
        this.setPersistenceRequired();
        this.dodges = DODGE_CHARGES;
        this.lethalDodgeUsed = false;
        this.orphanTicks = 0;
        this.wanderCountdown = 0;
        this.homeX = this.getX();
        this.homeY = this.getY();
        this.homeZ = this.getZ();
        this.cycleCharge = 0;
        this.cycleFlash = 0;
        this.cycleStarted = false;
        this.entityData.set(DATA_BEAMING, false);
    }

    /**
     * 刷怪蛋生成走这里：把自己切成「野生」个体。
     *
     * <p>用 {@code /summon} 出来的没有这一步 —— 那本来就不是给玩家用的入口。
     */
    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty,
                                        MobSpawnType reason, @Nullable SpawnGroupData spawnData) {
        SpawnGroupData result = super.finalizeSpawn(level, difficulty, reason, spawnData);
        initStandalone();
        return result;
    }

    // ------------------------------------------------------------------

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_OWNER, Optional.empty());
        builder.define(DATA_BEAMING, false);
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

    /** 客户端：现在是不是在放光柱（贴图切换）。 */
    public boolean isBeaming() {
        return this.entityData.get(DATA_BEAMING);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        UUID owner = getOwnerUUID();
        if (owner != null) {
            tag.putUUID("Owner", owner);
        }
        tag.putInt("Dodges", this.dodges);
        tag.putBoolean("LethalDodge", this.lethalDodgeUsed);
        tag.putBoolean("Wild", this.wild);
        tag.putDouble("HomeX", this.homeX);
        tag.putDouble("HomeY", this.homeY);
        tag.putDouble("HomeZ", this.homeZ);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID("Owner")) {
            setOwnerUUID(tag.getUUID("Owner"));
        }
        this.dodges = tag.contains("Dodges") ? tag.getInt("Dodges") : DODGE_CHARGES;
        this.lethalDodgeUsed = tag.getBoolean("LethalDodge");
        this.wild = tag.getBoolean("Wild");
        if (this.wild) {
            this.homeX = tag.getDouble("HomeX");
            this.homeY = tag.getDouble("HomeY");
            this.homeZ = tag.getDouble("HomeZ");
            this.cycleCharge = 0;
            this.cycleFlash = 0;
            this.cycleStarted = false;
        }
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
    // 名册（和唐嘉琦（1）一样按 UUID 查，跨维度、不经过实体查询）
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

    /** 某个人名下还活着的白色唐嘉琦。 */
    static List<TangJiaqiWhite> ownedBy(LivingEntity owner) {
        List<TangJiaqiWhite> found = new ArrayList<>();
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
            TangJiaqiWhite alive = null;
            for (ServerLevel level : levels) {
                if (level.getEntity(entry.getKey()) instanceof TangJiaqiWhite white && !white.isRemoved()) {
                    alive = white;
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

    /** 名下有没有白色唐嘉琦（链路起手时用来避免重复召唤 —— 每人同时只有一只）。 */
    static boolean anyOwnedBy(LivingEntity owner) {
        return !ownedBy(owner).isEmpty();
    }

    // ------------------------------------------------------------------
    // 友方判定：和唐嘉琦（1）/ Teto 同一条路子
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
        // 先把自己放出去的那些珍珠看住：到点（或已经不在）就收掉，绝不让它们飘在半空
        this.tickSpawnedPearls(level);
        // 瞬移演出中：贴图已经藏起来、珍珠在飞 —— 什么都不做，等珍珠碎掉
        if (this.blinkPending) {
            tickBlink(level);
            return;
        }
        // 刷怪蛋放出来的野生个体：不认主，自己跑白帽的循环
        if (this.wild) {
            tickWild(level);
            return;
        }
        LivingEntity owner = ownerEntity(level);
        if (owner == null) {
            disappear(level, "主人已不在这个世界");
            return;
        }
        if (owner.isDeadOrDying() || owner.isSpectator()) {
            disappear(level, "主人已经死亡");
            return;
        }
        // 主人的白帽链路收场（曲终 / 摘帽 / 换帽）：它不是散养宠物，跟着一起散
        if (!ownerChainAlive(owner)) {
            stopBeams();
            if (++this.orphanTicks >= ORPHAN_GRACE_TICKS) {
                disappear(level, "白帽链路已经收场");
                return;
            }
            follow(level, owner);
            return;
        }
        this.orphanTicks = 0;

        // 开火节奏完全跟主人：主人照射它才照射（同一刻开、同一刻收）
        if (HatAbilities.isFlashing(owner)) {
            fireBeams(level, owner.getX(), owner.getZ(), owner.getY());
            return;
        }
        stopBeams();
        // 主人在蓄力 / 走位：随机游走，把自己演成背景板
        wander(level, owner);
    }

    // ------------------------------------------------------------------
    // 野生个体：自己的白帽循环
    // ------------------------------------------------------------------

    /**
     * 野生个体的一刻：**攻击节奏和白帽一模一样** —— 蓄力 364 刻、照射 129 刻，
     * 每轮开场放白帽 BGM；每照完一轮，闪避重新补满 6 次。
     *
     * <p>关键：这个「刻」是**按真实时间补的**（{@link HatAbilities#musicSteps}），
     * 不是游戏刻。BGM 按真实时间播，整合包跑不满 20 TPS 时，364 个游戏刻的实际耗时
     * 会比 18.2 秒长得多 —— 那时光柱就永远晚于音乐，怎么调数值都对不上。
     *
     * <p>没敌人在附近就待机（不放音乐），等有敌人了再起新一轮。
     */
    private void tickWild(ServerLevel level) {
        if (!this.cycleStarted) {
            this.cycleStarted = true;
            this.cycleCharge = 0;
            this.cycleFlash = 0;
        }
        // 等客户端确认这一轮的白帽 BGM 真的响起来了，蓄力才开始倒数
        if (this.wildMusicPending) {
            if (++this.wildMusicWait > WILD_MUSIC_ACK_TIMEOUT) {
                this.wildMusicPending = false;
            }
            return;
        }
        int steps = HatAbilities.musicSteps(this.getUUID());
        for (int i = 0; i < steps; i++) {
            if (this.wildMusicPending) {
                break;      // 刚起了一轮、等音乐回执：剩下的步数不走了
            }
            wildStep(level);
        }
    }

    /** 野生循环走一刻（按真实时间可能要一次补好几刻）。 */
    private void wildStep(ServerLevel level) {
        if (this.cycleFlash > 0) {
            fireBeams(level, this.homeX, this.homeZ, this.homeY);
            if (--this.cycleFlash <= 0) {
                stopBeams();
                HatMusic.stop(level, this, HatType.WHITE);
                // 每释放完一次光束：闪避重新补满
                this.dodges = DODGE_CHARGES;
                // 附近还有敌人才接着蓄力；没有了就待机（下一次看到敌人再起一轮）
                if (HatAbilities.findNearestEnemy(this) != null) {
                    startWildCharge(level, true);
                }
            }
            return;
        }
        if (this.cycleCharge > 0) {
            this.wander(level, this.homeX, this.homeZ, this.homeY);
            if (--this.cycleCharge <= 0) {
                this.cycleFlash = Math.max(1, HatSettings.flashTicks(HatType.WHITE));
                level.playSound(null, this.getX(), this.getY(), this.getZ(),
                        SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 0.7F, 1.6F);
            }
            return;
        }
        // 空闲：原地绕一绕，有敌人了才起新一轮
        this.wander(level, this.homeX, this.homeZ, this.homeY);
        if (HatAbilities.findNearestEnemy(this) != null) {
            startWildCharge(level, true);
        }
    }

    /** 开一轮蓄力（可选放音乐）。照射结束 / 发现敌人时调用。 */
    private void startWildCharge(ServerLevel level, boolean withMusic) {
        this.cycleFlash = 0;
        this.cycleCharge = Math.max(1, HatSettings.chargeTicks(HatType.WHITE));
        if (withMusic) {
            HatMusic.play(level, this, HatType.WHITE);
            // 等客户端回执「这一首真的响起来了」再倒数（见 tickWild / noteMusicStarted）
            this.wildMusicPending = true;
            this.wildMusicWait = 0;
        }
    }

    /** 客户端回执：这一轮的白帽 BGM 真的响起来了（由 {@link HatAbilities#noteMusicStarted} 转发）。 */
    void noteMusicStarted() {
        this.wildMusicPending = false;
        this.wildMusicWait = 0;
    }

    // ------------------------------------------------------------------
    // 瞬移演出
    // ------------------------------------------------------------------

    /**
     * 瞬移演出的一刻：珍珠还在飞就等着；珍珠碎掉（或者等太久）就把贴图放回来、落到终点。
     */
    private void tickBlink(ServerLevel level) {
        Entity pearl = this.blinkPearl >= 0 ? level.getEntity(this.blinkPearl) : null;
        boolean gone = pearl == null || pearl.isRemoved();
        if (!gone && --this.blinkWait > 0) {
            return;
        }
        this.blinkPending = false;
        this.blinkPearl = -1;
        this.moveTo(this.blinkX, this.blinkY, this.blinkZ, this.getYRot(), 0.0F);        this.setInvisible(false);
        Vec3 pos = this.position().add(0.0D, this.getBbHeight() * 0.6D, 0.0D);
        level.sendParticles(ParticleTypes.END_ROD, pos.x, pos.y, pos.z,
                10, 0.25D, 0.35D, 0.25D, 0.02D);
        level.sendParticles(ParticleTypes.PORTAL, pos.x, pos.y, pos.z,
                18, 0.3D, 0.4D, 0.3D, 0.25D);
        if (this.blinkLethal) {
            level.sendParticles(ParticleTypes.EXPLOSION, pos.x, pos.y, pos.z,
                    1, 0.0D, 0.0D, 0.0D, 0.0D);
            this.blinkLethal = false;
        }
    }

    /**
     * 照射期的一刻：两道光柱各锁一个最近的敌人（索敌同白帽），玩家永远不打。
     *
     * <p>每道光柱一整轮总伤 {@link #LANE_DAMAGE}（475）：照射期平摊，这一刻分到
     * 「475 ÷ 照射刻数」。两道各锁一个目标，各算各的 —— 不再按目标数摊薄。
     * 没有「当前生命百分比」那一层 —— 那样总伤就没边了。
     */
    private void fireBeams(ServerLevel level, double anchorX, double anchorZ, double anchorY) {
        List<LivingEntity> targets = new ArrayList<>();
        for (LivingEntity candidate : HatAbilities.findNearestEnemies(this, BEAM_LANES)) {
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
        this.entityData.set(DATA_BEAMING, true);
        // 放光柱也照常走位（不站桩）：脸朝第一个目标，脚底下继续绕着自己那一圈溜达
        this.moveWander(level, anchorX, anchorZ, anchorY);
        if (!targets.isEmpty()) {
            LivingEntity face = targets.get(0);
            this.faceTowards(face.getX(), face.getZ());
        }
        if (targets.isEmpty()) {
            return;
        }
        DamageSource source = HatSettings.damageSource(level, this, HatType.WHITE);
        float share = LANE_DAMAGE / Math.max(1, HatSettings.flashTicks(HatType.WHITE));
        for (LivingEntity target : targets) {
            HatAbilities.damageBeamTick(level, this, HatType.WHITE, source, share, 0.0F, true, target);
        }
    }

    /** 收光柱 + 蓄力作废（链路没了时用），只在状态变化时发包。 */
    private void stopBeams() {
        if (!this.entityData.get(DATA_BEAMING)) {
            return;
        }
        this.entityData.set(DATA_BEAMING, false);
        if (this.level() instanceof ServerLevel level) {
            HatNetwork.sendBeamTarget(this, false, new int[0], 0);
        }
    }

    // ------------------------------------------------------------------
    // 移动
    // ------------------------------------------------------------------

    /** 蓄力期的随机游走：绕着主人随便溜达（每 WANDER_PERIOD 刻换一个点）。 */
    private void wander(ServerLevel level, LivingEntity owner) {
        wander(level, owner.getX(), owner.getZ(), owner.getY());
    }

    private void moveWander(ServerLevel level, LivingEntity owner) {
        moveWander(level, owner.getX(), owner.getZ(), owner.getY());
    }

    /** 绕某个中心点（主人 / 野生个体的老家）溜达。 */
    private void wander(ServerLevel level, double cx, double cz, double cy) {
        moveWander(level, cx, cz, cy);
        faceTowards(this.wanderX, this.wanderZ);
    }

    /**
     * 只挪脚、不改朝向：给「照射期也要走位」用 —— 身体在动，脸还得对着目标。
     */
    private void moveWander(ServerLevel level, double cx, double cz, double cy) {
        if (--this.wanderCountdown <= 0
                || this.distanceToSqr(this.wanderX, this.getY(), this.wanderZ) < 0.25D) {
            this.wanderCountdown = WANDER_PERIOD;
            double angle = Math.PI * 2.0D * this.random.nextDouble();
            double dist = 1.5D + this.random.nextDouble() * (WANDER_RADIUS - 1.5D);
            this.wanderX = cx + Math.cos(angle) * dist;
            this.wanderZ = cz + Math.sin(angle) * dist;
        }
        stepToward(this.wanderX, cy, this.wanderZ, 0.4D);
    }

    /** 贴身的跟随：站在主人左手边（右边是唐嘉琦（1）的位子），超过 28 格直接传送。 */
    private void follow(ServerLevel level, LivingEntity owner) {
        double distance = this.distanceTo(owner);
        if (distance > FOLLOW_TELEPORT) {
            double yaw = Math.toRadians(owner.getYRot());
            double side = -1.4D;
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
        double tx = owner.getX() - Math.sin(yaw) * 1.8D - Math.cos(yaw) * 0.9D;
        double tz = owner.getZ() + Math.cos(yaw) * 1.8D - Math.sin(yaw) * 0.9D;
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

    // ------------------------------------------------------------------
    // 主人的链路还活着吗
    // ------------------------------------------------------------------

    /**
     * 主人头上还是白帽 / 「全」，而且他的蓄力 / 照射还有一个在跑。
     * 摘帽、换成别的帽、曲终收场，都从这里看出来。
     *
     * <p>帽子**不一定在头槽**：这个包里帽子的「戴着」一直是
     * {@link HatAbilities#hatStack}（头槽没有就看饰品栏 curios）。以前这里直接读头槽，
     * 帽子在饰品栏的玩家一召唤出来 2 秒就被判成「链路收场」散场 —— 现在跟链路同一条判据。
     */
    private boolean ownerChainAlive(LivingEntity owner) {
        HatType type = HatItems.typeOf(HatAbilities.hatStack(owner));
        if (type != HatType.WHITE && type != HatType.ALL) {
            return false;
        }
        return HatAbilities.chainAlive(owner);
    }

    // ------------------------------------------------------------------
    // 闪避：免疫这一下（不反弹 —— 那是唐嘉琦（1）的戏份）
    // ------------------------------------------------------------------

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.isRemoved() || amount <= 0.0F) {
            return false;
        }
        if (this.level().isClientSide) {
            return false;
        }
        // 瞬移演出中：人已经「不在」了，这一下彻底落空，也不扣闪避
        if (this.blinkPending) {
            return false;
        }
        ServerLevel level = (ServerLevel) this.level();
        Entity attacker = source.getEntity();
        LivingEntity owner = this.getOwner();
        // 自己人（主人和主人的盟友）打它：不扣闪避、不掉血 —— 同伴不是训练桩
        boolean friendly = attacker instanceof LivingEntity living
                && living != this
                && (owner != null && (living == owner || HatAbilities.isFriendlyTo(owner, living)));
        if (friendly) {
            return false;
        }
        if (this.dodges > 0) {
            this.dodges--;
            this.beginBlink(level, source, false);
            return false;                       // 这一下完全免疫
        }
        // 闪避用完之后的保命：这一下会把它打死 -> 额外瞬移闪避一次，整辈子只有一回
        if (!this.lethalDodgeUsed && amount >= this.getHealth()) {
            this.lethalDodgeUsed = true;
            this.beginBlink(level, source, true);
            HatMod.LOGGER.info("[hatmod] 唐嘉琦（白色）用掉了保命闪避（剩余闪避 0）");
            return false;                       // 致命一击落空
        }
        return super.hurt(source, amount);
    }

    // ------------------------------------------------------------------
    // 闪避瞬移：挨打 -> 贴图消失 -> 珍珠飞到终点 -> 珍珠碎了 -> 贴图在终点重现
    // ------------------------------------------------------------------

    /**
     * 闪避的第一帧：贴图当场消失、人「不在原地」，同时放出一颗珍珠飞向终点。
     *
     * <p>真正挪过去要等珍珠落地（见 {@link #tickBlink}）：这一下本身已经免疫，
     * 所以对伤害结算来说她仍然是「闪掉了」，只是演出上把位移做成了「珍珠飞过去」。
     * 路径上的敌人照例挨一下挥扫（主人 / 盟友 / 玩家不算）。
     */
    private void beginBlink(ServerLevel level, DamageSource source, boolean lethal) {
        Vec3 from = this.position();
        Vec3 spot = pickBlinkSpot(source.getEntity());
        if (spot == null) {
            // 周围没有能站的地方：不挪了，原地照旧免伤（不然会隐身站着等 1 秒）
            Vec3 at = from.add(0.0D, this.getBbHeight() * 0.5D, 0.0D);
            level.sendParticles(ParticleTypes.CLOUD, at.x, at.y, at.z,
                    12, 0.3D, 0.35D, 0.3D, 0.05D);
            level.playSound(null, this.getX(), this.getY(), this.getZ(),
                    SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 0.55F, 1.6F);
            return;
        }
        sweepPath(level, from, spot);
        // 1) 受伤的这一刻：贴图当场消失
        this.setInvisible(true);
        stopBeams();
        // 2) 一颗珍珠从起点飞向终点（沿途还有一串残影）
        this.blinkPearl = launchPearl(level, from, spot);
        // 3) 记下终点，等珍珠碎掉再出现
        this.blinkPending = true;
        this.blinkLethal = lethal;
        this.blinkWait = BLINK_WAIT_TICKS;
        this.blinkX = spot.x;
        this.blinkY = spot.y;
        this.blinkZ = spot.z;
        // 起点的烟和火花（人是在这里消失的）
        Vec3 start = from.add(0.0D, this.getBbHeight() * 0.45D, 0.0D);
        level.sendParticles(ParticleTypes.CLOUD, start.x, start.y, start.z,
                12, 0.3D, 0.35D, 0.3D, 0.05D);
        level.sendParticles(ParticleTypes.PORTAL, start.x, start.y, start.z,
                24, 0.35D, 0.5D, 0.35D, 0.2D);
        long now = level.getGameTime();
        if (now - this.lastDodgeSound >= 4L) {
            this.lastDodgeSound = now;
            level.playSound(null, this.getX(), this.getY(), this.getZ(),
                    SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE,
                    lethal ? 1.0F : 0.55F, lethal ? 0.6F : 1.6F);
        }
    }

    /**
     * 放珍珠：一颗主珍珠 4 刻从起点飞到终点（贴图消失的那段时间就是它飞的时间），
     * 沿途再撒 9 颗原版末影珍珠残影，各自落到地上碎掉。
     *
     * <p>珍珠都没认主，碎了不会把任何人传送走。
     *
     * @return 主珍珠的实体 id（等它碎掉/超时后让她在终点重现）
     */
    private int launchPearl(ServerLevel level, Vec3 from, Vec3 to) {
        double height = this.getBbHeight() * 0.45D;
        Vec3 eye = new Vec3(from.x, from.y + height, from.z);
        Vec3 aim = new Vec3(to.x, to.y + 0.12D, to.z);
        Vec3 dash = aim.subtract(eye);

        ThrownEnderpearl pearl = new ThrownEnderpearl(EntityType.ENDER_PEARL, level);
        pearl.setPos(eye.x, eye.y, eye.z);
        pearl.setNoGravity(true);
        pearl.setDeltaMovement(dash.scale(1.0D / 4.0D));   // 4 刻飞到终点
        level.addFreshEntity(pearl);
        this.spawnedPearls.put(pearl.getId(), BLINK_WAIT_TICKS + 2);

        for (int i = 0; i < 9; i++) {
            double t = 0.15D + 0.85D * i / 8.0D;
            Vec3 at = eye.add(dash.scale(t));
            // 残影只活 3 刻：位移线一闪就没。速度随意，反正几乎立刻收掉
            spawnPearl(level, at,
                    (this.random.nextDouble() - 0.5D) * 0.03D,
                    -0.16D - this.random.nextDouble() * 0.06D,
                    (this.random.nextDouble() - 0.5D) * 0.03D,
                    GHOST_PEARL_TTL);
        }
        return pearl.getId();
    }

    /** 放一颗不带主人的末影珍珠（原版实体、原版贴图），并登记它最多活多久。 */
    private void spawnPearl(ServerLevel level, Vec3 at, double vx, double vy, double vz, int ttl) {
        ThrownEnderpearl pearl = new ThrownEnderpearl(EntityType.ENDER_PEARL, level);
        pearl.setPos(at.x, at.y, at.z);
        pearl.setDeltaMovement(vx, vy, vz);
        level.addFreshEntity(pearl);
        this.spawnedPearls.put(pearl.getId(), ttl);
    }

    /**
     * 每刻看住自己放出去的珍珠：已经不在世界的划掉；活到 TTL 还没碎的**强制收掉**。
     *
     * <p>没有这一道，个别残影会卡在半空慢慢飘 —— 它们没撞到东西，原版就不会自己消失。
     */
    private void tickSpawnedPearls(ServerLevel level) {
        if (this.spawnedPearls.isEmpty()) {
            return;
        }
        java.util.Iterator<java.util.Map.Entry<Integer, Integer>> iterator =
                this.spawnedPearls.entrySet().iterator();
        while (iterator.hasNext()) {
            java.util.Map.Entry<Integer, Integer> entry = iterator.next();
            Entity pearl = level.getEntity(entry.getKey());
            if (pearl == null || pearl.isRemoved()) {
                iterator.remove();
                continue;
            }
            int left = entry.getValue() - 1;
            if (left <= 0) {
                pearl.discard();
                iterator.remove();
            } else {
                entry.setValue(left);
            }
        }
    }

    /** 挑瞬移落点：优先背对出手者，6~10.5 格（原来的 1.5 倍），要头脚都是空的。 */
    @Nullable
    private Vec3 pickBlinkSpot(@Nullable Entity attacker) {
        double baseAngle = attacker != null
                ? Math.atan2(this.getZ() - attacker.getZ(), this.getX() - attacker.getX())
                : Math.PI * 2.0D * this.random.nextDouble();
        for (int i = 0; i < 8; i++) {
            double angle = baseAngle + (this.random.nextDouble() - 0.5D) * Math.PI;
            double dist = (4.0D + this.random.nextDouble() * 3.0D) * BLINK_DISTANCE_SCALE;
            double x = this.getX() + Math.cos(angle) * dist;
            double z = this.getZ() + Math.sin(angle) * dist;
            BlockPos at = BlockPos.containing(x, this.getY(), z);
            if (this.level().isEmptyBlock(at) && this.level().isEmptyBlock(at.above())) {
                return new Vec3(x, this.getY(), z);
            }
        }
        return null;
    }

    /** 瞬移走廊（旧位置 -> 新位置，往外扩一点）里的每个敌人都挨一下挥扫。 */
    private void sweepPath(ServerLevel level, Vec3 from, Vec3 to) {
        AABB corridor = new AABB(from, to).inflate(1.2D, 1.0D, 1.2D);
        DamageSource sweep = HatSettings.damageSource(level, this, HatType.WHITE);
        for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, corridor)) {
            if (victim == this || !victim.isAlive() || victim.isRemoved()
                    || victim instanceof Player
                    || !HatAbilities.isEnemy(victim, this)) {
                continue;
            }
            victim.hurt(sweep, DODGE_PATH_DAMAGE);
        }
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
        stopBeams();
        HatMusic.stop(level, this, HatType.WHITE);
        HatMod.LOGGER.info("[hatmod] 唐嘉琦（白色）退场（{}，剩余闪避 {}，保命闪避{}）",
                why, this.dodges, this.lethalDodgeUsed ? "已用" : "未用");
        level.sendParticles(ParticleTypes.CLOUD,
                this.getX(), this.getY() + this.getBbHeight() * 0.5D, this.getZ(),
                16, 0.35D, 0.7D, 0.35D, 0.03D);
        rememberOwner(this.getUUID(), null);
        this.hatmodSelfRemoval = true;
        this.discard();
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        if (this.level() instanceof ServerLevel level) {
            HatMusic.stop(level, this, HatType.WHITE);
        }
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
