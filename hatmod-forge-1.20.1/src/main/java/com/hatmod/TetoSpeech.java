package com.hatmod;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

/**
 * Teto 的「熟悉度」和「记忆」：她记得这是第几次被你叫出来，也记得一起经历过的事。
 *
 * <p>闲话因此分三层（见语言文件）：
 * <ul>
 *   <li>{@code hatmod.teto.free.idle.*} —— 第一次见面时说的话（原来那 20 句）；</li>
 *   <li>{@code hatmod.teto.again.*} —— 第二次：开场她一定会提「这首曲子是缝出来的」；</li>
 *   <li>{@code hatmod.teto.return.*} —— 第三次往后：熟客，语气更随便。</li>
 * </ul>
 *
 * <p>另外还有一层「记忆台词」（{@code hatmod.teto.mem.<事件>.*}）：只有真的和她一起
 * 经历过那件事才会说，而且**一辈子只说一次**（说过的记在配置里，见
 * {@link HatSettings#markTetoSpoken}）。所以这些话说出来的时候才像「她还记得」，
 * 而不是每次都背同一个段子。
 *
 * <p>召唤次数只算**成功的**：帽子链路那边一只都没放成不算，刷怪蛋那边要等她认了主才算
 * （见 {@code HatSummons#spawnTetos} 与 {@link Teto#tick()}）。
 */
final class TetoSpeech {

    /** 第二次见面时她一定会先说这一句：这首曲子是缝出来的。 */
    static final String AGAIN_INTRO = "hatmod.teto.again.song";

    /** 有没说过的话时，闲聊有多大概率先说它（其余时候还是抽池子）。 */
    private static final double MEMORY_CHANCE = 0.45D;

    /** 记忆标记：陪她听完过一整首（链路自然收尾）。 */
    static final String CHAIN_END = "chain_end";
    /** 记忆标记：她在一轮里被打倒过。 */
    static final String CHAIN_DIED = "chain_died";
    /** 记忆标记：喂过她。 */
    static final String FED = "fed";

    /** 带食物名的那句「上次那个 XX 还有吗」：只在记得上一次喂的是什么时说，一辈子一次。 */
    private static final String FED_NAME = "hatmod.teto.mem.fed.name.1";
    /** 「难吃的那种」也记一条：腐肉、蜘蛛眼这些不该被当成「还想再吃」。 */
    static final String FED_BAD = "fedbad";
    /** 每类食物几句话（说完就回落到按次数分档的池子）。 */
    private static final int FOOD_LINES = 3;
    /** 记忆标记：让她坐下过。 */
    static final String SAT = "sat";
    /** 记忆标记：看她跳过动作 2。 */
    static final String DANCE = "dance";
    /** 记忆标记：见过她闲聊时溜走。 */
    static final String VANISH = "vanish";
    /** 记忆标记：她挨过打（开场白和见面招呼会因此换一组）。 */
    static final String HIT = "hit";
    /** 记忆标记：在她面前开过破防模式。 */
    static final String BREAK_MODE = "break_mode";

    /** 每个记忆有几条台词（和语言文件的编号 1..N 对应）。 */
    private static final int MEMORY_LINES = 2;

    /** 第一次 / 第二次 / 第三次往后。 */
    static int tierOf(int summons) {
        if (summons <= 1) {
            return 1;
        }
        return summons == 2 ? 2 : 3;
    }

    /** 每一层闲话池里有多少条自己的话（不含 meta）。 */
    private static final int POOL_LINES = 20;
    /** 第一次见面时池子只有这几条 —— 那时候 meta 一条都不给。 */
    private static final int FIRST_LINES = 14;

    /**
     * meta 叙述一共几条（{@code hatmod.teto.meta.1..N}）。
     *
     * <p>「她其实知道自己是个模组角色」那一类。第一次见面一句都不会出现 ——
     * 见面的次数越多，池子里才放得越多，见满 {@link #META_LINES} 条为止。
     */
    private static final int META_LINES = 16;
    /** 每多一次见面，池子里多放几条 meta。 */
    private static final int META_PER_SUMMON = 4;

