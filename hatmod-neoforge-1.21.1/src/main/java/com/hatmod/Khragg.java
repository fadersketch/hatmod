package com.hatmod;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 「Khragg」—— 开发者的化身，做彩蛋真好玩。
 *
 * <p>只能用刷怪蛋 / 指令生成，生成和真正死亡都会走原版的
 * 「加入了游戏 / 离开了游戏」系统消息。
 *
 * <p>流程：平时中立站着；被玩家打了就激怒（头顶那顶满附魔「全」帽把动手的玩家
 * 设为敌人，{@link HatAbilities} 的状态机会自动对他跑完整套蓄力 / 光柱 / 虚影）；
 * 打死玩家嘲一句；自己第一次死掉，死亡动画放完原地满血复活并换上 Teto 帽、
 * 节拍由服务端代打（全程满连击）；第二次被玩家打死才是真死亡。
 */
public class Khragg extends PathfinderMob {

    public static final String KEY_PROVOKE = "hatmod.khragg.provoke";
    public static final String KEY_WIN = "hatmod.khragg.win";
    public static final String KEY_REVIVE = "hatmod.khragg.revive";
    public static final String KEY_DEFEATED = "hatmod.khragg.defeated";

    private static final String TAG_ANGER = "AngerTarget";
    private static final String TAG_REVIVED = "Revived";
    private static final String TAG_EQUIPPED = "Equipped";
    private static final String TAG_ANNOUNCED = "Announced";
    private static final String TAG_HOME = "Home";

    /** 台词的收听半径（格）。 */
    private static final double TALK_RANGE = 48.0D;

    @Nullable
    private UUID angerTarget;
    private boolean revived;
    private boolean equipped;
    private boolean announced;

    // ---------------- AI 的状态（不存档的活数据） ----------------

    /** 出生点：脱战之后自己走回来，不然打完一架人就找不着了。 */
    @Nullable
    private BlockPos home;
    /** 打过他的所有东西：UUID -> 最后一次挨打的刻。只记 30 秒。 */
    private final Map<UUID, Long> aggressors = new HashMap<>();
    /** 最近一次「有活着的目标」的刻：判脱战（20 秒）。 */
    private long lastCombatTick = -1L;
    /** 追击卡住检测：10 刻一量，连续 4 次没挪窝就重寻路 / 传送。 */
    private int stuckTicks;
    private double lastX;
    private double lastZ;
    /** 突进 / 跳劈的冷却刻。 */
    private long nextDash;
    private long nextSlam;

