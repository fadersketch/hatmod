package com.hatmod;

import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.forgespi.language.IModInfo;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 开局扫一遍所有模组的 jar，找「用 {@code Unsafe} 改对象头」的痕迹。
 *
 * <p>为什么要有这个：破防模式能拦住 {@code hurt} / {@code setHealth} / {@code remove}
 * 这些**走 API** 的杀法，可最狠的那一档是直接在 JVM 里改对象的类指针
 * （{@code Unsafe.putIntVolatile(o, 8L, klassPtr)}），让一个实体在运行时变成另一个类 ——
 * 之后所有 {@code instanceof}、事件取消、mixin 挂钩全部失效，我们既拦不住也修不回来。
 *
 * <p>唯一能做的是**发现它**：字节码里同时出现「拿 Unsafe」和「按偏移量写」的模组，
 * 基本就是在干这件事。扫到就往日志里甩一条 ERROR，至少能定位到是哪个模组。
 *
 * <p><b>只在破防模式下跑</b>（{@link HatSettings#forceBreak()}）：这套扫描是为了查
 * 「谁在 JVM 层动手」的，正常模式没有这层防御，扫了也没有意义。
 *
 * <p>几点克制：
 * <ul>
 *   <li>**只读、不改任何东西**，也不拒绝加载；</li>
 *   <li>开一个守护线程跑，8 秒 / 96 MB 预算，超了就带着已扫到的结果收工 —— 不拖慢开服；</li>
 *   <li>{@code META-INF/jarjar} 里嵌的库不扫（那是别人的依赖，扫了也只是噪声）；</li>
 *   <li>只出现 Unsafe 但没出现偏移量写的，只记一条 WARN（序列化 / 反射库也常这么干）；</li>
 *   <li>一次进程只扫一遍（换存档、重开服不重复扫）。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = HatMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ModTamperScan {

    /** 出现这些常量 = 在摸 Unsafe（{@code theUnsafe} 是反射拿实例的那个字段名）。 */
    private static final String[] UNSAFE_REFS = {
            "sun/misc/Unsafe",
            "jdk/internal/misc/Unsafe",
            "theUnsafe",
            "allocateInstance",
            "objectFieldOffset",
    };

    /** 出现这些常量 = 在按偏移量读写内存（改对象头 / 改字段都走它们）。 */
    private static final String[] OFFSET_WRITES = {
            "putIntVolatile",
            "putLongVolatile",
            "putObjectVolatile",
            "putAddress",
            "compareAndSwapInt",
    };

    /** 整次扫描最多跑多久（毫秒）。 */
    private static final long TIME_BUDGET_MS = 8000L;
    /** 整次扫描最多读多少字节（防止几百个模组把硬盘读满）。 */
    private static final long BYTE_BUDGET = 96L * 1024L * 1024L;
    /** 单个 class 超过这个大小就不看（正常类远小于它）。 */
    private static final int MAX_ENTRY_BYTES = 4 * 1024 * 1024;
    /** 一次进程只扫一遍。 */
    private static boolean scanned;

    private ModTamperScan() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        requestScanIfEnabled();
    }

    /**
     * 破防模式开着就扫一遍（只扫一次）。
     *
     * <p>保护类措施只在破防模式下生效，扫描也算：正常模式扫出来也没人管，白占启动时间。
     * 开局时如果破防是关的，就等到玩家真正打开它的那一刻再扫 —— 由
     * {@code HatAbilities} 的每刻心跳调这里，平时只是一次布尔判断。
     */
    static void requestScanIfEnabled() {
        if (scanned || !HatSettings.tamperScan() || !HatSettings.forceBreak()) {
            return;
        }
        scanned = true;
        Thread thread = new Thread(ModTamperScan::scan, "hatmod-mod-scan");
        thread.setDaemon(true);
        thread.start();
    }

    private static void scan() {
        long started = System.currentTimeMillis();
        long deadline = started + TIME_BUDGET_MS;
        long budget = BYTE_BUDGET;
        List<String> strong = new ArrayList<>();
        List<String> weak = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        int mods = 0;
        try {
            if (ModList.get() == null) {
                return;
            }
            for (IModInfo info : ModList.get().getMods()) {
                if (System.currentTimeMillis() > deadline || budget <= 0L) {
                    break;
                }
                String modId = info.getModId();
                if (modId == null || modId.equals(HatMod.MOD_ID)) {
                    continue;
                }
                Path path = jarPath(info);
                if (path == null || !Files.isRegularFile(path)) {
                    continue;               // 开发环境里是目录 / 内置模组没有文件
                }
                mods++;
                int[] used = new int[1];
                boolean[] hit = new boolean[2];
                List<String> classes = new ArrayList<>();
                try (ZipFile zip = new ZipFile(path.toFile())) {
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        if (budget <= 0L || System.currentTimeMillis() > deadline) {
                            break;
                        }
                        ZipEntry entry = entries.nextElement();
                        if (entry.isDirectory()) {
                            continue;
                        }
                        String name = entry.getName();
                        if (!name.endsWith(".class") || name.startsWith("META-INF/jarjar/")) {
                            continue;
                        }
                        long size = entry.getSize();
                        if (size > MAX_ENTRY_BYTES) {
                            continue;
                        }
                        byte[] bytes;
                        try (InputStream in = zip.getInputStream(entry)) {
                            bytes = readCapped(in, MAX_ENTRY_BYTES);
                        } catch (Throwable ignored) {
                            continue;
                        }
                        if (bytes == null || bytes.length == 0) {
                            continue;
                        }
                        budget -= bytes.length;
                        used[0]++;
                        String text = new String(bytes, StandardCharsets.ISO_8859_1);
                        boolean refs = containsAny(text, UNSAFE_REFS);
                        boolean writes = containsAny(text, OFFSET_WRITES);
                        if (refs) {
                            hit[0] = true;
                        }
                        if (writes) {
                            hit[1] = true;
                        }
                        if (refs && writes && classes.size() < 8) {
                            classes.add(name);
                        }
                    }
                } catch (Throwable error) {
                    unreadable.add(modId);
                    continue;
                }
                if (hit[0] && hit[1]) {
                    strong.add(modId + " -> " + String.join(", ", classes));
                } else if (hit[0] || hit[1]) {
                    weak.add(modId);
                }
            }
        } catch (Throwable error) {
            HatMod.LOGGER.debug("[hatmod] 模组扫描中断：{}", error.toString());
        }
        long elapsed = System.currentTimeMillis() - started;
        HatMod.LOGGER.info("[hatmod] 模组扫描完成：{} 个 jar，{} ms，可疑 {} 个",
                mods, elapsed, strong.size());
        if (!strong.isEmpty()) {
            HatMod.LOGGER.error("[hatmod] 这些模组的字节码里既拿 Unsafe 又按偏移量写 —— "
                    + "和改对象头是同一套手法。本模组的破防保护挡不住这一类篡改，"
                    + "出问题先从这里查：");
            for (String line : strong) {
                HatMod.LOGGER.error("[hatmod]   可疑：{}", line);
            }
        }
        if (!weak.isEmpty()) {
            HatMod.LOGGER.warn("[hatmod] 这些模组引用了 Unsafe 但没看到偏移量写"
                    + "（序列化 / 反射库常这样，仅供参考）：{}", weak);
        }
        if (!unreadable.isEmpty()) {
            HatMod.LOGGER.debug("[hatmod] 这些模组的 jar 读不了，跳过：{}", unreadable);
        }
    }

    /** 拿到模组主文件（jar）的路径；拿不到就 null。 */
    private static Path jarPath(IModInfo info) {
        try {
            if (info.getOwningFile() == null || info.getOwningFile().getFile() == null) {
                return null;
            }
            return info.getOwningFile().getFile().getFilePath();
        } catch (Throwable error) {
            return null;
        }
    }

    private static boolean containsAny(String text, String[] needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** 读进来但不超过 {@code cap} 字节（zip 里偶尔有 size 未知的条目）。 */
    private static byte[] readCapped(InputStream in, int cap) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) > 0) {
            int keep = Math.min(read, cap - total);
            if (keep <= 0) {
                break;
            }
            out.write(buffer, 0, keep);
            total += keep;
        }
        return out.toByteArray();
    }
}
