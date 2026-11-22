package com.hatmod.client;

/**
 * 「声波真的放出来了」的客户端标记：黑白闪 + 锁视线都挂在这上面。
 *
 * <p>声波现在要玩家踩中那一拍才放（见 {@code BrainrotCycle.onNoteHit}），
 * 没放出来就**一样效果都不给** —— 以前这两个效果是按固定刻数自己弹的，
 * 打空了照样黑白、照样锁镜头，看起来像「声波放了但没伤害」。
 *
 * <p>服务端在放出声波的那一刻发一条 {@code SonicFlashMessage}，这里用
 * {@link System#currentTimeMillis()} 记到期时间，不依赖某个时间轴变量是否就绪。
 */
public final class SonicFlash {

    /** 50 毫秒 = 1 刻。 */
    private static final long MS_PER_TICK = 50L;

    private static long monoUntil;
    private static long lockUntil;

    private SonicFlash() {
    }

    /** 服务端发来的「放出来了」：黑白闪 / 锁镜头各持续多少刻。 */
    public static void start(int monoTicks, int lockTicks) {
        long now = System.currentTimeMillis();
        monoUntil = now + Math.max(0, monoTicks) * MS_PER_TICK;
        lockUntil = now + Math.max(0, lockTicks) * MS_PER_TICK;
    }

    /** 黑白画面还该不该在。 */
    static boolean mono() {
        return System.currentTimeMillis() < monoUntil;
    }

    /** 镜头该不该还锁在声波方向上。 */
    static boolean locking() {
        return System.currentTimeMillis() < lockUntil;
    }

    /** 曲终 / 摘帽：立刻收干净。 */
    public static void clear() {
        monoUntil = 0L;
        lockUntil = 0L;
    }
}