    /** 这一层闲话池的语言键前缀。 */
    static String poolPrefix(int tier) {
        if (tier == 1) {
            return "hatmod.teto.free.idle.";
        }
        return tier == 2 ? "hatmod.teto.again." : "hatmod.teto.return.";
    }

    /**
     * 这一层的闲话池（完整的语言键，交给洗牌袋去抽）。
     *
     * <p>第一次见面只给 14 句「正常」的（{@code free.idle.1..14}）；第二次、第三次
     * 各另有 20 句。meta 叙述单独一排（{@code teto.meta.*}），按见面次数**渐进**地
     * 往池子里放 —— 见 {@link #metaCount}。
     *
     * @param summons 这个玩家第几次叫她出来
     * @param player   说给谁听（彩蛋要查「说没说过」，可以是 null）
     * @param allowMeta 这一句能不能是 meta（每次对话的前几句不让放，见 {@code Teto}）
     */
    static java.util.List<String> idlePool(int summons, java.util.UUID player, boolean allowMeta) {
        java.util.List<String> pool = new java.util.ArrayList<>();
        int tier = tierOf(summons);
        String prefix = poolPrefix(tier);
        int lines = tier == 1 ? FIRST_LINES : POOL_LINES;
        for (int i = 1; i <= lines; i++) {
            pool.add(prefix + i);
        }
        int meta = metaCount(summons, allowMeta);
        for (int i = 1; i <= meta; i++) {
            pool.add("hatmod.teto.meta." + i);
        }
        // 彩蛋：真装了「车万女仆：身怀百技」（Promaid）才会冒出来，而且一人只说一次
        if (promaidLoaded() && player != null) {
            for (String egg : PROMAID_EGGS) {
                if (!HatSettings.tetoSpoken(player, egg)) {
                    pool.add(egg);
                }
            }
            // 枪弹天使的机制 / 做她的初衷：**整个话题一辈子只开一次口**。
            // 任意一条说出口就把话题关掉，剩下的几条不再提（她提起她只值一遍）。
            if (!HatSettings.tetoSpoken(player, ANGEL_TOPIC)) {
                for (int i = 1; i <= ANGEL_LORE_LINES; i++) {
                    String key = "hatmod.teto.boss." + i;
                    if (!HatSettings.tetoSpoken(player, key)) {
                        pool.add(key);
                    }
                }
            }
        }
        return pool;
    }

    /**
     * 关于「枪弹天使」的闲聊有几条（{@code hatmod.teto.boss.1..N}）。
     *
     * <p>内容是机制（长枪 → 激流三叉戟 → 重锤 → 翅膀）、有几条命（图腾）、
     * 以及做她的两个初衷（想看加强过的女仆当敌人什么样；把视频里「枪弹天使」这个称号
     * 落成一个真正的人设），还有一句实话：Promaid 本来是为了优化，不是做 boss，
     * 所以默认只给创造模式玩家生成。
     */
    private static final int ANGEL_LORE_LINES = 6;

    /** 「枪弹天使」这个话题的开关：任意一条说出口，整段就此封掉。 */
    static final String ANGEL_TOPIC = "hatmod.teto.angel.topic";

    /**
     * 这一次能放几条 meta。
     *
     * <p>第一次见面 0 条（那时最多只有彩蛋），之后每多一次见面加 {@link #META_PER_SUMMON}
     * 条，封顶 {@link #META_LINES}。{@code allowMeta} 为假时（一次对话的前几句）一条都不放。
     */
    static int metaCount(int summons, boolean allowMeta) {
        if (!allowMeta) {
            return 0;
        }
        return Math.min(META_LINES, Math.max(0, summons - 1) * META_PER_SUMMON);
    }

