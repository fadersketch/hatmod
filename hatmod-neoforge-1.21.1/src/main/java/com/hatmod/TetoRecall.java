package com.hatmod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 跨存档记忆：她记得你们一起待过几个世界、多久没见、你死过几次、你最爱戴哪顶帽子、
 * 上次让她跳的是哪支舞。
 *
 * <p><b>为什么另开一个文件</b>：这些要跨存档保留（{@code config/} 本来就不属于任何存档），
 * 但和 {@code hatmod.json} 里那堆玩法参数不是一回事 —— 单独一份
 * {@code config/hatmod-teto-recall.json}，调坏了删掉即可，也不会被「版本对不上就整份作废」
 * 的规则顺手清空。
 *
 * <p><b>怎么算「换了个世界」</b>：存档种子 + 存档名。同一个存档里重进、换维度、重开游戏都不算；
 * 复制存档改个名字算新的一界（对她来说确实是一个新天空）。
 */
public final class TetoRecall {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "hatmod-teto-recall.json";

    /** 一天 / 一周 / 一个月 / 一年（毫秒），用来把「多久没见」分档。 */
    private static final long DAY = 24L * 60L * 60L * 1000L;
    private static final long WEEK = 7L * DAY;
    private static final long MONTH = 30L * DAY;
    private static final long YEAR = 365L * DAY;

    /** 玩家 -> 跨存档的那点记忆。字段 public + 默认构造，给 Gson 直接读写。 */
    public static final class Player {
        /** 一起待过几个世界。 */
        public int worlds;
        /** 上一个世界的标识（种子|存档名）。 */
        public String worldKey = "";
        /** 上次见到她的真实时间（epoch 毫秒）。 */
        public long lastSeen;
        /** 上次让她跳的那套动作（0 = 原版）。 */
        public int lastAction;
        /** 死在她面前的次数。 */
        public int deaths;
        /** 上次死在哪一维。 */
        public String lastDeathDim = "";
        /** 每种帽子戴过几次（HatType 名字 -> 次数）。 */
        public Map<String, Integer> hats = new LinkedHashMap<>();
    }

    /** 落盘用的外壳。 */
    private static final class Save {
        public Map<String, Player> players = new LinkedHashMap<>();
    }

    private static final Map<UUID, Player> PLAYERS = new LinkedHashMap<>();
    private static boolean loaded;

    private TetoRecall() {
    }

    // ------------------------------------------------------------------
    // 认主那一刻：数世界、算间隔、把上次那支舞捡回来
    // ------------------------------------------------------------------

    /**
     * 她第一次在这次召唤里认了主时调用。
     *
     * <p>说了话会直接播出去（跨存档的东西要带参数，交给这里说最省事）。
     *
     * @return 说过了返回 {@code true}，调用方就不用再补原来的招呼
     */
    static boolean onMeet(ServerLevel level, Teto teto, LivingEntity owner) {
        if (level == null || owner == null || owner.getUUID() == null) {
            return false;
        }
        UUID id = owner.getUUID();
        ensureLoaded();
        Player p = PLAYERS.computeIfAbsent(id, key -> new Player());
        String key = worldKey(level);
        long now = System.currentTimeMillis();

        // 第一次见到她（这个玩家从没被记过）：只把基线写下来，招呼还是原来那句
        if (p.worlds <= 0) {
            p.worlds = 1;
            p.worldKey = key;
            p.lastSeen = now;
            applyRememberedAction(teto, p);
            save();
            return false;
        }

        boolean newWorld = !key.equals(p.worldKey);
        long gap = p.lastSeen <= 0L ? 0L : Math.max(0L, now - p.lastSeen);
        if (newWorld) {
            p.worlds++;
            p.worldKey = key;
        }
        p.lastSeen = now;
        applyRememberedAction(teto, p);
        save();

        if (newWorld) {
            String[] line = pickNewWorldLine(id, p, gap, teto, level);
            if (line != null) {
                say(level, teto, line);
                return true;
            }
        }
        // 同一个世界里的重逢：她还是会提一句「还是上次那支吧」（一辈子一次）
        if (p.lastAction != 0) {
            String dance = p.lastAction == Teto.ACTION_COUNT - 1
                    ? "hatmod.teto.recall.dance.2"
                    : "hatmod.teto.recall.dance.1";
            if (claim(id, dance)) {
                say(level, teto, new String[]{dance});
                return true;
            }
        }
        return false;
    }

