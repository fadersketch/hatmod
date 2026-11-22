package com.hatmod.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.hatmod.HatMod;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Teto 的渲染参数（**纯客户端**，不进服务端那份 {@code config/hatmod.json}）。
 *
 * <p>显存的大头不是「播放多快」，而是那张序列图本身。现在是 373 个姿势、3840×4608、
 * 每格 160×288，解压后约 **67 MB**（RGBA）。
 *
 * <p>为什么要 371 帧：更早那版是 284 帧，抽帧方式是「每 3 帧取 1 帧」（10fps），
 * 而源视频的姿势大约 2.28 帧换一次 —— 采样周期 3 和姿势周期 2.28 拍不到一起，
 * 一部分姿势被抽了两遍、另一部分**一帧都没留下**，动作看起来就是缺帧。
 * 现在按**独有姿势**一个一个出图，一个姿势都不丢；播放用比例映射补回时长（见
 * {@link #INTRO_UNITS}）。
 * 所以这里拆成两个互不影响的旋钮：
 * <ul>
 *   <li><b>帧率</b>：{@link #frameTicks()}（每帧占几刻）+ {@link #frameStep()}（每次跨几帧），
 *       实际帧率 = 20 / (frameTicks × frameStep)。只影响观感，不影响显存。</li>
 *   <li><b>画质</b>：{@link #quality()} 选哪张贴图 —— 0 原尺寸 / 1 一半 / 2 四分之一，
 *       显存分别约 67 MB / 17 MB / 4 MB（含 mipmap 会再高一点）。</li>
 * </ul>
 * 于是「低帧率 + 高画质」（帧步进拉大、画质 0）和「高帧率 + 省显存」都能单独试。
 *
 * <p>文件：{@code config/hatmod-client.json}。坏文件不会崩游戏，直接落回默认值。
 */
public final class TetoRenderSettings {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "hatmod-client.json";

    /** 序列图里**一共存了**多少个姿势（= 前奏 183 + 循环 190，和 TetoRenderer 的排布一致）。 */
    public static final int FRAME_COUNT =
            TetoFrameSchedule.INTRO_POSES + TetoFrameSchedule.LOOP_POSES;

    /** 前奏有多少个姿势：这 183 个只播一次 —— 里面含着「Nope, too late」那一下的特殊动作。 */
    public static final int INTRO_POSES = TetoFrameSchedule.INTRO_POSES;

    /** 循环段有多少个姿势：这 190 个摆完就回到第 183 格（接缝实测 {@code diff < 1}）。 */
    public static final int LOOP_POSES = TetoFrameSchedule.LOOP_POSES;

    /**
     * 前奏在原视频里占多少帧（视频 640~1068，429 帧 = 286 刻 = 14.3 秒）。
     *
     * <p>帧号一律按**原视频的帧**算，不按刻 —— 见 {@link TetoFrameSchedule}。
     */
    public static final int INTRO_VIDEO_FRAMES = TetoFrameSchedule.INTRO_FRAMES;

    /**
     * 循环节在原视频里占多少帧（视频 1069~1492，424 帧 = 282.67 刻 = 14.13 秒）。
     *
     * <p>这个数是实测出来的：把 30fps 的视频里间隔 P 帧的两帧逐对相减，P=424 时平均差掉到
     * 0.28（相邻候选 423 是 3.66、425 是 4.74），所以周期就是整数 424 帧。
     * 用刻去循环（282 刻）每圈会少 2/3 刻，十圈就偏出 0.3 秒 —— 这就是「看久了有点不一样」的来源。
     */
    public static final int LOOP_VIDEO_FRAMES = TetoFrameSchedule.LOOP_FRAMES;

    /** 1 刻 = 1.5 帧（游戏 20 刻/秒，原视频 30fps）。 */
    private static final float FRAMES_PER_TICK = 1.5F;

    public static final int DEFAULT_FRAME_TICKS = 2;
    public static final int DEFAULT_FRAME_STEP = 1;
    public static final int DEFAULT_QUALITY = 0;

    /**
     * 序列帧的相位补偿（刻）：播放时把时间轴整体**延后**这么多刻再取帧。
     *
     * <p>出厂值是 **0**（不补偿）。以前这里写的是 7 —— 当时的依据是「绿幕源视频的音轨比
     * {@code music_brainrot.ogg} 晚 0.34 秒」，于是把画面往回压了 7 刻去对齐；
     * 但实际进游戏看，动作反而整体**慢半拍**（谱面里对好的点，画面上总晚一点踩到）。
     * 所以现在归零：谱子第几刻就是第几帧，不再做任何平移。
     *
     * <p>如果以后还想微调（比如换了素材、或觉得应该抢拍），改
     * {@code config/hatmod-client.json} 里的 {@code tetoFrameOffset} 就行：
     * 正数 = 画面更晚，0 = 不补偿。
     */
    public static final int DEFAULT_FRAME_OFFSET = 0;
    public static final int MAX_QUALITY = 2;

    private static final ResourceLocation TEXTURE_FULL = HatMod.id("textures/entity/teto.png");
    private static final ResourceLocation TEXTURE_HALF = HatMod.id("textures/entity/teto_half.png");
    private static final ResourceLocation TEXTURE_QUARTER = HatMod.id("textures/entity/teto_quarter.png");

    private static final ResourceLocation TEXTURE_ALT = HatMod.id("textures/entity/teto_alt.png");
    private static final ResourceLocation TEXTURE_ALT_HALF = HatMod.id("textures/entity/teto_alt_half.png");
    private static final ResourceLocation TEXTURE_ALT_QUARTER =
            HatMod.id("textures/entity/teto_alt_quarter.png");

    private static final ResourceLocation TEXTURE_ACT3 = HatMod.id("textures/entity/teto_act3.png");
    private static final ResourceLocation TEXTURE_ACT3_HALF =
            HatMod.id("textures/entity/teto_act3_half.png");
    private static final ResourceLocation TEXTURE_ACT3_QUARTER =
            HatMod.id("textures/entity/teto_act3_quarter.png");

    private static final ResourceLocation TEXTURE_JOIN = HatMod.id("textures/entity/teto_join.png");
    private static final ResourceLocation TEXTURE_JOIN_HALF =
            HatMod.id("textures/entity/teto_join_half.png");
    private static final ResourceLocation TEXTURE_JOIN_QUARTER =
            HatMod.id("textures/entity/teto_join_quarter.png");

    /** 动作 0 = 原来那套（曲子编排的那段舞）。 */
    public static final int ACTION_ORIGINAL = 0;
    /** 动作 1 = 「Let's get it!!」那套；动作 2 = 第三段（微信视频那段）。右键按这个顺序循环。 */
    public static final int ACTION_ACT3 = 2;
    /** 动作 3 = 动作 1 + 动作 2 连起来的完整版（接缝处重复的保持段已删）。 */
    public static final int ACTION_JOINED = 3;
    public static final int ACTION_COUNT = 4;

    /**
     * 连跳版里「动作 1」占多少帧：之后接动作 2。
     *
     * <p>两段在接缝处都是「双手抱头」的保持姿势，动作 1 留到第 148 帧、
     * 动作 2 从第 12 帧开始接（剪影 IoU ≈ 0.79）。这个切点后来被**音频对齐**独立验证过：
     * 实测视频 2 的第 0 秒 = 视频 1 的第 13.57 秒（两段音轨首尾重合约 2.2 秒），
     * 也就是视频 1 的第 148 帧 ↔ 视频 2 的第 12 帧，和剪影选出来的正好是同一刀。
     */
    public static final int JOIN_ALT_FRAMES = 148;
    /** 连跳版一共多少帧：148 + (189 - 12)。 */
    public static final int JOIN_FRAME_COUNT = JOIN_ALT_FRAMES + 177;

    /**
     * 动作 0 的排布：373 个姿势、24 列 × 16 行、每格 160×288（贴图 3840×4608）。
     *
     * <p>格子从 170×306 缩到 160×288：源视频只有 356×640，再大也只是插值，
     * 缩一点点正好把多出来那 89 帧的显存抵掉。
     */
    private static final int COLUMNS = 24;
    private static final int ROWS = 16;
    private static final int CELL_W = 160;
    private static final int CELL_H = 288;

    /**
     * 动作 1 的排布：源视频 16 秒、按每秒 10 帧抽（片尾黑场已切），
     * 24 列 × 7 行、每格 210×306，整张 5040×2142。
     */
    private static final int ALT_COLUMNS = 24;
    private static final int ALT_ROWS = 7;
    private static final int ALT_CELL_W = 210;
    private static final int ALT_CELL_H = 306;
    /** 动作 1 一共多少帧：源视频 15.8 秒、每秒 10 帧（生成脚本里会打印，改之前先对一下）。 */
    public static final int ALT_FRAME_COUNT = 157;

    /**
     * 三套新动作（1 / 2 / 3）的格子：210×306、24 列，人物统一按「中位身高 229 px、
     * 脚底基线 279」排版 —— 和原版那套（216/288 ≈ 229/306、脚底离格底 25/288 ≈ 27/306）
     * 的世界尺寸完全一致，所以渲染时四边形**不再放大**（contentScale = 1），
     * 换动作前后她的身高、脚的位置都不会变。
     *
     * <p>格子宽从 170 放宽到 210：720p 素材里有几帧手臂完全张开（人物宽 ≈ 0.87 × 身高），
     * 170 宽的格子会把超过边界的半边身子直接切掉 —— 这就是之前「游戏里只剩半个身子」的原因。
     */

    /**
     * 动作 2 的排布：源视频 18.93 秒、按每秒 10 帧抽成 189 帧，
     * 24 列 × 8 行、每格同样是 210×306，整张 5040×2448。
     */
    private static final int ACT3_COLUMNS = 24;
    private static final int ACT3_ROWS = 8;
    private static final int ACT3_CELL_W = 210;
    private static final int ACT3_CELL_H = 306;
    /** 动作 2 一共多少帧：源视频 18.93 秒、每秒 10 帧（189 帧 × 2 刻 = 18.9 秒）。 */
    public static final int ACT3_FRAME_COUNT = 189;

    /** 动作 3（连跳版）的排布：325 帧，24 列 × 14 行，每格 214×306。 */
    private static final int JOIN_COLUMNS = 24;
    private static final int JOIN_ROWS = 14;
    private static final int JOIN_CELL_W = 214;
    private static final int JOIN_CELL_H = 306;
    /**
     * 一套动作序列图：贴图 + 排布 + 帧数。
     *
     * <p>两套的格子尺寸一样，只有行数和帧数不同；渲染那边按这个结构取 UV。
     */
    public static final class Sheet {
        public final ResourceLocation texture;
        public final int columns;
        public final int rows;
        public final int cellW;
        public final int cellH;
        public final int frameCount;
        /** 四边形相对「满格」要放大多少（1 = 格子里的人物正好撑满）。 */
        public final float contentScale;

        private Sheet(ResourceLocation texture, int columns, int rows,
                      int cellW, int cellH, int frameCount, float contentScale) {
            this.texture = texture;
            this.columns = columns;
            this.rows = rows;
            this.cellW = cellW;
            this.cellH = cellH;
            this.frameCount = frameCount;
            this.contentScale = contentScale;
        }
    }

    /** 取某个动作当前画质下的那张图 / 那套排布。 */
    public static Sheet sheet(int action) {
        ensureLoaded();
        if (action == ACTION_ORIGINAL) {
            return new Sheet(texture(), COLUMNS, ROWS, CELL_W, CELL_H, FRAME_COUNT, 1.0F);
        }
        if (action == ACTION_JOINED) {
            return new Sheet(joinTexture(), JOIN_COLUMNS, JOIN_ROWS, JOIN_CELL_W, JOIN_CELL_H,
                    JOIN_FRAME_COUNT, 1.0F);
        }
        if (action == ACTION_ACT3) {
            return new Sheet(act3Texture(), ACT3_COLUMNS, ACT3_ROWS, ACT3_CELL_W, ACT3_CELL_H,
                    ACT3_FRAME_COUNT, 1.0F);
        }
        return new Sheet(altTexture(), ALT_COLUMNS, ALT_ROWS, ALT_CELL_W, ALT_CELL_H,
                ALT_FRAME_COUNT, 1.0F);
    }

    /** 某个动作一共多少个姿势（整段循环的圈长）。 */
    public static int frameCount(int action) {
        if (action == ACTION_ORIGINAL) {
            return FRAME_COUNT;
        }
        if (action == ACTION_JOINED) {
            return JOIN_FRAME_COUNT;
        }
        return action == ACTION_ACT3 ? ACT3_FRAME_COUNT : ALT_FRAME_COUNT;
    }

    /** 动作 1 / 2 的帧号：整段循环（源视频本身就是一段循环舞）。 */
    public static int actionFrameIndex(int action, int tickCount) {
        ensureLoaded();
        if (action == ACTION_ORIGINAL) {
            return frameIndex(tickCount);
        }
        // 还是「一张图一帧」的老节奏：刻数除以倍速，得到「第几张图」
        int denom = Math.max(1, frameTicks) * Math.max(1, frameStep);
        int played = (int) (Math.max(0, tickCount - frameOffset) / (float) denom);
        return played % Math.max(1, frameCount(action));
    }

    private static int frameTicks = DEFAULT_FRAME_TICKS;
    private static int frameStep = DEFAULT_FRAME_STEP;
    private static int quality = DEFAULT_QUALITY;
    private static int frameOffset = DEFAULT_FRAME_OFFSET;
    /** 动作 2 的舞曲是不是静音（木棍右键切换，见 {@code TetoDanceMusic}）。 */
    private static boolean danceMuted;
    private static boolean loaded;

    private TetoRenderSettings() {
    }

    /**
     * 第 {@code tickCount} 刻该显示第几帧。
     *
     * <p>两段式：前 {@link #INTRO_POSES} 个姿势只播一次（含那一下特殊动作），
     * 之后在 {@link #LOOP_POSES} 个姿势里循环。
     *
     * <p>刻数先换成正片里的位置，再换成原视频的帧号（{@code videoFrameAt}），最后查
     * {@link TetoFrameSchedule} —— 每个姿势都在原视频里它原本的那一帧出现，一个不落。
     */
    public static int frameIndex(int tickCount) {
        ensureLoaded();
        return chainFrame(tickCount);
    }

    /**
     * 链路里第 {@code ageTicks} 刻（**可以带小数**，渲染时把 partialTick 加进来）该显示第几帧。
     *
     * <p>先换算成原视频的帧号，再查表：前奏 429 帧放完就接循环段的 424 帧。
     * 因为位置是整数帧号，循环一圈正好回到原视频的同一帧，永远不会累积漂移。
     */
    public static int chainFrame(float ageTicks) {
        ensureLoaded();
        int frame = videoFrameAt(ageTicks);
        if (frame < INTRO_VIDEO_FRAMES) {
            return TetoFrameSchedule.introPose(frame);
        }
        return INTRO_POSES + TetoFrameSchedule.loopPose(
                (frame - INTRO_VIDEO_FRAMES) % LOOP_VIDEO_FRAMES);
    }

    /** 只走循环段（前奏之前那 21 秒用：跳掉里面那一下站定）。 */
    public static int loopFrame(float ageTicks) {
        ensureLoaded();
        return INTRO_POSES + TetoFrameSchedule.loopPose(
                videoFrameAt(ageTicks) % LOOP_VIDEO_FRAMES);
    }

    /**
     * 只取循环段里的帧（前奏用）：不播前 {@link #INTRO_VIDEO_FRAMES} 帧，
     * 也就是**跳掉里面那一下站定**，画面一直在重复段里转。
     */
    public static int loopFrameIndex(int tickCount) {
        ensureLoaded();
        return loopFrame(tickCount);
    }

    /**
     * 某套动作整段循环一圈要多少刻（帧数 × 每帧几刻）。
     *
     * <p>舞曲的钟（{@code TetoDanceMusic}）拿它判断「画面已经放完了、该停在最后一帧了」。
     */
    public static int loopTicks(int action) {
        ensureLoaded();
        return frameCount(action) * Math.max(1, frameTicks) * Math.max(1, frameStep);
    }

    /**
     * 动作 1 / 2 的帧号（带实体版）：**有舞曲跟着的那只用舞曲的钟**，其余照旧按实体自己的刻数循环。
     *
     * <p>这样画面和音乐只有一个来源 —— 音乐什么时候重开，画面就什么时候回到第 0 帧。
     */
    public static int actionFrameIndex(int action, Entity entity) {
        ensureLoaded();
        if (action != ACTION_ORIGINAL) {
            int phase = TetoDanceMusic.phaseOf(entity);
            if (phase >= 0) {
                int step = Math.max(1, frameTicks) * Math.max(1, frameStep);
                return (phase / step) % Math.max(1, frameCount(action));
            }
        }
        return actionFrameIndex(action, entity.tickCount);
    }

    /**
     * 把刻数（可以带小数）换成**原视频的帧号**。
     *
     * <p>先扣掉相位补偿（{@link #frameOffset}），再按 {@code frameTicks × frameStep}
     * 当倍速旋钮缩放（默认 2 × 1 = 1 倍速），最后乘 1.5：一刻 1/20 秒、原视频 30fps，
     * 所以一刻正好 1.5 帧。
     */
    private static int videoFrameAt(float ticks) {
        int denom = Math.max(1, frameTicks) * Math.max(1, frameStep);
        float natural = Math.max(0.0F, ticks - frameOffset) * DEFAULT_FRAME_TICKS / denom;
        return (int) Math.min(Integer.MAX_VALUE, natural * FRAMES_PER_TICK);
    }


    /** 动作 1 当前画质对应的贴图。 */
    public static ResourceLocation altTexture() {
        ensureLoaded();
        if (quality == 1) {
            return TEXTURE_ALT_HALF;
        }
        if (quality == MAX_QUALITY) {
            return TEXTURE_ALT_QUARTER;
        }
        return TEXTURE_ALT;
    }

    /** 动作 3（连跳版）当前画质对应的贴图。 */
    public static ResourceLocation joinTexture() {
        ensureLoaded();
        if (quality == 1) {
            return TEXTURE_JOIN_HALF;
        }
        if (quality == MAX_QUALITY) {
            return TEXTURE_JOIN_QUARTER;
        }
        return TEXTURE_JOIN;
    }

    /** 动作 2 当前画质对应的贴图。 */
    public static ResourceLocation act3Texture() {
        ensureLoaded();
        if (quality == 1) {
            return TEXTURE_ACT3_HALF;
        }
        if (quality == MAX_QUALITY) {
            return TEXTURE_ACT3_QUARTER;
        }
        return TEXTURE_ACT3;
    }

    /** 当前画质对应的贴图。 */
    public static ResourceLocation texture() {
        ensureLoaded();
        if (quality == 1) {
            return TEXTURE_HALF;
        }
        if (quality == MAX_QUALITY) {
            return TEXTURE_QUARTER;
        }
        return TEXTURE_FULL;
    }


    /**
     * 取帧时离格子边界留多少（按**源图**像素算）。
     *
     * <p>低分辨率图上一个像素对应源图 2/4 个像素，所以内缩也要按比例放大，
     * 否则双线性采样会把隔壁那一帧渗进来，边缘出现一条细缝。
     */
    public static float uvInset() {
        ensureLoaded();
        return 0.5F * (1 << quality);
    }

    public static int frameTicks() {
        ensureLoaded();
        return frameTicks;
    }

    public static int frameStep() {
        ensureLoaded();
        return frameStep;
    }

    public static int quality() {
        ensureLoaded();
        return quality;
    }

    /** 设置「每次跨几帧」：越大越卡顿、越省 CPU 解码（不影响显存）。 */
    public static void setFrameStep(int value) {
        ensureLoaded();
        frameStep = clamp(value, 1, 32);
        save();
    }

    /** 相位补偿（刻）：第 0 帧相对谱子第 0 刻要晚多少刻播放。 */
    public static int frameOffset() {
        ensureLoaded();
        return frameOffset;
    }

    /** 调相位：只影响观感，不影响显存；改完立刻生效。 */
    public static void setFrameOffset(int value) {
        ensureLoaded();
        frameOffset = clamp(value, 0, 40);
        save();
    }

    /** 动作 2 的舞曲是不是静音。 */
    public static boolean danceMuted() {
        ensureLoaded();
        return danceMuted;
    }

    /** 静音开关：跟着木棍右键走，存盘（config/hatmod-client.json）。 */
    public static void setDanceMuted(boolean value) {
        ensureLoaded();
        danceMuted = value;
        save();
    }

    /** 设置画质档位：0 原图 / 1 一半 / 2 四分之一。 */
    public static void setQuality(int value) {
        ensureLoaded();
        quality = clamp(value, 0, MAX_QUALITY);
        save();
    }

    /** 每帧占几刻：想改播放速度直接写配置文件。 */
    public static void setFrameTicks(int value) {
        ensureLoaded();
        frameTicks = clamp(value, 1, 20);
        save();
    }

    /** 三档画质对应的贴图尺寸（宽, 高），只用于界面显示。 */
    private static final int[][] SHEET_SIZE = {{3840, 4608}, {1920, 2304}, {960, 1152}};

    /**
     * 调参器上那一行说明：**具体多少帧/秒、具体多大分辨率**，别让人去猜 0~2 是什么。
     */
    public static String describe() {
        ensureLoaded();
        int[] size = SHEET_SIZE[clamp(quality, 0, MAX_QUALITY)];
        return String.format("%.1f 帧/秒 · 画质 %d：%d×%d · 共 %d 帧",
                framesPerSecond(), quality, size[0], size[1],
                FRAME_COUNT + ALT_FRAME_COUNT + ACT3_FRAME_COUNT + JOIN_FRAME_COUNT);
    }

    /** 实际帧率（每秒换多少个姿势），给界面显示用。 */
    public static float framesPerSecond() {
        int denom = Math.max(1, frameTicks()) * Math.max(1, frameStep());
        float ticks = (INTRO_VIDEO_FRAMES + LOOP_VIDEO_FRAMES) / FRAMES_PER_TICK;
        return 20.0F * FRAME_COUNT * DEFAULT_FRAME_TICKS / (ticks * denom);
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    private static Path configPath() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.gameDirectory == null) {
            return null;
        }
        return minecraft.gameDirectory.toPath().resolve("config").resolve(FILE_NAME);
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (TetoFrameSchedule.poseCount() != FRAME_COUNT) {
            HatMod.LOGGER.warn("Teto 序列图 {} 张，但查表是 {} 个姿势 —— 图和表不是同一版生成的",
                    FRAME_COUNT, TetoFrameSchedule.poseCount());
        }
        Path path = configPath();
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            JsonObject object = GSON.fromJson(json, JsonObject.class);
            if (object == null) {
                return;
            }
            if (object.has("tetoFrameTicks")) {
                frameTicks = clamp(object.get("tetoFrameTicks").getAsInt(), 1, 20);
            }
            if (object.has("tetoFrameStep")) {
                frameStep = clamp(object.get("tetoFrameStep").getAsInt(), 1, 32);
            }
            if (object.has("tetoQuality")) {
                quality = clamp(object.get("tetoQuality").getAsInt(), 0, MAX_QUALITY);
            }
            if (object.has("tetoFrameOffset")) {
                frameOffset = clamp(object.get("tetoFrameOffset").getAsInt(), 0, 40);
            }
            if (object.has("tetoDanceMuted")) {
                danceMuted = object.get("tetoDanceMuted").getAsBoolean();
            }
        } catch (Exception exception) {
            HatMod.LOGGER.warn("[HatMod] 读取 config/{} 失败，Teto 渲染参数改用默认值：{}",
                    FILE_NAME, exception.toString());
        }
    }

    private static void save() {
        Path path = configPath();
        if (path == null) {
            return;
        }
        try {
            JsonObject object = new JsonObject();
            object.addProperty("tetoFrameTicks", frameTicks);
            object.addProperty("tetoFrameStep", frameStep);
            object.addProperty("tetoQuality", quality);
            object.addProperty("tetoFrameOffset", frameOffset);
            object.addProperty("tetoDanceMuted", danceMuted);
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(object), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            HatMod.LOGGER.warn("[HatMod] 写入 config/{} 失败：{}", FILE_NAME, exception.toString());
        }
    }
}
