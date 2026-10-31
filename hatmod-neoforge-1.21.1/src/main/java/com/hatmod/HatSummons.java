package com.hatmod;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.ArrayList;
import java.util.List;

/**
 * 「虚影」{@link HatSummon} 的注册与召唤入口。
 *
 * <p>召唤时机在 {@code HatAbilities.tickingCharge} 里：蓄力计数归零、「全」帽进入照射期的那一瞬，
 * 一次放出 {@link #SUMMON_COUNT} 个，绕最近的敌人高速转圈。
 */
@EventBusSubscriber(modid = HatMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class HatSummons {

    /** 一次召唤几个虚影。 */
    public static final int SUMMON_COUNT = 4;
    /** 换位承伤时，离戴帽者最远多少格内的虚影可以被拉过来。 */
    public static final double SWAP_RANGE = 48.0D;

    private static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, HatMod.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<HatSummon>> SUMMON =
            ENTITY_TYPES.register("hat_summon", () -> EntityType.Builder
                    // 尺寸按玩家来，它渲染的也是玩家模型
                    .of(HatSummon::new, MobCategory.CREATURE)
                    .sized(0.6F, 1.8F)
                    .clientTrackingRange(10)
                    .build(HatMod.id("hat_summon").toString()));

    /** 「脑蚀」一轮开始时放出来的后室 Teto，见 {@link Teto}。 */
    public static final int TETO_COUNT = 4;

    public static final DeferredHolder<EntityType<?>, EntityType<Teto>> TETO =
            ENTITY_TYPES.register("backrooms_teto", () -> EntityType.Builder
                    // 平面贴图，判定箱按贴图比例给（1.33 格宽 / 2.4 格高 = 格子 94:169，
                    // 和 TetoRenderer 的四边形一致）。比史蒂夫(0.6×1.8)高三分之一左右
                    .of(Teto::new, MobCategory.CREATURE)
                    .sized(1.33F, 2.4F)
                    .clientTrackingRange(10)
                    .build(HatMod.id("backrooms_teto").toString()));

    private HatSummons() {
    }

    public static void register(IEventBus eventBus) {
        ENTITY_TYPES.register(eventBus);
    }

    @SubscribeEvent
    public static void createAttributes(EntityAttributeCreationEvent event) {
        event.put(SUMMON.get(), HatSummon.createAttributes().build());
        event.put(TETO.get(), Teto.createAttributes().build());
    }

    /**
     * 在召唤者周围放一圈虚影，每个立刻开始绕最近敌人转圈。
     *
     * <p>虚影<b>不戴帽子</b>：这样 {@code HatAbilities} 的状态机不会接管它们（也就不会发射光柱），
     * 它们只是「会嘲讽、会替挡刀、绕着敌人转的影子」。
     *
     * @param lifeTicks 寿命，等于主人这一轮的照射刻数
     */
    static void summonAll(ServerLevel level, LivingEntity owner, int lifeTicks) {
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
            summon.init(owner, lifeTicks, phase);
            level.addFreshEntity(summon);
            level.sendParticles(ParticleTypes.SQUID_INK,
                    summon.getX(), summon.getY() + summon.getBbHeight() * 0.5D, summon.getZ(),
                    10, 0.25D, 0.45D, 0.25D, 0.02D);
        }
        level.playSound(null, owner.getX(), owner.getY(), owner.getZ(),
                SoundEvents.SOUL_ESCAPE.value(), SoundSource.PLAYERS, 0.9F, 0.6F);
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

    /** 单个玩家最多同时存在几只 Teto。 */
    public static final int TETO_MAX_PER_PLAYER = 4;

    /**
     * 「脑蚀」一轮开始时在戴帽者周围放 4 只 Teto。
     *
     * <p>**先数已经在场的**（重进存档、上一局没收干净、刷怪蛋放出来的都算），
     * 只补到 {@link #TETO_MAX_PER_PLAYER} 只为止 —— 不会再出现「旧的 4 只没走、新的又来了 4 只」。
     */
    static void spawnTetos(ServerLevel level, LivingEntity owner) {
        EntityType<Teto> type = TETO.get();
        // 先把**全场**属于这个玩家的 Teto 收干净（不管多远）：上一轮没清掉的、
        // 重进存档留下的、刷怪蛋放的都算 —— 这样「每人最多 4 只」才是硬保证，
        // 而不是「附近 128 格内最多 4 只」（远处的照样活着，回来就变成 8 只）。
        List<Teto> leftovers = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof Teto teto && teto.isOwnedBy(owner)) {
                leftovers.add(teto);
            }
        }
        for (Teto teto : leftovers) {
            teto.discard();
        }
        double step = Math.PI * 2.0D / TETO_MAX_PER_PLAYER;
        for (int i = 0; i < TETO_MAX_PER_PLAYER; i++) {
            Teto teto = type.create(level);
            if (teto == null) {
                continue;
            }
            double phase = step * i;
            teto.moveTo(owner.getX() + Math.cos(phase) * 2.0D, owner.getY(),
                    owner.getZ() + Math.sin(phase) * 2.0D, owner.getYRot(), 0.0F);
            teto.init(owner, phase);
            level.addFreshEntity(teto);
            level.sendParticles(ParticleTypes.SCULK_SOUL,
                    teto.getX(), teto.getY() + teto.getBbHeight() * 0.5D, teto.getZ(),
                    12, 0.3D, 0.7D, 0.3D, 0.01D);
        }
        level.playSound(null, owner.getX(), owner.getY(), owner.getZ(),
                SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.PLAYERS, 0.7F, 1.4F);
    }

    /** 曲终 / 摘帽：把这一轮召唤的 Teto 全部收掉。 */
    static void clearTetos(ServerLevel level, LivingEntity owner) {
        List<Teto> found = level.getEntitiesOfClass(Teto.class,
                owner.getBoundingBox().inflate(64.0D), teto -> teto.isOwnedBy(owner));
        for (Teto teto : found) {
            teto.disappear(level);
        }
    }
}
