package com.hatmod.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.hatmod.HatMod;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Teto 的渲染参数（**纯客户端**，不进服务端那份 {@code config/hatmod.json}）。
 *
 * <p>显存的大头不是「播放多快」，而是那张 992 帧序列图本身：
 * 原图 4042×4056，解压后约 **65 MB**（RGBA），帧率再怎么降也不会少一个字节。
 * 所以这里拆成两个互不影响的旋钮：
 * <ul>
 *   <li><b>帧率</b>：{@link #frameTicks()}（每帧占几刻）+ {@link #frameStep()}（每次跨几帧），
 *       实际帧率 = 20 / (frameTicks × frameStep)。只影响观感，不影响显存。</li>
 *   <li><b>画质</b>：{@link #quality()} 选哪张贴图 —— 0 原尺寸 / 1 一半 / 2 四分之一，
 *       显存分别约 65 MB / 16 MB / 4 MB（含 mipmap 会再高一点）。</li>
 * </ul>
 * 于是「低帧率 + 高画质」（帧步进拉大、画质 0）和「高帧率 + 省显存」都能单独试。
 *
 * <p>文件：{@code config/hatmod-client.json}。坏文件不会崩游戏，直接落回默认值。
 */
public final class TetoRenderSettings {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "hatmod-client.json";

    /** 序列图一共多少帧（和 TetoRenderer 的排布一致）。 */
    public static final int FRAME_COUNT = 992;

    public static final int DEFAULT_FRAME_TICKS = 2;
    public static final int DEFAULT_FRAME_STEP = 1;
    public static final int DEFAULT_QUALITY = 0;
    public static final int MAX_QUALITY = 2;

    private static final ResourceLocation TEXTURE_FULL = HatMod.id("textures/entity/teto.png");
    private static final ResourceLocation TEXTURE_HALF = HatMod.id("textures/entity/teto_half.png");
    private static final ResourceLocation TEXTURE_QUARTER = HatMod.id("textures/entity/teto_quarter.png");

    private static int frameTicks = DEFAULT_FRAME_TICKS;
    private static int frameStep = DEFAULT_FRAME_STEP;
    private static int quality = DEFAULT_QUALITY;
    private static boolean loaded;

    private TetoRenderSettings() {
    }

    /** 第 {@code tickCount} 刻该显示第几帧。 */
    public static int frameIndex(int tickCount) {
        ensureLoaded();
        int step = Math.max(1, frameTicks) * Math.max(1, frameStep);
        return (tickCount / step) % FRAME_COUNT;
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
    private static final int[][] SHEET_SIZE = {{4042, 4056}, {2021, 2028}, {1010, 1014}};

    /**
     * 调参器上那一行说明：**具体多少帧/秒、具体多大分辨率**，别让人去猜 0~2 是什么。
     */
    public static String describe() {
        ensureLoaded();
        int[] size = SHEET_SIZE[clamp(quality, 0, MAX_QUALITY)];
        return String.format("%.1f 帧/秒 · 画质 %d：%d×%d · 共 %d 帧",
                framesPerSecond(), quality, size[0], size[1], FRAME_COUNT);
    }

    /** 实际帧率（每秒多少帧），给界面显示用。 */
    public static float framesPerSecond() {
        return 20.0F / (Math.max(1, frameTicks()) * Math.max(1, frameStep()));
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
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(object), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            HatMod.LOGGER.warn("[HatMod] 写入 config/{} 失败：{}", FILE_NAME, exception.toString());
        }
    }
}
