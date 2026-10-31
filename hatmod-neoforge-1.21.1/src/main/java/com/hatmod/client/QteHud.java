package com.hatmod.client;

import com.hatmod.BrainrotBeats;
import com.hatmod.BrainrotLyrics;
import com.hatmod.HatMod;
import com.hatmod.HatNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.lwjgl.glfw.GLFW;

/**
 * 「脑蚀」打拍的 HUD：屏幕下方一条**判定轨道** —— 拍点从右边滑进判定线，
 * 踩到线上时按 {@link #BEAT_TAP}（默认 V）或**空手右键**就算一拍。
 *
 * <p>判定（PERFECT / GOOD / MISS）和连击数值都在服务端，这里只按回包画：
 * 金字 = PERFECT、绿字 = GOOD、红字 = MISS，连击写在判定字上面，
 * 顺便把当前的光柱伤害加成换算给玩家看（每连击 +10%，封顶 +300%）。
 *
 * <p>计分段（真实鼓点区间）前 3 秒开始显示 —— 前奏不判定，但要让玩家
 * 看着拍点滑进来热手；计分段结束后再挂 1 秒收尾，然后整条消失。
 */
@EventBusSubscriber(modid = HatMod.MOD_ID, value = Dist.CLIENT)
public final class QteHud {

    /** 打拍键（默认 V，可在按键设置里改）。注册在 {@link HatModClient}。 */
    public static final KeyMapping BEAT_TAP = new KeyMapping(
            "key.hatmod.beat_tap", GLFW.GLFW_KEY_V, "key.categories.hatmod");

    /**
     * 强制开始链路（默认 P，可在按键设置里改）。
     *
     * <p>正常流程要「附近有敌人」才会自己开一轮；这个键不管有没有敌人都强行起手：
     * 戴脑蚀帽 → 开脑蚀那一轮（音乐 + Teto + 时间线），戴牛仔帽 → 直接进蓄力。
     */
    public static final KeyMapping FORCE_START = new KeyMapping(
            "key.hatmod.force_start", GLFW.GLFW_KEY_P, "key.categories.hatmod");

    /** 轨道半宽（像素）。 */
    private static final int TRACK_HALF = 80;
    /** 每刻滑多少像素：拍距约 8.86 刻，2.5 像素/刻 ≈ 一拍 22 像素。 */
    private static final float PIXELS_PER_TICK = 2.5F;
    /** 判定条变色 / 闪光用多久（刻）。 */
    private static final int RATING_TICKS = 10;
    /** 右侧判定字的完整寿命（刻）：满亮弹 6 刻（0.3s），再淡出 4 刻 —— 对应原版 0.25s + 0.2s。 */
    private static final int RATING_POP_TICKS = 10;
    /** 闪避提示显示多久（刻）。 */
    private static final int DODGE_TICKS = 14;
    /**
     * 计分段前多少刻开始显示（热手区）。
     *
     * <p>必须比倒计时更早：READY 卡在计分段前第 7 拍（约 62 刻），窗口要是只开 60 刻，
     * 第一个词就画在窗口外面 —— 表现就是「READY 根本不出来」。这里留到 110 刻。
     */
    private static final int LEAD_TICKS = 110;

    // 判定种类：和服务端 BrainrotQte 的常量一一对应（那边是包内私有）
    private static final int RATING_PERFECT = 1;
    private static final int RATING_GOOD = 2;
    private static final int RATING_MISS = 3;

    /** 客户端用来识别「幽灵召唤物」的位置记忆：UUID -> {x, y, z, 原地不动的刻数}。 */
    private static final java.util.Map<java.util.UUID, double[]> STUCK_POS = new java.util.HashMap<>();

    private QteHud() {
    }