    public Khragg(EntityType<? extends Khragg> type, Level level) {
        super(type, level);
        if (!level.isClientSide) {
            this.equipped = true;
            equipStageOne();
        }
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.ARMOR, 30.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.25D)
                .add(Attributes.ATTACK_DAMAGE, 8.0D)
                .add(Attributes.FOLLOW_RANGE, 64.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0D);
    }

    @Override
    protected void registerGoals() {
        // 1) 蓄力期走位：不是瞎跑 —— 挑「站得住、刚好在射程里、中间没遮挡」的落点，
        //    到位就站定摆姿势，把蓄力演完
        this.goalSelector.addGoal(1, new RetreatGoal());
        // 2) 他自己的两招：拉远了突进；近身 / 被围住跳劈横扫
        this.goalSelector.addGoal(2, new DashGoal());
        this.goalSelector.addGoal(2, new SlamGoal());
        this.goalSelector.addGoal(3, new MeleeAttackGoal(this, 1.05D, true));
        // 3) 打完了自己走回出生点
        this.goalSelector.addGoal(6, new ReturnHomeGoal());
        this.goalSelector.addGoal(7, new RandomStrollGoal(this, 0.6D, 60));
        this.goalSelector.addGoal(9, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(10, new RandomLookAroundGoal(this));
        // 中立生物同款索敌：谁打我就追谁。
        // 例外：Promaid 的「枪弹天使」。她把他打了也不还手 —— 她不是他的敌人，
        // 帽子那边（HatAbilities#isEnemy）早就把她排除了，这里把最后一条路也堵死。
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this) {
            @Override
            public boolean canUse() {
                if (Teto.isBulletAngel(Khragg.this.getLastHurtByMob())) {
                    return false;
                }
                return super.canUse();
            }
        });
    }

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

    // ------------------------------------------------------------------
    // 装备与开场
    // ------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        if (!(this.level() instanceof ServerLevel level)) {
            return;
        }
        if (!this.announced) {
            this.announced = true;
            this.setPersistenceRequired();
            announce(true);
        }
        if (!this.equipped || this.getMainHandItem().isEmpty()) {
            this.equipped = true;
            equipStageOne();
        }
        long now = level.getGameTime();
        if (this.home == null) {
            this.home = this.blockPosition();
        }
        // 多目标：每 10 刻按「谁近 / 谁刚打我 / 玩家优先」重挑一次。
        // 以前是「最后一个打我的人」独占仇恨 —— 另一个人贴脸打他都没反应，
        // 而且不管谁在跑远，只会追着那一个不放。
        if (now % 10L == 0L) {
            this.chooseTarget(level, now);
        }
        Player target = angerPlayer(level);
        if (target != null) {
            this.setTarget(target);
            this.lastCombatTick = now;
        } else if (this.angerTarget != null && now - this.lastCombatTick > 20L * 20L) {
            // 目标死了 / 下线 / 换维度了：20 秒没交手就彻底脱战，走回出生点
            this.angerTarget = null;
            this.setTarget(null);
        }
        // 目标已经躺下：别对着尸体挥剑
        if (this.getTarget() != null && !this.getTarget().isAlive()) {
            this.setTarget(null);
        }
        // 兜底：枪弹天使要是从别的路（旧存档、别的模组）被设成了目标，一律清掉
        if (this.getTarget() != null && Teto.isBulletAngel(this.getTarget())) {
            this.setTarget(null);
        }
        // 追击兜底：目标还在，但追不上（太远 / 原地打转）—— 重寻路，再不行闪过去
        if (target != null && now % 10L == 0L) {
            this.chaseFallback(level, target);
        }
    }

    private void enchantSword(ItemStack sword,
                              net.minecraft.resources.ResourceKey<Enchantment> key, int level) {
        sword.enchant(this.level().registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key), level);
    }

    private void equipStageOne() {
        ItemStack sword = new ItemStack(Items.NETHERITE_SWORD);
        enchantSword(sword, Enchantments.SHARPNESS, 5);
        enchantSword(sword, Enchantments.KNOCKBACK, 2);
        enchantSword(sword, Enchantments.FIRE_ASPECT, 2);
        enchantSword(sword, Enchantments.LOOTING, 3);
        enchantSword(sword, Enchantments.SWEEPING_EDGE, 3);
        enchantSword(sword, Enchantments.UNBREAKING, 3);
        enchantSword(sword, Enchantments.MENDING, 1);
        this.setItemSlot(EquipmentSlot.MAINHAND, sword);
        this.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
        ItemStack hat = new ItemStack(HatItems.ALL_HAT.get());
        for (HatEnchants.Kind kind : HatEnchants.Kind.values()) {
            hat.enchant(this.level().registryAccess()
                    .lookupOrThrow(Registries.ENCHANTMENT)
                    .getOrThrow(kind.key()), HatEnchants.MAX_LEVEL);
        }
        this.setItemSlot(EquipmentSlot.HEAD, hat);
        this.setDropChance(EquipmentSlot.HEAD, 0.0F);
        dyeBlack(EquipmentSlot.CHEST, Items.LEATHER_CHESTPLATE);
        dyeBlack(EquipmentSlot.LEGS, Items.LEATHER_LEGGINGS);
        dyeBlack(EquipmentSlot.FEET, Items.LEATHER_BOOTS);
    }

    private void dyeBlack(EquipmentSlot slot, Item item) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.DYED_COLOR, new DyedItemColor(0x14141A, false));
        this.setItemSlot(slot, stack);
        this.setDropChance(slot, 0.0F);
    }

    // ------------------------------------------------------------------
    // 激怒
    // ------------------------------------------------------------------

    @Override
    public boolean hurt(DamageSource source, float amount) {
        // 枪弹天使打不动他：本体挥的、子弹 / 法术（弹射物记着主人）来的都免疫。
        // 和 Teto 同一条设定 —— 她的事玩家自己处理，谁也别替他挡枪，也别替她挨打。
        if (Teto.fromBulletAngel(source)) {
            return false;
        }
        if (this.isDeadOrDying()) {
            return false;
        }
        if (this.level() instanceof ServerLevel level
                && source.getEntity() instanceof LivingEntity attacker
                && !(attacker instanceof Player ghost && ghost.isSpectator())) {
            // 记一笔账：谁打过我。之后的索敌就从这本账里按威胁挑，
            // 不再是「最后一个摸我的人」独占。
            this.aggressors.put(attacker.getUUID(), level.getGameTime());
            this.lastCombatTick = level.getGameTime();
            if (attacker instanceof ServerPlayer player) {
                boolean firstTime = this.angerTarget == null;
                this.angerTarget = player.getUUID();
                this.setTarget(player);
                if (firstTime) {
                    speak(level, KEY_PROVOKE);
                }
            } else {
                // 怪打我也照样还手（玩家另算：一次只认一个，按威胁重挑）
                this.setTarget(attacker);
            }
        }
        return super.hurt(source, amount);
    }

    /**
     * 蓄力 / 待机段落只逃不打 —— 就算别的 Goal、别的模组硬把他拽到人脸上，也不出手。
     * 照射期（{@link HatAbilities#mustFlee} 为假）才正常挥剑。
     */
    @Override
    public boolean doHurtTarget(Entity target) {
        if (HatAbilities.mustFlee(this)) {
            return false;
        }
        return super.doHurtTarget(target);
    }

    /** 玩家攻击过后，头顶的「全」帽就把他认作敌人（见 {@link HatAbilities#isEnemy}）。 */
    public boolean isAngryAt(Entity entity) {
        return this.angerTarget != null && entity.getUUID().equals(this.angerTarget)
                && this.isAlive() && !this.isRemoved();
    }

    @Nullable
    private Player angerPlayer(ServerLevel level) {
        if (this.angerTarget == null) {
            return null;
        }
        Player player = level.getPlayerByUUID(this.angerTarget);
        if (player != null && player.isAlive() && !player.isSpectator() && !player.isRemoved()) {
            return player;
        }
        return null;
    }

    /** 复活返场之后节拍由服务端代打（见 {@link BrainrotQte#autoTapMob}）。 */
    public boolean autoBeat() {
        return this.revived;
    }

    // ------------------------------------------------------------------
    // 死亡与复活
    // ------------------------------------------------------------------

    @Override
    public void die(DamageSource source) {
        if (this.level() instanceof ServerLevel && this.revived
                && source.getEntity() instanceof ServerPlayer) {
            speak((ServerLevel) this.level(), KEY_DEFEATED);
        }
        super.die(source);
    }

    @Override
    protected void tickDeath() {
        this.deathTime++;
        if (this.deathTime >= 20 && !this.level().isClientSide) {
            this.level().broadcastEntityEvent(this, (byte) 3);
            this.remove(Entity.RemovalReason.KILLED);
            return;
        }
        if (this.deathTime == 19 && this.level() instanceof ServerLevel level) {
            if (!this.revived) {
                reviveNow(level);
                return;
            }
            announce(false);
        }
    }

    private void reviveNow(ServerLevel level) {
        this.revived = true;
        this.dead = false;
        this.deathTime = 0;
        this.hurtTime = 0;
        this.setHealth(this.getMaxHealth());
        this.clearFire();
        this.removeAllEffects();
        ItemStack brain = new ItemStack(HatItems.BRAIN_HAT.get());
        this.setItemSlot(EquipmentSlot.HEAD, brain);
        this.setDropChance(EquipmentSlot.HEAD, 0.0F);
        speak(level, KEY_REVIVE);
        level.broadcastEntityEvent(this, EVENT_REVIVED);
        BrainrotCycle.forceStart(this);
    }

    private void announce(boolean joined) {
        MinecraftServer server = this.level().getServer();
        if (server == null) {
            return;
        }
        Component message = Component.translatable(
                        joined ? "multiplayer.player.joined" : "multiplayer.player.left",
                        this.getDisplayName())
                .withStyle(ChatFormatting.YELLOW);
        server.getPlayerList().broadcastSystemMessage(message, false);
    }

    private void speak(ServerLevel level, String key) {
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(this) <= TALK_RANGE * TALK_RANGE) {
                player.sendSystemMessage(Component.literal("[Khragg] ")
                        .withStyle(ChatFormatting.GOLD)
                        .append(Component.translatable(key).withStyle(ChatFormatting.WHITE)));
            }
        }
    }

    // ------------------------------------------------------------------
    // 「他打死玩家」的嘲讽
    // ------------------------------------------------------------------

    @EventBusSubscriber(modid = HatMod.MOD_ID)
    public static class KillWatcher {

        @SubscribeEvent
        public static void onPlayerDeath(LivingDeathEvent event) {
            if (event.getSource().getEntity() instanceof Khragg khragg
                    && event.getEntity() instanceof ServerPlayer
                    && khragg.isAlive()
                    && khragg.level() instanceof ServerLevel level) {
                khragg.speak(level, KEY_WIN);
            }
        }
    }

    private static final byte EVENT_REVIVED = 66;

    @Override
    public void handleEntityEvent(byte id) {
        if (id == EVENT_REVIVED) {
            this.deathTime = 0;
            this.hurtTime = 0;
            this.setHealth(this.getMaxHealth());
        } else {
            super.handleEntityEvent(id);
        }
    }

    // ==================================================================
    // AI 基础设施：索敌 / 追击 / 传送 / 视线
    // ==================================================================

    /**
     * 从「打过我的账本」里挑当前目标：越近分越低、刚打过我的分更低、玩家再压一档。
     * 这样被两个人围着打时不会只认最后一个，也不会追着一个跑远的人不放。
     */
    private void chooseTarget(ServerLevel level, long now) {
        this.aggressors.entrySet().removeIf(e -> now - e.getValue() > 600L);
        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (Map.Entry<UUID, Long> entry : this.aggressors.entrySet()) {
            if (!(level.getEntity(entry.getKey()) instanceof LivingEntity candidate)
                    || !candidate.isAlive() || candidate.isRemoved()
                    || Teto.isBulletAngel(candidate)) {
                continue;
            }
            if (candidate instanceof Player player && player.isSpectator()) {
                continue;
            }
            double distSq = candidate.distanceToSqr(this);
            if (distSq > 48.0D * 48.0D) {
                continue;
            }
            double score = Math.sqrt(distSq) + (now - entry.getValue()) * 0.25D;
            if (candidate instanceof Player) {
                score -= 12.0D;
            }
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        if (best instanceof Player player) {
            this.angerTarget = player.getUUID();
            this.setTarget(player);
        } else if (best != null) {
            this.setTarget(best);
        } else if (this.angerTarget == null && this.getTarget() != null
                && !(this.getTarget() instanceof Player) && now - this.lastCombatTick > 200L) {
            // 账本里没人了（怪死光 / 跑远了）：别对着空气挥剑
            this.setTarget(null);
        }
    }

    /**
     * 追击兜底：原版寻路碰到「跳不上去、绕不过去」的地形会一直原地蹭。
     * 每 10 刻查一次 —— 距离超过 40 格，或者连续 2 秒没挪动：先重寻路，不行就直接闪过去。
     */
    private void chaseFallback(ServerLevel level, Player target) {
        double distSq = this.distanceToSqr(target);
        if (distSq > 96.0D * 96.0D) {
            // 96 格开外就已经不是「追击」了 —— 多半是刚被打死重生走 / 换了维度。
            // 这时候闪过去太赖，直接脱战回家。
            this.angerTarget = null;
            this.setTarget(null);
            return;
        }
        if (distSq > 40.0D * 40.0D) {
            this.teleportNear(level, target);
            return;
        }
        double moved = Math.abs(this.getX() - this.lastX) + Math.abs(this.getZ() - this.lastZ);
        this.lastX = this.getX();
        this.lastZ = this.getZ();
        if (distSq > 25.0D && moved < 0.1D) {
            if (++this.stuckTicks >= 4) {
                this.stuckTicks = 0;
                this.getNavigation().stop();
                if (this.getNavigation().createPath(target, 0) != null) {
                    this.getNavigation().moveTo(target, 1.25D);
                } else {
                    this.teleportNear(level, target);
                }
            }
        } else {
            this.stuckTicks = 0;
        }
    }

    /** 闪到目标身边 5~10 格，找一个真能站的点；找不到就拉倒，绝不往虚空里跳。 */
    private void teleportNear(ServerLevel level, Entity target) {
        for (int i = 0; i < 16; i++) {
            double angle = Math.PI * 2.0D * this.random.nextDouble();
            double dist = 5.0D + this.random.nextDouble() * 5.0D;
            int x = Mth.floor(target.getX() + Math.cos(angle) * dist);
            int z = Mth.floor(target.getZ() + Math.sin(angle) * dist);
            int y = Mth.floor(target.getY());
            for (int dy = 3; dy >= -4; dy--) {
                BlockPos pos = new BlockPos(x, y + dy, z);
                if (!level.isEmptyBlock(pos) || !level.isEmptyBlock(pos.above())) {
                    continue;
                }
                BlockPos ground = pos.below();
                if (!level.getBlockState(ground).isFaceSturdy(level, ground, Direction.UP)
                        || !level.getFluidState(pos).isEmpty()
                        || !level.getFluidState(ground).isEmpty()) {
                    continue;
                }
                this.getNavigation().stop();
                this.teleportTo(x + 0.5D, y + dy, z + 0.5D);
                level.sendParticles(ParticleTypes.PORTAL, this.getX(), this.getY() + 1.0D,
                        this.getZ(), 30, 0.4D, 0.7D, 0.4D, 0.25D);
                level.playSound(null, this.getX(), this.getY(), this.getZ(),
                        SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.0F, 0.7F);
                this.lastCombatTick = level.getGameTime();
                return;
            }
        }
    }

    /** 从 {@code from} 的眼睛高度到目标眼睛之间有没有遮挡（光柱 / 突进都得能过去）。 */
    private boolean hasClearShot(Vec3 from, LivingEntity target) {
        Vec3 eye = new Vec3(from.x, from.y + this.getEyeHeight(), from.z);
        HitResult hit = this.level().clip(new ClipContext(eye, target.getEyePosition(),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
        return hit.getType() == HitResult.Type.MISS;
    }

    /** 现在该不该打它：被我记过账的、正在激怒我的、帽子判定为敌的。 */
    private boolean hates(Entity entity) {
        if (entity == this || Teto.isBulletAngel(entity)) {
            return false;
        }
        if (!(entity instanceof LivingEntity living) || !living.isAlive()) {
            return false;
        }
        return this.isAngryAt(entity)
                || this.aggressors.containsKey(entity.getUUID())
                || HatAbilities.isEnemy(entity, this);
    }

    /** 身边 {@code radius} 格内有多少「该打的」。 */
    private int countEnemiesAround(double radius) {
        return this.level().getEntitiesOfClass(LivingEntity.class,
                this.getBoundingBox().inflate(radius), this::hates).size();
    }

    // ==================================================================
    // 走位 & 招式
    // ==================================================================

    /**
     * 被动期只逃不打：一阶段蓄力期、二阶段非光束段都算
     * （判据见 {@link HatAbilities#mustFlee}）。
     *
     * <p>这个 Goal 在整个被动段落里一直占着移动权（优先级 1），近战 Goal 抢不到人；
     * 它只挑一个 <b>跑得动、不在水里、不是死角、和敌人还有射界</b> 的落点，
     * 跑到了立刻再挑下一个 —— 整段都在拉开距离，不会站定挨打。
     */
    class RetreatGoal extends Goal {

        /** 逃命时想保持的距离：够远不至于被贴脸，又不至于跑出 40 格光柱射程。 */
        private static final double KEEP_RANGE = 22.0D;
        /** 候选点最远到这个距离就打住：再远光柱就够不着了。 */
        private static final double MAX_RANGE = 30.0D;

        private long nextRepick;

        @Override
        public boolean canUse() {
            return Khragg.this.level() instanceof ServerLevel
                    && Khragg.this.getTarget() != null
                    && HatAbilities.mustFlee(Khragg.this);
        }

        @Override
        public boolean canContinueToUse() {
            return Khragg.this.getTarget() != null
                    && HatAbilities.mustFlee(Khragg.this);
        }

        @Override
        public void start() {
            this.nextRepick = 0L;
            Khragg.this.getNavigation().setSpeedModifier(1.4D);
            repickPath();
        }

        @Override
        public void tick() {
            LivingEntity target = Khragg.this.getTarget();
            if (target == null || !(Khragg.this.level() instanceof ServerLevel level)) {
                return;
            }
            if (!Khragg.this.getNavigation().isDone()) {
                return;   // 还在跑
            }
            long now = level.getGameTime();
            if (now < this.nextRepick) {
                return;
            }
            // 到了就再挑下一个点：整段被动期都在移动，不站定
            this.nextRepick = now + 10L;
            repickPath();
        }

        /** 抓 8 个「背向目标」的候选点打分：先求跑出去，再求有射界、不成死角。 */
        private void repickPath() {
            LivingEntity target = Khragg.this.getTarget();
            if (target == null) {
                return;
            }
            Vec3 best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < 8; i++) {
                Vec3 spot = LandRandomPos.getPosAway(Khragg.this, 16, 8,
                        Vec3.atBottomCenterOf(target.blockPosition()));
                if (spot == null) {
                    continue;
                }
                double dist = spot.distanceTo(target.position());
                if (dist > MAX_RANGE) {
                    continue;   // 跑太远，蓄力完光柱就打不着了
                }
                double score = -Math.abs(dist - KEEP_RANGE) * 1.2D;
                BlockPos at = BlockPos.containing(spot);
                BlockPos ground = at.below();
                // 脚下得踩得住
                if (Khragg.this.level().getBlockState(ground)
                        .isFaceSturdy(Khragg.this.level(), ground, Direction.UP)) {
                    score += 3.0D;
                }
                // 水 / 岩浆 / 岩浆块：逃命不能往里面跑
                if (!Khragg.this.level().getFluidState(at).isEmpty()
                        || !Khragg.this.level().getFluidState(ground).isEmpty()
                        || Khragg.this.level().getBlockState(ground).is(Blocks.MAGMA_BLOCK)) {
                    score -= 20.0D;
                }
                // 死角（三面是墙）不钻
                score += Khragg.this.openSides(at) * 2.0D;
                // 留一条射界：蓄力一结束就能直接开火
                if (Khragg.this.hasClearShot(spot, target)) {
                    score += 5.0D;
                }
                // 别横穿整个场地
                score -= spot.distanceTo(Khragg.this.position()) * 0.2D;
                if (score > bestScore) {
                    bestScore = score;
                    best = spot;
                }
            }
            if (best == null) {
                return;   // 实在没地方跑（被围死 / 悬崖边）就保持当前动作
            }
            Path path = Khragg.this.getNavigation().createPath(best.x, best.y, best.z, 0);
            if (path != null) {
                Khragg.this.getNavigation().moveTo(path, 1.4D);
            }
        }
    }

    /** 这个落点四面有几面是能走出去的（0~4）：0~1 就是死角，逃命时别钻。 */
    private int openSides(BlockPos at) {
        int open = 0;
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH,
                Direction.EAST, Direction.WEST}) {
            BlockPos side = at.relative(dir);
            BlockPos sideGround = side.below();
            if (this.level().isEmptyBlock(side) && this.level().isEmptyBlock(side.above())
                    && this.level().getBlockState(sideGround)
                            .isFaceSturdy(this.level(), sideGround, Direction.UP)) {
                open++;
            }
        }
        return open;
    }

    /**
     * 「最强的矛」第一式 · 突进：距离拉开了先摆 0.6 秒架势，然后一路加速撞过去。
     * 走的是导航加速（不改动量），撞墙 / 撞岩浆都不会出事。
     */
    class DashGoal extends Goal {

        private int windup;
        private int lunge;

        @Override
        public boolean canUse() {
            LivingEntity target = Khragg.this.getTarget();
            if (target == null || !target.isAlive() || !Khragg.this.onGround()
                    || !(Khragg.this.level() instanceof ServerLevel level)) {
                return false;
            }
            if (level.getGameTime() < Khragg.this.nextDash || HatAbilities.mustFlee(Khragg.this)) {
                return false;
            }
            double distSq = Khragg.this.distanceToSqr(target);
            return distSq > 25.0D && distSq < 400.0D
                    && Khragg.this.hasClearShot(Khragg.this.position(), target);
        }

        @Override
        public void start() {
            this.windup = 12;
            this.lunge = 0;
            Khragg.this.getNavigation().stop();
        }

        @Override
        public boolean canContinueToUse() {
            return (this.windup > 0 || this.lunge > 0) && Khragg.this.getTarget() != null;
        }

        @Override
        public void stop() {
            Khragg.this.getNavigation().setSpeedModifier(1.0D);
        }

        @Override
        public void tick() {
            LivingEntity target = Khragg.this.getTarget();
            if (target == null || !(Khragg.this.level() instanceof ServerLevel level)) {
                return;
            }
            Khragg.this.getLookControl().setLookAt(target, 30.0F, 30.0F);
            if (this.windup > 0) {
                this.windup--;
                if (this.windup % 4 == 0) {
                    level.sendParticles(ParticleTypes.CLOUD,
                            Khragg.this.getX(), Khragg.this.getY() + 0.2D, Khragg.this.getZ(),
                            4, 0.35D, 0.05D, 0.35D, 0.02D);
                }
                if (this.windup == 0) {
                    level.playSound(null, Khragg.this.getX(), Khragg.this.getY(),
                            Khragg.this.getZ(), SoundEvents.WARDEN_SONIC_CHARGE,
                            SoundSource.HOSTILE, 0.6F, 1.4F);
                    Khragg.this.nextDash = level.getGameTime() + 160L;   // 8 秒冷却
                    this.lunge = 25;
                }
                return;
            }
            if (this.lunge > 0) {
                this.lunge--;
                if (this.lunge % 5 == 0) {
                    Khragg.this.getNavigation().moveTo(target, 1.6D);
                    level.sendParticles(ParticleTypes.CLOUD,
                            Khragg.this.getX(), Khragg.this.getY() + 0.1D, Khragg.this.getZ(),
                            3, 0.2D, 0.05D, 0.2D, 0.04D);
                }
            }
        }
    }

    /**
     * 「最强的矛」第二式 · 跳劈横扫：近身、或者被三个以上围住时跳起来砸地，
     * 落地一圈全打飞 —— 清场，也顺手把自己从包围里放出来。
     */
    class SlamGoal extends Goal {

        private static final double SWEEP_RADIUS = 4.5D;

        private boolean airborne;

        @Override
        public boolean canUse() {
            LivingEntity target = Khragg.this.getTarget();
            if (target == null || !target.isAlive() || !Khragg.this.onGround()
                    || !(Khragg.this.level() instanceof ServerLevel level)) {
                return false;
            }
            if (level.getGameTime() < Khragg.this.nextSlam || HatAbilities.mustFlee(Khragg.this)) {
                return false;
            }
            // 近身就砸；被围住（三个以上）也砸
            return Khragg.this.distanceToSqr(target) <= 20.25D
                    || Khragg.this.countEnemiesAround(SWEEP_RADIUS) >= 3;
        }

        @Override
        public void start() {
            this.airborne = false;
            Khragg.this.getNavigation().stop();
            Khragg.this.setDeltaMovement(Khragg.this.getDeltaMovement().x, 0.62D,
                    Khragg.this.getDeltaMovement().z);
            Khragg.this.hurtMarked = true;
            if (Khragg.this.level() instanceof ServerLevel level) {
                level.playSound(null, Khragg.this.getX(), Khragg.this.getY(), Khragg.this.getZ(),
                        SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.HOSTILE, 0.5F, 0.8F);
            }
        }

        @Override
        public boolean canContinueToUse() {
            return !Khragg.this.onGround() || this.airborne;
        }

        @Override
        public void tick() {
            if (!Khragg.this.onGround()) {
                this.airborne = true;
                return;
            }
            if (!this.airborne || !(Khragg.this.level() instanceof ServerLevel level)) {
                return;
            }
            this.airborne = false;
            sweep(level);
        }

        private void sweep(ServerLevel level) {
            for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class,
                    Khragg.this.getBoundingBox().inflate(SWEEP_RADIUS), Khragg.this::hates)) {
                victim.hurt(Khragg.this.damageSources().mobAttack(Khragg.this), 7.0F);
                Vec3 away = new Vec3(victim.getX() - Khragg.this.getX(), 0.0D,
                        victim.getZ() - Khragg.this.getZ());
                if (away.lengthSqr() > 1.0E-4D) {
                    away = away.normalize();
                    victim.push(away.x * 1.25D, 0.45D, away.z * 1.25D);
                    victim.hurtMarked = true;
                }
            }
            // 落地演出：一圈尘土 + 一声挥扫
            for (int i = 0; i < 20; i++) {
                double angle = Math.PI * 2.0D * i / 20.0D;
                level.sendParticles(ParticleTypes.CLOUD,
                        Khragg.this.getX() + Math.cos(angle) * 2.2D,
                        Khragg.this.getY() + 0.15D,
                        Khragg.this.getZ() + Math.sin(angle) * 2.2D,
                        2, 0.15D, 0.05D, 0.15D, 0.05D);
            }
            level.playSound(null, Khragg.this.getX(), Khragg.this.getY(), Khragg.this.getZ(),
                    SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.HOSTILE, 1.2F, 0.7F);
            Khragg.this.nextSlam = level.getGameTime() + 200L;   // 10 秒冷却
        }
    }

    /** 脱战后自己走回出生点。 */
    class ReturnHomeGoal extends Goal {

        @Override
        public boolean canUse() {
            return Khragg.this.getTarget() == null
                    && Khragg.this.home != null
                    && Khragg.this.home.distSqr(Khragg.this.blockPosition()) > 18.0D * 18.0D;
        }

        @Override
        public void start() {
            headHome();
        }

        @Override
        public boolean canContinueToUse() {
            return Khragg.this.getTarget() == null
                    && Khragg.this.home != null
                    && Khragg.this.home.distSqr(Khragg.this.blockPosition()) > 16.0D
                    && !Khragg.this.getNavigation().isDone();
        }

        @Override
        public void tick() {
            if (Khragg.this.getNavigation().isDone()) {
                headHome();
            }
        }

        private void headHome() {
            if (Khragg.this.home != null) {
                Khragg.this.getNavigation().moveTo(Khragg.this.home.getX() + 0.5D,
                        Khragg.this.home.getY(), Khragg.this.home.getZ() + 0.5D, 0.8D);
            }
        }
    }

    // ------------------------------------------------------------------
    // 存档
    // ------------------------------------------------------------------

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (this.angerTarget != null) {
            tag.putUUID(TAG_ANGER, this.angerTarget);
        }
        tag.putBoolean(TAG_REVIVED, this.revived);
        tag.putBoolean(TAG_EQUIPPED, this.equipped);
        tag.putBoolean(TAG_ANNOUNCED, this.announced);
        if (this.home != null) {
            tag.putLong(TAG_HOME, this.home.asLong());
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.angerTarget = tag.hasUUID(TAG_ANGER) ? tag.getUUID(TAG_ANGER) : null;
        this.revived = tag.getBoolean(TAG_REVIVED);
        this.equipped = tag.getBoolean(TAG_EQUIPPED);
        this.announced = tag.getBoolean(TAG_ANNOUNCED);
        this.home = tag.contains(TAG_HOME) ? BlockPos.of(tag.getLong(TAG_HOME)) : null;
    }
}
