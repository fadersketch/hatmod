package com.hatmod;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * 帽子的核心技能状态机，对**任何戴帽子的生物**生效（玩家、车万女仆等）。
 *
 * <pre>
 *   IDLE ──24 格内出现合法敌人──> CHARGE ── 循环开始，播放这顶帽子的音乐
 *                                  │  （附魔「预知」：蓄力完成的那一刻释放，把 30 格内的敌人定住，时长按等级 1/3、2/3、3/3 递进，III 级打满全程）
 *                                  │  期间：戴帽者不能攻击被动生物与未激怒的中立生物
 *                                  │        （索敌照常：戴帽的生物也会正常锁定/追击目标）
 *                                  │        戴帽者自身固定抗性提升 II
 *                                  │        自身以外 5 格内：缓慢 V + 时间变慢（整体压速，方向不变）
 *                                  │        （己方：主人、同一主人的女仆/宠物、同队 —— 不受影响）
 *                                  │  黑帽：受到伤害 -> 瞬移躲开这一击（完全免伤），蓄力期每轮最多 3 次
 *                                  │        （附魔「闪避」每级 +1 次；照射期另有一套，见「残影」）
 *                                  ▼
 *                              蓄力结束的一瞬间
 *                                  │  · 范围减速全部解除（周围生物恢复自由）
 *                                  │  · 获得迅捷 II —— 一直挂到本轮循环结束（下一轮蓄力开始时收掉）
 *                                  │  · 黑帽：附魔「残影」给的照射期闪避免费在这时补满
 *                                  ▼
 *                               FLASH ── 每道光柱各锁一个不同的敌人，会随距离转火
 *                                  │  每刻重新取最近的敌人；换目标时通知客户端
 *                                  │  被光柱罩住的敌人每刻吃光柱伤害
 *                                  │  （该帽固定值走 hurt() + 当前生命的 5% 直接改血），并被完全硬控
 *                                  │  · 红帽：期间自身力量 I
 *                                  │  · 白帽：附魔「光辉」给身边友军「神隐」（原版隐身 + 定期甩索敌）
 *                                  ▼
 *                           附近仍有敌人 ? ── 是 ──> 回到 CHARGE（再次播放音乐）
 *                                            └ 否 ──> IDLE（音乐停止）
 * </pre>
 *
 * <p>每顶帽子的蓄力/照射时长与伤害见 {@link HatType}。
 * <p>光柱本体是客户端用几何体画的，见 {@code com.hatmod.client.BeamRenderer}。
 */
@EventBusSubscriber(modid = HatMod.MOD_ID)
public final class HatAbilities {

    /** 时间差力场半径：5 格。 */
    public static final double SLOW_RADIUS = 5.0D;
    /**
     * 敌人接近判定半径：有敌人才会进入蓄力阶段。
     *
     * <p>当前**不再使用**：蓄力触发与光柱锁定共用同一个射程 {@link #FLASH_RANGE}（24 格），
     * 所以「能触发蓄力」和「能打到」是同一个距离，不存在「进了蓄力却打不到」的空档。
     * 这个常量保留只是为了兼容旧引用，新代码一律用 {@link #FLASH_RANGE}。
     */
    public static final double DETECT_RADIUS = 16.0D;
    /** 光柱射程，同时也是锁定敌人的最大距离。 */
    public static final double FLASH_RANGE = 24.0D;

    /**
     * 光柱起点相对身高的比例：0 = 脚底，1 = 头顶。
     *
     * <p>0.4 就是「腿和身体的交界处」（胯部）：玩家身高 1.8 时约 0.72 格高。起点放这里有两个作用：
     * <ul>
     *   <li>观感上光是从胯部打出去的，不再挂在眼睛上；</li>
     *   <li>第一人称下相机在光柱<b>上方</b>约 0.9 格，不再是「顺着轴线看一张曲面」——
     *       那种角度下锥壳的投影会缩成一圈<b>空心</b>的环，正中间什么都看不见。
     *       相机挪到轴线外之后，看到的是光锥的侧面，立刻就明显了。</li>
     * </ul>
     *
     * <p>客户端渲染（{@code client.BeamRenderer}）用的是同一个函数，所以画面和判定永远一致。
     */
    public static final float BEAM_ORIGIN_HEIGHT_RATIO = 0.4F;

    /**
     * 黑帽瞬移音效的音量：和 BGM 等量（{@link HatMusic#MUSIC_VOLUME} = 3.0），
     * 这样瞬移声不会淹没在音乐里。>1 也会让声音传得更远。
     */
    private static final float BLINK_SOUND_VOLUME = 3.0F;
    /** 黑帽每轮蓄力最多能「瞬移躲掉」几次伤害。 */
    private static final int BLINKS_PER_CHARGE = 3;
    /** 「身轻如燕」：一次照射里要用光柱打死几个。 */
    private static final int LIGHT_STEP_KILLS = 5;

    /** 帽子上每多一个附魔，就给戴帽者多加多少点护甲。 */
    private static final double ARMOR_PER_ENCHANT = 2.0D;
    /** 附魔护甲加成用的固定修饰符 id（同一顶帽子反复刷新时靠它去重 / 覆盖）。 */
    private static final ResourceLocation ENCHANT_ARMOR_ID = HatMod.id("enchant_armor");
    /** 饰品栏里那顶帽子的护甲（Curios 不结算 ArmorItem 的属性，这里自己补一条）。 */
    private static final ResourceLocation CURIO_ARMOR_ID = HatMod.id("curio_hat_armor");
    /** 白帽永久跳跃的最高等级：放大 1 = 跳跃 II，「疾行」再高也不往上加。 */
    private static final int MAX_PERMANENT_JUMP_AMPLIFIER = 1;

    /**
     * 蓄力结束时给的迅捷 II：**一直持续到本轮循环结束**（下一轮蓄力开始、或转回待机时收掉）。
     *
     * <p>照射期间每刻刷新，但单次时长只有 1 秒 —— 所以中途出事（摘帽、换帽、停照）时
     * 它也会在半秒内自己掉干净，不需要额外的清理路径。
     */
    private static final int SPEED_TICKS = 20;
    public static final int SPEED_AMPLIFIER = 1;

    // ------------------------------------------------------------------
    // 蓄力期间的抗性提升
    //
    // 三顶帽子在蓄力全程固定获得抗性提升 II（放大 1），蓄力一结束立刻收掉。
    // 每刻用这么短的时长刷新，是为了配合「摘帽子 / 换帽子」的中断：中断时哪怕
    // 漏掉了显式清理，效果也会在半秒内自己掉干净。
    // ------------------------------------------------------------------
    /** 抗性提升 II = 放大 1。 */
    private static final int CHARGE_RESISTANCE_AMPLIFIER = 1;
    /** 抗性效果的每刻刷新时长（刻）。 */
    private static final int CHARGE_RESISTANCE_TICKS = 10;

    // ------------------------------------------------------------------
    // 蓄力阶段的「时间差」力场
    //
    // 不是「把速度拽住」（阻尼），而是「让时间在原地变慢」：
    //   · 生物：缓慢 V（放大 4，-75% 移速）+ 三轴同系数的整体压速 —— 还在沿原路走，只是慢。
    //   · 弹射物：关掉重力 + 把速度压到一个很低的定值（方向不变）—— 箭矢继续朝原方向飞，
    //     只是慢得像停住，**不会**因为重力越积越多而一头栽下来（那正是阻尼版的毛病）。
    //   · 掉落物等其它非生物：同样三轴同系数压速。
    // 三轴同系数是关键：方向不变，看起来才是「时间变慢」而不是「被一只大手摁住」。
    // 关掉的重力在离开力场 / 力场结束时还回去，见 GRAVITY_HELD。
    // 己方（见 isFriendlyTo）直接跳过：女仆戴帽子时不能把主人一起按住。
    // ------------------------------------------------------------------
    /** 缓慢 V。 */
    private static final int SLOW_AMPLIFIER = 4;
    private static final int SLOW_EFFECT_TICKS = 10;
    /** 生物的整体压速系数（三轴同系数，方向不变）。 */
    private static final double LIVING_DAMP = 0.55D;
    /** 弹射物在力场里的速度上限（格/刻）：比这快就压到该值，方向不变；重力同时被关掉。 */
    private static final double PROJECTILE_SLOW_SPEED = 0.12D;
    /** 掉落物等其它非生物实体的整体压速系数（三轴同系数）。 */
    private static final double OBJECT_DAMP = 0.5D;

    /**
     * 被力场关掉重力的弹射物 -> 实体本身。
     *
     * <p>只登记「原本有重力」的那一批（{@code !isNoGravity()}），所以离场时统一
     * {@code setNoGravity(false)} 就是准确还原，不需要另存原值。离场、力场结束、
     * 实体消失都会从这里摘掉并还原重力，见 {@link #releaseEscapedProjectiles} 与
     * {@link #clearSlowField}。
     */
    private static final Map<UUID, Entity> GRAVITY_HELD = new HashMap<>();

    /**
     * 力场边缘那圈细碎粒子：每 {@link #RING_PERIOD} 刻撒一圈、一圈 {@link #RING_POINTS} 个点。
     *
     * <p>数量刻意压得很低（12 点 / 4 刻 ≈ 每秒 60 个），只让"圈"看得见，不糊视野。
     * 嫌多就把 RING_PERIOD 调大，嫌少就调小。
     */
    private static final int RING_PERIOD = 4;
    private static final int RING_POINTS = 12;
    /** 粒子在脚底往上的高度抖动范围（格）。 */
    private static final double RING_HEIGHT = 0.25D;

    /** 被光柱罩住的敌人：缓慢 255 完全定身。 */
    private static final int HOLD_AMPLIFIER = 254;
    private static final int HOLD_TICKS = 60;
    /** 虚弱/挖掘疲劳用的满级放大值。 */
    private static final int DISABLE_AMPLIFIER = 255;

    /** 红帽照射期间的力量持续时间（每刻刷新，等效全程）。 */
    private static final int STRENGTH_TICKS = 10;

    /**
     * 光柱伤害吃「力量」的系数：力量每级给光柱 +35% 伤害（力量 I = ×1.35）。
     *
     * <p>原版力量只加 {@code Attributes.ATTACK_DAMAGE}，只有走 {@code Player.attack} 的近战才算得进去；
     * 光柱是直接 {@code hurt()} 一个固定值，默认完全吃不到 —— 这个系数就是补上这一步。
     */
    private static final float STRENGTH_BEAM_BONUS_PER_LEVEL = 0.35F;
    /** 虚弱每级给光柱的伤害惩罚（×0.8/级）。 */
    private static final float WEAKNESS_BEAM_PENALTY_PER_LEVEL = 0.20F;
    /** 光柱力量倍率的上下限，防止力量/虚弱叠出离谱数值。 */
    private static final float MIN_BEAM_POWER = 0.10F;
    private static final float MAX_BEAM_POWER = 4.0F;

    private static final Map<UUID, HatState> STATES = new HashMap<>();

    private HatAbilities() {
    }

    /**
     * 有没有装 Curios。{@code null} 表示还没问过。
     *
     * <p>做成"问到就记住"的惰性字段：{@code ModList} 在模组构造期就已经填好了，
     * 但为了不给"万一还没就绪"留坑，取不到就下次再问。
     */
    private static Boolean CURIOS_LOADED;

    private static boolean curiosLoaded() {
        if (CURIOS_LOADED == null && ModList.get() != null) {
            CURIOS_LOADED = ModList.get().isLoaded("curios");
        }
        return CURIOS_LOADED != null && CURIOS_LOADED;
    }

    /**
     * 这只生物身上正在生效的那顶帽子：先看头盔槽，再看 Curios 饰品栏。
     *
     * <p>两个地方都按原样返回（空栈表示没戴），调用方照旧用 {@link HatItems#typeOf} 判断。
     * 饰品栏那条只在装了 Curios 时才会走到；没装时 {@link CuriosCompat} 这个类
     * 根本不会被加载，所以 Curios 不在也不会报 {@code NoClassDefFoundError}。
     */
    public static ItemStack hatStack(LivingEntity entity) {
        ItemStack head = entity.getItemBySlot(EquipmentSlot.HEAD);
        if (HatItems.isHat(head)) {
            return head;
        }
        return curiosLoaded() ? CuriosCompat.findHat(entity) : ItemStack.EMPTY;
    }

    public static boolean isWearingHat(LivingEntity entity) {
        return HatItems.isHat(hatStack(entity));
    }


    /**
     * 被光柱强控的生物一点伤害都打不出来（{@link #HELD}）。
     *
     * <p>「蓄力期间不可攻击」这条已经删掉了 —— 蓄力期戴帽者照常能攻击，
     * 只保留「不能打被动生物 / 未激怒的中立生物」那一条（见 {@code HatProtection}）。
     */
    public static boolean isAttackLocked(LivingEntity entity) {
        return HELD.containsKey(entity.getUUID());
    }

    // ------------------------------------------------------------------
    // 光柱强控：直接关掉 AI，让它连技能都放不出来
    // ------------------------------------------------------------------

    /** 被光柱控住的生物 -> 强控记录；用于到点自动解除。 */
    private static final Map<UUID, Hold> HELD = new HashMap<>();

    /** 一次强控。{@code forcedNoAi} 非空表示"AI 是我们关的"，解除时要还回去。 */
    private static final class Hold {
        /**
         * 到期刻：{@code now > expiresAt} 就还它自由。
         *
         * <p>光柱每刻重挂一次，到期刻只往后放 2 刻；「脑蚀」的声波是一次性结算，
         * 直接挂 3 秒 —— 两者共用这套到点释放，不会留下永久 noAI 的怪。
         */
        private final long expiresAt;
        private final Mob forcedNoAi;

        private Hold(long expiresAt, Mob forcedNoAi) {
            this.expiresAt = expiresAt;
            this.forcedNoAi = forcedNoAi;
        }
    }

    /**
     * 把被光柱照到的生物彻底控住。
     *
     * <p>只靠缓慢控不住灾变那种 boss —— 它们的技能挂在 AI 上，不看你走不走得动。
     * 所以这里直接 {@link Mob#setNoAi(boolean)}（等价于原版 {@code /data merge {NoAI:1}}）：
     * 关掉之后不选目标、不施法、不移动。为了不误伤本来就没 AI 的生物，
     * 只有"原本有 AI"的才会记进 {@code forcedNoAi}，解除时也只还这一批。
     */
    private static void hold(ServerLevel level, LivingEntity living) {
        // 光柱每刻都会重挂一次，所以到期刻只往后放 2 刻
        holdFor(level, living, 2);
    }

    /**
     * 按住 {@code ticks} 刻（一次性，不会自动续期）。
     *
     * <p>光柱靠每刻刷新维持，用的是 {@link #hold}；「脑蚀」的声波只结算一瞬，没有下一次刷新，
     * 所以给它一个真正的持续时间 —— 这就是声波的「控制」部分。
     */
    private static void holdFor(ServerLevel level, LivingEntity living, int ticks) {
        // 效果时长 = 这次强控要持续多久（多留 10 刻余量，别刚好卡在到期那一帧掉）
        holdFor(level, living, ticks, ticks + 10, HOLD_AMPLIFIER);
    }

