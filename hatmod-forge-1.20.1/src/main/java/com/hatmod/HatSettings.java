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
    public static final int CONFIG_VERSION = 6;

    /** 光柱每刻额外造成「目标当前生命 × 这个比例」的伤害。 */
    public static final float DEFAULT_HEALTH_DAMAGE_RATIO = 0.05F;
    /** 目标血量低于「最大生命 × 这个比例」后不再扣附加伤害（避免 setHealth 把目标压到 0 血不死）。 */
    public static final float DEFAULT_HEALTH_DAMAGE_FLOOR = 0.05F;
    /** 默认伤害类型：本模组的自定义类型。 */
    public static final String DEFAULT_DAMAGE_TYPE = "hatmod:flash";

    /**
     * 「自己的 BGM 优先」的出厂默认值：开启。
     *
     * <p>开启后每个听者只听得见一条帽子 BGM：自己的那条绝对优先；
     * 自己没在放的时候，只留**最后响起**的那一条。详见 {@code client.HatMusicPlayer}。
     */
    public static final boolean DEFAULT_OWN_BGM_PRIORITY = true;

    /** 界面里允许的范围，服务端也会按同一套夹一遍（防手改配置文件写出离谱数值）。 */
    public static final int MIN_TICKS = 0;
    public static final int MAX_TICKS = 72000;
    public static final float MIN_DAMAGE = 0.0F;
    public static final float MAX_DAMAGE = 100000.0F;
    public static final float MAX_RATIO = 1.0F;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type FILE_TYPE = new TypeToken<ConfigFile>() { }.getType();

    private static final Map<HatType, Entry> ENTRIES = new EnumMap<>(HatType.class);
    private static boolean loaded;
    private static boolean warnedMissingType;

    /** 全局开关「自己的 BGM 优先」的当前值（服务端权威）。 */
    private static boolean ownBgmPriority = DEFAULT_OWN_BGM_PRIORITY;

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
                HatMod.LOGGER.info("[HatMod] config/hatmod.json 版本 {} 落后于 {}，已改用新的默认数值；"
                        + "下一次在调参器里保存会写上新版本。", fromDisk.version, CONFIG_VERSION);
                return;
            }
            ownBgmPriority = fromDisk.ownBgmPriority;
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
                entry.healthDamageFloor = stored.healthDamageFloor;
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
