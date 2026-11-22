package com.hatmod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 每顶帽子的可调参数：蓄力/照射时长、伤害数值、以及光柱用哪种伤害类型。
 *
 * <p>由创造模式的「调参器」物品打开界面修改（{@code client.HatTunerScreen}），
 * 存在 {@code config/hatmod.json}。
 *
 * <p><b>服务端权威</b>：真正生效的是服务端这一份；客户端只通过 {@link HatNetwork}
 * 收一份只读拷贝，用来把界面上的当前值显示出来。改完保存 -> 客户端发包 -> 服务端落盘
 * -> 服务端把最新的一份广播给所有人。
 *
 * <p><b>配置版本（{@link #CONFIG_VERSION}）</b>：文件一旦存在就会一直盖住
 * {@link HatType} 里的默认数值，也就是「改了默认值但游戏里没变化」。所以这里带一个版本号：
 * 加载时发现文件里的版本比 {@link #CONFIG_VERSION} 旧，就整份丢弃、改用新默认值
 * （下次在调参器里保存时会写上新版本号）。改默认数值时把版本号加一即可，不用让人手动删文件。
 *
 * <p>伤害类型存的是字符串 id（默认 {@code hatmod:flash}），所以可以随手改成
 * {@code minecraft:magic}、{@code minecraft:generic_kill}、或其它模组注册的类型。
 * 配置的类型查不到时**不会崩**，只是退回到 {@code minecraft:generic_kill} 并打一条警告。
 */
public final class HatSettings {

    /**
     * 配置格式 / 默认数值的版本号。
     *
     * <p>改动 {@link HatType} 里的任何默认数值时把它加一，旧 config 就会自动作废。
     *
     * <p>只增不减，纯粹用来让旧 config 失效；每一版具体改了什么不在这里逐个记，
     * 看 {@link HatType} 顶部那段「当前开火点 / 一轮总长」。
     */
    public static final int CONFIG_VERSION = 8;

    /** 光柱每刻额外造成「目标当前生命 × 这个比例」的伤害。 */
    public static final float DEFAULT_HEALTH_DAMAGE_RATIO = 0.05F;
    /**
     * 目标血量低于「最大生命 × 这个比例」后不再扣附加伤害。
     *
     * <p>出厂值是 <b>0</b>：不设地板，百分比伤害一路扣到 0，再由死亡事件收尾。
     * 想要留一点余量（让固定伤害去收尾）可以自己在调参器里调大。
     */
    public static final float DEFAULT_HEALTH_DAMAGE_FLOOR = 0.0F;
    /** 默认伤害类型：本模组的自定义类型。 */
    public static final String DEFAULT_DAMAGE_TYPE = "hatmod:flash";

    /**
     * 「脑蚀」光柱每帧伤害的倍率（乘在黑帽那份 {@code damagePerTick} 上）。
     *
     * <p>倍率，乘在黑帽的 {@code damagePerTick}（10）上 —— 1.5 = 每道每刻 15 点。
     * 可以在调参器的「脑蚀」页改；
     * 老配置文件里没有这个字段时会自动落成这个出厂值（字段带初始化值，
     * 不需要为此加 {@link #CONFIG_VERSION}，也就不会把用户调好的其它数值冲掉）。
     */
    public static final float DEFAULT_BRAINROT_DAMAGE_SCALE = 1.5F;

    /**
     * 「脑蚀」光柱**百分比伤害**的倍率（乘在「每刻 当前生命 × 0.5%」那一层上）。
     *
     * <p>出厂值 <b>0.5</b>：就是原来的一半 —— 0.5%/刻 → 0.25%/刻，
     * 连击 / 涌动叠上去的那几层也一起按这个比例缩。
     * 调参器「脑蚀」页第二个输入框就是它。
     */
    public static final float DEFAULT_BRAINROT_PERCENT_SCALE = 0.5F;

    /**
     * 散养的 Teto **每一次闲聊时消失**的概率（出厂 0.1%）。
     *
     * <p>她本来就要在后室里继续找出口，偶尔也会「我有点事，先走了」—— 说完就散场，
     * 不是被打死，也不掉东西，想要再放一只就行。调参器「脑蚀」页最后一个输入框；
     * 调试时拉到 {@code 1}（100%）可以每 30 秒复现一次，不用等运气。
     */
    public static final float DEFAULT_TETO_VANISH_CHANCE = 0.01F;

    /**
     * 「自己的 BGM 优先」的出厂默认值：开启。
     *
     * <p>开启后每个听者只听得见一条帽子 BGM：自己的那条绝对优先；
     * 自己没在放的时候，只留**最后响起**的那一条。详见 {@code client.HatMusicPlayer}。
     */
    public static final boolean DEFAULT_OWN_BGM_PRIORITY = true;

    /**
     * 「破防模式」的出厂值：**关**。
     *
     * <p>关着的时候光柱完全走原来的链路（固定伤害 + 百分比 + 多段/压上限），
     * 一点都不去动对方的血量；开着才会在一轮打不到「应该造成的伤害」的 50% 时强写血量。
     */
    public static final boolean DEFAULT_FORCE_BREAK = false;

    /**
     * 「全」三条路线（黑 / 白 / 红）各自的默认帧伤。
     *
     * <p>这三个值只给「全」用（直接取 {@link HatType#routeDamage} 的出厂 12/10/15，不重复写一遍数字）；
     * 三顶原色帽仍走各自的 {@link #damagePerTick}，不受它们影响。
     * 可在调参器的「全帽」页改，或用 {@code /hatmod routedamage} 改。
     */
    public static final float DEFAULT_ROUTE_DAMAGE_BLACK = HatType.routeDamage(HatType.BLACK);
    public static final float DEFAULT_ROUTE_DAMAGE_WHITE = HatType.routeDamage(HatType.WHITE);
    public static final float DEFAULT_ROUTE_DAMAGE_RED = HatType.routeDamage(HatType.RED);

    /**
     * 「强制路线」的默认值：{@code random}，也就是正常玩法里的「每轮随机取一条」。
     *
     * <p>调试时把它设成 {@code black} / {@code white} / {@code red}，「全」就会**每轮都走那一条**，
     * 用来逐条核对时长、BGM、粒子颜色和帽子渲染颜色（否则要等随机轮到）。
     */
    public static final String ROUTE_RANDOM = "random";

    /** 界面里允许的范围，服务端也会按同一套夹一遍（防手改配置文件写出离谱数值）。 */
    public static final int MIN_TICKS = 0;
    public static final int MAX_TICKS = 72000;
    public static final float MIN_DAMAGE = 0.0F;
    /**
     * 伤害上限。原来是 10 万，打 21 亿血的目标得打两万多下 —— 现在放到 21 亿
     * （{@code Integer.MAX_VALUE} 那一档），一帧就能抹平原版/模组里任何一个血量条。
     * 注意它只是**配置夹取**的范围，默认伤害值没动。
     */
    public static final float MAX_DAMAGE = 2100000000.0F;
    public static final float MAX_RATIO = 1.0F;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type FILE_TYPE = new TypeToken<ConfigFile>() { }.getType();

    private static final Map<HatType, Entry> ENTRIES = new EnumMap<>(HatType.class);
    private static boolean loaded;
    private static boolean warnedMissingType;

    /** 全局开关「自己的 BGM 优先」的当前值（服务端权威）。 */
    private static boolean ownBgmPriority = DEFAULT_OWN_BGM_PRIORITY;
    /** 「破防模式」当前值（服务端权威）。 */
    private static boolean forceBreak = DEFAULT_FORCE_BREAK;
    /** 「实体表接管」当前值（服务端权威）：被别人从实体管理器里挖走时，自己塞回去。 */
    private static boolean entityLock;

    /** 脑蚀 P 链路运行期间，范围内所有非友方单位都算敌人。 */
    private static boolean brainrotLockAll = true;
    /** 锁敌时把玩家也算进去。 */
    private static boolean lockPlayers;
    /** 对玩家真的造成伤害 / 挂效果。 */
    private static boolean affectPlayers;

    /** 「全」三条路线的帧伤当前值（服务端权威）。 */
    private static float routeDamageBlack = DEFAULT_ROUTE_DAMAGE_BLACK;
    private static float routeDamageWhite = DEFAULT_ROUTE_DAMAGE_WHITE;
    private static float routeDamageRed = DEFAULT_ROUTE_DAMAGE_RED;

    /** 「脑蚀」每帧伤害倍率当前值（服务端权威）。 */
    private static float brainrotDamageScale = DEFAULT_BRAINROT_DAMAGE_SCALE;

    /** 「脑蚀」百分比伤害倍率当前值（服务端权威）。 */
    private static float brainrotPercentScale = DEFAULT_BRAINROT_PERCENT_SCALE;

    /** 散养 Teto 每次闲聊消失的概率当前值（服务端权威，0..1）。 */
    private static float tetoVanishChance = DEFAULT_TETO_VANISH_CHANCE;

    /** 「强制路线」当前值；{@link #ROUTE_RANDOM} 表示正常随机。 */
    private static String forcedRoute = ROUTE_RANDOM;

    /** 开局要不要扫各模组 jar 里的 Unsafe 痕迹（{@link ModTamperScan}）。 */
    private static boolean tamperScan = true;

    private HatSettings() {
    }

    /**
     * 磁盘上的整份配置。
     *
     * <p>{@code version} 缺失时 Gson 会留成 0，正好比 {@link #CONFIG_VERSION} 小，
     * 于是**老格式的文件（带 version 之前写出来的那些）会被当成旧版本丢弃**，
     * 自动换用新的默认数值。
     */
    public static final class ConfigFile {
        public int version;
        public Map<String, Entry> hats;

        /**
         * 全局开关：「自己的 BGM 优先」。
         *
         * <p>字段带初始化值，而 {@code ConfigFile} 有隐式无参构造，Gson 会走它 ——
         * 所以老配置文件里没有这个字段时会自动落成 {@code true}（出厂默认），
         * <b>不需要为此把 {@link #CONFIG_VERSION} 加一</b>，也就不会把用户调好的时长冲掉。
         */
        public boolean ownBgmPriority = DEFAULT_OWN_BGM_PRIORITY;
        /** 「破防模式」：老配置里没有这个字段时 Gson 会留成 false，正好就是出厂值。 */
        public boolean forceBreak = DEFAULT_FORCE_BREAK;
        public boolean entityLock;
        public boolean brainrotLockAll = true;
        public boolean lockPlayers;
        public boolean affectPlayers;

        /**
         * 「全」三条路线各自的帧伤（出厂 12 / 10 / 15）。
         *
         * <p>与 {@link #ownBgmPriority} 同理：字段带初始化值，老配置文件里没有它们时
         * 会自动落成出厂值，<b>不需要为此加 {@link #CONFIG_VERSION}</b>，
         * 也就不会把用户已经调好的时长/伤害冲掉。
         */
        public float routeDamageBlack = DEFAULT_ROUTE_DAMAGE_BLACK;
        public float routeDamageWhite = DEFAULT_ROUTE_DAMAGE_WHITE;
        public float routeDamageRed = DEFAULT_ROUTE_DAMAGE_RED;

        /** 「脑蚀」每帧伤害倍率（出厂 1.5）。老配置里没有这个字段时自动落成出厂值。 */
        public float brainrotDamageScale = DEFAULT_BRAINROT_DAMAGE_SCALE;

        /** 「脑蚀」百分比伤害倍率（出厂 0.5）。老配置里没有这个字段时自动落成出厂值。 */
        public float brainrotPercentScale = DEFAULT_BRAINROT_PERCENT_SCALE;

        /** 散养 Teto 每次闲聊消失的概率（出厂 0.001 = 0.1%）。 */
        public float tetoVanishChance = DEFAULT_TETO_VANISH_CHANCE;

        /**
         * 「强制路线」：{@link #ROUTE_RANDOM}（默认，正常随机）或 {@code black} / {@code white} / {@code red}。
         *
         * <p>调试用：设成某条颜色后，「全」每轮都走那一条，方便逐条核对时长 / BGM / 颜色。
         */
        public String forcedRoute = ROUTE_RANDOM;

        /** 开局扫描（{@link ModTamperScan}）开关，出厂开。 */
        public boolean tamperScan = true;

        /**
         * 调试开关「自动打拍」的玩家名单（UUID 字符串）。
         *
         * <p>按玩家记而不是全局：联机时谁打开就只影响谁。字段带初始化值，
         * 老配置里没有它时会落成空表，<b>不需要动 {@link #CONFIG_VERSION}</b>。
         */
        public java.util.List<String> autoTapPlayers = new java.util.ArrayList<>();

        /**
         * 已经**完整打过至少一轮**脑蚀的玩家（UUID 字符串）。
         *
         * <p>台词里有些话（「你的手比上一次听话了」）默认玩家玩过 —— 第一次玩的人听到
         * 「上一次」会很出戏。这份名单就是判断「他到底有没有上一次」用的。
         */
        public java.util.List<String> playedBrainrot = new java.util.ArrayList<>();

        /** 见过散养 Teto 的玩家（UUID 字符串）。「又见面了？」只对他们说。 */
        public java.util.List<String> metTeto = new java.util.ArrayList<>();

        /**
         * 每个玩家**成功召唤** Teto 的次数（UUID 字符串 -> 次数）。
         *
         * <p>帽子链路和刷怪蛋都算，但只算「真的放进去了」的那些：链路那边 {@code spawnTetos}
         * 一只都没放成就不是召唤，刷怪蛋那边要等她认了主才算数。台词按这个分层
         * （见 {@code TetoSpeech}）。
         */
        public java.util.Map<String, Integer> tetoSummons = new java.util.LinkedHashMap<>();

        /** 每个玩家喂过她几次（UUID 字符串 -> 次数）。喂食台词按这次数分阶段。 */
        public java.util.Map<String, Integer> tetoFeeds = new java.util.LinkedHashMap<>();

        /** 每个玩家最后一次喂的是什么（UUID 字符串 -> 物品的显示名）。 */
        public java.util.Map<String, String> tetoLastFood = new java.util.LinkedHashMap<>();

        /** 每个玩家最后一次喂的**难吃**东西（腐肉那类，UUID 字符串 -> 显示名）。 */
        public java.util.Map<String, String> tetoLastBadFood = new java.util.LinkedHashMap<>();

        /** 每个玩家和她一起经历过的事（UUID 字符串 -> 记忆标记列表），见 {@code TetoSpeech}。 */
        public java.util.Map<String, java.util.List<String>> tetoMemories = new java.util.LinkedHashMap<>();

        /** 每个玩家已经说过的记忆台词（UUID 字符串 -> 语言键列表）：同一句一辈子只说一次。 */
        public java.util.Map<String, java.util.List<String>> tetoSpoken = new java.util.LinkedHashMap<>();

        /** 每个玩家上一次**成功计数**的召唤发生在什么时候（UUID 字符串 -> 游戏刻）。 */
        public java.util.Map<String, Long> tetoLastSummon = new java.util.LinkedHashMap<>();

        /** 每个玩家的 Teto 心情值（0..100，出厂 50），见 {@code TetoMood}。 */
        public java.util.Map<String, Integer> tetoMood = new java.util.LinkedHashMap<>();
        /** 心情值上次结算的时刻（UUID 字符串 -> 游戏刻）。 */
        public java.util.Map<String, Long> tetoMoodAt = new java.util.LinkedHashMap<>();
    }

    /** 一顶帽子的一组参数。字段 public、有默认构造，是给 Gson 直接读写用的。 */
    public static final class Entry {
        public int chargeTicks;
        public int flashTicks;
        public float damagePerTick;
        public float healthDamageRatio;
        public float healthDamageFloor;
        public String damageType;

        Entry() {
        }

        Entry(HatType type) {
            reset(type);
        }

        void reset(HatType type) {
            this.chargeTicks = type.chargeTicks();
            this.flashTicks = type.flashTicks();
            this.damagePerTick = type.damagePerTick();
            this.healthDamageRatio = DEFAULT_HEALTH_DAMAGE_RATIO;
            this.healthDamageFloor = DEFAULT_HEALTH_DAMAGE_FLOOR;
            this.damageType = DEFAULT_DAMAGE_TYPE;
        }

        /** 只读拷贝：发给客户端用，避免对面改到服务端这份。 */
        public Entry copy() {
            Entry copy = new Entry();
            copy.chargeTicks = this.chargeTicks;
            copy.flashTicks = this.flashTicks;
            copy.damagePerTick = this.damagePerTick;
            copy.healthDamageRatio = this.healthDamageRatio;
            copy.healthDamageFloor = this.healthDamageFloor;
            copy.damageType = this.damageType;
            return copy;
        }

        void normalize(HatType type) {
            this.chargeTicks = clamp(this.chargeTicks, MIN_TICKS, MAX_TICKS);
            this.flashTicks = clamp(this.flashTicks, MIN_TICKS, MAX_TICKS);
            this.damagePerTick = clamp(this.damagePerTick, MIN_DAMAGE, MAX_DAMAGE);
            this.healthDamageRatio = clamp(this.healthDamageRatio, 0.0F, MAX_RATIO);
            this.healthDamageFloor = clamp(this.healthDamageFloor, 0.0F, MAX_RATIO);
            if (this.damageType == null || this.damageType.isBlank()) {
                this.damageType = DEFAULT_DAMAGE_TYPE;
            } else {
                this.damageType = this.damageType.trim();
            }
        }
    }

    // ------------------------------------------------------------------
    // 读取（游戏逻辑走这里）
    // ------------------------------------------------------------------

    public static synchronized Entry get(HatType type) {
        ensureLoaded();
        Entry entry = ENTRIES.get(type);
        if (entry == null) {
            entry = new Entry(type);
            ENTRIES.put(type, entry);
        }
        return entry;
    }

    public static int chargeTicks(HatType type) {
        return get(type).chargeTicks;
    }

    public static int flashTicks(HatType type) {
        return get(type).flashTicks;
    }

    public static float damagePerTick(HatType type) {
        return get(type).damagePerTick;
    }

    public static float healthDamageRatio(HatType type) {
        return get(type).healthDamageRatio;
    }

    public static float healthDamageFloor(HatType type) {
        return get(type).healthDamageFloor;
    }

    public static String damageTypeId(HatType type) {
        return get(type).damageType;
    }

    /** 「自己的 BGM 优先」当前是否开启。 */
    public static synchronized boolean ownBgmPriority() {
        ensureLoaded();
        return ownBgmPriority;
    }

    /**
     * 「全」走某条路线时的光柱帧伤。
     *
     * <p>三顶原色帽不走这里（它们用各自的 {@link #damagePerTick}）；「全」每轮取到哪条路线
     * 就用哪条的值 —— 见 {@code HatAbilities.damageBeam}。入参不是三条路线之一时退回白帽那份。
     */
    public static synchronized float routeDamage(HatType route) {
        ensureLoaded();
        if (route == HatType.BLACK) {
            return routeDamageBlack;
        }
        if (route == HatType.RED) {
            return routeDamageRed;
        }
        return routeDamageWhite;
    }

    /**
     * 「强制路线」：{@link #ROUTE_RANDOM} 或 black/white/red；非 {@link #ROUTE_RANDOM}
     * 表示「全」每轮都固定走这一条（调试用）。
     */
    public static synchronized String forcedRoute() {
        ensureLoaded();
        return forcedRoute;
    }

    /** 强制路线对应的 {@link HatType}；没强制（随机）时返回 {@code null}。 */
    public static HatType forcedRouteType() {
        String forced = forcedRoute();
        if (forced == null || ROUTE_RANDOM.equals(forced)) {
            return null;
        }
        for (HatType type : HatType.values()) {
            if (type.id().equals(forced)) {
                return type;
            }
        }
        return null;
    }

    /** 「脑蚀」每帧伤害倍率（乘在黑帽那份帧伤上）。 */
    public static synchronized float brainrotDamageScale() {
        ensureLoaded();
        return brainrotDamageScale;
    }

    /** 改「脑蚀」每帧伤害倍率（调参器「脑蚀」页）。 */
    public static synchronized void setBrainrotDamageScale(float value) {
        ensureLoaded();
        brainrotDamageScale = clamp(value, MIN_DAMAGE, MAX_DAMAGE);
        save();
    }

    /** 「脑蚀」百分比伤害倍率（乘在每刻「当前生命百分比」那一层上）。 */
    public static synchronized float brainrotPercentScale() {
        ensureLoaded();
        return brainrotPercentScale;
    }

    /** 改「脑蚀」百分比伤害倍率（调参器「脑蚀」页第二个框）。0 = 完全没有百分比伤害。 */
    public static synchronized void setBrainrotPercentScale(float value) {
        ensureLoaded();
        brainrotPercentScale = clamp(value, 0.0F, 3.0F);
        save();
    }

    /** 散养 Teto 每次闲聊消失的概率（0..1）。 */
    public static synchronized float tetoVanishChance() {
        ensureLoaded();
        return tetoVanishChance;
    }

    /** 改散养 Teto 每次闲聊消失的概率（调参器「脑蚀」页）。1 = 必走，用来复现。 */
    public static synchronized void setTetoVanishChance(float value) {
        ensureLoaded();
        tetoVanishChance = clamp(value, 0.0F, 1.0F);
        save();
    }

    /** 改「全」三条路线的帧伤（调参器「全帽」页 / 命令调用）。 */
    public static synchronized void setRouteDamage(float black, float white, float red) {
        ensureLoaded();
        routeDamageBlack = clamp(black, MIN_DAMAGE, MAX_DAMAGE);
        routeDamageWhite = clamp(white, MIN_DAMAGE, MAX_DAMAGE);
        routeDamageRed = clamp(red, MIN_DAMAGE, MAX_DAMAGE);
        save();
    }

    /**
     * 改「强制路线」。
     *
     * @param forced {@link #ROUTE_RANDOM} 或 black/white/red；其它值一律当成随机
     */
    public static synchronized void setForcedRoute(String forced) {
        ensureLoaded();
        forcedRoute = normalizeForcedRoute(forced);
        save();
    }

    /** 把外部传进来的字符串归一成 {@code random}/black/white/red。 */
    private static String normalizeForcedRoute(String forced) {
        if (forced == null) {
            return ROUTE_RANDOM;
        }
        String trimmed = forced.trim().toLowerCase(java.util.Locale.ROOT);
        for (HatType type : HatType.values()) {
            if (type.id().equals(trimmed) || type.id().equals(trimmed + "_hat")) {
                return type.id();
            }
        }
        return ROUTE_RANDOM;
    }

    // ------------------------------------------------------------------
    // 修改 / 落盘
    // ------------------------------------------------------------------

    /** 界面保存时调用：服务端夹一遍范围、写进内存、立刻落盘。 */
    public static synchronized void apply(HatType type, int chargeTicks, int flashTicks,
                                          float damagePerTick, float healthDamageRatio,
                                          float healthDamageFloor, String damageType) {
        Entry entry = get(type);
        entry.chargeTicks = chargeTicks;
        entry.flashTicks = flashTicks;
        entry.damagePerTick = damagePerTick;
        entry.healthDamageRatio = healthDamageRatio;
        entry.healthDamageFloor = healthDamageFloor;
        entry.damageType = damageType;
        entry.normalize(type);
        save();
    }

    /** 改「自己的 BGM 优先」这个全局开关（调参器保存时一并生效）。 */
    public static synchronized void setOwnBgmPriority(boolean value) {
        ensureLoaded();
        ownBgmPriority = value;
        save();
    }

    /** 「破防模式」开着没有（默认关）。 */
    public static synchronized boolean forceBreak() {
        ensureLoaded();
        return forceBreak;
    }

    /** 改「破防模式」这个全局开关（调参器保存时一并生效）。 */
    public static synchronized void setForceBreak(boolean value) {
        ensureLoaded();
        forceBreak = value;
        com.hatmod.guard.HatGuard.syncSwitch();
        save();
    }

    /**
     * 「实体表接管」开着没有（默认关）。
     *
     * <p>开了之后，破防模式下被外部从实体管理器里挖走的虚影 / 铁托，会被按
     * {@code PersistentEntitySectionManager} 的内部接口直接塞回去 —— 这是常见的一招，
     * 也是唯一能扛住「改实体表」类删除的办法。代价是动了原版内部结构，所以默认关。
     */
    public static synchronized boolean brainrotLockAll() {
        return brainrotLockAll;
    }

    public static synchronized void setBrainrotLockAll(boolean value) {
        brainrotLockAll = value;
        save();
    }

    public static synchronized boolean lockPlayers() {
        return lockPlayers;
    }

    public static synchronized void setLockPlayers(boolean value) {
        lockPlayers = value;
        save();
    }

    public static synchronized boolean affectPlayers() {
        return affectPlayers;
    }

    public static synchronized void setAffectPlayers(boolean value) {
        affectPlayers = value;
        save();
    }

    public static synchronized boolean entityLock() {
        ensureLoaded();
        return entityLock;
    }

    /** 改「实体表接管」（写进 {@code config/hatmod.json}）。 */
    public static synchronized void setEntityLock(boolean value) {
        ensureLoaded();
        entityLock = value;
        save();
    }

    /** 开局扫描开关（默认开）。 */
    public static synchronized boolean tamperScan() {
        ensureLoaded();
        return tamperScan;
    }

    /** 改开局扫描开关。 */
    public static synchronized void setTamperScan(boolean value) {
        ensureLoaded();
        tamperScan = value;
        save();
    }


    // ------------------------------------------------------------------
    // 调试开关（落盘，重启后还在）
    // ------------------------------------------------------------------

    /** 磁盘上「自动打拍」的玩家名单。 */
    private static final java.util.Set<java.util.UUID> DEBUG_AUTO_TAP = new java.util.HashSet<>();

    /** 这个玩家上次退出前有没有开着「自动打拍」。 */
    public static synchronized boolean debugAutoTap(java.util.UUID player) {
        ensureLoaded();
        return DEBUG_AUTO_TAP.contains(player);
    }

    /** 记下「自动打拍」开关并立刻落盘（进服时由 {@code BrainrotCycle} 恢复）。 */
    public static synchronized void setDebugAutoTap(java.util.UUID player, boolean on) {
        ensureLoaded();
        if (on) {
            DEBUG_AUTO_TAP.add(player);
        } else {
            DEBUG_AUTO_TAP.remove(player);
        }
        save();
    }

    /** 打完过至少一轮脑蚀的玩家（落盘，重启后还在）。 */
    private static final java.util.Set<java.util.UUID> BRAINROT_PLAYED = new java.util.HashSet<>();

    /** 见过散养 Teto 的玩家（落盘，重启后还在）。 */
    private static final java.util.Set<java.util.UUID> MET_TETO = new java.util.HashSet<>();

    /** 这个玩家以前见过散养的她吗（决定「又见面了？」能不能说）。 */
    public static synchronized boolean tetoMet(java.util.UUID player) {
        ensureLoaded();
        return MET_TETO.contains(player);
    }

    /** 记下「见过她了」，并立刻落盘。 */
    public static synchronized void markTetoMet(java.util.UUID player) {
        ensureLoaded();
        if (MET_TETO.add(player)) {
            save();
        }
    }

    // ------------------------------------------------------------------
    // Teto 的「熟悉度」与「记忆」
    //
    // 她要记得这是第几次被叫出来、也记得一起经历过什么（见 TetoSpeech）。
    // 和 metTeto 一样按玩家记、落盘，重进游戏还在。
    // ------------------------------------------------------------------

    /** 玩家 -> 成功召唤 Teto 的次数。 */
    private static final Map<java.util.UUID, Integer> TETO_SUMMONS = new java.util.HashMap<>();
    private static final Map<java.util.UUID, Integer> TETO_FEEDS = new java.util.HashMap<>();
    private static final Map<java.util.UUID, String> TETO_LAST_FOOD = new java.util.HashMap<>();
    private static final Map<java.util.UUID, String> TETO_LAST_BAD_FOOD = new java.util.HashMap<>();

    /** 玩家 -> 和她一起经历过的事（{@code chain_end} / {@code fed} 之类）。 */
    private static final Map<java.util.UUID, java.util.Set<String>> TETO_MEMORIES = new java.util.HashMap<>();

    /** 玩家 -> 已经说过的记忆台词（每句一辈子一次）。 */
    private static final Map<java.util.UUID, java.util.Set<String>> TETO_SPOKEN = new java.util.HashMap<>();

    /** 玩家 -> 上一次计数的召唤发生在哪个游戏刻（防刷次数用）。 */
    private static final Map<java.util.UUID, Long> TETO_LAST_SUMMON = new java.util.HashMap<>();

    /**
     * 两次**计数**之间至少要隔多久（刻）：一个完整的游戏日（24000 刻）。
     *
     * <p>只从第 2 次开始卡：第 1 次 → 第 2 次不限，之后每往上涨一级都要再等一天。
     * 期间玩家照样能把她叫出来，只是**次数不涨**、台词也不会进下一层。
     */
    public static final long TETO_SUMMON_INTERVAL = 24000L;

    /** 玩家 -> 脑蚀被磨穿的那一刻（游戏刻）。只用来分辨「自己摘」还是「被敌人弄掉」，不存盘。 */
    private static final Map<java.util.UUID, Long> HAT_BROKEN_AT = new java.util.HashMap<>();

    /** 记下「脑蚀刚被磨穿」（由 {@code HatItems} 的 damageItem 调用）。 */
    public static synchronized void markHatBroken(java.util.UUID player, long now) {
        HAT_BROKEN_AT.entrySet().removeIf(entry -> now - entry.getValue() > 200L);
        HAT_BROKEN_AT.put(player, now);
    }

    /** 最近（100 刻内）这个玩家的脑蚀是不是被磨穿的 —— 用来区分「自己摘」和「被敌人弄掉」。 */
    public static synchronized boolean hatBrokeRecently(java.util.UUID player, long now) {
        Long at = HAT_BROKEN_AT.get(player);
        return at != null && now - at >= 0L && now - at <= 100L;
    }

    /** 玩家 -> 她最后一次被**主人本人**打到是什么时候（游戏刻）。只放内存：重进就当她忘了。 */
    private static final Map<java.util.UUID, Long> TETO_HIT_AT = new java.util.HashMap<>();

    /** 打完她之后，这句话的有效期（刻）：一个游戏日。 */
    public static final long TETO_HIT_MEMORY_TICKS = 24000L;

    /** 记一笔「主人刚才打了她」。 */
    public static synchronized void markTetoHit(java.util.UUID player, long now) {
        TETO_HIT_AT.entrySet().removeIf(entry -> now - entry.getValue() > TETO_HIT_MEMORY_TICKS);
        TETO_HIT_AT.put(player, now);
    }

    /**
     * 她最近（一个游戏日内）被主人本人打过吗 —— 「你这次不会又打我吧」用它判定。
     *
     * <p>不做成永久记忆：被怪打过、被别的玩家打过都不该算到她头上；
     * 真打过一次也确实该记一阵子，但一个游戏日之后她就不提了。
     */
    public static synchronized boolean tetoHitFresh(java.util.UUID player, long now) {
        Long at = TETO_HIT_AT.get(player);
        return at != null && now - at >= 0L && now - at <= TETO_HIT_MEMORY_TICKS;
    }

    /** 这个玩家成功召唤过她几次（0 = 还没见过）。 */
    public static synchronized int tetoSummons(java.util.UUID player) {
        ensureLoaded();
        Integer count = TETO_SUMMONS.get(player);
        return count == null ? 0 : count;
    }

    /**
     * 记一次成功召唤，返回**记完之后**的次数（台词要按「第几次」分层）。
     *
     * <p>防刷：从第 2 次起，距上一次计数不满 {@link #TETO_SUMMON_INTERVAL}（一个游戏日）
     * 就**不计数**，原样返回当前次数 —— 召唤本身照常发生，只是不推进台词进度。
     *
     * @param now 当前游戏刻（{@code level.getGameTime()}）
     */
    public static synchronized int addTetoSummon(java.util.UUID player, long now) {
        ensureLoaded();
        int current = tetoSummons(player);
        if (current >= 2) {
            Long last = TETO_LAST_SUMMON.get(player);
            if (last != null && now - last < TETO_SUMMON_INTERVAL) {
                return current;
            }
        }
        int next = current + 1;
        TETO_SUMMONS.put(player, next);
        TETO_LAST_SUMMON.put(player, now);
        save();
        return next;
    }

    /** 这个玩家喂过她几次（0 = 还没喂过）。 */
    public static synchronized int tetoFeeds(java.util.UUID player) {
        ensureLoaded();
        Integer count = TETO_FEEDS.get(player);
        return count == null ? 0 : count;
    }

    /**
     * 记一次投喂，返回**记完之后**的次数。
     *
     * <p>不管喂的是什么、隔了多久，喂一口就算一次 —— 喂食台词按「第一次 / 第二次 / 第三次往后」
     * 分三套说法，不然连着喂十次都是同一句，她会像个复读机。
     */
    public static synchronized int addTetoFeed(java.util.UUID player) {
        ensureLoaded();
        int next = tetoFeeds(player) + 1;
        TETO_FEEDS.put(player, next);
        save();
        return next;
    }

    /** 记住最后一次喂的是什么（只用于她自己提起来，不参与任何判定）。 */
    public static synchronized void rememberTetoFood(java.util.UUID player, String name) {
        ensureLoaded();
        if (name == null || name.isEmpty()) {
            return;
        }
        if (!name.equals(TETO_LAST_FOOD.get(player))) {
            TETO_LAST_FOOD.put(player, name);
            save();
        }
    }

    /** 上一次喂的是什么（没喂过就是空串）。 */
    public static synchronized String tetoLastFood(java.util.UUID player) {
        ensureLoaded();
        String name = TETO_LAST_FOOD.get(player);
        return name == null ? "" : name;
    }

    /** 记住最后一次喂的那种**难吃**的东西（腐肉、蜘蛛眼……），只用来让她之后抱怨一句。 */
    public static synchronized void rememberTetoBadFood(java.util.UUID player, String name) {
        ensureLoaded();
        if (name == null || name.isEmpty()) {
            return;
        }
        if (!name.equals(TETO_LAST_BAD_FOOD.get(player))) {
            TETO_LAST_BAD_FOOD.put(player, name);
            save();
        }
    }

    /** 上一次喂的那种难吃的东西（没有就是空串）。 */
    public static synchronized String tetoLastBadFood(java.util.UUID player) {
        ensureLoaded();
        String name = TETO_LAST_BAD_FOOD.get(player);
        return name == null ? "" : name;
    }

    /** 他有没有这段记忆。 */
    public static synchronized boolean tetoMemory(java.util.UUID player, String memory) {
        ensureLoaded();
        java.util.Set<String> set = TETO_MEMORIES.get(player);
        return set != null && set.contains(memory);
    }

    /** 这段记忆的副本（挑台词时遍历用，外面改不动）。 */
    public static synchronized java.util.Set<String> tetoMemories(java.util.UUID player) {
        ensureLoaded();
        java.util.Set<String> set = TETO_MEMORIES.get(player);
        return set == null ? java.util.Collections.emptySet() : new java.util.HashSet<>(set);
    }

    /** 记下一段记忆（已经有了就不再写盘）。 */
    public static synchronized void rememberTeto(java.util.UUID player, String memory) {
        ensureLoaded();
        java.util.Set<String> set = TETO_MEMORIES.computeIfAbsent(player, id -> new java.util.HashSet<>());
        if (set.add(memory)) {
            save();
        }
    }

    /** 这句记忆台词说过了吗（说过就不再重复）。 */
    public static synchronized boolean tetoSpoken(java.util.UUID player, String key) {
        ensureLoaded();
        java.util.Set<String> set = TETO_SPOKEN.get(player);
        return set != null && set.contains(key);
    }

    /** 记下「这句说过了」。 */
    public static synchronized void markTetoSpoken(java.util.UUID player, String key) {
        ensureLoaded();
        java.util.Set<String> set = TETO_SPOKEN.computeIfAbsent(player, id -> new java.util.HashSet<>());
        if (set.add(key)) {
            save();
        }
    }

    /** 这个玩家以前打过脑蚀吗（决定能不能提「上一次」）。 */
    public static synchronized boolean brainrotPlayed(java.util.UUID player) {
        ensureLoaded();
        return BRAINROT_PLAYED.contains(player);
    }

    /** 记下「他打完过一轮」，并立刻落盘。 */
    public static synchronized void markBrainrotPlayed(java.util.UUID player) {
        ensureLoaded();
        if (BRAINROT_PLAYED.add(player)) {
            save();
        }
    }

    /** 恢复这顶帽子的出厂数值（{@link HatType} 里那一份）。 */
    public static synchronized void resetToDefaults(HatType type) {
        get(type).reset(type);
        save();
    }

    /**
     * 丢掉内存里那一份、重新从磁盘读（{@code /hatmod reload} 用）。
     *
     * <p>手改 {@code config/hatmod.json} 之后不想重启游戏就调这个。
     */
    public static synchronized void reload() {
        loaded = false;
        ENTRIES.clear();
        warnedMissingType = false;
        ensureLoaded();
    }

    private static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        ownBgmPriority = DEFAULT_OWN_BGM_PRIORITY;
        forceBreak = DEFAULT_FORCE_BREAK;
        entityLock = false;
        brainrotLockAll = true;
        lockPlayers = false;
        affectPlayers = false;
        routeDamageBlack = DEFAULT_ROUTE_DAMAGE_BLACK;
        routeDamageWhite = DEFAULT_ROUTE_DAMAGE_WHITE;
        routeDamageRed = DEFAULT_ROUTE_DAMAGE_RED;
        forcedRoute = ROUTE_RANDOM;
        brainrotDamageScale = DEFAULT_BRAINROT_DAMAGE_SCALE;
        brainrotPercentScale = DEFAULT_BRAINROT_PERCENT_SCALE;
        tetoVanishChance = DEFAULT_TETO_VANISH_CHANCE;
        tamperScan = true;
        DEBUG_AUTO_TAP.clear();
        BRAINROT_PLAYED.clear();
        MET_TETO.clear();
        TETO_SUMMONS.clear();
        TETO_MEMORIES.clear();
        TETO_SPOKEN.clear();
        TETO_LAST_SUMMON.clear();
        for (HatType type : HatType.values()) {
            ENTRIES.put(type, new Entry(type));
        }

        Path file = file();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            ConfigFile fromDisk = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), FILE_TYPE);
            if (fromDisk == null) {
                return;
            }
            // 版本比当前旧（含根本没有 version 字段的老格式）-> 整份作废，用新默认值。
            // 这样每次调完默认数值都不需要让人手动去 config 目录删文件。
            if (fromDisk.version < CONFIG_VERSION) {
                if (fromDisk.version == CONFIG_VERSION - 1) {
                    // v7 -> v8：上一版的牛仔帽帧伤被整体砍到了十分之一，这里乘回 10 倍。
                    // 破防开关、蓄力/照射时长、强制路线这些设置原样保留，不整份重置。
                    if (fromDisk.hats != null) {
                        for (Entry stored : fromDisk.hats.values()) {
                            if (stored != null) {
                                stored.damagePerTick *= 10.0F;
                            }
                        }
                    }
                    // 路线帧伤如果还是旧的个位数尺度，也一起乘回 10 倍（已经是 12/10/15 的不动）
                    if (fromDisk.routeDamageBlack > 0.0F && fromDisk.routeDamageBlack < 5.0F) {
                        fromDisk.routeDamageBlack *= 10.0F;
                        fromDisk.routeDamageWhite *= 10.0F;
                        fromDisk.routeDamageRed *= 10.0F;
                    }
                    HatMod.LOGGER.info("[HatMod] config/hatmod.json 从版本 {} 升到 {}：帧伤乘回 10 倍，"
                            + "其它设置保持原样。", fromDisk.version, CONFIG_VERSION);
                } else {
                    HatMod.LOGGER.info("[HatMod] config/hatmod.json 版本 {} 落后于 {}，已改用新的默认数值；"
                            + "下一次在调参器里保存会写上新版本。", fromDisk.version, CONFIG_VERSION);
                    return;
                }
            }
            ownBgmPriority = fromDisk.ownBgmPriority;
            forceBreak = fromDisk.forceBreak;
            entityLock = fromDisk.entityLock;
            brainrotLockAll = fromDisk.brainrotLockAll;
            lockPlayers = fromDisk.lockPlayers;
            affectPlayers = fromDisk.affectPlayers;
            routeDamageBlack = clamp(fromDisk.routeDamageBlack, MIN_DAMAGE, MAX_DAMAGE);
            routeDamageWhite = clamp(fromDisk.routeDamageWhite, MIN_DAMAGE, MAX_DAMAGE);
            routeDamageRed = clamp(fromDisk.routeDamageRed, MIN_DAMAGE, MAX_DAMAGE);
            forcedRoute = normalizeForcedRoute(fromDisk.forcedRoute);
            brainrotDamageScale = clamp(fromDisk.brainrotDamageScale, MIN_DAMAGE, MAX_DAMAGE);
            brainrotPercentScale = clamp(fromDisk.brainrotPercentScale, 0.0F, 3.0F);
            tetoVanishChance = clamp(fromDisk.tetoVanishChance, 0.0F, 1.0F);
            MET_TETO.clear();
            if (fromDisk.metTeto != null) {
                for (String raw : fromDisk.metTeto) {
                    try {
                        MET_TETO.add(java.util.UUID.fromString(raw));
                    } catch (IllegalArgumentException ignored) {
                        // 手改坏了就当没这一行
                    }
                }
            }
            TETO_SUMMONS.clear();
            if (fromDisk.tetoSummons != null) {
                for (java.util.Map.Entry<String, Integer> entry : fromDisk.tetoSummons.entrySet()) {
                    try {
                        int count = entry.getValue() == null ? 0 : entry.getValue();
                        TETO_SUMMONS.put(java.util.UUID.fromString(entry.getKey()), Math.max(0, count));
                    } catch (IllegalArgumentException ignored) {
                        // 手改坏了就当没这一行
                    }
                }
            }
            TETO_FEEDS.clear();
            if (fromDisk.tetoFeeds != null) {
                for (java.util.Map.Entry<String, Integer> entry : fromDisk.tetoFeeds.entrySet()) {
                    try {
                        int count = entry.getValue() == null ? 0 : entry.getValue();
                        if (count > 0) {
                            TETO_FEEDS.put(java.util.UUID.fromString(entry.getKey()), count);
                        }
                    } catch (IllegalArgumentException ignored) {
                        // 手改坏了就当没这一行
                    }
                }
            }
            TETO_LAST_FOOD.clear();
            if (fromDisk.tetoLastFood != null) {
                for (java.util.Map.Entry<String, String> entry : fromDisk.tetoLastFood.entrySet()) {
                    try {
                        if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                            TETO_LAST_FOOD.put(java.util.UUID.fromString(entry.getKey()), entry.getValue());
                        }
                    } catch (IllegalArgumentException ignored) {
                        // 同上
                    }
                }
            }
            TETO_LAST_BAD_FOOD.clear();
            if (fromDisk.tetoLastBadFood != null) {
                for (java.util.Map.Entry<String, String> entry : fromDisk.tetoLastBadFood.entrySet()) {
                    try {
                        if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                            TETO_LAST_BAD_FOOD.put(java.util.UUID.fromString(entry.getKey()),
                                    entry.getValue());
                        }
                    } catch (IllegalArgumentException ignored) {
                        // 手改坏了就当没这一行
                    }
                }
            }
            TETO_LAST_SUMMON.clear();
            if (fromDisk.tetoLastSummon != null) {
                for (java.util.Map.Entry<String, Long> entry : fromDisk.tetoLastSummon.entrySet()) {
                    try {
                        long when = entry.getValue() == null ? 0L : entry.getValue();
                        TETO_LAST_SUMMON.put(java.util.UUID.fromString(entry.getKey()), when);
                    } catch (IllegalArgumentException ignored) {
                        // 手改坏了就当没这一行
                    }
                }
            }
            tamperScan = fromDisk.tamperScan;
            TetoMood.restore(fromDisk.tetoMood, fromDisk.tetoMoodAt);
            TETO_MEMORIES.clear();
            if (fromDisk.tetoMemories != null) {
                for (java.util.Map.Entry<String, java.util.List<String>> entry : fromDisk.tetoMemories.entrySet()) {
                    try {
                        java.util.UUID id = java.util.UUID.fromString(entry.getKey());
                        java.util.Set<String> set = new java.util.HashSet<>();
                        if (entry.getValue() != null) {
                            set.addAll(entry.getValue());
                        }
                        if (!set.isEmpty()) {
                            TETO_MEMORIES.put(id, set);
                        }
                    } catch (IllegalArgumentException ignored) {
                        // 同上
                    }
                }
            }
            TETO_SPOKEN.clear();
            if (fromDisk.tetoSpoken != null) {
                for (java.util.Map.Entry<String, java.util.List<String>> entry : fromDisk.tetoSpoken.entrySet()) {
                    try {
                        java.util.UUID id = java.util.UUID.fromString(entry.getKey());
                        java.util.Set<String> set = new java.util.HashSet<>();
                        if (entry.getValue() != null) {
                            set.addAll(entry.getValue());
                        }
                        if (!set.isEmpty()) {
                            TETO_SPOKEN.put(id, set);
                        }
                    } catch (IllegalArgumentException ignored) {
                        // 同上
                    }
                }
            }
            BRAINROT_PLAYED.clear();
            if (fromDisk.playedBrainrot != null) {
                for (String raw : fromDisk.playedBrainrot) {
                    try {
                        BRAINROT_PLAYED.add(java.util.UUID.fromString(raw));
                    } catch (IllegalArgumentException ignored) {
                        // 手改坏了就当没这一行
                    }
                }
            }
            DEBUG_AUTO_TAP.clear();
            if (fromDisk.autoTapPlayers != null) {
                for (String raw : fromDisk.autoTapPlayers) {
                    try {
                        DEBUG_AUTO_TAP.add(java.util.UUID.fromString(raw));
                    } catch (IllegalArgumentException ignored) {
                        // 手改坏了就当没这一行
                    }
                }
            }
            if (fromDisk.hats == null) {
                return;
            }
            for (HatType type : HatType.values()) {
                Entry stored = fromDisk.hats.get(type.id());
                if (stored == null) {
                    continue;
                }
                Entry entry = ENTRIES.get(type);
                entry.chargeTicks = stored.chargeTicks;
                entry.flashTicks = stored.flashTicks;
                entry.damagePerTick = stored.damagePerTick;
                entry.healthDamageRatio = stored.healthDamageRatio;
                // 老配置里的 5% 是旧出厂值（那时这是个硬地板）；现在出厂值是 0，按新语义走
                entry.healthDamageFloor = stored.healthDamageFloor == 0.05F
                        ? DEFAULT_HEALTH_DAMAGE_FLOOR : stored.healthDamageFloor;
                entry.damageType = stored.damageType;
                entry.normalize(type);
            }
        } catch (Exception exception) {
            HatMod.LOGGER.warn("[HatMod] 读取 config/hatmod.json 失败，改用默认数值：{}", exception.toString());
        }
    }

    private static synchronized void save() {
        ConfigFile out = new ConfigFile();
        out.version = CONFIG_VERSION;
        out.ownBgmPriority = ownBgmPriority;
        out.forceBreak = forceBreak;
        out.entityLock = entityLock;
        out.brainrotLockAll = brainrotLockAll;
        out.lockPlayers = lockPlayers;
        out.affectPlayers = affectPlayers;
        out.routeDamageBlack = routeDamageBlack;
        out.routeDamageWhite = routeDamageWhite;
        out.routeDamageRed = routeDamageRed;
        out.forcedRoute = forcedRoute;
        out.brainrotDamageScale = brainrotDamageScale;
        out.brainrotPercentScale = brainrotPercentScale;
        out.tetoVanishChance = tetoVanishChance;
        out.autoTapPlayers = new java.util.ArrayList<>();
        for (java.util.UUID id : DEBUG_AUTO_TAP) {
            out.autoTapPlayers.add(id.toString());
        }
        out.playedBrainrot = new java.util.ArrayList<>();
        for (java.util.UUID id : BRAINROT_PLAYED) {
            out.playedBrainrot.add(id.toString());
        }
        out.metTeto = new java.util.ArrayList<>();
        for (java.util.UUID id : MET_TETO) {
            out.metTeto.add(id.toString());
        }
        out.tetoSummons = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<java.util.UUID, Integer> entry : TETO_SUMMONS.entrySet()) {
            out.tetoSummons.put(entry.getKey().toString(), entry.getValue());
        }
        out.tetoFeeds = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<java.util.UUID, Integer> entry : TETO_FEEDS.entrySet()) {
            out.tetoFeeds.put(entry.getKey().toString(), entry.getValue());
        }
        out.tetoLastFood = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<java.util.UUID, String> entry : TETO_LAST_FOOD.entrySet()) {
            out.tetoLastFood.put(entry.getKey().toString(), entry.getValue());
        }
        out.tetoLastBadFood = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<java.util.UUID, String> entry : TETO_LAST_BAD_FOOD.entrySet()) {
            out.tetoLastBadFood.put(entry.getKey().toString(), entry.getValue());
        }
        out.tetoMemories = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<java.util.UUID, java.util.Set<String>> entry : TETO_MEMORIES.entrySet()) {
            out.tetoMemories.put(entry.getKey().toString(), new java.util.ArrayList<>(entry.getValue()));
        }
        out.tetoSpoken = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<java.util.UUID, java.util.Set<String>> entry : TETO_SPOKEN.entrySet()) {
            out.tetoSpoken.put(entry.getKey().toString(), new java.util.ArrayList<>(entry.getValue()));
        }
        out.tetoLastSummon = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<java.util.UUID, Long> entry : TETO_LAST_SUMMON.entrySet()) {
            out.tetoLastSummon.put(entry.getKey().toString(), entry.getValue());
        }
        out.tamperScan = tamperScan;
        out.tetoMood = TetoMood.snapshot();
        out.tetoMoodAt = TetoMood.snapshotTimes();
        out.hats = new LinkedHashMap<>();
        for (HatType type : HatType.values()) {
            Entry entry = ENTRIES.get(type);
            if (entry != null) {
                out.hats.put(type.id(), entry);
            }
        }
        try {
            Path file = file();
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(out, FILE_TYPE), StandardCharsets.UTF_8);
            HatMod.LOGGER.info("[HatMod] 参数已写入 {}", file.toAbsolutePath());
        } catch (IOException exception) {
            HatMod.LOGGER.warn("[HatMod] 写入 config/hatmod.json 失败：{}", exception.toString());
        }
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("hatmod.json");
    }

    // ------------------------------------------------------------------
    // 伤害类型
    // ------------------------------------------------------------------

    /**
     * 按配置的类型 id 造一个伤害源，造成者记成戴帽者（击杀算他的）。
     *
     * <p>查不到就退回到原版 {@code minecraft:generic_kill} —— 它自带
     * {@code bypasses_armor / bypasses_invulnerability / bypasses_resistance}，
     * 「无视一切减伤的强制伤害」这个性质一致。之所以不全抛异常：模组自带数据包理论上可能没被
     * 注册（Forge 那边实测出现过 {@code Missing data pack mod:hatmod}），
     * 而查表失败绝不该把服务端从「玩家 tick」里崩掉。
     */
    public static DamageSource damageSource(ServerLevel level, LivingEntity wearer, HatType type) {
        Registry<DamageType> registry = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
        String id = damageTypeId(type);
        ResourceLocation key = ResourceLocation.tryParse(id);
        Holder.Reference<DamageType> holder = key == null
                ? null
                : registry.getHolder(ResourceKey.create(Registries.DAMAGE_TYPE, key)).orElse(null);
        if (holder == null) {
            holder = registry.getHolderOrThrow(DamageTypes.GENERIC_KILL);
            warnMissingTypeOnce(id);
        }
        return new DamageSource(holder, wearer);
    }

    private static void warnMissingTypeOnce(String id) {
        if (warnedMissingType) {
            return;
        }
        warnedMissingType = true;
        HatMod.LOGGER.warn("[HatMod] 伤害类型 {} 不在注册表里（模组自带数据包没被加载？），"
                + "光柱伤害已回退到 minecraft:generic_kill。数值不受影响，只是死亡信息不同。", id);
    }

    /**
     * 服务端启动时报一次每顶帽子配置的伤害类型到底有没有就绪 ——
     * 看日志就能判断是不是数据包的问题，不用靠崩服来发现。
     */
    public static void reportAvailability(RegistryAccess access) {
        Registry<DamageType> registry = access.registryOrThrow(Registries.DAMAGE_TYPE);
        for (HatType type : HatType.values()) {
            String id = damageTypeId(type);
            ResourceLocation key = ResourceLocation.tryParse(id);
            boolean ready = key != null
                    && registry.getHolder(ResourceKey.create(Registries.DAMAGE_TYPE, key)).isPresent();
            if (ready) {
                HatMod.LOGGER.info("[HatMod] {} 的伤害类型 {} 已就绪", type.id(), id);
            } else {
                HatMod.LOGGER.warn("[HatMod] !! {} 的伤害类型 {} 未注册，光柱将回退到 minecraft:generic_kill",
                        type.id(), id);
            }
        }
        HatMod.LOGGER.info("[HatMod] 参数文件：{}", file().toAbsolutePath());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        if (Float.isNaN(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }
}
