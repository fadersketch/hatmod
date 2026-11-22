package com.hatmod;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 「虚影」{@link HatSummon} 的注册与召唤入口。
 *
 * <p>召唤时机在 {@code HatAbilities.tickingCharge} 里：蓄力计数归零、「全」帽进入照射期的那一瞬，
 * 一次放出 {@link #SUMMON_COUNT} 个，绕最近的敌人高速转圈。
 */
@Mod.EventBusSubscriber(modid = HatMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class HatSummons {

    /** 一次召唤几个虚影。 */
    public static final int SUMMON_COUNT = 4;
    /** 换位承伤时，离戴帽者最远多少格内的虚影可以被拉过来。 */
    public static final double SWAP_RANGE = 48.0D;
    /** 虚影的持续时间（刻）：10 秒 —— 照射结束那一刻才召唤，所以这就是它待在世上的全部时间。 */
    public static final int SHADE_LIFE_TICKS = 200;

    private static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, HatMod.MOD_ID);

    public static final RegistryObject<EntityType<HatSummon>> SUMMON =
            ENTITY_TYPES.register("hat_summon", () -> EntityType.Builder
                    // 尺寸按玩家来，它渲染的也是玩家模型
                    .of(HatSummon::new, MobCategory.CREATURE)
                    .sized(0.6F, 1.8F)
                    .clientTrackingRange(10)
                    .build(HatMod.id("hat_summon").toString()));

    /** 「脑蚀」一轮开始时放出来的后室 Teto，见 {@link Teto}。 */
    public static final int TETO_COUNT = 4;

    public static final RegistryObject<EntityType<Teto>> TETO =
            ENTITY_TYPES.register("backrooms_teto", () -> EntityType.Builder
                    // 平面贴图，判定箱按贴图比例给（1.33 格宽 / 2.4 格高 = 格子 94:169，
                    // 和 TetoRenderer 的四边形一致）。比史蒂夫(0.6×1.8)高三分之一左右
                    .of(Teto::new, MobCategory.CREATURE)
                    .sized(1.33F, 2.4F)
                    .clientTrackingRange(10)
                    .build(HatMod.id("backrooms_teto").toString()));

    /** 链路里和 Teto 一起放出来的同伴，见 {@link TangJiaqi}。 */
    public static final RegistryObject<EntityType<TangJiaqi>> TANG =
            ENTITY_TYPES.register("tang_jiaqi", () -> EntityType.Builder
                    // 视频面片：判定箱和 16:9 的面片对齐（宽 2.4 / 高 1.35）
                    .of(TangJiaqi::new, MobCategory.CREATURE)
                    .sized(2.4F, 1.0875F)
                    .clientTrackingRange(10)
                    .build(HatMod.id("tang_jiaqi").toString()));

    /** 开发者的化身，做彩蛋真好玩。只能刷怪蛋 / 指令生成，见 {@link Khragg}。 */
    public static final RegistryObject<EntityType<Khragg>> KHragg =
            ENTITY_TYPES.register("khragg", () -> EntityType.Builder
                    .of(Khragg::new, MobCategory.MISC)
                    .sized(0.6F, 1.8F)
                    .clientTrackingRange(10)
                    .build(HatMod.id("khragg").toString()));

    /** 白帽 / 「全」白路线链路的同伴，见 {@link TangJiaqiWhite}。 */
    public static final RegistryObject<EntityType<TangJiaqiWhite>> TANG_WHITE =
            ENTITY_TYPES.register("tang_jiaqi_white", () -> EntityType.Builder
                    // 平面贴图：判定箱和待机那张竖版面片对齐（高 2.2 / 宽约 1.34）
                    .of(TangJiaqiWhite::new, MobCategory.CREATURE)
                    .sized(1.4F, 2.2F)
                    .clientTrackingRange(10)
                    .build(HatMod.id("tang_jiaqi_white").toString()));

    private HatSummons() {
    }

    public static void register(IEventBus eventBus) {
        ENTITY_TYPES.register(eventBus);
    }

    @SubscribeEvent
    public static void createAttributes(EntityAttributeCreationEvent event) {
        event.put(SUMMON.get(), HatSummon.createAttributes().build());
        event.put(TETO.get(), Teto.createAttributes().build());
        event.put(TANG.get(), TangJiaqi.createAttributes().build());
        event.put(TANG_WHITE.get(), TangJiaqiWhite.createAttributes().build());
        event.put(KHragg.get(), Khragg.createAttributes().build());
    }

    /**
     * 在召唤者周围放一圈虚影，每个立刻开始绕最近敌人转圈。
     *
     * <p>虚影头上戴着真的「全」帽子，但 {@code HatAbilities} 的状态机会跳过 {@code HatSummon}，
     * 所以它们不会自己蓄力开火 —— 它们只负责绕圈、嘲讽、替挡刀，外加自己那道小光柱。
     *
     * @param lifeTicks 寿命（调用方传 {@link #SHADE_LIFE_TICKS}，10 秒）
     * @param route     主人这一轮「全」走的路线：分身的帧伤按这条路线取配置
     */
    static void summonAll(ServerLevel level, LivingEntity owner, int lifeTicks, HatType route) {
        // 分身不能再召唤分身。它现在头上戴着真的「全」帽子，但 HatAbilities 的状态机
        // 本来就会跳过 HatSummon（见那边的 tick 开头）；这里再兜一道，
        // 以后就算加了别的调用点也不会滚雪球。
        if (owner instanceof HatSummon) {
            return;
        }
        // 上一批还没走完就不再放新的一批：宁可让旧的把剩下的寿命走完，也不叠成 8 个
        if (nearest(level, owner) != null) {
            return;
        }
        EntityType<HatSummon> type = SUMMON.get();
        double step = Math.PI * 2.0D / SUMMON_COUNT;
        for (int i = 0; i < SUMMON_COUNT; i++) {
            HatSummon summon = type.create(level);
            if (summon == null) {
                continue;
            }
            double phase = step * i;
            // 先摆在召唤者周围一圈，第一刻的 orbit() 会把它们带到敌人身边
            summon.moveTo(owner.getX() + Math.cos(phase) * 1.2D, owner.getY(),
                    owner.getZ() + Math.sin(phase) * 1.2D, owner.getYRot(), 0.0F);
            summon.init(owner, lifeTicks, phase, route);
            level.addFreshEntity(summon);
            level.sendParticles(ParticleTypes.SQUID_INK,
                    summon.getX(), summon.getY() + summon.getBbHeight() * 0.5D, summon.getZ(),
                    10, 0.25D, 0.45D, 0.25D, 0.02D);
        }
        level.playSound(null, owner.getX(), owner.getY(), owner.getZ(),
                SoundEvents.SOUL_ESCAPE, SoundSource.PLAYERS, 0.9F, 0.6F);
    }

    /**
     * 找离 {@code owner} 最近、还活着的虚影；找不到返回 {@code null}。
     * 用于「闪避用完后再挨打 → 换位承伤」。
     */
    @org.jetbrains.annotations.Nullable
    static HatSummon nearest(ServerLevel level, LivingEntity owner) {
        AABB area = owner.getBoundingBox().inflate(SWAP_RANGE);
        List<HatSummon> found = level.getEntitiesOfClass(HatSummon.class, area,
                summon -> summon.isAlive() && !summon.isRemoved() && summon.isOwnedBy(owner));
        HatSummon best = null;
        double bestDistance = Double.MAX_VALUE;
        for (HatSummon summon : found) {
            double distance = summon.distanceToSqr(owner);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = summon;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // 后室 Teto（「脑蚀」专用）
    // ------------------------------------------------------------------

    /** 单个玩家最多同时存在几只 Teto（本体 1 + 光柱期间 3 个分身）。 */
    public static final int TETO_MAX_PER_PLAYER = 4;

    /** 起手时只放 1 只本体。 */
    public static final int BASE_TETOS = 1;

    /** 光柱期间额外补几只分身（无台词，光柱一收就散）。 */
    public static final int BEAM_EXTRA_TETOS = 3;

    /**
     * 「脑蚀」一轮开始时在戴帽者周围放 {@link #BASE_TETOS} 只 Teto（本体）。
     *
     * <p>**先数已经在场的**（重进存档、上一局没收干净、刷怪蛋放出来的都算），
     * 只补到 {@link #TETO_MAX_PER_PLAYER} 只为止 —— 不会再出现「旧的 4 只没走、新的又来了 4 只」。
     */
    static int spawnTetos(ServerLevel level, LivingEntity owner) {
        if (Teto.hasSitting(owner)) {
            // 她坐着 = 这一轮她不上场。连「补人」也不补：不然每 10 刻被 checkTetos 拉起来一次，
            // 玩家刚让她坐下就又被拽上场，看着像按钮坏了。
            return 0;
        }
        EntityType<Teto> type = TETO.get();
        // 先把**全场**属于这个玩家的 Teto 收干净（不管多远）：上一轮没清掉的、
        // 重进存档留下的、刷怪蛋放的都算 —— 这样「每人最多 4 只」才是硬保证，
        // 而不是「附近 128 格内最多 4 只」（远处的照样活着，回来就变成 8 只）。
        // 走 Teto 自己的 UUID 名册：有模组换掉实体查询之后 getAllEntities 可能是空的。
        // 坐着的**不收**：她说要歇着，那就别拿「放新的」当借口把她清掉。
        List<Teto> leftovers = new ArrayList<>();
        for (Teto teto : Teto.ownedBy(owner)) {
            if (!teto.isSitting()) {
                leftovers.add(teto);
            }
        }
        for (Teto teto : leftovers) {
            teto.hatmodDismiss();
        }
        int added = 0;
        double step = Math.PI * 2.0D / TETO_MAX_PER_PLAYER;
        // 初始相位：先按「斜向」粗略对齐（正前/正后会被模型和判定条挡住），
        // 真正精确的定位在站定前 1 秒做 —— BrainrotCycle.stillAlignBlend 会把整圈慢慢
        // 补到正前/正右/正后/正左，并保证那一只正好站在玩家面前。
        //
        // 轨道角 a 处的世界坐标是 (cos a, sin a)；玩家朝向前方的方向向量是
        // (-sin yaw, cos yaw)，所以「正前方」对应的轨道角是 yaw + 90°。
        // 起手就在玩家**正前方**；之后的角度由 {@code Teto#orbit} 按链路年龄直接算，
        // 到「站定」（第一次 Nope, too late）那一刻正好转满一圈，于是又回到正前方。
        double frontAngle = Math.toRadians(owner.getYRot()) + Math.PI / 2.0D;
        for (int i = 0; i < BASE_TETOS; i++) {
            Teto teto = type.create(level);
            if (teto == null) {
                continue;
            }
            double phase = frontAngle + step * i;
            // 正前方，距离就是绕圈半径（以前写的 2 格，比轨道近一半，第一刻就被拽出去）
            teto.moveTo(owner.getX() + Math.cos(phase) * Teto.orbitRadius(), owner.getY(),
                    owner.getZ() + Math.sin(phase) * Teto.orbitRadius(), owner.getYRot(), 0.0F);
            teto.init(owner, phase);
            if (level.addFreshEntity(teto)) {
                added++;
            }
            if (SPAWN_WARNINGS < 5) {
                int near = level.getEntitiesOfClass(Teto.class,
                        owner.getBoundingBox().inflate(8.0D)).size();
                HatMod.LOGGER.warn("[hatmod] Teto 刚放进去：pos=({},{},{}) removed={} 同层={} 8格内={}",
                        Math.round(teto.getX() * 10) / 10.0, Math.round(teto.getY() * 10) / 10.0,
                        Math.round(teto.getZ() * 10) / 10.0, teto.isRemoved(),
                        teto.level() == level, near);
            }
            level.sendParticles(ParticleTypes.SCULK_SOUL,
                    teto.getX(), teto.getY() + teto.getBbHeight() * 0.5D, teto.getZ(),
                    12, 0.3D, 0.7D, 0.3D, 0.01D);
        }
        level.playSound(null, owner.getX(), owner.getY(), owner.getZ(),
                SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.PLAYERS, 0.7F, 1.4F);
        if (added < BASE_TETOS && SPAWN_WARNINGS++ < 10) {
            HatMod.LOGGER.warn("[hatmod] Teto 只放进去了 {} / {} 只（位置 {},{},{}，主人 {}）",
                    added, BASE_TETOS,
                    Math.round(owner.getX()), Math.round(owner.getY()), Math.round(owner.getZ()),
                    owner.getName().getString());
        }
        // 放进去几只告诉调用方：只有真的放成了才算「成功召唤」（见 BrainrotCycle.start）
        return added;
    }

    /**
     * 破防模式下「场上被清空」时补的那一只：**就一只**，站在玩家正前方。
     *
     * <p>和 {@link #spawnTetos} 的区别：不清场、不按 {@link #BASE_TETOS} 铺一圈 ——
     * 破防模式本来就不靠数量，补一只把链路续上就够了。
     */
    static void spawnOneTeto(ServerLevel level, LivingEntity owner) {
        if (Teto.hasSitting(owner)) {
            return;                     // 她留守了：这一轮就是没有 Teto
        }
        EntityType<Teto> type = TETO.get();
        Teto teto = type.create(level);
        if (teto == null) {
            return;
        }
        double phase = Math.toRadians(owner.getYRot()) + Math.PI / 2.0D;
        teto.moveTo(owner.getX() + Math.cos(phase) * Teto.orbitRadius(), owner.getY(),
                owner.getZ() + Math.sin(phase) * Teto.orbitRadius(), owner.getYRot(), 0.0F);
        teto.init(owner, phase);
        if (level.addFreshEntity(teto)) {
            level.sendParticles(ParticleTypes.SCULK_SOUL,
                    teto.getX(), teto.getY() + teto.getBbHeight() * 0.5D, teto.getZ(),
                    12, 0.3D, 0.7D, 0.3D, 0.01D);
            level.playSound(null, owner.getX(), owner.getY(), owner.getZ(),
                    SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.PLAYERS, 0.7F, 1.4F);
        }
    }

    /** 「没放满」这条日志已经打过几次。 */
    private static int SPAWN_WARNINGS;

    // ------------------------------------------------------------------
    // 唐嘉琦（「脑蚀」里的同伴）
    // ------------------------------------------------------------------

    /**
     * 链路起手时和 Teto 一起放出来：**每人只放一只**，已经有一只就不再放。
     *
     * <p>**不看 Teto 是不是在留守**：她坐下只是她自己待命，这一轮照样要有一个同伴上场。
     */
    static void spawnTang(ServerLevel level, LivingEntity owner) {
        if (TangJiaqi.anyOwnedBy(owner)) {
            return;
        }
        EntityType<TangJiaqi> type = TANG.get();
        TangJiaqi tang = type.create(level);
        if (tang == null) {
            return;
        }
        // 摆在玩家侧后方（和跟随时的站位一致），第一刻不会被拽一下
        double yaw = Math.toRadians(owner.getYRot());
        tang.moveTo(owner.getX() - Math.sin(yaw) * 1.8D + Math.cos(yaw) * 0.9D,
                owner.getY(),
                owner.getZ() + Math.cos(yaw) * 1.8D + Math.sin(yaw) * 0.9D,
                owner.getYRot(), 0.0F);
        tang.init(owner);
        if (!level.addFreshEntity(tang)) {
            return;
        }
        level.sendParticles(ParticleTypes.CLOUD,
                tang.getX(), tang.getY() + tang.getBbHeight() * 0.5D, tang.getZ(),
                14, 0.3D, 0.7D, 0.3D, 0.02D);
        level.playSound(null, tang.getX(), tang.getY(), tang.getZ(),
                SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.6F, 1.4F);
    }

    /** 链路收场：把名下所有唐嘉琦清掉。 */
    static void clearTang(ServerLevel level, LivingEntity owner) {
        for (TangJiaqi tang : TangJiaqi.ownedBy(owner)) {
            ServerLevel where = tang.level() instanceof ServerLevel own ? own : level;
            tang.disappear(where, "脑蚀链路收场");
        }
    }

    /**
     * 白帽 / 「全」白路线链路开场带上白色唐嘉琦：每人只放一只，已经有了就不放。
     *
     * <p>摆在主人左手边（右手边是唐嘉琦（1）的位子）；站进去就自己跑白帽循环，
     * 链路收场它自己会散（见 {@link TangJiaqiWhite#tick} 的孤儿判定），这里不用记着清。
     */
    static void spawnTangWhite(ServerLevel level, LivingEntity owner) {
        if (TangJiaqiWhite.anyOwnedBy(owner)) {
            return;
        }
        EntityType<TangJiaqiWhite> type = TANG_WHITE.get();
        TangJiaqiWhite tang = type.create(level);
        if (tang == null) {
            return;
        }
        double yaw = Math.toRadians(owner.getYRot());
        tang.moveTo(owner.getX() - Math.sin(yaw) * 1.8D - Math.cos(yaw) * 0.9D,
                owner.getY(),
                owner.getZ() + Math.cos(yaw) * 1.8D - Math.sin(yaw) * 0.9D,
                owner.getYRot(), 0.0F);
        tang.init(owner);
        if (!level.addFreshEntity(tang)) {
            return;
        }
        level.sendParticles(ParticleTypes.CLOUD,
                tang.getX(), tang.getY() + tang.getBbHeight() * 0.5D, tang.getZ(),
                14, 0.3D, 0.7D, 0.3D, 0.02D);
        level.playSound(null, tang.getX(), tang.getY(), tang.getZ(),
                SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.6F, 1.4F);
    }

    /**
     * 光柱期间补 3 个分身：只负责围住敌人、替本体挡刀，**不带任何台词**。
     *
     * <p>已经有分身在场就不再叠（两段光柱中间来回切的时候不会越攒越多）。
     */
    static void spawnBeamExtras(ServerLevel level, LivingEntity owner) {
        if (Teto.hasSitting(owner)) {
            // 她留在原地 = 这一轮不掺和：连光柱分身也不补，不然场上还是凭空多出 3 只
            return;
        }
        List<Teto> owned = Teto.ownedBy(owner);
        boolean exists = false;
        for (Teto teto : owned) {
            if (teto.isExtra()) {
                exists = true;
                break;
            }
        }
        if (exists) {
            return;
        }
        EntityType<Teto> type = TETO.get();
        // 相位严格 90°：三个分身落在那只**已经在场的** Teto 的 +90° / +180° / +270°。
        // 基准必须取「它此刻的角度」（orbitAngle + orbitPhase），只取初始相位的话，
        // 本体已经转过的角度不会被算进去，分身就会和它叠在一起。
        double base = 0.0D;
        for (Teto main : owned) {
            if (!main.isExtra()) {
                base = main.orbitAngleNow();
                break;
            }
        }
        double step = Math.PI / 2.0D;
        for (int i = 0; i < BEAM_EXTRA_TETOS; i++) {
            Teto teto = type.create(level);
            if (teto == null) {
                continue;
            }
            double phase = base + step * (i + 1);
            teto.moveTo(owner.getX() + Math.cos(phase) * 2.5D, owner.getY(),
                    owner.getZ() + Math.sin(phase) * 2.5D, owner.getYRot(), 0.0F);
            teto.initExtra(owner, phase);
            level.addFreshEntity(teto);
            level.sendParticles(ParticleTypes.SCULK_SOUL,
                    teto.getX(), teto.getY() + teto.getBbHeight() * 0.5D, teto.getZ(),
                    10, 0.3D, 0.7D, 0.3D, 0.01D);
        }
    }

    /**
     * 曲子响起来：附近有散养的 Teto 就**直接收编**成本轮本体。
     *
     * <p>以前是「把散养的收掉、再放一只新的」—— 玩家刚放出来的那只就站在那儿，
     * 刷地一下换成四只（现在是 1 只）新的很突兀。现在能收编就收编，
     * 收编不了（附近没有）才重新召唤。
     *
     * @return true = 已经有一只可用，调用方不用再放新的
     */
    static boolean adoptFreeTeto(ServerLevel level, LivingEntity owner) {
        Teto best = null;
        double bestDistance = 32.0D * 32.0D;
        for (Teto teto : Teto.ownedBy(owner)) {
            // 坐着的**不收编**：她已经在待命了，被拉上场等于把「坐下」这个动作作废。
            if (teto.isSummoned() || teto.isSitting() || teto.level() != level) {
                continue;
            }
            double distance = teto.distanceToSqr(owner);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = teto;
            }
        }
        if (best == null) {
            return false;
        }
        // 收编后直接站到玩家正前方（她是被就地收编的，可能刚在别处绕圈）
        double phase = Math.toRadians(owner.getYRot()) + Math.PI / 2.0D;
        best.hatmodAdopt(owner, phase);
        best.moveTo(owner.getX() + Math.cos(phase) * Teto.orbitRadius(), owner.getY(),
                owner.getZ() + Math.sin(phase) * Teto.orbitRadius(), owner.getYRot(), 0.0F);
        // 收编那句交给 BrainrotCycle#sayOpening：开场白和它二选一，别一次冒两个气泡
        return true;
    }

    /** 光柱结束：把分身收掉（不打日志、不说话）。 */
    static void clearBeamExtras(ServerLevel level, LivingEntity owner) {
        for (Teto teto : Teto.ownedBy(owner)) {
            if (teto.isExtra()) {
                teto.hatmodDismiss();
            }
        }
    }

    /** 曲终 / 摘帽：把这一轮召唤的 Teto 全部收掉。 */
    static void clearTetos(ServerLevel level, LivingEntity owner) {
        clearTetos(level, owner, false);
    }

    /**
     * 曲终 / 摘帽：收掉这一轮的 Teto。
     *
     * @param keepOne 留一只下来（变回散养，继续跟着玩家）—— 曲终之后常见的那种
     */
    static void clearTetos(ServerLevel level, LivingEntity owner, boolean keepOne) {
        Teto kept = null;
        for (Teto teto : Teto.ownedBy(owner)) {
            if (!teto.isSummoned()) {
                continue;               // 散养 / 留守的：这一轮跟她无关，留着
            }
            if (keepOne && kept == null && !teto.isExtra() && teto.level() == level) {
                kept = teto;
                continue;               // 本体留一只；光柱分身该散还是散
            }
            // 粒子要撒在她自己那个维度里（名册是跨维度的）
            ServerLevel where = teto.level() instanceof ServerLevel own ? own : level;
            teto.disappear(where, keepOne ? "曲终/摘帽清场（留一只）" : "曲终/摘帽清场");
        }
        if (kept != null) {
            kept.hatmodRelease(owner);
            HatMod.LOGGER.info("[hatmod] 脑蚀链路收场：留了一只 Teto 继续跟着 {}",
                    owner.getName().getString());
        }
    }
}