    /** 念一句：{@code line[0]} 是语言键，后面是参数。 */
    private static void say(ServerLevel level, Teto teto, String[] line) {
        Object[] args = new Object[line.length - 1];
        System.arraycopy(line, 1, args, 0, args.length);
        TetoTalk.sayNear(level, teto, 24.0D, line[0], args);
    }

    /** 换了个世界时，按「第几个世界 → 死过几次 → 隔了多久 → 死在哪儿」的优先级挑一句。 */
    private static String[] pickNewWorldLine(UUID id, Player p, long gap, Teto teto, ServerLevel level) {
        // 1) 世界计数里程碑：第 3 / 5 / 10 个世界，之后每 10 个一次
        String world = null;
        if (p.worlds == 3) {
            world = "hatmod.teto.recall.world.1";
        } else if (p.worlds == 5) {
            world = "hatmod.teto.recall.world.2";
        } else if (p.worlds == 10) {
            world = "hatmod.teto.recall.world.3";
        } else if (p.worlds > 10 && p.worlds % 10 == 0) {
            world = "hatmod.teto.recall.world.more";
        }
        if (world != null && claim(id, world)) {
            // 里程碑配一点小小的演出：几颗末地烛火星 + 音符
            level.sendParticles(ParticleTypes.END_ROD,
                    teto.getX(), teto.getY() + teto.getBbHeight() * 0.6D, teto.getZ(),
                    10, 0.3D, 0.4D, 0.3D, 0.02D);
            level.sendParticles(ParticleTypes.NOTE,
                    teto.getX(), teto.getY() + teto.getBbHeight() * 0.85D, teto.getZ(),
                    4, 0.25D, 0.2D, 0.25D, 0.6D);
            return world.equals("hatmod.teto.recall.world.more")
                    ? new String[]{world, String.valueOf(p.worlds)}
                    : new String[]{world};
        }

        // 2) 死亡次数到达档位
        String death = null;
        if (p.deaths >= 10) {
            death = "hatmod.teto.recall.death.tier10";
        } else if (p.deaths >= 5) {
            death = "hatmod.teto.recall.death.tier5";
        } else if (p.deaths >= 3) {
            death = "hatmod.teto.recall.death.tier3";
        }
        if (death != null && claim(id, death)) {
            return new String[]{death};
        }

        // 3) 隔了多久
        String gapKey = null;
        if (gap >= YEAR) {
            gapKey = "hatmod.teto.recall.gap.5";
        } else if (gap >= MONTH) {
            gapKey = "hatmod.teto.recall.gap.4";
        } else if (gap >= WEEK) {
            gapKey = "hatmod.teto.recall.gap.3";
        } else if (gap >= DAY) {
            gapKey = "hatmod.teto.recall.gap.2";
        } else if (gap > 0L) {
            gapKey = "hatmod.teto.recall.gap.1";
        }
        if (gapKey != null && claim(id, gapKey)) {
            return new String[]{gapKey};
        }

        // 4) 上次死在哪儿
        String dim = p.lastDeathDim == null ? "" : p.lastDeathDim.toLowerCase(Locale.ROOT);
        String where = null;
        if (dim.contains("the_nether")) {
            where = "hatmod.teto.recall.death.nether";
        } else if (dim.contains("the_end")) {
            where = "hatmod.teto.recall.death.end";
        } else if (!dim.isEmpty() && !dim.equals("minecraft:overworld")) {
            where = "hatmod.teto.recall.death.other";
        }
        if (where != null && claim(id, where)) {
            return new String[]{where};
        }
        return null;
    }

