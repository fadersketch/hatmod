package com.hatmod;

/**
 * 「脑蚀」这一首曲子的**真实拍点表**。
 *
 * <p>数据是从 {@code music_brainrot.ogg} 里直接分析出来的：起音检测（spectral flux）+
 * 自相关估速 + 网格拟合，再让每一拍吸附到最近的鼓点上。结论是全曲 **135.5 BPM**、
 * 每拍 0.443 秒 —— 也就是 **8.86 刻**。
 *
 * <p>为什么要把 238 个拍点一个个写死，而不是在代码里按「每 9 刻一拍」算：
 * 8.86 不是整数，四舍五入到 9 刻的话，到曲末会累计漂 **约 33 刻（1.7 秒 ≈ 4 拍）**，
 * 后半段会全部打空。所以这里存的是**绝对刻数**，和音频一一对应。
 *
 * <p>{@link #SCORE_FROM} / {@link #SCORE_TO} 是真有鼓点的区间：前面 14 秒是氛围前奏
 * （整段只有 1 个鼓点），100 秒之后是收尾，这两段不参与判定，只在界面上轻轻呼吸。
 */
public final class BrainrotBeats {

    /**
     * 两句「Nope too late」的起拍：这三个词是**说出来**的，本来不在鼓点网格上，
     * 所以干脆把它们当成三个实键塞进拍表里 ——
     * 和倒计时的 READY / SET / GO 同刻，念到哪个词，就有一个键压到判定线。
     *
     * <p>位置是拿原曲实测的：第一次 260 / 269 / 278（原曲 34.38 / 34.82 / 35.27 秒），
     * 第二次 1398 / 1407 / 1416（原曲 91.26 / 91.70 / 92.16 秒）。这六个数对的是
     * 「Nope」「too」「late」三声**各自开口的那一刻**（按能量包络切出来的音节边界），
     * 不是平均分出来的 —— 词和词之间大约隔 0.44 秒。
     */
    public static final int NO_TOO_LATE_FIRST = 260;
    public static final int NO_TOO_LATE_SECOND = 1398;
    /** 一个词隔多少刻（= 倒计时 READY / SET / GO 的间隔）：9 刻 ≈ 0.45 秒，和口白的实际语速一致。 */
    public static final int SPEECH_STEP = 9;
    /** 那三个白键对应的词：按中之后判定字直接写这个词（不再写 3 / 2 / 1）。 */
    public static final String[] SPEECH_WORDS = {"Nope", "too", "late"};
    /** 台词拍前后多少刻内的**鼓点**要挪走（那一拍是鼓，不是那三个词）。 */
    private static final int SPEECH_WINDOW = 4;

    /** 全曲拍点（刻，从这一轮开始计时；20 刻 = 1 秒）。台词那三拍是手工塞进去的实键。 */
    private static final int[] TICKS = buildChart(new int[] {
            6, 15, 24, 32, 41, 50, 59, 68, 77, 86, 94, 103,
            112, 121, 130, 139, 148, 156, 165, 174, 181, 192, 201, 208,
            218, 227, 235, 244, 254, 263, 272, 280, 288, 297, 306, 315,
            324, 333, 342, 350, 359, 368, 377, 386, 395, 404, 413, 421,
            430, 439, 448, 457, 466, 475, 484, 493, 502, 510, 519, 528,
            537, 546, 554, 564, 572, 581, 590, 599, 608, 617, 626, 635,
            644, 653, 662, 671, 679, 688, 697, 704, 715, 724, 733, 741,
            750, 759, 768, 777, 786, 795, 804, 813, 822, 830, 840, 848,
            856, 866, 875, 884, 893, 901, 910, 919, 928, 937, 946, 955,
            964, 973, 982, 989, 999, 1008, 1017, 1026, 1035, 1044, 1053, 1061,
            1070, 1079, 1088, 1097, 1106, 1115, 1122, 1133, 1140, 1148, 1157, 1168,
            1177, 1184, 1194, 1203, 1212, 1219, 1228, 1239, 1246, 1255, 1264, 1272,
            1281, 1290, 1299, 1306, 1317, 1326, 1333, 1341, 1352, 1361, 1368, 1377,
            1388, 1396, 1405, 1414, 1423, 1430, 1439, 1451, 1457, 1466, 1477, 1486,
            1493, 1502, 1511, 1519, 1528, 1537, 1546, 1555, 1565, 1573, 1582, 1590,
            1599, 1608, 1617, 1626, 1635, 1644, 1653, 1662, 1670, 1679, 1689, 1697,
            1706, 1715, 1724, 1732, 1741, 1751, 1759, 1768, 1777, 1786, 1795, 1804,
            1813, 1822, 1829, 1839, 1848, 1857, 1866, 1875, 1884, 1892, 1902, 1910,
            1919, 1928, 1937, 1946, 1955, 1964, 1971, 1982, 1990, 1999, 2008, 2016,
            2025, 2034, 2043, 2052, 2061, 2070, 2078, 2087, 2096, 2105,
    });

