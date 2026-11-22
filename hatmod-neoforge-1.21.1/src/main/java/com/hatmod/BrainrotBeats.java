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
    /**
     * 前奏的终点：原曲 21.358 秒 = 427 刻。**这是两条链路的分界**。
     *
     * <p>0 – 427：前奏链路（整段黑暗、不给加成、按拍击退）；
     * 427 之后：正片链路，所有时间点都用 {@link #main(int)} 表达成「正片自己的第几刻」。
     */
    public static final int INTRO_END = 427;

    /** 正片链路的第 {@code tick} 刻，落在绝对时间轴的第几刻。 */
    public static int main(int tick) {
        return tick + INTRO_END;
    }

    public static final int NO_TOO_LATE_FIRST = main(260);
    public static final int NO_TOO_LATE_SECOND = main(1398);
    /** 一个词隔多少刻（= 倒计时 READY / SET / GO 的间隔）：9 刻 ≈ 0.45 秒，和口白的实际语速一致。 */
    public static final int SPEECH_STEP = 9;
    /** 那三个白键对应的词：按中之后判定字直接写这个词（不再写 3 / 2 / 1）。 */
    public static final String[] SPEECH_WORDS = {"Nope", "too", "late"};
    /**
     * 台词拍前后多少刻内的**普通键**要挪走：±10 刻（0.5 秒）。
     *
     * <p>原来是 ±4，只躲开了「同一个鼓点」。但整句「Nope / too / late」本来就该把舞台让给字幕
     * 和 Teto 的动作（这三拍由服务端代按），前后留白 0.5 秒，词才砸得干净。
     */
    private static final int SPEECH_WINDOW = 10;

    /**
     * 最后一个词打完后再留多少刻就收掉字幕（含它带出来的那些「隐藏」）。
     *
     * <p>原来是 26 刻（1.3 秒），整段收得偏晚；改成 16 刻（0.8 秒），正好提前 0.5 秒。
     * HUD 藏条、药水粒子、Teto 的负面场都读 {@link #speechAnchor}，跟着一起收。
     */
    public static final int SPEECH_TAIL = 6;

    /**
     * 拍网格（只给 HUD 画重音 / 判定条呼吸用，不再参与排谱）。
     *
     * <p>新谱面直接落在两条分轨的真实起音上，所以网格精度不再决定手感；这里仍保留
     * 原来拟合出来的 135.48 BPM 网格，让「小节头」的高亮和旧版视觉一致。
     */
    private static final double BEAT_TICKS = 8.8573D;
    /** 正片的拍网格原点（原曲实测 5.865 刻），落在绝对轴上要加前奏长度。 */
    private static final double BEAT_ORIGIN = 5.865D + INTRO_END;

    /** 这一刻在不在前奏里（前奏链路）。 */
    public static boolean inIntro(int age) {
        return age >= 0 && age < INTRO_END;
    }

    /**
     * 打拍的起手拍：**「NOPE」出声那一刻**（13 秒 = 260 刻）。
     *
     * <p>READY / SET / GO 和 NOPE / too / late 同刻（260 / 269 / 278），
     * 这三个词本身也已经写进拍表，所以从第一个词起，词到、键也到。
     */
    public static final int SCORE_START_TICK = main(260);

    /**
     * 最后一个真实鼓点（约 99.1 秒）。
     *
     * <p>**必须声明在 {@link #VOCAL} 之前**：它不再是一个字面量，静态初始化时
     * {@code buildVocal} 要读它；写在后面的话读到的还是 0，整张人声谱会被过滤光。
     */
    public static final int SCORE_TO = main(1999);

    /** 光束阶段：28.5–57 秒、71–100 秒（和 {@code BrainrotCycle.FIRE_START} 那几个数对齐）。 */
    private static final int BEAM_START = main(574);
    private static final int BEAM_END = main(1140);
    private static final int BEAM2_START = main(1420);
    private static final int BEAM2_END = main(2000);

    /** 最后一段黑暗从这一该开始（100 秒）：从这里起不再放键，判定条慢慢淡出。 */
    public static final int FINAL_DARK = main(2000);

    /** 示范长音：11 秒末尾起，一直拖到 No too late 的第一个实体拍（只是示范，不算分）。 */
    public static final int LEAD_HOLD_START = main(220);

    /**
     * 正片链路的第一次人声（HUD 的显形、READY / SET / GO 都从这里算）。
     *
     * <p>**只认 {@link #INTRO_END} 之后的唱词**：前奏里也有三句词，但前奏是另一条链路
     * （全程黑暗、不给加成、按拍击退），判定条和节拍板不该被提前拉进黑屏里。
     */
    public static int firstVocalTick() {
        for (int packed : VOCAL) {
            int tick = packed >>> 8;
            if (tick >= INTRO_END) {
                return tick;
            }
        }
        return SCORE_FROM;
    }

    /** 一拍取整多少刻（8.8573 → 9）。 */
    private static final int BEAT_ROUND = (int) Math.round(BEAT_TICKS);

    /** 前奏 HUD 的淡入时长：3 秒。 */
    public static final int HUD_FADE_TICKS = 60;

    /**
     * 前奏的第一句人声（144 刻 = 7.2 秒）。
     *
     * <p>**这是前奏链路自己的开场**，不是正片那句（{@link #firstVocalTick()}）。
     * 前奏的条、歌词、倒计时、演示键全部围着它排。
     */
    public static int introVocalTick() {
        for (int packed : VOCAL) {
            int tick = packed >>> 8;
            if (tick < INTRO_END) {
                return tick;
            }
        }
        return INTRO_END;
    }

    /** HUD 完全就绪的那一刻：人声之前 2 拍 —— 条、打拍反馈、歌词到这里必须全部亮满。 */
    public static int hudReadyTick() {
        return introVocalTick() - 2 * BEAT_ROUND;
    }

    /** HUD 开始淡入：就绪前 {@link #HUD_FADE_TICKS} 刻。 */
    public static int hudStartTick() {
        return hudReadyTick() - HUD_FADE_TICKS;
    }

    /** 倒计时的词数（READY / SET / GO）。 */
    public static final int COUNTDOWN_WORDS = 3;

    /** 倒计时第一个词离人声几拍：4 拍起，GO 正好落在人声前 2 拍。 */
    public static final int COUNTDOWN_LEAD_BEATS = 4;

    /** 倒计时第 {@code i} 个词落在第几刻：一拍一个，一个接一个。 */
    public static int countdownTick(int i) {
        return introVocalTick() - (COUNTDOWN_LEAD_BEATS - i) * BEAT_ROUND;
    }

    /** 演示键只有 2 个：人声之前的那 2 拍。 */
    public static final int DEMO_BEATS = 2;

    /** 第 {@code i} 个演示键（0 = 离人声最近的那一拍）。 */
    public static int demoTick(int i) {
        return introVocalTick() - (DEMO_BEATS - i) * BEAT_ROUND;
    }

    /**
     * 这一拍是不是**人声**（谱面里贴上人声轨起音的那些键）。
     *
     * <p>不是人声的就是间奏补进来的乐器 / 鼓点 —— HUD 用另一种颜色画，一眼分得出
     * 「这一下是唱词」还是「这一下是鼓」。
     */
    public static boolean isVocal(int tick) {
        return contains(CHART_VOCAL, tick);
    }

    /** 有序 int 表里有没有 {@code tick}。 */
    private static boolean contains(int[] sorted, int tick) {
        int lo = 0;
        int hi = sorted.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int value = sorted[mid];
            if (value < tick) {
                lo = mid + 1;
            } else if (value > tick) {
                hi = mid - 1;
            } else {
                return true;
            }
        }
        return false;
    }

    /**
     * 这一刻是不是小节头（每 4 拍一下）。
     *
     * <p>HUD 用它把重音画得更高一档：音游里「看得出哪下是重音」比「音符一样粗」重要得多。
     */
    public static boolean isBarBeat(int tick) {
        double beat = (tick - BEAT_ORIGIN) / BEAT_TICKS;
        long nearest = Math.round(beat);
        return Math.abs(beat - nearest) < 0.12D && (nearest & 3L) == 0L;
    }

    /**
     * 人声键：{@code (tick << 8) | 拍长}，从 {@link BrainrotLyrics} 的 QRC 逐字表直接推出来。
     *
     * <p>规则：拉丁字母连成的一段算**一个**键（Warning? / Don't / worry 各一下，
     * 而不是一个字母一下），日文按字；标点、空格、长音符号跳过。
     * 这就是「按歌词排谱」—— 键跟的是嗓子，不是鼓。
     */
    private static final int[] VOCAL = buildVocal();

    /**
     * 长音表（刻，{start, end}）：谱面编辑器导出的那一份（{@code chart (13).json}）。
     *
     * <p>导入时会做一次清理：间距 ≤ 1 刻的长音并成一根，夹在合并后区间里的短键全部去掉
     * （见 {@link #TICKS} 的说明）。这一版导出得干净 —— 41 根长音彼此不重叠、缝里也没有
     * 短键，清理规则保留着，下一版换谱面照样跑。
     *
     * <p>前奏段里的长音也保留（只画、前奏不计分），计分段内的照常判。
     */
    private static final int[][] HOLDS = {
            {199, 205}, {207, 214}, {278, 286}, {358, 373},
            {419, 430}, {440, 449}, {450, 458}, {462, 471},
            {472, 484}, {497, 502}, {514, 523}, {524, 530},
            {537, 546}, {547, 554}, {585, 591}, {592, 599},
            {606, 613}, {614, 620}, {641, 677}, {774, 783},
            {840, 852}, {928, 943}, {991, 999}, {1054, 1065},
            {1125, 1137}, {1215, 1229}, {1288, 1299}, {1578, 1586},
            {1587, 1595}, {1600, 1610}, {1611, 1620}, {1632, 1641},
            {1654, 1659}, {1660, 1667}, {1672, 1681}, {1682, 1689},
            {1722, 1728}, {1729, 1736}, {1747, 1758}, {1777, 1810},
            {1905, 1915}, {1976, 1989}, {2064, 2078}, {2125, 2135},
            {2190, 2200}, {2260, 2272}, {2350, 2364}, {2411, 2424},
    };

    /** 一共几根长音。 */
    public static final int HOLD_COUNT = HOLDS.length;

    /**
     * 全曲拍点（刻，从这一轮开始计时；20 刻 = 1 秒）。
     *
     * <p>这一版是**谱面编辑器**导出的主旋律谱（{@code chart (13).json}）：
     * <ul>
     *   <li>人声键按歌者的起唱点排，HUD 画成红色；伴奏键画成灰色，两者由
     *       {@link #CHART_VOCAL} 区分；</li>
     *   <li>「Nope / too / late」六个口白键照旧由服务端代按，前后 0.5 秒清空；</li>
     *   <li>这一版没有夹在长音里的短键（导入时仍会做一次合并清理，见 {@link #HOLDS}）；</li>
     *   <li>有五处挨得很近的键：458/461、532/535、559/562、1598/1600、1669/1672，
     *       最短只差 2 刻 —— 判定窗会自动收窄（最短到 {@code MIN_WINDOW} 的 ±3 刻，
     *       见 {@link #buildWindows()}）。</li>
     * </ul>
     */
    private static final int[] TICKS = {
            150, 154, 159, 163, 167, 172, 176, 180, 185, 189, 194, 199,
            207, 221, 225, 229, 234, 238, 243, 247, 252, 256, 261, 265,
            270, 274, 278, 291, 296, 300, 305, 309, 314, 318, 322, 327,
            331, 336, 340, 345, 350, 354, 358, 382, 386, 390, 399, 404,
            408, 419, 440, 450, 459, 462, 472, 487, 493, 497, 514, 524,
            532, 537, 547, 555, 559, 562, 567, 572, 585, 592, 602, 606,
            614, 622, 628, 632, 637, 641, 723, 733, 743, 752, 759, 765,
            770, 774, 793, 801, 810, 818, 826, 831, 836, 840, 864, 872,
            880, 888, 898, 903, 907, 911, 915, 920, 924, 928, 953, 957,
            961, 970, 975, 979, 991, 1008, 1015, 1023, 1031, 1041, 1045, 1050,
            1054, 1077, 1086, 1094, 1103, 1112, 1116, 1121, 1125, 1148, 1156, 1165,
            1174, 1183, 1188, 1192, 1197, 1201, 1206, 1210, 1215, 1237, 1241, 1246,
            1255, 1259, 1264, 1275, 1288, 1578, 1587, 1598, 1600, 1611, 1623, 1627,
            1632, 1654, 1660, 1669, 1672, 1682, 1691, 1696, 1700, 1705, 1712, 1718,
            1722, 1729, 1742, 1747, 1759, 1763, 1768, 1772, 1777, 1857, 1865, 1873,
            1882, 1892, 1896, 1901, 1905, 1928, 1936, 1945, 1953, 1963, 1967, 1972,
            1976, 1999, 2007, 2016, 2025, 2034, 2038, 2043, 2048, 2052, 2056, 2060,
            2064, 2088, 2092, 2097, 2106, 2111, 2115, 2125, 2142, 2150, 2159, 2167,
            2176, 2181, 2186, 2190, 2212, 2220, 2229, 2238, 2247, 2251, 2256, 2260,
            2284, 2292, 2300, 2310, 2319, 2323, 2328, 2332, 2336, 2341, 2346, 2350,
            2373, 2377, 2381, 2390, 2395, 2399, 2411,
    };

    /**
     * {@link #TICKS} 里哪些键是**人声**键（HUD 画成红色；其余是伴奏键，画成灰色）。
     *
     * <p>不能再拿 {@link #VOCAL} 直接比：新谱面的人声键是贴到人声轨起音上的，
     * 和 QRC 的逐字时刻会差 1–3 刻；这张表就是贴完之后的那一份。
     */
    private static final int[] CHART_VOCAL = {
            150, 154, 159, 163, 167, 172, 176, 180, 185, 189, 194, 199,
            207, 221, 225, 229, 234, 238, 243, 247, 252, 256, 261, 265,
            270, 274, 278, 291, 296, 300, 305, 309, 314, 318, 322, 327,
            331, 336, 340, 345, 350, 354, 358, 419, 440, 450, 459, 462,
            472, 487, 493, 497, 514, 524, 532, 537, 547, 555, 559, 562,
            567, 572, 585, 592, 602, 606, 614, 622, 628, 632, 637, 641,
            723, 733, 743, 752, 759, 765, 770, 774, 793, 801, 810, 818,
            826, 831, 836, 840, 864, 872, 880, 888, 898, 903, 907, 911,
            915, 920, 924, 928, 991, 1008, 1015, 1023, 1031, 1041, 1045, 1050,
            1054, 1077, 1086, 1094, 1103, 1112, 1116, 1121, 1125, 1148, 1156, 1165,
            1174, 1183, 1188, 1192, 1197, 1201, 1206, 1210, 1215, 1288, 1578, 1587,
            1598, 1600, 1611, 1623, 1627, 1632, 1654, 1660, 1669, 1672, 1682, 1691,
            1696, 1700, 1705, 1712, 1718, 1722, 1729, 1742, 1747, 1759, 1763, 1768,
            1772, 1777, 1857, 1865, 1873, 1882, 1892, 1896, 1901, 1905, 1928, 1936,
            1945, 1953, 1963, 1967, 1972, 1976, 1999, 2007, 2016, 2025, 2034, 2038,
            2043, 2048, 2052, 2056, 2060, 2064, 2125, 2142, 2150, 2159, 2167, 2176,
            2181, 2186, 2190, 2212, 2220, 2229, 2238, 2247, 2251, 2256, 2260, 2284,
            2292, 2300, 2310, 2319, 2323, 2328, 2332, 2336, 2341, 2346, 2350, 2411,
    };

    /**
     * 示范键（ghost）：只画不算分，和 {@link #TICKS} 分开存，所以不会进判定。
     *
     * <p>以前 HUD 用「427–687 刻」这个硬编码区间画示范键；现在改成一张显式的表，
     * 编辑器可以把任意位置的键标成示范，导出时写进这里。
     */
    private static final int[] DEMO = {
            24, 30, 59, 66, 96, 102, 128, 136, 1295, 1299, 1303, 1315,
            1317, 1342, 1347, 1369, 1372, 1387, 1391, 1405, 1409, 1413, 1417, 1422,
            1437, 1443, 1445, 1459, 1463, 1479, 1485, 1489, 1507, 1511, 1515, 1529,
            1533, 1545, 1549, 1553, 1557, 1561, 1565,
    };

    /**
     * 示范长音（刻，{start, end}）：和 {@link #HOLDS} 一样是长条，但只画不判。
     *
     * <p>编辑器里把一根长音设成「示范」就会导出到这里 —— HUD 画成白色虚线，
     * 到点不消失、不计连击，用来教玩家「这一句要一直按住」。
     */
    private static final int[][] DEMO_HOLDS = {
            {2, 15}, {38, 51}, {72, 87}, {108, 126},
            {1307, 1312}, {1321, 1330}, {1333, 1339}, {1351, 1361},
            {1377, 1385}, {1395, 1403}, {1426, 1435}, {1449, 1457},
            {1467, 1475}, {1493, 1503}, {1519, 1528}, {1537, 1544},
    };

    /** 示范长音一共几根。 */
    public static final int DEMO_HOLD_COUNT = DEMO_HOLDS.length;

    /**
     * 每一拍自己的判定窗（刻）：左右邻居隔得近就自动收紧。
     *
     * <p>原来一刀切 ±6 刻，可八分音符只隔 4.4 刻 —— 按晚一点就会被判到**下一拍**上，
     * 手感是「粘」和「抢拍」。现在窗口收到邻居间隔的一半（最少 3 刻），谁的拍就归谁。
     */
    private static final int[] WINDOWS = buildWindows();
    /** 判定窗上限（和 {@code BrainrotQte.GOOD_TICKS} 同一个数）。 */
    private static final int MAX_WINDOW = 6;
    /** 判定窗下限（刻）：以前是 2，连打处只剩 ±2 刻（±100ms），手感太硬。 */
    private static final int MIN_WINDOW = 3;
    /**
     * 晚按的额外宽限（刻）。
     *
     * <p>人跟拍天然偏晚：眼睛看到键压线再动手，落地总会迟一两刻。以前早晚同宽，
     * 压在窗尾那一半的按键全被当成「按空了」，紧接着过窗判成 MISS。现在早按还是
     * ±窗口，晚按多给 {@link #LATE_GRACE} 刻，过窗判定也用这个加长后的窗。
     */
    public static final int LATE_GRACE = 3;

    private static int[] buildWindows() {
        int[] windows = new int[TICKS.length];
        for (int i = 0; i < TICKS.length; i++) {
            int gap = MAX_WINDOW * 2;
            if (i > 0) {
                gap = Math.min(gap, TICKS[i] - TICKS[i - 1]);
            }
            if (i + 1 < TICKS.length) {
                gap = Math.min(gap, TICKS[i + 1] - TICKS[i]);
            }
            windows[i] = Math.max(MIN_WINDOW, Math.min(MAX_WINDOW, gap / 2));
        }
        return windows;
    }

    /** 第 {@code index} 拍允许的偏差（刻）—— 早按那一侧。 */
    public static int windowAt(int index) {
        if (index < 0 || index >= WINDOWS.length) {
            return MAX_WINDOW;
        }
        return WINDOWS[index];
    }

    /** 第 {@code index} 拍**晚按**的宽限（刻）：判定窗 + {@link #LATE_GRACE}。 */
    public static int lateWindowAt(int index) {
        return windowAt(index) + LATE_GRACE;
    }

    /** QRC 逐字表 → 人声键表（见 {@link #VOCAL}）。 */
    private static int[] buildVocal() {
        java.util.TreeMap<Integer, Integer> map = new java.util.TreeMap<>();
        int lastTick = -1000;
        for (int line = 0; line < BrainrotLyrics.count(); line++) {
            int start = BrainrotLyrics.tickAt(line);
            String text = BrainrotLyrics.textAt(line);
            int[] cue = BrainrotLyrics.cue(line);
            boolean inLatinRun = false;
            for (int i = 0; i < cue.length && i < text.length(); i++) {
                char c = text.charAt(i);
                if (isLatinLetter(c)) {
                    if (inLatinRun) {
                        continue;   // 同一个词里的后续字母，不另起一个键
                    }
                    inLatinRun = true;
                } else if (isSingable(c)) {
                    inLatinRun = false;
                } else {
                    continue;       // 标点 / 空格：不打断拉丁词
                }
                int tick = start + (cue[i] >>> 8) - BrainrotLyrics.CUE_BIAS;
                int dur = cue[i] & 0xFF;
                if (tick < 6 || tick > SCORE_TO) {
                    continue;
                }
                // 同一口气里挤在 50ms 内的两个字（QRC 对齐的毛刺）只留第一个：
                // 一个键 100ms 已经是最快的十六分了，再密就按不出来
                if (tick - lastTick < 2) {
                    continue;
                }
                lastTick = tick;
                Integer last = map.get(tick);
                if (last == null || dur > last) {
                    map.put(tick, dur);
                }
            }
        }
        int[] result = new int[map.size()];
        int i = 0;
        for (java.util.Map.Entry<Integer, Integer> entry : map.entrySet()) {
            result[i++] = (entry.getKey() << 8) | entry.getValue();
        }
        return result;
    }

    private static boolean isLatinLetter(char c) {
        return c < 0x3000 && Character.isLetter(c);
    }

    /** 能唱的字：不是空白、不是标点和长音符号。 */
    private static boolean isSingable(char c) {
        if (Character.isWhitespace(c)) {
            return false;
        }
        return "（）()?!.,、。・'’\"“”「」ー…-—".indexOf(c) < 0;
    }

    /** 这一刻是不是落在光束阶段（给 HUD 换配色用）。 */
    public static boolean inBeam(int tick) {
        return (tick >= BEAM_START && tick < BEAM_END)
                || (tick >= BEAM2_START && tick < BEAM2_END);
    }

    /** 光束段的进出各留多少刻做渐变，别让整条谱面「啪」地弹一下。 */
    private static final int BEAM_EDGE_TICKS = 10;
    /** 光束期谱面滚动倍率。 */
    private static final float BEAM_SCROLL = 1.8F;

    /**
     * 谱面滚动倍率：光束在放的时候键跑得更快（判定窗仍是按刻算的，所以只是更赶、不是更难判）。
     * 进出各用 {@link #BEAM_EDGE_TICKS} 刻平滑过渡。
     */
    public static float scrollFactor(int age) {
        return 1.0F + (BEAM_SCROLL - 1.0F) * beamBlend(age);
    }

    /** 0 = 不在光束段，1 = 完整光束段，中间是进出渐变。 */
    public static float beamBlend(int age) {
        return Math.max(edgeBlend(age, BEAM_START, BEAM_END),
                edgeBlend(age, BEAM2_START, BEAM2_END));
    }

    private static float edgeBlend(int age, int start, int end) {
        if (age < start - BEAM_EDGE_TICKS || age > end + BEAM_EDGE_TICKS) {
            return 0.0F;
        }
        if (age < start) {
            return (age - (start - BEAM_EDGE_TICKS)) / (float) BEAM_EDGE_TICKS;
        }
        if (age <= end) {
            return 1.0F;
        }
        return 1.0F - (age - end) / (float) BEAM_EDGE_TICKS;
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

    /**
     * 计分的第一拍：**前奏结束后的第一个谱面键**。
     *
     * <p>旧谱面前奏之后（427–687）只放示范键，所以这里等价于 687；但现在的谱面是从 0.1 秒
     * 一路排下来的，如果还卡在 687，第一段二十多个真键就白按了。改成跟着谱面走：
     * 前奏仍然只击退不计分，前奏一结束就进判定。
     */
    public static final int SCORE_FROM = firstAtOrAfter(INTRO_END);

    /** 长音表本身在上面的 {@link #HOLDS}（要排在拍表之前，生成谱面时要用）。 */

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
        return HOLDS[index][0];
    }

    /** 第 {@code index} 根长音的止刻（不含）。 */
    public static int holdEnd(int index) {
        return HOLDS[index][1];
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

    /** 示范键总数。 */
    public static int demoNoteCount() {
        return DEMO.length;
    }

    /** 第 {@code index} 个示范键的刻（越界夹到两端）。 */
    public static int demoNoteTick(int index) {
        if (index < 0) {
            return DEMO[0];
        }
        if (index >= DEMO.length) {
            return DEMO[DEMO.length - 1];
        }
        return DEMO[index];
    }

    /** 第 {@code index} 根示范长音的起刻。 */
    public static int demoHoldStart(int index) {
        return DEMO_HOLDS[index][0];
    }

    /** 第 {@code index} 根示范长音的止刻（不含）。 */
    public static int demoHoldEnd(int index) {
        return DEMO_HOLDS[index][1];
    }

    /** 示范长音总数。 */
    public static int demoHoldCount() {
        return DEMO_HOLDS.length;
    }

    /** {@code tick} 附近（±{@code tolerance} 刻）有没有谱面实键。 */
    public static boolean hasNoteNear(int tick, int tolerance) {
        if (TICKS.length == 0) {
            return false;
        }
        int[] out = new int[1];
        nearest(tick, out);
        return Math.abs(out[0]) <= tolerance;
    }

    /** {@code tick} 附近（±{@code tolerance} 刻）有没有示范键。 */
    public static boolean hasDemoNear(int tick, int tolerance) {
        for (int d : DEMO) {
            if (Math.abs(d - tick) <= tolerance) {
                return true;
            }
        }
        return false;
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

    // ------------------------------------------------------------------
    // 伴奏层（牛仔帽）：全自动播放，只负责积累闪避
    // ------------------------------------------------------------------

    /** 伴奏层全自动：每自动按下多少颗换 1 次闪避（一次只存 1 次，HUD 用同一个数算进度）。 */
    public static final int BONUS_PER_DODGE = 10;

    /**
     * 伴奏层（牛仔帽）的时刻表：取自伴奏 / 卡点谱。
     *
     * <p>**不进主旋律**（不在 {@link #TICKS} 里），也**不需要玩家按**：
     * 谱子每走到一颗就自动「按」一下（HUD 白闪反馈），每 {@link #BONUS_PER_DODGE}
     * 颗换 1 次闪避。**一次只存 1 次**：攒满后暂停，等玩家把闪避用掉，再从头攒 10 颗。
     * 漏不漏、准不准都不判 —— 这一层只是观赏 + 攒闪避。
     */
    private static final int[] BONUS = {
            440, 454, 467, 475, 489, 498, 506, 525, 533, 542, 560, 569,
            578, 595, 603, 612, 629, 639, 648, 665, 674, 725, 738, 746,
            755, 774, 782, 791, 809, 818, 826, 845, 854, 862, 880, 889,
            897, 916, 925, 932, 952, 961, 970, 986, 995, 1004, 1011, 1019,
            1023, 1027, 1032, 1034, 1037, 1040, 1048, 1054, 1059, 1063, 1065, 1068,
            1071, 1077, 1083, 1091, 1095, 1099, 1103, 1106, 1108, 1112, 1117, 1122,
            1129, 1133, 1135, 1138, 1141, 1144, 1149, 1154, 1161, 1166, 1169, 1174,
            1177, 1179, 1182, 1186, 1190, 1198, 1202, 1205, 1207, 1211, 1213, 1217,
            1220, 1225, 1232, 1236, 1239, 1244, 1247, 1250, 1252, 1256, 1261, 1266,
            1272, 1275, 1279, 1281, 1285, 1288, 1290, 1297, 1306, 1310, 1313, 1316,
            1319, 1322, 1327, 1332, 1342, 1345, 1348, 1351, 1354, 1357, 1362, 1368,
            1373, 1378, 1381, 1387, 1391, 1394, 1397, 1402, 1406, 1415, 1417, 1420,
            1424, 1427, 1432, 1439, 1444, 1450, 1453, 1456, 1459, 1462, 1466, 1469,
            1475, 1481, 1486, 1490, 1493, 1496, 1499, 1504, 1510, 1514, 1520, 1524,
            1527, 1530, 1533, 1536, 1540, 1545, 1550, 1556, 1559, 1562, 1565, 1569,
            1572, 1577, 1586, 1596, 1607, 1616, 1626, 1635, 1644, 1655, 1665, 1675,
            1686, 1697, 1708, 1720, 1732, 1745, 1758, 1771, 1783, 1796, 1809, 1821,
            1833, 1845, 1861, 1868, 1874, 1877, 1881, 1884, 1887, 1890, 1895, 1901,
            1908, 1911, 1914, 1916, 1919, 1922, 1926, 1932, 1941, 1944, 1948, 1953,
            1956, 1959, 1963, 1966, 1972, 1979, 1982, 1985, 1987, 1990, 1994, 1998,
            2004, 2012, 2015, 2019, 2024, 2027, 2030, 2033, 2037, 2042, 2051, 2054,
            2057, 2060, 2063, 2069, 2074, 2082, 2085, 2088, 2094, 2098, 2101, 2103,
            2109, 2117, 2121, 2124, 2127, 2129, 2132, 2135, 2140, 2148, 2152, 2155,
            2158, 2162, 2164, 2167, 2170, 2173, 2177, 2185, 2187, 2193, 2196, 2198,
            2201, 2204, 2207, 2211, 2217, 2220, 2225, 2228, 2236, 2239, 2241, 2244,
            2248, 2252, 2258, 2263, 2266, 2269, 2272, 2275, 2278, 2282, 2288, 2297,
            2301, 2306, 2309, 2312, 2315, 2318, 2323, 2329, 2336, 2339, 2342, 2345,
            2348, 2351, 2356, 2360, 2365, 2369, 2374, 2379, 2382, 2384, 2390, 2394,
            2399, 2406, 2409, 2412, 2415, 2419,
    };



    /** 白键总数。 */
    public static int bonusCount() {
        return BONUS.length;
    }

    /** 第 {@code index} 颗白键的刻。 */
    public static int bonusTick(int index) {
        if (index < 0) {
            return BONUS.length == 0 ? 0 : BONUS[0];
        }
        if (index >= BONUS.length) {
            return BONUS.length == 0 ? 0 : BONUS[BONUS.length - 1];
        }
        return BONUS[index];
    }

    /** 离 {@code age} 最近、且在 {@code window} 刻内的白键下标（没有就 -1）。 */
    public static int bonusIndexAt(int age, int window) {
        if (BONUS.length == 0) {
            return -1;
        }
        int lo = 0;
        int hi = BONUS.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (BONUS[mid] < age) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        int best = lo;
        if (lo > 0 && Math.abs(BONUS[lo - 1] - age) <= Math.abs(BONUS[lo] - age)) {
            best = lo - 1;
        }
        return Math.abs(BONUS[best] - age) <= window ? best : -1;
    }

    /** 到 {@code age} 为止，已经自动按下了多少颗伴奏键（含正好落在这一刻的）。 */
    public static int bonusPassed(int age) {
        int lo = 0;
        int hi = BONUS.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (BONUS[mid] <= age) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /**
     * 「Nope / too / late」那一排字在屏幕上的窗口：在的话返回这一句的锚点刻（第一个词），
     * 不在返回 -1。HUD 藏条 / 藏歌词、客户端压药水粒子，全都用这一个判断。
     */
    public static int speechAnchor(int age) {
        int first = NO_TOO_LATE_FIRST;
        int second = NO_TOO_LATE_SECOND;
        int anchor = Math.abs(age - second) <= Math.abs(age - first) ? second : first;
        int last = anchor + SPEECH_STEP * (SPEECH_WORDS.length - 1);
        return age < anchor - 8 || age > last + SPEECH_TAIL ? -1 : anchor;
    }
}