    /** 上次让她跳的那套舞：新世界里第一次见面就直接摆好，不用你再按一遍。 */
    private static void applyRememberedAction(Teto teto, Player p) {
        if (teto == null || teto.isSummoned() || p.lastAction <= 0) {
            return;
        }
        teto.setActionIndex(p.lastAction);
    }

    /** 这句一辈子只说一次：没说过就记下来并返回 true。 */
    private static boolean claim(UUID id, String key) {
        if (HatSettings.tetoSpoken(id, key)) {
            return false;
        }
        HatSettings.markTetoSpoken(id, key);
        return true;
    }

    /** 存档标识：种子 + 存档名。同一个存档重进、换维度、重开游戏都不变。 */
    private static String worldKey(ServerLevel level) {
        long seed = level.getSeed();
        String name = level.getServer() == null ? "" : level.getServer().getWorldData().getLevelName();
        return seed + "|" + name;
    }

    // ------------------------------------------------------------------
    // 三条「习惯」记忆：死过几次、让你跳过哪支、最常戴哪顶帽子
    // ------------------------------------------------------------------

    /** 主人死了：记一笔次数和死在哪个维度。 */
    static void noteDeath(ServerPlayer player) {
        if (player == null) {
            return;
        }
        ensureLoaded();
        Player p = PLAYERS.computeIfAbsent(player.getUUID(), key -> new Player());
        p.deaths++;
        p.lastDeathDim = player.level().dimension().location().toString();
        save();
    }

    /** 他让她换了舞步：记住这一套，下次换存档直接摆好。 */
    static void noteAction(UUID player, int action) {
        if (player == null) {
            return;
        }
        ensureLoaded();
        Player p = PLAYERS.computeIfAbsent(player, key -> new Player());
        if (p.lastAction == action) {
            return;
        }
        p.lastAction = action;
        save();
    }

    /**
     * 他又戴上了某顶帽子：记次数。
     *
     * @return 第 4 次戴同一顶时冒出那句「又是这顶」（一辈子一次）；否则 {@code null}
     */
    static String noteHat(UUID player, HatType type) {
        if (player == null || type == null) {
            return null;
        }
        ensureLoaded();
        Player p = PLAYERS.computeIfAbsent(player, key -> new Player());
        String name = type.name();
        int count = p.hats.merge(name, 1, Integer::sum);
        save();
        if (count >= 4) {
            // 同一顶帽子的四句「你换了帽子」都说完之后才轮到这句
            String loyal = "hatmod.teto.recall.hat.loyal";
            if (claim(player, loyal)) {
                return loyal;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 落盘
    // ------------------------------------------------------------------

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path path = file();
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            Save save = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Save.class);
            if (save == null || save.players == null) {
                return;
            }
            for (Map.Entry<String, Player> entry : save.players.entrySet()) {
                try {
                    Player value = entry.getValue() == null ? new Player() : entry.getValue();
                    if (value.hats == null) {
                        value.hats = new LinkedHashMap<>();
                    }
                    PLAYERS.put(UUID.fromString(entry.getKey()), value);
                } catch (IllegalArgumentException ignored) {
                    // 手改坏了就当没这一行
                }
            }
        } catch (Exception exception) {
            HatMod.LOGGER.warn("[HatMod] 读取 config/{} 失败，跨存档记忆当作空白：{}",
                    FILE_NAME, exception.toString());
        }
    }

    private static void save() {
        Save save = new Save();
        for (Map.Entry<UUID, Player> entry : PLAYERS.entrySet()) {
            save.players.put(entry.getKey().toString(), entry.getValue());
        }
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(save), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            HatMod.LOGGER.warn("[HatMod] 写入 config/{} 失败：{}", FILE_NAME, exception.toString());
        }
    }
}