    /**
     * Promaid 的彩蛋就一句（说过的记在 tetoSpoken 里，一人一次）。
     *
     * <p>后半句「你还真装了？！」不在这里 —— 它要隔 6 秒才补上，见 {@code Teto#tick}。
     */
    private static final String[] PROMAID_EGGS = {
            "hatmod.teto.egg.promaid.1",
    };

    /** 装没装 Promaid；null = 还没问过。 */
    private static Boolean promaidLoaded;

    private static boolean promaidLoaded() {
        if (promaidLoaded == null) {
            net.minecraftforge.fml.ModList list = net.minecraftforge.fml.ModList.get();
            promaidLoaded = list != null && list.isLoaded("promaid");
        }
        return promaidLoaded;
    }

    /** 每顶牛仔帽几句「你换了帽子」的话（语言文件 {@code wear.<帽子>.1..N}）。 */
    private static final int WEAR_LINES = 3;

    /**
     * 玩家换上了另一顶牛仔帽：按「这顶帽子第几次出现」往后排，一个颜色三句，说完就不提了。
     *
     * @param type 帽子种类（{@code HatItems#typeOf}），不是牛仔帽就别调这里
     * @return 台词键；这个颜色的三句都说过了就返回 {@code null}
     */
    static String wearLine(java.util.UUID player, HatType type) {
        if (player == null || type == null) {
            return null;
        }
        String base = "hatmod.teto.wear." + type.name().toLowerCase(java.util.Locale.ROOT) + ".";
        for (int i = 1; i <= WEAR_LINES; i++) {
            String key = base + i;
            if (!HatSettings.tetoSpoken(player, key)) {
                HatSettings.markTetoSpoken(player, key);
                return key;
            }
        }
        return null;
    }

    /**
     * 按「第几次」挑一个池子说一句（开场白、挨打、死亡、散场、喂食、留守、收编都用它）。
     *
     * <p>第一次用原来的池子（{@code base.1..firstCount}）；第二次用 {@code base.t2.N}；
     * 第三次往后用 {@code base.t3.N}（两者各 1..{@code laterCount}）。
     * 这样同一件事，第一次和第三次听到的说法是两个人。
     */
    static String pick(RandomSource random, int summons, String base, int firstCount, int laterCount) {
        int tier = tierOf(summons);
        if (tier == 1) {
            return base + "." + (1 + random.nextInt(firstCount));
        }
        String pool = tier == 2 ? base + ".t2." : base + ".t3.";
        return pool + (1 + random.nextInt(laterCount));
    }

    /** 这个 UUID 召唤过她几次（null 当 0）。 */
    static int summonsOf(java.util.UUID player) {
        return player == null ? 0 : HatSettings.tetoSummons(player);
    }

    /**
     * 喂食那句：按「喂过她几次」分阶段。
     *
     * <p>第一次是原来那套（还分第几次见面）；第二次 {@code feed.more}、第三次 {@code feed.again}、
     * 第四次往后 {@code feed.over} 各有一套 —— 不然连着喂，她说的永远是同一句。
     */
    static String feedLine(RandomSource random, int summons, int feeds) {
        if (feeds <= 1) {
            return pick(random, summons, "hatmod.teto.feed", 3, 2);
        }
        if (feeds == 2) {
            return "hatmod.teto.feed.more." + (1 + random.nextInt(3));
        }
        if (feeds == 3) {
            return "hatmod.teto.feed.again." + (1 + random.nextInt(3));
        }
        return "hatmod.teto.feed.over." + (1 + random.nextInt(2));
    }