    /**
     * 按住 {@code holdTicks} 刻，三条效果各持续 {@code effectTicks} 刻，
     * 「缓慢」用 {@code slowAmplifier} 级（声波要的是 255，压到基本走不动）。
     */
    private static void holdFor(ServerLevel level, LivingEntity living, int holdTicks,
                                int effectTicks, int slowAmplifier) {
        living.setDeltaMovement(Vec3.ZERO);
        living.hurtMarked = true;
        living.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, effectTicks, slowAmplifier, false, false, false));
        living.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, effectTicks, DISABLE_AMPLIFIER, false, false, false));
        living.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN, effectTicks, DISABLE_AMPLIFIER, false, false, false));

        Hold previous = HELD.get(living.getUUID());
        Mob forced = previous == null ? null : previous.forcedNoAi;
        if (living instanceof Mob mob) {
            mob.setTarget(null);
            mob.getNavigation().stop();
            if (!mob.isNoAi()) {
                mob.setNoAi(true);
                forced = mob;
            }
        }
        long now = level.getServer().getTickCount();
        HELD.put(living.getUUID(), new Hold(now + holdTicks, forced));
    }

    /**
     * 「脑蚀」的 AoE 光柱没有走 {@link #damageBeam} 那条线（它不按射线逐个判定，
     * 而是一圈全打），所以由 {@code BrainrotCycle} 直接调这里补上同款强控 —— 射程内的敌人照样被按住。
     */
    static void applyBeamHold(ServerLevel level, LivingEntity living) {
        hold(level, living);
    }

    /**
     * 「脑蚀」的声波：一次结算，按住 {@code ticks} 刻。
     *
     * <p>和光柱同一套强控（缓慢 255 + 虚弱 255 + 挖掘疲劳 255 + 强制 {@code NoAI}），
     * 区别只在不需要每刻续 —— 声波只发生一瞬，所以一次性挂满整段（40 秒）。
     */
    static void applySonicHold(ServerLevel level, LivingEntity living, int ticks) {
        // 声波只结算一瞬，没有下一次续期：效果时长就是整段定身时间，缓慢直接给 255
        holdFor(level, living, ticks, ticks, SONIC_SLOW_AMPLIFIER);
    }

    /** 声波的「缓慢」等级：255（压到几乎不动，配合强制 NoAI 就是彻底定住）。 */
    private static final int SONIC_SLOW_AMPLIFIER = 255;

    /**
     * 解除到期的强控并恢复 AI。
     *
     * <p>按「到期刻」判定（光柱每刻续 2 刻，声波一次挂 40 秒），而不是在光柱结束时逐个通知：
     * 施法者死亡、摘帽、掉线、走远等情况都能自动收尾，不会留下一只永久 noAI 的怪。
     */
    private static void releaseStaleHolds(int now) {
        if (HELD.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Hold>> iterator = HELD.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Hold> entry = iterator.next();
            if (now <= entry.getValue().expiresAt) {
                continue;
            }
            iterator.remove();
            Mob mob = entry.getValue().forcedNoAi;
            if (mob != null && !mob.isRemoved()) {
                mob.setNoAi(false);
            }
        }
    }

    // ------------------------------------------------------------------
    // 「每刻维护」的辅助状态
    //
    // 这四种状态都不属于「某一顶帽子正在蓄力 / 照射」这条主线，但都要每刻盯着，
    // 于是统一从这里走一遍（玩家的 PlayerTickEvent、其它生物的 EntityTickEvent 各调一次）：
    //   · 预知静止 —— 到点解除，期间每刻压着（见 SOUL_REAP_STASIS）
    //   · 神隐     —— 置隐身标记、定期把已经锁上来的敌人甩掉（见 VEIL_REFRESH_MARGIN）
    //   · 掌控内讧 —— 给被控住的生物找个对手打（见 DOMINION_VICTIM）
    //   · 绿心     —— 吸血换来的护盾，掉落速度随颗数指数增长、基础 1 秒/颗（见 GREEN_HEARTS）
    // ------------------------------------------------------------------

    /** 每刻维护上面那四种状态。和「自己戴没戴帽子」无关：被定住的敌人自己并不戴帽子。 */
    private static void tickAuras(LivingEntity living, ServerLevel level) {
        if (SOUL_REAP_STASIS.containsKey(living.getUUID())) {
            tickStasis(living, level);
        }
        if (living.hasEffect(HatEffects.VEIL)) {
            tickVeil(living);
        } else {
            VEIL_AGGRO_FREE_UNTIL.remove(living.getUUID());
        }
        if (DOMINION_LOCKED.containsKey(living.getUUID())) {
            tickDominionFight(living);
        }
        if (GREEN_HEARTS.containsKey(living.getUUID())) {
            // 绿心只属于这顶帽子：帽子没了就立刻全清（摘帽那条路在 tick 里，这里兜住
            // 「离线时被摘掉、回来时 state 已经不在」这类漏网的）。
            if (HatItems.typeOf(hatStack(living)) == null) {
                clearGreenHearts(living);
            } else {
                tickGreenHearts(living);
            }
        }
    }

    // ------------------------------------------------------------------
    // 附魔「预知」：蓄力完成那一刻的「我早已料到」
    //
    // 效果就是蓄力时间差力场的那一套（整体压速 + 缓慢 V，走的是同一个 applyStillness），
    // 只是**不跟着戴帽者走**：发动的一瞬间按 30 格点名，点到的原地定住。
    // 时长按附魔等级递进（I/II/III = 最大时长的 1/3、2/3、3/3），最大时长就是
    // 「从蓄力完成释放到本轮循环结束」这一段 —— 所以 III 级仍旧是打满全程（最大值不变）。
    // 同时给戴帽者自己一段**整段持续黑白**的画面 + 一句「我早已料到」（只有玩家看得到），
    // 见 client.ForesightFilter。
    // ------------------------------------------------------------------

    /** 被「预知」定住的生物 -> 解除时刻（服务器刻）。 */
    private static final Map<UUID, Long> SOUL_REAP_STASIS = new HashMap<>();

    /**
     * 释放「预知」：把 30 格内的敌人定住，时长按等级取 {@code maxTicks} 的 1/3、2/3、3/3。
     *
     * <p>{@code maxTicks} 由调用方给成本轮循环剩余的长度（也就是这次照射的时长）——
     * 「从开始释放到循环结束」，也就是 III 级吃满的那个最大值。没附上「预知」时什么都不做。
     */
    private static void fireSoulReapStasis(LivingEntity wearer, ServerLevel level, ItemStack hat, int maxTicks) {
        int ticks = HatEnchants.soulReapTicks(hat, maxTicks);
        if (ticks <= 0) {
            return;
        }
        long until = (long) (level.getServer().getTickCount() + ticks);
        AABB area = wearer.getBoundingBox().inflate(HatEnchants.SOUL_REAP_RADIUS);
        for (Entity entity : level.getEntities(wearer, area)) {
            if (!(entity instanceof LivingEntity living) || !isEnemy(living, wearer)) {
                continue; // 己方和旁观者不在点名之列
            }
            SOUL_REAP_STASIS.put(living.getUUID(), until);
            applyStillness(living);
        }

        // 表现层：一声闷响 + 一圈波纹 + （玩家的话）黑白画面与那句话。
        // 一个人都没定住时照放不误 —— 这是技能本身的表现，不是命中特效。
        level.playSound(null, wearer.getX(), wearer.getY(), wearer.getZ(),
                SoundEvents.BELL_RESONATE, SoundSource.PLAYERS, 1.0F, 0.55F);
        spawnStasisRing(level, wearer);
        if (wearer instanceof ServerPlayer player) {
            player.displayClientMessage(Component.translatable("message.hatmod.foresight"), true);
            HatNetwork.sendForesight(player, ticks);
        }
    }

    /** 被点名的敌人每刻的维持：到点收掉缓慢，其余时间一直压着（和力场同一套数值）。 */
    private static void tickStasis(LivingEntity living, ServerLevel level) {
        Long until = SOUL_REAP_STASIS.get(living.getUUID());
        if (until == null) {
            return;
        }
        if (level.getServer().getTickCount() >= until) {
            SOUL_REAP_STASIS.remove(living.getUUID());
            removeStillness(living);
            return;
        }
        applyStillness(living);
    }

    /**
     * 「时间差力场」落在单个生物身上的那一下：整体压速（三轴同系数）+ 缓慢 V。
     *
     * <p>蓄力力场（{@link #applySlowField}）和「预知」的定点静止用的都是这一个方法 ——
     * 「和力场内一样的效果」就是字面意义上的同一份效果，没有两套数值可以走偏。
     *
     * <p>三轴用**同一个**系数（而不是横竖分开压）：方向不变，才是「时间变慢」而不是
     * 「被拽住」。早先横竖分开压时，水平速度很快掉光、竖直却还在被重力往上/往下加，
     * 于是看着就像一头栽下去 —— 现在不会了。
     */
    private static void applyStillness(LivingEntity living) {
        living.setDeltaMovement(living.getDeltaMovement().scale(LIVING_DAMP));
        living.hurtMarked = true;
        living.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,
                SLOW_EFFECT_TICKS, SLOW_AMPLIFIER, false, false, false));
    }

    /** 静止解除：只收掉我们自己加的那一档缓慢（和 {@link #clearSlowField} 同一个规矩）。 */
    private static void removeStillness(LivingEntity living) {
        MobEffectInstance slow = living.getEffect(MobEffects.MOVEMENT_SLOWDOWN);
        if (slow != null && slow.getAmplifier() == SLOW_AMPLIFIER) {
            living.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
        }
    }

    /** 发动时的一圈涟漪：半径就是技能半径，让戴帽者一眼看出这一下罩到多远。 */
    private static void spawnStasisRing(ServerLevel level, LivingEntity wearer) {
        double radius = HatEnchants.SOUL_REAP_RADIUS;
        int points = 64;
        for (int i = 0; i < points; i++) {
            double angle = Math.PI * 2.0D * i / points;
            level.sendParticles(ParticleTypes.SCULK_SOUL,
                    wearer.getX() + Math.cos(angle) * radius,
                    wearer.getY() + 0.2D,
                    wearer.getZ() + Math.sin(angle) * radius,
                    1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    // ------------------------------------------------------------------
    // 附魔「光辉」：支援隐身「神隐」
    //
    // 给谁、给多久由附魔等级决定，都在 HatEnchants 里：
    //   · 范围 = 时间差力场的半径（slowRadius(wearer)：5 格起，「缓速」每级再 +1 格）
    //   · 时长 = HatEnchants.veilDurationTicks（6/8/10 秒，快到点了再补一次）
    // 这一节只管「挂着神隐之后每刻要做什么」。
    // ------------------------------------------------------------------

    /** 「神隐」剩余不足这么多刻（2 秒）就补一次，别等它断了再接。 */
    private static final int VEIL_REFRESH_MARGIN = 40;
    /** 「神隐」每多少刻把已经锁上来的敌人甩掉一次。 */
    private static final int VEIL_SHAKE_PERIOD = 10;
    /** 「神隐」甩索敌的搜索半径（格）。 */
    private static final double VEIL_SHAKE_RADIUS = 32.0D;
    /**
     * 「神隐」刚挂上的**前 3 秒**（60 刻）内保证把索敌甩干净。
     *
     * <p>之后再怎么隐身也只是普通隐身（连穿盔甲都照样生效的那种）：已经开打的敌人不会被甩掉，
     * 只有「还没发现你」的敌人会按原版隐身的规则看不见你。以前是整段时间一路甩，
     * 等于常驻「强制脱战」，削成只保前 3 秒。
     */
    private static final int VEIL_AGGRO_FREE_TICKS = 60;

    /** 生物 -> 「还在保证清仇恨」的截止时刻（世界刻）。 */
    private static final Map<UUID, Long> VEIL_AGGRO_FREE_UNTIL = new HashMap<>();

    /**
     * 挂着「神隐」的生物每刻的维护，整体设计见 {@link HatEffects} 顶部的说明。
     *
     * <p>两件事：
     * <ul>
     *   <li>把它标成**隐身** —— 神隐本身就是隐身；</li>
     *   <li><b>前 3 秒</b>把「已经锁上来」的目标甩掉 —— 之后不再甩，只留普通隐身。</li>
     * </ul>
     */
    private static void tickVeil(LivingEntity living) {
        // 神隐**本身**就是隐身：直接把「隐身」这个同步标记置上，不再另外挂一份原版隐身效果。
        // 原版隐身效果内部也只是把这个标记置位（LivingEntity.updateInvisibilityStatus 从
        // INVISIBILITY 效果推出来），多挂一份的结果只是状态栏平白多出一条「隐身」。
        // 标记是同步字段：模型、原版生物索敌（Sensing）以及没装本模组的客户端都照它走。
        // 别的效果增删会让原版按效果重推这个标记，所以每刻补一次。
        if (!living.isInvisible()) {
            living.setInvisible(true);
        }
        // 只保证刚挂上的前 3 秒：之后就是普通隐身，已经开打的敌人照旧锁着你
        Long until = VEIL_AGGRO_FREE_UNTIL.get(living.getUUID());
        if (until == null) {
            return;
        }
        if (living.level().getGameTime() >= until) {
            VEIL_AGGRO_FREE_UNTIL.remove(living.getUUID());
            return;
        }
        AABB area = living.getBoundingBox().inflate(VEIL_SHAKE_RADIUS);
        // 监守者得**每刻**抹：它的目标与怒气一两刻就能重新长回来，隔 10 刻清一次等于没清。
        for (Warden warden : living.level().getEntitiesOfClass(Warden.class, area)) {
            blindWardenTo(warden, living);
        }
        if (living.tickCount % VEIL_SHAKE_PERIOD != 0) {
            return;
        }
        for (Entity entity : living.level().getEntities(living, area)) {
            if (entity instanceof Mob mob && mob.getTarget() == living) {
                mob.setTarget(null);
            }
        }
    }

    /**
     * 让监守者**发现不了**某个生物。
     *
     * <p>监守者跟别的生物不一样，对普通生物那一套对它完全无效：它的目标**不在
     * {@code Mob#target} 里**，而在 Brain 的 {@code ATTACK_TARGET} 记忆里
     * （{@code Warden#getTarget()} 读的就是这条记忆），填这条记忆又靠
     * {@code NEAREST_ATTACKABLE} 与怒气。所以 {@code setTarget(null)} 和拦
     * {@code LivingChangeTargetEvent} 都碰不到它，隐身也骗不过它的嗅觉 ——
     * 要让它「看不见」谁，只能每刻把这几样一起抹掉。
     *
     * <p>只抹**指向 {@code hidden}** 的那几样，不碰它正在打的别人 —— 免得顺手把别处的战斗搅了。
     */
    private static void blindWardenTo(Warden warden, LivingEntity hidden) {
        warden.clearAnger(hidden);
        Brain<Warden> brain = warden.getBrain();
        if (warden.getTarget() == hidden) {
            brain.eraseMemory(MemoryModuleType.ATTACK_TARGET);
        }
        if (brain.getMemory(MemoryModuleType.NEAREST_ATTACKABLE).orElse(null) == hidden) {
            brain.eraseMemory(MemoryModuleType.NEAREST_ATTACKABLE);
        }
    }

    /**
     * 被「掌控」控住的监守者：只要它锁的不是我们指给它的那个对手，就一律甩掉。
     *
     * <p>普通生物那一套（{@code setTarget(null)} + 拦事件）对监守者无效，原因见
     * {@link #blindWardenTo} —— 不这么做的话，「掌控」对监守者就是个空技能。
     */
    private static void blindLockedWarden(Warden warden) {
        LivingEntity target = warden.getTarget();
        if (target != null && !isDominionVictim(warden, target)) {
            blindWardenTo(warden, target);
        }
    }

    /** 每刻扫一遍：没人再照着就还它自由。 */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        int now = event.getServer().getTickCount();
        releaseStaleHolds(now);
        releaseStaleDominion(event.getServer(), now);
        // 力场关掉重力的弹射物要是已经没了（打中/超时），就从登记里摘掉，免得越攒越多
        GRAVITY_HELD.values().removeIf(Entity::isRemoved);
        // 破防判定：每秒一次，哪顶帽子打的都算 —— 和帽子种类、循环节奏全都无关
        if (now % 20 == 0) {
            evaluateForceCycle(event.getServer());
        }
        // 破防接管：放在 tick 末尾，把这一 tick 里对方回的血 / 写的满血 / 同步的镜像覆盖掉
        tickSealedHealth(event.getServer());
        // 硬杀过的目标要是被自己的复活逻辑塞回来了，接着摘
        tickPurged(event.getServer());
        // 破防模式下被外人删掉的虚影 / 铁托，拉回来
        reviveSummons(event.getServer());
    }

    // ------------------------------------------------------------------
    // 状态机
    // ------------------------------------------------------------------

    /**
     * 玩家走 PlayerTickEvent、其他生物走 EntityTickEvent，各管一半。
     *
     * <p>EntityTickEvent 只在 Level.tickNonPassenger 里触发，而玩家是由网络线程那边
     * 单独 tick 的，两者并不完全重合。拆开写可以保证每个生物每刻**只**被处理一次，
     * 也不会漏掉玩家。
     */
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        tickAuras(player, player.serverLevel());
        tick(player, player.serverLevel());
        if (HatSettings.forceBreak() && !hatStack(player).isEmpty()) {
            lockPlayerHealth(player);
        }
    }
    /** 玩家身上最后的血量：破防模式下被绕过伤害系统写低时，用这个值抬回去。 */
    private static final Map<UUID, Float> PLAYER_HEALTH = new HashMap<>();

    /**
     * 破防模式下给玩家的「Teto 级防御」第二层：血量锁。
     *
     * <p>第一层（伤害事件取消）挡的是正常伤害；这一层挡的是绕过伤害系统直接
     * {@code setHealth(0)} 的那类杀法（Witheria 的死亡程序、永爱之刃的 killEntity）。
     */
    private static void lockPlayerHealth(ServerPlayer player) {
        float health = player.getHealth();
        Float remembered = PLAYER_HEALTH.get(player.getUUID());
        if (remembered == null) {
            PLAYER_HEALTH.put(player.getUUID(), health);
            return;
        }
        if (health < remembered - 0.01F) {
            player.setHealth(Math.max(remembered, health));
        } else {
            PLAYER_HEALTH.put(player.getUUID(), health);
        }
    }

    /**
     * 破防模式下给玩家的「Teto 级防御」第三层：死亡拦截。
     *
     * <p>前两层都被绕过去（比如 {@code /kill} 走完整死亡流程）时，这里直接把死亡事件取消，
     * 让 {@code die()} 根本不执行。
     */
    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!HatSettings.forceBreak()) {
            return;
        }
        // 破防模式：死了就别想回来。记进「已杀」名册，被它的主人/召唤者拉起来就跟进摘掉
        // （见 tickPurged）。这条对召唤物尤其重要 —— 它们往往死了会被 Boss 当场复活。
        LivingEntity dead = event.getEntity();
        if (dead instanceof Teto) {
            HatMod.LOGGER.warn("[hatmod] Teto 触发了死亡事件（血量 {}/{}）—— 之后它会被记进清算名册，"
                            + "每 5 刻被摘一次，链路就会不停「补回来」",
                    dead.getHealth(), dead.getMaxHealth());
        }
        if (!(dead instanceof Player) && !isUntouchable(dead)) {
            if (PURGED.size() > 256) {
                PURGED.clear();
            }
            PURGED.computeIfAbsent(dead.getUUID(), key -> new Purge());
        }
        if (!(event.getEntity() instanceof Player player) || hatStack(player).isEmpty()) {
            return;
        }
        event.setCanceled(true);
        player.setHealth(player.getMaxHealth());
        player.invulnerableTime = 20;
        PLAYER_HEALTH.put(player.getUUID(), player.getMaxHealth());
        HatMod.LOGGER.info("[hatmod] 破防模式拦下了 {} 的死亡", player.getName().getString());
    }


    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof LivingEntity living) || living instanceof Player) {
            return;
        }
        if (!(living.level() instanceof ServerLevel level)) {
            return;
        }
        tickAuras(living, level);
        tick(living, level);
    }

    private static void tick(LivingEntity wearer, ServerLevel level) {
        // 「虚影」不参与状态机：它虽然戴着帽子（HatSummon.init 给的「全」），但这里的蓄力/
        // 照射/召唤一律不跑 —— 它只负责绕圈、嘲讽、替挡和它自己那道小光柱
        if (wearer instanceof HatSummon) {
            return;
        }
        UUID id = wearer.getUUID();
        HatState state = STATES.get(id);
        HatType type = HatItems.typeOf(hatStack(wearer));

        if (type == null || wearer.isDeadOrDying() || wearer.isSpectator()) {
            if (state != null) {
                // 半路摘帽子：把这一轮留下的东西收拾干净
                clearSlowField(level, wearer);
                clearChargeResistance(wearer);
                clearPermanentBuffs(wearer);
                clearEnchantArmor(wearer);
                clearGreenHearts(wearer);
                clearCycleSpeed(wearer);
                HatMusic.stop(level, wearer, state.route);
                clearBeamSync(wearer, state);
                STATES.remove(id);
                STEP_NANOS.remove(id);
                STEP_CARRY.remove(id);
            }
            // 帽子没戴 / 戴的是脑蚀帽：饰品栏里那顶的护甲照给（和头上是什么无关）
            applyCurioArmor(wearer);
            return;
        }

        if (state == null) {
            state = new HatState();
            state.type = type;
            state.route = type;
            STATES.put(id, state);
        } else if (state.type != type) {
            // 中途换帽子：上一轮的减速/抗性/光柱/增益/音乐全部收掉，从零开始
            clearSlowField(level, wearer);
            clearChargeResistance(wearer);
            clearPermanentBuffs(wearer);
            clearEnchantArmor(wearer);
            clearGreenHearts(wearer);
            clearCycleSpeed(wearer);
            HatMusic.stop(level, wearer, state.route);
            clearBeamSync(wearer, state);
            state.reset();
            state.type = type;
            state.route = type;
        }

        // 白帽：戴着就一直有迅捷 I + 跳跃 I
        applyPermanentBuffs(wearer, type);
        // 帽子上每多一个附魔，护甲 +2
        ItemStack activeHat = hatStack(wearer);
        applyEnchantArmor(wearer, activeHat);
        applyCurioArmor(wearer);

        // ---- 这一轮的时长也是照着 BGM 排的（蓄力 = 前奏、照射 = 副歌）----
        // 和「脑蚀」那条链路同一个问题：服务器掉 TPS 时游戏刻慢于真实时间，而音乐是按
        // 真实时间放的，光柱就会比音乐晚亮、晚收。这里按真实流逝的时间补刻，一次最多补
        // MAX_CATCH_UP 刻；补的部分仍旧一刻一刻按顺序走，所以蓄力/照射的分界点不会漏。
        int steps = stepsFor(id);
        for (int i = 0; i < steps; i++) {
            if (state.flash > 0) {
                tickingFlash(wearer, level, state);
            } else if (state.charge > 0) {
                tickingCharge(wearer, level, state);
            } else if (findNearestEnemy(wearer) != null) {
                // 只有存在合法目标才会进入蓄力阶段；循环由此开始
                startCharge(wearer, level, state);
                tickingCharge(wearer, level, state);
            } else {
                break;
            }
        }
    }

    /** 一次最多补几刻（和 {@code BrainrotCycle} 同一个数）：再多说明是暂停 / 传送 / 读盘。 */
    private static final int MAX_CATCH_UP = 3;

    /** 每个戴帽者上一次走谱的真实时刻 / 攒下的零头（刻）。 */
    private static final Map<UUID, Long> STEP_NANOS = new HashMap<>();
    private static final Map<UUID, Double> STEP_CARRY = new HashMap<>();

    /**
     * 这一服务器刻该走几刻（按真实流逝时间算，1 ~ {@link #MAX_CATCH_UP}）。
     *
     * <p>正常 20 TPS 时每次正好 1；掉到 15 TPS 会攒够再补一刻，掉到 10 TPS 每刻走 2 刻，
     * 「蓄力结束 / 照射开始」还是按真实时间落在音乐上。超过 5 秒的空档（单人暂停、
     * 区块读盘）直接当 1 刻，不追 —— 那段时间音乐也停着。
     */
    private static int stepsFor(UUID id) {
        long now = System.nanoTime();
        Long last = STEP_NANOS.put(id, now);
        double elapsed = last == null ? 1.0D : (now - last) / 50_000_000.0D;
        if (elapsed > 5.0D) {
            elapsed = 1.0D;
        }
        elapsed = Math.min(elapsed, MAX_CATCH_UP);
        double pending = elapsed + STEP_CARRY.getOrDefault(id, 0.0D);
        int steps = (int) pending;
        if (steps <= 0) {
            steps = 1;
            pending = 1.0D;
        }
        STEP_CARRY.put(id, Math.max(0.0D, pending - steps));
        return Math.min(steps, MAX_CATCH_UP);
    }

    /**
     * 取「全」这一轮的路线：三条路线（黑/白/红）打乱后依次取，取空再重洗 ——
     * 于是**随机但不重复**：三条路线各走一遍之后才可能重复，中间不会连着两次同色。
     *
     * <p>重洗时还多做一步：如果新洗出的第一条正好等于刚刚走过的最后一条，就把它和
     * 洗牌结果的后一条换个位置 —— 避免正好卡在「洗牌边界」上出现连续同色。
     *
     * <p>三顶原色帽没有路线概念，直接返回它自己。
     */
    private static void nextRoute(HatState state, ServerLevel level) {
        if (state.type != HatType.ALL) {
            state.route = state.type;
            return;
        }
        // 调试用的「强制路线」：设了某条颜色就每轮都走它（见 HatSettings.setForcedRoute）。
        // 没设（默认 random）才走下面的洗牌袋。
        HatType forced = HatSettings.forcedRouteType();
        if (forced != null) {
            state.route = forced;
            return;
        }
        if (state.routeBag == null || state.routeBagIndex >= state.routeBag.length) {
            state.routeBag = HatType.routeChoices();
            net.minecraft.util.RandomSource random = level.getRandom();
            for (int i = state.routeBag.length - 1; i > 0; i--) {
                int j = random.nextInt(i + 1);
                HatType swap = state.routeBag[i];
                state.routeBag[i] = state.routeBag[j];
                state.routeBag[j] = swap;
            }
            // 跨袋不连色：新袋第一条若和上一轮同色，就换个位置（有别的选择时）
            if (state.routeBag.length > 1 && state.routeBag[0] == state.route) {
                HatType swap = state.routeBag[0];
                state.routeBag[0] = state.routeBag[1];
                state.routeBag[1] = swap;
            }
            state.routeBagIndex = 0;
        }
        state.route = state.routeBag[state.routeBagIndex++];
    }

    /**
     * 强制开一轮（P 键）：附近没有敌人也直接进蓄力。
     *
     * <p>正常流程要 {@link #findNearestEnemy} 找到目标才会起手；这个入口跳过那条判断。
     * 已经在蓄力 / 照射中、或者压根没戴帽子就返回 false。
     */
    public static boolean forceStart(LivingEntity wearer) {
        if (!(wearer.level() instanceof ServerLevel level)) {
            return false;
        }
        HatType type = HatItems.typeOf(hatStack(wearer));
        if (type == null || wearer.isDeadOrDying() || wearer.isSpectator()) {
            return false;
        }
        UUID id = wearer.getUUID();
        HatState state = STATES.get(id);
        if (state != null && (state.charge > 0 || state.flash > 0)) {
            return false;
        }
        if (state == null) {
            state = new HatState();
            STATES.put(id, state);
        }
        if (state.type != type) {
            state.reset();
            state.type = type;
            state.route = type;
        }
        startCharge(wearer, level, state);
        tickingCharge(wearer, level, state);
        return true;
    }

    /** 进入蓄力阶段：重置本轮状态并播放这顶帽子（或「全」这一轮路线）的音乐。 */
    private static void startCharge(LivingEntity wearer, ServerLevel level, HatState state) {
        ItemStack hat = hatStack(wearer);
        // 「全」每轮随机取一条路线（黑/白/红，随机不重复）；三顶原色帽就是它自己。
        // 路线决定本轮的：蓄力/照射时长、BGM、粒子颜色、光柱帧伤、以及帽子渲染颜色。
        nextRoute(state, level);
        // 附魔「闪避」多给几次瞬移闪避；蓄力时长本身**不受附魔影响**（要跟音乐对齐）
        state.charge = HatSettings.chargeTicks(state.route);
        state.blinks = BLINKS_PER_CHARGE + HatEnchants.extraBlinks(hat);
        state.flashBlinks = 0;
        // 成就记账：本轮蓄力还没闪避过、也没挨过打
        state.chargeBlinks = 0;
        state.hitWhileCharging = false;
        // 「三色归一」：「全」每轮走一条路线，三条都集齐就发
        if (state.type == HatType.ALL) {
            HatAchievements.noteRoute(wearer, state.route);
        }
        state.dominionFired = false;
        // 新一轮：上一轮的实际伤害账清掉（破防判定每秒做一次，见 onServerTick）
        resetCycleDamage();
        HatMusic.play(level, wearer, state.route);
        // 路线换了一条：把新颜色告诉客户端（帽子会跟着变那个颜色，见 getArmorTexture）
        HatNetwork.sendRoute(wearer, HatItems.typeOf(hat), state.route);
        // 附魔「预知」不在这里放 —— 它改到**蓄力完成的那一刻**才释放（见 tickingCharge）
    }

    private static void tickingCharge(LivingEntity wearer, ServerLevel level, HatState state) {
        applySlowField(wearer, level);
        // 蓄力全程固定抗性提升 II，蓄力一结束就收（见下面的 clearChargeResistance）
        applyChargeResistance(wearer);
        spawnChargeAura(level, wearer);
        spawnSlowRing(level, wearer, state.route);

        // 附魔「掌控」：蓄力到第 15 秒时，剥夺最近那个敌人的索敌目标
        maybeFireDominion(wearer, level, state);

        state.charge--;
        if (state.charge <= 0) {
            state.charge = 0;
            // 蓄力完成：范围减速解除、抗性解除
            clearSlowField(level, wearer);
            clearChargeResistance(wearer);
            // 迅捷 II：一直挂到本轮循环结束（照射期间每刻续，见 tickingFlash）
            applyCycleSpeed(wearer);
            // 黑帽「残影」：进入照射期的这一瞬间把闪避次数补满
            state.flashBlinks = HatEnchants.afterimageBlinks(hatStack(wearer));
            level.playSound(null, wearer.getX(), wearer.getY(), wearer.getZ(),
                    SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.7F, 1.6F);
            state.flash = HatSettings.flashTicks(state.route);
            state.flashKills = 0;
            // 「不动如山」：红帽蓄力全程一次没被打中，光柱照常放出来了
            if (state.type == HatType.RED && !state.hitWhileCharging) {
                HatAchievements.grant(wearer, HatAchievements.IMMOVABLE);
            }
            // 附魔「预知」：蓄力完成的那一刻释放，30 格内的敌人当场定住 —— 时长按等级取
            // 「本轮循环剩余长度」的 1/3、2/3、3/3（III 级打满，最大值不变）
            fireSoulReapStasis(wearer, level, hatStack(wearer), state.flash);
        }
    }

    // ------------------------------------------------------------------
    // 附魔「掌控」：蓄力第 15 秒，让最近的敌人短暂失去索敌目标
    //
    // 「失去索敌目标」用 LivingChangeTargetEvent 实现：在持续时间里，只要那个生物
    // 试图重新锁定任何目标，就把这次锁定取消掉。这比"只 setTarget(null) 一次"可靠得多
    // —— 否则它下一刻就会把自己重新锁回去。
    // 同时打上发光标记（原版光灵箭用的就是 GLOWING），并把标记的颜色换成粉色，
    // 见 DOMINION_TEAM。标记时长 = 剥夺索敌时长（控多久，光标就亮多久）。
    // 被控住的生物还会**掉转枪口**：身边还有别的敌人的话，每刻的 tickDominionFight
    // 会把它指过去打 —— 见 DOMINION_VICTIM。
    // ------------------------------------------------------------------

    /** 被「掌控」剥夺索敌的生物 -> 解禁时刻（服务器刻）。 */
    private static final Map<UUID, Long> DOMINION_LOCKED = new HashMap<>();

    /** 被「掌控」标记的生物 -> 它原本所在的队伍名（{@code ""} 表示原本没有队伍）。 */
    private static final Map<UUID, String> DOMINION_ORIGINAL_TEAM = new HashMap<>();

    /**
     * 被「掌控」标记的生物 -> 入队时实际用的计分板名字。
     *
     * <p>不能等到解除时再用 {@code UUID.toString()} 顶替：生物（尤其是别的模组的生物）
     * 有可能重写 {@code getScoreboardName()}，两边对不上时原版 {@code removePlayerFromTeam}
     * 会直接抛 {@link IllegalStateException} 崩服。入队时是什么名字，出队就用什么名字。
     */
    private static final Map<UUID, String> DOMINION_NAME = new HashMap<>();

    /**
     * 「掌控」标记用的临时队伍名，存在的唯一目的就是让发光描边变成粉色。
     *
     * <p>原版发光描边的颜色**只能**取自 {@code Entity#getTeamColor()}，也就是所在队伍的
     * 颜色（没有队伍就是白色 —— 光灵箭给的就是这个白框）。所以「换个颜色」在原版里
     * 唯一的正规做法就是临时入队：不需要 mixin、不碰客户端渲染，两个加载器表现完全一致。
     *
     * <p>代价是原版队伍颜色只有 16 档（{@link ChatFormatting}），偏粉的那一档是
     * {@link ChatFormatting#LIGHT_PURPLE}（#FF55FF）。更精确的粉色得上 mixin 改客户端，
     * 风险大得多，没做。
     */
    private static final String DOMINION_TEAM = "hatmod_dominion";

    private static void maybeFireDominion(LivingEntity wearer, ServerLevel level, HatState state) {
        if (state.dominionFired) {
            return;
        }
        // 蓄力总长各不相同（黑 440 / 白 358 / 红 588），但都是在开始后的第 300 刻触发
        if (HatSettings.chargeTicks(state.route) - state.charge < HatEnchants.DOMINION_TRIGGER_TICK) {
            return;
        }
        int ticks = HatEnchants.dominionTicks(hatStack(wearer));
        if (ticks <= 0) {
            // 没附魔「掌控」：标记一下，本轮不要再空转检查
            state.dominionFired = true;
            return;
        }

        // 附魔等级越高，一次控住的人越多（I 级 2 人，每级 +2，封顶 5 人）。
        int count = HatEnchants.dominionTargets(hatStack(wearer));
        List<LivingEntity> targets = findNearestEnemies(wearer, count);
        if (targets.isEmpty()) {
            return; // 这一轮还没有敌人，下一轮再说（不置 fired，允许在窗口内补触发）
        }
        state.dominionFired = true;

        long until = (long) (level.getServer().getTickCount() + ticks);
        for (LivingEntity target : targets) {
            DOMINION_LOCKED.put(target.getUUID(), until);
            markWithDominionTeam(level.getServer(), target);
            if (target instanceof Mob mob) {
                mob.setTarget(null);
            }
            // 光灵标记：原版光灵箭给受害者挂的就是这个效果。时长跟索敌时长一致。
            target.addEffect(new MobEffectInstance(MobEffects.GLOWING, ticks, 0, false, false, true));
            // 「掌控」的另一半：被控住的家伙**破绽全开**（受伤 +50%，见 HatProtection）。
            // 对星之魔女这种「施法目标根本不走 Mob#target、改目标等于空转」的 Boss，
            // 这是唯一还吃得上的部分。
            target.addEffect(new MobEffectInstance(HatEffects.VULNERABLE, ticks, 0, false, false, true));
            level.playSound(null, target.getX(), target.getY(), target.getZ(),
                    SoundEvents.ENDER_EYE_LAUNCH, SoundSource.PLAYERS, 1.0F, 0.7F);
        }

        // 表现层：戴帽者眼部炸出一小团亮光（纯渲染，不参与任何判定）
        spawnDominionEyeFlash(level, wearer);
    }

    /**
     * 「掌控」发动时戴帽者**眼部**闪出的一团亮光。
     *
     * <p><b>纯粹是表现</b>：只有几颗粒子，既不造成伤害、也不写任何状态 —— 就是让这一下
     * 「看得见」而已。核心用原版的 {@code FLASH}（烟花爆炸时那颗白闪），外面再撒一圈
     * {@code END_ROD} 把亮度撑开一点。
     *
     * <p>位置取眼睛再**沿视线往前挪一点**：就落在眼睛上。往前挪是为了不正好压在
     * 第一人称相机的近裁剪面上（那样自己反而什么都看不到），挪一点点旁人看仍是「从眼睛闪出来」。
     */
    private static void spawnDominionEyeFlash(ServerLevel level, LivingEntity wearer) {
        Vec3 eyes = wearer.getEyePosition().add(wearer.getViewVector(1.0F).scale(0.3D));
        level.sendParticles(ParticleTypes.FLASH, eyes.x, eyes.y, eyes.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        level.sendParticles(ParticleTypes.END_ROD, eyes.x, eyes.y, eyes.z, 12, 0.12D, 0.12D, 0.12D, 0.02D);
    }

    /**
     * 到点的「掌控」自动解除：撤销临时队伍（把粉色描边收掉、原本的队伍还回去）。
     *
     * <p>被控的生物中途死掉、走远、卸载都不影响清理 —— 这里只用到 UUID 和解禁时刻，
     * 不需要生物本人还在场。
     */
    private static void releaseStaleDominion(MinecraftServer server, int now) {
        if (DOMINION_LOCKED.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Long>> iterator = DOMINION_LOCKED.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Long> entry = iterator.next();
            if (now < entry.getValue()) {
                continue;
            }
            iterator.remove();
            DOMINION_VICTIM.remove(entry.getKey());
            clearDominionTeam(server, entry.getKey());
        }
    }

    /**
     * 把目标临时编进粉色队伍，让它的发光描边变粉。
     *
     * <p>入队前记下它原本的队伍（没有记 {@code ""}），解除时还原 —— 不能把人家原本的队伍顶掉。
     */
    private static void markWithDominionTeam(MinecraftServer server, LivingEntity target) {
        Scoreboard scoreboard = server.getScoreboard();
        String name = target.getScoreboardName();
        UUID id = target.getUUID();
        try {
            PlayerTeam current = scoreboard.getPlayersTeam(name);
            PlayerTeam team = dominionTeam(scoreboard);
            if (current != team) {
                DOMINION_ORIGINAL_TEAM.put(id, current == null ? "" : current.getName());
            }
            DOMINION_NAME.put(id, name);
            scoreboard.addPlayerToTeam(name, team);
        } catch (Exception e) {
            // 计分板是全局共享的，别的模组随时可能动它。染色只是锦上添花，绝不能让它崩服。
            DOMINION_ORIGINAL_TEAM.remove(id);
            DOMINION_NAME.remove(id);
            HatMod.LOGGER.warn("[hatmod] 「掌控」染色失败，已跳过（技能本身不受影响）：{}", name, e);
        }
    }

    /**
     * 撤销临时队伍：退出粉色队，原本有队伍的话还回去。
     *
     * <p><b>每一步都要先确认"现在确实是那样"再动手。</b>到点时目标很可能已经死了、
     * 被卸载、或者被别的模组挪走了队伍 —— 这时原版 {@code removePlayerFromTeam} 会直接抛
     * {@link IllegalStateException}（"not on any team"）。这句话跑在服务器主循环里，
     * 一抛就是整个服务器崩掉。
     */
    private static void clearDominionTeam(MinecraftServer server, UUID id) {
        String original = DOMINION_ORIGINAL_TEAM.remove(id);
        String name = DOMINION_NAME.remove(id);
        if (original == null || name == null) {
            return;
        }
        Scoreboard scoreboard = server.getScoreboard();
        try {
            PlayerTeam team = scoreboard.getPlayerTeam(DOMINION_TEAM);
            if (team != null && scoreboard.getPlayersTeam(name) == team) {
                scoreboard.removePlayerFromTeam(name, team);
            }
            if (!original.isEmpty() && scoreboard.getPlayersTeam(name) == null) {
                PlayerTeam previous = scoreboard.getPlayerTeam(original);
                if (previous != null) {
                    scoreboard.addPlayerToTeam(name, previous);
                }
            }
        } catch (Exception e) {
            HatMod.LOGGER.warn("[hatmod] 撤销「掌控」染色失败，已忽略：{}", name, e);
        }
    }

    /** 取「掌控」用的粉色队伍，没有就建一个。只设颜色，其它选项一律保持原版默认。 */
    private static PlayerTeam dominionTeam(Scoreboard scoreboard) {
        PlayerTeam team = scoreboard.getPlayerTeam(DOMINION_TEAM);
        if (team == null) {
            team = scoreboard.addPlayerTeam(DOMINION_TEAM);
            team.setColor(ChatFormatting.LIGHT_PURPLE);
        }
        return team;
    }

    /**
     * 生物想锁定目标时介入：蓄力中的戴帽生物、以及被「掌控」剥夺索敌的生物，
     * 一律不许选目标。
     *
     * <p>除了上面两条，还有两条规则：
     *
     * <ul>
     *   <li>挂着「神隐」的目标谁都别想锁上 —— 这是原版隐身之外的那层保险
     *       （见 {@link HatEffects}）；</li>
     *   <li>被「掌控」控住的生物，只准锁**我们指给它的那个对手** ——
     *       「内讧」要打得起来，就不能被这一层拦截误伤，见 {@link #isDominionVictim}。</li>
     * </ul>
     *
     * <p><b>做法是「取消事件」，不是「把新目标置空」</b> —— 这里踩过一个大坑：
     * 走 Brain 的生物（女仆、猪灵、监守者这类）索敌走的是 {@code StartAttacking}，
     * 它拿到事件之后会把 {@code getNewAboutToBeSetTarget()} 原样塞进
     * {@code MemoryAccessor.set()}，也就是 {@code Optional.of(...)}。如果只把新目标置成 null，
     * 那就成了 {@code Optional.of(null)}，<b>当场 NPE 崩服</b>。
     * 1.20.1 的 Forge 补丁对这一句没判空、会真的炸（崩在 {@code StartAttacking} 里，
     * 表面上跟本模组毫无关系）；1.21 的 NeoForge 补丁加了判空所以侥幸没事 ——
     * 但两版统一用取消，才不用去赌加载器的补丁细节。
     *
     * <p>也不能无脑取消：{@code Mob#setTarget(null)}（真正要「清掉目标」的动作）自己也会
     * 走这个事件，无脑取消会把清空一起拦下，目标就永远清不掉。所以按目标类型分开处理：
     * <ul>
     *   <li>BEHAVIOR_TARGET（Brain 那条路）—— 一律取消。那一侧只可能是「抢新目标」，
     *       清空走的是擦记忆，不经过这里。</li>
     *   <li>MOB_TARGET（{@code Mob#setTarget} 那条路）—— 只在「要锁一个非空目标」时取消，
     *       置空（清目标）放行。</li>
     * </ul>
     */
    @SubscribeEvent
    public static void onLivingChangeTarget(LivingChangeTargetEvent event) {
        // 这个事件每个生物索敌时都要过一遍，而且事件总线的「监听器抛异常」报错路径
        // 本身可能踩到 log4j 的 LinkageError 把服务器线程打死 —— 这里自己兜住。
        try {
            LivingEntity entity = event.getEntity();
            LivingEntity newTarget = event.getNewAboutToBeSetTarget();

            // ① 挂着「神隐」的目标：谁都别想锁上（原版隐身已经能骗过大部分生物，这里补上剩下的）
            if (newTarget != null && newTarget.hasEffect(HatEffects.VEIL)) {
                blockTargeting(event);
                return;
            }
            // ② 被「掌控」剥夺索敌的生物：只放行我们指给它的那个对手，其余一律拦下
            if (isDominionLocked(entity) && !isDominionVictim(entity, newTarget)) {
                blockTargeting(event);
            }
        } catch (Throwable ignored) {
            // 索敌拦截失效远好过崩服
        }
    }

    /** 见 {@link #onLivingChangeTarget} 的说明：该取消就取消，绝不给 Brain 递 null。 */
    private static void blockTargeting(LivingChangeTargetEvent event) {
        boolean brain = event.getTargetType() == LivingChangeTargetEvent.LivingTargetType.BEHAVIOR_TARGET;
        if (brain || event.getNewAboutToBeSetTarget() != null) {
            event.setCanceled(true);
        }
    }

    /** 这个生物当前是不是被「掌控」剥夺了索敌（到点就不算，交给每刻的清理收尾）。 */
    private static boolean isDominionLocked(LivingEntity entity) {
        Long until = DOMINION_LOCKED.get(entity.getUUID());
        if (until == null) {
            return false;
        }
        MinecraftServer server = entity.level().getServer();
        return server != null && server.getTickCount() < until;
    }

    // ------------------------------------------------------------------
    // 「掌控」的第二层：被控住的生物掉转枪口打自己人
    // ------------------------------------------------------------------

    /** 被控生物 -> 我们指给它的对手（UUID）。 */
    private static final Map<UUID, UUID> DOMINION_VICTIM = new HashMap<>();
    /** 内讧时找对手的搜索半径（格）。 */
    private static final double DOMINION_FIGHT_RADIUS = 16.0D;

    /**
     * 被「掌控」控住的生物在解禁之前，只要身边还有**别的敌人**，就会被指过去打它 ——
     * 「连自己人都不认得了」。
     *
     * <p>每刻只在两种情况下动手：现在没有目标、或者目标已经死了。否则会被反复改来改去，
     * 变成原地转圈。对手的搜索半径刻意比光柱近（16 格）：这一下是「让它们先自相残杀一会儿」，
     * 不是全图点名。
     */
    private static void tickDominionFight(LivingEntity entity) {
        if (!(entity instanceof Mob mob) || !isDominionLocked(mob)) {
            return;
        }
        // 监守者的目标在 Brain 里、不在 Mob#target 里：先把它锁着的那个目标甩掉，
        // 下面这句 getTarget() 才看得到「它现在没目标」。
        if (mob instanceof Warden warden) {
            blindLockedWarden(warden);
        }
        LivingEntity current = mob.getTarget();
        // 已经在打别的敌人了：不打扰（免得每刻改来改去，原地转圈）
        if (current != null && current.isAlive() && !(current instanceof Player)) {
            return;
        }
        LivingEntity victim = currentVictim(mob);
        if (victim == null) {
            return; // 身边没有别的敌人：那就照旧发呆，等解禁
        }
        DOMINION_VICTIM.put(mob.getUUID(), victim.getUUID());
        if (mob instanceof Warden warden) {
            // 监守者的目标是 Brain 记忆，只有它自己的 setAttackTarget 写得进去
            warden.setAttackTarget(victim);
            return;
        }
        // 有的生物（星之魔女这种 Boss）重写了 setTarget：只要它还看得见挑战者，
        // 就会把传进去的目标换成玩家。先走正规路径，没生效再直接写字段。
        mob.setTarget(victim);
        if (mob.getTarget() != victim) {
            writeTargetField(mob, victim);
        }
    }

    /** 上次指给它的对手还活着就继续用，死了 / 没了就重新挑一个。 */
    @Nullable
    private static LivingEntity currentVictim(Mob mob) {
        UUID assigned = DOMINION_VICTIM.get(mob.getUUID());
        if (assigned != null) {
            for (Entity entity : mob.level().getEntities(mob, mob.getBoundingBox().inflate(DOMINION_FIGHT_RADIUS * 2.0D))) {
                if (entity instanceof LivingEntity living && living.isAlive()
                        && living.getUUID().equals(assigned)) {
                    return living;
                }
            }
        }
        return findDominionVictim(mob);
    }

    /** {@code Mob#target} 字段：只按**类型**找，不认名字（线上 1.20.1 是 SRG 的 {@code f_21344_}）。 */
    @Nullable
    private static Field TARGET_FIELD;
    private static boolean TARGET_FIELD_READY;

    /**
     * 把目标直接写进 {@code Mob#target}，绕开被重写的 {@code setTarget}。
     *
     * <p>反射拿字段：先看 {@link Mob} 里类型为 {@link LivingEntity} 的字段，且必须**只有一条** ——
     * 多于一条就不猜了（宁可不改，也不能写错字段）。找不到就什么都不做，行为退化成原来的样子。
     */
    private static void writeTargetField(Mob mob, LivingEntity victim) {
        Field field = targetField();
        if (field == null) {
            return;
        }
        try {
            field.set(mob, victim);
        } catch (Throwable ignored) {
            // 反射被安全策略挡住也无所谓：这一下只是「让 Boss 转火」的加强，不能因此崩服
        }
    }

    @Nullable
    private static Field targetField() {
        if (TARGET_FIELD_READY) {
            return TARGET_FIELD;
        }
        TARGET_FIELD_READY = true;
        Field found = null;
        for (Field field : Mob.class.getDeclaredFields()) {
            if (field.getType() != LivingEntity.class || java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if (found != null) {
                return null; // 不止一条，不猜
            }
            found = field;
        }
        if (found != null) {
            try {
                found.setAccessible(true);
            } catch (Throwable ignored) {
                return null;
            }
        }
        TARGET_FIELD = found;
        return TARGET_FIELD;
    }

    /** 内讧的对手：周围最近的另一个**敌意生物**（不含自己、不含玩家和己方）。 */
    private static LivingEntity findDominionVictim(Mob mob) {
        AABB area = mob.getBoundingBox().inflate(DOMINION_FIGHT_RADIUS);
        LivingEntity best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (Entity entity : mob.level().getEntities(mob, area)) {
            if (!(entity instanceof LivingEntity living) || living == mob || !living.isAlive()) {
                continue;
            }
            if (!(living instanceof Enemy)) {
                continue; // 只挑敌意生物：玩家、宠物、牛羊都不在名单里
            }
            double distSq = mob.distanceToSqr(living);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = living;
            }
        }
        return best;
    }

    /** 这个新目标是不是我们指给它的那个对手（是的话，索敌拦截放行）。 */
    private static boolean isDominionVictim(LivingEntity entity, LivingEntity newTarget) {
        return newTarget != null && newTarget.getUUID().equals(DOMINION_VICTIM.get(entity.getUUID()));
    }

    private static void tickingFlash(LivingEntity wearer, ServerLevel level, HatState state) {
        // 每刻重新锁最近的敌人：更近的顶掉旧的，也就是会「转火」。
        // 所有帽子天生同时打 BASE_BEAM_COUNT 道；红帽附魔「光柱」在此之上每级再加 1 道。
        // 每道光各锁一个敌人，敌人不够时基础那几道可以无衰减地叠在同一个目标上
        // （附魔多出来的道仍按边际递减），见 beamTargets / damageBeam。
        ItemStack hat = hatStack(wearer);
        int beamCount = BASE_BEAM_COUNT + HatEnchants.conflagrationExtraBeams(hat);
        List<LivingEntity> targets = beamTargets(wearer, beamCount);
        logBeamLock(wearer, targets);

        // 「正在照射 / 锁定谁」全部走本模组自己的包（BeamTargets），不依赖任何状态效果。
        // 多道光柱要把所有目标的 id 一起发过去，客户端才能各自画一条。
        syncBeam(wearer, state, true, targets);

        // 红帽：照射期间力量 I（每刻刷新）。原先附魔「炽怒」能把它顶到力量 IV，已废弃。
        // 必须赶在 damageBeam 前面给：光柱伤害会读这份力量折成倍率（见 beamPowerMultiplier），
        // 否则每轮照射的第一刻会漏掉加成。
        if (state.type.has(HatType.Trait.STRENGTH)) {
            wearer.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, STRENGTH_TICKS, 0, false, false, true));
        }

        damageBeam(level, wearer, state.type, state.route, hat, targets);
        // 每道光柱都在轴线附近撒一点火花（没有目标时沿视线撒，同样按道数散开）
        if (targets.isEmpty()) {
            Vec3 view = wearer.getViewVector(1.0F).normalize();
            for (int i = 0; i < beamCount; i++) {
                spawnBeamSparks(level, wearer, fanDirection(view, i, beamCount));
            }
        } else {
            for (int i = 0; i < targets.size(); i++) {
                Vec3 aim = fanDirection(beamDirection(wearer, targets.get(i)), i, targets.size());
                spawnBeamSparks(level, wearer, aim);
            }
        }

        // 本轮循环的迅捷 II：每刻续着，直到这一轮循环结束
        applyCycleSpeed(wearer);

        // 附魔「光辉」支援：照射期间自己与光环内的友军获得「神隐」（强化隐身）
        applyRadianceVeil(wearer, level);

        state.flash--;
        if (state.flash <= 0) {
            state.flash = 0;
            syncBeam(wearer, state, false, List.of());
            state.blinks = 0;
            state.flashBlinks = 0;
            // 本轮循环到此结束：迅捷收掉（下一轮蓄力开始时会再给一次）
            clearCycleSpeed(wearer);
            // 「全」帽专属：**照射结束的那一刻**才放出 4 个虚影 —— 光柱收了，影子留下来
            // 再缠 10 秒（绕最近的敌人转圈并嘲讽）。
            if (state.type == HatType.ALL) {
                HatSummons.summonAll(level, wearer, HatSummons.SHADE_LIFE_TICKS, state.route);
            }
            // 照完之后重新进入循环：附近还有敌人就再次蓄力（并从循环开始处再放一次音乐）
            if (findNearestEnemy(wearer) != null) {
                startCharge(wearer, level, state);
            } else {
                state.charge = 0;
                HatMusic.stop(level, wearer, state.route);
            }
        }
    }

    /**
     * 所有帽子**天生**同时打几道光柱。
     *
     * <p>这 {@code 2} 道是「基础光柱」：各自锁一个敌人；敌人不够时**可以叠在同一个敌人身上，
     * 且完全不衰减**（两道各自满伤）。附魔「光柱」是在此之上再加道数，见
     * {@link HatEnchants#conflagrationExtraBeams}。
     */
    public static final int BASE_BEAM_COUNT = 2;

    /**
     * 单道光柱的伤害倍率：基础 2 道把原来 1 道的伤害劈成两半，每道 ×0.5，
     * 两道合计与改动前的一道光柱相同。
     *
     * <p>附魔「光柱」多出来的道也按这个倍率结算（它们同样是「一道光柱」），
     * 再另行叠加 {@link #BEAM_STACK_FACTOR} 的边际递减。
     */
    public static final float BEAM_DAMAGE_SCALE = 0.5F;

    /**
     * 一次光柱结算拆成几次 {@code hurt()}。
     *
     * <p>不少 Boss（下界亚伯伦、Witheria、星之魔女……）在 {@code hurt()} 里做了「每击上限」：
     * 一次打 100 点和打 10 点掉的血一样多。这个上限是按**单次 hurt** 结算的，所以把同一份伤害
     * 拆成几段、每段都在上限之下，总伤害就上去了 —— 不改对方代码的前提下，这是最稳的破防手段。
     */
    public static final int PIERCE_SPLIT = 4;

    // ------------------------------------------------------------------
    // 「破防」：一轮打不到最大生命的一半，就改成按帽子伤害强写血量
    //
    // 判据全部来自 {@link net.minecraft.world.entity.LivingEntity#getHealth} 的差值 ——
    // 不猜对方减免了多少，而是量出「我们该打多少」和「实际掉了多少」的差。
    // ------------------------------------------------------------------

    /** 一轮（蓄力 + 照射）里每个目标：[0] 实际掉的血，[1] 帽子**应该**打出的伤害。 */
    private static final Map<UUID, float[]> CYCLE_DAMAGE = new HashMap<>();
    /** 下一轮要「破防」的目标：不再走常规减伤，直接按帽子该给的伤害写血。 */
    private static final Set<UUID> FORCE_TARGETS = new HashSet<>();
    /**
     * 破防判定线：**实际掉血 / 应该造成的伤害** 低于这个比例，才算「伤害被防御吃掉了」。
     *
     * <p>刻意不用「最大生命的百分比」当分母 —— 那样血牛（血极厚但没有减伤）会被误判：
     * 它一轮掉的血本来就不可能到最大生命的一半，可它一点伤害都没吃。
     */
    private static final float FORCE_THRESHOLD = 0.5F;

    /**
     * 多道光柱堆叠在同一目标上的**边际递减**系数：同一目标上第 m 道（从 0 数）乘
     * {@code BEAM_STACK_FACTOR^m}。
     *
     * <p>只作用于**附魔「光柱」加出来的**那些道 —— 基础 {@link #BASE_BEAM_COUNT} 道
     * 叠在同一个人身上不打折。敌人少于光柱数时（比如单挑一个 Boss），多余的附魔光柱会落回
     * 已有目标而不是白白浪费，但每多叠一道就打个折：三道光柱全堆一个人是
     * 1 + 0.4 + 0.16 = 1.56 倍（相对一道附魔光柱），而不是 3 倍。
     * 调大它红帽单挑更强，调小则更接近「一人一道」。
     */
    private static final float BEAM_STACK_FACTOR = 0.4F;

    /**
     * 这次照射每道光柱各自锁谁：由近到远取敌人，**敌人不够时多余的光柱堆叠到已有目标上**。
     *
     * <p>返回列表的长度恒为 {@code beamCount}（有敌人时），下标 i 就是「第 i 道光柱」：
     * 目标取 {@code enemies[i % n]}，堆叠轮次 {@code i / n}。于是敌人够多时一人一道。
     *
     * <p>注意区分两种道：下标小于 {@link #BASE_BEAM_COUNT} 的是**基础光柱**，堆叠时不衰减；
     * 之后的是附魔「光柱」加出来的，堆叠时按 {@link #BEAM_STACK_FACTOR} 递减
     * （扣血倍率在 {@link #damageBeam} 里算）。
     * 一个敌人都没有时返回空列表（客户端仍会沿视线画出 {@link #BASE_BEAM_COUNT} 道，
     * 见 {@link #tickingFlash}）。
     */
    /** 上一次打过的光柱锁定组合（组合变了才打日志，免得每刻刷屏）。 */
    private static final Map<UUID, String> BEAM_LOCK_SIG = new HashMap<>();

    /**
     * 记一条「光柱现在锁着谁」。
     *
     * <p>只在破防模式下打，而且同一种组合只打一次 —— 用来回答「光束为什么打不死它」：
     * 是没锁上、还是锁到了别的实体（Witheria 的激光 / 炮塔 / 爆炸云就吃过这个亏）。
     */
    private static void logBeamLock(LivingEntity wearer, List<LivingEntity> targets) {
        if (!HatSettings.forceBreak()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (LivingEntity target : targets) {
            if (sb.length() > 0) {
                sb.append("，");
            }
            sb.append(target.getType()).append('(')
                    .append(Math.round(wearer.distanceTo(target))).append("格)");
        }
        String signature = sb.toString();
        if (signature.equals(BEAM_LOCK_SIG.get(wearer.getUUID()))) {
            return;
        }
        BEAM_LOCK_SIG.put(wearer.getUUID(), signature);
        HatMod.LOGGER.warn("[hatmod] 光柱锁定：{}",
                signature.isEmpty() ? "射程内没有合法敌人" : signature);
    }

    private static List<LivingEntity> beamTargets(LivingEntity wearer, int beamCount) {
        List<LivingEntity> enemies = findNearestEnemies(wearer, beamCount);
        if (enemies.isEmpty() || enemies.size() >= beamCount) {
            return enemies;
        }
        List<LivingEntity> beams = new ArrayList<>(beamCount);
        for (int i = 0; i < beamCount; i++) {
            beams.add(enemies.get(i % enemies.size()));
        }
        return beams;
    }

    /** 列表里有几个不同的目标（堆叠时同一目标会出现多次）。按身份比较，列表很短不值得上 Set。 */
    private static int distinctTargets(List<LivingEntity> targets) {
        List<LivingEntity> seen = new ArrayList<>(targets.size());
        for (LivingEntity target : targets) {
            boolean duplicate = false;
            for (LivingEntity previous : seen) {
                if (previous == target) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                seen.add(target);
            }
        }
        return seen.size();
    }

    /**
     * 多道光柱的**散射角**：把瞄准方向绕一根垂直轴微微转开，让几道光看得出是「几道」，
     * 而不是完全重合的一束。角度很小（见 {@link #BEAM_SPREAD_DEGREES}），目标仍牢牢落在
     * 35° 的判定锥内，所以伤害不会因为散射而漏。
     *
     * <p>服务端判定和客户端绘制调用的是同一个函数，两边永远一致。
     */
    public static Vec3 fanDirection(Vec3 aim, int index, int count) {
        if (count <= 1) {
            return aim;
        }
        // 绕一根与瞄准方向垂直的轴转，光束就呈扇形展开；瞄得越竖直越要用水平轴兜底。
        Vec3 axis = Math.abs(aim.y) > 0.9D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
        double angle = Math.toRadians(BEAM_SPREAD_DEGREES) * (index - (count - 1) / 2.0D);
        return rotateAround(aim, axis, angle);
    }

    /** 罗德里格斯公式：把向量 {@code v} 绕单位轴 {@code axis} 旋转 {@code angle} 弧度。 */
    private static Vec3 rotateAround(Vec3 v, Vec3 axis, double angle) {
        Vec3 k = axis.normalize();
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        Vec3 cross = k.cross(v);
        double dot = k.dot(v);
        return v.scale(cos).add(cross.scale(sin)).add(k.scale(dot * (1.0D - cos)));
    }

    /** 每道光柱相对瞄准方向偏开的角度（度）。 */
    private static final double BEAM_SPREAD_DEGREES = 6.0D;

    /**
     * 把「正在照射 / 锁定谁」同步给客户端，只在状态真的变了时才发包。
     *
     * <p>红帽可以同时打多道光柱，所以这里发的是一串目标 id（每道光一个）。
     * 停止照射时发空数组，客户端会把这个戴帽者整个移除。
     */
    private static void syncBeam(LivingEntity wearer, HatState state, boolean beaming, List<LivingEntity> targets) {
        int[] ids = new int[targets.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = targets.get(i).getId();
        }
        if (state.beamSynced == beaming && Arrays.equals(state.targetIds, ids)) {
            return;
        }
        state.beamSynced = beaming;
        state.targetIds = ids;
        HatNetwork.sendBeamTarget(wearer, beaming, ids);
    }

    /** 把「本轮不再照射」告诉客户端（摘帽 / 死亡 / 换帽 / 停照时统一走这里）。 */
    private static void clearBeamSync(LivingEntity wearer, HatState state) {
        if (state.beamSynced || (state.targetIds != null && state.targetIds.length > 0)) {
            state.beamSynced = false;
            state.targetIds = new int[0];
            HatNetwork.sendBeamTarget(wearer, false, new int[0]);
        }
    }

    /** 光柱方向：优先指向锁定的敌人；没有目标时沿视线方向。 */
    private static Vec3 beamDirection(LivingEntity wearer, LivingEntity target) {
        Vec3 origin = beamOrigin(wearer, 1.0F);
        if (target != null) {
            Vec3 to = target.getBoundingBox().getCenter().subtract(origin);
            if (to.lengthSqr() > 1.0E-6D) {
                return to.normalize();
            }
        }
        return wearer.getViewVector(1.0F).normalize();
    }

    /**
     * 光柱起点：脚底往上 {@link #BEAM_ORIGIN_HEIGHT_RATIO} 倍身高处（胯部），<b>不是眼睛</b>。
     *
     * <p>方向、命中判定、粒子、以及客户端的几何绘制全部从这里出发，改一处就全改。
     */
    public static Vec3 beamOrigin(LivingEntity wearer, float partialTick) {
        Vec3 base = wearer.getPosition(partialTick);
        return base.add(0.0D, wearer.getBbHeight() * BEAM_ORIGIN_HEIGHT_RATIO, 0.0D);
    }

    /** 白帽的永久增益：持续时间 -1（无限），摘下帽子时再清掉。附魔「疾行」每级把等级抬一级。 */
    private static void applyPermanentBuffs(LivingEntity wearer, HatType type) {
        if (!type.has(HatType.Trait.PERMANENT_BUFFS)) {
            return;
        }
        int amplifier = HatEnchants.permanentBuffBonus(hatStack(wearer));
        wearer.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, MobEffectInstance.INFINITE_DURATION, amplifier, false, false, true));
        // 跳跃封顶在 II：跳太高反而不好用，所以「疾行」只把迅捷继续往上抬。
        int jumpAmplifier = Math.min(amplifier, MAX_PERMANENT_JUMP_AMPLIFIER);
        wearer.addEffect(new MobEffectInstance(MobEffects.JUMP, MobEffectInstance.INFINITE_DURATION, jumpAmplifier, false, false, true));
    }

    /**
     * 本轮循环的迅捷 II：蓄力一结束就给上，照射期间每刻续，直到这一轮循环结束。
     *
     * <p>只挂 {@link #SPEED_AMPLIFIER} 这一档、有限时长 —— 收的时候（{@link #clearCycleSpeed}）
     * 就是靠这两个特征认出「自己那一份」，白帽「疾行」的永久迅捷（无限时长）我们不碰。
     */
    private static void applyCycleSpeed(LivingEntity wearer) {
        wearer.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED,
                SPEED_TICKS, SPEED_AMPLIFIER, false, false, true));
    }

    /** 收掉本轮循环的迅捷（下一轮循环开始、转回待机、摘帽 / 换帽时都走这里）。 */
    private static void clearCycleSpeed(LivingEntity wearer) {
        MobEffectInstance speed = wearer.getEffect(MobEffects.MOVEMENT_SPEED);
        if (speed != null && !speed.isInfiniteDuration() && speed.getAmplifier() == SPEED_AMPLIFIER) {
            wearer.removeEffect(MobEffects.MOVEMENT_SPEED);
        }
    }

    /**
     * 附魔「光辉」支援：照射期间，戴帽者和**时间差力场圈内的所有队友**获得「神隐」。
     *
     * <p>范围**直接跟着时间差力场走**（{@code slowRadius(wearer)}：基础 5 格，
     * 「缓速」每级再 +1 格）—— 于是「光辉」和「缓速」天然打配合：缓速把场子撑大，
     * 神隐就跟着罩住更多队友。
     *
     * <p>队友判据复用 {@link #isFriendlyTo}，敌人不在支援之列。
     *
     * <p>时长按「光辉」等级算（6/8/10 秒，{@link HatEnchants#veilDurationTicks}）。
     * 只在**还没有**、或者快到期（剩余不足 {@link #VEIL_REFRESH_MARGIN}）时才补一次：
     * 每刻无脑续会把原版逼得每刻重推一遍隐身标记，而隐身标记是本模组自己置的，
     * 会被顺手清掉，表现上就是神隐期间隐身一闪一闪。
     */
    private static void applyRadianceVeil(LivingEntity wearer, ServerLevel level) {
        int ticks = HatEnchants.veilDurationTicks(hatStack(wearer));
        if (ticks <= 0) {
            return;
        }
        applyVeil(wearer, ticks);
        AABB area = wearer.getBoundingBox().inflate(slowRadius(wearer));
        for (Entity entity : level.getEntities(wearer, area)) {
            if (!(entity instanceof LivingEntity living) || living == wearer) {
                continue;
            }
            if (!living.isAlive() || living.isSpectator()) {
                continue;
            }
            if (!isFriendlyTo(wearer, living)) {
                continue;
            }
            applyVeil(living, ticks);
        }
    }

    /**
     * 「完整版神隐」：整段效果期间都保证清仇恨。
     *
     * <p>普通神隐是削过的 —— 只保刚挂上的前 3 秒，之后就是普通隐身。这里给脑蚀链路用，
     * 效果挂多久就清多久仇恨，一直持续到第一次「Nope, too late」。
     */
    public static void grantFullVeil(LivingEntity living, int ticks) {
        if (ticks <= 0) {
            return;
        }
        living.addEffect(new MobEffectInstance(HatEffects.VEIL, ticks, 0, false, false, true));
        VEIL_AGGRO_FREE_UNTIL.put(living.getUUID(), living.level().getGameTime() + ticks);
        // 立刻甩一次，别等下一 tick
        tickVeil(living);
    }

    // ------------------------------------------------------------------
    // 发光标记
    // ------------------------------------------------------------------

    /**
     * 给目标挂上发光（原版轮廓色，白色）：被音波定死的敌人一直亮着，好找。
     *
     * <p>以前为了让轮廓变黑，把它临时塞进一支黑色队伍（原版轮廓色取自队伍颜色），
     * 但那会把别人模组的队伍关系一起改掉，代价太大 —— 现在就是普通发光。
     */
    public static void markGlow(LivingEntity target, int ticks) {
        if (ticks <= 0) {
            return;
        }
        target.addEffect(new MobEffectInstance(MobEffects.GLOWING, ticks + 5, 0, false, false, false));
    }

    /** 还没有神隐、或者快到期了才补一次；已经有了且时间还早就不动它。 */
    private static void applyVeil(LivingEntity living, int ticks) {
        MobEffectInstance current = living.getEffect(HatEffects.VEIL);
        if (current == null) {
            // 刚挂上：从头开始算「保证清仇恨」的 3 秒
            VEIL_AGGRO_FREE_UNTIL.put(living.getUUID(),
                    living.level().getGameTime() + VEIL_AGGRO_FREE_TICKS);
        }
        if (current == null || current.getDuration() < VEIL_REFRESH_MARGIN) {
            living.addEffect(new MobEffectInstance(HatEffects.VEIL, ticks, 0, false, false, true));
        }
    }

    /** 只清掉「无限时长」的那份增益，不会误删玩家自己喝的迅捷药水。 */
    private static void clearPermanentBuffs(LivingEntity wearer) {
        MobEffectInstance speed = wearer.getEffect(MobEffects.MOVEMENT_SPEED);
        if (speed != null && speed.isInfiniteDuration()) {
            wearer.removeEffect(MobEffects.MOVEMENT_SPEED);
        }
        MobEffectInstance jump = wearer.getEffect(MobEffects.JUMP);
        if (jump != null && jump.isInfiniteDuration()) {
            wearer.removeEffect(MobEffects.JUMP);
        }
    }


    /**
     * 饰品栏里那顶帽子的护甲值。
     *
     * <p>Curios 的 head 槽**不会**替物品结算 {@code ArmorItem} 自带的那条护甲属性
     * （它只认 {@code ICurioItem#getAttributeModifiers}，本模组的帽子没实现），所以帽子放进
     * 饰品栏之后一直「只有效果、没有护甲」。这里按 {@link HatItems#HAT_ARMOR} 补一条瞬态修饰符。
     *
     * <p>和头盔槽那顶**互不影响**：头盔槽那份是 {@code ArmorItem} 自己的原版装备加成，
     * 这里补的是饰品栏那顶。两顶同时在，护甲是两个 2 相加。
     *
     * <p>直接读饰品栏，不看 {@code hatStack()} —— 以前从「当前生效的帽子」推，
     * 脑蚀帽（{@code typeOf} 返回 null）戴在头上时会走进摘帽分支把这份一起清掉。
     */
    private static void applyCurioArmor(LivingEntity wearer) {
        AttributeInstance armor = wearer.getAttribute(Attributes.ARMOR);
        if (armor == null) {
            return;
        }
        ItemStack curioHat = curiosLoaded() ? CuriosCompat.findHat(wearer) : ItemStack.EMPTY;
        boolean hasCurioHat = HatItems.isHat(curioHat);
        AttributeModifier existing = armor.getModifier(CURIO_ARMOR_ID);
        if (!hasCurioHat) {
            if (existing != null) {
                armor.removeModifier(CURIO_ARMOR_ID);
                logArmorBreakdown(wearer, false, 0.0D);
            }
            return;
        }
        // 基础护甲 + 这顶自己的附魔。头上没戴帽子时它的附魔已经由 applyEnchantArmor 算过，
        // 这里就不重复加。
        boolean headIsHat = HatItems.isHat(wearer.getItemBySlot(EquipmentSlot.HEAD));
        double wanted = HatItems.HAT_ARMOR;
        if (headIsHat) {
            wanted += EnchantmentHelper.getEnchantmentsForCrafting(curioHat).size() * ARMOR_PER_ENCHANT;
        }
        if (existing != null && existing.amount() == wanted) {
            return;
        }
        if (existing != null) {
            armor.removeModifier(CURIO_ARMOR_ID);
        }
        armor.addTransientModifier(new AttributeModifier(
                CURIO_ARMOR_ID, wanted, AttributeModifier.Operation.ADD_VALUE));
        logArmorBreakdown(wearer, true, wanted);
    }

    /** 上一次打过的护甲构成（同一种构成只打一次）。 */
    private static final Map<UUID, String> ARMOR_BREAKDOWN = new HashMap<>();

    /**
     * 记一条护甲构成：装备给的、附魔给的、饰品栏给的分别是多少。
     *
     * <p>只在构成变化时打一行 —— 用来回答「为什么饰品栏那顶帽子没加护甲」。
     */
    private static void logArmorBreakdown(LivingEntity wearer, boolean hasCurioHat, double curioBonus) {
        AttributeInstance armor = wearer.getAttribute(Attributes.ARMOR);
        if (armor == null) {
            return;
        }
        double total = armor.getValue();
        double enchanted = 0.0D;
        AttributeModifier enchant = armor.getModifier(ENCHANT_ARMOR_ID);
        if (enchant != null) {
            enchanted = enchant.amount();
        }
        String signature = String.format("total=%.1f equipped=%.1f enchant=%.1f curio=%s%.1f",
                total, equipped(total, enchanted, curioBonus), enchanted,
                hasCurioHat ? "+" : "", hasCurioHat ? curioBonus : 0.0D);
        if (signature.equals(ARMOR_BREAKDOWN.get(wearer.getUUID()))) {
            return;
        }
        ARMOR_BREAKDOWN.put(wearer.getUUID(), signature);
        HatMod.LOGGER.info("[hatmod] {} 的护甲：{}（装备 {} / 附魔 {} / 饰品栏 {}）",
                wearer.getName().getString(),
                String.format("%.1f", total),
                String.format("%.1f", equipped(total, enchanted, curioBonus)),
                String.format("%.1f", enchanted),
                hasCurioHat ? String.format("%.1f", curioBonus) : "无");
    }

    private static double equipped(double total, double enchanted, double curioBonus) {
        return total - enchanted - curioBonus;
    }

    /** 饰品栏里的帽子被拿走时把那条护甲收掉。 */
    private static void clearCurioArmor(LivingEntity wearer) {
        AttributeInstance armor = wearer.getAttribute(Attributes.ARMOR);
        if (armor != null && armor.getModifier(CURIO_ARMOR_ID) != null) {
            armor.removeModifier(CURIO_ARMOR_ID);
        }
    }

    /**
     * 帽子上每多敲一个附魔，就给戴帽者多 {@link #ARMOR_PER_ENCHANT}（2）点护甲。
     *
     * <p>走的是原版 {@code Attributes.ARMOR} 上的一条**瞬态修饰符**（固定 id）：
     * 每刻按当前附魔数算一个目标值，和已有那条一致就不动 —— 所以附魔没变时不会每刻
     * 重加、也就不会把属性同步包刷爆。附魔数变多/变少（铁砧上加附魔、磨掉）会立刻跟上。
     */
    private static void applyEnchantArmor(LivingEntity wearer, ItemStack hat) {
        AttributeInstance armor = wearer.getAttribute(Attributes.ARMOR);
        if (armor == null) {
            return;
        }
        double wanted = EnchantmentHelper.getEnchantmentsForCrafting(hat).size() * ARMOR_PER_ENCHANT;
        AttributeModifier existing = armor.getModifier(ENCHANT_ARMOR_ID);
        if (wanted <= 0.0D) {
            if (existing != null) {
                armor.removeModifier(ENCHANT_ARMOR_ID);
            }
            return;
        }
        if (existing != null && existing.amount() == wanted) {
            return;
        }
        if (existing != null) {
            armor.removeModifier(ENCHANT_ARMOR_ID);
        }
        armor.addTransientModifier(new AttributeModifier(
                ENCHANT_ARMOR_ID, wanted, AttributeModifier.Operation.ADD_VALUE));
    }

    /** 摘帽 / 换帽时把附魔护甲加成收掉。 */
    private static void clearEnchantArmor(LivingEntity wearer) {
        AttributeInstance armor = wearer.getAttribute(Attributes.ARMOR);
        if (armor != null && armor.getModifier(ENCHANT_ARMOR_ID) != null) {
            armor.removeModifier(ENCHANT_ARMOR_ID);
        }
    }

    /**
     * 摘帽 / 换帽时把「光柱」攒下的绿心一并收走。
     *
     * <p>绿心是吸血溢出换来的，来源只有这顶帽子；不主动收的话它会按自己的节奏慢慢掉，
     * 中途摘帽就等于白嫖一截，重新戴上还能再叠。这里直接清零：摘完立刻全没，
     * 重新戴上也不会补回来。
     */
    private static void clearGreenHearts(LivingEntity wearer) {
        setGreenHearts(wearer, 0.0F);
    }

    // ------------------------------------------------------------------
    // 附魔「光柱」的「绿心」
    //
    // 绿心是**本模组自己的一种抗伤池**，不是原版「吸收」换个颜色：
    //   · **优先级最高**：受到伤害时先扣绿心，绿心挡不住的才轮到黄心、再轮到血量；
    //   · **无限时长**：不挂任何状态、没有倒计时，挂着就是挂着；
    //   · 掉落速度随当前颗数**指数增长**：心越多掉得越快（基础 1 颗时 1 秒/颗），掉光就清零。
    // 数值只存在服务端（GREEN_HEARTS）；每变一次推一条包给本人，客户端
    // {@code client.GreenHeartRender} 拿它画血条上方那条绿心能量条。
    // 因为「先扣谁」由我们自己决定，所以原版黄心完全不受影响 —— 它该是黄的还是黄的。
    // ------------------------------------------------------------------

    /** 一颗绿心多少点（1 颗心 = 2 点）。 */
    private static final float GREEN_DECAY_PER_HEART = 2.0F;
    /** 绿心的**基础**掉落速度：只有 1 颗心时每 GREEN_DECAY_BASE_SECONDS 秒掉一颗（1 秒）。 */
    private static final float GREEN_DECAY_BASE_SECONDS = 1.0F;
    /**
     * 绿心掉落速度随「当前颗数」的**指数增长**因子：每多一颗心，速度乘一次这个数。
     *
     * <p>所以心越多掉得越快 —— 这既是表现，也是一种**软上限**：掉速按指数追上吸血的速度后，
     * 绿心池会稳定在一个平衡点上，而不会对着高血量 Boss 无限膨胀。取 1.07 时大致是
     * 「1 颗 1 秒/颗」，10 颗约 1.7 颗/秒、20 颗约 3.4 颗/秒 —— 比早先的「0.25 秒/颗、1.10 增长」
     * 温和得多，小池子留得住。
     */
    private static final float GREEN_DECAY_GROWTH = 1.07F;

    /** 生物 -> 当前绿心点数（1 颗心 = 2 点）。只属于「吸血」，摘帽即清零。 */
    private static final Map<UUID, Float> GREEN_HEARTS = new HashMap<>();

    /** 这个生物现在有多少绿心。 */
    private static float greenHearts(LivingEntity living) {
        Float value = GREEN_HEARTS.get(living.getUUID());
        return value == null ? 0.0F : value;
    }

    /** 直接设定绿心点数（<= 0 视为清零），并把新值推给本人（只有玩家才有客户端 HUD 要画）。 */
    private static void setGreenHearts(LivingEntity living, float amount) {
        if (amount <= 0.0F) {
            GREEN_HEARTS.remove(living.getUUID());
            amount = 0.0F;
        } else {
            GREEN_HEARTS.put(living.getUUID(), amount);
        }
        if (living instanceof ServerPlayer player) {
            HatNetwork.sendGreenHearts(player, amount);
        }
    }

    /** 给戴帽者加一段绿心（吸血溢出转来的）。 */
    private static void grantGreenHearts(LivingEntity wearer, float amount) {
        if (amount <= 0.0F) {
            return;
        }
        setGreenHearts(wearer, greenHearts(wearer) + amount);
    }

    /**
     * 绿心每刻的维护：按**随颗数指数增长**的速度掉，直到掉光。
     *
     * <p>速度不是固定的「每 X 刻一颗」，而是每刻按 {@link #greenDecayPerTick(float)}
     * 掉一段连续量 —— 颗数多了以后，一刻掉的量可以超过一颗。
     */
    private static void tickGreenHearts(LivingEntity living) {
        float amount = greenHearts(living);
        if (amount <= 0.0F) {
            return;
        }
        setGreenHearts(living, amount - greenDecayPerTick(amount));
    }

    /**
     * 这一刻该掉多少绿心（点）：速度 = 基础速度 × {@link #GREEN_DECAY_GROWTH}^(当前颗数 - 1)。
     *
     * <p>因为是指数，颗数越大掉得越快（没有「每刻最多掉一颗」的封顶），所以池子会被指数
     * 增长的掉速自然压住、不会无限膨胀。1 颗心时正好是
     * {@link #GREEN_DECAY_BASE_SECONDS} 秒/颗。
     */
    private static float greenDecayPerTick(float amount) {
        float hearts = amount / GREEN_DECAY_PER_HEART;
        float heartsPerSecond = (1.0F / GREEN_DECAY_BASE_SECONDS)
                * (float) Math.pow(GREEN_DECAY_GROWTH, hearts - 1.0F);
        // 颗/秒 -> 点/刻（20 刻 = 1 秒）
        return heartsPerSecond * GREEN_DECAY_PER_HEART / 20.0F;
    }

    /**
     * 用绿心挡这一击：从 {@code amount} 里扣掉绿心能吃下的部分，返回剩下的伤害。
     *
     * <p><b>扣的是「经过护甲等减伤之后」的数值</b>，不再拿原始伤害直接扣：一套铁甲把 10 点砍成
     * 4 点，绿心就只掉 4 点而不是 10 点 —— 护甲越厚，绿心越耐用。抗性提升 / 保护附魔同理
     * （{@link #mitigate} 把原版那几步减伤照走一遍）。
     *
     * <p><b>整击被吃下时不扣护甲耐久</b>：这一层在 {@code LivingIncomingDamageEvent}
     * （NeoForge 在护甲结算之前触发）里把伤害整个取消，所以护甲一点磨损都没有。
     *
     * <p>只吃下一部分时，把剩下那一截**按比例换算回减伤前**的数值交还给原版流程，
     * 让原版自己做同样的减伤 —— 结果约等于「减伤后的伤害再减去绿心吃掉的部分」。
     *
     * <p>返回 0 表示这一击被绿心整个吃掉（调用方应当把这次伤害取消掉）。
     */
    private static float absorbWithGreenHearts(LivingEntity living, DamageSource source, float amount) {
        float green = greenHearts(living);
        if (green <= 0.0F || amount <= 0.0F) {
            return amount;
        }
        float mitigated = mitigate(living, source, amount);
        if (mitigated <= 0.0F) {
            // 护甲 / 抗性已经把这一击削到 0：绿心没得扣，照原样放行（护甲该磨损就磨损）
            return amount;
        }
        float absorbed = Math.min(green, mitigated);
        setGreenHearts(living, green - absorbed);
        if (absorbed >= mitigated) {
            return 0.0F;
        }
        return amount * (mitigated - absorbed) / mitigated;
    }

    /**
     * 原版那几步「护甲 / 抗性 / 保护附魔」减伤，按 {@link DamageSource} 的标签逐条照走，
     * 但**不真的扣护甲耐久**（只算数值）。
     *
     * <p>和原版 {@code LivingEntity#getDamageAfterArmorAbsorb} / {@code getDamageAfterMagicAbsorb}
     * 的结算顺序、公式完全一致，这里只是拿来预估「这一击实际会打到多少血」，
     * 好让绿心按真实压力扣。任何一条标签命中（例如光柱自带 {@code bypasses_armor}）就跳过对应那一步。
     */
    private static float mitigate(LivingEntity living, DamageSource source, float amount) {
        float result = amount;
        if (!source.is(DamageTypeTags.BYPASSES_ARMOR)) {
            result = CombatRules.getDamageAfterAbsorb(living, result, source,
                    (float) living.getArmorValue(), (float) living.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
        }
        if (source.is(DamageTypeTags.BYPASSES_EFFECTS)) {
            return result;
        }
        MobEffectInstance resistance = living.getEffect(MobEffects.DAMAGE_RESISTANCE);
        if (resistance != null && !source.is(DamageTypeTags.BYPASSES_RESISTANCE)) {
            int percent = (resistance.getAmplifier() + 1) * 5;
            result = Math.max(result * (25 - percent) / 25.0F, 0.0F);
        }
        if (result > 0.0F && !source.is(DamageTypeTags.BYPASSES_ENCHANTMENTS)
                && living.level() instanceof ServerLevel serverLevel) {
            float protection = EnchantmentHelper.getDamageProtection(serverLevel, living, source);
            if (protection > 0.0F) {
                result = CombatRules.getDamageAfterMagicAbsorb(result, protection);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 黑帽：受到伤害 -> 整个人瞬移走，**这一击因此完全落空**
    //
    // 分成两段，用的是同一条瞬移逻辑，只是各自的次数池分开数：
    //   · 蓄力期「闪避」：基础 {@link #BLINKS_PER_CHARGE} 次 + 每级 1 次 —— 用来安心读条；
    //   · 照射期「残影」：每级 1 次 —— 用来在最容易被集火的那一段脱身。
    // 「免疫这次伤害」是靠取消伤害事件实现的：伤害在真正结算之前就被否掉，
    // 所以护甲耐久、击退、附带的状态效果、致死判定统统不会发生 —— 是「躲开了」，
    // 而不是「先挨一下再传送走」。
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        LivingEntity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }

        // 破防记账：光柱之外打出去的伤害（近战、召唤物……）也要记成「这一秒打到了它」。
        // 只靠光柱记账的话，近战打出来的伤害在账本里一条都没有（见 evaluateForceCycle）。
        noteHitByHat(entity, event.getSource().getEntity());

        // 破防模式 + 戴着帽子（牛仔帽 / TETO 帽同一套）：玩家完全免伤
        if (HatSettings.forceBreak() && entity instanceof Player player && !hatStack(player).isEmpty()) {
            event.setCanceled(true);
            PLAYER_HEALTH.put(player.getUUID(), player.getHealth());
            return;
        }

        // 「谁打过我」记一笔（见 rememberAggressor）：记下来之后它就会进光柱的攻击名单。
        // 放在最前面 —— 哪怕这一下后面被绿心吃掉、或者被黑帽的闪避躲开，账都照记。
        // 0 伤害的一律不记（那是打雪仗那种推一把的雪球，不算动手）。
        rememberAggressor(entity, event.getSource(), event.getAmount());

        // 戴帽者不能打「被动生物 / 未激怒的中立生物」：玩家近战走 AttackEntityEvent，
        // 远程攻击和其它生物（女仆等）的伤害都从这个伤害事件里拦下来。
        // 另外被光柱强控的生物同样一点伤害都打不出来（isAttackLocked）。
        Entity attacker = event.getSource().getEntity();
        if (attacker instanceof LivingEntity livingAttacker
                && (isAttackLocked(livingAttacker)
                        || (isWearingHat(livingAttacker)
                                && HatProtection.isProtected(entity, livingAttacker)))) {
            event.setCanceled(true);
            return;
        }

        // 「光柱」吸血的绿心：本模组里优先级最高的一层抗伤，先扣它，扣不完的才轮到黄心/血量。
        // 带 bypasses_invulnerability 的伤害（/kill、虚空，以及本模组自己的光柱）不吃绿心 ——
        // 免得一颗绿心把「必死」的伤害也挡下来。
        //
        // 绿心吸收的是**经过护甲等减伤之后**的数值（见 absorbWithGreenHearts），所以护甲/抗性/
        // 保护附魔同样能让绿心更耐用；整个被绿心吃下的那一击直接取消，护甲一点耐久都不掉。
        DamageSource source = event.getSource();
        if (!source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            float remaining = absorbWithGreenHearts(entity, source, event.getAmount());
            if (remaining < event.getAmount()) {
                event.setAmount(remaining);
                if (remaining <= 0.0F) {
                    // 这一击被绿心整个吃掉：取消掉，不掉血、不吃击退、不触发无敌帧，也不磨损护甲
                    event.setCanceled(true);
                    return;
                }
            }
        }

        HatState state = STATES.get(entity.getUUID());
        if (state == null || state.type == null) {
            return;
        }
        // 「不动如山」：红帽没有闪避特性，伤害能走到这里就说明这一下真的打中了
        if (state.type == HatType.RED && state.charge > 0) {
            state.hitWhileCharging = true;
        }
        if (!state.type.has(HatType.Trait.BLINK)) {
            return;
        }
        // 这一击落在哪个阶段、那个阶段的次数还剩多少（当前这一击算一次，所以先减 1）
        boolean charging = state.charge > 0;
        if (!charging && state.flash <= 0) {
            return;
        }
        int remaining = (charging ? state.blinks : state.flashBlinks) - 1;
        if (remaining < 0) {
            // 闪避次数已经用完：改成和「虚影」换位，这一击由虚影承担。
            // 没有虚影在场就照常挨打（下面 return 交给原版结算）。
            if (swapWithSummon(level, entity, source, event.getAmount())) {
                event.setCanceled(true);
            }
            return;
        }

        // 先传送：传成功才算躲掉，传不出去（找不到安全落点）就照常挨打，
        // 白白浪费一次机会不说、还免疫掉伤害，那才不合理。
        if (!blinkToSafety(level, entity, remaining)) {
            return;
        }
        if (charging) {
            state.blinks--;
            state.chargeBlinks++;
            // 「神本无相」：一轮蓄力里三次闪避全用掉
            if (state.chargeBlinks >= BLINKS_PER_CHARGE) {
                HatAchievements.grant(entity, HatAchievements.SHADE_STEP);
            }
        } else {
            state.flashBlinks--;
        }

        // 人已经闪走了，这一击随之落空：取消伤害事件 = 这一下完全没打中。
        // 护甲不掉耐久、不吃击退、不掉血，附带的中毒/燃烧之类也一并作废。
        event.setCanceled(true);
    }

    /**
     * 闪避次数用完后再挨打：和最近的「虚影」交换位置，这一击改由虚影承担。
     *
     * <p>换位是双向的 —— 虚影把你原来的位置补上（那边已经安全了），你落到虚影刚才的位置，
     * 而这一下已经打空了。伤害用同一个 {@link DamageSource} 结算，击杀归属不变。
     *
     * @return 是否真的换成功了（没有虚影在场时为 {@code false}，调用方照常结算伤害）
     */
    private static boolean swapWithSummon(ServerLevel level, LivingEntity wearer, DamageSource source, float amount) {
        HatSummon shade = HatSummons.nearest(level, wearer);
        if (shade == null) {
            return false;
        }
        Vec3 from = wearer.position();
        Vec3 to = shade.position();
        wearer.teleportTo(to.x, to.y, to.z);
        wearer.resetFallDistance();
        wearer.setDeltaMovement(Vec3.ZERO);
        wearer.hurtMarked = true;
        shade.moveTo(from.x, from.y, from.z, shade.getYRot(), shade.getXRot());
        shade.resetFallDistance();
        shade.setDeltaMovement(Vec3.ZERO);
        // 这一击由虚影吃下（二十点血，大多数攻击直接把它打散）
        shade.hurt(source, amount);
        // 换位轨迹拖一串墨色粒子，看得出是「和影子对调了」
        double deltaX = from.x - to.x;
        double deltaY = from.y - to.y;
        double deltaZ = from.z - to.z;
        double distance = Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
        int steps = (int) Math.min(48.0D, Math.max(8.0D, Math.ceil(distance * 1.5D)));
        for (int step = 0; step <= steps; step++) {
            double f = (double) step / (double) steps;
            level.sendParticles(ParticleTypes.SQUID_INK,
                    to.x + deltaX * f, to.y + 1.0D + deltaY * f, to.z + deltaZ * f,
                    2, 0.15D, 0.15D, 0.15D, 0.01D);
        }
        level.playSound(null, wearer.getX(), wearer.getY(), wearer.getZ(),
                SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.8F, 0.6F);
        if (wearer instanceof ServerPlayer player) {
            player.displayClientMessage(Component.translatable("message.hatmod.shade"), true);
        }
        return true;
    }

    /**
     * 随机传送到附近的安全落点。随机方式与末影人一致，落点判定与末影珍珠同源
     * （向下找到可站立的地面、检查碰撞与液体），但不造成任何摔落伤害。
     *
     * @param remaining 这次躲完之后还剩几次，用于给玩家的提示
     * @return 是否真的传走了（找不到安全落点时为 {@code false}）
     */
    private static boolean blinkToSafety(ServerLevel level, LivingEntity entity, int remaining) {
        RandomSource random = level.getRandom();
        double originX = entity.getX();
        double originY = entity.getY();
        double originZ = entity.getZ();

        for (int attempt = 0; attempt < 16; attempt++) {
            double x = originX + (random.nextDouble() - 0.5D) * 32.0D;
            double y = originY + (double) (random.nextInt(16) - 8);
            double z = originZ + (random.nextDouble() - 0.5D) * 32.0D;
            if (teleportToSafeSpot(level, entity, x, y, z)) {
                // 末影珍珠同款：音效用 PLAYER_TELEPORT、粒子用 PORTAL（原版投掷末影珍珠
                // 落地传送时放的就是这个音效，飞行途中拖的就是这串粒子）。
                // 起点和落点各来一份：起点是"消失"，落点是"出现"；只放起点的话
                // 人已经瞬移走了，距离衰减之后几乎听不见也看不见。
                level.playSound(null, originX, originY, originZ,
                        SoundEvents.PLAYER_TELEPORT, SoundSource.PLAYERS, BLINK_SOUND_VOLUME, 1.0F);
                level.playSound(null, entity.getX(), entity.getY(), entity.getZ(),
                        SoundEvents.PLAYER_TELEPORT, SoundSource.PLAYERS, BLINK_SOUND_VOLUME, 1.0F);
                // 从起点到落点整条路径都拖一串末影粒子：瞬移是一瞬间的事，
                // 只在两端各撒一团的话中间是断的，看着不像「从这里闪到那里」。
                double deltaX = entity.getX() - originX;
                double deltaY = entity.getY() - originY;
                double deltaZ = entity.getZ() - originZ;
                double distance = Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
                int steps = (int) Math.min(64.0D, Math.max(8.0D, Math.ceil(distance * 2.0D)));
                for (int step = 0; step <= steps; step++) {
                    double f = (double) step / (double) steps;
                    level.sendParticles(ParticleTypes.PORTAL,
                            originX + deltaX * f, originY + 1.0D + deltaY * f, originZ + deltaZ * f,
                            3, 0.15D, 0.15D, 0.15D, 0.05D);
                }
                // 只有玩家能收到提示，生物那半边自然跳过
                if (entity instanceof ServerPlayer player) {
                    player.displayClientMessage(
                            Component.translatable("message.hatmod.blink", remaining), true);
                }
                entity.resetFallDistance();
                entity.setDeltaMovement(Vec3.ZERO);
                entity.hurtMarked = true;
                return true;
            }
        }
        return false;
    }

    private static boolean teleportToSafeSpot(ServerLevel level, LivingEntity entity, double x, double y, double z) {
        BlockPos pos = BlockPos.containing(x, y, z);
        if (!level.hasChunkAt(pos)) {
            return false;
        }

        int minY = level.getMinBuildHeight();
        while (pos.getY() > minY && !level.getBlockState(pos.below()).blocksMotion()) {
            pos = pos.below();
        }
        if (pos.getY() <= minY) {
            return false;
        }

        double targetY = pos.getY();
        AABB box = entity.getBoundingBox().move(x - entity.getX(), targetY - entity.getY(), z - entity.getZ());
        if (!level.noCollision(entity, box) || level.containsAnyLiquid(box)) {
            return false;
        }

        entity.teleportTo(x, targetY, z);
        return true;
    }

    // ------------------------------------------------------------------
    // 敌人判定与锁定
    // ------------------------------------------------------------------

    /** 戴帽者 ->（打过它的生物 -> 记忆到期刻）。 */
    private static final Map<UUID, Map<UUID, Long>> AGGRO = new HashMap<>();

    /**
     * 「谁打过我」记多久（30 秒 = 600 刻）。
     *
     * <p>要盖过一整轮蓄力（最长 29.4 秒）—— 不然「打我的人」在光柱真正开火之前就被忘掉了。
     * 每挨一下就重新计时。
     */
    private static final int AGGRO_MEMORY_TICKS = 600;

    /**
     * 记一笔「谁打了这个戴帽者」。
     *
     * <p>光柱的目标名单本来就认「敌意生物 / 被激怒的中立生物 / 正在锁定我的生物」，
     * 这一条是**兜底**：有些生物按类型分根本不是敌人（被动生物、甚至没被激怒），
     * 但确实动了手 —— 例如魔改过的野生女仆「名义上是被动生物，实际上会打人」。
     * 判据只看结果：**真的对戴帽者造成了伤害**（{@code amount > 0}）才算数。
     *
     * <p>伤害为 0 的「攻击」不算（打雪仗那种扔过来推一把的雪球就是 0 伤害）——
     * 这也正是要卡伤害量的原因：不打掉血的不进名单。
     * 伤害来源背后的「人」由 {@link DamageSource#getEntity()} 给（箭、雪球算发射者/主人）。
     */
    private static void rememberAggressor(LivingEntity victim, DamageSource source, float amount) {
        if (amount <= 0.0F || !isWearingHat(victim)) {
            return;
        }
        Entity attacker = source.getEntity();
        if (!(attacker instanceof LivingEntity living) || living == victim || living instanceof Player) {
            return;
        }
        long until = victim.level().getGameTime() + AGGRO_MEMORY_TICKS;
        AGGRO.computeIfAbsent(victim.getUUID(), key -> new HashMap<>()).put(living.getUUID(), until);
    }

    /**
     * 最近**真的打过这个戴帽者**的生物（{@code amount > 0}），按距离由近到远。
     *
     * <p>这些目标**不受 24 格射程限制**：远程偷袭你的弓箭手、隔着一道墙丢技能的法系怪，
     * 都会进光柱/声波的目标名单，也会把整条链路触发起来（链路的开火条件就是
     * 「{@link #findNearestEnemy} 不为 null」）。
     */
    static List<LivingEntity> recentAggressors(LivingEntity wearer) {
        Map<UUID, Long> memory = AGGRO.get(wearer.getUUID());
        if (memory == null || memory.isEmpty()) {
            return List.of();
        }
        long now = wearer.level().getGameTime();
        memory.entrySet().removeIf(entry -> entry.getValue() < now);
        if (memory.isEmpty()) {
            AGGRO.remove(wearer.getUUID());
            return List.of();
        }
        if (!(wearer.level() instanceof ServerLevel level)) {
            return List.of();
        }
        List<LivingEntity> found = new ArrayList<>();
        // 复制一份键来遍历：isEnemy 内部会顺手清理过期项，直接迭代原 keySet 有并发修改的风险
        for (UUID id : new ArrayList<>(memory.keySet())) {
            Entity entity = level.getEntity(id);
            if (entity instanceof LivingEntity living && living.isAlive() && !living.isRemoved()
                    && isEnemy(living, wearer)) {
                found.add(living);
            }
        }
        found.sort((a, b) -> Double.compare(wearer.distanceToSqr(a), wearer.distanceToSqr(b)));
        return found;
    }

    /** 这个生物最近（{@link #AGGRO_MEMORY_TICKS} 刻内）打过这个戴帽者吗。 */
    static boolean isRecentAggressor(LivingEntity wearer, Entity entity) {
        Map<UUID, Long> memory = AGGRO.get(wearer.getUUID());
        if (memory == null) {
            return false;
        }
        Long until = memory.get(entity.getUUID());
        if (until == null) {
            return false;
        }
        if (until < wearer.level().getGameTime()) {
            // 过期的顺手清掉；这个戴帽者名下清空了就把这一层也摘掉，别让表一直挂着。
            memory.remove(entity.getUUID());
            if (memory.isEmpty()) {
                AGGRO.remove(wearer.getUUID());
            }
            return false;
        }
        return true;
    }

    /** 锁定「最近的合法敌人」（光柱射程内）。没有则返回 null。 */
    public static LivingEntity findNearestEnemy(LivingEntity wearer) {
        List<LivingEntity> nearest = findNearestEnemies(wearer, 1);
        return nearest.isEmpty() ? null : nearest.get(0);
    }

    /**
     * 射程内**最近的 {@code count} 个**合法敌人，由近到远排序（互不重复）。
     *
     * <p>红帽附魔「光柱」的多道光柱按这个列表各指一个：敌人够多就是「一人一道」，
     * 敌人不够（比如单挑一个 Boss）时列表更短，也就少发几道光。
     * 敌人一个都没有时返回空列表。
     */
    public static List<LivingEntity> findNearestEnemies(LivingEntity wearer, int count) {
        if (count <= 0) {
            return List.of();
        }
        // ① 刚打过我的：不管现在离多远都算目标，而且排在最前面。
        //    链路的触发条件（附近有没有合法敌人）就是看这个列表，所以远程偷袭也会把整轮打起来。
        List<LivingEntity> found = new ArrayList<>(recentAggressors(wearer));

        // ② 再补射程内的普通敌人
        List<LivingEntity> nearby = new ArrayList<>();
        AABB area = wearer.getBoundingBox().inflate(FLASH_RANGE);
        for (Entity entity : wearer.level().getEntities(wearer, area)) {
            if (!(entity instanceof LivingEntity living) || !isEnemy(living, wearer)) {
                continue;
            }
            if (!found.contains(living)) {
                nearby.add(living);
            }
        }
        nearby.sort((a, b) -> Double.compare(wearer.distanceToSqr(a), wearer.distanceToSqr(b)));
        found.addAll(nearby);
        // ③ 敌意生物优先。脑蚀链路跑起来时，isEnemy 的 brainrotLockAll 会把 40 格内
        //    **一切非友方单位**都算成敌人 —— 于是 Witheria 那些特效实体（垂直激光
        //    VerticalLaserEntity extends PathfinderMob、炮塔、爆炸云……）会跟本体抢
        //    光柱的 4 个名额。它们离得更近、数量又多，结果 4 道光全砸在激光上，
        //    本体一发都没挨。Enemy（Monster 一类）才是真正要打的东西，排前面。
        List<LivingEntity> ordered = new ArrayList<>(found.size());
        for (LivingEntity living : found) {
            if (living instanceof Enemy) {
                ordered.add(living);
            }
        }
        for (LivingEntity living : found) {
            if (!(living instanceof Enemy)) {
                ordered.add(living);
            }
        }
        return ordered.size() <= count ? ordered : new ArrayList<>(ordered.subList(0, count));
    }

    /**
     * 敌人 = 敌意生物 / 被激怒的中立生物 / 注册分类为 MONSTER 的生物 / 正在锁定戴帽者的生物 /
     * 最近真的打过戴帽者的生物（见 {@link #rememberAggressor}）。
     * 不含玩家和己方（避免误伤队友、宠物、自己的女仆）。
     */
    /** 脑蚀强制锁敌的半径（格）：链路的照射范围是 40，锁敌同一圈。 */
    private static final double BRAINROT_LOCK_RANGE = 40.0D;

    public static boolean isEnemy(Entity entity, LivingEntity wearer) {
        if (entity == wearer) {
            return false;
        }
        if (entity instanceof Player) {
            // 锁敌模式下玩家也可以成为目标（是否真的造成伤害由 affectPlayers 另外管）
            return HatSettings.lockPlayers() && !isFriendlyTo(wearer, entity);
        }
        if (isFriendlyTo(wearer, entity)) {
            return false;
        }
        // 脑蚀强制锁敌：P 触发的链路在跑的时候，范围内的非友方单位一律算敌人
        if (HatSettings.brainrotLockAll() && BrainrotCycle.isRunning(wearer)
                && wearer.distanceToSqr(entity) <= BRAINROT_LOCK_RANGE * BRAINROT_LOCK_RANGE) {
            return true;
        }
        // 兜底：真的打过我的，一律算敌人 —— 放在类型判断前面，所以「被动生物但会打人」
        // 这类魔改生物也进得来（它本来连 Mob 的敌意判断都过不了）。
        if (isRecentAggressor(wearer, entity)) {
            return true;
        }
        if (entity instanceof Mob mob) {
            if (mob instanceof Enemy) {
                return true;
            }
            if (mob instanceof NeutralMob neutral && neutral.getRemainingPersistentAngerTime() > 0) {
                return true;
            }
            // 兜底：注册分类就是 MONSTER 的一律算敌意。有些模组怪（诡厄巫法的「仆从」系列就是）
            // 注册成 MONSTER、却**不实现 Enemy 接口**，平时也**不 setTarget**（索敌全在自己的 AI 里），
            // 上面三条全都命中不了 —— 少了这一条，它们永远不会被链路当成敌人，
            // 光柱不锁、负面场也不挂。
            if (mob.getType().getCategory() == net.minecraft.world.entity.MobCategory.MONSTER) {
                return true;
            }
            return mob.getTarget() == wearer;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 己方判定（「后门」）
    //
    // 技能是「对任何戴帽子的生物」生效的，所以女仆戴上帽子之后，力场会连主人一起压住。
    // 这里给出「哪些是自己人」的唯一判据，时间差力场和敌人判定都走它：
    //   · 同一个主人的（主人本人 + 主人名下的驯服生物/女仆）—— 车万女仆的 EntityMaid
    //     继承 TamableAnimal，所以走的是原版 OwnableEntity，不需要硬依赖女仆模组；
    //   · 被戴帽者自己驯服的；
    //   · 同一计分板队伍的（isAlliedTo）。
    // ------------------------------------------------------------------

    /**
     * {@code entity} 对戴着帽子的 {@code wearer} 来说算不算「自己人」。
     *
     * <p>玩家没有主人，这里把「玩家自己」当自己的主人，于是
     * 「玩家戴帽 -> 不减速自己的宠物/女仆」和「女仆戴帽 -> 不减速主人」是同一套逻辑。
     */
    public static boolean isFriendlyTo(LivingEntity wearer, Entity entity) {
        if (entity == wearer) {
            return true;
        }
        // 「脑蚀」负面效果爆出来的史莱姆/蠹虫，一进世界就记在主人名下
        if (BrainrotMinions.isMinionOf(entity, wearer)) {
            return true;
        }
        UUID wearerOwner = ownerOf(wearer);
        UUID entityOwner = ownerOf(entity);
        if (wearerOwner != null && wearerOwner.equals(entityOwner)) {
            return true;
        }
        Team wearerTeam = wearer.getTeam();
        Team entityTeam = entity.getTeam();
        return wearerTeam != null && entityTeam != null && wearerTeam.isAlliedTo(entityTeam);
    }

    /** 主人的 UUID：玩家返回自己；驯服生物/女仆走 {@link OwnableEntity}；其余返回 null。 */
    private static UUID ownerOf(Entity entity) {
        if (entity instanceof Player player) {
            return player.getUUID();
        }
        if (entity instanceof OwnableEntity ownable) {
            return ownable.getOwnerUUID();
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 时间差力场
    // ------------------------------------------------------------------

    /**
     * 时间差力场当前的半径 = 基础 {@link #SLOW_RADIUS} + 附魔「缓速」的加成。
     *
     * <p>加力场和清力场必须用同一个半径：清的时候如果只按基础半径找，附魔撑大的那一圈
     * 会留下清不掉的「缓慢」，蓄力结束之后周围生物还在被减速。
     */
    private static double slowRadius(LivingEntity wearer) {
        return SLOW_RADIUS + HatEnchants.slowRadiusBonus(hatStack(wearer));
    }

    private static void applySlowField(LivingEntity wearer, ServerLevel level) {
        AABB area = wearer.getBoundingBox().inflate(slowRadius(wearer));
        // 先把已经飞出/被移除的弹射物的重力还回去，再管还在场里的
        releaseEscapedProjectiles(area);
        for (Entity entity : level.getEntities(wearer, area)) {
            if (entity.isSpectator() || !entity.isAlive()) {
                continue;
            }
            // 己方后门：自己的主人、同一主人的女仆/宠物、同队的人，一律不受力场影响
            if (isFriendlyTo(wearer, entity)) {
                continue;
            }
            if (entity instanceof Projectile projectile) {
                // 己方射出的箭矢等也不压速度（否则等于把自己主人的输出一起削了）
                Entity shooter = projectile.getOwner();
                if (shooter != null && isFriendlyTo(wearer, shooter)) {
                    continue;
                }
                slowProjectile(projectile);
            } else if (entity instanceof LivingEntity living) {
                // 生物那半边和「预知」的定点静止用的是同一个方法，见 applyStillness
                applyStillness(living);
            } else {
                // 掉落物等：同样是三轴同系数压速（方向不变）
                entity.setDeltaMovement(entity.getDeltaMovement().scale(OBJECT_DAMP));
                entity.hurtMarked = true;
            }
        }
    }

    /**
     * 弹射物在力场里的「慢动作」：关掉重力、把速度压到一个很低的定值，**方向不变**。
     *
     * <p>关重力是关键：只要重力还在每刻往下加，压得越狠、在空中的时间越长，越会一头栽下来
     * （早先的阻尼版正是如此）。关掉之后箭矢就沿着原来那条直线慢慢飞 —— 这正是
     * 「按原有路径、速度大幅放慢」，而不是「被拽住」。只登记原本有重力的，离场时还回去。
     */
    private static void slowProjectile(Projectile projectile) {
        if (!projectile.isNoGravity()) {
            projectile.setNoGravity(true);
            GRAVITY_HELD.put(projectile.getUUID(), projectile);
        }
        Vec3 velocity = projectile.getDeltaMovement();
        double speed = velocity.length();
        if (speed > 1.0E-6D && speed > PROJECTILE_SLOW_SPEED) {
            projectile.setDeltaMovement(velocity.scale(PROJECTILE_SLOW_SPEED / speed));
            projectile.hurtMarked = true;
        }
    }

    /**
     * 把已经不在力场里、或者已经被移除的弹射物的重力还回去。
     *
     * <p>力场每刻重扫，飞出去的弹射物不会自己回来销号，只能靠这里摘掉登记；
     * 不摘的话它会一直没重力，{@link #GRAVITY_HELD} 也会越攒越多。
     */
    private static void releaseEscapedProjectiles(AABB area) {
        for (UUID id : new ArrayList<>(GRAVITY_HELD.keySet())) {
            Entity entity = GRAVITY_HELD.get(id);
            if (entity == null || entity.isRemoved()) {
                GRAVITY_HELD.remove(id);
            } else if (!entity.getBoundingBox().intersects(area)) {
                entity.setNoGravity(false);
                GRAVITY_HELD.remove(id);
            }
        }
    }

    /**
     * 蓄力结束时把力场留下的缓慢清掉（只清我们自己加的那一档，不碰别的缓慢来源），
     * 并把被力场关掉重力的弹射物全部还原（不论它现在飞到了哪里）。
     */
    private static void clearSlowField(ServerLevel level, LivingEntity wearer) {
        AABB area = wearer.getBoundingBox().inflate(slowRadius(wearer));
        for (Entity entity : level.getEntities(wearer, area)) {
            if (!(entity instanceof LivingEntity living)) {
                continue;
            }
            MobEffectInstance slow = living.getEffect(MobEffects.MOVEMENT_SLOWDOWN);
            if (slow != null && slow.getAmplifier() == SLOW_AMPLIFIER) {
                living.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
            }
        }
        for (Entity entity : new ArrayList<>(GRAVITY_HELD.values())) {
            if (!entity.isRemoved()) {
                entity.setNoGravity(false);
            }
        }
        GRAVITY_HELD.clear();
    }

    /** 蓄力期间给戴帽者自己挂抗性提升 II（每刻刷新，见 {@link #CHARGE_RESISTANCE_TICKS}）。 */
    private static void applyChargeResistance(LivingEntity wearer) {
        // 和迅捷 / 永久增益一样带图标：这是给玩家自己的、看得见的状态
        wearer.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE,
                CHARGE_RESISTANCE_TICKS, CHARGE_RESISTANCE_AMPLIFIER, false, false, true));
    }

    /**
     * 蓄力结束/中断时收掉抗性。
     *
     * <p>只清我们自己加的那一档（放大 {@link #CHARGE_RESISTANCE_AMPLIFIER}），
     * 玩家自己喝的抗性药水不受影响 —— 跟 {@link #clearSlowField} 一个路子。
     */
    private static void clearChargeResistance(LivingEntity wearer) {
        MobEffectInstance resistance = wearer.getEffect(MobEffects.DAMAGE_RESISTANCE);
        if (resistance != null && resistance.getAmplifier() == CHARGE_RESISTANCE_AMPLIFIER) {
            wearer.removeEffect(MobEffects.DAMAGE_RESISTANCE);
        }
    }

    // ------------------------------------------------------------------
    // 手电筒光柱
    //
    // 光柱本体（那道实心的散射锥）是**客户端用几何体绘制的**，见
    // com.hatmod.client.BeamRenderer —— 和凋灵风暴牵引光束同款做法。
    // 服务端这里只负责：
    //   1. 用隐藏效果把"正在照射"同步给客户端
    //   2. 把锁定的目标 id 发给客户端，让光柱指过去
    //   3. 撒一点零星火花做点缀
    // ------------------------------------------------------------------

    /** 蓄力阶段：脚下缓缓升起的细碎光点。 */
    private static void spawnChargeAura(ServerLevel level, LivingEntity wearer) {
        RandomSource random = level.getRandom();
        if (wearer.tickCount % 3 != 0) {
            return;
        }
        for (int i = 0; i < 2; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            double radius = SLOW_RADIUS * (0.5D + random.nextDouble() * 0.5D);
            level.sendParticles(HatParticles.FLASH_LIGHT.get(),
                    wearer.getX() + Math.cos(angle) * radius,
                    wearer.getY() + random.nextDouble() * 1.6D,
                    wearer.getZ() + Math.sin(angle) * radius,
                    1, 0.0D, 0.05D, 0.0D, 0.01D);
        }
    }

    /**
     * 力场边缘那一圈细碎粒子：半径直接取 {@link #slowRadius}，所以附魔「缓速」把力场撑大时，
     * 这圈粒子也跟着一起变大。
     *
     * <p>颜色按帽子分（用的都是**原版粒子自带的颜色**，不额外注册粒子类型）：
     * 黑 = 幽匿魂（暗青）、白 = 末地烛（白）、红 = 绯红孢子（暗红）。
     * 每圈把起始角随机错开，粒子才不会像一串钉死的点。
     */
    private static void spawnSlowRing(ServerLevel level, LivingEntity wearer, HatType type) {
        if (wearer.tickCount % RING_PERIOD != 0) {
            return;
        }
        RandomSource random = level.getRandom();
        double radius = slowRadius(wearer);
        double offset = random.nextDouble() * Math.PI * 2.0D;
        ParticleOptions particle = ringParticle(type);
        for (int i = 0; i < RING_POINTS; i++) {
            double angle = offset + Math.PI * 2.0D * i / RING_POINTS;
            level.sendParticles(particle,
                    wearer.getX() + Math.cos(angle) * radius,
                    wearer.getY() + random.nextDouble() * RING_HEIGHT,
                    wearer.getZ() + Math.sin(angle) * radius,
                    1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    /**
     * 每顶帽子对应的力场粒子颜色。
     *
     * <p>刻意写成 if/else 而不是 switch：对枚举做 switch 会让 javac 额外生成一个
     * {@code HatAbilities$1} 合成类，而本模组每个类都要登记进 {@link HatPreloader}
     * 才不会被"关了 jar 句柄"的优化模组坑到（见那个类的注释）—— 少一个类少一处要维护的。
     */
    private static ParticleOptions ringParticle(HatType type) {
        if (type == HatType.BLACK) {
            return ParticleTypes.SCULK_SOUL;
        }
        if (type == HatType.RED) {
            return ParticleTypes.CRIMSON_SPORE;
        }
        return ParticleTypes.END_ROD;
    }

    /**
     * 照射阶段：少量火花沿光柱轴线附近飞散，作为几何光柱之上的点缀。
     *
     * <p>两点刻意为之：数量少、而且最近也要离眼睛 5 格。
     * 早先是按伤害判定锥的 35° 铺满、而且从 1 格就开始撒，
     * 结果第一人称顺着光柱看过去满屏都是光点，几何光柱反而被压得看不见了。
     * 主体体积感来自几何体（{@code com.hatmod.client.BeamRenderer}），不是靠粒子堆。
     */
    private static void spawnBeamSparks(ServerLevel level, LivingEntity wearer, Vec3 dir) {
        Vec3 origin = beamOrigin(wearer, 1.0F);
        // 视线接近垂直时用 X 轴当参考，避免叉乘得到零向量
        Vec3 helper = Math.abs(dir.y) > 0.99D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 right = dir.cross(helper).normalize();
        Vec3 up = right.cross(dir).normalize();

        RandomSource random = level.getRandom();
        // 贴着光柱的细锥撒（12°），而不是铺满 35° 的伤害判定锥
        double spreadTan = Math.tan(Math.toRadians(12.0D));
        double nearLimit = 5.0D;

        // 光柱中远段的火花
        for (int i = 0; i < 6; i++) {
            double distance = nearLimit + random.nextDouble() * (FLASH_RANGE - nearLimit);
            double spread = distance * spreadTan * Math.sqrt(random.nextDouble());
            double angle = random.nextDouble() * Math.PI * 2.0D;
            Vec3 pos = origin
                    .add(dir.scale(distance))
                    .add(right.scale(spread * Math.cos(angle)))
                    .add(up.scale(spread * Math.sin(angle)));
            level.sendParticles(HatParticles.FLASH_LIGHT.get(), pos.x, pos.y, pos.z, 1, 0.02D, 0.02D, 0.02D, 0.01D);
        }

        // 灯口附近只留两粒小火花点缀，同样保持 5 格开外
        for (int i = 0; i < 2; i++) {
            Vec3 muzzle = origin.add(dir.scale(nearLimit + random.nextDouble() * 2.0D));
            level.sendParticles(HatParticles.FLASH_LIGHT.get(), muzzle.x, muzzle.y, muzzle.z, 1, 0.0D, 0.0D, 0.0D, 0.05D);
        }
    }

    /**
     * 光柱每刻的伤害分成<b>两部分</b>，各走各的路：
     *
     * <ul>
     *   <li><b>固定伤害</b>：该帽子的 {@link HatType#damagePerTick()}，走正常的
     *       {@link LivingEntity#hurt}，所以护甲、抗性、Boss 减伤等常规机制都照常生效，
     *       也因此能正常掉落、算击杀归属、播死亡信息。</li>
     *   <li><b>附加百分比伤害</b>：该部分见 {@link #applyPercentageDamage}，直接改血量，
     *       绕过所有减伤。</li>
     * </ul>
     *
     * <p>伤害类型是本模组的自定义类型 {@code hatmod:flash}（见 {@link #flashSource}）。
     * 用自定义类型而不是 {@code minecraft:magic}，是因为原版和其它模组会给魔法伤害加抗性，
     * 自定义类型没有现成的防御手段。该类型同时挂进了
     * {@code minecraft:bypasses_invulnerability} 标签，所以灾变那种「无敌帧/伤害上限」
     * 也拦不住它（见 {@link #damageBeam}）。
     *
     * @param hatType 这顶帽子的类型（「全」是 ALL）：决定伤害类型 id、附加百分比等**配置**
     * @param route   「全」本轮走的路线；三顶原色帽就是它自己。**帧伤数值**取
     *                {@link HatSettings#routeDamage}（出厂黑 12 / 白 10 / 红 15，可调），
     *                所以「全」轮到哪条路线就用哪条的帧伤。
     */
    private static void damageBeam(ServerLevel level, LivingEntity wearer, HatType hatType,
                                   HatType route, ItemStack hat, List<LivingEntity> targets) {
        // 每刻固定伤害：
        //   · 三顶原色帽 —— 自己的 damagePerTick（默认 10，可用调参器改）；
        //   · 「全」—— 按本轮路线取（默认黑 12 / 白 10 / 红 15，可在调参器「全帽」页改），
        //     **不读「全」自己配置的 damagePerTick**，这样「黑路线 = 黑帽手感」才成立。
        float flatDamage = hatType == HatType.ALL
                ? HatSettings.routeDamage(route)
                : HatSettings.damagePerTick(hatType);
        // 戴帽者身上的力量/虚弱折成倍率，固定伤害和百分比伤害一起吃（见 beamPowerMultiplier）
        float power = beamPowerMultiplier(wearer);
        // 伤害类型 / 附加百分比等仍按帽子自己的配置走（「全」用的是「全」那份）
        DamageSource source = HatSettings.damageSource(level, wearer, hatType);

        // 每道光柱各打自己锁定的那个目标。红帽附魔「光柱」会有多道：
        // 每道光柱沿自己的方向结算一遍。所有帽子天生 2 道：这两道叠在同一目标上**不打折**；
        // 红帽附魔「光柱」多出来的道，敌人够多就一人一道，敌人不够时堆叠到已有目标上，
        // 按 BEAM_STACK_FACTOR 边际递减。每道伤害再乘 BEAM_DAMAGE_SCALE（基础两道合计 = 原来一道）。
        // 没有目标时不结算（客户端仍会沿视线画出这几道，见 tickingFlash）。
        int distinct = distinctTargets(targets);
        for (int i = 0; i < targets.size(); i++) {
            float factor = power * BEAM_DAMAGE_SCALE;
            if (i >= BASE_BEAM_COUNT && distinct > 0) {
                // 同一目标上第 m 道「附魔光柱」才打折；基础那几道永远满伤
                int enchantStack = (i - BASE_BEAM_COUNT) / distinct;
                factor *= (float) Math.pow(BEAM_STACK_FACTOR, enchantStack);
            }
            float drained = damageOneBeam(level, wearer, hatType, source, flatDamage * factor, factor, targets.get(i));
            applyLifesteal(wearer, hat, drained);
        }
    }

    /**
     * 分身光柱的一刻结算：**和正主同一套伤害**，只是不控人。
     *
     * <p>固定帧伤照 {@code state.route} 取（{@code HatSettings.routeDamage}，和正主同一个旋钮），
     * 百分比伤害照 {@code HatType.ALL} 取（正主也是这么取的：帧伤跟路线、百分比跟帽子本身）。
     * 打之前把目标的动量存下来、打完还原，{@code hurt()} 自带的击退就不留了。
     */
    static void damageShadeBeam(ServerLevel level, LivingEntity shooter, LivingEntity target) {
        if (target.isRemoved() || !target.isAlive() || !isEnemy(target, shooter)) {
            return;
        }
        HatType route = HatType.WHITE;
        // 伤害**记在召唤者头上**：分身自己只是一个生物，某些 Boss（星之魔女就是）
        // 在 die() 里会问「凶手是不是玩家」，不是就把死亡拦下来、把血补回去 ——
        // 表现就是「被分身打死会满血复活」。记到玩家身上就按正常击杀走。
        LivingEntity credit = shooter;
        if (shooter instanceof HatSummon summon) {
            route = summon.route();
            LivingEntity owner = summon.ownerEntity(level);
            if (owner != null) {
                credit = owner;
            }
        }
        // 先扣百分比那部分（绕过减伤），再用正常 hurt() 收尾 —— 顺序和正主一致
        float before = target.getHealth();
        applyPercentageDamage(credit, target, HatType.ALL, 1.0F);
        if (target.isRemoved() || !target.isAlive()) {
            return;
        }
        DamageSource source = HatSettings.damageSource(level, credit, HatType.ALL);
        // 先清零受伤冷却，不然每刻这点伤害会被 10 刻无敌帧整个吃掉
        Vec3 motion = target.getDeltaMovement();
        float beforeFlat = target.getHealth();
        pierceHurt(target, source, HatSettings.routeDamage(route));
        if (target.isAlive() && beforeFlat - target.getHealth() < HatSettings.routeDamage(route) * 0.25F) {
            clampPierce(target, beforeFlat * HatSettings.healthDamageRatio(HatType.ALL));
        }
        target.setDeltaMovement(motion);
        target.hurtMarked = true;
        // 分身打的也进破防的账
        settleForce(target, Math.max(0.0F, before - target.getHealth()),
                HatSettings.routeDamage(route) + before * HatSettings.healthDamageRatio(HatType.ALL));
    }

    /**
     * 把一份伤害拆成 {@link #PIERCE_SPLIT} 段分别 {@code hurt()}（破「每击上限」）。
     */
    static void pierceHurt(LivingEntity target, DamageSource source, float total) {
        float per = total / PIERCE_SPLIT;
        for (int i = 0; i < PIERCE_SPLIT; i++) {
            if (target.isRemoved() || !target.isAlive()) {
                return;
            }
            // 每段都得先清零无敌帧，否则第二段起全被 10 刻受伤冷却吃掉
            target.invulnerableTime = 0;
            target.hurt(source, per);
        }
    }

    /** 「压最大生命」用的修饰符（1.21 走 ResourceLocation）。 */
    private static final ResourceLocation MAX_HEALTH_PIERCE_ID = HatMod.id("max_health_pierce");

    /**
     * 破防阶梯的最后一级：**临时压低目标的最大生命**。
     *
     * <p>原版在最大生命被改小时会把当前血量夹到新上限（`LivingEntity#onAttributeUpdated`），
     * 这一下**不经过任何伤害计算** —— 「每击上限 / 减伤 / 无敌阶段 / 伤害类型免疫」全都管不着它。
     * 压完立刻把上限还原：上限回去了，掉的血不会回来。
     *
     * <p>留底：不会压到 1 点以下，也不低于最大生命的 5%，收尾交给正常的 {@code hurt()}。
     */
    static void clampPierce(LivingEntity target, float amount) {
        if (amount <= 0.0F || target.isRemoved() || !target.isAlive()) {
            return;
        }
        AttributeInstance maxHealth = target.getAttribute(Attributes.MAX_HEALTH);
        if (maxHealth == null) {
            return;
        }
        double max = maxHealth.getValue();
        float floor = Math.max(1.0F, target.getMaxHealth() * 0.05F);
        double wanted = Math.max(floor, target.getHealth() - amount);
        double shrink = Math.min(0.0D, wanted - max);
        if (shrink >= 0.0D) {
            return;
        }
        try {
            maxHealth.addTransientModifier(new AttributeModifier(
                    MAX_HEALTH_PIERCE_ID, shrink, AttributeModifier.Operation.ADD_VALUE));
        } catch (IllegalArgumentException ignored) {
            return;
        }
        // 立刻还原上限：血已经被夹下去了，这一下不会回去
        maxHealth.removeModifier(MAX_HEALTH_PIERCE_ID);
        // 这一下也是绕过伤害系统直接改的血量，同样要跟对方的真实存储对账
        syncChannel(target);
    }

    /**
     * 记一笔：这一轮在这个目标身上**应该**打掉多少、**实际**掉了多少。
     *
     * <p>「实际」是 {@code getHealth()} 的差值 —— 不估算对方减免了多少，直接量结果。
     */
    /** 破防：累计缺口达到「目标最大生命的这个比例」就直接尾杀。 */
    private static final float DEFICIT_RATIO = 0.25F;
    /** 缺口的下限：小怪别因为几点差额就被点名。 */
    private static final float DEFICIT_MIN = 40.0F;
    /** 目标 -> {累计被吃掉的伤害, 最后一次记账的毫秒时间}。 */
    private static final Map<UUID, float[]> DEFICIT = new HashMap<>();

    static void noteCycleDamage(LivingEntity target, float dealt, float intended) {
        if (HatSettings.forceBreak() && HIT_THIS_SECOND.size() <= 512) {
            HIT_THIS_SECOND.put(target.getUUID(), target);
        }
        float[] entry = CYCLE_DAMAGE.get(target.getUUID());
        if (entry == null) {
            entry = new float[]{0.0F, 0.0F};
            CYCLE_DAMAGE.put(target.getUUID(), entry);
        }
        entry[0] += Math.max(0.0F, dealt);
        entry[1] += Math.max(0.0F, intended);
        // 破防：累计「理论上应该打出来的伤害」，给秒杀门槛用
        if (HatSettings.forceBreak()) {
            if (EXEC_BUDGET.size() > 512) {
                EXEC_BUDGET.clear();
                EXEC_LOCKED_LOGGED.clear();
            }
            EXEC_BUDGET.merge(target.getUUID(), Math.max(0.0F, intended), Float::sum);
        }
    }

    /** 目标 -> 累计「理论上应该造成的伤害」。 */
    private static final Map<UUID, Float> EXEC_BUDGET = new HashMap<>();
    /** 已经提示过「尾杀锁定」的目标，别每刻刷屏。 */
    private static final Set<UUID> EXEC_LOCKED_LOGGED = new HashSet<>();

    /** 这一秒里我们打到过谁（光柱、近战、召唤物都算）。 */
    private static final Map<UUID, LivingEntity> HIT_THIS_SECOND = new HashMap<>();
    /** 上一秒结算时的血量快照，用来判断「打了，但血一点没动」。 */
    private static final Map<UUID, Float> HEALTH_SNAPSHOT = new HashMap<>();
    /**
     * 目标 -> 连着多少秒「打得到、血量一点没动」。
     *
     * <p>判据本来是「按总血量算出还要打几轮」，问题出在**分母**上：永爱之刃那台「黑塔」
     * 把 {@code getHealth()} 和 {@code getMaxHealth()} 都写死成 2^31-1、{@code hurt()}
     * 直接 {@code return false}，按它报的血量算轮数会得到天文数字，这道门永远不开。
     *
     * <p>所以判据改成看**血量快照**：每秒比一次，打得到但数值一动不动，就记一秒。
     * 它报的数是不是真的都骗不过这里 —— 真掉血就会下降，下降就清零。
     */
    private static final Map<UUID, Integer> ZERO_ROUNDS = new HashMap<>();
    /** 目标 -> 最近一轮「理论上打出去多少」。 */
    private static final Map<UUID, Float> ROUND_OUTPUT = new HashMap<>();
    /** 连着这么多秒打得到却一点血没掉，就不再等「打满一条命」的预算，直接尾杀。 */
    private static final int MAX_EXEC_ROUNDS = 5;

    /** 最近一次「谁打的它」：处决窗口要按**出手者的链路阶段**判。 */
    private static final Map<UUID, LivingEntity> HIT_BY = new HashMap<>();
    /** 牛仔帽一轮照射的最后这么多刻算「尾声」。 */
    private static final int HAT_FINISH_WINDOW_TICKS = 40;

    /**
     * 这个出手者现在是不是处在**链路尾声**。
     *
     * <p>破防的处决（{@link #finishOff}）只在这段时间里允许发生：债和快照全程照记，
     * 但真正动手要等链路走到收尾，不在光束刚亮起来的时候就开始抹人。
     */
    private static boolean isFinishWindow(LivingEntity wearer) {
        if (wearer == null) {
            return false;
        }
        if (BrainrotCycle.isRunning(wearer)) {
            return BrainrotCycle.inFinishWindow(wearer);
        }
        HatState state = STATES.get(wearer.getUUID());
        return state != null && state.flash > 0 && state.flash <= HAT_FINISH_WINDOW_TICKS;
    }

    /** 这个目标现在能不能被处决：得有一个正处在链路尾声的出手者。 */
    private static boolean finishAllowed(LivingEntity target) {
        return isFinishWindow(HIT_BY.get(target.getUUID()));
    }

    /**
     * 破防记账的一个入口：这笔伤害是戴帽者（或他的召唤物）打出去的，就记下目标。
     *
     * <p>以前只有光柱结算会记账，近战 / 召唤物打出去的一概不进账本。这里补上；
     * 至于「到底掉没掉血」，交给每秒的血量快照去判断，不信任何一方自己报的数。
     */
    static void noteHitByHat(LivingEntity victim, @Nullable Entity attacker) {
        if (!HatSettings.forceBreak() || attacker == null || attacker == victim) {
            return;
        }
        if (!(attacker instanceof LivingEntity source)) {
            return;
        }
        if (!isWearingHat(source) && !(source instanceof HatSummon)) {
            return;
        }
        if (HIT_THIS_SECOND.size() > 512) {
            HIT_THIS_SECOND.clear();
            HIT_BY.clear();
        }
        HIT_THIS_SECOND.put(victim.getUUID(), victim);
        HIT_BY.put(victim.getUUID(), source);
    }

    /**
     * 秒杀门槛：这一场**理论上该打出来的伤害**先打满一条命，才允许走尾杀。
     *
     * <p>按「血量 ÷ 每轮输出」算下来要 N 轮才能打死的 Boss，前 N 轮只有原来那些手段，
     * 第 N+1 轮才解锁秒杀。这里直接用「累计 intended ≥ 目标最大生命」当判据：
     * 每轮打掉一条命的 1/N，累计到一条命正好是第 N 轮结束。
     */
    private static boolean executionUnlocked(LivingEntity target) {
        // 打得到、血量却连着 MAX_EXEC_ROUNDS 秒一点没动：它报的血量是假的（黑塔那种），
        // 「按血量算还要打几轮」这个判据在它身上没有意义，直接放行
        if (ZERO_ROUNDS.getOrDefault(target.getUUID(), 0) >= MAX_EXEC_ROUNDS) {
            return true;
        }
        float done = EXEC_BUDGET.getOrDefault(target.getUUID(), 0.0F);
        float need = Math.max(40.0F, target.getMaxHealth());
        if (done < need) {
            if (EXEC_LOCKED_LOGGED.add(target.getUUID())) {
                HatMod.LOGGER.info("[hatmod] 破防：{} 理论伤害还没打满（{} / {}），尾杀暂不开放",
                        target.getType(), Math.round(done), Math.round(need));
            }
            return false;
        }
        return true;
    }

    /**
     * 破防的**统一记账口**：任何帽子、任何伤害路径都从这里过。
     *
     * <p>记一笔「应该打出多少 / 实际掉了多少」；目标已经被判定破防的话，
     * 当场把差额补写进它的真实血量（{@link #forceHealth}）。
     */
    static void settleForce(LivingEntity target, float dealt, float intended) {
        noteCycleDamage(target, dealt, intended);
        if (FORCE_TARGETS.contains(target.getUUID())) {
            forceHealth(target, intended - dealt);
        }
        accumulateDeficit(target, dealt, intended);
    }

    /**
     * 破防的另一条判据：**它吃掉多少伤害，就攒多少**。
     *
     * <p>原本要等血量被压到地板才会走 {@link #finishOff}；碰到减伤、单次上限、无敌阶段的
     * Boss，血根本压不下去，尾杀就永远进不了。这里每次结算都记「应该打出 / 实际掉了」的差额，
     * 累计到 {@link #DEFICIT_RATIO} 就直接尾杀 —— 不给它把血磨回来的机会。
     */
    private static void accumulateDeficit(LivingEntity target, float dealt, float intended) {
        if (!HatSettings.forceBreak() || target instanceof Player || !target.isAlive()) {
            return;
        }
        float missed = intended - Math.max(0.0F, dealt);
        if (missed <= 0.0F) {
            return;
        }
        UUID id = target.getUUID();
        float[] entry = DEFICIT.get(id);
        if (entry == null) {
            if (DEFICIT.size() > 512) {
                DEFICIT.clear();
            }
            entry = new float[]{0.0F, 0.0F};
            DEFICIT.put(id, entry);
        }
        entry[0] += missed;
        entry[1] = (float) System.currentTimeMillis();
        float limit = Math.max(DEFICIT_MIN, target.getMaxHealth() * DEFICIT_RATIO);
        if (entry[0] >= limit) {
            // 债记着不动，但要等出手者的链路走到尾声才真动手
            if (!finishAllowed(target)) {
                return;
            }
            float total = entry[0];
            DEFICIT.remove(id);
            HatMod.LOGGER.warn("[hatmod] 破防：{} 累计吃掉 {} 点伤害（阈值 {}），链路已到尾声，走尾杀",
                    target.getType(), Math.round(total), Math.round(limit));
            finishOff(target);
        }
    }

    /** 新一轮开始：上一轮的账清掉。 */
    static void resetCycleDamage() {
        CYCLE_DAMAGE.clear();
    }

    /**
     * 一轮结束时的判定：**实际掉血 / 应该造成的伤害** 低于 {@link #FORCE_THRESHOLD} 的目标，
     * 下一轮标成「破防」；打得动的就恢复正常结算。
     */
    private static void evaluateForceCycle(net.minecraft.server.MinecraftServer server) {
        // 10 秒没有新缺口的记账作废，免得换个怪还接着上一只的账
        long now = System.currentTimeMillis();
        DEFICIT.entrySet().removeIf(entry -> now - (long) entry.getValue()[1] > 10_000L);
        FORCE_TARGETS.clear();
        if (!HatSettings.forceBreak()) {
            // 破防模式关着：连账都不记，一点不碰对方的血量
            CYCLE_DAMAGE.clear();
            ZERO_ROUNDS.clear();
            ROUND_OUTPUT.clear();
            HIT_THIS_SECOND.clear();
            HEALTH_SNAPSHOT.clear();
            return;
        }
        for (Map.Entry<UUID, float[]> entry : CYCLE_DAMAGE.entrySet()) {
            float dealt = entry.getValue()[0];
            float intended = entry.getValue()[1];
            if (intended > 0.0F && dealt < intended * FORCE_THRESHOLD) {
                FORCE_TARGETS.add(entry.getKey());
            }
            // 这一轮理论上打了多少，留着给「还要打几轮」当分母
            ROUND_OUTPUT.put(entry.getKey(), intended);
        }
        CYCLE_DAMAGE.clear();

        // 血量快照对比：这一秒里我们打到过的目标，血量一动不动 = 它根本吃不进伤害。
        // 不依赖哪条伤害路径（光柱、近战、召唤物都一样），也不信它自己报的掉血数。
        for (Map.Entry<UUID, LivingEntity> hit : HIT_THIS_SECOND.entrySet()) {
            LivingEntity target = hit.getValue();
            if (target == null || target.isRemoved() || !target.isAlive()) {
                ZERO_ROUNDS.remove(hit.getKey());
                HEALTH_SNAPSHOT.remove(hit.getKey());
                continue;
            }
            float current = hatmodGetHealth(target);
            Float before = HEALTH_SNAPSHOT.put(hit.getKey(), current);
            if (before == null) {
                continue;                       // 第一次打到，先记一条基线
            }
            if (current < before) {             // 真的掉血了，一切照旧
                ZERO_ROUNDS.remove(hit.getKey());
                continue;
            }
            if (ZERO_ROUNDS.size() > 512) {
                ZERO_ROUNDS.clear();
            }
            int seconds = ZERO_ROUNDS.merge(hit.getKey(), 1, Integer::sum);
            // 每满 MAX_EXEC_ROUNDS 秒试一次（失败了下个周期还会再试，不会卡死），
            // 但要等链路尾声 —— 光束刚亮起来时不抹人
            if (seconds % MAX_EXEC_ROUNDS == 0 && finishAllowed(target)) {
                HatMod.LOGGER.warn("[hatmod] 破防：{} 连着 {} 秒打得到、血量一点没动，"
                        + "判定为硬免疫，出尾杀", target.getType(), seconds);
                finishOff(target);
            }
        }
        HIT_THIS_SECOND.clear();
    }

    /** 这个目标这一轮是不是处于「破防」状态。 */
    static boolean isForceTarget(LivingEntity target) {
        return FORCE_TARGETS.contains(target.getUUID());
    }

    /**
     * 破防状态下强制扣血：先走 {@code setHealth}（它自己的掉血流程、仇恨、死亡都还算数），
     * 血一点没动（有血量镜像 / 写了会被改回的 Boss）就直接写 {@code health} 字段。
     *
     * <p>地板照旧留 5%（或 1 点），收尾交给正常伤害 —— {@code setHealth} 不会触发 {@code die()}。
     */
    static void forceHealth(LivingEntity target, float amount) {
        if (!HatSettings.forceBreak() || amount <= 0.0F || target.isRemoved() || !target.isAlive()) {
            return;
        }
        ensureFightable(target);
        float floor = Math.max(1.0F, target.getMaxHealth() * 0.05F);
        float current = hatmodGetHealth(target);
        float want = Math.max(floor, current - amount);
        if (want >= current) {
            return;
        }
        // 写血 + 找出它的真血字段一起写，并记住这个值；每刻末尾再覆盖一次
        writeTrueHealth(target, want);
    }


    // ------------------------------------------------------------------
    // 破防 v2：血量托管制
    //
    // 看了这么多 Boss，共同点只有一个：血量被搬到某个地方、由它们自己每刻写。
    // 所以这里不去拆它们的逻辑，而是：
    //   1. 探测「权威血量」到底存在哪（五通道，按值认、不按名字认）；
    //   2. 破防命中时写进去，并记下"应该是多少"；
    //   3. 每个服务器刻的**最后**再覆盖一遍 —— 回血、满血循环、镜像同步全部抹平；
    //   4. 血被压到地板还判活着，才走终局：先求正常死亡事件，拒收就套永爱式硬杀。
    // ------------------------------------------------------------------

    /** 一个被接管目标的记录。 */
    private static final class Seal {
        float hp;
        @Nullable
        HealthChannel channel;
        int finishTries;
        boolean logged;

        Seal(float hp, @Nullable HealthChannel channel) {
            this.hp = hp;
            this.channel = channel;
        }
    }

    /** 把「权威血量」读出来 / 写进去的最小接口。 */
    private interface HealthChannel {
        float read(LivingEntity entity);

        void write(LivingEntity entity, float value);
    }

    /** 破防模式下「删不掉」的登记表：被外人删掉的虚影 / 铁托会在几刻内被拉回来。 */
    private static final Map<UUID, Entity> WATCHED = new HashMap<>();

    /** 被挖走之前最后一次「正常」的位置：救回来的时候原地放回去。 */
    private static final Map<UUID, double[]> LAST_POS = new HashMap<>();

    /** 登记一只虚影 / 铁托（只在服务端登记），顺手记住它在世界里的位置。 */
    static void watchSummon(Entity entity) {
        if (!(entity.level() instanceof ServerLevel)) {
            return;
        }
        WATCHED.put(entity.getUUID(), entity);
        if (!entity.isRemoved() && entity.getY() > -1000.0D) {
            LAST_POS.put(entity.getUUID(), new double[]{entity.getX(), entity.getY(), entity.getZ()});
        }
    }

    /** 本体自己收掉它的时候，从登记表里摘掉（不然下一 tick 又给拉回来了）。 */
    static void forgetSummon(Entity entity) {
        WATCHED.remove(entity.getUUID());
        LAST_POS.remove(entity.getUUID());
    }

    /**
     * 每 5 刻看一遍登记表：被外人删掉的拉回来；开了「实体表接管」就再塞回实体管理器。
     *
     * <p>这是 pig2 {@code fukkatsu} 的路子，只是我们只拉自己人，不碰别人的实体。
     */
    private static void reviveSummons(MinecraftServer server) {
        if (!HatSettings.forceBreak() || WATCHED.isEmpty() || server.getTickCount() % 5 != 0) {
            return;
        }
        Iterator<Map.Entry<UUID, Entity>> iterator = WATCHED.entrySet().iterator();
        while (iterator.hasNext()) {
            Entity entity = iterator.next().getValue();
            if (!(entity instanceof LivingEntity living)) {
                iterator.remove();
                continue;
            }
            if (!living.isRemoved() && living.isAlive()) {
                continue;
            }
            // 正常死亡就不管了，只救「被人挖走」的
            if (isTrulyDead(living) && living.isRemoved() && living.getHealth() <= 0.0F) {
                iterator.remove();
                continue;
            }
            if (!(living.level() instanceof ServerLevel level)) {
                iterator.remove();
                continue;
            }
            unsetRemoved(living);
            boolean present = level.getEntity(living.getUUID()) != null;
            if (!present) {
                present = level.addFreshEntity(living);
            }
            if (!present && HatSettings.entityLock() && reinsertIntoEntityManager(level, living)) {
                HatMod.LOGGER.warn("[hatmod] {} 被从实体表里挖走，已强行塞回", living.getType());
                present = true;
            }
            if (present) {
                // pig2 的清场会先把人传到 (10000, -10000, 10000) 再打上隐身标记，
                // 救回来的时候得把它放回原处、把隐身摘掉，否则「人回来了但看不见」
                double[] pos = LAST_POS.get(living.getUUID());
                if (pos != null && living.getY() < -1000.0D) {
                    living.moveTo(pos[0], pos[1], pos[2]);
                }
                living.setInvisible(false);
            } else {
                iterator.remove();
            }
        }
    }

    /** {@code Entity.unsetRemoved()} 是 protected，反射叫一下（1.20.1 生产环境是 SRG 名）。 */
    private static void unsetRemoved(Entity entity) {
        for (String name : new String[]{"unsetRemoved", "m_146912_"}) {
            for (Class<?> c = entity.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    java.lang.reflect.Method method = c.getDeclaredMethod(name);
                    method.setAccessible(true);
                    method.invoke(entity);
                    return;
                } catch (NoSuchMethodException ignored) {
                    // 换下一个名字 / 父类
                } catch (Throwable ignored) {
                    return;
                }
            }
        }
    }

    /**
     * pig2 那招：直接往 {@code PersistentEntitySectionManager} 里塞实体。
     *
     * <p>按类型找字段和方法，不按名字 —— 1.20.1 生产环境全是 SRG 名。
     */
    /** 拿到这个维度的 {@code PersistentEntitySectionManager}（按类型找，不按名字）。 */
    @Nullable
    private static Object entityManagerOf(ServerLevel level) {
        for (Class<?> c = level.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (field.getType().getName().contains("PersistentEntitySectionManager")) {
                    try {
                        field.setAccessible(true);
                        return field.get(level);
                    } catch (Throwable ignored) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    /**
     * pig2 同款清场：把它「传去天边 + 标成已删除 + 从实体管理器里抠掉」。
     *
     * <p>顺序照抄它的 {@code removeTheEnemiesInTheServerLevel}：先挪走（这样存档里也不会留在原地），
     * 再 {@code setRemoved(KILLED)}，最后把它从实体管理器的几张表里摘出来。
     * 只在我们已经把正常死亡 / 后门都试过、它还不死的时候才走这一步。
     */
    private static void pig2Purge(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level) || entity instanceof Player) {
            return;
        }
        try {
            // ① 先照 pig2 的 removeTheEnemiesInTheServerLevel 走一遍
            if (entity instanceof LivingEntity living) {
                living.kill();
            }
            entity.setInvisible(true);
            entity.moveTo(10000.0D, -10000.0D, 10000.0D);
            entity.setRemoved(Entity.RemovalReason.KILLED);
            // ② 区块缓存里也摘一次（pig2 是 serverLevel.getChunkSource().removeEntity）
            try {
                Object chunkSource = level.getChunkSource();
                for (java.lang.reflect.Method method : chunkSource.getClass().getMethods()) {
                    if (method.getName().equals("removeEntity") && method.getParameterCount() == 1) {
                        method.setAccessible(true);
                        method.invoke(chunkSource, entity);
                        break;
                    }
                }
            } catch (Throwable ignored) {
                // 区块缓存摘不掉不影响实体表那一步
            }
            Object manager = entityManagerOf(level);
            if (manager != null) {
                java.util.UUID id = entity.getUUID();
                Integer eid = entity.getId();
                for (Class<?> c = manager.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Field field : c.getDeclaredFields()) {
                        try {
                            field.setAccessible(true);
                            Object value = field.get(manager);
                            if (value instanceof java.util.Collection<?> collection) {
                                collection.remove(id);
                            } else if (value instanceof java.util.Map<?, ?> map) {
                                map.remove(id);
                                map.remove(eid);
                            }
                        } catch (Throwable ignored) {
                            // 单张表清不掉不影响整体
                        }
                    }
                }
            }
            // ③ 最后把分区（EntitySection）里那一份也剔掉，不然重新加载区块又冒出来
            purgeFromSections(manager, entity);
            HatMod.LOGGER.warn("[hatmod] 尾杀清场：{} 已从实体表摘除", entity.getType());
        } catch (Throwable ignored) {
            // 摘不掉就交给 discard 之后的轮询
        }
    }

    /** 把实体从它所在的 EntitySection 里剔掉（pig2 是整段重建，我们逐个删）。 */
    private static void purgeFromSections(Object manager, Entity entity) {
        try {
            for (Class<?> c = manager.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    field.setAccessible(true);
                    Object value = field.get(manager);
                    if (!(value instanceof java.util.Map<?, ?> sections)) {
                        continue;
                    }
                    for (Object section : sections.values()) {
                        if (section == null) {
                            continue;
                        }
                        for (java.lang.reflect.Method getter : section.getClass().getMethods()) {
                            if (getter.getParameterCount() != 0
                                    || !getter.getReturnType().getName().contains("ClassInstanceMultiMap")) {
                                continue;
                            }
                            Object storage = getter.invoke(section);
                            if (storage == null) {
                                continue;
                            }
                            for (java.lang.reflect.Method remove : storage.getClass().getMethods()) {
                                if (remove.getName().equals("remove") && remove.getParameterCount() == 1) {
                                    remove.invoke(storage, entity);
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // 分区剔不掉就靠 setRemoved 那一步
        }
    }

    private static boolean reinsertIntoEntityManager(ServerLevel level, Entity entity) {
        try {
            Object manager = entityManagerOf(level);
            if (manager == null) {
                return false;
            }
            for (java.lang.reflect.Method method : manager.getClass().getMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 2 && params[0] == Entity.class && params[1] == boolean.class
                        && method.getReturnType() == boolean.class) {
                    method.setAccessible(true);
                    return Boolean.TRUE.equals(method.invoke(manager, entity, false));
                }
            }
        } catch (Throwable ignored) {
            // 塞不回去就算了，至少 unsetRemoved 已经做了
        }
        return false;
    }

    /** 非破防时也要用的通道缓存（按需探测一次）。 */
    private static final Map<UUID, HealthChannel> CHANNELS = new HashMap<>();

    /**
     * 把实体身上现在的血量同步进它真正的血量存储（世界变量 / 自有字段 / 镜像）。
     *
     * <p>我们的百分比伤害、压最大生命都是绕过伤害系统直接改实体血量的。对
     * 「实体血量只是副本」的 Boss（Witheria 的世界变量），这会让它的校验判定成篡改，
     * 于是删掉自己重新召唤 —— 必须每一步都跟着对账。
     */
    static void syncChannel(LivingEntity target) {
        HealthChannel channel = channelOf(target);
        if (channel != null && channel != VANILLA) {
            channel.write(target, target.getHealth());
            if (LOGGED_CHANNELS.add(target.getClass())) {
                HatMod.LOGGER.info("[hatmod] {} 的血量托管在 {} 里，百分比伤害改的是那一份",
                        target.getType(), channel.getClass().getSimpleName());
            }
        }
    }

    /** 每种怪只报一次用的。 */
    private static final java.util.Set<Class<?>> LOGGED_CHANNELS = new java.util.HashSet<>();

    /** 取（必要时探测一次）这个目标的血量通道，按 UUID 缓存。 */
    @Nullable
    private static HealthChannel channelOf(LivingEntity target) {
        HealthChannel channel = CHANNELS.get(target.getUUID());
        if (channel == null) {
            if (CHANNELS.size() > 512) {
                CHANNELS.clear();
            }
            channel = detectChannel(target);
            CHANNELS.put(target.getUUID(), channel);
        }
        return channel;
    }

    /**
     * 破防模式下的 {@code getHealth()}：读它**真正的**血量存储。
     *
     * <p>原版 {@code getHealth()} 对这类 Boss 是被钉死的假值 —— 星之魔女把同步血量固定成
     * 1.0、Witheria 把真血放进世界变量、亚伯伦放在自有字段里。拿假值去算「当前生命 × 5%」，
     * 结果就是 0.05 点，等于没打。破防既然已经接管了它们的血量存储，就顺手用同一份数据来读。
     *
     * <p>没开破防、或者探测不到通道时，退回原版 {@code getHealth()}，行为与以前一致。
     */
    static float hatmodGetHealth(LivingEntity target) {
        if (!HatSettings.forceBreak()) {
            return target.getHealth();
        }
        HealthChannel channel = channelOf(target);
        if (channel == null || channel == VANILLA) {
            return target.getHealth();
        }
        try {
            return channel.read(target);
        } catch (Throwable ignored) {
            return target.getHealth();
        }
    }

    /** 破防接管的目标 -> 记录。 */
    private static final Map<UUID, Seal> SEALED = new HashMap<>();
    /** 每种怪探测出来的通道（只探一次）。 */
    private static final Map<Class<?>, List<Field>> FLOAT_FIELDS = new HashMap<>();

    private static final HealthChannel VANILLA = new HealthChannel() {
        @Override
        public float read(LivingEntity entity) {
            return entity.getHealth();
        }

        @Override
        public void write(LivingEntity entity, float value) {
            entity.setHealth(value);
        }
    };

    /**
     * 找出「原版血量」所在的那条同步数据（{@code DATA_HEALTH_ID}）。做法是**试写**：
     * 值等于 {@code getHealth()} 的浮点同步数据，改一下看 {@code getHealth()} 跟不跟。
     *
     * <p>为什么不按名字取：1.20.1 生产环境里它是 SRG 的 {@code f_...}，NeoForge 下才是
     * {@code DATA_HEALTH_ID}；而且有的模组会把自己的血量也塞进同步数据 —— 按值认最稳。
     *
     * <p>这一条是给「只把 {@code setHealth()} 改成不理会外部写入、但 {@code getHealth()}
     * 仍然读同步数据」的 Boss 准备的：直接写底层数据，绕过那个被改过的 setter。
     */
    @Nullable
    private static EntityDataAccessor<Float> rawHealthAccessor(LivingEntity target) {
        float health = target.getHealth();
        if (health <= 2.0F) {
            return null;
        }
        for (EntityDataAccessor<?> accessor : accessors(target, Float.class)) {
            try {
                Object current = target.getEntityData().get(accessor);
                if (!(current instanceof Float value) || Math.abs(value - health) > 0.01F) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                EntityDataAccessor<Float> data = (EntityDataAccessor<Float>) accessor;
                float probe = health - 1.0F;
                target.getEntityData().set(data, probe);
                boolean works = Math.abs(target.getHealth() - probe) <= 0.01F;
                // 试探完立刻还原：不是这条就一点痕迹都不留
                target.getEntityData().set(data, health);
                if (works) {
                    return data;
                }
            } catch (Throwable ignored) {
                // 下一个
            }
        }
        return null;
    }

    /** 直接写原版血量同步数据（绕过被覆盖的 {@code setHealth}）。 */
    private static final class DataChannel implements HealthChannel {
        private final EntityDataAccessor<Float> accessor;

        DataChannel(EntityDataAccessor<Float> accessor) {
            this.accessor = accessor;
        }

        @Override
        public float read(LivingEntity entity) {
            try {
                Float value = entity.getEntityData().get(accessor);
                return value == null ? entity.getHealth() : value;
            } catch (Throwable ignored) {
                return entity.getHealth();
            }
        }

        @Override
        public void write(LivingEntity entity, float value) {
            try {
                entity.getEntityData().set(accessor,
                        Math.max(0.0F, Math.min(value, entity.getMaxHealth())));
            } catch (Throwable ignored) {
                // 写不进去就交给每刻覆盖那一道
            }
        }
    }

    /**
     * 探测这个生物的「权威血量」在哪。
     *
     * <p>顺序（贵的、特殊的放前面）：
     * <ol>
     *   <li><b>G 世界变量</b> —— MCreator 那系把血放在 {@code ModVariables} 里的（Witheria）；</li>
     *   <li><b>F 权威血量方法对</b> —— {@code xxxHealth()} / {@code setXxxHealth(float)}，
     *       对应那些把原版血量钉成假值的 Boss（星之魔女恒为 1.0）；</li>
     *   <li><b>A 原版 setHealth</b>；</li>
     *   <li><b>E 原版血量同步数据</b>（绕过被覆盖的 setHealth）；</li>
     *   <li><b>B 自有字段</b>（Regnator / 星辉死神的 {@code HEALTS}）；</li>
     *   <li><b>C 同步浮点对</b>（variant_zsiein 的 {@code EXALTED_NORMAL - EXALTED_AWAY}）；</li>
     *   <li><b>D 同步字符串</b>（星之魔女的 {@code BOSS_HEALTH}）：先按"等于 getHealth()"严格认，
     *       认不出来再看是不是血量被钉死了 —— 那时放宽成"是个合理血量就认"。</li>
     * </ol>
     */
    @Nullable
    private static HealthChannel detectChannel(LivingEntity target) {
        // H：星之魔女 —— 走它自己的「剧情血量」通道，跳过它给外来伤害设的四道闸
        HealthChannel controlled = controllerHealthChannel(target);
        if (controlled != null) {
            return controlled;
        }

        // G：世界变量（Witheria）
        HealthChannel world = worldVariableChannel(target);
        if (world != null) {
            return world;
        }

        // F：权威血量方法对（星之魔女）
        HealthChannel authoritative = authoritativeHealthChannel(target);
        if (authoritative != null) {
            return authoritative;
        }

        // A：原版 setHealth 真的能改血量
        try {
            float before = target.getHealth();
            if (before > 2.0F) {
                target.setHealth(before - 1.0F);
                boolean works = Math.abs(target.getHealth() - (before - 1.0F)) < 0.01F;
                target.setHealth(before);
                if (works) {
                    return VANILLA;
                }
            }
        } catch (Throwable ignored) {
            // 继续往下探
        }

        // E：原版血量那条同步数据（DATA_HEALTH_ID）直接写 —— 绕过被覆盖的 setHealth
        EntityDataAccessor<Float> rawHealth = rawHealthAccessor(target);
        if (rawHealth != null) {
            return new DataChannel(rawHealth);
        }

        // B：自有 float/double 字段，当前值等于 getHealth()
        for (Field field : healthFields(target.getClass())) {
            try {
                double held = field.getType() == float.class ? field.getFloat(target) : field.getDouble(target);
                if (Math.abs(held - target.getHealth()) <= 0.5D) {
                    return new FieldChannel(field);
                }
            } catch (Throwable ignored) {
                // 下一个
            }
        }

        // C：同步数据里的两个浮点，满足 a - b == getHealth()
        List<EntityDataAccessor<?>> floats = accessors(target, Float.class);
        for (EntityDataAccessor<?> a : floats) {
            for (EntityDataAccessor<?> b : floats) {
                if (a == b) {
                    continue;
                }
                try {
                    Object va = target.getEntityData().get(a);
                    Object vb = target.getEntityData().get(b);
                    if (va instanceof Float fa && vb instanceof Float fb
                            && Math.abs((fa - fb) - target.getHealth()) <= 0.5F) {
                        return new SyncPairChannel(a, b);
                    }
                } catch (Throwable ignored) {
                    // 下一个
                }
            }
        }

        // D：同步数据里的字符串
        HealthChannel mirror = stringMirrorChannel(target, false);
        if (mirror != null) {
            return mirror;
        }
        if (isPinnedHealth(target)) {
            // 血量被钉成假值的 Boss：字符串里的数字只要落在一个合理区间就认
            return stringMirrorChannel(target, true);
        }
        return null;
    }

    /** D：同步数据里的字符串镜像。{@code relaxed} 打开时只要求"是个合理血量"。 */
    @Nullable
    private static HealthChannel stringMirrorChannel(LivingEntity target, boolean relaxed) {
        float max = Math.max(1.0F, target.getMaxHealth());
        for (EntityDataAccessor<?> accessor : accessors(target, String.class)) {
            try {
                Object raw = target.getEntityData().get(accessor);
                if (!(raw instanceof String text)) {
                    continue;
                }
                float parsed = Float.parseFloat(text.trim());
                if (!Float.isFinite(parsed)) {
                    continue;
                }
                boolean matched = relaxed
                        ? parsed > 0.5F && parsed <= max + 0.5F
                        : Math.abs(parsed - target.getHealth()) <= 0.5F;
                if (matched) {
                    return new SyncStringChannel(accessor);
                }
            } catch (Throwable ignored) {
                // 下一个
            }
        }
        return null;
    }

    /** 原版血量是不是被钉成假值了（星之魔女恒写 1.0，真血在别处）。 */
    private static boolean isPinnedHealth(LivingEntity target) {
        return target.getHealth() <= 1.5F && target.getMaxHealth() > 8.0F;
    }

    /**
     * F：找一对「xxxHealth() / setXxxHealth(float)」。
     *
     * <p>星之魔女的真血走 {@code maidspell$authoritativeHealth()}，原版 {@code getHealth()}
     * 恒为 1.0 —— 只认原版血量的话，百分比伤害会拿 1.0 去算，等于没打。
     */
    @Nullable
    private static HealthChannel authoritativeHealthChannel(LivingEntity target) {
        float max = Math.max(1.0F, target.getMaxHealth());
        List<Class<?>> types = new ArrayList<>();
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            types.add(c);
        }
        for (Class<?> iface : target.getClass().getInterfaces()) {
            types.add(iface);
        }
        for (Class<?> type : types) {
            for (java.lang.reflect.Method getter : type.getDeclaredMethods()) {
                if (getter.getParameterCount() != 0) {
                    continue;
                }
                Class<?> ret = getter.getReturnType();
                if (ret != float.class && ret != double.class) {
                    continue;
                }
                String name = getter.getName();
                String lower = name.toLowerCase(java.util.Locale.ROOT);
                if (!lower.contains("health") || lower.contains("max") || lower.equals("gethealth")) {
                    continue;
                }
                // 同名 set 方法：名字里含同一段核心词（maidspell$setAuthoritativeHealth）
                int dollar = name.indexOf('$');
                String core = (dollar >= 0 ? name.substring(dollar + 1) : name).toLowerCase(java.util.Locale.ROOT);
                for (Class<?> setterOwner : types) {
                    for (java.lang.reflect.Method setter : setterOwner.getDeclaredMethods()) {
                        if (setter.getParameterCount() != 1 || setter.getParameterTypes()[0] != float.class) {
                            continue;
                        }
                        if (!setter.getName().toLowerCase(java.util.Locale.ROOT).contains(core)) {
                            continue;
                        }
                        try {
                            getter.setAccessible(true);
                            setter.setAccessible(true);
                            float current = ((Number) getter.invoke(target)).floatValue();
                            if (current > 0.0F && current <= max + 0.5F
                                    && Math.abs(current - target.getHealth()) > 0.01F) {
                                return new MethodChannel(getter, setter);
                            }
                        } catch (Throwable ignored) {
                            // 换下一对
                        }
                    }
                }
            }
        }
        return null;
    }

    /** F 通道：走 mod 自己的「权威血量」读写方法。 */
    private static final class MethodChannel implements HealthChannel {
        private final java.lang.reflect.Method getter;
        private final java.lang.reflect.Method setter;

        MethodChannel(java.lang.reflect.Method getter, java.lang.reflect.Method setter) {
            this.getter = getter;
            this.setter = setter;
        }

        @Override
        public float read(LivingEntity entity) {
            try {
                return ((Number) getter.invoke(entity)).floatValue();
            } catch (Throwable ignored) {
                return entity.getHealth();
            }
        }

        @Override
        public void write(LivingEntity entity, float value) {
            try {
                setter.invoke(entity, Math.max(0.0F, Math.min(value, entity.getMaxHealth())));
            } catch (Throwable ignored) {
                // 写不进去就交给每刻覆盖那一道
            }
        }
    }

    /**
     * G：世界变量。MCreator 那一系（Witheria）把血放在 {@code ModVariables} 的
     * {@code Witheria_Health} / {@code Witheria_True_Health} 里，实体血量只是副本 ——
     * 只写实体血量会被它的校验抓（差值 > 4 就抹掉重召唤）。
     */
    @Nullable
    // ------------------------------------------------------------------
    // H：星之魔女（maidspell）—— 借它自己的「剧情血量」通道
    // ------------------------------------------------------------------

    private static final String WINEFOX_ENTITY =
            "com.github.yimeng261.maidspell.compat.irons_spellbooks.entity.winefox.MagicalWinefoxBossEntity";
    private static final String WINEFOX_CONTROLLER =
            "com.github.yimeng261.maidspell.compat.irons_spellbooks.entity.winefox.WinefoxBossHealthController";

    private static java.lang.reflect.Method WINEFOX_WRITE;
    private static java.lang.reflect.Method WINEFOX_WITH_CAUSE;
    private static Object WINEFOX_SCRIPTED_CAUSE;
    private static boolean WINEFOX_PROBED;

    /**
     * 星之魔女把真血关在 {@code WinefoxBossHealthController} 里，外面写进去的血
     * 都要过「战斗状态 / 每击间隔 / 单次上限 / 自适应 DPS 预算 / 高伤减免」这一串闸门，
     * 连续几帧的百分比伤害基本全被丢掉 —— 看起来就是帽子没伤害。
     *
     * <p>它自己有一个 {@code Cause.SCRIPTED_TRANSITION}（剧情血量变更），那个 cause
     * 不在 {@code isCappedCause} 里，闸门全跳过，直接精确写进 healthState 并同步镜像。
     */
    @Nullable
    private static HealthChannel controllerHealthChannel(LivingEntity target) {
        if (!WINEFOX_ENTITY.equals(target.getClass().getName())) {
            return null;
        }
        if (!WINEFOX_PROBED) {
            WINEFOX_PROBED = true;
            try {
                Class<?> entityType = Class.forName(WINEFOX_ENTITY);
                Class<?> controller = Class.forName(WINEFOX_CONTROLLER);
                Class<?> causeType = Class.forName(WINEFOX_CONTROLLER + "$Cause");
                WINEFOX_WRITE = controller.getMethod("write", entityType, float.class);
                WINEFOX_WITH_CAUSE = controller.getMethod("withCause", entityType, causeType, Runnable.class);
                for (Object constant : causeType.getEnumConstants()) {
                    if ("SCRIPTED_TRANSITION".equals(String.valueOf(constant))) {
                        WINEFOX_SCRIPTED_CAUSE = constant;
                        break;
                    }
                }
            } catch (Throwable ignored) {
                WINEFOX_WRITE = null;
                WINEFOX_WITH_CAUSE = null;
                WINEFOX_SCRIPTED_CAUSE = null;
            }
        }
        if (WINEFOX_WRITE == null || WINEFOX_WITH_CAUSE == null || WINEFOX_SCRIPTED_CAUSE == null) {
            return null;
        }
        return new ControllerChannel(WINEFOX_WRITE, WINEFOX_WITH_CAUSE, WINEFOX_SCRIPTED_CAUSE);
    }

    /** H 通道：把写入塞进 {@code withCause(boss, SCRIPTED_TRANSITION, () -> write(boss, v))}。 */
    private static final class ControllerChannel implements HealthChannel {
        private final java.lang.reflect.Method write;
        private final java.lang.reflect.Method withCause;
        private final Object scriptedCause;

        ControllerChannel(java.lang.reflect.Method write, java.lang.reflect.Method withCause,
                          Object scriptedCause) {
            this.write = write;
            this.withCause = withCause;
            this.scriptedCause = scriptedCause;
        }

        @Override
        public float read(LivingEntity entity) {
            return entity.getHealth();
        }

        @Override
        public void write(LivingEntity entity, float value) {
            try {
                Runnable action = () -> {
                    try {
                        write.invoke(null, entity, value);
                    } catch (Throwable ignored) {
                        // 写不进去就算了，外层还有每刻覆盖
                    }
                };
                withCause.invoke(null, entity, scriptedCause, action);
            } catch (Throwable ignored) {
                // 同上
            }
        }
    }

    private static HealthChannel worldVariableChannel(LivingEntity target) {
        if (!target.getClass().getName().startsWith("net.mcreator.witheria.entity.WitheriaEntity")) {
            return null;
        }
        try {
            Class<?> variables = Class.forName("net.mcreator.witheria.network.WitheriaModVariables$MapVariables");
            java.lang.reflect.Method getter = null;
            for (java.lang.reflect.Method method : variables.getDeclaredMethods()) {
                if (!method.getName().equals("get") || method.getParameterCount() != 1
                        || !java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                if (method.getParameterTypes()[0].isInstance(target.level())) {
                    getter = method;
                    break;
                }
            }
            if (getter == null) {
                return null;
            }
            getter.setAccessible(true);
            Object map = getter.invoke(null, target.level());
            if (map == null) {
                return null;
            }
            List<Field> fields = new ArrayList<>();
            float max = Math.max(1.0F, target.getMaxHealth());
            for (Field field : map.getClass().getFields()) {
                if (field.getType() != double.class && field.getType() != float.class) {
                    continue;
                }
                String lower = field.getName().toLowerCase(java.util.Locale.ROOT);
                if (!lower.contains("health") || lower.contains("max")) {
                    continue;
                }
                double value = field.getType() == double.class ? field.getDouble(map) : field.getFloat(map);
                if (value > 0.0D && value <= (double) max + 4.0D) {
                    fields.add(field);
                }
            }
            if (fields.isEmpty()) {
                return null;
            }
            return new WorldVariableChannel(getter, fields);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** G 通道：读写世界变量里的那几个血量字段，并把改动同步出去。 */
    private static final class WorldVariableChannel implements HealthChannel {
        private final java.lang.reflect.Method getter;
        private final List<Field> fields;
        private net.minecraft.world.level.Level lastLevel;
        private Vec3 lastPos;

        WorldVariableChannel(java.lang.reflect.Method getter, List<Field> fields) {
            this.getter = getter;
            this.fields = fields;
        }

        @Nullable
        private Object mapOf(net.minecraft.world.level.Level level) {
            try {
                return getter.invoke(null, level);
            } catch (Throwable ignored) {
                return null;
            }
        }

        @Override
        public float read(LivingEntity entity) {
            Object map = mapOf(entity.level());
            if (map == null) {
                return entity.getHealth();
            }
            try {
                // True_Health 才是它自己校验用的那个，优先读它
                for (Field field : fields) {
                    if (field.getName().toLowerCase(java.util.Locale.ROOT).contains("true")) {
                        return field.getType() == double.class ? (float) field.getDouble(map) : field.getFloat(map);
                    }
                }
                Field field = fields.get(0);
                return field.getType() == double.class ? (float) field.getDouble(map) : field.getFloat(map);
            } catch (Throwable ignored) {
                return entity.getHealth();
            }
        }

        @Override
        public void write(LivingEntity entity, float value) {
            this.lastLevel = entity.level();
            this.lastPos = entity.position();
            Object map = mapOf(entity.level());
            if (map == null) {
                return;
            }
            float clamped = Math.max(0.0F, value);
            try {
                for (Field field : fields) {
                    if (field.getType() == double.class) {
                        field.setDouble(map, clamped);
                    } else {
                        field.setFloat(map, clamped);
                    }
                }
                sync(map, entity.level());
            } catch (Throwable ignored) {
                // 同上
            }
        }

        /**
         * 接手结束：把世界变量和场上那一只对回同一个数。
         *
         * <p>Witheria 的 {@code AdditionalCheckProcedure} 会拿 {@code Witheria_True_Health}
         * 跟实体血量比对，差 > 4 就判成篡改 —— 删掉这只再召唤一只新的。我们放手时如果不把
         * 变量同步回去，新召唤的那只会立刻又对不上，于是无限循环。
         */
        void release(@Nullable LivingEntity entity) {
            net.minecraft.world.level.Level level = entity != null ? entity.level() : this.lastLevel;
            Vec3 pos = entity != null ? entity.position() : this.lastPos;
            if (!(level instanceof ServerLevel) || pos == null) {
                return;
            }
            Object map = mapOf(level);
            if (map == null) {
                return;
            }
            float health = 0.0F;
            try {
                Class<?> type = Class.forName("net.mcreator.witheria.entity.WitheriaEntity");
                List<?> found = ((ServerLevel) level).getEntitiesOfClass((Class) type,
                        new AABB(pos, pos).inflate(256.0D));
                if (!found.isEmpty() && found.get(0) instanceof LivingEntity living && living.isAlive()) {
                    health = living.getHealth();
                }
            } catch (Throwable ignored) {
                // 拿不到就当它已经不在了
            }
            try {
                for (Field field : fields) {
                    if (field.getType() == double.class) {
                        field.setDouble(map, health);
                    } else {
                        field.setFloat(map, health);
                    }
                }
                if (health <= 0.0F) {
                    for (Field field : map.getClass().getFields()) {
                        if (field.getType() == boolean.class && "active".equalsIgnoreCase(field.getName())) {
                            field.setBoolean(map, false);
                        }
                    }
                }
                sync(map, level);
            } catch (Throwable ignored) {
                // 同 release 的备注
            }
        }

        private void sync(Object map, net.minecraft.world.level.Level level) {
            try {
                for (java.lang.reflect.Method method : map.getClass().getDeclaredMethods()) {
                    if (method.getName().equals("syncData") && method.getParameterCount() == 1) {
                        method.setAccessible(true);
                        method.invoke(map, level);
                        return;
                    }
                }
            } catch (Throwable ignored) {
                // 同步失败不影响主逻辑
            }
        }
    }

    private static final class FieldChannel implements HealthChannel {
        private final Field field;

        FieldChannel(Field field) {
            this.field = field;
        }

        @Override
        public float read(LivingEntity entity) {
            try {
                return field.getType() == float.class ? field.getFloat(entity) : (float) field.getDouble(entity);
            } catch (Throwable ignored) {
                return entity.getHealth();
            }
        }

        @Override
        public void write(LivingEntity entity, float value) {
            try {
                if (field.getType() == float.class) {
                    field.setFloat(entity, value);
                } else {
                    field.setDouble(entity, value);
                }
            } catch (Throwable ignored) {
                // 写不进去就交给每刻覆盖那一道
            }
        }
    }

    private static final class SyncPairChannel implements HealthChannel {
        private final EntityDataAccessor<?> normal;
        private final EntityDataAccessor<?> away;

        SyncPairChannel(EntityDataAccessor<?> normal, EntityDataAccessor<?> away) {
            this.normal = normal;
            this.away = away;
        }

        @Override
        public float read(LivingEntity entity) {
            try {
                Object n = entity.getEntityData().get(normal);
                Object a = entity.getEntityData().get(away);
                if (n instanceof Float fn && a instanceof Float fa) {
                    return fn - fa;
                }
            } catch (Throwable ignored) {
                // 落到下面
            }
            return entity.getHealth();
        }

        @Override
        public void write(LivingEntity entity, float value) {
            try {
                Object n = entity.getEntityData().get(normal);
                float normalValue = n instanceof Float fn ? fn : value;
                float away = Math.max(0.0F, normalValue - value);
                @SuppressWarnings("unchecked")
                EntityDataAccessor<Float> accessor = (EntityDataAccessor<Float>) this.away;
                entity.getEntityData().set(accessor, away);
            } catch (Throwable ignored) {
                // 同上
            }
        }
    }

    private static final class SyncStringChannel implements HealthChannel {
        private final EntityDataAccessor<?> accessor;

        SyncStringChannel(EntityDataAccessor<?> accessor) {
            this.accessor = accessor;
        }

        @Override
        public float read(LivingEntity entity) {
            try {
                Object raw = entity.getEntityData().get(accessor);
                if (raw instanceof String text) {
                    return Float.parseFloat(text.trim());
                }
            } catch (Throwable ignored) {
                // 落到下面
            }
            return entity.getHealth();
        }

        @Override
        public void write(LivingEntity entity, float value) {
            try {
                @SuppressWarnings("unchecked")
                EntityDataAccessor<String> cast = (EntityDataAccessor<String>) this.accessor;
                entity.getEntityData().set(cast, Float.toString(value));
            } catch (Throwable ignored) {
                // 同上
            }
        }
    }

    /** 类里所有 float / double、非静态的字段（按类缓存）。 */
    private static List<Field> healthFields(Class<?> type) {
        List<Field> cached = FLOAT_FIELDS.get(type);
        if (cached != null) {
            return cached;
        }
        List<Field> found = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (field.getType() != float.class && field.getType() != double.class) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    found.add(field);
                } catch (Throwable ignored) {
                    // 跳过
                }
            }
        }
        FLOAT_FIELDS.put(type, found);
        return found;
    }

    /** 类（含父类）里所有同步数据访问器，按**当前值的实际类型**过滤。 */
    private static List<EntityDataAccessor<?>> accessors(LivingEntity target, Class<?> valueType) {
        List<EntityDataAccessor<?>> found = new ArrayList<>();
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (!EntityDataAccessor.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object accessor = field.get(null);
                    if (!(accessor instanceof EntityDataAccessor<?> data)) {
                        continue;
                    }
                    Object value = target.getEntityData().get(data);
                    if (valueType.isInstance(value)) {
                        found.add(data);
                    }
                } catch (Throwable ignored) {
                    // 跳过
                }
            }
        }
        return found;
    }

    /**
     * 把血写进探测到的通道，并记下"应该是多少"。
     */
    private static void writeTrueHealth(LivingEntity target, float value) {
        float wanted = Math.max(0.0F, value);
        Seal seal = SEALED.get(target.getUUID());
        if (seal == null) {
            seal = new Seal(wanted, detectChannel(target));
            SEALED.put(target.getUUID(), seal);
            if (!seal.logged) {
                seal.logged = true;
                HatMod.LOGGER.info("[hatmod] 破防接管 {}：血量通道 = {}，当前 {}",
                        target.getType(), seal.channel == null ? "未探测到（仅 setHealth）" : seal.channel.getClass().getSimpleName(),
                        target.getHealth());
            }
        }
        seal.hp = wanted;
        if (seal.channel != null) {
            seal.channel.write(target, wanted);
        }
        target.setHealth(wanted);
    }

    /**
     * 每个服务器刻**最后**跑：把被接管目标的血量覆盖回我们记的值，并处理终局。
     */
    private static void tickSealedHealth(net.minecraft.server.MinecraftServer server) {
        if (SEALED.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Seal>> iterator = SEALED.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Seal> entry = iterator.next();
            Seal seal = entry.getValue();
            LivingEntity target = findLiving(server, entry.getKey());
            if (target == null || target.isRemoved() || !target.isAlive()) {
                // 世界变量类的 Boss：接手结束前必须把变量跟场上那只对回去，
                // 不然它的「血量被篡改」校验会判定成外挂，删掉再召唤，无限循环
                if (seal.channel instanceof WorldVariableChannel world) {
                    world.release(target);
                }
                iterator.remove();
                continue;
            }
            float wanted = seal.hp;
            if (Math.abs(target.getHealth() - wanted) > 0.01F) {
                if (seal.channel != null) {
                    seal.channel.write(target, wanted);
                }
                target.setHealth(wanted);
            }
            // 血量已经压到地板还判活着：先立刻试一次，之后每秒一次
            float floor = Math.max(1.0F, target.getMaxHealth() * 0.05F);
            if (seal.hp <= floor + 0.5F && seal.finishTries++ % 20 == 0 && finishAllowed(target)) {
                finishOff(target);
            }
        }
    }

    /**
     * 终局：先求一个**正常的死亡事件**，对方拒收才套永爱之刃那套硬杀。
     *
     * <p>逐档往下掉，能在前面停住就别走到后面：
     * <ol>
     *   <li>{@code hurt(genericKill, MAX)} —— 走完整伤害管线，能死就是正常死，
     *       掉落、死亡消息、成就、Boss 条全对；</li>
     *   <li>反射直接叫 {@code die()} —— 只拦 {@code hurt}、不拦 {@code die} 的 Boss
     *       到这一档就死，同样带掉落和演出；</li>
     *   <li>永爱式硬杀 —— 自己 roll 一次掉落，然后 {@code discard()} 把它从实体表里摘掉，
     *       并把 UUID 拉黑一段时间；它的复活逻辑要是又把它塞回来，见到一次摘一次。</li>
     * </ol>
     */
    static void finishOff(LivingEntity target) {
        if (target instanceof Player) {
            return;
        }
        // 秒杀门槛：先把理论上该打的伤害打满，才有资格走尾杀
        if (!executionUnlocked(target)) {
            return;
        }
        if (isUntouchable(target)) {
            if (UNTOUCHABLE_LOGGED.add(target.getClass())) {
                HatMod.LOGGER.warn("[hatmod] {} 把「被删除」当计数器用，破防的硬杀跳过它"
                        + "（删了它也只会喂它自己的计数器，还得被它反噬）", target.getType());
            }
            return;
        }
        DamageSource source = target.damageSources().genericKill();
        // 不留情面名单：破防的目标就是「让它消失」，不是「陪它打完这一场」。
        // 它有 NPC 保护、有死亡守卫、有战败流程 —— 统统不问了，直接抹。
        if (isNoMercy(target)) {
            HatMod.LOGGER.warn("[hatmod] 破防：{} 在「不留情面」名单里，跳过死亡流程直接抹除", target.getType());
            hardKill(target, source);
            return;
        }
        // 一档 + 二档：先求一个正常的死亡事件 —— 只有它**真从实体表里没了**才算数
        if (giveDeathEvent(target, source) && isReallyGone(target)) {
            return;
        }
        // 打不过就加入：把它自己那套「离场 / 战败 / 结束」开关挨个试一遍。
        // 注意：调到了不等于它真死了 —— 星之魔女的 setDead 就是个空壳，调用成功但什么也没发生。
        if (tryBackdoors(target) && isReallyGone(target)) {
            return;
        }
        // 更深一层：直接调用它自己的「退场程序」，把战斗状态变量清零。
        // 不这么做的话，Witheria 的 CheckProcedure 会在下一 tick 就把它重新召唤出来。
        if (endEncounter(target) && isReallyGone(target)) {
            return;
        }
        // 有的 Boss 被删掉会自己重新召唤（Witheria），硬杀只会让它无限复活
        if (isSelfSummoning(target)) {
            if (UNTOUCHABLE_LOGGED.add(target.getClass())) {
                HatMod.LOGGER.warn("[hatmod] {} 被删掉会自己重新召唤，破防跳过硬杀", target.getType());
            }
            return;
        }
        hardKill(target, source);
    }

    /**
     * 破防的最后一档：不指望它配合，直接抹掉。
     *
     * <p>顺序是「先把权威血量清零 → 摘实体 → 从实体表抠掉 → 拉黑」：
     * 血量清零是为了让它的每刻逻辑（回血、状态机、守护）看到 0，别再往回加；
     * 剩下的交给 {@link #pig2Purge}。
     */
    private static void hardKill(LivingEntity target, DamageSource source) {
        try {
            HealthChannel channel = CHANNELS.get(target.getUUID());
            if (channel == null) {
                channel = detectChannel(target);
                CHANNELS.put(target.getUUID(), channel);
            }
            if (channel != null && channel != VANILLA) {
                channel.write(target, 0.0F);
            }
        } catch (Throwable ignored) {
            // 清不掉就算了，后面照样摘实体
        }
        if (isAntiMod(target)) {
            // 反模组实体（pig2 那种）：它自己会看调用栈，判断"这次是不是别的模组在动我"，
            // 是就 return + 直接开战。所以一步 API 都不碰，只做它拦不了的三件事：
            // 写 removalReason 字段、走实体管理器的 onRemove、给客户端发移除包。
            forceRemove(target);
            noteForceRemoval(target);
            HatMod.LOGGER.warn("[hatmod] 破防硬杀 {}（{}）：反模组实体，已跳过全部 API 调用",
                    target.getType(), target.getUUID());
            return;
        }
        invokeSingle(target, "dropAllDeathLoot", "m_6668_", source);
        target.setHealth(0.0F);
        target.discard();
        // 上面全是 API 层面。碰到把 hurt / setHealth / discard / remove 全写成空方法的实体
        // （比如永爱之刃的「黑塔」），这三步一步都不会生效 —— 走下面两档真删除。
        if (!isReallyGone(target)) {
            eternalKill(target, source.getEntity());
            if (!isReallyGone(target)) {
                forceRemove(target);
            }
            // 这类实体的 mod 往往还会把它重新刷出来：记一笔，反复出现就按类型封禁
            noteForceRemoval(target);
        }
        pig2Purge(target);
        PURGED.put(target.getUUID(), new Purge());
        HatMod.LOGGER.warn("[hatmod] 破防硬杀 {}（{}），已从实体表摘除（{}）",
                target.getType(), target.getUUID(), isReallyGone(target) ? "已消失" : "仍在！");
    }

    /** 永爱之刃的工具类（可选依赖，反射调用；没装就返回 false）。 */
    private static final String ETERNAL_UTILS = "com.wzz.forever_love_sword.util.ForeverUtils";
    private static final String ETERNAL_API = "com.wzz.forever_love_sword.api.IForeverUtils";

    /**
     * 借「永爱之刃」自己的强制击杀。
     *
     * <p>它对「黑塔」这类实体的杀法不是走 {@code hurt} / {@code setHealth}，而是
     * 反射写 {@code removalReason} 字段 + 换掉 {@code levelCallback} 把实体从实体表里摘掉 ——
     * 所以它杀得动。这套我们不复刻，直接借它（同包环境里现成可用）。
     *
     * @return 调用成功（不代表目标已经消失，调用方还会再验一次）
     */
    private static boolean eternalKill(LivingEntity target, Entity killer) {
        try {
            Class<?> utils = Class.forName(ETERNAL_UTILS);
            Object instance = utils.getMethod("getInstance").invoke(null);
            if (instance == null) {
                return false;
            }
            Method kill;
            try {
                kill = Class.forName(ETERNAL_API)
                        .getMethod("killEntity", Entity.class, LivingEntity.class, boolean.class);
            } catch (Throwable ignored) {
                kill = instance.getClass()
                        .getMethod("killEntity", Entity.class, LivingEntity.class, boolean.class);
            }
            kill.invoke(instance, target, killer, Boolean.TRUE);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 在 {@code owner} 里按**类型**找字段。
     *
     * <p>不能按名字找：Forge 运行时 MC 类的字段是 SRG 名（{@code f_146795_} 之类），
     * 拿 Mojang 名 {@code removalReason} / {@code levelCallback} 去 {@code getDeclaredField}
     * 只会抛 {@code NoSuchFieldException} —— 之前强删一直「失败」、Teto 的守卫一直没装上，
     * 都是这个原因。
     */
    @Nullable
    static Field fieldOfType(Class<?> owner, Class<?> type) {
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (field.getType() == type) {
                    field.setAccessible(true);
                    return field;
                }
            }
        }
        return null;
    }

    /**
     * 自己动手的兜底：绕过实体自己的重写，从**区域实体管理器**那一层把它摘掉。
     *
     * <ol>
     *   <li>反射把 {@code Entity.removalReason} 写成 KILLED —— 字段没有重写，一定写得进；</li>
     *   <li>调用实体自己的 {@code EntityInLevelCallback#onRemove} —— 这是实体管理器挂在实体上的
     *       包装类，它会把实体从 section 和追踪表里摘掉，同样不经过实体自己的方法。</li>
     * </ol>
     */
    /** 给 Teto 用的公开入口：自己散场时如果 discard 没删干净，直接硬摘。 */
    static void forceRemoveEntity(LivingEntity target) {
        forceRemove(target);
    }

    private static void forceRemove(LivingEntity target) {
        try {
            Field reason = fieldOfType(Entity.class, RemovalReason.class);
            if (reason == null) {
                throw new IllegalStateException("找不到 removalReason 字段");
            }
            reason.set(target, RemovalReason.KILLED);
            Field callback = fieldOfType(Entity.class,
                    net.minecraft.world.level.entity.EntityInLevelCallback.class);
            if (callback == null) {
                throw new IllegalStateException("找不到 levelCallback 字段");
            }
            Object wrapper = callback.get(target);
            if (wrapper instanceof EntityInLevelCallback inLevel) {
                inLevel.onRemove(RemovalReason.KILLED);
            }
            // 客户端那边也要摘：服务端从实体表删了、客户端照旧在画，看上去就是"杀不死"。
            // 永爱之刃专门为这件事准备了 ClientEntityRemovePacket，我们直接用原版的移除包。
            if (target.level() instanceof ServerLevel serverLevel) {
                serverLevel.getChunkSource().broadcastAndSend(target,
                        new ClientboundRemoveEntitiesPacket(target.getId()));
            }
            HatMod.LOGGER.warn("[hatmod] 破防兜底：绕过重写强删 {}（{}）",
                    target.getType(), target.getUUID());
        } catch (Throwable error) {
            HatMod.LOGGER.warn("[hatmod] 强删失败 {}：{}", target.getType(), error.toString());
        }
    }

    /**
     * 「不留情面」名单：这些目标在破防下直接抹除，不走任何它自己的流程。
     *
     * <p>星之魔女当 NPC 时 {@code m_6469_} 直接拒伤、{@code m_6667_} 被死亡守卫拦下、
     * 血量控制器还要看战斗状态 —— 陪她走完这一场没有任何意义。
     */
    private static final String[] NO_MERCY = {
            "com.github.yimeng261.maidspell.compat.irons_spellbooks.entity.winefox.MagicalWinefoxBossEntity",
    };

    /**
     * 「专门反别的模组」的实体名单：对它们调用任何 API 都会被识别成"外模组动手"，
     * 结果是调用被吞掉、还会反过来开战。这类只走纯字段级抹除。
     *
     * <p>pig2mod 的 {@code Pig2} 就是典型：{@code MyLib2.isCalledFromOtherModWithin5()}
     * 扫调用栈，命中就在 {@code setRemoved / remove / setHealth} 里 {@code return} + {@code seeFighting()}，
     * 而且它还会备份自己的 {@code levelCallback}，有人把回调换成 NULL 就还原回去。
     */
    private static final String[] ANTI_MOD = {
            "kakiku.pig2mod.entity.Pig2",
    };

    private static boolean isAntiMod(Entity target) {
        String name = target.getClass().getName();
        for (String prefix : ANTI_MOD) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNoMercy(Entity target) {
        String name = target.getClass().getName();
        for (String prefix : NO_MERCY) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 求一个**正常的死亡事件**：先走完整伤害管线，不行就把 {@code die()} 直接叫出来。
     *
     * <p>只拦 {@code hurt}、不拦 {@code die} 的 Boss 在这一步就死，掉落和演出照样有。
     * 返回是否真的死了 —— 没死才轮到上游去考虑「硬杀」。
     */
    static boolean giveDeathEvent(LivingEntity target, DamageSource source) {
        if (target instanceof Player) {
            return false;
        }
        target.invulnerableTime = 0;
        target.setInvulnerable(false);
        // setHealth(0) 之后 hurt() 会被「已经死了」直接挡掉，先还一点点血再打
        if (target.getHealth() <= 0.0F) {
            float max = Math.max(1.0F, target.getMaxHealth());
            target.setHealth(Math.min(1.0F, max * 0.01F));
        }
        // 一档：正常伤害管线
        target.hurt(source, Math.max(100.0F, target.getMaxHealth() * 4.0F));
        if (isTrulyDead(target)) {
            endEncounter(target);
            return true;
        }
        // 二档：不拦 hurt、只拦 setHealth 的那类，die() 叫出来照样走完整死亡流程
        invokeSingle(target, "die", "m_6667_", source);
        boolean dead = isTrulyDead(target);
        if (dead) {
            endEncounter(target);
        }
        return dead;
    }

    /**
     * 虚影 / 铁托阵亡时的反噬 —— 照搬 pig2 那套：谁把我们的人打死，
     * 就在它尸体被删除的前一秒（{@code deathTime == 19}）把对方从世界里抹掉。
     *
     * <p>只对「外人」生效：玩家、自己人、以及 pig2 这种删不得的一律放过。
     */
    /** 反噬清场的半径（格）与最多处理数量。 */
    private static final double RETALIATE_RADIUS = 32.0D;
    private static final int RETALIATE_MAX = 64;

    static void retaliate(Entity self, @Nullable Entity killer) {
        if (!HatSettings.forceBreak() || !(self.level() instanceof ServerLevel level)) {
            return;
        }
        LivingEntity owner = null;
        if (self instanceof OwnableEntity ownable) {
            owner = ownable.getOwner();
        }
        if (killer instanceof LivingEntity target && canRetaliate(owner, target)) {
            HatMod.LOGGER.warn("[hatmod] {} 反击：抹掉 {}", self.getType(), target.getType());
            finishOff(target);
        }
        // pig2 L4「皆消え失せろ」：顺手把周围一圈外人一起抹掉
        int wiped = 0;
        AABB area = new AABB(self.blockPosition()).inflate(RETALIATE_RADIUS);
        for (LivingEntity nearby : level.getEntitiesOfClass(LivingEntity.class, area)) {
            if (wiped >= RETALIATE_MAX) {
                break;
            }
            if (!canRetaliate(owner, nearby)) {
                continue;
            }
            finishOff(nearby);
            wiped++;
        }
        if (wiped > 0) {
            HatMod.LOGGER.warn("[hatmod] {} 反击清场：抹掉 {} 个外来生物", self.getType(), wiped);
        }
    }

    /** 这个目标能不能被反噬带走（玩家 / 自己人 / 玩家养的 / 删不得的一律放过）。 */
    private static boolean canRetaliate(@Nullable LivingEntity owner, LivingEntity target) {
        if (target instanceof Player || target instanceof HatSummon || target instanceof Teto) {
            return false;
        }
        if (isUntouchable(target) || isSelfSummoning(target) || !target.isAlive()) {
            return false;
        }
        if (owner != null && isFriendlyTo(owner, target)) {
            return false;
        }
        return !(target instanceof OwnableEntity ownable) || !(ownable.getOwner() instanceof Player);
    }

    /** 常见「离场 / 战败 / 结束」开关（boolean 版）。 */
    private static final String[] BACKDOOR_FLAGS = {
            "setWantAway", "setWantReturn", "setWantLeave", "setRetreat", "setFlee",
            "setEscaped", "setEscape", "setDefeated", "setDead", "setDeath",
            "setKilled", "setEnding", "setFinished", "setVanished", "setBanished"
    };

    /** 常见「离场 / 战败 / 结束」开关（float 版，一般是血量镜像）。 */
    private static final String[] BACKDOOR_VALUES = {"setExaltedAway", "setExaltedNormal"};

    /**
     * 「打不过就加入」：这些 Boss 都给自己留了正常的收场入口（离场、战败、结束），
     * 与其硬拆，不如按常见命名挨个试一遍 —— 真被叫中了，它自己会走完演出和清理。
     */
    /**
     * 「这一场已经打完了」的开关。
     *
     * <p>Witheria 的 {@code CheckProcedure} 挂在每个玩家的 {@code PlayerTickEvent.END} 上：
     * 只要 {@code Witheria==1 && Difficulty==2 && Witheria_True_Health>4}，它一旦发现场上
     * 512 格内没有 Witheria，就立刻在记录的坐标 {@code summon} 一只新的。也就是说光把实体
     * 杀掉是没有意义的 —— 下一 tick 就复活，看起来就是无限复活。
     *
     * <p>作者自己留了正规出口：{@code DespawnProcedure.execute(world, entity)} 会把
     * {@code Witheria / Witheria_Health / Witheria_True_Health / Difficulty / Damage_Interval /
     * Second_Phase / Turrets} 全部清零再同步。走这条路，战斗是真的结束了。
     *
     * <p>表里每一行是「类名 / 方法名 / 参数形状 / 目标实体包名」，以后遇到别的模组
     * 有同款退场入口，加一行就行。
     */
    private static final String[][] ENCOUNTER_END = {
            {"net.mcreator.witheria.procedures.DespawnProcedure", "execute", "WE",
                    "net.mcreator.witheria.entity.WitheriaEntity"},
    };

    /**
     * 调用目标自己模组里的「退场程序」，把它的战斗状态清零。
     *
     * @return 是否真的调用到了一个
     */
    private static boolean endEncounter(LivingEntity target) {
        if (!HatSettings.forceBreak() || target instanceof Player) {
            return false;
        }
        String name = target.getClass().getName();
        for (String[] hook : ENCOUNTER_END) {
            if (!name.startsWith(hook[3])) {
                continue;
            }
            try {
                Class<?> type = Class.forName(hook[0]);
                for (java.lang.reflect.Method method : type.getMethods()) {
                    if (!method.getName().equals(hook[1])
                            || !java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                        continue;
                    }
                    Class<?>[] params = method.getParameterTypes();
                    Object[] args;
                    if ("WE".equals(hook[2]) && params.length == 2) {
                        args = new Object[]{target.level(), target};
                    } else if ("W".equals(hook[2]) && params.length == 1) {
                        args = new Object[]{target.level()};
                    } else {
                        continue;
                    }
                    boolean matches = true;
                    for (int i = 0; i < params.length; i++) {
                        if (!params[i].isInstance(args[i])) {
                            matches = false;
                            break;
                        }
                    }
                    if (!matches) {
                        continue;
                    }
                    method.setAccessible(true);
                    method.invoke(null, args);
                    HatMod.LOGGER.warn("[hatmod] 破防收场：{} 已走它自己的退场程序 {}#{}"
                                    + "（战斗状态变量清零，不会再被重新召唤）",
                            target.getType(), type.getSimpleName(), method.getName());
                    return true;
                }
            } catch (Throwable ignored) {
                // 这个模组没装 / 版本对不上，下一个
            }
        }
        return false;
    }

    /** 目标是不是真的没了：死了、被摘了、或者已经不存活。 */
    /**
     * 它是不是**真的**从世界里没了 —— 不看它自己的 dead / removed 标记，直接查实体表。
     *
     * <p>黑塔那类实体把 {@code remove()} 重写成空方法：{@code die()} 走完了、{@code dead}
     * 标记也置上了，实体却还站在那儿。只看标记的话，尾杀会在「求死亡事件」那一档就以为
     * 大功告成直接返回，一次硬杀都走不到。
     */
    private static boolean isReallyGone(LivingEntity target) {
        if (target.isRemoved() && !(target.level() instanceof ServerLevel)) {
            return true;
        }
        if (target.level() instanceof ServerLevel level) {
            return level.getEntity(target.getId()) == null;
        }
        return target.isRemoved();
    }

    private static boolean deadOrGone(LivingEntity target) {
        return isTrulyDead(target) || target.isRemoved() || !target.isAlive();
    }

    // ------------------------------------------------------------------
    // 星之魔女的「开战」开关
    // ------------------------------------------------------------------

    private static java.lang.reflect.Method WINEFOX_BEGIN_ENCOUNTER;
    private static java.lang.reflect.Method WINEFOX_READ_STATE;
    private static Object WINEFOX_COMBAT_STATE;
    private static boolean WINEFOX_STATE_PROBED;

    /**
     * 星之魔女坐在那儿当 NPC 的时候（{@code SEATED} / {@code CHALLENGE_START}），
     * {@code m_6469_} 直接 {@code return false} —— 一点伤害都不接；
     * 血量控制器那边也会把「不在 COMBAT」的外部写入整条丢掉。
     *
     * <p>所以破防想杀她，第一步得先用她自己的
     * {@code maidspell$beginEncounter(WinefoxEncounterState.COMBAT)} 把这一场正式开起来。
     * 开了之后 {@code isSeated()} 为假，伤害、死亡、掉落、DEFEATED 状态全部走正常流程。
     */
    private static void ensureFightable(LivingEntity target) {
        if (!HatSettings.forceBreak() || !WINEFOX_ENTITY.equals(target.getClass().getName())) {
            return;
        }
        if (!WINEFOX_STATE_PROBED) {
            WINEFOX_STATE_PROBED = true;
            try {
                Class<?> entityType = Class.forName(WINEFOX_ENTITY);
                Class<?> stateType = Class.forName(WINEFOX_ENTITY.replace("MagicalWinefoxBossEntity",
                        "WinefoxEncounterState"));
                WINEFOX_BEGIN_ENCOUNTER = entityType.getDeclaredMethod("maidspell$beginEncounter", stateType);
                WINEFOX_BEGIN_ENCOUNTER.setAccessible(true);
                WINEFOX_READ_STATE = entityType.getDeclaredMethod("maidspell$encounterState");
                WINEFOX_READ_STATE.setAccessible(true);
                for (Object constant : stateType.getEnumConstants()) {
                    if ("COMBAT".equals(String.valueOf(constant))) {
                        WINEFOX_COMBAT_STATE = constant;
                        break;
                    }
                }
            } catch (Throwable ignored) {
                WINEFOX_BEGIN_ENCOUNTER = null;
                WINEFOX_READ_STATE = null;
                WINEFOX_COMBAT_STATE = null;
            }
        }
        if (WINEFOX_BEGIN_ENCOUNTER == null || WINEFOX_READ_STATE == null || WINEFOX_COMBAT_STATE == null) {
            return;
        }
        try {
            String state = String.valueOf(WINEFOX_READ_STATE.invoke(target));
            // 已经在打（COMBAT）、已经打完（DEFEATED）、正在退场（RETURNING）都不用管
            if (!"SEATED".equals(state) && !"CHALLENGE_START".equals(state)) {
                return;
            }
            if (Boolean.TRUE.equals(WINEFOX_BEGIN_ENCOUNTER.invoke(target, WINEFOX_COMBAT_STATE))) {
                HatMod.LOGGER.warn("[hatmod] 破防把 {} 从 {} 拉进战斗（走它自己的 beginEncounter）",
                        target.getType(), state);
            }
        } catch (Throwable ignored) {
            // 拉不进来就按老路硬杀
        }
    }

    private static boolean tryBackdoors(LivingEntity target) {
        for (String name : BACKDOOR_FLAGS) {
            if (callBackdoor(target, name, boolean.class, Boolean.TRUE)) {
                return true;
            }
        }
        for (String name : BACKDOOR_VALUES) {
            if (callBackdoor(target, name, float.class, target.getMaxHealth())) {
                return true;
            }
        }
        return false;
    }

    /** 调一个后门方法；调完看看它是不是真的收场了（死了 / 被移除 / 血见底）。 */
    private static boolean callBackdoor(LivingEntity target, String name, Class<?> param, Object value) {
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Method method = c.getDeclaredMethod(name, param);
                method.setAccessible(true);
                method.invoke(target, value);
                HatMod.LOGGER.info("[hatmod] 已强制触发 {} 的后门 {}", target.getType(), name);
                return isTrulyDead(target) || !target.isAlive() || target.getHealth() <= 0.01F;
            } catch (NoSuchMethodException ignored) {
                // 试父类
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }

    /** 被删掉会自己重新召唤的那一类（Witheria）。 */
    private static boolean isSelfSummoning(Entity target) {
        return target.getClass().getName().startsWith("net.mcreator.witheria.entity.WitheriaEntity");
    }

    /** 真的死了吗。{@code dead} 字段最准 —— 有些 Boss 会把 {@code isRemoved()} 谎报成 false。 */
    private static boolean isTrulyDead(LivingEntity target) {
        if (target.isRemoved()) {
            return true;
        }
        try {
            Field dead = deadField(target.getClass());
            if (dead != null) {
                return dead.getBoolean(target);
            }
        } catch (Throwable ignored) {
            // 读不到就退回 isAlive
        }
        return !target.isAlive();
    }

    /** {@code LivingEntity#dead}：按名字找，找不到再按 SRG 名找。 */
    @Nullable
    private static Field deadField(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (String name : DEAD_NAMES) {
                try {
                    Field field = c.getDeclaredField(name);
                    if (field.getType() == boolean.class) {
                        field.setAccessible(true);
                        return field;
                    }
                } catch (NoSuchFieldException ignored) {
                    // 换下一个名字
                }
            }
        }
        return null;
    }

    /**
     * 调一个「单个参数、参数类型正好收得住 {@code arg}」的方法，名字给几个候选
     * （开发环境是 Mojmap，1.20.1 生产环境是 SRG，两边都得认）。
     */
    private static void invokeSingle(LivingEntity target, String name, String srgName, Object arg) {
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Method method : c.getDeclaredMethods()) {
                if (method.getParameterCount() != 1) {
                    continue;
                }
                if (!method.getName().equals(name) && !method.getName().equals(srgName)) {
                    continue;
                }
                if (!method.getParameterTypes()[0].isInstance(arg)) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    method.invoke(target, arg);
                    return;
                } catch (Throwable ignored) {
                    // 换下一个
                }
            }
        }
    }

    /**
     * 碰不得的生物：这些包下的类不参与破防硬杀。
     *
     * <p>有的模组把「被删除」本身当计数器 —— pig2 每次被删都会往它自己的自杀表里塞一个 id，
     * 塞到 50 次就调 {@code removeAllServerSide()} 把**其它模组的实体**一起抹掉，
     * 60 次进 MAX_FIGHT 反复抹；而它被删后 200ms 内就会自己 {@code copy()} 出一只新的，
     * 删了等于白删。这种目标只能走正常伤害，硬杀纯粹是在喂它。
     */
    private static final String[] UNTOUCHABLE_PACKAGES = {"kakiku.pig2mod."};
    private static final String[] UNTOUCHABLE_NAMESPACES = {"pig2"};
    private static final Set<Class<?>> UNTOUCHABLE_LOGGED = new HashSet<>();

    /** 这个目标是不是「碰不得」的那一类（见 {@link #UNTOUCHABLE_PACKAGES}）。 */
    private static boolean isUntouchable(Entity target) {
        String name = target.getClass().getName();
        for (String prefix : UNTOUCHABLE_PACKAGES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        net.minecraft.resources.ResourceLocation id =
                net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(target.getType());
        if (id == null) {
            return false;
        }
        for (String namespace : UNTOUCHABLE_NAMESPACES) {
            if (namespace.equals(id.getNamespace())) {
                return true;
            }
        }
        return false;
    }

    /** 硬杀过、还在被复活逻辑往回塞的目标。 */
    private static final Map<UUID, Purge> PURGED = new HashMap<>();
    /** 拉黑时长：60 秒没再露头就结案。 */
    private static final int PURGE_TICKS = 1200;
    /** 最多陪它玩这么多次，再多就是纯复活循环，放手。 */
    private static final int PURGE_MAX_TRIES = 600;
    private static final String[] DEAD_NAMES = {"dead", "f_20890_"};

    /** 一个被硬杀的目标：还剩多久结案、被摘掉了几次。 */
    private static final class Purge {
        int left = PURGE_TICKS;
        int tries;
        /** 「摘掉的是 Teto」这条日志已经打过几次。 */
        int logged;
    }

    // ------------------------------------------------------------------
    // 按类型封禁：删掉又被同一个 mod 重新刷出来的情况
    // ------------------------------------------------------------------

    /** 类名 -> 封禁记录。 */
    private static final Map<String, Ban> BANNED_TYPES = new HashMap<>();
    /** 同一个类在 {@link #BAN_WINDOW} 刻里被硬杀这么多次，就判定是"复活循环"。 */
    private static final int BAN_AFTER_TRIES = 2;
    /** 计数窗口（刻）：30 秒。 */
    private static final int BAN_WINDOW = 600;
    /** 封禁时长（刻）：120 秒 —— 只是压住这一阵，不会永久封死。 */
    private static final int BAN_TICKS = 2400;

    private static final class Ban {
        long lastKillTick;
        long untilTick;
        int hits;
        /** 拦截日志已经打过几次（别刷屏）。 */
        int logged;
    }

    /** 硬杀成功删掉一次之后记一笔；短时间内反复被刷出来就升格成类型封禁。 */
    private static void noteForceRemoval(LivingEntity target) {
        long now = target.level().getGameTime();
        Ban ban = BANNED_TYPES.computeIfAbsent(target.getClass().getName(), key -> new Ban());
        if (now - ban.lastKillTick > BAN_WINDOW) {
            ban.hits = 0;
        }
        ban.lastKillTick = now;
        if (++ban.hits >= BAN_AFTER_TRIES && now > ban.untilTick) {
            ban.untilTick = now + BAN_TICKS;
            HatMod.LOGGER.warn("[hatmod] {} 删掉又回来，按类型封禁 {} 秒",
                    target.getType(), BAN_TICKS / 20);
        }
    }

    /** 封禁期内，同类型的实体一刷出来就摘掉。 */
    private static void tickBans(net.minecraft.server.MinecraftServer server) {
        if (BANNED_TYPES.isEmpty()) {
            return;
        }
        long now = server.getTickCount();
        BANNED_TYPES.entrySet().removeIf(entry -> now > entry.getValue().untilTick
                && now - entry.getValue().lastKillTick > BAN_WINDOW);
        if (BANNED_TYPES.isEmpty()) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            // 先收名单再删：边遍历边摘会撞 ConcurrentModificationException
            List<LivingEntity> victims = new ArrayList<>();
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof LivingEntity living) || living.isRemoved()
                        || living instanceof Player || isUntouchable(living)) {
                    continue;
                }
                Ban ban = BANNED_TYPES.get(living.getClass().getName());
                if (ban != null && now <= ban.untilTick) {
                    victims.add(living);
                }
            }
            for (LivingEntity victim : victims) {
                victim.setHealth(0.0F);
                victim.discard();
                if (!deadOrGone(victim)) {
                    forceRemove(victim);
                }
            }
        }
    }

    /**
     * 每 5 刻扫一遍：硬杀过的目标只要又出现在世界里，就再摘一次，
     * 直到它 60 秒没再露头（或者摆明车马地无限复活，作罢）。
     */
    private static void tickPurged(net.minecraft.server.MinecraftServer server) {
        if (server.getTickCount() % 5 != 0) {
            return;
        }
        tickBans(server);
        if (PURGED.isEmpty()) {
            return;
        }
        // 一次遍历建索引：名册里可能有上百条，逐个 findLiving 会把整个实体表扫上百遍
        Map<UUID, LivingEntity> alive = new HashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof LivingEntity living && !living.isRemoved() && living.isAlive()) {
                    alive.put(living.getUUID(), living);
                }
            }
        }
        Iterator<Map.Entry<UUID, Purge>> iterator = PURGED.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Purge> entry = iterator.next();
            Purge purge = entry.getValue();
            LivingEntity target = alive.get(entry.getKey());
            if (target != null) {
                if (isUntouchable(target)) {
                    iterator.remove();
                    continue;
                }
                target.setHealth(0.0F);
                target.discard();
                // 有些实体把 setHealth / discard 都重写成空方法（黑塔就是），必须走真删除
                if (!deadOrGone(target)) {
                    forceRemove(target);
                }
                if (target instanceof Teto && purge.logged++ < 3) {
                    HatMod.LOGGER.warn("[hatmod] 清算名册摘掉了一只 Teto（第 {} 次）—— "
                            + "它进过死亡事件，所以会被反复摘", purge.logged);
                }
                // 又活过来了：记一笔 —— 同一个类短时间反复回来就升格成类型封禁
                noteForceRemoval(target);
                purge.left = PURGE_TICKS;
                if (++purge.tries > PURGE_MAX_TRIES) {
                    HatMod.LOGGER.warn("[hatmod] {} 反复复活，破防硬杀放弃（已摘 {} 次）",
                            target.getType(), purge.tries);
                    iterator.remove();
                }
                continue;
            }
            if (--purge.left <= 0) {
                iterator.remove();
            }
        }
    }

    /**
     * 封禁期内的类型**根本不让它进场**。
     *
     * <p>比每 5 刻扫一遍干净得多：实体连一个 tick 都跑不到，那些"进场即复活"的花招
     * （pig2 那种 tick 里自己再 register 的）也就没机会表演。
     */
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (BANNED_TYPES.isEmpty() || !(event.getEntity() instanceof LivingEntity living)) {
            return;
        }
        if (living instanceof Player || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Ban ban = BANNED_TYPES.get(living.getClass().getName());
        if (ban == null || level.getGameTime() > ban.untilTick) {
            return;
        }
        event.setCanceled(true);
        if (ban.logged < 3) {
            ban.logged++;
            HatMod.LOGGER.warn("[hatmod] 封禁期内拦下 {} 的进场（第 {} 次）",
                    living.getType(), ban.logged);
        }
    }

    @Nullable
    private static LivingEntity findLiving(net.minecraft.server.MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity.getUUID().equals(id)) {
                    return entity instanceof LivingEntity living ? living : null;
                }
            }
        }
        return null;
    }

    /**
     * 一道光柱的每刻结算：**只打它锁定的那一个目标**，返回这一刻实际打掉的血量（供吸血用）。
     *
     * <p>激光现在是**单体伤害** —— 锥形范围内的其它敌人不再被顺带扫到，一道光只认它锁的那一个
     * （多道光就是各打各的目标，见 {@code beamTargets}）。锁定的目标由 {@code findNearestEnemies}
     * 在射程内选出，必定还在范围里，所以这里不再重复做锥形判定。
     */
    private static float damageOneBeam(ServerLevel level, LivingEntity wearer, HatType type,
                                       DamageSource source, float poweredFlat, float power, LivingEntity target) {
        if (target.isRemoved() || !target.isAlive() || !isEnemy(target, wearer)) {
            return 0.0F;
        }

        return damageBeamTick(level, wearer, type, source, poweredFlat,
                HatSettings.healthDamageRatio(type) * power, true, target);
    }

    /**
     * 光柱对**单个目标的一刻结算** —— 牛仔帽和脑蚀帽（Teto）共用这一份。
     *
     * <p>顺序：先按当前血量扣掉百分比（绕过减伤 / 伤害桶），再用 {@code hurt()} 结算固定伤害；
     * 固定伤害被吃光就压一段最大生命补回来；最后记账给破防阶梯判「打不动」。
     *
     * @param flat        这一刻的固定伤害（已经乘过力量 / 帽子倍率）
     * @param percentRatio 这一刻要扣掉的「当前生命比例」（已经乘过所有倍率；0 = 没有百分比）
     * @param holdTarget  是否顺带把目标控住（脑蚀帽只控被这道光选中的那个）
     */
    static float damageBeamTick(ServerLevel level, LivingEntity wearer, HatType type, DamageSource source,
                                float flat, float percentRatio, boolean holdTarget, LivingEntity target) {
        if (target.isRemoved() || !target.isAlive() || !isEnemy(target, wearer)) {
            return 0.0F;
        }
        // 这一击是我们打的，先记下「谁打的它」。
        // 有些 Boss 的 hurt() 直接 return false（黑塔就是），LivingHurtEvent 根本不会抛，
        // 而 HIT_BY / HIT_THIS_SECOND 只在那个监听里写 —— 少了这一句，这类目标永远没有
        // 尾杀窗口（finishAllowed 拿不到出手者），ZERO_ROUNDS 也永远不涨。
        noteHitByHat(target, wearer);
        float before = hatmodGetHealth(target);
        float ratio = Math.max(0.0F, Math.min(percentRatio, 1.0F));

        // 先按当前血量扣掉百分比那部分（绕过减伤），再用正常 hurt() 结算固定伤害收尾。
        // 顺序不能反：hurt() 有可能直接把目标打死，之后再 setHealth 会很难看。
        if (ratio > 0.0F) {
            applyPercentageRatio(wearer, target, type, ratio);
        }

        // 再结算固定伤害、冻结速度：否则 hurt() 内部施加的击退会残留在速度里。
        // 伤害类型已加入 bypasses_cooldown 标签；这里同时清零无敌帧，双保险。
        // 破防阶梯 ①：拆成几段打，绕开「每击上限」
        pierceHurt(target, source, flat);
        // 破防阶梯 ③：常规伤害被吃光了（减伤 / 上限 / 无敌阶段），压一段最大生命补回来。
        if (target.isAlive() && before - hatmodGetHealth(target) < flat * 0.25F) {
            clampPierce(target, before * ratio);
        }

        // 记账：这一击**应该**打出多少、**实际**掉了多少（真血差值）。
        // 每秒按「实际 / 应该」判定要不要破防 —— 血牛不会因为血厚被误判，因为它两边都吃满。
        float intended = flat + before * ratio;
        float dealt = Math.max(0.0F, before - hatmodGetHealth(target));
        settleForce(target, dealt, intended);

        // 「身轻如燕」：白帽一次照射里用光柱击杀五个
        if (type == HatType.WHITE && before > 0.0F && target.isDeadOrDying()) {
            HatState killState = STATES.get(wearer.getUUID());
            if (killState != null && killState.flash > 0) {
                killState.flashKills++;
                if (killState.flashKills >= LIGHT_STEP_KILLS) {
                    HatAchievements.grant(wearer, HatAchievements.LIGHT_STEP);
                }
            }
        }

        // **这一击算戴帽者本人打的**：原版近战会在攻击方身上记一个 lastHurtMob，
        // 被驯服的狼（OwnerHurtTargetGoal）、女仆、脑蚀小怪都是靠它决定「跟着主人咬谁」。
        // 光柱是直接 hurt()，不会自动记，所以这里补上。
        wearer.setLastHurtMob(target);

        // 被罩住 = 彻底控住，连技能都放不出来
        if (holdTarget) {
            hold(level, target);
        }

        // 这一击实际打掉了多少血（setHealth 与 hurt 都算在内），供吸血用。
        return Math.max(0.0F, before - hatmodGetHealth(target));
    }

    /**
     * 光柱伤害的「力量倍率」：把戴帽者身上的伤害类状态效果折成一个乘数。
     *
     * <p>原版力量（{@code DAMAGE_BOOST}）只加 {@code Attributes.ATTACK_DAMAGE}，
     * 只有近战 {@code Player.attack} 会读；光柱是直接 {@code hurt()} 一个固定值，
     * 默认完全吃不到 —— 这个倍率就是补上这一步。红帽固有「照射期力量 I」，
     * 于是红帽的光柱天然 ×1.35（见 {@link #STRENGTH_BEAM_BONUS_PER_LEVEL}）；虚弱反向扣。
     *
     * <p>只认「和伤害直接相关」的原版效果。想再接别的 buff（比如某模组的易伤），往这里加一行即可。
     */
    static float beamPowerMultiplier(LivingEntity wearer) {
        float power = 1.0F;
        MobEffectInstance strength = wearer.getEffect(MobEffects.DAMAGE_BOOST);
        if (strength != null) {
            power += (strength.getAmplifier() + 1) * STRENGTH_BEAM_BONUS_PER_LEVEL;
        }
        MobEffectInstance weakness = wearer.getEffect(MobEffects.WEAKNESS);
        if (weakness != null) {
            power -= (weakness.getAmplifier() + 1) * WEAKNESS_BEAM_PENALTY_PER_LEVEL;
        }
        return Math.max(MIN_BEAM_POWER, Math.min(MAX_BEAM_POWER, power));
    }

    /**
     * 附魔「吸血」：把光柱这一刻造成的总伤害按比例转成戴帽者的治疗。
     *
     * <p>刻意**不封顶** —— 见 {@link HatEnchants#BLOODTHIRST_LIFESTEAL_PER_LEVEL} 的说明。
     *
     * <p>没掉血（或补不满）时，多的那部分不会浪费：按
     * {@link HatEnchants#BLOODTHIRST_ABSORPTION_EFFICIENCY}（100%，无损耗）转成**绿心**
     * （本模组自己的抗伤池，不是原版吸收）。绿心的来龙去脉见
     * {@link #grantGreenHearts}：无限时长、掉落速度随颗数指数增长、基础 1 秒/颗。
     */
    private static void applyLifesteal(LivingEntity wearer, ItemStack hat, float drained) {
        float ratio = HatEnchants.bloodthirstLifesteal(hat);
        if (ratio <= 0.0F || drained <= 0.0F) {
            return;
        }
        float healed = drained * ratio;
        if (healed <= 0.0F) {
            return;
        }

        // 先补血；补不进去的那部分（满血时就是全部）转成绿心
        float missing = Math.max(0.0F, wearer.getMaxHealth() - wearer.getHealth());
        float toHealth = Math.min(healed, missing);
        if (toHealth > 0.0F) {
            wearer.heal(toHealth);
        }
        // 溢出部分原样转成绿心（无损耗；回血那部分不受影响）
        grantGreenHearts(wearer, (healed - toHealth) * HatEnchants.BLOODTHIRST_ABSORPTION_EFFICIENCY);

        // 一点点绯红粒子，让「在吸血」这件事看得见（红帽的既有配色）
        if (wearer.level() instanceof ServerLevel level && wearer.tickCount % 4 == 0) {
            level.sendParticles(ParticleTypes.CRIMSON_SPORE,
                    wearer.getX(), wearer.getY() + wearer.getBbHeight() * 0.5D, wearer.getZ(),
                    4, 0.3D, 0.4D, 0.3D, 0.01D);
        }
    }

    /**
     * 附加百分比伤害：直接 {@link LivingEntity#setHealth} 扣掉「当前生命 ×
     * {@link HatSettings#healthDamageRatio}」。
     *
     * <p>为什么不用 {@code hurt()}：灾变那类 Boss 在 {@code hurt()} 里挂了「单次受伤上限 +
     * 每秒伤害上限」的伤害桶，常规伤害根本打不动。{@code setHealth} 不经过伤害结算，
     * 那些上限对它无效 —— 这就是「强制百分比伤害」。
     *
     * <p>{@code setHealth} 只改血量、不触发死亡（{@code die()} 只在 {@code hurt()} 和死亡事件里调）。
     * 所以这里**没有地板、也没有每轮预算**：能扣多少扣多少，扣到 0 之后由
     * {@link #giveDeathEvent} 补一个正经的死亡事件（掉落、死亡消息、成就都靠它）。
     */
    /**
     * 按**真实血量**扣掉 {@code amount}，并写回它真正托管的那一份。
     *
     * <p>脑蚀帽（Teto）的光柱用它：以前那边是 {@code setHealth(getHealth() - damage)}，
     * 对 Witheria 这种把真血放进世界变量的 Boss 只是改了镜像，它自己的
     * {@code DamageCapProcedure} 每刻再把镜像刷回去 —— 所以怎么打都不掉血。
     * 这里和牛仔帽的百分比走同一条路：读真血、写真血通道。
     */
    static void drainTrueHealth(LivingEntity target, float amount, DamageSource source) {
        float health = hatmodGetHealth(target);
        if (health <= 0.0F || amount <= 0.0F || target.isRemoved()) {
            return;
        }
        float want = Math.max(0.0F, health - amount);
        HealthChannel channel = channelOf(target);
        boolean custom = channel != null && channel != VANILLA;
        if (custom) {
            // 它的真血在别处（世界变量 / 健康控制器 / 自有字段）：直接写那份，别指望 setHealth
            channel.write(target, want);
        }
        target.setHealth(want);
        if (!custom && hatmodGetHealth(target) >= health - 0.01F) {
            // 原版血量被减伤 / 伤害上限 / 无敌阶段吃掉了 —— 走一次正常伤害管线
            target.invulnerableTime = 0;
            target.hurt(source, amount);
        }
        if (!target.isRemoved() && hatmodGetHealth(target) <= 0.0F) {
            giveDeathEvent(target, source);
        }
    }

    private static void applyPercentageDamage(LivingEntity wearer, LivingEntity target, HatType type, float power) {
        applyPercentageRatio(wearer, target, type, HatSettings.healthDamageRatio(type) * power);
    }

    /** 百分比扣血的实现：比例由调用方给（牛仔帽按配置算，脑蚀帽按连击/涌动算）。 */
    private static void applyPercentageRatio(LivingEntity wearer, LivingEntity target, HatType type, float ratioIn) {
        if (target.isDeadOrDying() || !(wearer.level() instanceof ServerLevel level)) {
            return;
        }
        ensureFightable(target);
        if (target instanceof Player && !HatSettings.affectPlayers()) {
            return;
        }
        // 破防下这里读的是它**真正的**血量（hatmodGetHealth）；没开破防就是原版 getHealth()
        float health = hatmodGetHealth(target);
        if (health <= 0.0F) {
            return;
        }
        // 倍率再高也只夹在 (0, health] 之间，不会把血扣成负数
        float ratio = Math.min(ratioIn, 1.0F);
        float damage = health * ratio;
        if (damage <= 0.0F) {
            return;
        }
        DamageSource source = HatSettings.damageSource(level, wearer, type);
        float want = Math.max(0.0F, health - damage);
        HealthChannel channel = channelOf(target);
        boolean custom = channel != null && channel != VANILLA;
        if (custom) {
            // 它的真血在别处（世界变量 / 健康控制器 / 自有字段）：直接写那份，别指望 setHealth
            channel.write(target, want);
        }
        target.setHealth(want);
        if (!custom && hatmodGetHealth(target) >= health - 0.01F) {
            // 原版血量被减伤 / 伤害上限 / 无敌阶段吃掉了 —— 走一次正常伤害管线
            target.invulnerableTime = 0;
            target.hurt(source, damage);
        }
        // 打到 0：setHealth 不触发死亡，补一个正经的死亡事件
        if (!target.isRemoved() && hatmodGetHealth(target) <= 0.0F) {
            giveDeathEvent(target, source);
        }
    }

    /**
     * 服务端启动时报一次每顶帽子的伤害类型到底有没有就绪 —— 看日志就能判断是不是数据包的问题，
     * 不用再靠崩服来发现。
     */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        HatSettings.reportAvailability(event.getServer().registryAccess());
    }

    /** 玩家一登录就把当前参数发过去，调参界面才有当前值可显示；绿心也顺手同步一份。 */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            HatNetwork.sendSettings(player, HatNetwork.FEEDBACK_NONE);
            HatNetwork.sendGreenHearts(player, greenHearts(player));
        }
    }

    /**
     * 某个玩家刚开始追踪一个实体：如果那是个戴着「全」的生物，补发一条当前路线给他。
     *
     * <p>路线包平时只在**循环开始**广播，所以循环中途走进视野的玩家会漏掉那一刻的包，
     * 帽子的颜色会一直停在兜底的「全」三色贴图上，直到下一轮循环才纠正。
     * 这里在开始追踪的瞬间补一条，颜色立刻对上当前路线。
     */
    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (!(event.getTarget() instanceof LivingEntity living)) {
            return;
        }
        if (HatItems.typeOf(hatStack(living)) != HatType.ALL) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        HatState state = STATES.get(living.getUUID());
        HatType route = state != null && state.route != null ? state.route : HatType.ALL;
        HatNetwork.sendRouteTo(player, living, route);
    }
}