    /**
     * 把鼓点网格整理成真正的谱面。
     *
     * <p>规则：
     * <ul>
     *   <li>台词那三拍（260 / 269 / 278、1398 / 1407 / 1416）附近 {@link #SPEECH_WINDOW} 刻内
     *       的鼓点先挪走 —— 那几个词才是这一段该按的东西；</li>
     *   <li>长音（含前奏那根示范长音）里面不放别的键，整段是一根长条；</li>
     *   <li>计分段之后（{@code > SCORE_TO}）不再放键，否则会看到「按不掉」的键；</li>
     *   <li>最后把台词那六个口头拍原样塞回去，和 READY / SET / GO 同刻。</li>
     * </ul>
     */
    private static int[] buildChart(int[] source) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (int tick : source) {
            if (nearSpeech(tick)) {
                continue;
            }
            if (tick > LEAD_HOLD_START && tick < SCORE_START_TICK) {
                continue;
            }
            // 两根长音里面都不放别的键：整段是一根长条
            boolean inHold = false;
            for (int h = 0; h < HOLD_COUNT; h++) {
                if (tick > holdStart(h) && tick <= holdEnd(h)) {
                    inHold = true;
                    break;
                }
            }
            if (inHold) {
                continue;
            }
            if (tick > SCORE_TO) {
                continue;
            }
            out.add(tick);
        }
        for (int i = 0; i < 3; i++) {
            out.add(NO_TOO_LATE_FIRST + i * SPEECH_STEP);
            out.add(NO_TOO_LATE_SECOND + i * SPEECH_STEP);
        }
        java.util.Collections.sort(out);
        int[] result = new int[out.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = out.get(i);
        }
        return result;
    }

    /** 这一拍是不是「NOPE / too / late」那六个口头拍本身（HUD 把它们画成纯白键）。 */
    public static boolean isSpeechKey(int tick) {
        for (int i = 0; i < 3; i++) {
            if (tick == NO_TOO_LATE_FIRST + i * SPEECH_STEP
                    || tick == NO_TOO_LATE_SECOND + i * SPEECH_STEP) {
                return true;
            }
        }
        return false;
    }

    /** 这一拍是三连里的第几个（0 / 1 / 2）；不是口头拍返回 -1。 */
    public static int speechIndex(int tick) {
        for (int i = 0; i < 3; i++) {
            if (tick == NO_TOO_LATE_FIRST + i * SPEECH_STEP
                    || tick == NO_TOO_LATE_SECOND + i * SPEECH_STEP) {
                return i;
            }
        }
        return -1;
    }

    /** 这一拍是六个口白键里的第几个（0–5，按时间顺序）；不是就返回 -1。 */
    public static int speechBit(int tick) {
        for (int i = 0; i < 3; i++) {
            if (tick == NO_TOO_LATE_FIRST + i * SPEECH_STEP) {
                return i;
            }
            if (tick == NO_TOO_LATE_SECOND + i * SPEECH_STEP) {
                return 3 + i;
            }
        }
        return -1;
    }

    /**
     * 这一刻附近、还没按过的最早一个口白键（刻）；没有就返回 -1。
     *
     * <p>三连只隔 5 刻（0.25 秒），按「离得最近的那一拍」判很容易把「Nope」判成「too」
     * —— 隔壁那个键永远只差 2~3 刻，稍微慢一点就滑过去了。所以口白键单独走这条：
     * 先认附近**还没按过的最早那个**，按中的先后可以乱，但每个键只算一次。
     *
     * @param mask  六个口白键的命中位（见 {@link #speechBit(int)}）
     * @param grace 判定窗（刻）
     */
    public static int earliestUnhitSpeech(int age, int mask, int grace) {
        for (int i = 0; i < 3; i++) {
            int tick = NO_TOO_LATE_FIRST + i * SPEECH_STEP;
            if ((mask & (1 << i)) == 0 && Math.abs(tick - age) <= grace) {
                return tick;
            }
        }
        for (int i = 0; i < 3; i++) {
            int tick = NO_TOO_LATE_SECOND + i * SPEECH_STEP;
            if ((mask & (1 << (3 + i))) == 0 && Math.abs(tick - age) <= grace) {
                return tick;
            }
        }
        return -1;
    }

    /** 这一拍是不是「Nope / too / late」六个口头拍附近的鼓点。 */
    private static boolean nearSpeech(int tick) {
        for (int i = 0; i < 3; i++) {
            if (Math.abs(tick - (NO_TOO_LATE_FIRST + i * SPEECH_STEP)) <= SPEECH_WINDOW
                    || Math.abs(tick - (NO_TOO_LATE_SECOND + i * SPEECH_STEP)) <= SPEECH_WINDOW) {
                return true;
            }
        }
        return false;
    }

    /**
     * 打拍的起手拍：**「NOPE」出声那一刻**（13 秒 = 260 刻）。
     *
     * <p>READY / SET / GO 和 NOPE / too / late 同刻（260 / 269 / 278），
     * 这三个词本身也已经写进拍表，所以从第一个词起，词到、键也到。
     */
    public static final int SCORE_START_TICK = 260;

    /** 计分的第一拍：No too late 之后（含）的第一个真实拍点。 */
    public static final int SCORE_FROM = firstAtOrAfter(SCORE_START_TICK);
    /** 最后一个真实鼓点（约 99.1 秒）。 */
    public static final int SCORE_TO = 1999;

    /** 最后一段黑暗从这一该开始（100 秒）：从这里起不再放键，判定条慢慢淡出。 */
    public static final int FINAL_DARK = 100 * 20;

    /** 示范长音：11 秒末尾起，一直拖到 No too late 的第一个实体拍（只是示范，不算分）。 */
    public static final int LEAD_HOLD_START = 11 * 20;

    /**
     * 长音：1:08 起到第二句口白出口前一瞬（1360–1398 刻）。
     *
     * <p>尾巴收到 1398 而不是整 70 秒：第二句「Nope」在 1398 刻出口，长音要是压到 1400，
     * 那一下会被当成「按住」续拍，白键就吞掉了。
     */
    public static final int HOLD_START = 68 * 20;
    public static final int HOLD_END = 1398;
    /** 收尾那根长音：最后两个短键（1990 / 1999）并成一根，从 1990 拖到曲末的黑暗。 */
    public static final int FINAL_HOLD_START = 1990;
    public static final int FINAL_HOLD_END = 2000;
    /** 一共几根长音。 */
    public static final int HOLD_COUNT = 2;

    private BrainrotBeats() {
    }

    /** 第一个不早于 {@code tick} 的拍点（夹到两端）。 */
    private static int firstAtOrAfter(int tick) {
        for (int value : TICKS) {
            if (value >= tick) {
                return value;
            }
        }
        return TICKS[TICKS.length - 1];
    }

    /** 第 {@code index} 根长音的起刻。 */
    public static int holdStart(int index) {
        return index == 0 ? HOLD_START : FINAL_HOLD_START;
    }

    /** 第 {@code index} 根长音的止刻（不含）。 */
    public static int holdEnd(int index) {
        return index == 0 ? HOLD_END : FINAL_HOLD_END;
    }

    /** 第 {@code index} 根长音的头拍在拍表里的下标。 */
    public static int holdHeadIndex(int index) {
        int[] out = new int[1];
        return nearest(holdStart(index), out);
    }

    /** 第 {@code index} 根长音头拍的刻。 */
    public static int holdHeadTick(int index) {
        return tickAt(holdHeadIndex(index));
    }

    /** 这一刻正处在第几根长音里（-1 = 不在长音里）。 */
    public static int holdAt(int age) {
        for (int i = 0; i < HOLD_COUNT; i++) {
            if (age >= holdStart(i) && age < holdEnd(i)) {
                return i;
            }
        }
        return -1;
    }

    /** 拍点总数。 */
    public static int count() {
        return TICKS.length;
    }

    /** 第 {@code index} 拍在第几刻（越界会夹到两端）。 */
    public static int tickAt(int index) {
        if (index < 0) {
            return TICKS[0];
        }
        if (index >= TICKS.length) {
            return TICKS[TICKS.length - 1];
        }
        return TICKS[index];
    }

    /**
     * 离 {@code age} 最近的一拍：返回下标，并把「差几刻」写进 {@code out[0]}。
     *
     * <p>{@code out[0]} 为正表示这一拍还没到（age 在拍点之前），为负表示已经过了。
     */
    public static int nearest(int age, int[] out) {
        int lo = 0;
        int hi = TICKS.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (TICKS[mid] < age) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        int best = lo;
        if (lo > 0 && Math.abs(TICKS[lo - 1] - age) <= Math.abs(TICKS[lo] - age)) {
            best = lo - 1;
        }
        if (out != null) {
            out[0] = TICKS[best] - age;
        }
        return best;
    }

    /**
     * 从 {@code tick} 起（含）往后连续 {@code count} 个实体拍点的刻。
     *
     * <p>给「NOPE too late」用：三个词刚好压在三个真键上，玩家看着词打、词就是键。
     */
    public static int[] beatsFrom(int tick, int count) {
        int[] out = new int[count];
        int index = 0;
        while (index < TICKS.length - 1 && TICKS[index] < tick) {
            index++;
        }
        for (int i = 0; i < count; i++) {
            out[i] = tickAt(index + i);
        }
        return out;
    }

    /** {@code age} 所在的是不是「真实鼓点区间」（前奏和收尾不计分）。 */
    public static boolean scores(int age) {
        return age >= SCORE_FROM && age <= SCORE_TO;
    }
}