    /** 打拍键每刻轮询：按一下发一次打拍包（连点键会攒在 consumeClick 里，逐个结）。 */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        sweepFrozenMinions();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        // 强制开链路：和打拍无关 —— 曲子还没放、甚至这一轮压根没开始时也要能用
        while (FORCE_START.consumeClick()) {
            HatNetwork.sendForceStart();
        }
        if (!BrainrotQteClient.active()) {
            // 没在打拍时也把攒下的点击清掉，免得下一轮一开就补发一堆
            while (BEAT_TAP.consumeClick()) {
                // 空循环：只为清队列
            }
            return;
        }
        while (BEAT_TAP.consumeClick()) {
            BrainrotQteClient.tap();
        }
    }

    /** 空手右键也算一拍（键位之外的备用输入）。 */
    @SubscribeEvent
    public static void onRightClickEmpty(PlayerInteractEvent.RightClickEmpty event) {
        BrainrotQteClient.tap();
    }

    /** 空手右键对着方块时走的是 RightClickBlock（不是 Empty），一样算一拍。 */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getEntity().getMainHandItem().isEmpty()) {
            BrainrotQteClient.tap();
        }
    }

    /**
     * 原始鼠标按下：右键自己的 4 刻冷却（Minecraft 里那个私有计数器）会把连按吞掉，
     * 所以打拍直接听**鼠标原始输入**，按得再密也接得住。
     */
    @SubscribeEvent
    public static void onMouseButton(net.neoforged.neoforge.client.event.InputEvent.MouseButton.Pre event) {
        if (event.getButton() == org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT
                && event.getAction() == org.lwjgl.glfw.GLFW.GLFW_PRESS) {
            BrainrotQteClient.tap();
        }
    }

    /**
     * 客户端兜底：卡在死亡动画最后一帧、永远不消失的史莱姆 / 蠹虫，本地直接抹掉。
     *
     * <p>这类「原地挂着的尸体」是服务端已经删了、客户端却漏了移除包的残留。
     */
    private static void sweepFrozenMinions() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.level instanceof net.minecraft.client.multiplayer.ClientLevel level)) {
            return;
        }
        // 先收集、再删：entitiesForRendering() 是底层 int->obj 哈希表，
        // 边遍历边 discard 会直接把它改坏（ArrayIndexOutOfBoundsException，实测会崩客户端）。
        java.util.List<net.minecraft.world.entity.Entity> doomed = new java.util.ArrayList<>();
        for (net.minecraft.world.entity.Entity entity : level.entitiesForRendering()) {
            boolean minion = entity instanceof net.minecraft.world.entity.monster.Slime
                    || entity instanceof net.minecraft.world.entity.monster.Silverfish;
            if (!minion) {
                continue;
            }
            // 有重力就恢复（活着但被加了悬浮的，靠这一下自己掉下来）
            if (entity.isNoGravity()) {
                entity.setNoGravity(false);
            }
            if (entity instanceof net.minecraft.world.entity.LivingEntity living) {
                // ① 卡在死亡动画最后一帧的
                if (living.isDeadOrDying() && living.deathTime > 45) {
                    doomed.add(entity);
                    continue;
                }
                // ② 幽灵：悬在空中、位置一动不动超过 3 秒（服务端早就不存在这只了）
                java.util.UUID id = entity.getUUID();
                double[] last = STUCK_POS.get(id);
                if (last != null
                        && Math.abs(last[0] - entity.getX()) < 0.05D
                        && Math.abs(last[1] - entity.getY()) < 0.05D
                        && Math.abs(last[2] - entity.getZ()) < 0.05D
                        && !entity.onGround()) {
                    last[3] += 1.0D;
                } else {
                    last = new double[]{entity.getX(), entity.getY(), entity.getZ(), 0.0D};
                    STUCK_POS.put(id, last);
                }
                if (last[3] > 60.0D) {
                    STUCK_POS.remove(id);
                    doomed.add(entity);
                }
            }
        }
        for (net.minecraft.world.entity.Entity entity : doomed) {
            STUCK_POS.remove(entity.getUUID());
            entity.discard();
        }
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        render(event.getGuiGraphics());
    }

    // ------------------------------------------------------------------
    // READY / SET / GO：传统音游那套准备字幕 —— 不用固定秒数，直接**卡拍**：
    // 计分段第一拍往前数七拍起，一拍一个词，最后一拍和计分开始同起。
    // 弹出动画和「No too late」同一套（前 3 刻从 2.2 倍缩到 1 倍 + 淡入淡出）。
    // ------------------------------------------------------------------

    private static final String[] COUNTDOWN_WORDS = {"READY", "SET", "GO"};
    /** 青 → 金 → 绿，一个比一个醒目，GO 再放大一号。 */
    private static final int[] COUNTDOWN_COLORS = {0x55FFFF, 0xFFAA00, 0x55FF55};
    /** 一个词亮几刻（只在没接下一个词时兜底用）。 */
    private static final int COUNTDOWN_TICKS = 10;
    /** 倒计时三个词之间的间隔（刻）——和「NOPE / too / late」逐词同步，各隔 7 刻。 */
    private static final int COUNTDOWN_STEP = BrainrotBeats.SPEECH_STEP;

    /** 计分段第一拍在拍表里的下标。 */
    private static int firstScoreBeatIndex() {
        int[] out = new int[1];
        int first = BrainrotBeats.nearest(BrainrotBeats.SCORE_FROM, out);
        if (out[0] > 0) {
            // SCORE_FROM 落在第一拍之后（不是正好拍点）就退回前一拍
            first--;
        }
        return first;
    }

    /**
     * 倒计时第 {@code i} 个词在几刻：**和「No too late」逐词同刻** ——
     * READY 和「No」一起、SET 和「too」一起、GO 和「late」一起。
     */
    private static int countdownBeatTick(int i) {
        return BrainrotBeats.NO_TOO_LATE_FIRST + i * COUNTDOWN_STEP;
    }

    /**
     * 允许出现按键的最早时刻：GO 消失之后（计分第一拍往前数三拍）。
     *
     * <p>也就是说：READY / SET / GO 走完、GO 从屏幕上消失，键才开始从右边飞进来 ——
     * 准备段整段是干净的。
     */
    private static int keysFromTick() {
        // 从「NOPE」那一刻起就是实键：这三个词本身就是键，词到键也到
        return BrainrotBeats.NO_TOO_LATE_FIRST;
    }

    /** 画判定轨道（NeoForge 走 RenderGuiEvent，Forge 注册成 GUI 覆盖层后也调这里）。 */
    public static void render(GuiGraphics graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !BrainrotQteClient.active()) {
            return;
        }
        int age = BrainrotQteClient.age();
        // 判定条从**曲子一开始**就在（只是很虚），末段黑暗淡完之后才收
        if (age < 0 || age > BrainrotBeats.FINAL_DARK + 120) {
            return;
        }
        Font font = minecraft.font;
        int cx = graphics.guiWidth() / 2;
        int y = graphics.guiHeight() - 68;
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();

        // ---------- 闪避：整屏紫边 + 大字「闪避」 ----------
        // 闪避是这一轮最值得庆祝的一刻（挨打却落空、人还挪出去一段），单独一段反馈，
        // 不和 PERFECT / GOOD 的金边绿边混在一起。
        long dodgeSince = BrainrotQteClient.sinceDodge();
        if (dodgeSince >= 0L && dodgeSince <= DODGE_TICKS) {
            float left = 1.0F - dodgeSince / (float) DODGE_TICKS;
            flashEdges(graphics, (int) (110 * left), 0x8B5CF6);
            float pop = Math.min(1.0F, dodgeSince / 3.0F);
            float scale = 1.0F + (1.0F - pop) * 1.4F;
            int alpha = (int) (255 * Math.min(1.0F, left * 2.2F));
            if (alpha > 0) {
                Component text = Component.translatable("hatmod.qte.dodge")
                        .withStyle(ChatFormatting.BOLD);
                var pose = graphics.pose();
                pose.pushPose();
                pose.translate(cx, y - 94, 0.0F);
                pose.scale(scale, scale, 1.0F);
                graphics.drawString(font, text, -font.width(text) / 2, -font.lineHeight / 2,
                        (alpha << 24) | 0xC9B6FF, true);
                pose.popPose();
            }
        }

        // 最近一次判定打了什么、落在哪一拍
        int rating = BrainrotQteClient.rating();
        int since = age - BrainrotQteClient.ratingTick();
        int judgedBeatTick = -1;
        if (rating != 0 && since >= 0 && since <= 24) {
            int[] out = new int[1];
            judgedBeatTick = BrainrotBeats.tickAt(
                    BrainrotBeats.nearest(BrainrotQteClient.ratingTick(), out));
        }

        // 节拍的「呼吸」：离下一拍越近，整条轨道越亮（0 = 刚过去，1 = 正踩上）
        int[] near = new int[1];
        BrainrotBeats.nearest(age, near);
        float beatPulse = 1.0F - Math.min(1.0F, Math.abs(near[0]) / 8.86F);

        // ---------- 判定条：虚化显形 → 击打变色 + 上下微跳 ----------
        // ① 显形：从 HUD 出现那一刻起、到倒计时第一拍前，用几秒从几乎透明浮到全亮
        //    —— 这段时间里不放任何键，条只是先「长出来」；
        // ② 击打：整条按判定换色，并**向上微微一跳**（PERFECT 3px / GOOD 2px，正弦衰减）；
        // ③ MISS：整条红到底、**一点不动** —— 红是警告，抖是奖励，别混在一起。
        // 前 8 秒（160 刻）整条判定条不出现，8–12 秒（160–240 刻）慢慢显形
        float reveal = Math.max(0.0F, Math.min(1.0F, (age - 160) / 80.0F));
        // 歌词有自己的淡入（开场就要唱），判定条要等到 8 秒
        // 歌词的淡入要 5 秒：0 → 100 刻
        float lyricReveal = Math.max(0.0F, Math.min(1.0F, age / 100.0F));
        if (age < 160) {
            drawLyrics(graphics, font, cx, y, age, lyricReveal);
            return;
        }
        // 最后那段黑暗：不再放键，整条判定条用约 5 秒慢慢淡出去
        boolean keysOff = age >= BrainrotBeats.FINAL_DARK;
        if (keysOff) {
            reveal *= Math.max(0.0F, 1.0F - (age - BrainrotBeats.FINAL_DARK) / 100.0F);
        }

        // ---------- 歌词：判定条上方一行，卡拉 OK 式（唱过的亮、没唱的暗，下面是下一句预告） ----------
        drawLyrics(graphics, font, cx, y, age, lyricReveal);

        int barRgb = 0x9FA6C8;
        int barBounce = 0;
        if (rating != 0 && since >= 0 && since <= 8) {
            barRgb = rating == RATING_PERFECT ? 0xFFD700
                    : rating == RATING_GOOD ? 0x55FF55 : 0xFF5555;
            if (rating != RATING_MISS) {
                // 上半圈的正弦：起手最高、几刻内回位，像踩了一脚
                float decay = 1.0F - since / 8.0F;
                float bounce = Math.abs((float) Math.sin(since * 1.9F)) * decay;
                barBounce = -Math.round(bounce * (rating == RATING_PERFECT ? 3.0F : 2.0F));
            }
        }
        int barY = y + barBounce;

        // 外投影 → 内槽 → 亮芯 → 两端圆头，四层叠出一条有厚度感的判定条
        graphics.fill(cx - TRACK_HALF - 3, barY - 3, cx + TRACK_HALF + 3, barY + 5,
                fade(0x66, reveal));
        graphics.fill(cx - TRACK_HALF - 1, barY - 1, cx + TRACK_HALF + 1, barY + 3,
                fade(0xAA, reveal) | 0x101018);
        graphics.fill(cx - TRACK_HALF, barY, cx + TRACK_HALF, barY + 1,
                fade(0xFF, reveal) | barRgb);
        graphics.fill(cx - TRACK_HALF, barY - 1, cx + TRACK_HALF, barY,
                fade(0x88, reveal) | (barRgb & 0x808080));
        // 圆头
        graphics.fill(cx - TRACK_HALF - 3, barY - 2, cx - TRACK_HALF - 1, barY + 3,
                fade(0xAA, reveal) | 0x101018);
        graphics.fill(cx + TRACK_HALF + 1, barY - 2, cx + TRACK_HALF + 3, barY + 3,
                fade(0xAA, reveal) | 0x101018);
        // 节拍脉冲：踩点那一刻整条提亮
        int pulseAlpha = Math.round(46 * beatPulse * reveal);
        if (pulseAlpha > 0) {
            graphics.fill(cx - TRACK_HALF, barY - 1, cx + TRACK_HALF, barY + 2,
                    (pulseAlpha << 24) | 0xFFFFFF);
        }
        // 刚判过的几刻再盖一层判定色（MISS 时整条红到底）
        if (rating != 0 && since >= 0 && since <= 8) {
            int tint = Math.round(120 * (1.0F - since / 8.0F) * reveal);
            if (tint > 0) {
                graphics.fill(cx - TRACK_HALF, barY - 2, cx + TRACK_HALF, barY + 3,
                        (tint << 24) | barRgb);
            }
        }

        // ---------- 拍点：实体键 + 示范键 ----------
        // GO（「late」）之前的拍点画成**极虚的示范键**：看得清节奏、看得出原来有哪些节点，
        // 但按了不算（判定从第一个实体键才开始）；之后才是实体键，末段黑暗整段不放。
        int revealAlpha = Math.round(255 * reveal);
        int keysFrom = keysFromTick();
        for (int i = 0; i < BrainrotBeats.count(); i++) {
            if (keysOff) {
                break;
            }
            int tick = BrainrotBeats.tickAt(i);
            int delta = tick - age;
            if (delta < -8 || delta > 32) {
                continue;
            }
            int x = cx + Math.round(delta * PIXELS_PER_TICK);
            if (tick < keysFrom) {
                // 示范键：**全透明**的影子键 —— 碰到判定线就消失，不穿过去（它只是示范，不算一拍）
                if (delta < 0) {
                    continue;
                }
                int ghostAlpha = Math.round(0x20 * reveal);
                if (ghostAlpha > 0) {
                    graphics.fill(x, barY - 4, x + 1, barY + 5, (ghostAlpha << 24) | 0xFFFFFF);
                }
                continue;
            }
            boolean judged = tick == judgedBeatTick && delta < 0;
            int rgb;
            int bodyAlpha;
            if (delta >= 0) {
                // 实体键：红色；快进判定窗时更亮更红
                boolean hot = Math.abs(delta) <= 4;
                rgb = hot ? 0xFF2020 : 0xD22C2C;
                bodyAlpha = revealAlpha;
            } else {
                bodyAlpha = (int) (0xDD * (1.0F + delta / 8.0F) * reveal);
                if (bodyAlpha <= 0) {
                    continue;
                }
                rgb = judged
                        ? (rating == RATING_PERFECT ? 0xFFD700
                        : rating == RATING_GOOD ? 0x55FF55 : 0xFF5555)
                        : 0xB03030;
            }
            // 「NOPE / too / late」那三个键画成纯白：它和词是同一件事（判定后的反馈色不变）
            boolean speechKey = !judged && BrainrotBeats.isSpeechKey(tick);
            if (speechKey) {
                rgb = 0xFFFFFF;
            }
            int top = judged ? barY - 11 : (delta >= 0 && Math.abs(delta) <= 4 ? barY - 8 : barY - 6);
            // 外发光（白键就是白晕，在深色条上很跳）
            int glowAlpha = Math.max(0, bodyAlpha / 3);
            graphics.fill(x - 2, top - 1, x + 4, barY + 8, (glowAlpha << 24) | rgb);
            // 本体
            graphics.fill(x, top, x + 2, barY + 7, (bodyAlpha << 24) | rgb);
            // 顶端亮点（还没到的那拍才画，像音游里的音符头）
            if (delta >= 0) {
                graphics.fill(x - 1, top - 2, x + 3, top, (bodyAlpha << 24) | rgb);
            }
        }

        // ---------- 示范长音（11 秒末尾 → No too late）----------
        // 从前奏末尾一路拖到第一个实体键上：正好把「示范」接到「实体」的那一下。
        if (!keysOff) {
            int startDelta = BrainrotBeats.LEAD_HOLD_START - age;
            int endDelta = BrainrotBeats.SCORE_FROM - age;
            if (startDelta <= 40 && endDelta >= -8) {
                int sx = cx + Math.round(startDelta * PIXELS_PER_TICK);
                int ex = cx + Math.round(endDelta * PIXELS_PER_TICK);
                int left = Math.min(sx, ex);
                int right = Math.max(sx, ex);
                // 和示范键同一套色：透明白，别一个紫一个白
                graphics.fill(left, barY - 3, right, barY + 4, fade(0x1E, reveal) | 0xFFFFFF);
                graphics.fill(left, barY - 1, right, barY + 1, fade(0x40, reveal) | 0xFFFFFF);
                graphics.fill(sx - 1, barY - 7, sx + 2, barY + 8, fade(0x66, reveal) | 0xFFFFFF);
            }
        }

        // ---------- 长音（1:08–1:10）：一根从「头」拖到「尾」的长条 ----------
        // 头拍踩上之后一直算按住：服务端在这段里每 8 刻涨一层连击、并让光柱保持涌动态。
        if (!keysOff) {
            for (int h = 0; h < BrainrotBeats.HOLD_COUNT; h++) {
                int headTick = BrainrotBeats.holdHeadTick(h);
                int holdEnd = BrainrotBeats.holdEnd(h);
                int headDelta = headTick - age;
                int tailDelta = holdEnd - age;
                if (headDelta > 40 || tailDelta < -8) {
                    continue;
                }
                int hx = cx + Math.round(headDelta * PIXELS_PER_TICK);
                int tx = cx + Math.round(tailDelta * PIXELS_PER_TICK);
                int left = Math.min(hx, tx);
                int right = Math.max(hx, tx);
                boolean holding = age >= headTick && age < holdEnd
                        && BrainrotQteClient.combo() > 0;
                int rgb = holding ? 0xFF2020 : 0xB02A2A;
                graphics.fill(left, barY - 4, right, barY + 5, fade(0x44, reveal) | rgb);
                graphics.fill(left, barY - 1, right, barY + 2, fade(0xCC, reveal) | rgb);
                // 头部亮点（就踩在它上面起手）
                graphics.fill(hx - 1, barY - 9, hx + 3, barY + 10, fade(0xFF, reveal) | 0xFFFFFF);
                if (holding) {
                    graphics.fill(hx - 1, barY - 9, hx + 3, barY + 10, fade(0xFF, reveal) | 0xFFD700);
                }
            }
        }

        // ---------- 节拍奖励面板：闪避 / 伤害加成 / 当前吃到的增益 ----------
        // 摆在判定条左上，把「打拍挣到了什么」直接写在屏幕上。
        if (reveal > 0.05F) {
            java.util.List<Component> lines = new java.util.ArrayList<>();
            lines.add(Component.translatable("hatmod.qte.reward.title"));
            lines.add(Component.translatable("hatmod.qte.reward.dodge",
                    BrainrotQteClient.dodges()));
            lines.add(Component.translatable("hatmod.qte.reward.bonus",
                    Math.min(999, BrainrotQteClient.combo() * 2)));
            int shown = 0;
            for (var effect : minecraft.player.getActiveEffects()) {
                if (shown >= 3) {
                    break;
                }
                if (!effect.getEffect().value().isBeneficial()) {
                    continue;
                }
                lines.add(Component.literal("· "
                        + effect.getEffect().value().getDisplayName().getString()
                        + " " + roman(effect.getAmplifier() + 1)
                        + "  " + (effect.getDuration() / 20) + "s"));
                shown++;
            }
            int panelX = Math.max(4, cx - TRACK_HALF - 132);
            int panelY = y - 100;
            int panelW = 0;
            for (Component line : lines) {
                panelW = Math.max(panelW, font.width(line));
            }
            panelW += 10;
            int panelH = lines.size() * 10 + 8;
            graphics.fill(panelX - 4, panelY - 4, panelX - 4 + panelW, panelY - 4 + panelH,
                    fade(0x45, reveal) | 0x0A0A12);
            graphics.fill(panelX - 4, panelY - 4, panelX - 4 + panelW, panelY - 3,
                    fade(0xAA, reveal) | 0x8B5CF6);
            int lineY = panelY;
            for (int i = 0; i < lines.size(); i++) {
                // 颜色里不要带 alpha：外面还要叠 reveal，自带 0xFF 会把淡入淡出盖掉
                int color = i == 0 ? 0xC9B6FF : 0xE8E8F0;
                graphics.drawString(font, lines.get(i), panelX + 1, lineY,
                        (Math.round(0xFF * reveal) << 24) | color, true);
                lineY += 10;
            }
        }

        // ---------- 判定线：菱形 + 外圈光晕 ----------
        int lineRgb = 0xFFFFFF;
        if (rating != 0 && since >= 0 && since <= 8) {
            lineRgb = rating == RATING_PERFECT ? 0xFFD700
                    : rating == RATING_GOOD ? 0x55FF55 : 0xFF5555;
        }
        // 由外到内三层：光晕 → 半透 → 实心菱形
        graphics.fill(cx - 5, barY - 2, cx + 5, barY + 3, fade(0x33, reveal) | 0x101018);
        graphics.fill(cx - 3, barY - 6, cx + 3, barY + 7, fade(0x44, reveal) | lineRgb);
        graphics.fill(cx - 2, barY - 9, cx + 2, barY + 10, fade(0xAA, reveal) | lineRgb);
        graphics.fill(cx - 1, barY - 10, cx + 1, barY + 11, fade(0xFF, reveal) | lineRgb);

        // ---------- 整屏反馈 ----------
        if (rating == RATING_PERFECT && since >= 0 && since <= 10) {
            flashEdges(graphics, (int) (80 * (1.0F - since / 10.0F)), 0xFFD700);
        }
        if (rating == RATING_GOOD && since >= 0 && since <= 8) {
            flashEdges(graphics, (int) (45 * (1.0F - since / 8.0F)), 0x55FF55);
        }
        if (rating == RATING_MISS && since >= 0 && since <= 10) {
            flashEdges(graphics, (int) (70 * (1.0F - since / 10.0F)), 0xFF3030);
        }

        // ---------- 判定字 ----------
        // 口白三键不写判定字：那三个词由下面那排大字自己亮
        // （长音里那串 PERFECT 是「按住」刷出来的，不是按了白键，别跟着亮）
        int speechHit = judgedBeatTick >= 0 && rating != RATING_MISS
                && BrainrotBeats.holdAt(age) < 0
                ? BrainrotBeats.speechIndex(judgedBeatTick) : -1;
        // ---------- 判定字（右起四分之一屏）----------
        // 手感照 FNF 原版 `PlayState.popUpScore()`：
        //   判定图出生时带一个向上的初速（140~175 px/s），同时挂 550 px/s² 的重力，
        //   0.25 秒后才开始用 0.2 秒淡出 —— 所以观感是「往上弹 → 在顶点停一下 → 淡掉」。
        // 换成刻（20 刻/秒）：v0 = -8 px/刻、a = 1.375 px/刻²，
        //   位移 rise = -8t + 0.6875t²（6 刻到顶 ≈ -23px，之后自己落回来）。
        // 每一条独立排队：连打时几个字先后弹上去、各淡各的，新判定不会顶掉旧的。
        for (int p = 0; p < BrainrotQteClient.popupCount(); p++) {
            int popupRating = BrainrotQteClient.popupRating(p);
            int popupSince = age - BrainrotQteClient.popupTick(p);
            if (popupRating == 0 || popupSince < 0 || popupSince > RATING_POP_TICKS) {
                continue;
            }
            // 基础字号：PERFECT / GOOD 要够大才像 FNF 的判定字；MISS 稍小一点
            float base = popupRating == RATING_MISS ? 1.30F : 1.60F;
            float t = popupSince;
            float rise = -8.0F * t + 0.6875F * t * t;   // 原版的抛物线
            float drift = -0.2F * t;                   // 原版同时往左飘 0~10 px/s
            float fade = Math.max(0.0F, Math.min(1.0F, (RATING_POP_TICKS - t) / 5.0F));
            int alpha = Math.round(255 * fade);
            if (alpha <= 0) {
                continue;
            }
            Component text = Component.literal(
                            popupRating == RATING_PERFECT ? "PERFECT"
                                    : popupRating == RATING_GOOD ? "GOOD" : "MISS")
                    .withStyle(ChatFormatting.BOLD);
            int color = popupRating == RATING_PERFECT ? 0xFFD700
                    : popupRating == RATING_GOOD ? 0x55FF55 : 0xFF5555;
            var pose = graphics.pose();
            pose.pushPose();
            // 右起四分之一屏（≈ 0.75 屏宽）：够靠右，但整串字不会顶出屏幕
            pose.translate(graphics.guiWidth() * 0.75F + drift, (float) (y - 30) + rise, 0.0F);
            pose.scale(base, base, 1.0F);
            graphics.drawString(font, text, -font.width(text) / 2, -font.lineHeight / 2,
                    (alpha << 24) | color, true);
            pose.popPose();
        }

        // ---------- Nope / too / late：一排大字 ----------
        drawSpeechRow(graphics, font, cx, y, age, speechHit);

        // ---------- 连击板：半透明底板 + 连击数 + 伤害加成 ----------
        // 连击掉到 0 之后不立刻消失，再留 10 刻淡出（panelFade）。
        int combo = BrainrotQteClient.combo();
        int panelCombo = combo > 0 ? combo : BrainrotQteClient.panelCombo();
        float panelFade = combo > 0 ? 1.0F : BrainrotQteClient.panelFade(age);
        float plateReveal = reveal * panelFade;
        if (panelCombo > 0 && plateReveal > 0.01F) {
            Component comboText = Component.translatable("hatmod.qte.combo", panelCombo)
                    .withStyle(ChatFormatting.BOLD, ChatFormatting.YELLOW);
            int bonus = panelCombo * 2;
            Component bonusText = Component.translatable("hatmod.qte.bonus", bonus)
                    .withStyle(ChatFormatting.GOLD);
            int plateW = Math.max(font.width(comboText), font.width(bonusText)) + 20;
            int plateAlpha = Math.round(0x3A * plateReveal);
            graphics.fill(cx - plateW / 2, y - 52, cx + plateW / 2, y - 22,
                    (plateAlpha << 24) | 0x000000);
            graphics.fill(cx - plateW / 2, y - 52, cx + plateW / 2, y - 51,
                    fade(0x55, plateReveal) | 0xFFD700);
            graphics.fill(cx - plateW / 2, y - 23, cx + plateW / 2, y - 22,
                    fade(0x55, plateReveal) | 0xFFD700);
            graphics.drawString(font, comboText, cx - font.width(comboText) / 2, y - 48,
                    fade(0xFF, plateReveal) | 0xFFE066, true);
            graphics.drawString(font, bonusText, cx - font.width(bonusText) / 2, y - 35,
                    fade(0xFF, plateReveal) | 0xFFD700, true);
        }

        // ---------- READY / SET / GO：和三个白键同刻，一个接一个，不叠 ----------
        // 间隔就是 SPEECH_STEP；每个词只活到下一个词出现为止 —— 屏幕上永远只有一个。
        int cdEnd = countdownBeatTick(COUNTDOWN_WORDS.length - 1) + 14;
        // 三个词横向排一排，各自占自己那一格
        int cdGap = 8;
        int cdTotal = -cdGap;
        for (String word : COUNTDOWN_WORDS) {
            cdTotal += font.width(word) + cdGap;
        }
        int cdX = cx - cdTotal / 2;
        for (int i = 0; i < COUNTDOWN_WORDS.length; i++) {
            int wordWidth = font.width(COUNTDOWN_WORDS[i]);
            int wordX = cdX;
            cdX += wordWidth + cdGap;
            int at = countdownBeatTick(i);
            // 下一个词出来的那一刻，这个词先撤掉：三个词永远只有一个在屏幕上
            int until = i + 1 < COUNTDOWN_WORDS.length ? countdownBeatTick(i + 1) : cdEnd;
            int beatAge = age - at;
            if (beatAge < 0 || age >= until) {
                continue;
            }
            float pop = Math.min(1.0F, beatAge / 3.0F);
            float scale = 1.0F + (1.0F - pop) * 1.2F;
            scale *= i == 2 ? 2.0F : 1.6F;
            float fadeOut = Math.max(0.0F, Math.min(1.0F, (until - (float) age) / 2.0F));
            int alpha = (int) (255.0F * Math.min(1.0F, pop + 0.25F) * fadeOut);
            if (alpha <= 0) {
                continue;
            }
            Component text = Component.literal(COUNTDOWN_WORDS[i])
                    .withStyle(ChatFormatting.BOLD);
            var pose = graphics.pose();
            pose.pushPose();
            // 摆在歌词行上面 42px，每个词占自己那一格
            pose.translate(wordX + wordWidth / 2.0F, (float) (lyricLineY(height) - 42), 0.0F);
            pose.scale(scale, scale, 1.0F);
            graphics.drawString(font, text, -wordWidth / 2, -font.lineHeight / 2,
                    (alpha << 24) | COUNTDOWN_COLORS[i], true);
            pose.popPose();
        }

        // ---------- 热手提示 ----------
        if (age < BrainrotBeats.SCORE_FROM) {
            Component hint = Component.translatable("hatmod.qte.hint",
                    BEAT_TAP.getTranslatedKeyMessage());
            graphics.drawString(font, hint, cx - font.width(hint) / 2, y + 14, 0xFFB9C2D6, true);
        }
    }

    /** 歌词那一行的 y（READY / SET / GO 在这条线上面 42px）。 */
    static int lyricLineY(int guiHeight) {
        return guiHeight - 68 - 126;
    }

    /**
     * 判定条上方那行歌词：正在唱的这句先整行铺暗色，再用剪刀从左往右「扫」出亮色 ——
     * 扫到哪儿就是唱到哪儿（逐字时间见 {@code BrainrotLyrics#CUE}），和判定条共用
     * {@link BrainrotQteClient#age()} 这个钟，听到的和看到的是同一刻。
     */
    /** 歌词行距（像素）与换行时上滚的时长（刻）。 */
    private static final int LYRIC_LINE_HEIGHT = 12;
    private static final int LYRIC_TRANSITION = 8;

    /**
     * 歌词：像音乐软件那样**整块往上滚**。
     *
     * <p>当前这句在中间（跟着拍点扫字），上一句一边往上飘一边淡出，下一句从下面浮上来；
     * 换行时三行一起上移一格，所以是连续滚动，而不是「一句话突然换掉」。
     *
     * <p>「NOPE too late」弹出来的那段时间整行让位 —— 那三个词就画在这个位置。
     */
    private static void drawLyrics(GuiGraphics graphics, Font font, int cx, int y,
                                   int age, float reveal) {
        int index = BrainrotLyrics.currentIndex(age);
        if (index < 0) {
            return;
        }
        int start = BrainrotLyrics.tickAt(index);
        int end = BrainrotLyrics.endTick(index);
        // 最后一句唱完之后用 5 秒慢慢淡出，其他句交给下一句顶上来
        boolean lastLine = index == BrainrotLyrics.count() - 1;
        int tail = lastLine ? 100 : 20;
        if (age > end + tail) {
            return;
        }
        if (lastLine) {
            reveal *= Math.max(0.0F, Math.min(1.0F, (end + 100 - (float) age) / 100.0F));
        }
        int lineY = lyricLineY(graphics.guiHeight());
        // 换行进度：新的一句开始时，三行一起往上滚一格
        float slide = Math.min(1.0F, Math.max(0.0F,
                (age - start) / (float) LYRIC_TRANSITION));

        // 上一句：往上飘 + 淡出
        if (index > 0) {
            String prev = BrainrotLyrics.textAt(index - 1);
            graphics.drawString(font, prev, cx - font.width(prev) / 2,
                    lineY - Math.round(LYRIC_LINE_HEIGHT * slide),
                    fade(0x80, reveal * (1.0F - slide)) | 0x8F8FA6, false);
        }
        // 下一句：从下面浮上来
        if (index + 1 < BrainrotLyrics.count()) {
            String next = BrainrotLyrics.textAt(index + 1);
            graphics.drawString(font, next, cx - font.width(next) / 2,
                    lineY + Math.round(LYRIC_LINE_HEIGHT * (2.0F - slide)),
                    fade(0x60, reveal) | 0x9AA3B8, false);
        }
        // 当前句：从下方一格浮到位，同时跟着人声逐字扫
        int rowY = lineY + Math.round(LYRIC_LINE_HEIGHT * (1.0F - slide));
        String text = BrainrotLyrics.textAt(index);
        int w = font.width(text);
        int left = cx - w / 2;
        float appear = reveal * slide;
        int plateAlpha = Math.round(0x24 * appear);
        if (plateAlpha > 0) {
            graphics.fill(left - 6, rowY - 2, left + w + 6, rowY + 10,
                    (plateAlpha << 24) | 0x0A0A12);
        }
        graphics.drawString(font, text, left, rowY, fade(0x70, appear) | 0x5A5A70, true);
        int cut = sweepCut(font, text, BrainrotLyrics.cue(index),
                BrainrotLyrics.tickAt(index), age);
        if (cut > 0) {
            graphics.enableScissor(left, rowY - 2, left + cut, rowY + 10);
            graphics.drawString(font, text, left, rowY, fade(0xFF, appear) | 0xFFF2C8, true);
            graphics.disableScissor();
        }
    }

    /**
     * 卡拉 OK 扫到第几个像素：**逐字按 QRC 记下的真人声时间走**。
     *
     * <p>以前拿鼓点当刻度，一拍亮一格；可是人会拖长音（「抜け出せない」的「い」拖两秒、
     * 「溺れて」的「溺」占 0.9 秒），按拍切必然比嗓子快或者慢。现在每个字都带着自己的
     * 起唱刻和时长（{@link BrainrotLyrics#cue(int)}），边界就是「这一刻唱到哪个字的
     * 百分之几」，长音就慢慢挪，短音一闪而过。
     *
     * @return 已经唱到第几个像素（相对整句左端）
     */
    private static int sweepCut(Font font, String text, int[] cue, int lineStart, int age) {
        int cut = 0;
        int count = Math.min(text.length(), cue.length);
        for (int i = 0; i < count; i++) {
            int start = lineStart + (cue[i] >>> 8) - BrainrotLyrics.CUE_BIAS;
            int span = Math.max(1, cue[i] & 0xFF);
            if (age < start) {
                continue;
            }
            int left = font.width(text.substring(0, i));
            int right = font.width(text.substring(0, i + 1));
            float done = Math.min(1.0F, (age - start) / (float) span);
            cut = Math.max(cut, left + Math.round((right - left) * done));
        }
        return cut;
    }

    /** 「Nope / too / late」这一排里，哪些词已经按中；以及当前是哪一句（换句就清空）。 */
    private static final boolean[] SPEECH_LIT = new boolean[3];
    private static int SPEECH_PHRASE = Integer.MIN_VALUE;
    /** 上一轮音乐：换轮了就把点亮记录清掉（两轮的 260 / 1398 刻是同一个数）。 */
    private static int SPEECH_EPOCH = Integer.MIN_VALUE;

    /**
     * 「Nope / too / late」—— **一排大字**，三个词从头就在，按中哪个哪个亮。
     *
     * <p>不是「弹一个删一个」：三个词始终占着三个位置，没按到的是暗影（告诉你这一排
     * 有三个词、各在哪儿），按中的变成白亮大字；三个全中就整排转金色并多一条亮线，
     * 这时候屏幕上是一整排「Nope too late」。
     *
     * @param hitSlot 这一帧刚按中的是第几个（-1 = 没有）
     */
    private static void drawSpeechRow(GuiGraphics graphics, Font font, int cx, int y,
                                      int age, int hitSlot) {
        int first = BrainrotBeats.NO_TOO_LATE_FIRST;
        int second = BrainrotBeats.NO_TOO_LATE_SECOND;
        int anchor = Math.abs(age - second) <= Math.abs(age - first) ? second : first;
        int last = anchor + BrainrotBeats.SPEECH_STEP * (BrainrotBeats.SPEECH_WORDS.length - 1);
        // 提前几刻把暗影放出来，给玩家反应时间；最后再多挂一会儿收尾
        if (age < anchor - 8 || age > last + 26) {
            return;
        }
        if (anchor != SPEECH_PHRASE || BrainrotQteClient.epoch() != SPEECH_EPOCH) {
            SPEECH_PHRASE = anchor;
            SPEECH_EPOCH = BrainrotQteClient.epoch();
            java.util.Arrays.fill(SPEECH_LIT, false);
        }
        if (hitSlot >= 0 && hitSlot < SPEECH_LIT.length) {
            SPEECH_LIT[hitSlot] = true;
        }
        boolean all = true;
        for (boolean lit : SPEECH_LIT) {
            all &= lit;
        }

        // 大字：整排按 2 倍放大，位置以歌词那一行和判定条之间为准
        final float scale = 2.0F;
        final int gap = 12;
        int total = -gap;
        for (String word : BrainrotBeats.SPEECH_WORDS) {
            total += Math.round(font.width(word) * scale) + gap;
        }
        float rowY = y - 78.0F;
        int left = Math.round(cx - total / 2.0F);
        int top = Math.round(rowY - font.lineHeight);

        // 底板 + 上下描边：字压得住任何背景（这是「亮眼」的一半）
        graphics.fill(left - 10, top - 6, left + total + 10, top + font.lineHeight * 2 + 8,
                ((all ? 0x78 : 0x5A) << 24) | 0x06060B);
        int edge = all ? 0xFFD700 : 0x9AA3B8;
        graphics.fill(left - 10, top - 6, left + total + 10, top - 5,
                ((all ? 0xCC : 0x88) << 24) | edge);

        float x = left;
        for (int i = 0; i < BrainrotBeats.SPEECH_WORDS.length; i++) {
            String word = BrainrotBeats.SPEECH_WORDS[i];
            int wPix = Math.round(font.width(word) * scale);
            boolean lit = SPEECH_LIT[i];
            // 该按的那一个：这一刻正好到它，放大一点、亮一截 —— 告诉玩家「就现在」
            int tick = anchor + i * BrainrotBeats.SPEECH_STEP;
            boolean due = !lit && Math.abs(age - tick) <= 3;
            // 没按中：暗但看得清；按中：纯白；三个全中：整排转金
            int alpha = lit ? 0xFF : due ? 0xE0 : 0x6A;
            int color = lit ? (all ? 0xFFE066 : 0xFFFFFF) : due ? 0xD8DEEA : 0x8E96AC;
            float wordScale = due ? scale * 1.18F : scale;
            var pose = graphics.pose();
            pose.pushPose();
            pose.translate(x + wPix / 2.0F, rowY, 0.0F);
            pose.scale(wordScale, wordScale, 1.0F);
            graphics.drawString(font, word, -font.width(word) / 2, -font.lineHeight / 2,
                    (alpha << 24) | color, true);
            pose.popPose();
            x += wPix + gap;
        }
        // 三个全中：底下再补一条亮线，整排收口
        if (all) {
            graphics.fill(left - 4, top + font.lineHeight * 2 + 2,
                    left + total + 4, top + font.lineHeight * 2 + 4,
                    0xCCFFD700);
        }
    }

    /** 把一个透明度值按 {@code factor}（0~1）缩放，返回 ARGB 的高 8 位。 */
    private static int fade(int alpha, float factor) {
        int scaled = Math.round(alpha * Math.max(0.0F, Math.min(1.0F, factor)));
        return scaled << 24;
    }

    /** 1~10 的罗马数字（面板里显示增益等级）。 */
    private static String roman(int value) {
        String[] table = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return value >= 1 && value <= table.length ? table[value - 1] : Integer.toString(value);
    }

    /** 屏幕四边各包一道 {@code alpha} 透明度的 {@code rgb} 色条（整屏判定的闪光）。 */
    private static void flashEdges(GuiGraphics graphics, int alpha, int rgb) {
        if (alpha <= 0) {
            return;
        }
        int color = (alpha << 24) | rgb;
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        int band = 28;
        graphics.fill(0, 0, width, band, color);
        graphics.fill(0, height - band, width, height, color);
        graphics.fill(0, 0, band, height, color);
        graphics.fill(width - band, 0, width, height, color);
    }
}
