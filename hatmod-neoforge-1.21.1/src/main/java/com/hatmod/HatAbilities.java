package com.hatmod;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 帽子的核心技能状态机，对**任何戴帽子的生物**生效（玩家、车万女仆等）。
 *
 * <pre>
 *   IDLE ──16 格内出现合法敌人──> CHARGE ── 循环开始，播放这顶帽子的音乐
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
    /** 敌人接近判定半径：有敌人才会进入蓄力阶段。 */
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

    /** 帽子上每多一个附魔，就给戴帽者多加多少点护甲。 */
    private static final double ARMOR_PER_ENCHANT = 2.0D;
    /** 附魔护甲加成用的固定修饰符 id（同一顶帽子反复刷新时靠它去重 / 覆盖）。 */
    private static final ResourceLocation ENCHANT_ARMOR_ID = HatMod.id("enchant_armor");
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
        private final long lastTick;
        private final Mob forcedNoAi;

        private Hold(long lastTick, Mob forcedNoAi) {
            this.lastTick = lastTick;
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
        living.setDeltaMovement(Vec3.ZERO);
        living.hurtMarked = true;
        living.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, HOLD_TICKS, HOLD_AMPLIFIER, false, false, false));
        living.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, HOLD_TICKS, DISABLE_AMPLIFIER, false, false, false));
        living.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN, HOLD_TICKS, DISABLE_AMPLIFIER, false, false, false));

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
        HELD.put(living.getUUID(), new Hold(level.getServer().getTickCount(), forced));
    }

    /**
     * 解除"超过 2 刻没再被照到"的强控并恢复 AI。
     *
     * <p>按"最后一次被照到的刻"判定，而不是在光柱结束时逐个通知：
     * 施法者死亡、摘帽、掉线、走远等情况都能自动收尾，不会留下一只永久 noAI 的怪。
     */
    private static void releaseStaleHolds(int now) {
        if (HELD.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Hold>> iterator = HELD.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Hold> entry = iterator.next();
            if (now - entry.getValue().lastTick <= 2) {
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
     * 挂着「神隐」的生物每刻的维护，整体设计见 {@link HatEffects} 顶部的说明。
     *
     * <p>两件事：
     * <ul>
     *   <li>把它标成**隐身** —— 神隐本身就是隐身；</li>
     *   <li>定期把「已经锁上来」的目标甩掉 —— 隐身劝不退已经开打的敌人，那些锁定只能一个个清。</li>
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
                HatMusic.stop(level, wearer, state.type);
                clearBeamSync(wearer, state);
                STATES.remove(id);
            }
            return;
        }

        if (state == null) {
            state = new HatState();
            state.type = type;
            STATES.put(id, state);
        } else if (state.type != type) {
            // 中途换帽子：上一轮的减速/抗性/光柱/增益/音乐全部收掉，从零开始
            clearSlowField(level, wearer);
            clearChargeResistance(wearer);
            clearPermanentBuffs(wearer);
            clearEnchantArmor(wearer);
            clearGreenHearts(wearer);
            clearCycleSpeed(wearer);
            HatMusic.stop(level, wearer, state.type);
            clearBeamSync(wearer, state);
            state.reset();
            state.type = type;
        }

        // 白帽：戴着就一直有迅捷 I + 跳跃 I
        applyPermanentBuffs(wearer, type);
        // 帽子上每多一个附魔，护甲 +2
        applyEnchantArmor(wearer, hatStack(wearer));

        if (state.flash > 0) {
            tickingFlash(wearer, level, state);
        } else if (state.charge > 0) {
            tickingCharge(wearer, level, state);
        } else if (findNearestEnemy(wearer) != null) {
            // 只有存在合法目标才会进入蓄力阶段；循环由此开始
            startCharge(wearer, level, state);
            tickingCharge(wearer, level, state);
        }
    }

    /** 进入蓄力阶段：重置本轮状态并播放这顶帽子的音乐。 */
    private static void startCharge(LivingEntity wearer, ServerLevel level, HatState state) {
        ItemStack hat = hatStack(wearer);
        // 附魔「闪避」多给几次瞬移闪避；蓄力时长本身**不受附魔影响**（要跟音乐对齐）
        state.charge = HatSettings.chargeTicks(state.type);
        state.blinks = BLINKS_PER_CHARGE + HatEnchants.extraBlinks(hat);
        state.flashBlinks = 0;
        state.dominionFired = false;
        HatMusic.play(level, wearer, state.type);
        // 附魔「预知」不在这里放 —— 它改到**蓄力完成的那一刻**才释放（见 tickingCharge）
    }

    private static void tickingCharge(LivingEntity wearer, ServerLevel level, HatState state) {
        applySlowField(wearer, level);
        // 蓄力全程固定抗性提升 II，蓄力一结束就收（见下面的 clearChargeResistance）
        applyChargeResistance(wearer);
        spawnChargeAura(level, wearer);
        spawnSlowRing(level, wearer, state.type);

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
            state.flash = HatSettings.flashTicks(state.type);
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
        if (HatSettings.chargeTicks(state.type) - state.charge < HatEnchants.DOMINION_TRIGGER_TICK) {
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
        if (current != null && current.isAlive()) {
            return; // 已经在打了
        }
        LivingEntity victim = findDominionVictim(mob);
        if (victim == null) {
            return; // 身边没有别的敌人：那就照旧发呆，等解禁
        }
        DOMINION_VICTIM.put(mob.getUUID(), victim.getUUID());
        if (mob instanceof Warden warden) {
            // 监守者的目标是 Brain 记忆，只有它自己的 setAttackTarget 写得进去
            warden.setAttackTarget(victim);
        } else {
            mob.setTarget(victim);
        }
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
        // 红帽附魔「光柱」会同时打多道光柱：每道光**各锁一个不同的敌人**，
        // 敌人不够就少发几道（单挑一个 Boss 时仍旧只有一道），见 beamTargets。
        ItemStack hat = hatStack(wearer);
        int beamCount = HatEnchants.conflagrationBeams(hat);
        List<LivingEntity> targets = beamTargets(wearer, beamCount);

        // 「正在照射 / 锁定谁」全部走本模组自己的包（BeamTargets），不依赖任何状态效果。
        // 多道光柱要把所有目标的 id 一起发过去，客户端才能各自画一条。
        syncBeam(wearer, state, true, targets);

        // 红帽：照射期间力量 I（每刻刷新）。原先附魔「炽怒」能把它顶到力量 IV，已废弃。
        // 必须赶在 damageBeam 前面给：光柱伤害会读这份力量折成倍率（见 beamPowerMultiplier），
        // 否则每轮照射的第一刻会漏掉加成。
        if (state.type.has(HatType.Trait.STRENGTH)) {
            wearer.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, STRENGTH_TICKS, 0, false, false, true));
        }

        damageBeam(level, wearer, state.type, hat, targets);
        // 每道光柱都在轴线附近撒一点火花（没有目标时沿视线撒）
        if (targets.isEmpty()) {
            spawnBeamSparks(level, wearer, wearer.getViewVector(1.0F).normalize());
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
            // 照完之后重新进入循环：附近还有敌人就再次蓄力（并从循环开始处再放一次音乐）
            if (findNearestEnemy(wearer) != null) {
                startCharge(wearer, level, state);
            } else {
                state.charge = 0;
                HatMusic.stop(level, wearer, state.type);
            }
        }
    }

    /**
     * 多道光柱堆叠在同一目标上的**边际递减**系数：同一目标上第 m 道（从 0 数）乘
     * {@code BEAM_STACK_FACTOR^m}。
     *
     * <p>敌人少于光柱数时（比如单挑一个 Boss），多余的光柱会落回已有目标而不是白白浪费 ——
     * 但每多叠一道就打个折，所以 4 道全堆一个人是 1 + 0.4 + 0.16 + 0.064 = 1.624 倍，
     * 而不是 4 倍。调大它红帽单挑更强，调小则更接近「一人一道」。
     */
    private static final float BEAM_STACK_FACTOR = 0.4F;

    /**
     * 这次照射每道光柱各自锁谁：由近到远取敌人，**敌人不够时多余的光柱堆叠到已有目标上**。
     *
     * <p>返回列表的长度恒为 {@code beamCount}（有敌人时），下标 i 就是「第 i 道光柱」：
     * 目标取 {@code enemies[i % n]}，堆叠轮次 {@code i / n}。于是敌人够多时一人一道（系数全 1），
     * 敌人不够时最近的那些敌人各多挨几道、第 m 道按 {@link #BEAM_STACK_FACTOR} 递减。
     * 一个敌人都没有时返回空列表（客户端仍会沿视线画出一道，见 {@link #tickingFlash}）。
     */
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

    /** 还没有神隐、或者快到期了才补一次；已经有了且时间还早就不动它。 */
    private static void applyVeil(LivingEntity living, int ticks) {
        MobEffectInstance current = living.getEffect(HatEffects.VEIL);
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
     * <p>因为是在伤害结算之前直接把数值削掉，剩下的才继续走原版流程，所以
     * **绿心永远排在黄心和血量前面** —— 这就是「抗伤优先级比黄心高」。
     * 返回 0 表示这一击被绿心整个吃掉（调用方应当把这次伤害取消掉）。
     */
    private static float absorbWithGreenHearts(LivingEntity living, float amount) {
        float green = greenHearts(living);
        if (green <= 0.0F || amount <= 0.0F) {
            return amount;
        }
        float absorbed = Math.min(green, amount);
        setGreenHearts(living, green - absorbed);
        return amount - absorbed;
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
                        || (isWearingHat(livingAttacker) && HatProtection.isProtected(entity)))) {
            event.setCanceled(true);
            return;
        }

        // 「光柱」吸血的绿心：本模组里优先级最高的一层抗伤，先扣它，扣不完的才轮到黄心/血量。
        // 带 bypasses_invulnerability 的伤害（/kill、虚空，以及本模组自己的光柱）不吃绿心 ——
        // 免得一颗绿心把「必死」的伤害也挡下来。
        DamageSource source = event.getSource();
        if (!source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            float remaining = absorbWithGreenHearts(entity, event.getAmount());
            if (remaining < event.getAmount()) {
                event.setAmount(remaining);
                if (remaining <= 0.0F) {
                    // 这一击被绿心整个吃掉：取消掉，不掉血、不吃击退、不触发无敌帧
                    event.setCanceled(true);
                    return;
                }
            }
        }

        HatState state = STATES.get(entity.getUUID());
        if (state == null || state.type == null) {
            return;
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
            return;
        }

        // 先传送：传成功才算躲掉，传不出去（找不到安全落点）就照常挨打，
        // 白白浪费一次机会不说、还免疫掉伤害，那才不合理。
        if (!blinkToSafety(level, entity, remaining)) {
            return;
        }
        if (charging) {
            state.blinks--;
        } else {
            state.flashBlinks--;
        }

        // 人已经闪走了，这一击随之落空：取消伤害事件 = 这一下完全没打中。
        // 护甲不掉耐久、不吃击退、不掉血，附带的中毒/燃烧之类也一并作废。
        event.setCanceled(true);
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

    /** 「谁打过我」记多久（10 秒 = 200 刻）。 */
    private static final int AGGRO_MEMORY_TICKS = 200;

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
        AABB area = wearer.getBoundingBox().inflate(FLASH_RANGE);
        List<LivingEntity> found = new ArrayList<>();
        for (Entity entity : wearer.level().getEntities(wearer, area)) {
            if (!(entity instanceof LivingEntity living) || !isEnemy(living, wearer)) {
                continue;
            }
            found.add(living);
        }
        found.sort((a, b) -> Double.compare(wearer.distanceToSqr(a), wearer.distanceToSqr(b)));
        return found.size() <= count ? found : new ArrayList<>(found.subList(0, count));
    }

    /**
     * 敌人 = 敌意生物 / 被激怒的中立生物 / 正在锁定戴帽者的生物 /
     * 最近真的打过戴帽者的生物（见 {@link #rememberAggressor}）。
     * 不含玩家和己方（避免误伤队友、宠物、自己的女仆）。
     */
    public static boolean isEnemy(Entity entity, LivingEntity wearer) {
        if (entity == wearer || entity instanceof Player) {
            return false;
        }
        if (isFriendlyTo(wearer, entity)) {
            return false;
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
     */
    private static void damageBeam(ServerLevel level, LivingEntity wearer, HatType type,
                                   ItemStack hat, List<LivingEntity> targets) {
        // 每刻固定伤害：就是这顶帽子的 damagePerTick（默认 10）——
        // 附魔不再给光柱加伤害（光辉只管神隐、预知只管定身、光柱只管道数）
        float flatDamage = HatSettings.damagePerTick(type);
        // 戴帽者身上的力量/虚弱折成倍率，固定伤害和百分比伤害一起吃（见 beamPowerMultiplier）
        float power = beamPowerMultiplier(wearer);
        DamageSource source = HatSettings.damageSource(level, wearer, type);

        // 每道光柱各打自己锁定的那个目标。红帽附魔「光柱」会有多道：
        // 敌人够多就一人一道；敌人不够时多余的堆叠到已有目标上，按 BEAM_STACK_FACTOR 边际递减。
        // 没有目标时不结算（客户端仍会沿视线画出一道，见 tickingFlash）。
        int distinct = distinctTargets(targets);
        for (int i = 0; i < targets.size(); i++) {
            int stack = distinct <= 0 ? 0 : i / distinct;
            float factor = power * (float) Math.pow(BEAM_STACK_FACTOR, stack);
            float drained = damageOneBeam(level, wearer, type, source, flatDamage * factor, factor, targets.get(i));
            applyLifesteal(wearer, hat, drained);
        }
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

        float before = target.getHealth();

        // 先按当前血量扣掉百分比那部分（绕过减伤），再用正常 hurt() 结算固定伤害收尾。
        // 顺序不能反：hurt() 有可能直接把目标打死，之后再 setHealth 会很难看。
        applyPercentageDamage(target, type, power);

        // 再结算固定伤害、冻结速度：否则 hurt() 内部施加的击退会残留在速度里。
        // 伤害类型已加入 bypasses_cooldown 标签；这里同时清零无敌帧，双保险。
        target.invulnerableTime = 0;
        target.hurt(source, poweredFlat);

        // 被罩住 = 彻底控住，连技能都放不出来
        hold(level, target);

        // 这一击实际打掉了多少血（setHealth 与 hurt 都算在内），供吸血用。
        return Math.max(0.0F, before - target.getHealth());
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
    private static float beamPowerMultiplier(LivingEntity wearer) {
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
     * <p>但 {@code setHealth} 只改血量、不触发死亡（{@code die()} 只在 {@code hurt()} 和
     * 死亡事件里调）。所以目标血量掉到最大生命的
     * {@link HatSettings#healthDamageFloor}（默认 5%）以下后就不再扣了，
     * 把收尾让给同样在打的固定伤害，让它走正常的死亡流程。
     */
    private static void applyPercentageDamage(LivingEntity target, HatType type, float power) {
        if (target.isDeadOrDying()) {
            return;
        }
        float health = target.getHealth();
        if (health < target.getMaxHealth() * HatSettings.healthDamageFloor(type)) {
            return;
        }
        // setHealth 内部会 clamp 到 [0, maxHealth]；力量倍率再高，这里也夹在 (0, health) 之间，安全。
        float ratio = Math.min(HatSettings.healthDamageRatio(type) * power, 0.95F);
        target.setHealth(health - health * ratio);
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
}
