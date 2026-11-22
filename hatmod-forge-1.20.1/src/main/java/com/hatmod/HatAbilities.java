package com.hatmod;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
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
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
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
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

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
import java.util.concurrent.ConcurrentHashMap;

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
@Mod.EventBusSubscriber(modid = HatMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
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
    private static final Map<UUID, Entity> GRAVITY_HELD = new ConcurrentHashMap<>();

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
    private static final UUID ENCHANT_ARMOR_ID = UUID.fromString("6f2d1e64-9c3a-4a4f-8b1e-2a7c5d9e0b31");
    /** 附魔护甲修饰符的名字（1.20.1 的 AttributeModifier 需要它；1.21 已去掉）。 */
    private static final String ENCHANT_ARMOR_NAME = "hatmod_enchant_armor";
    /**
     * 帽子上每多一个附魔，额外再加多少点**盔甲韧性**。
     *
     * <p>韧性和护甲值是两种抗性：护甲值挡普通伤害，韧性专门压「穿甲 / 破甲」那部分
     * （高单发伤害、法术穿透、部分 Boss 的真伤都从这里走）。只堆护甲的话，穿甲一上来
     * 该疼还是疼。每一条 +4：满附魔（9 条）就是 +36，加上帽子自带的 40 一共 **76** ——
     * 一整套下界合金也才 12。
     */
    private static final double TOUGHNESS_PER_ENCHANT = 4.0D;
    /** 附魔韧性加成用的固定修饰符 id / 名字（和护甲那条分开，互不覆盖）。 */
    private static final UUID ENCHANT_TOUGHNESS_ID = UUID.fromString("d81a6f3c-2b57-4f0e-a9c4-7e5b10d2c836");
    private static final String ENCHANT_TOUGHNESS_NAME = "hatmod_enchant_toughness";
    /** 饰品栏里那顶帽子的护甲（Curios 不结算 ArmorItem 的属性，这里自己补一条）。 */
    private static final UUID CURIO_ARMOR_ID = UUID.fromString("b3f0c2a8-57d1-4e6b-9a44-1c8d2f6e7a90");
    private static final String CURIO_ARMOR_NAME = "hatmod_curio_hat_armor";
    /** 饰品栏那顶帽子按附魔给的韧性（和它的护甲加成同一套条件）。 */
    private static final UUID CURIO_TOUGHNESS_ID = UUID.fromString("4c7e9a15-83f2-4d6b-b07a-5e1c9f3d2408");
    private static final String CURIO_TOUGHNESS_NAME = "hatmod_curio_hat_toughness";
    /** 白帽永久跳跃的最高等级：放大 1 = 跳跃 II，「疾行」再高也不往上加。 */
    private static final int MAX_PERMANENT_JUMP_AMPLIFIER = 1;

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

    private static final Map<UUID, HatState> STATES = new ConcurrentHashMap<>();

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
     * <p>头顶那顶优先：头上已经戴着一顶帽子时，生效的就是头上那顶 —— 饰品栏里再放一顶
     * 只贡献它自己的护甲值（Curios 结算那条属性），不会顶掉头上的效果。
     *
     * <p>两个地方都按原样返回（空栈表示没戴），调用方照旧用 {@link HatItems#typeOf} 判断。
     * 饰品栏那条只在装了 Curios 时才会走到；没装时 {@link CuriosCompat} 这个类
     * 根本不会被加载，所以 Curios 不在也不会报 {@code NoClassDefFoundError}。
     */
    public static ItemStack hatStack(LivingEntity entity) {
        if (entity == null) {
            return ItemStack.EMPTY;
        }
        ItemStack head;
        try {
            head = entity.getItemBySlot(EquipmentSlot.HEAD);
        } catch (Throwable ignored) {
            // 玩家对象还在 super() 里构造：inventory / 装备槽都还没建出来，
            // 这时候 Player#getItemBySlot 会 NPE。登录阶段这一下会让原版
            // 把 placeNewPlayer 的异常显示成 multiplayer.disconnect.invalid_player_data。
            return ItemStack.EMPTY;
        }
        if (HatItems.isHat(head)) {
            return head;
        }
        try {
            return curiosLoaded() ? CuriosCompat.findHat(entity) : ItemStack.EMPTY;
        } catch (Throwable ignored) {
            return ItemStack.EMPTY;
        }
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

    /**
     * 这只生物现在是不是正被我们按着（光柱 / 声波 / 定身），或者还在等 AI 被还回去。
     *
     * <p>给 {@code /hatmod unfreeze} 用：还压着的当然不能放。
     */
    static boolean isUnderControl(LivingEntity living) {
        UUID id = living.getUUID();
        return HELD.containsKey(id) || AI_SUPPRESSED.containsKey(id);
    }

    // ------------------------------------------------------------------
    // 光柱强控：直接关掉 AI，让它连技能都放不出来
    // ------------------------------------------------------------------

    /** 被光柱控住的生物 -> 强控记录；用于到点自动解除。 */
    private static final Map<UUID, Hold> HELD = new ConcurrentHashMap<>();

    /** 一次强控。 */
    private static final class Hold {
        /**
         * 到期刻：{@code now > expiresAt} 就还它自由。
         *
         * <p>光柱每刻重挂一次，到期刻只往后放 2 刻；「脑蚀」的声波是一次性结算，
         * 直接挂 3 秒 —— 两者共用这套到点释放，不会留下永久 noAI 的怪。
         */
        private final long expiresAt;

        private Hold(long expiresAt) {
            this.expiresAt = expiresAt;
        }
    }

    /**
     * **我们**关掉 AI 的生物 -> 该还 AI 的刻。
     *
     * <p>存 UUID 而不是 {@code Mob} 引用：区块卸载 / 换维度之后实体对象会换一个新的，
     * 旧那份已经把 {@code NoAI:1} 写进 NBT 了 —— 只有等它重新加载出来、按 UUID 找到新的那个，
     * 才还得回去。以前直接存引用、找不到就跳过，于是落盘的那份 NBT 永远是 {@code NoAI:1}：
     * 地面上的怪站着不动，漂在空中的怪就直接冻在半空，看起来像「被光柱照死机了」。
     *
     * <p>原本就没 AI 的生物（贴图怪、别模组冻住的）不登记 —— 那不是我们关的，也不该由我们打开。
     */
    private static final Map<UUID, Long> AI_SUPPRESSED = new ConcurrentHashMap<>();

    /**
     * 被 Teto 按住时「闭嘴」的鬼 -> 解除时刻（服务器刻）。见 {@link #pinInPlace}。
     */
    private static final Map<UUID, Long> MUTED = new ConcurrentHashMap<>();

    /** 实体一直找不到（死了 / 区块一直没重新加载）时，登记最多留这么久再扔掉，别让表无限长。 */
    private static final int AI_SUPPRESSED_KEEP = 6000;

    /**
     * 把被光柱照到的生物彻底控住。
     *
     * <p>只靠缓慢控不住灾变那种 boss —— 它们的技能挂在 AI 上，不看你走不走得动。
     * 所以这里直接 {@link Mob#setNoAi(boolean)}（等价于原版 {@code /data merge {NoAI:1}}）：
     * 关掉之后不选目标、不施法、不移动。为了不误伤本来就没 AI 的生物，
     * 只有「原本有 AI」的才登记（见 {@link #AI_SUPPRESSED}），解除时也只还这一批。
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

        long now = level.getServer().getTickCount();
        if (living instanceof Mob mob) {
            mob.setTarget(null);
            mob.getNavigation().stop();
            suppressAi(mob, now + holdTicks);
        }
        HELD.put(living.getUUID(), new Hold(now + holdTicks));
    }

    /**
     * 关掉一只生物的 AI（等价于原版 {@code /data merge {NoAI:1}}）并登记到 {@link #AI_SUPPRESSED}。
     *
     * <p>只有「原本有 AI」的才登记。已经在登记里的（光柱和定身叠在一起、定身每刻续期）
     * 只把到期刻往后推，谁也别提前撒手 —— 早先两套系统各记各的，先解除的那个会把
     * 还在定身中的怪放出来。
     *
     * <p>光柱（{@code HatAbilities#hold}）、定身（{@code BrainrotCycle#pinLiving}）
     * 都走这一个入口，解除也只有 {@link #releaseStaleHolds} 一处。
     */
    static void suppressAi(Mob mob, long until) {
        UUID id = mob.getUUID();
        if (!mob.isNoAi()) {
            mob.setNoAi(true);
            AI_SUPPRESSED.put(id, until);
            return;
        }
        Long previous = AI_SUPPRESSED.get(id);
        if (previous != null && until > previous) {
            AI_SUPPRESSED.put(id, until);
        }
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

    /**
     * 「把这只按在原地」：临时按住的通用入口（唐嘉琦光束段按怨灵也走这里）——
     * 和光柱强控同一套（缓慢 255 + 虚弱 + NoAI），按 {@code ticks} 刻，可以每刻续；
     * 没人续了就到点自动解冻（见 {@link #releaseStaleHolds}）。
     */
    static void pinInPlace(ServerLevel level, LivingEntity living, int ticks) {
        holdFor(level, living, ticks);
        if (isGhostMob(living)) {
            // 按住时连「嘴」也一起摁住：silent 是同步给客户端的实体标志，
            // 屏蔽它自己发出的环境音 / 受击音。定向广播的哭声绕开这个标志，
            // 由客户端的 GhostSoundBlockMixin 在播放层拦。
            living.setSilent(true);
            MUTED.put(living.getUUID(), (long) level.getServer().getTickCount() + ticks);
        }
    }

    /**
     * 「按住就不放手」的极限时长（刻）。
     *
     * <p>正常存档根本活不到这一刻：效果时长 / 强控登记都放这个数，
     * {@code releaseStaleHolds} / {@code releaseStaleMutes} 永远等不到到期那一刻。
     * 死亡清理（那两处 {@code isDeadOrDying}）照常收走登记，不会留脏表。
     */
    private static final int HOLD_FOREVER = 1 << 29;

    /**
     * 「按过一次就永不恢复」的按住：Teto 对怨灵专用。
     *
     * <p>被她按住的这段时间里，怨灵的杀招已经拆了（NoAI + 闭嘴 + 按在原地）；
     * 她离开之后**也不解冻** —— 一旦被 Teto 按过，这只鬼就永远是一尊无害的雕像
     * （NoAI / Silent 都写进实体 NBT，重启存档之后照旧）。唐嘉琦光束段的按住
     * 不走这里：那边收手时直接尾杀，见 {@code GhostGuard#beamFinisher}。
     */
    static void pinInPlaceForever(ServerLevel level, LivingEntity living) {
        holdFor(level, living, HOLD_FOREVER, HOLD_FOREVER, HOLD_AMPLIFIER);
        if (isGhostMob(living)) {
            living.setSilent(true);
            MUTED.put(living.getUUID(), Long.MAX_VALUE);
        }
    }

    /** 声波的「缓慢」等级：255（压到几乎不动，配合强制 NoAI 就是彻底定住）。 */
    private static final int SONIC_SLOW_AMPLIFIER = 255;

    /**
     * 解除到期的强控并恢复 AI。
     *
     * <p>按「到期刻」判定（光柱每刻续 2 刻，声波一次挂 40 秒），而不是在光柱结束时逐个通知：
     * 施法者死亡、摘帽、掉线、走远等情况都能自动收尾，不会留下一只永久 noAI 的怪。
     *
     * <p>释放时**按 UUID 重新找实体**（跨维度、跨区块重载都找得到），而不是用登记时那个
     * 可能已经失效的对象 —— 见 {@link #AI_SUPPRESSED}。
     */
    private static void releaseStaleHolds(MinecraftServer server, int now) {
        // HELD 只负责「被按着的时候不能出手」这条判定
        HELD.entrySet().removeIf(entry -> now > entry.getValue().expiresAt);
        releaseStaleMutes(server, now);
        if (AI_SUPPRESSED.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Long>> iterator = AI_SUPPRESSED.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Long> entry = iterator.next();
            long expiresAt = entry.getValue();
            LivingEntity living = findLiving(server, entry.getKey());
            // 死亡的当场还 AI，一刻都不拖：快照类模组（死亡前存档、复活时还原）
            // 会把这份 NoAI 一起存进去，还原出来就是一只永远动不了的生物。
            if (living != null && living.isDeadOrDying()) {
                iterator.remove();
                if (living instanceof Mob mob) {
                    mob.setNoAi(false);
                }
                HELD.remove(entry.getKey());
                continue;
            }
            if (now <= expiresAt) {
                continue;
            }
            if (living == null) {
                // 还没重新加载出来：先留着，等它出现的那一 tick 再还 AI。
                // 留太久（多半是死了 / 再也不回来）就丢掉，免得越攒越多。
                if (now - expiresAt > AI_SUPPRESSED_KEEP) {
                    iterator.remove();
                }
                continue;
            }
            iterator.remove();
            if (living instanceof Mob mob && !mob.isRemoved()) {
                mob.setNoAi(false);
            }
        }
    }

    /**
     * 到期解除鬼的「闭嘴」标志（见 {@link #pinInPlace}）：和 AI 释放同一套到期逻辑 ——
     * 按 UUID 找实体，找到就还，找不到就登记照清（登记在内存里，不随实体持久化）。
     */
    private static void releaseStaleMutes(MinecraftServer server, int now) {
        if (MUTED.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Long>> iterator = MUTED.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Long> entry = iterator.next();
            long expiresAt = entry.getValue();
            LivingEntity living = findLiving(server, entry.getKey());
            if (living != null && living.isDeadOrDying()) {
                iterator.remove();
                continue;                       // 死了的不用还嘴
            }
            if (now <= expiresAt) {
                continue;
            }
            if (living == null) {
                if (now - expiresAt > AI_SUPPRESSED_KEEP) {
                    iterator.remove();
                }
                continue;
            }
            iterator.remove();
            if (!living.isRemoved()) {
                living.setSilent(false);
            }
        }
    }

    // ------------------------------------------------------------------
    // 「每刻维护」的辅助状态
    //
    // 这四种状态都不属于「某一顶帽子正在蓄力 / 照射」这条主线，但都要每刻盯着，
    // 于是统一从这里走一遍（玩家的 PlayerTickEvent、其它生物的 LivingTickEvent 各调一次）：
    //   · 预知静止 —— 到点解除，期间每刻压着（见 SOUL_REAP_STASIS）
    //   · 神隐     —— 置隐身标记、定期把已经锁上来的敌人甩掉（见 VEIL_REFRESH_MARGIN）
    //   · 掌控内讧 —— 给被控住的生物找个对手打（见 DOMINION_VICTIM）
    //   · 绿心     —— 吸血换来的护盾，掉落速度随颗数指数增长、基础 1 秒/颗（见 GREEN_HEARTS）
    // ------------------------------------------------------------------

    /** 每刻维护上面那三种状态。和「自己戴没戴帽子」无关：被定住的敌人自己并不戴帽子。 */
    private static void tickAuras(LivingEntity living, ServerLevel level) {
        if (SOUL_REAP_STASIS.containsKey(living.getUUID())) {
            tickStasis(living, level);
        }
        if (living.hasEffect(HatEffects.VEIL.get())) {
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
    private static final Map<UUID, Long> SOUL_REAP_STASIS = new ConcurrentHashMap<>();

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
    private static final Map<UUID, Long> VEIL_AGGRO_FREE_UNTIL = new ConcurrentHashMap<>();

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

    /** 每刻扫一遍：没人再照着就还它自由；到点的「掌控」也在这里解禁。 */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        com.hatmod.guard.HatGuard.syncSwitch();
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        serverTickWork(event.getServer());
    }

    /** 最近一次跑「服务端每刻工作」的刻号：两个入口共用，保证每刻只跑一次。 */
    private static int lastServerTickWork = Integer.MIN_VALUE;

    /**
     * 服务端的每刻工作：光环释放、破防记账、复活血量、清场。
     *
     * <p><b>为什么还要从 {@code LivingTickEvent} 兜进来</b>：有的模组会把事件总线换成
     * 包装类，{@code post()} 里把 {@code ServerTickEvent}（以及除客户端外**所有**
     * {@code TickEvent}）直接吞掉、不往下传。那样一来这里就再也收不到服务端刻，
     * 回血、清场全部停摆 —— 表现就是「帽子完全防不住它，也杀不掉它」。
     *
     * <p>{@code LivingEvent.LivingTickEvent} 不是 {@code TickEvent}，包装类会照常转发，
     * 而且每只生物每刻都会发一次，拿来当心跳正合适。按服务端刻号去重，和
     * {@code ServerTickEvent} 同时可用时也只会执行一次。
     */
    private static void serverTickWork(MinecraftServer server) {
        if (server == null) {
            return;
        }
        int now = server.getTickCount();
        if (now == lastServerTickWork) {
            return;
        }
        lastServerTickWork = now;
        // 强制不变量：每刻把所有戴帽玩家校验/修一遍（见 guardPlayer）。
        // 这一层不依赖任何事件 —— 外部模组的 force* 杀法（直接写血 / 摘实体 / 写坐标）
        // 根本不会触发伤害或死亡事件，只有「每刻校一遍、修回去」挡得住。
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            updateReach(player);        // 牛仔帽 +2 交互距离（和破防模式无关）
            noteHatSwap(player);        // 换了另一顶牛仔帽 -> 旁边的 Teto 说一句
            guardPlayer(player);
        }
        releaseStaleHolds(server, now);
        releaseStaleDominion(server, now);
        // 力场关掉重力的弹射物要是已经没了（打中/超时），就从登记里摘掉，免得越攒越多
        GRAVITY_HELD.values().removeIf(Entity::isRemoved);
        // 破防判定：每秒一次，哪顶帽子打的都算 —— 和帽子种类、循环节奏全都无关
        if (now % 20 == 0) {
            evaluateForceCycle(server);
        }
        // 破防接管：放在 tick 末尾，把这一 tick 里对方回的血 / 写的满血 / 同步的镜像覆盖掉
        tickSealedHealth(server);
        // 硬杀过的目标要是被自己的复活逻辑塞回来了，接着摘
        tickPurged(server);
        // 每 5 秒清一遍「0 血活尸」：破防直接写血留下的、对方又拒收死亡事件的
        if (now % 100 == 0) {
            sweepDeadWeight(server);
        }
        // 破防模式下被外人删掉的虚影 / 铁托，拉回来（每刻都看：外部抹除随时可能发生）
        reviveSummons(server);
        // 破防是中途打开的也行：打开后的第一刻补一次模组扫描（关着的时候什么也不做）
        ModTamperScan.requestScanIfEnabled();
    }

    // ------------------------------------------------------------------
    // 状态机
    // ------------------------------------------------------------------

    /**
     * 玩家走 PlayerTickEvent、其他生物走 LivingTickEvent，各管一半。
     *
     * <p>理论上 LivingTickEvent 也能覆盖玩家（Player.tick 会调 LivingEntity.tick），
     * 但两个加载器在这一点上的行为不完全一致，拆开写可以保证每个生物每刻**只**被处理一次，
     * 也不会漏掉玩家。
     */
    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!(event.player instanceof ServerPlayer player)) {
            return;
        }
        playerTickWork(player);
    }

    /**
     * 玩家每刻工作的去重：{@code PlayerTickEvent} / {@code LivingTickEvent} 两个入口共用。
     *
     * <p>键用**世界刻号**而不是实体的 {@code tickCount}：{@code LivingTickEvent} 是在
     * {@code LivingEntity.tick()} 的最开头发的（这时 {@code tickCount} 还没自增），
     * 拿它比会让同一刻跑两遍状态机。世界刻号在整刻内是常量，两条入口拿到的完全一样。
     */
    private static final Map<UUID, Long> LAST_PLAYER_TICK = new ConcurrentHashMap<>();

    /**
     * 玩家每刻的状态机 + 破防抢救。
     *
     * <p>和 {@link #serverTickWork} 同一个原因：{@code PlayerTickEvent} 有可能被别的事件
     * 总线吞掉，所以 {@code LivingTickEvent} 也调一份。两个入口按世界刻号去重，
     * 一刻只会跑一次 —— 状态机里的计时/蓄力不会因为跑两遍而加速。
     */
    private static void playerTickWork(ServerPlayer player) {
        long age = player.level().getGameTime();
        Long last = LAST_PLAYER_TICK.get(player.getUUID());
        if (last != null && last == age) {
            return;
        }
        LAST_PLAYER_TICK.put(player.getUUID(), age);
        if (LAST_PLAYER_TICK.size() > 64) {
            LAST_PLAYER_TICK.clear();
        }
        // 破防模式：先把「被外来硬杀写坏的状态」恢复掉，再进状态机 —— 否则 tick() 一看到
        // isDeadOrDying() 就会把整轮帽子状态、音乐、拍点全部清掉，人救回来了也没节拍了。
        // 正常模式一律不碰，保持原来的行为。
        boolean guarded = HatSettings.forceBreak() && !hatStack(player).isEmpty();
        if (guarded) {
            guardPlayer(player);
        }
        tickAuras(player, player.serverLevel());
        tick(player, player.serverLevel());
        if (guarded) {
            lockPlayerHealth(player);
        }
    }
    /** 玩家身上最后的血量：破防模式下被绕过伤害系统写低时，用这个值抬回去。 */
    private static final Map<UUID, Float> PLAYER_HEALTH = new ConcurrentHashMap<>();

    /**
     * 破防模式下给玩家的「Teto 级防御」第二层：血量锁。
     *
     * <p>第一层（伤害事件取消）挡的是正常伤害；这一层挡的是绕过伤害系统直接
     * {@code setHealth(0)} 的那类杀法。
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
     * 破防的「强制不变量」：每个服务端刻把所有戴帽玩家校验一遍、修回去。
     *
     * <p>外部模组的 {@code force*} 系列（{@code forceHurt} / {@code forceSetHealth} /
     * {@code forceRemove} / {@code forceSetPos}）全都不走伤害事件、不走死亡事件、
     * 也不走 {@code setRemoved} —— 事件监听在这种情况下一条都不会触发。唯一挡得住的
     * 是「每刻校一遍」：一个活着的戴帽玩家应该有的样子在这里写死，任何一条不满足就恢复。
     * 对方想杀就得每刻重新来一遍，永远差一个刻。
     *
     * <p>正常模式不调用（{@link HatSettings#forceBreak()} 挡着），行为一点不变。
     */
    static void guardPlayer(ServerPlayer player) {
        if (!HatSettings.forceBreak()) {
            return;
        }
        // 先扣住帽子：有些模组不跟你硬碰硬，而是先把装备清空 / 从头盔槽挪走 ——
        // 帽子一没了，下面所有保护都会因为「没戴帽子」自动关掉。
        ensureHat(player);
        // 再管游戏模式：被强杀流程切到旁观/冒险时改回去（自己 /gamemode 切的不拦）。
        guardGameMode(player);
        if (player.isSpectator() || hatStack(player).isEmpty()) {
            return;
        }
        rescuePlayer(player);
        lockPlayerHealth(player);
        watchClassIdentity(player);
        ensurePlayerPresent(player);
    }

    // ------------------------------------------------------------------
    // 牛仔帽：交互距离 +2
    // ------------------------------------------------------------------

    /** 加在 {@code forge:block_reach} / {@code forge:entity_reach} 上的修饰符 id（靠它增删）。 */
    private static final java.util.UUID REACH_MODIFIER_ID =
            java.util.UUID.fromString("6f1f0e0e-3f5a-4f0e-9a1e-2c9c7f1a5b10");
    /** 牛仔帽加多少格。 */
    private static final double REACH_BONUS = 2.0D;

    /** 这顶帽子算不算「牛仔帽」（黑 / 白 / 红 / 全；脑蚀帽不算）。 */
    static boolean isCowboyHat(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return stack.is(HatItems.BLACK_HAT.get()) || stack.is(HatItems.WHITE_HAT.get())
                || stack.is(HatItems.RED_HAT.get()) || stack.is(HatItems.ALL_HAT.get());
    }

    /**
     * 戴着牛仔帽时，方块 / 实体的交互距离各 +2 格。
     *
     * <p>每刻对一遍：只在「该有却没有 / 不该有却还在」时动属性，平时就是两次查表。
     * 用的是 transient 修饰符 —— 玩家退出 / 重生时随实体一起消失，不会写进存档。
     */
    private static void updateReach(ServerPlayer player) {
        boolean bonus = isCowboyHat(hatStack(player));
        applyReach(player, ForgeMod.BLOCK_REACH.get(), bonus);
        applyReach(player, ForgeMod.ENTITY_REACH.get(), bonus);
    }

    private static void applyReach(ServerPlayer player, net.minecraft.world.entity.ai.attributes.Attribute attribute,
                                   boolean bonus) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        boolean present = instance.getModifier(REACH_MODIFIER_ID) != null;
        if (bonus && !present) {
            instance.addTransientModifier(new AttributeModifier(REACH_MODIFIER_ID,
                    "hatmod_cowboy_reach", REACH_BONUS, AttributeModifier.Operation.ADDITION));
        } else if (!bonus && present) {
            instance.removeModifier(REACH_MODIFIER_ID);
        }
    }

    /** 上一次看到的牛仔帽（UUID -> 帽子种类）：用来发现「玩家换了另一顶」。 */
    private static final Map<UUID, HatType> LAST_HAT_TYPE = new ConcurrentHashMap<>();
    /** 已经见过至少一次的玩家：第一次看到不算「换帽子」，不然每次上线她都要说一句。 */
    private static final Set<UUID> HAT_SEEN = new HashSet<>();

    /**
     * 玩家换上 / 换掉牛仔帽时，让旁边属于他的 Teto 评价一句。
     *
     * <p>只认 {@link HatItems#typeOf} 认得出来的四顶（黑 / 白 / 红 / 全）—— 脑蚀帽不算，
     * 那是她自己戴的那顶。摘下帽子也有台词，走的是别处（{@code hatoff}）。
     */
    private static void noteHatSwap(ServerPlayer player) {
        HatType now = HatItems.typeOf(hatStack(player));
        UUID id = player.getUUID();
        HatType before = LAST_HAT_TYPE.put(id, now);
        boolean first = HAT_SEEN.add(id);
        if (first || now == null || before == now) {
            return;
        }
        if (player.level() instanceof ServerLevel level) {
            Teto.commentOnHat(player, level, now);
        }
    }

    /**
     * 受保护玩家的「正常运行时类」。
     *
     * <p>比「直接写血 / 走死亡事件」更狠的一档是 **JVM 级篡改**：用 {@code Unsafe}
     * 去改对象头里的类指针（{@code putIntVolatile(o, 8L, klassPtr)}），让一个实体在运行时
     * 变成**另一个类**。这一下之后所有 {@code instanceof} 判定、事件取消、mixin 挂钩都可能失效 ——
     * 我们既拦不住也修不回来（没有对应的公开 API）。
     *
     * <p>但可以**当场发现**：每刻比一次类，变了就往日志里甩一条 ERROR，并立刻重新上保护
     * （帽子、血量、位置）。这样至少能确定「是哪个整合包/哪个模组在干这件事」。
     */
    private static final Map<UUID, Class<?>> PLAYER_CLASS = new ConcurrentHashMap<>();

    private static void watchClassIdentity(ServerPlayer player) {
        Class<?> expected = PLAYER_CLASS.putIfAbsent(player.getUUID(), player.getClass());
        if (expected == null || player.getClass() == expected) {
            return;
        }
        HatMod.LOGGER.error("[hatmod] 检测到 {} 的运行时类被篡改：{} -> {}。"
                        + "有模组在用 Unsafe / 反射改对象头，本模组的破防保护对它无效，"
                        + "请查整合包里的可疑模组。",
                player.getGameProfile().getName(), expected.getName(), player.getClass().getName());
    }

    /** 玩家上一次「正常戴着」的帽子（含 NBT 的整份拷贝）；被清掉 / 挪走时照这份装回去。 */
    private static final Map<UUID, ItemStack> HAT_SNAPSHOT = new ConcurrentHashMap<>();

    /**
     * 这个玩家现在是不是处在「破防 + 戴帽」的保护里。
     *
     * <p>除了看当前头盔 / 饰品槽，还看 {@link #HAT_SNAPSHOT}：有些杀法会先把装备清掉再动手，
     * 同一刻里帽子已经不在头上了 —— 只看槽位就会被「先清装备」绕过。
     */
    public static boolean isGuardedPlayer(Player player) {
        if (player == null || !HatSettings.forceBreak()) {
            return false;
        }
        // 构造到一半（placeNewPlayer 里 super() 还没返回）时 inventory 还是 null：
        // 这一下读装备槽必 NPE，而原版会把异常显示成「玩家数据无效」。
        if (player.getInventory() == null) {
            return false;
        }
        try {
            if (!hatStack(player).isEmpty()) {
                return true;
            }
            ItemStack snapshot = HAT_SNAPSHOT.get(player.getUUID());
            return snapshot != null && !snapshot.isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }






    /**
     * 调用栈里第一个「既不是我们也不是原版 / Forge」的类（日志用，标识是谁在动守护对象）。
     */
    public static String foreignCaller() {
        StackTraceElement[] stack = new Throwable().getStackTrace();
        for (int i = 2; i < Math.min(stack.length, 20); i++) {
            String cls = stack[i].getClassName();
            if (cls.startsWith("com.hatmod.") || cls.startsWith("net.minecraft.")
                    || cls.startsWith("net.minecraftforge.") || cls.startsWith("net.neoforged.")
                    || cls.startsWith("com.mojang.") || cls.startsWith("java.") || cls.startsWith("jdk.")
                    || cls.startsWith("sun.") || cls.startsWith("cpw.mods.")
                    || cls.startsWith("org.spongepowered.") || cls.startsWith("com.llamalad7.")) {
                continue;
            }
            return cls;
        }
        return null;
    }


    /**
     * 破防模式下，帽子在戴着的状态下会「钉」在头盔槽里。
     *
     * <p>外部模组常见的一种杀法不是直接打人，而是**先让帽子失效**：清空背包、把头箍槽
     * 里的东西销毁、或者把帽子挪到别的栏位 —— 帽子一不在头上，这个模组的整套保护
     * 就会因为「没戴帽子」而自动关掉，对方再动手就没人拦了。
     *
     * <p>做法：每刻记一份帽子拷贝。头上（或饰品栏）还有帽子就更新拷贝；一旦没了，
     * 就先把背包里同一件找出来扣掉（防复制），再装回头盔槽。换帽子不受影响 ——
     * 只要新戴上的还是帽子，就走「还有帽子」那条分支。
     *
     * <p>代价是破防模式下没法把帽子脱下来（脱了下一 tick 就装回去）。这是有意的：
     * 破防本来就是「帽子无敌」的模式，正常模式完全不执行这段。
     */
    private static void ensureHat(ServerPlayer player) {
        ItemStack worn = hatStack(player);
        if (!worn.isEmpty()) {
            if (HAT_SNAPSHOT.size() > 64) {
                HAT_SNAPSHOT.clear();
            }
            HAT_SNAPSHOT.put(player.getUUID(), worn.copy());
            return;
        }
        ItemStack snapshot = HAT_SNAPSHOT.get(player.getUUID());
        if (snapshot == null || snapshot.isEmpty()) {
            return;                     // 本来就没戴过：不凭空发帽子
        }
        // 背包里如果还留着同一件（被挪到别的栏位了），先扣掉再装，免得复制
        removeOneMatchingHat(player, snapshot);
        player.setItemSlot(EquipmentSlot.HEAD, snapshot.copy());
        HatMod.LOGGER.warn("[hatmod] 破防：帽子被外力清掉/挪走，已装回头盔槽（{}）",
                player.getName().getString());
    }

    /** 从玩家背包（主背包 / 护甲 / 副手）里扣掉一件和 {@code hat} 相同的物品。 */
    private static void removeOneMatchingHat(ServerPlayer player, ItemStack hat) {
        net.minecraft.world.entity.player.Inventory inventory = player.getInventory();
        if (removeOne(inventory.items, hat) || removeOne(inventory.armor, hat)
                || removeOne(inventory.offhand, hat)) {
            inventory.setChanged();
        }
    }

    private static boolean removeOne(java.util.List<ItemStack> slots, ItemStack hat) {
        for (ItemStack slot : slots) {
            if (!slot.isEmpty() && ItemStack.isSameItem(slot, hat)) {
                slot.shrink(1);
                return true;
            }
        }
        return false;
    }

    /** 上一次「不是旁观/冒险」的游戏模式；被强杀流程改掉时按这个改回来。 */
    private static final Map<UUID, GameType> LAST_GAME_MODE = new ConcurrentHashMap<>();

    /**
     * 防止外部模组把人强行切成旁观 / 冒险模式（切了就动不了，等于变相“杀死”）。
     *
     * <p>只改「死亡状态下的切换」：血量 0 / 正在死亡 / 已被摘掉时被切成旁观或冒险，
     * 说明是某个强杀流程干的，按上一次的正常模式改回去；玩家自己 {@code /gamemode}
     * 切旁观时血是满的，不拦。
     */
    private static void guardGameMode(ServerPlayer player) {
        GameType mode = player.gameMode.getGameModeForPlayer();
        GameType last = LAST_GAME_MODE.get(player.getUUID());
        boolean deathish = player.getHealth() <= 0.0F || Float.isNaN(player.getHealth())
                || player.deathTime > 0 || player.isDeadOrDying() || player.isRemoved();
        if ((mode == GameType.SPECTATOR || mode == GameType.ADVENTURE)
                && deathish && last != null && last != mode) {
            player.setGameMode(last);
            HatMod.LOGGER.warn("[hatmod] 破防：游戏模式被强杀流程改成 {}，已改回 {}（{}）",
                    mode, last, player.getName().getString());
            return;
        }
        if (mode != GameType.SPECTATOR && mode != GameType.ADVENTURE) {
            if (LAST_GAME_MODE.size() > 64) {
                LAST_GAME_MODE.clear();
            }
            LAST_GAME_MODE.put(player.getUUID(), mode);
        }
    }

    /**
     * 玩家被从实体表里摘掉（{@code forceRemove} / 直接写 {@code removalReason}）时塞回去。
     *
     * <p>清 {@code removalReason} 只是让它「看起来没被删」；实体管理器里的 section /
     * 追踪表也得有它，否则服务端 tick 不到、客户端也会收到移除包。这里补最后一刀。
     */
    private static void ensurePlayerPresent(ServerPlayer player) {
        if (!player.isRemoved()) {
            return;
        }
        unsetRemoved(player);
        if (player.level() instanceof ServerLevel level && level.getEntities().get(player.getId()) != player) {
            reinsertIntoEntityManager(level, player);
        }
    }

    /** 玩家最后一次「像还在世界里」的坐标：有些硬杀会把目标写到世界角落。 */
    private static final Map<UUID, double[]> PLAYER_POS = new ConcurrentHashMap<>();
    /**
     * 把玩家从「绕过死亡事件的外来硬杀」里捞回来。
     *
     * <p>有些硬杀不走伤害也不走死亡事件：血写 0、{@code deathTime} 顶到 20、
     * {@code removalReason} 写成 DISCARDED、{@code dead} 置上、坐标写到世界角落，
     * 客户端还会收到一条移除包。表现就是「死亡界面卡住、只剩镜头能转、帽子的拍点全没了」。
     *
     * <p>正常模式不调用这个方法。
     */
    private static void rescuePlayer(Player player) {
        // 坐标：killEntity 会先 forceSetPos 到世界角落，再把这份坐标写进 NBT 读回来
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        if (isSanePosition(x, y, z)) {
            PLAYER_POS.put(player.getUUID(), new double[]{x, y, z});
        } else {
            double[] home = PLAYER_POS.get(player.getUUID());
            if (home == null) {
                net.minecraft.core.BlockPos spawn = player.level().getSharedSpawnPos();
                home = new double[]{spawn.getX() + 0.5D, spawn.getY() + 1.0D, spawn.getZ() + 0.5D};
            }
            // 死亡事件里拿到的是 Player，不一定是 ServerPlayer；传送只有服务端玩家那条路会发包
            if (player instanceof ServerPlayer server) {
                server.teleportTo(home[0], home[1], home[2]);
            } else {
                player.setPos(home[0], home[1], home[2]);
            }
            PLAYER_POS.put(player.getUUID(), home);
        }
        float health = player.getHealth();
        float max = player.getMaxHealth();
        // 只在破防模式调用，所以按「玩家不该死」来判：血被写低、被标移除都恢复。
        boolean broken = Float.isNaN(health) || Float.isInfinite(health)
                || health <= 0.0F || health > max + 0.01F
                || player.deathTime > 0 || player.isRemoved();
        if (!broken) {
            return;
        }
        player.deathTime = 0;
        player.setHealth(max);
        player.setPose(net.minecraft.world.entity.Pose.STANDING);
        player.setAirSupply(player.getMaxAirSupply());
        player.invulnerableTime = 20;
        PLAYER_HEALTH.put(player.getUUID(), max);
        if (player.isRemoved()) {
            unsetRemoved(player);
        }
        try {
            Field reason = fieldOfType(Entity.class, RemovalReason.class);
            if (reason != null && reason.get(player) != null) {
                reason.set(player, null);
            }
        } catch (Throwable ignored) {
            // 字段清不掉也没关系，unsetRemoved 已经试过一次
        }
        // dead 标记：LivingEntity 的 dead 置上之后 isAlive() 会一直是 false
        Field dead = deadField(player.getClass());
        if (dead != null) {
            try {
                if (dead.getBoolean(player)) {
                    dead.setBoolean(player, false);
                }
            } catch (Throwable ignored) {
                // 类型对不上就算了
            }
        }
    }

    @Nullable
    private static Field findField(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 这个坐标像不像「人还在世界里」。
     *
     * <p>几套硬杀都是先把目标搬到世界外：(-999, -999, -999)、(10000, -10000, 10000)、
     * (1e9, 0, 0) 都是常见坐标。正常游戏里 y 不会低于 -512
     * （世界最低 -64），x/z 也不会超过一百万格，所以这几个数足够把它们全兜住。
     */
    private static boolean isSanePosition(double x, double y, double z) {
        return y > -512.0D && y < 1.0E6D
                && Math.abs(x) < 1.0E6D && Math.abs(z) < 1.0E6D;
    }

    /**
     * 破防模式下给玩家的「Teto 级防御」第三层：死亡拦截。
     *
     * <p>前两层都被绕过去（比如 {@code /kill} 走完整死亡流程）时，这里直接把死亡事件取消，
     * 让 {@code die()} 根本不执行。
     */
    /**
     * 强控中的生物死掉了：当场把 AI 还回去，再让它走死亡流程。
     *
     * <p>这是 {@link #releaseStaleHolds} 的即时版 —— 快照类模组常在死亡事件附近取快照，
     * 监听顺序不可控，所以死亡钩子和每刻兜底两条路都留。
     */
    @SubscribeEvent
    public static void releaseHoldsOnDeath(LivingDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (dead instanceof Mob mob && mob.isNoAi() && AI_SUPPRESSED.containsKey(mob.getUUID())) {
            mob.setNoAi(false);
            AI_SUPPRESSED.remove(mob.getUUID());
            HELD.remove(mob.getUUID());
        }
    }

    /**
     * 守护名单里的召唤物（Teto / HatSummon / 唐嘉琦系列）：
     * 清算名册 / 尾杀 / 硬杀 / 0 血清扫全部绕开 —— 它们是「自己人」，
     * 不然破防的保护和破防的杀器会互相打架（1.5.203 的 5 秒死亡循环就是这么来的）。
     */
    static boolean isProtectedSummon(LivingEntity dead) {
        return dead instanceof Teto || dead instanceof HatSummon
                || dead instanceof TangJiaqi || dead instanceof TangJiaqiWhite;
    }

    /** winefox delight（maid_dismemberment）的怨灵：夜里飘过来跳鬼图的那只。 */
    private static final String WFD_GHOST_ID = "maid_dismemberment:kitsune_ghost";

    /** winefox delight 的「凝视者」：同一场怨灵夜里的另一只鬼（远远盯着看）。 */
    private static final String WFD_WATCHER_ID = "maid_dismemberment:kitsune_watcher";

    /**
     * 是不是 winefox delight 的鬼（怨灵 / 凝视者）—— 按注册 id 认，不依赖那个模组的类。
     * Teto 遇见它们时：配乐卡掉、鬼图拦下、实体按在原地；唐嘉琦在场时更直接：
     * 先于鬼图整个移除。见 {@link GhostGuard} 与客户端 {@code GhostWarden}。
     */
    public static boolean isGhostMob(Entity entity) {
        if (!(entity instanceof LivingEntity living)) {
            return false;
        }
        net.minecraft.resources.ResourceLocation id =
                net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(living.getType());
        if (id == null) {
            return false;
        }
        String text = id.toString();
        return WFD_GHOST_ID.equals(text) || WFD_WATCHER_ID.equals(text);
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!HatSettings.forceBreak()) {
            return;
        }
        // 破防模式：死了就别想回来。记进「已杀」名册，被它的主人/召唤者拉起来就跟进摘掉
        // （见 tickPurged）。这条对召唤物尤其重要 —— 它们往往死了会被 Boss 当场复活。
        LivingEntity dead = event.getEntity();
        if (dead instanceof Teto) {
            HatMod.LOGGER.warn("[hatmod] Teto 触发了死亡事件（血量 {}/{}，来源 {}）—— "
                            + "自己人不进清算名册，破防下照常保护",
                    dead.getHealth(), dead.getMaxHealth(),
                    event.getSource() == null ? "未知" : event.getSource().getMsgId());
        }
        // 枪弹天使例外：她有自己的一套复活周期（图腾），破防对她「只摘一次」——
        // 既不进清算名册（她被拉回来不追杀），也不按类型封禁（再生成出来的照常活）。
        // 摘一次就够了，之后要不要再找她打，是玩家自己的事。
        if (!(dead instanceof Player) && !(dead instanceof Khragg)
                && !Teto.isBulletAngel(dead) && !isProtectedSummon(dead)) {
            if (PURGED.size() > 256) {
                PURGED.clear();
            }
            PURGED.computeIfAbsent(dead.getUUID(), key -> new Purge());
        }
        if (!(event.getEntity() instanceof Player player) || !isGuardedPlayer(player)) {
            return;
        }
        event.setCanceled(true);
        // 「先清装备再杀」的那一类：这一刻帽子可能已经不在了，先装回去再救人
        if (player instanceof ServerPlayer server) {
            ensureHat(server);
        }
        // 外来硬杀在 die() 之外还会写血 / removalReason / 坐标；死亡事件拦下来了，这些也一起恢复
        rescuePlayer(player);
        player.setHealth(player.getMaxHealth());
        player.invulnerableTime = 20;
        PLAYER_HEALTH.put(player.getUUID(), player.getMaxHealth());
        HatMod.LOGGER.info("[hatmod] 破防模式拦下了 {} 的死亡", player.getName().getString());
    }


    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        if (!(living.level() instanceof ServerLevel level)) {
            return;
        }
        // 玩家走 PlayerTickEvent.END，这条入口只管其它生物
        if (living instanceof ServerPlayer) {
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
        // 帽子上每多一个附魔：护甲 +2、韧性 +1
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
                if (state.musicPending) {
                    // 等客户端的「BGM 真的响起来了」回执再开始倒数：光柱才会落在这一轮
                    // 音乐的拍子上（第二轮、第三轮同样是按各自刚起播的那一首算）
                    if (++state.musicWait > MUSIC_ACK_TIMEOUT) {
                        state.musicPending = false;
                    }
                    break;
                }
                tickingCharge(wearer, level, state);
            } else if (findNearestEnemy(wearer) != null) {
                // 只有存在合法目标才会进入蓄力阶段；循环由此开始
                startCharge(wearer, level, state);
            } else {
                break;
            }
        }
    }

    /** 等客户端回执最多等多久（刻）：关掉声音 / 别的客户端不回包时不能把循环卡死。 */
    private static final int MUSIC_ACK_TIMEOUT = 60;

    /**
     * 客户端回执：这位戴帽者的 BGM 真的响起来了 —— 蓄力从这一刻开始倒数。
     *
     * <p>顺手把「按真实时间补刻」的钟清零：等回执的那几刻没走谱，留着会让放开时
     * 一次补 3 刻，把刚对上的拍子又推早。
     */
    static void noteMusicStarted(net.minecraft.server.level.ServerPlayer listener, int wearerId) {
        if (listener == null) {
            return;
        }
        LivingEntity wearer = null;
        if (listener.level().getEntity(wearerId) instanceof LivingEntity living) {
            wearer = living;
        } else if (listener.server != null) {
            for (ServerLevel any : listener.server.getAllLevels()) {
                if (any.getEntity(wearerId) instanceof LivingEntity living) {
                    wearer = living;
                    break;
                }
            }
        }
        if (wearer == null) {
            return;
        }
        // 刷怪蛋放出来的野生唐嘉琦（白色）：它的循环自己等这一下回执
        if (wearer instanceof TangJiaqiWhite wild) {
            wild.noteMusicStarted();
            // 等回执的这几刻没走谱，把补刻的钟清零，免得一放开就补 3 刻
            STEP_NANOS.remove(wearer.getUUID());
            STEP_CARRY.remove(wearer.getUUID());
            return;
        }
        HatState state = STATES.get(wearer.getUUID());
        if (state == null || !state.musicPending) {
            return;
        }
        state.musicPending = false;
        state.musicWait = 0;
        STEP_NANOS.remove(wearer.getUUID());
        STEP_CARRY.remove(wearer.getUUID());
    }

    /** 一次最多补几刻（和 {@code BrainrotCycle} 同一个数）：再多说明是暂停 / 传送 / 读盘。 */
    private static final int MAX_CATCH_UP = 3;

    /**
     * 这一套「谱」这一服务器刻该走几刻（按真实流逝时间算，1 ~ {@link #MAX_CATCH_UP}）。
     *
     * <p>给野生唐嘉琦的循环用：它的光柱要咬住**音乐**（按真实时间播），所以不能按游戏刻
     * 数 —— 整合包跑不满 20 TPS 时，364 刻的实际耗时会比 18.2 秒长得多。
     */
    static int musicSteps(java.util.UUID id) {
        return stepsFor(id);
    }

    /** 每个戴帽者上一次走谱的真实时刻 / 攒下的零头（刻）。 */
    private static final Map<UUID, Long> STEP_NANOS = new ConcurrentHashMap<>();
    private static final Map<UUID, Double> STEP_CARRY = new ConcurrentHashMap<>();

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
            RandomSource random = level.getRandom();
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

    /** 这个戴帽者现在是否在蓄力期（给 Khragg 的「蓄力走位」用）。 */
    static boolean isCharging(LivingEntity wearer) {
        HatState state = STATES.get(wearer.getUUID());
        return state != null && state.charge > 0;
    }

    /**
     * 现在是不是「只能逃、不能打」的段落。
     *
     * <p>一阶段（牛仔帽 / 「全」帽）：蓄力期 charge &gt; 0，光柱还没亮；
     * 二阶段（脑蚀）：{@link BrainrotCycle} 的时间线里不在 {@link BrainrotCycle#beamWindow}
     * 内的全部段落（开头、57~71 秒、100 秒之后的黑暗期……）。
     *
     * <p>Khragg 的走位和出手限制都走这一条（见 {@code Khragg.RetreatGoal} /
     * {@code Khragg#doHurtTarget}）—— 蓄力逃跑、发射才打，两个阶段一致。
     */
    static boolean mustFlee(LivingEntity wearer) {
        if (isCharging(wearer)) {
            return true;
        }
        return BrainrotCycle.isRunning(wearer) && !BrainrotCycle.beamWindow(wearer);
    }

    /** 这个戴帽者的链路（蓄力 / 照射循环）现在还活着吗：蓄力或照射任一在跑就算。 */
    static boolean chainAlive(LivingEntity wearer) {
        HatState state = STATES.get(wearer.getUUID());
        return state != null && (state.charge > 0 || state.flash > 0);
    }

    /** 这个戴帽者现在是否在照射期（光柱亮着的那段）。给白色唐嘉琦的同步开火用。 */
    static boolean isFlashing(LivingEntity wearer) {
        HatState state = STATES.get(wearer.getUUID());
        return state != null && state.flash > 0;
    }

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
        // 蓄力等客户端回执「这一首真的响起来了」再倒数（见 noteMusicStarted / HatState.musicPending）：
        // 光柱落在音乐哪一拍是设计好的，不能让客户端起播的延迟把第二次、第三次推歪。
        // 只管玩家 — 女仆这类没有自己的客户端，「卡点」也无从谈起，别让它们白等。
        state.musicPending = wearer instanceof ServerPlayer;
        state.musicWait = 0;
        // 路线换了一条：把新颜色告诉客户端（帽子会跟着变那个颜色，见 getArmorTexture）
        HatNetwork.sendRoute(wearer, HatItems.typeOf(hat), state.route);
        // 白帽 / 「全」白路线的链路开场：带上白色唐嘉琦（每人一只，链路收场它自己散）
        if (state.route == HatType.WHITE) {
            HatSummons.spawnTangWhite(level, wearer);
        }
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
            // 蓄力完成：范围减速解除、抗性解除、攻击限制解除（能攻击只看 charge > 0）
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
            // 光柱亮起：她如果就在旁边，会评价一句（只对非脑蚀帽的那套循环生效）
            Teto.commentOnBeam(wearer, level);
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
    // 试图重新锁定任何目标，就把这次锁定取消掉并清空它的目标。这比"只 setTarget(null)
    // 一次"可靠得多 —— 否则它下一刻就会把自己重新锁回去。
    // 同时打上发光标记（原版光灵箭用的就是 GLOWING），并把标记的颜色换成粉色，
    // 见 DOMINION_TEAM。标记时长 = 剥夺索敌时长（控多久，光标就亮多久）。
    // 被控住的生物还会**掉转枪口**：身边还有别的敌人的话，每刻的 tickDominionFight
    // 会把它指过去打 —— 见 DOMINION_VICTIM。
    // ------------------------------------------------------------------

    /** 被「掌控」剥夺索敌的生物 -> 解禁时刻（服务器刻）。 */
    private static final Map<UUID, Long> DOMINION_LOCKED = new ConcurrentHashMap<>();

    /** 被「掌控」标记的生物 -> 它原本所在的队伍名（{@code ""} 表示原本没有队伍）。 */
    private static final Map<UUID, String> DOMINION_ORIGINAL_TEAM = new ConcurrentHashMap<>();

    /**
     * 被「掌控」标记的生物 -> 入队时实际用的计分板名字。
     *
     * <p>不能等到解除时再用 {@code UUID.toString()} 顶替：生物（尤其是别的模组的生物）
     * 有可能重写 {@code getScoreboardName()}，两边对不上时原版 {@code removePlayerFromTeam}
     * 会直接抛 {@link IllegalStateException} 崩服。入队时是什么名字，出队就用什么名字。
     */
    private static final Map<UUID, String> DOMINION_NAME = new ConcurrentHashMap<>();

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
            // 对「施法目标根本不走 Mob#target、改目标等于空转」的 Boss，
            // 这是唯一还吃得上的部分。
            target.addEffect(new MobEffectInstance(HatEffects.VULNERABLE.get(), ticks, 0, false, false, true));
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
     * 它拿到事件之后会把 {@code getNewTarget()} 原样塞进 {@code MemoryAccessor.set()}，
     * 也就是 {@code Optional.of(...)}。如果这时只把新目标置成 null，那就成了
     * {@code Optional.of(null)}，<b>当场 NPE 崩服</b>（崩在 {@code StartAttacking} 里，
     * 表面上跟本模组毫无关系，实际是我们递过去的 null）。
     * 1.21 的 NeoForge 补丁对这一句做了判空，所以只有 1.20.1 会炸。
     *
     * <p>也不能无脑取消：{@code Mob#setTarget(null)}（真正要「清掉目标」的动作）自己也会
     * 走这个事件，无脑取消会把清空一起拦下，目标就永远清不掉。所以分开处理：
     * <ul>
     *   <li>BEHAVIOR_TARGET（Brain 那条路）—— 一律取消。那一侧只可能是「抢新目标」，
     *       清空走的是擦记忆，不经过这里。</li>
     *   <li>MOB_TARGET（{@code Mob#setTarget} 那条路）—— 只在「要锁一个非空目标」时取消，
     *       置空（清目标）放行。</li>
     * </ul>
     */
    @SubscribeEvent
    public static void onLivingChangeTarget(LivingChangeTargetEvent event) {
        // 这个事件每个生物索敌时都要过一遍，而且 Forge 的 EventBus 在「监听器抛异常」
        // 时的报错路径本身会踩到 log4j 的 LinkageError，直接把服务器线程打死 ——
        // 所以这里自己兜住，绝不往外抛。
        try {
            LivingEntity entity = event.getEntity();
            LivingEntity newTarget = event.getNewTarget();

            // ① 挂着「神隐」的目标：谁都别想锁上（原版隐身已经能骗过大部分生物，这里补上剩下的）
            if (newTarget != null && newTarget.hasEffect(HatEffects.VEIL.get())) {
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
        if (brain || event.getNewTarget() != null) {
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
    private static final Map<UUID, UUID> DOMINION_VICTIM = new ConcurrentHashMap<>();
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
        // 有的生物（尤其是 Boss）重写了 setTarget：只要它还看得见挑战者，
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
            if (!(living instanceof Enemy) || Teto.isBulletAngel(living)) {
                continue; // 只挑敌意生物：玩家、宠物、牛羊、枪弹天使都不在名单里
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

        // 光柱本体由客户端用几何体绘制。同步走本模组自己的包，**不用状态效果**：
        // 1.20.1 的原版只把生物的状态效果同步给乘客，只有玩家会额外发给自己，
        // 所以靠效果判断的话，女仆身上的光柱永远传不到客户端（详见 BeamTargets 的注释）。
        // 多道光柱要把所有目标的 id 一起发过去，客户端才能各自画一条。
        syncBeam(wearer, state, true, targets);

        // 红帽：照射期间力量 I（每刻刷新）。原先附魔「炽怒」能把它顶到力量 IV，已废弃。
        // 必须赶在 damageBeam 前面给：光柱伤害会读这份力量折成倍率（见 beamPowerMultiplier），
        // 否则每轮照射的第一刻会漏掉加成。
        if (state.type.has(HatType.Trait.STRENGTH)) {
            wearer.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, STRENGTH_TICKS, 0, false, false, true));
        }

        // 「全」这一轮按路线走：伤害、伤害类型都取路线那顶帽子；三顶原色帽就是它自己。
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
     * <p>附魔「光柱」多出来的道也按这个倍率结算。**多道叠在同一个目标上不再打折**
     * （原来的边际递减已移除）—— 敌人不够时多余的几道全砸在同一个 Boss 上，
     * 每一道都是完整的伤害。
     */
    public static final float BEAM_DAMAGE_SCALE = 0.5F;

    /**
     * 一次光柱结算拆成几次 {@code hurt()}。
     *
     * <p>不少 Boss 在 {@code hurt()} 里做了「每击上限」：
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
    private static final Map<UUID, float[]> CYCLE_DAMAGE = new ConcurrentHashMap<>();
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
     * 这次照射每道光柱各自锁谁：由近到远取敌人，**敌人不够时多余的光柱堆叠到已有目标上**。
     *
     * <p>返回列表的长度恒为 {@code beamCount}（有敌人时），下标 i 就是「第 i 道光柱」：
     * 目标取 {@code enemies[i % n]}，堆叠轮次 {@code i / n}。于是敌人够多时一人一道。
     *
     * <p>堆叠不打折：同一个目标被几道光同时锁定就吃几份满伤（扣血倍率在
     * {@link #damageBeam} 里算）。
     * 一个敌人都没有时返回空列表（客户端仍会沿视线画出 {@link #BASE_BEAM_COUNT} 道，
     * 见 {@link #tickingFlash}）。
     */
    /** 上一次打过的光柱锁定组合（组合变了才打日志，免得每刻刷屏）。 */
    private static final Map<UUID, String> BEAM_LOCK_SIG = new ConcurrentHashMap<>();

    /**
     * 记一条「光柱现在锁着谁」。
     *
     * <p>只在破防模式下打，而且同一种组合只打一次 —— 用来回答「光束为什么打不死它」：
     * 是没锁上、还是锁到了别的实体（有的 Boss 的激光 / 炮塔 / 爆炸云就吃过这个亏）。
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

    // ------------------------------------------------------------------
    // 白色唐嘉琦：两道肩部光柱
    // ------------------------------------------------------------------

    /**
     * 白色唐嘉琦两道光的起点：贴图上**两个红圈**的位置（左右肩胛各一处）。
     *
     * <p>红圈量自面片（高 {@code 2.2} 格）：约在脚底往上 {@code 0.78} 格、中心左右各
     * {@code 0.6} 格处。lane 0 = 左肩、lane 1 = 右肩；别的生物照旧走
     * {@link #beamOrigin(LivingEntity, float)} 的胯部点。
     *
     * <p>左右用朝向的右向量算（yaw = 0 朝 +Z 时右 = (cos, sin)），这样它面朝谁，
     * 两道光就从左右两肩上朝谁张开。
     */
    public static Vec3 beamOrigin(LivingEntity wearer, float partialTick, int lane) {
        if (!(wearer instanceof TangJiaqiWhite)) {
            return beamOrigin(wearer, partialTick);
        }
        Vec3 base = wearer.getPosition(partialTick);
        double yaw = Math.toRadians(wearer.getYRot());
        double side = (lane % 2 == 0) ? -TangJiaqiWhite.SHOULDER_LATERAL : TangJiaqiWhite.SHOULDER_LATERAL;
        return new Vec3(base.x + Math.cos(yaw) * side,
                base.y + wearer.getBbHeight() * TangJiaqiWhite.SHOULDER_HEIGHT_RATIO,
                base.z + Math.sin(yaw) * side);
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
        living.addEffect(new MobEffectInstance(HatEffects.VEIL.get(), ticks, 0, false, false, true));
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
        MobEffectInstance current = living.getEffect(HatEffects.VEIL.get());
        if (current == null) {
            // 刚挂上：从头开始算「保证清仇恨」的 3 秒
            VEIL_AGGRO_FREE_UNTIL.put(living.getUUID(),
                    living.level().getGameTime() + VEIL_AGGRO_FREE_TICKS);
        }
        if (current == null || current.getDuration() < VEIL_REFRESH_MARGIN) {
            living.addEffect(new MobEffectInstance(HatEffects.VEIL.get(), ticks, 0, false, false, true));
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
            applyBonus(wearer.getAttribute(Attributes.ARMOR_TOUGHNESS), CURIO_TOUGHNESS_ID,
                    CURIO_TOUGHNESS_NAME, 0.0D);
            return;
        }
        // 基础护甲 + 这顶自己的附魔。头上没戴帽子时它的附魔已经由 applyEnchantArmor 算过，
        // 这里就不重复加。
        boolean headIsHat = HatItems.isHat(wearer.getItemBySlot(EquipmentSlot.HEAD));
        int curioEnchants = headIsHat ? EnchantmentHelper.getEnchantments(curioHat).size() : 0;
        double wanted = HatItems.HAT_ARMOR + curioEnchants * ARMOR_PER_ENCHANT;
        // 附魔韧性：和上面那份附魔护甲同一套条件（头上也戴着帽子时，饰品栏这顶的附魔才算）
        applyBonus(wearer.getAttribute(Attributes.ARMOR_TOUGHNESS), CURIO_TOUGHNESS_ID,
                CURIO_TOUGHNESS_NAME,
                HatItems.HAT_TOUGHNESS + curioEnchants * TOUGHNESS_PER_ENCHANT);
        if (existing != null && existing.getAmount() == wanted) {
            return;
        }
        if (existing != null) {
            armor.removeModifier(CURIO_ARMOR_ID);
        }
        armor.addTransientModifier(new AttributeModifier(
                CURIO_ARMOR_ID, CURIO_ARMOR_NAME, wanted, AttributeModifier.Operation.ADDITION));
        logArmorBreakdown(wearer, true, wanted);
    }

    /** 上一次打过的护甲构成（同一种构成只打一次）。 */
    private static final Map<UUID, String> ARMOR_BREAKDOWN = new ConcurrentHashMap<>();

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
            enchanted = enchant.getAmount();
        }
        double equipped = total - enchanted - curioBonus;
        String signature = String.format("total=%.1f equipped=%.1f enchant=%.1f curio=%s%.1f",
                total, equipped, enchanted, hasCurioHat ? "+" : "", hasCurioHat ? curioBonus : 0.0D);
        if (signature.equals(ARMOR_BREAKDOWN.get(wearer.getUUID()))) {
            return;
        }
        ARMOR_BREAKDOWN.put(wearer.getUUID(), signature);
        HatMod.LOGGER.info("[hatmod] {} 的护甲：{}（装备 {} / 附魔 {} / 饰品栏 {}）",
                wearer.getName().getString(),
                String.format("%.1f", total), String.format("%.1f", equipped),
                String.format("%.1f", enchanted),
                hasCurioHat ? String.format("%.1f", curioBonus) : "无");
    }

    /** 饰品栏里的帽子被拿走时把那条护甲（和附魔韧性）收掉。 */
    private static void clearCurioArmor(LivingEntity wearer) {
        AttributeInstance armor = wearer.getAttribute(Attributes.ARMOR);
        if (armor != null && armor.getModifier(CURIO_ARMOR_ID) != null) {
            armor.removeModifier(CURIO_ARMOR_ID);
        }
        AttributeInstance toughness = wearer.getAttribute(Attributes.ARMOR_TOUGHNESS);
        if (toughness != null && toughness.getModifier(CURIO_TOUGHNESS_ID) != null) {
            toughness.removeModifier(CURIO_TOUGHNESS_ID);
        }
    }

    /**
     * 帽子上每多敲一个附魔，就给戴帽者多 {@link #ARMOR_PER_ENCHANT}（2）点护甲
     * **和** {@link #TOUGHNESS_PER_ENCHANT}（1）点盔甲韧性。
     *
     * <p>走的是原版 {@code Attributes.ARMOR} / {@code Attributes.ARMOR_TOUGHNESS} 上的
     * 两条**瞬态修饰符**（固定 UUID、互不覆盖）：每刻按当前附魔数算一个目标值，
     * 和已有那条一致就不动 —— 所以附魔没变时不会每刻重加、也就不会把属性同步包刷爆。
     * 附魔数变多/变少（铁砧上加附魔、磨掉）会立刻跟上。
     */
    private static void applyEnchantArmor(LivingEntity wearer, ItemStack hat) {
        int enchants = EnchantmentHelper.getEnchantments(hat).size();
        applyBonus(wearer.getAttribute(Attributes.ARMOR), ENCHANT_ARMOR_ID, ENCHANT_ARMOR_NAME,
                enchants * ARMOR_PER_ENCHANT);
        applyBonus(wearer.getAttribute(Attributes.ARMOR_TOUGHNESS), ENCHANT_TOUGHNESS_ID,
                ENCHANT_TOUGHNESS_NAME, enchants * TOUGHNESS_PER_ENCHANT);
    }

    /**
     * 往一条属性上挂 / 更新 / 摘掉一条瞬态修饰符：值没变就不动，变 0 就摘掉。
     *
     * <p>直接收 {@link AttributeInstance} 而不是属性本身，省得为 {@code Attribute}
     * 再多一个 import（1.20.1 是 {@code Attribute}，1.21 是 {@code Holder<Attribute>}）。
     */
    private static void applyBonus(AttributeInstance instance, UUID id, String name, double wanted) {
        if (instance == null) {
            return;
        }
        AttributeModifier existing = instance.getModifier(id);
        if (wanted <= 0.0D) {
            if (existing != null) {
                instance.removeModifier(id);
            }
            return;
        }
        if (existing != null && existing.getAmount() == wanted) {
            return;
        }
        if (existing != null) {
            instance.removeModifier(id);
        }
        instance.addTransientModifier(new AttributeModifier(
                id, name, wanted, AttributeModifier.Operation.ADDITION));
    }

    /** 摘帽 / 换帽时把附魔的护甲 / 韧性加成一起收掉。 */
    private static void clearEnchantArmor(LivingEntity wearer) {
        AttributeInstance armor = wearer.getAttribute(Attributes.ARMOR);
        if (armor != null && armor.getModifier(ENCHANT_ARMOR_ID) != null) {
            armor.removeModifier(ENCHANT_ARMOR_ID);
        }
        AttributeInstance toughness = wearer.getAttribute(Attributes.ARMOR_TOUGHNESS);
        if (toughness != null && toughness.getModifier(ENCHANT_TOUGHNESS_ID) != null) {
            toughness.removeModifier(ENCHANT_TOUGHNESS_ID);
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
    private static final Map<UUID, Float> GREEN_HEARTS = new ConcurrentHashMap<>();

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
     * <p><b>整击被吃下时不扣护甲耐久</b>：这一层是在 {@code LivingHurtEvent}（原版还没走到
     * {@code hurtArmor}）里把伤害整个取消的，所以护甲一点磨损都没有。
     *
     * <p>只吃下一部分时，把剩下那一截**按比例换算回减伤前**的数值交还给原版流程，
     * 让原版自己做同样的减伤 —— 结果约等于「减伤后的伤害再减去绿心吃掉的部分」。
     * （原版护甲那条在伤害偏大时略有非线性，这里不做迭代求解，误差只落在「没被吃满」的少数几击上。）
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
            result = CombatRules.getDamageAfterAbsorb(result, living.getArmorValue(),
                    (float) living.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
        }
        if (source.is(DamageTypeTags.BYPASSES_EFFECTS)) {
            return result;
        }
        MobEffectInstance resistance = living.getEffect(MobEffects.DAMAGE_RESISTANCE);
        if (resistance != null && !source.is(DamageTypeTags.BYPASSES_RESISTANCE)) {
            int percent = (resistance.getAmplifier() + 1) * 5;
            result = Math.max(result * (25 - percent) / 25.0F, 0.0F);
        }
        if (result > 0.0F && !source.is(DamageTypeTags.BYPASSES_ENCHANTMENTS)) {
            int protection = EnchantmentHelper.getDamageProtection(living.getArmorSlots(), source);
            if (protection > 0) {
                result = CombatRules.getDamageAfterMagicAbsorb(result, (float) protection);
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
    public static void onLivingHurt(LivingHurtEvent event) {
        LivingEntity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }

        // 破防记账：光柱之外打出去的伤害（近战、召唤物……）也要记成「这一秒打到了它」。
        // 只靠光柱记账的话，近战打出来的伤害在账本里一条都没有（见 evaluateForceCycle）。
        noteHitByHat(entity, event.getSource().getEntity());

        // 「谁打过我」记一笔（见 rememberAggressor）：记下来之后它就会进光柱的攻击名单。
        // 放在免伤判定**之前** —— 破防模式下玩家这一击虽然被取消，但「谁打的我」照样要记，
        // 否则那些不走 AI 索敌的 Boss 永远不会进光柱的锁敌名单。
        rememberAggressor(entity, event.getSource(), event.getAmount());

        // 破防模式 + 戴着帽子（牛仔帽 / TETO 帽同一套）：玩家完全免伤。
        // 判据用 isGuardedPlayer：帽子这一刻刚被清掉、上一刻还戴着，也算受保护 ——
        // 不然「先清装备再打」就能在同一刻绕过这里。
        if (entity instanceof Player player && isGuardedPlayer(player)) {
            if (player instanceof ServerPlayer server) {
                ensureHat(server);
            }
            event.setCanceled(true);
            PLAYER_HEALTH.put(player.getUUID(), player.getHealth());
            return;
        }

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
        // 这一击由虚影吃下（单次最多掉 20% 血，还会把伤害反回去 —— 见 HatSummon#hurt）
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
                // 末影系传送特效：粒子用 PORTAL（末影珍珠飞行时拖的就是这串粒子，
                // 末影人传送也是这一套）。音效这边只能用 ENDERMAN_TELEPORT ——
                // 1.20.1 根本没有 entity.player.teleport 这个音效，它是 1.21 才加的，
                // 所以 1.21.1 那份用的是末影珍珠真正播的 PLAYER_TELEPORT。
                // 起点和落点各来一份：起点是"消失"，落点是"出现"；只放起点的话
                // 人已经瞬移走了，距离衰减之后几乎听不见也看不见。
                level.playSound(null, originX, originY, originZ,
                        SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, BLINK_SOUND_VOLUME, 1.0F);
                level.playSound(null, entity.getX(), entity.getY(), entity.getZ(),
                        SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, BLINK_SOUND_VOLUME, 1.0F);
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
    private static final Map<UUID, Map<UUID, Long>> AGGRO = new ConcurrentHashMap<>();

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

    /**
     * 撤销一条「谁打过我」的记录。
     *
     * <p>自己人的误伤被取消掉之后，这一笔就不该留着 —— 否则「召唤物反叛」那条判定
     * 会被史莱姆蹭一下就触发，自己的虫子一碰人就成了敌人。
     */
    static void forgetAggressor(LivingEntity wearer, Entity entity) {
        Map<UUID, Long> memory = AGGRO.get(wearer.getUUID());
        if (memory == null) {
            return;
        }
        memory.remove(entity.getUUID());
        if (memory.isEmpty()) {
            AGGRO.remove(wearer.getUUID());
        }
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
            // 0 血但还没被移除的「活尸」不能再占光柱名额：它也打不动（见 ensureDead）
            if (!(entity instanceof LivingEntity living) || living.isRemoved() || !living.isAlive()
                    || !isEnemy(living, wearer)) {
                continue;
            }
            if (!found.contains(living)) {
                nearby.add(living);
            }
        }
        nearby.sort((a, b) -> Double.compare(wearer.distanceToSqr(a), wearer.distanceToSqr(b)));
        found.addAll(nearby);
        // ③ 敌意生物优先。脑蚀链路跑起来时，isEnemy 的 brainrotLockAll 会把 40 格内
        //    **一切非友方单位**都算成敌人 —— 于是那些特效实体（垂直激光
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

    /** 万法皆通「星之魔女」的实体 id（按注册 id 认，不依赖那个模组的类）。 */
    private static final String STELLAR_WITCH_ID = "touhou_little_maid_spell:stellar_witch";

    /**
     * 「未开战」的星之魔女：帽子一族不索敌她。
     *
     * <p>她是切磋型 Boss —— 平时站在秋千边看星星、可以对话 / 交易 / 用星芒短剑下战书；
     * 「开战」的判据用最通用的「有没有在打仗」：AI 里有目标，或者刚被谁打了一记
     * （{@code getLastHurtByMob} 自带 100 刻的记忆窗），任一成立就算开战。
     * 玩家先动手 / 递战书把她拉进战斗之后，光柱照常锁她。
     */
    private static boolean isPeacefulStellarWitch(Entity entity) {
        if (!(entity instanceof Mob mob)) {
            return false;
        }
        net.minecraft.resources.ResourceLocation id =
                net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType());
        if (id == null || !STELLAR_WITCH_ID.equals(id.toString())) {
            return false;
        }
        return mob.getTarget() == null && mob.getLastHurtByMob() == null;
    }

    public static boolean isEnemy(Entity entity, LivingEntity wearer) {
        if (entity == wearer) {
            return false;
        }
        // 枪弹天使永远不算敌人：三顶牛仔帽 / 「全」这一轮不索敌她，
        // 脑蚀「后室」按 P 强制锁全场的时候也把她排除在外 —— 她的事玩家自己处理。
        if (Teto.isBulletAngel(entity)) {
            return false;
        }
        // 万法皆通的星之魔女：没开战就是「切磋邀约待机」（看星星 / 对话 / 交易），
        // 帽子一族不索敌她 —— 只有真的打起来了（有目标 / 刚被谁打过）才按普通敌人处理。
        // 放在脑蚀强制锁敌前面：P 强制开的一轮也不打没开战的她。
        if (isPeacefulStellarWitch(entity)) {
            return false;
        }
        if (wearer instanceof Khragg khragg && khragg.isAngryAt(entity)) {
            return true;
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
        if (wearerOwner == null) {
            // 帽子戴在**非玩家**头上（女仆、Teto、驯服生物……）：它自己就是这一方的「主人」。
            // 以前这里直接是 null，于是它自己名下的 Teto / 召唤物全都匹配不上，
            // 开链路之后光柱会把自己人也当成敌人打。
            wearerOwner = wearer.getUUID();
        }
        UUID entityOwner = ownerOf(entity);
        if (wearerOwner.equals(entityOwner)) {
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
     * 黑 = 幽匿魂（暗青）、白 = 末地烛（白）、红 = 绯红孢子（暗红）、全 = 末地烛（白，配金色光柱）。
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
     * 才不会被「关了 jar 句柄」的优化模组坑到（见那个类的注释）—— 少一个类少一处要维护的。
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
     * @param route   「全」本轮走的路线；三顶原色帽就是它自己。**帧伤数值**取它的
     *                {@link HatType#routeDamage}（黑 12 / 白 10 / 红 15），
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

        // 每道光柱各打自己锁定的那个目标，**每一道都是满伤**：
        // 敌人够多就一人一道；敌人不够时多出来的道全堆到已有目标上，也不打折。
        // 每道伤害乘 BEAM_DAMAGE_SCALE（基础两道合计 = 原来一道）。
        // 没有目标时不结算（客户端仍会沿视线画出这几道，见 tickingFlash）。
        for (int i = 0; i < targets.size(); i++) {
            float factor = power * BEAM_DAMAGE_SCALE;
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
        // 伤害**记在召唤者头上**：分身自己只是一个生物，某些 Boss
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

    /** 「压最大生命」用的修饰符（Forge 走 UUID + 名字）。 */
    private static final UUID MAX_HEALTH_PIERCE_ID = UUID.fromString("9e2d41c6-0b7a-4f13-9a55-6c1d8e3f2b04");
    private static final String MAX_HEALTH_PIERCE_NAME = "hatmod_max_health_pierce";

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
        // 地板只防「一次压过头」，绝不能把**已经低于地板**的血抬回去。
        // 少了这一句，打到最后会卡在 5% 上：每刻百分比往下扣一点，这里又把它夹回来，
        // 使徒那种「每击上限 + 无敌帧」的 Boss 就永远差最后一口气（看着像只剩一滴血）。
        if (wanted >= target.getHealth()) {
            return;
        }
        double shrink = Math.min(0.0D, wanted - max);
        if (shrink >= 0.0D) {
            return;
        }
        try {
            maxHealth.addTransientModifier(new AttributeModifier(
                    MAX_HEALTH_PIERCE_ID, MAX_HEALTH_PIERCE_NAME, shrink, AttributeModifier.Operation.ADDITION));
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
    private static final Map<UUID, float[]> DEFICIT = new ConcurrentHashMap<>();

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
    private static final Map<UUID, Float> EXEC_BUDGET = new ConcurrentHashMap<>();
    /** 已经提示过「尾杀锁定」的目标，别每刻刷屏。 */
    private static final Set<UUID> EXEC_LOCKED_LOGGED = new HashSet<>();

    /** 这一秒里我们打到过谁（光柱、近战、召唤物都算）。 */
    private static final Map<UUID, LivingEntity> HIT_THIS_SECOND = new ConcurrentHashMap<>();
    /** 上一秒结算时的血量快照，用来判断「打了，但血一点没动」。 */
    private static final Map<UUID, Float> HEALTH_SNAPSHOT = new ConcurrentHashMap<>();
    /**
     * 目标 -> 连着多少秒「打得到、血量一点没动」。
     *
     * <p>判据本来是「按总血量算出还要打几轮」，问题出在**分母**上：有的 Boss 把
     * {@code getHealth()} 和 {@code getMaxHealth()} 都写死成 2^31-1、{@code hurt()}
     * 直接 {@code return false}，按它报的血量算轮数会得到天文数字，这道门永远不开。
     *
     * <p>所以判据改成看**血量快照**：每秒比一次，打得到但数值一动不动，就记一秒。
     * 它报的数是不是真的都骗不过这里 —— 真掉血就会下降，下降就清零。
     */
    private static final Map<UUID, Integer> ZERO_ROUNDS = new ConcurrentHashMap<>();
    /** 目标 -> 最近一轮「理论上打出去多少」。 */
    private static final Map<UUID, Float> ROUND_OUTPUT = new ConcurrentHashMap<>();
    /** 连着这么多秒打得到却一点血没掉，就不再等「打满一条命」的预算，直接尾杀。 */
    private static final int MAX_EXEC_ROUNDS = 5;

    /** 最近一次「谁打的它」：处决窗口要按**出手者的链路阶段**判。 */
    private static final Map<UUID, LivingEntity> HIT_BY = new ConcurrentHashMap<>();
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
        if (!HatSettings.forceBreak() || amount <= 0.0F || target.isRemoved() || !target.isAlive()
                || target instanceof Khragg) {
            return;
        }
        // Khragg 不进血量托管：他有写死的两段死亡流程（第一次死亡满血复活 + 强制开演）。
        // 托管的 SEALED 记录在他复活后仍然生效，会每刻把他的血写回托管值 ——
        // 「满血复活」变成「1 血爬起来」，下一道光柱就真死了。普通光柱伤害照常打他，
        // 只是破防的强制扣血层不碰他（与 finishOff / ensureDead 的放行同一口径）。
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
    //   4. 血被压到地板还判活着，才走终局：先求正常死亡事件，拒收就走硬杀。
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
    private static final Map<UUID, Entity> WATCHED = new ConcurrentHashMap<>();

    /** 被挖走之前最后一次「正常」的位置：救回来的时候原地放回去。 */
    private static final Map<UUID, double[]> LAST_POS = new ConcurrentHashMap<>();

    /** 救不回来的次数：实体表刷新 / 追踪表重建有延迟，给足重试次数，不因为一次失败就放弃。 */
    private static final Map<UUID, Integer> REVIVE_FAILS = new ConcurrentHashMap<>();

    /** 登记一只虚影 / 铁托（只在服务端登记），顺手记住它在世界里的位置。 */
    static void watchSummon(Entity entity) {
        if (!(entity.level() instanceof ServerLevel)) {
            return;
        }
        WATCHED.put(entity.getUUID(), entity);
        // 只记「像还在世界里」的坐标：(1e9, 0, 0)、(-999, -999, -999) 都是几种硬杀的
        // 抹除坐标。把它们当成「最后一次正常位置」，拉回时就再也放不回原处了。
        if (!entity.isRemoved() && isSanePosition(entity.getX(), entity.getY(), entity.getZ())) {
            LAST_POS.put(entity.getUUID(), new double[]{entity.getX(), entity.getY(), entity.getZ()});
        }
    }

    /** 本体自己收掉它的时候，从登记表里摘掉（不然下一 tick 又给拉回来了）。 */
    static void forgetSummon(Entity entity) {
        WATCHED.remove(entity.getUUID());
        LAST_POS.remove(entity.getUUID());
        REVIVE_FAILS.remove(entity.getUUID());
    }

    /**
     * 每刻看一遍登记表：被外人删掉的拉回来、被搬到天边的按回原位；开了「实体表接管」就再塞回实体管理器。
     *
     * <p>只在破防模式下跑；正常模式不打这个补丁。
     *
     * <p>这是很常见的「删了就自己补回来」的路子，只是我们只拉自己人，不碰别人的实体。
     */
    private static void reviveSummons(MinecraftServer server) {
        // 只在破防模式：正常模式该被打死就被打死，链路跟着正常收场。
        if (!HatSettings.forceBreak() || WATCHED.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Entity>> iterator = WATCHED.entrySet().iterator();
        while (iterator.hasNext()) {
            Entity entity = iterator.next().getValue();
            if (!(entity instanceof LivingEntity living)) {
                iterator.remove();
                continue;
            }
            if (!(living.level() instanceof ServerLevel level)) {
                iterator.remove();
                continue;
            }
            // 有的「抹除」是两步：先把实体摘掉，再把坐标写到 (1e9, 0, 0)，还顺手给客户端
            // 发同步包。只把实体塞回表里不够 —— 它在一亿格外，数不到、也打不着。
            // 这一步必须放在「还活着就跳过」之前：有的路径只搬位置、不写 removalReason。
            double[] home = LAST_POS.get(living.getUUID());
            if (home != null && !isSanePosition(living.getX(), living.getY(), living.getZ())) {
                unsetRemoved(living);
                living.setPos(home[0], home[1], home[2]);
                living.setDeltaMovement(Vec3.ZERO);
            }
            // 血量被写成 NaN / -∞：有的模组直接改同步数据，绕过了 Teto 的
            // setHealth 覆盖。抬回满血，否则它「活着」却顶着一个负血量到处走。
            float summonHealth = living.getHealth();
            if (living instanceof Teto && !(summonHealth > 0.0F)) {
                living.setHealth(living.getMaxHealth());
            }
            if (!living.isRemoved() && living.isAlive()) {
                continue;
            }
            // 正常死亡就不管了，只救「被人挖走」的
            if (isTrulyDead(living) && living.getHealth() <= 0.0F) {
                iterator.remove();
                continue;
            }
            unsetRemoved(living);
            boolean present = false;
            try {
                present = level.getEntity(living.getUUID()) != null;
                if (!present) {
                    present = level.addFreshEntity(living);
                }
                if (!present && HatSettings.entityLock() && reinsertIntoEntityManager(level, living)) {
                    HatMod.LOGGER.warn("[hatmod] {} 被从实体表里挖走，已强行塞回", living.getType());
                    present = true;
                }
            } catch (Throwable error) {
                // 实体表被掏空 / 回调被置空时 addFreshEntity 可能抛：别让一个坏实体把整刻带崩，
                // 下一 tick 接着重试。
                if (REVIVE_FAILS.getOrDefault(living.getUUID(), 0) == 0) {
                    HatMod.LOGGER.warn("[hatmod] {} 塞回实体表时出错：{}", living.getType(), error.toString());
                }
                present = false;
            }
            if (present) {
                REVIVE_FAILS.remove(living.getUUID());
                // 有的清场会先把人传到 (10000, -10000, 10000) 再打上隐身标记，
                // 救回来的时候得把它放回原处、把隐身摘掉，否则「人回来了但看不见」
                if (home != null && !isSanePosition(living.getX(), living.getY(), living.getZ())) {
                    living.setPos(home[0], home[1], home[2]);
                }
                living.setInvisible(false);
            } else {
                // 塞回去失败（实体管理器的 section / 追踪表刚被清掉，或这一 tick 的实体表还没刷新）：
                // 记一次失败，下一 tick 继续试 —— 绝对不能因为一次失败就把目标从名册里删掉，
                // 那等于「被外人的剑砍一次就永久丢人」。
                int fails = REVIVE_FAILS.merge(living.getUUID(), 1, Integer::sum);
                if (fails == 1 || fails % 40 == 0) {
                    HatMod.LOGGER.warn("[hatmod] {} 被删掉后第 {} 次尝试塞回实体表失败，继续重试",
                            living.getType(), fails);
                }
                if (fails > 200) {
                    iterator.remove();
                    REVIVE_FAILS.remove(living.getUUID());
                }
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
     * 常见的一招：直接往 {@code PersistentEntitySectionManager} 里塞实体。
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

    private static void purgeFromEntityManager(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level) || entity instanceof Player) {
            return;
        }
        try {
            if (entity instanceof LivingEntity living) {
                living.kill();
            }
            entity.setInvisible(true);
            entity.moveTo(10000.0D, -10000.0D, 10000.0D);
            entity.setRemoved(Entity.RemovalReason.KILLED);
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
                        }
                    }
                }
            }
            purgeFromSections(manager, entity);
            HatMod.LOGGER.warn("[hatmod] 尾杀清场：{} 已从实体表摘除", entity.getType());
        } catch (Throwable ignored) {
        }
    }

    /** 把实体从它所在的 EntitySection 里剔掉。 */
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
    private static final Map<UUID, HealthChannel> CHANNELS = new ConcurrentHashMap<>();

    /**
     * 把实体身上现在的血量同步进它真正的血量存储（世界变量 / 自有字段 / 镜像）。
     *
     * <p>我们的百分比伤害、压最大生命都是绕过伤害系统直接改实体血量的。对
     * 「实体血量只是副本」的 Boss，这会让它的校验判定成篡改，
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
     * <p>原版 {@code getHealth()} 对这类 Boss 是被钉死的假值 —— 有的固定成 1.0，
     * 有的把真血放进世界变量，有的放在自有字段里。拿假值去算「当前生命 × 5%」，
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
    private static final Map<UUID, Seal> SEALED = new ConcurrentHashMap<>();
    /** 每种怪探测出来的通道（只探一次）。 */
    private static final Map<Class<?>, List<Field>> FLOAT_FIELDS = new ConcurrentHashMap<>();

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
     *   <li><b>F 权威血量方法对</b> —— {@code xxxHealth()} / {@code setXxxHealth(float)}，
     *       对应那些把原版血量钉成假值的 Boss；</li>
     *   <li><b>A 原版 setHealth</b>；</li>
     *   <li><b>E 原版血量同步数据</b>（绕过被覆盖的 setHealth）；</li>
     *   <li><b>B 自有字段</b>；</li>
     *   <li><b>C 同步浮点对</b>（两个浮点字段相减等于血量的那种）；</li>
     *   <li><b>D 同步字符串</b>（{@code BOSS_HEALTH} 一类）：先按"等于 getHealth()"严格认，
     *       认不出来再看是不是血量被钉死了 —— 那时放宽成"是个合理血量就认"。</li>
     * </ol>
     */
    @Nullable
    private static HealthChannel detectChannel(LivingEntity target) {
        // F：权威血量方法对
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

    /** 原版血量是不是被钉成假值了（真血在别处）。 */
    private static boolean isPinnedHealth(LivingEntity target) {
        return target.getHealth() <= 1.5F && target.getMaxHealth() > 8.0F;
    }

    /**
     * F：找一对「xxxHealth() / setXxxHealth(float)」。
     *
     * <p>有的 Boss 真血走 {@code xxxHealth()}，原版 {@code getHealth()} 恒为 1.0 ——
     * 只认原版血量的话，百分比伤害会拿 1.0 去算，等于没打。
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
                // 已经 0 血却没被移除：补一刀，别把它留成「0 血活尸」
                if (target != null && !target.isRemoved() && target.getHealth() <= 0.0F) {
                    ensureDead(target, target.damageSources().genericKill());
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
     * 每 5 秒扫一遍玩家附近的「0 血活尸」。
     *
     * <p>破防的百分比伤害是 {@code setHealth} 直接写血，不经过 {@code hurt()}；
     * 如果目标自己又拒收死亡事件，就会停在 0 血、既不消失也不算活着的状态 ——
     * 光柱不会再选它，玩家也打不动。这里给它补一个死亡事件，还不走就强制移除。
     *
     * <p>只在破防模式下扫，而且只扫玩家附近 48 格，不会去翻整个世界。
     */
    private static void sweepDeadWeight(MinecraftServer server) {
        if (!HatSettings.forceBreak()) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (ServerPlayer player : level.players()) {
                for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class,
                        player.getBoundingBox().inflate(48.0D))) {
                    if (living instanceof Player || living.isRemoved()
                            || living.getHealth() > 0.0F) {
                        continue;
                    }
                    ensureDead(living, living.damageSources().genericKill());
                }
            }
        }
    }

    /**
     * 终局：先求一个**正常的死亡事件**，对方拒收才走硬杀。
     *
     * <p>逐档往下掉，能在前面停住就别走到后面：
     * <ol>
     *   <li>{@code hurt(genericKill, MAX)} —— 走完整伤害管线，能死就是正常死，
     *       掉落、死亡消息、成就、Boss 条全对；</li>
     *   <li>反射直接叫 {@code die()} —— 只拦 {@code hurt}、不拦 {@code die} 的 Boss
     *       到这一档就死，同样带掉落和演出；</li>
     *   <li>硬杀 —— 自己 roll 一次掉落，然后 {@code discard()} 把它从实体表里摘掉，
     *       并把 UUID 拉黑一段时间；它的复活逻辑要是又把它塞回来，见到一次摘一次。</li>
     * </ol>
     */
    static void finishOff(LivingEntity target) {
        if (target instanceof Player || target instanceof Khragg || isProtectedSummon(target)) {
            return;
        }
        if (!executionUnlocked(target)) {
            return;
        }
        DamageSource source = target.damageSources().genericKill();
        if (giveDeathEvent(target, source) && isReallyGone(target)) {
            return;
        }
        if (tryBackdoors(target) && isReallyGone(target)) {
            return;
        }
        // 「免删 Boss」硬杀：把它从它自己的静态登记表里划掉，再从实体表里字段级移除。
        // 不碰它的 canKill / 反作弊开关，靠的是「它的复活机制找不到它了」。
        if (evictFromStaticRegistries(target) > 0) {
            forceDie(target, source);
            if (!isReallyGone(target)) {
                forceRemove(target);
                purgeFromEntityManager(target);
            }
            if (isReallyGone(target)) {
                return;
            }
        }
        // 没有登记表的「免删 Boss」：把它自己的可死亡开关打开（它的正常死亡演出本来也会这么做），
        // 再走一次死亡 —— discard 这时才走得通，也不会被它的反作弊当成作弊。
        if (unlockDeathFlags(target)) {
            giveDeathEvent(target, source);
            if (!isReallyGone(target)) {
                forceDie(target, source);
            }
            if (!isReallyGone(target)) {
                target.discard();
            }
            if (isReallyGone(target)) {
                return;
            }
        }
        hardKill(target, source);
    }

    /**
     * 破防的最后一档：不指望它配合，直接抹掉。
     *
     * <p>顺序是「先把能写的血量清零 → 摘实体 → 字段级强删 → 从实体表抠掉 → 拉黑」。
     * 后面几档都不经过目标自己的方法，所以把 {@code discard} / {@code setRemoved}
     * 重写成空方法的实体也拦不住。
     */
    private static void hardKill(LivingEntity target, DamageSource source) {
        if (isProtectedSummon(target)) {
            return;
        }
        // 先解锁死亡开关：反作弊型 Boss 只有在「可死亡」时才允许 setRemoved 走通
        unlockDeathFlags(target);
        // 再从它的静态登记表里划掉：没有这类开关的 Boss 靠登记表每个刻复活
        evictFromStaticRegistries(target);
        try {
            HealthChannel channel = channelOf(target);
            if (channel != null && channel != VANILLA) {
                channel.write(target, 0.0F);
            }
        } catch (Throwable ignored) {
        }
        forceDie(target, source);
        target.setHealth(0.0F);
        target.discard();
        com.hatmod.guard.ProtectedListPurge.wipe(target);
        forceRemove(target);
        // 枪弹天使只摘一次：不记「删掉又回来」的类型账（记满两次会封类型 120 秒，
        // 封禁期内一刷出来就抹掉 = 禁止生成），也不进清算名册
        if (!Teto.isBulletAngel(target)) {
            noteForceRemoval(target);
        }
        purgeFromEntityManager(target);
        if (!isReallyGone(target)) {
            lastResort(target);
        }
        if (!Teto.isBulletAngel(target)) {
            PURGED.put(target.getUUID(), new Purge());
        }
        HatMod.LOGGER.warn("[hatmod] 破防硬杀 {}（{}），已从实体表摘除（{}）",
                target.getType(), target.getUUID(), isReallyGone(target) ? "已消失" : "仍在！");
    }

    private static void forceDie(LivingEntity target, DamageSource source) {
        invokeSingle(target, "die", "m_6667_", source);
        Field dead = deadField(target.getClass());
        boolean marked = false;
        try {
            marked = dead != null && dead.getBoolean(target);
        } catch (Throwable ignored) {
        }
        if (!marked) {
            invokeSingle(target, "dropAllDeathLoot", "m_6668_", source);
            try {
                if (dead != null) {
                    dead.setBoolean(target, true);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void allReturnZero(LivingEntity target) {
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                        || !field.getType().isPrimitive()) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Class<?> type = field.getType();
                    if (type == boolean.class) {
                        field.setBoolean(target, false);
                    } else if (type == byte.class) {
                        field.setByte(target, (byte) 0);
                    } else if (type == short.class) {
                        field.setShort(target, (short) 0);
                    } else if (type == int.class) {
                        field.setInt(target, 0);
                    } else if (type == long.class) {
                        field.setLong(target, 0L);
                    } else if (type == float.class) {
                        field.setFloat(target, 0.0F);
                    } else if (type == double.class) {
                        field.setDouble(target, 0.0D);
                    } else if (type == char.class) {
                        field.setChar(target, (char) 0);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void lastResort(LivingEntity target) {
        int id = target.getId();
        try {
            target.deathTime = 20;
            target.setHealth(0.0F);
        } catch (Throwable ignored) {
        }
        try {
            if (target.level() instanceof ServerLevel level) {
                level.getChunkSource().broadcastAndSend(target,
                        new net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket(id));
            }
        } catch (Throwable ignored) {
        }
        allReturnZero(target);
        try {
            Field dead = deadField(target.getClass());
            if (dead != null) {
                dead.setBoolean(target, true);
            }
        } catch (Throwable ignored) {
        }
        try {
            Field reason = fieldOfType(Entity.class, RemovalReason.class);
            if (reason != null) {
                reason.set(target, RemovalReason.KILLED);
            }
        } catch (Throwable ignored) {
        }
        try {
            Field callback = fieldOfType(Entity.class,
                    net.minecraft.world.level.entity.EntityInLevelCallback.class);
            if (callback != null) {
                callback.set(target, net.minecraft.world.level.entity.EntityInLevelCallback.NULL);
            }
        } catch (Throwable ignored) {
        }
        purgeFromEntityManager(target);
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

    static boolean giveDeathEvent(LivingEntity target, DamageSource source) {
        if (target instanceof Player) {
            return false;
        }
        target.invulnerableTime = 0;
        target.setInvulnerable(false);
        if (target.getHealth() <= 0.0F) {
            float max = Math.max(1.0F, target.getMaxHealth());
            target.setHealth(Math.min(1.0F, max * 0.01F));
        }
        target.hurt(source, Math.max(100.0F, target.getMaxHealth() * 4.0F));
        if (isTrulyDead(target)) {
            return true;
        }
        invokeSingle(target, "die", "m_6667_", source);
        return isTrulyDead(target);
    }

    /**
     * 把一个已经掉到 0 血、却还留在世界里的目标真正送走。
     *
     * <p>{@code setHealth(0)} 不会触发 {@code die()}。对拒收 {@code hurt} / {@code die}
     * 的模组生物，它会停在「血量 0、实体还在」的状态：原版 {@code isAlive()} 为 false，
     * 光柱不会再选它，玩家也打不动它 —— 只能在这里收尾。
     */
    private static void ensureDead(LivingEntity target, DamageSource source) {
        if (target instanceof Player || target instanceof Khragg || isProtectedSummon(target)
                || target.isRemoved()) {
            return;
        }
        if (target.getHealth() > 0.0F) {
            return;
        }
        giveDeathEvent(target, source);
        if (target.isRemoved() || target.getHealth() > 0.0F) {
            return;
        }
        unlockDeathFlags(target);
        evictFromStaticRegistries(target);
        target.discard();
        HatMod.LOGGER.warn("[hatmod] {} 掉到 0 血却没死，已强制移除", target.getType());
    }

    /**
     * 虚影 / 铁托阵亡时的反噬：谁把我们的人打死，
     * 就在它尸体被删除的前一秒（{@code deathTime == 19}）把对方从世界里抹掉。
     *
     * <p>只对「外人」生效：玩家、自己人一律放过。
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
        // 顺手把周围一圈外人一起抹掉
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
        if (!target.isAlive()) {
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

    /** 「免删 Boss」的死亡开关（打开 = true）：按命名习惯试，不认模组。 */
    private static final String[] DEATH_FLAGS_ENABLE = {
            "setCanKill", "setCanDie", "setKillable", "setCanBeKilled",
            "setMortal", "setVulnerable", "setRemovable"
    };

    /** 「免删 Boss」的死亡开关（关掉 = false）。 */
    private static final String[] DEATH_FLAGS_DISABLE = {
            "setImmortal", "setInvincible", "setGodMode", "setDeathLocked"
    };

    /**
     * 把目标自己的「可死亡 / 可移除」开关拨到「能死」—— **按命名习惯挨个试，不认模组**。
     *
     * <p>有一类 Boss 用「反作弊」把删除操作挡在外面：血量清零 / 移除请求会被它当成作弊，
     * 实体被它自己的表强行加回来，还顺手把反作弊打开；而反作弊一打开，它自己的
     * {@code die()} 就成了空操作 —— 于是永远杀不掉。
     *
     * <p>但它的**正常死亡演出最后一步**本来就会把「可死亡」开关打开（不然玩家也杀不掉它）。
     * 这里只是替它把这一步补上，之后 {@code discard()} 走得通、它的表也不会再把实体加回来。
     * <b>反作弊本身一个字都不动</b> —— 我们只声明「这只该死了」，不声明「玩家作弊了」。
     *
     * @return true = 至少拨动了一个开关
     */
    private static boolean unlockDeathFlags(LivingEntity target) {
        boolean any = false;
        any |= setBooleanFlags(target, DEATH_FLAGS_ENABLE, true);
        any |= setBooleanFlags(target, DEATH_FLAGS_DISABLE, false);
        return any;
    }

    private static boolean setBooleanFlags(LivingEntity target, String[] names, boolean value) {
        boolean any = false;
        for (String name : names) {
            for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    java.lang.reflect.Method method = c.getDeclaredMethod(name, boolean.class);
                    method.setAccessible(true);
                    method.invoke(target, value);
                    HatMod.LOGGER.warn("[hatmod] 破防解锁 {} 的死亡开关：{}({})",
                            target.getType(), name, value);
                    any = true;
                    break;
                } catch (NoSuchMethodException ignored) {
                    // 换父类 / 换下一个名字
                } catch (Throwable ignored) {
                    break;
                }
            }
        }
        return any;
    }

    /** 「硬杀」用的静态登记表扫描结果缓存：jar 路径 -> 该 jar 里的静态集合字段。 */
    private static final java.util.Map<String, java.util.List<java.lang.reflect.Field>>
            STATIC_REGISTRY_FIELDS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 硬杀：把目标从**它自己那个模组的所有静态登记表**里点名划掉。
     *
     * <p>有一类「免删 Boss」不靠 canKill 保命，而是把自己登记进一张静态表：
     * 每个服务器刻扫一遍，发现实体不在了就**字段级**强行加回来（还会顺手打开它自己的反作弊）。
     * 只要它还在表里，怎么删都会被复活；而它自己的 {@code die()} 在反作弊打开后又是空操作。
     *
     * <p>这里不认类名、不认实体 id：扫描**目标所在 jar** 的全部类，找所有静态
     * {@code Map} / {@code Collection}，把「值就是这只实体」或「键就是它的 UUID」的条目删掉。
     * 之后正常移除就会永久生效 —— 不需要它自己同意（它的 canKill / 反作弊开关一个字都不动）。
     *
     * @return 清掉了几个登记项（0 = 没有登记表，或者扫描不到）
     */
    static int evictFromStaticRegistries(LivingEntity target) {
        java.util.List<java.lang.reflect.Field> fields;
        try {
            fields = staticCollectionsOf(target.getClass());
        } catch (Throwable error) {
            return 0;
        }
        if (fields.isEmpty()) {
            return 0;
        }
        java.util.UUID id = target.getUUID();
        int removed = 0;
        for (java.lang.reflect.Field field : fields) {
            try {
                Object value = field.get(null);
                if (value instanceof java.util.Map<?, ?> map) {
                    java.util.Iterator<? extends java.util.Map.Entry<?, ?>> it = map.entrySet().iterator();
                    while (it.hasNext()) {
                        java.util.Map.Entry<?, ?> entry = it.next();
                        if (entry.getValue() == target || id.equals(entry.getKey())) {
                            it.remove();
                            removed++;
                        }
                    }
                } else if (value instanceof java.util.Collection<?> collection) {
                    java.util.Iterator<?> it = collection.iterator();
                    while (it.hasNext()) {
                        Object element = it.next();
                        if (element == target || id.equals(element)) {
                            it.remove();
                            removed++;
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 只读集合 / 静态初始化炸了：换下一个
            }
        }
        if (removed > 0) {
            HatMod.LOGGER.warn("[hatmod] 破防硬杀：已把 {} 从 {} 个静态登记项里划掉",
                    target.getType(), removed);
        }
        return removed;
    }

    /** 目标所在 jar 里所有「静态 Map / Collection」字段（按 jar 缓存）。 */
    private static java.util.List<java.lang.reflect.Field> staticCollectionsOf(Class<?> type) {
        String pkg = type.getPackageName();
        if (pkg.startsWith("net.minecraft") || pkg.startsWith("net.minecraftforge")
                || pkg.startsWith("net.neoforged") || pkg.startsWith("com.mojang")
                || pkg.startsWith("java.") || pkg.startsWith("com.hatmod")) {
            return java.util.List.of();             // 原版 / 自家实体：没有这种登记表
        }
        java.io.File file = jarFileOf(type);
        if (file == null || !file.isFile()) {
            return java.util.List.of();             // 开发环境（目录）/ 拿不到 jar：不扫
        }
        String key = file.getAbsolutePath();
        java.util.List<java.lang.reflect.Field> cached = STATIC_REGISTRY_FIELDS.get(key);
        if (cached != null) {
            return cached;
        }
        java.util.List<java.lang.reflect.Field> found = new java.util.ArrayList<>();
        STATIC_REGISTRY_FIELDS.put(key, found);     // 先占位，避免并发重复扫描
        try {
            ClassLoader loader = type.getClassLoader();
            try (java.util.zip.ZipFile jar = new java.util.zip.ZipFile(file)) {
                java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (!name.endsWith(".class") || name.startsWith("META-INF/")
                            || name.endsWith("module-info.class")
                            || name.endsWith("package-info.class")) {
                        continue;
                    }
                    String className = name.substring(0, name.length() - 6).replace('/', '.');
                    Class<?> clazz;
                    try {
                        clazz = Class.forName(className, false, loader);
                    } catch (Throwable ignored) {
                        continue;                   // 可选依赖缺失之类：跳过
                    }
                    for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
                        if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                            continue;
                        }
                        Class<?> fieldType = field.getType();
                        if (!java.util.Map.class.isAssignableFrom(fieldType)
                                && !java.util.Collection.class.isAssignableFrom(fieldType)) {
                            continue;               // 只看静态 Map / Collection
                        }
                        try {
                            field.setAccessible(true);
                        } catch (Throwable ignored) {
                            continue;
                        }
                        found.add(field);
                    }
                }
            }
        } catch (Throwable error) {
            HatMod.LOGGER.warn("[hatmod] 硬杀：扫描 {} 的登记表失败：{}",
                    type.getSimpleName(), error.toString());
        }
        return found;
    }

    /**
     * 找到这个类（或者它的某个父类）所在的 jar。
     *
     * <p>两个坑（1.5.209 实测）：
     * <ul>
     *   <li>Forge 的联合类加载器给的是 {@code union:} / {@code jar:} URL，
     *       {@code CodeSource#getLocation().toURI()} 会直接抛「URI scheme is not "file"」；</li>
     *   <li>目标可能是**运行时生成**的子类（反作弊 Boss 常见，如 {@code ...Entity$true}），
     *       它自己不在任何 jar 里 —— 得顺着父类往上找。</li>
     * </ul>
     * 所以这里按字符串手动拆 URL，并且逐级往上找第一个真实存在的 jar。
     */
    @Nullable
    private static java.io.File jarFileOf(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            java.io.File file = fileFromUrl(codeSourceOf(c));
            if (file != null && file.isFile()) {
                return file;
            }
            try {
                java.net.URL url = c.getResource("/" + c.getName().replace('.', '/') + ".class");
                file = fileFromUrl(url);
                if (file != null && file.isFile()) {
                    return file;
                }
            } catch (Throwable ignored) {
                // 换父类
            }
        }
        return null;
    }

    @Nullable
    private static java.net.URL codeSourceOf(Class<?> type) {
        try {
            java.security.CodeSource source = type.getProtectionDomain().getCodeSource();
            return source == null ? null : source.getLocation();
        } catch (Throwable error) {
            return null;
        }
    }

    /** 把 {@code file:} / {@code jar:file:} / {@code union:} 这几种 URL 还原成磁盘路径。 */
    @Nullable
    private static java.io.File fileFromUrl(@Nullable java.net.URL url) {
        if (url == null) {
            return null;
        }
        String text = url.toString();
        try {
            int bang = text.indexOf("!/");
            if (bang > 0) {
                text = text.substring(0, bang);     // jar:file:/x.jar!/pkg/Cls.class -> jar:file:/x.jar
            }
            // Forge 的联合类加载器会在 jar 路径后面拼 "#<序号>"（URL 里写成 %23<序号>）：
            // 那一段不是文件名的一部分，先切掉再解码，否则会去找一个不存在的 xxx.jar#254
            int hash = text.indexOf("%23");
            if (hash < 0) {
                hash = text.indexOf('#');
            }
            if (hash > 0) {
                text = text.substring(0, hash);
            }
            boolean stripped = true;
            while (stripped) {
                stripped = false;
                for (String prefix : new String[] {"jar:", "union:", "file:"}) {
                    if (text.startsWith(prefix)) {
                        text = text.substring(prefix.length());
                        stripped = true;
                        break;
                    }
                }
            }
            if (text.startsWith("//")) {
                text = text.substring(2);
            }
            if (text.isEmpty() || text.startsWith("union") || text.contains("!/")) {
                return null;
            }
            return new java.io.File(java.net.URLDecoder.decode(text,
                    java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable error) {
            return null;
        }
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
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
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

    /** 硬杀过、还在被复活逻辑往回塞的目标。 */
    private static final Map<UUID, Purge> PURGED = new ConcurrentHashMap<>();
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
    private static final Map<String, Ban> BANNED_TYPES = new ConcurrentHashMap<>();
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

    static int watchedCount() {
        return WATCHED.size();
    }

    static java.util.List<String> guardLines(net.minecraft.server.MinecraftServer server) {
        java.util.List<String> lines = new ArrayList<>();
        lines.add("forceBreak=" + HatSettings.forceBreak()
                + " entityLock=" + HatSettings.entityLock()
                + " tamperScan=" + HatSettings.tamperScan());
        lines.add("PURGED=" + PURGED.size() + " BANNED_TYPES=" + BANNED_TYPES.size()
                + " WATCHED=" + WATCHED.size());
        long now = server.getTickCount();
        for (Map.Entry<String, Ban> entry : BANNED_TYPES.entrySet()) {
            lines.add("  ban " + entry.getKey() + " -> "
                    + Math.max(0L, (entry.getValue().untilTick - now) / 20L) + "s");
        }
        return lines;
    }

    private static void noteForceRemoval(LivingEntity target) {
        if (target instanceof Khragg) {
            return;
        }
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
            List<LivingEntity> victims = new ArrayList<>();
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof LivingEntity living) || living.isRemoved()
                        || living instanceof Player) {
                    continue;
                }
                Ban ban = BANNED_TYPES.get(living.getClass().getName());
                if (ban != null && now <= ban.untilTick) {
                    victims.add(living);
                }
            }
            for (LivingEntity victim : victims) {
                evictFromStaticRegistries(victim);
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
                // 又活过来了 = 有东西把它加回来：先把它从静态登记表里划掉，再摘一次
                evictFromStaticRegistries(target);
                target.setHealth(0.0F);
                target.discard();
                if (!deadOrGone(target)) {
                    forceRemove(target);
                }
                if (target instanceof Teto && purge.logged++ < 3) {
                    HatMod.LOGGER.warn("[hatmod] 清算名册摘掉了一只 Teto（第 {} 次）—— "
                            + "它进过死亡事件，所以会被反复摘", purge.logged);
                }
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
     * <p>比每 5 刻扫一遍干净得多：实体连一个 tick 都跑不到，那些「进场即复活」的花招
     * （tick 里自己再 register 的那种）也就没机会表演。
     */
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        // 贴脸生成的怨灵（指令 / 刷怪蛋硬塞到玩家身上）：生成事件在实体第一刻 tick
        // 之前触发，Teto 在岗的话她生成那一刻就把它按住，抢在 strike() 之前。
        // 存档里读出来的旧鬼（坟位上的）不归这管，走正常流程。
        if (event.getEntity() instanceof LivingEntity freshGhost
                && HatAbilities.isGhostMob(freshGhost)
                && event.getLevel() instanceof ServerLevel joinLevel
                && !event.loadedFromDisk()) {
            GhostGuard.handleGhostJoin(joinLevel, freshGhost);
        }
        if (BANNED_TYPES.isEmpty() || !(event.getEntity() instanceof LivingEntity living)) {
            return;
        }
        if (living instanceof Player || living instanceof Khragg
                || !(event.getLevel() instanceof ServerLevel level)) {
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
     * （多道光就是各打各的目标，见 {@link #beamTargets}）。锁定的目标由 {@code findNearestEnemies}
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
        // 1.20.1 没有 bypasses_cooldown 伤害标签，改为把受击无敌帧清零，
        // 这样每刻的伤害都会真正结算。
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

        // 百分比伤害是**乘性**的：血再少也乘不到 0。对方又拒收 hurt（每击上限 + 无敌帧
        // 那类，使徒就是），就会停在「一丝血、怎么打都不死」。压到 1 点以下时把最后这点
        // 补成 0，让它走**自己的**死亡流程 —— 掉落、死亡消息、Boss 条都照常。
        if (!target.isRemoved() && target.isAlive() && hatmodGetHealth(target) <= 1.0F) {
            HealthChannel channel = channelOf(target);
            if (channel != null && channel != VANILLA) {
                channel.write(target, 0.0F);
            }
            target.setHealth(0.0F);
        }

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
     * {@link #grantGreenHearts}：无限时长、掉落速度随颗数指数增长、基础 1 秒/颗、优先级高过黄心。
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
     * 对把真血放在别处的 Boss 只是改了镜像，它自己的每刻逻辑再把镜像刷回去 ——
     * 所以怎么打都不掉血。
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
        if (!target.isRemoved() && (target.getHealth() <= 0.0F || hatmodGetHealth(target) <= 0.0F)) {
            ensureDead(target, source);
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
        if (target instanceof Player && !HatSettings.affectPlayers()
                && !(wearer instanceof Khragg)) {
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
        // 打到 0：setHealth 不触发死亡，补一个正经的死亡事件。
        // **原版血量和通道血量都要看**：通道探测错的时候通道可能还读出非 0，
        // 但原版血已经 0 —— 只查通道就会漏掉，把它留成「0 血不死」的活尸。
        if (!target.isRemoved() && (target.getHealth() <= 0.0F || hatmodGetHealth(target) <= 0.0F)) {
            ensureDead(target, source);
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
            // 上线第一刻就把「强制不变量」过一遍：有些 force* 杀法会把死亡状态写进
            // 玩家存档（血 NaN / dead / 坐标写在世界外），不修的话进服就死在原地。
            guardPlayer(player);
        }
    }

    /** 重生那一刻同样过一遍：死亡流程里被写坏的状态（deathTime / 坐标 / 血量）就地清掉。 */
    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            guardPlayer(player);
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