    /**
     * 她问过「上次那个 XX 还有吗」之后，这一口算不算回答。
     *
     * <p>三种说法，各自只出现一次（{@code %s} 填的都是**她问的那个**）：
     * <ul>
     *   <li>拿的正好是她问的那样 → {@code feed.answer.same}（惊喜）；</li>
     *   <li>拿了别的能吃的东西 → {@code feed.answer.other}（“我说的是那个啦”）；</li>
     *   <li>端来的还是腐肉那种难吃的 → {@code feed.answer.bad}（“你拿这个来干什么”）。</li>
     * </ul>
     *
     * @param previous 上一次喂的是什么（也就是她问的那个）
     * @param current  这一次喂的是什么
     * @param tag      这一次的食物类别（{@code TetoFeeding.foodTag}），用来认出「难吃的」
     * @return 台词键；还没到这一步就返回 {@code null}
     */
    static String feedAnswerLine(RandomSource random, java.util.UUID player,
                                 String previous, String current, String tag) {
        if (player == null || previous.isEmpty() || !HatSettings.tetoSpoken(player, FED_NAME)) {
            return null;
        }
        String pool;
        if ("strange".equals(tag)) {
            pool = "hatmod.teto.feed.answer.bad.";
        } else if (previous.equals(current)) {
            pool = "hatmod.teto.feed.answer.same.";
        } else {
            pool = "hatmod.teto.feed.answer.other.";
        }
        java.util.List<String> candidates = new java.util.ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            String key = pool + i;
            if (!HatSettings.tetoSpoken(player, key)) {
                candidates.add(key);
            }
        }
        return candidates.isEmpty() ? null : candidates.get(random.nextInt(candidates.size()));
    }

    /**
     * 这一类的食物她头一次（和第二次）吃到时说的那句。
     *
     * <p>按类别记在 {@code tetoSpoken} 里：{@code hatmod.teto.food.<类别>.1/2} 各说一次，
     * 所以换着花样喂，能一路听到新东西。
     *
     * @param tag 食物类别（{@code TetoFeeding.foodTag}），认不出来是 {@code null}
     * @return 台词键；这一类已经说过两遍、或者认不出类别就返回 {@code null}
     */
    static String foodFirstLine(RandomSource random, java.util.UUID player, String tag) {
        if (player == null || tag == null) {
            return null;
        }
        String base = "hatmod.teto.food." + tag + ".";
        for (int i = 1; i <= FOOD_LINES; i++) {
            String key = base + i;
            if (!HatSettings.tetoSpoken(player, key)) {
                HatSettings.markTetoSpoken(player, key);
                return key;
            }
        }
        // 这一类说过三轮了：难吃的那种继续抱怨，别的交给按次数分档的池子
        if ("strange".equals(tag)) {
            return "hatmod.teto.food.strange.repeat." + (1 + random.nextInt(2));
        }
        return null;
    }

    /**
     * 还没到下一次能吃的点：她把东西推回来，而且明说。
     *
     * <p>拒绝的话按**类别**分：塞腐肉、塞金苹果、塞面包，得到的回答不一样 ——
     * 「把人当垃圾桶」这件事才会有明确的反应，而不是一句通用的「等一下」。
     *
     * @param tag 手里那东西的类别（{@code TetoFeeding.foodTag}），认不出来是 {@code null}
     */
    static String feedWaitLine(RandomSource random, String tag) {
        String pool = tag == null
                ? "hatmod.teto.feed.wait.other."
                : "hatmod.teto.feed.wait." + tag + ".";
        return pool + (1 + random.nextInt(2));
    }

    /** 刷怪蛋认主时那句招呼：第一次见 / 又见面。 */
    static String pickGreeting(RandomSource random, int summons) {
        return pickGreeting(random, summons, TetoMood.NORMAL);
    }

    /**
     * 认主招呼，带心情档：憋屈的时候她爱答不理，雀跃的时候未语先笑。
     * 心情档只在「没有挨打记忆要提」的时候生效（那一半先在调用方抽走）。
     */
    static String pickGreeting(RandomSource random, int summons, int moodTier) {
        if (moodTier == TetoMood.UPSET) {
            return "hatmod.teto.greet.upset." + (1 + random.nextInt(2));
        }
        if (moodTier == TetoMood.HAPPY) {
            return "hatmod.teto.greet.happy." + (1 + random.nextInt(2));
        }
        if (summons >= 2) {
            return "hatmod.teto.greet.again." + (1 + random.nextInt(3));
        }
        return "hatmod.teto.free.greet." + (1 + random.nextInt(3));
    }

    /** 主人身边有虚影转圈时，可能会冒出来的那句话（一共两句）。 */
    private static final int SHADE_LINES = 2;
    /** 有虚影在场时，每次闲聊有多大概率改说虚影那句。 */
    private static final double SHADE_CHANCE = 0.3D;

    /**
     * 主人身边正有虚影绕圈时，她偶尔会评价一句（冷却交给调用方的 sayCooled）。
     *
     * @return 台词键；16 格内没有主人的虚影、或者这次没抽中就返回 {@code null}
     */
    static String shadeLine(ServerLevel level, net.minecraft.world.entity.LivingEntity owner) {
        if (owner == null || level.random.nextDouble() >= SHADE_CHANCE) {
            return null;
        }
        for (HatSummon shade : level.getEntitiesOfClass(HatSummon.class,
                owner.getBoundingBox().inflate(16.0D))) {
            if (shade.isOwnedBy(owner)) {
                return "hatmod.teto.shade." + (1 + level.random.nextInt(SHADE_LINES));
            }
        }
        return null;
    }

    /**
     * 按心情档往闲话池里补的话：憋屈时她阴阳怪气，雀跃时她话多。
     * 正常档不补 —— 池子还是原来那些。
     */
    static java.util.List<String> moodPool(int tier) {
        java.util.List<String> pool = new java.util.ArrayList<>();
        if (tier == TetoMood.UPSET) {
            pool.add("hatmod.teto.mood.low.1");
            pool.add("hatmod.teto.mood.low.2");
        } else if (tier == TetoMood.HAPPY) {
            pool.add("hatmod.teto.mood.high.1");
            pool.add("hatmod.teto.mood.high.2");
        }
        return pool;
    }

    /**
     * 这一句是不是「只属于此刻」的话：第二次见面那句，或者某段还没说过的记忆。
     *
     * @return 语言键；没有就返回 {@code null}，让调用方去池子里抽。
     */
    static String specialLine(ServerLevel level, java.util.UUID player, int summons) {
        if (player == null) {
            return null;
        }
        // 第二次：先把「这首曲子是缝出来的」说了（只此一次）
        if (summons == 2 && !HatSettings.tetoSpoken(player, AGAIN_INTRO)) {
            HatSettings.markTetoSpoken(player, AGAIN_INTRO);
            return AGAIN_INTRO;
        }
        if (level.random.nextDouble() >= MEMORY_CHANCE) {
            return null;
        }
        return unspokenMemory(level.random, player);
    }

    /**
     * 记忆台词要带的参数：目前只有「上次喂的是什么」那句要往 {@code %s} 里填食物名。
     */
    static Object[] memoryArgs(java.util.UUID player, String key) {
        if (FED_NAME.equals(key)) {
            return new Object[] {HatSettings.tetoLastFood(player)};
        }
        if (key != null && key.startsWith("hatmod.teto.mem." + FED_BAD + ".")) {
            return new Object[] {HatSettings.tetoLastBadFood(player)};
        }
        return new Object[0];
    }

    /** 从「有这段记忆、但还没说过」的台词里随便挑一句，并记成说过了。 */
    private static String unspokenMemory(RandomSource random, java.util.UUID player) {
        java.util.List<String> candidates = new java.util.ArrayList<>();
        for (String memory : HatSettings.tetoMemories(player)) {
            for (int i = 1; i <= MEMORY_LINES; i++) {
                String key = "hatmod.teto.mem." + memory + "." + i;
                if (!HatSettings.tetoSpoken(player, key)) {
                    candidates.add(key);
                }
            }
            // 被喂过：如果记得上一次喂的是什么，优先说带名字的那句
            if (FED.equals(memory) && !HatSettings.tetoLastFood(player).isEmpty()
                    && !HatSettings.tetoSpoken(player, FED_NAME)) {
                candidates.add(FED_NAME);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        String key = candidates.get(random.nextInt(candidates.size()));
        HatSettings.markTetoSpoken(player, key);
        return key;
    }
}
