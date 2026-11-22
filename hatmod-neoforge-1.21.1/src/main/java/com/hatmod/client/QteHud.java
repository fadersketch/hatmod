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
import net.minecraft.world.item.ItemStack;
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
    /** 光束期的配色：平时灰条 + 红键，光束期整条换成紫系，一眼看出进了哪一段。 */
    private static final int BEAM_BAR_RGB = 0x9B6BFF;
    private static final int BEAM_KEY_RGB = 0xB9A0FF;
    private static final int BEAM_KEY_HOT_RGB = 0xE6DBFF;
    /**
     * 前奏段的人声键：**淡红**（粉红），和正片那种正红分开 —— 前奏一开就很清楚
     * 现在是「还没进正片」的那一段。
     */
    private static final int INTRO_VOCAL_KEY_RGB = 0xE89090;
    private static final int INTRO_VOCAL_KEY_HOT_RGB = 0xFFB8B8;
    /**
     * 乐器键（间奏补的鼓点）画成**青色**：人声键红、乐器键青、示范键幽灵白、
     * 伴奏是条下面的帽子图标 —— 四种东西四种颜色，一眼分得出这一下是什么。
     */
    private static final int INSTRUMENT_KEY_RGB = 0x36C8BE;
    private static final int INSTRUMENT_KEY_HOT_RGB = 0x86F5EA;
    /** 光束期判定色：PERFECT 亮紫 / GOOD 淡紫（MISS 不跟着变，永远红）。 */
    private static final int BEAM_PERFECT_RGB = 0xB15CFF;
    private static final int BEAM_GOOD_RGB = 0xC7A3FF;

    private static boolean beamNow() {
        return BrainrotBeats.inBeam(BrainrotQteClient.age());
    }

    /** PERFECT 的颜色：平时金，光束期亮紫。 */
    /** 判定结果对应的颜色（HUD 里过线键固定用这一套）。 */
    private static int ratingRgb(int rating) {
        return rating == RATING_PERFECT ? perfectRgb()
                : rating == RATING_GOOD ? goodRgb() : 0xFF5555;
    }

    private static int perfectRgb() {
        return beamNow() ? BEAM_PERFECT_RGB : 0xFFD700;
    }

    /** GOOD 的颜色：平时绿，光束期淡紫。 */
    private static int goodRgb() {
        return beamNow() ? BEAM_GOOD_RGB : 0x55FF55;
    }
    /** 判定条变色 / 闪光用多久（刻）。 */
    private static final int RATING_TICKS = 10;
    /** 右侧判定字的完整寿命（刻）：满亮弹 6 刻（0.3s），再淡出 4 刻 —— 对应原版 0.25s + 0.2s。 */
    private static final int RATING_POP_TICKS = 10;
    /** 闪避提示显示多久（刻）。 */
    private static final int DODGE_TICKS = 14;
    /** 上一次画出来的闪避次数（涨了就在奖励面板上闪 +1）。 */
    private static int lastDodgeCount = -1;
    /**
     * 已经过线的键 -> 判定后固定下来的颜色（谱面刻）。
     *
     * <p>以前「判定色」只挂在「最近一次判定」上：打了下一拍，上一颗键就变回默认色，
     * 长音更是过了尾巴又暗回去 —— 看着像颜色在乱跳。现在一颗键一旦过线，
     * 颜色就定死，直到它滑出屏幕为止。
     */
    private static final java.util.Map<Integer, Integer> JUDGED_RGB = new java.util.HashMap<>();
    /** 上面那张表属于第几轮：换轮就清空（谱面刻会从头再数一遍）。 */
    private static int judgedEpoch = -1;
    /** 「闪避 +1」提示的截止时刻（毫秒）。 */
    private static long dodgePlusUntil;
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

    /**
     * 这一次「按下」已经报过一拍了。
     *
     * <p>按住右键的时候，原版每 4 刻会重复调一次 {@code startUseItem}，空手时每次都抛一条
     * {@code RightClickEmpty} / {@code RightClickBlock} —— 那些**不是新的按键**，是同一次长按。
     * 判定窗有 ±6 刻，不过滤的话长按就等于每 4 刻自动打一拍，谱面上的短键会被整个包住。
     * 松开之后（见 {@link #onClientTick}）才放开这一把锁。
     */
    private static boolean useTapSent;

    /** 上一次报给服务端的「按住」状态（只在变化时发包）。 */
    private static boolean lastHolding;
    /**
     * 每根长音**逐刻**的「按住没按住」：第 i 位 = 头拍之后第 i 刻按住了。
     *
     * <p>黄条按它逐段上色 —— 按住的那几刻黄，松手划过的那几刻红，
     * 一路按到底就是整根黄，中间松一次手就在那个位置留一段红。
     */
    private static final long[] HOLD_HELD = new long[BrainrotBeats.HOLD_COUNT];
    /** 上一次统计到的谱面刻（跳帧时把中间那几刻补上）。 */
    private static int lastHoldTick = -1;

    /**
     * 热手提示那句「随节奏打拍：V 或空手右键」—— **两个键名都按玩家实际的改键来**。
     *
     * <p>打拍键用 {@link #BEAT_TAP} 自己的显示名（改了键就跟着变）；「右键」用原版
     * 「使用」键的显示名（改键界面里它叫什么这里就写什么）。打拍键被清空时，
     * 退到只提「使用」键的那条文案（见 {@code hatmod.qte.hint.nokey}）。
     */
    private static Component beatTapHint() {
        Minecraft minecraft = Minecraft.getInstance();
        if (BEAT_TAP.isUnbound()) {
            return Component.translatable("hatmod.qte.hint.nokey",
                    minecraft.options.keyUse.getTranslatedKeyMessage());
        }
        return Component.translatable("hatmod.qte.hint",
                BEAT_TAP.getTranslatedKeyMessage(),
                minecraft.options.keyUse.getTranslatedKeyMessage());
    }

    /**
     * 给玩家看的按键名：未绑定给「未设置」；键盘上的单字符键（V、P 这类）补一个「键」字，
     * 鼠标 / 多字符名（鼠标右键、Space……）原样返回。
     *
     * <p>所有提到「V 键 / P 键 / 右键」的物品说明都从这里取名字 —— 玩家改了按键，
     * 文案跟着改，不能再写死默认键。
     */
    public static Component keyName(KeyMapping key) {
        if (key.isUnbound()) {
            return Component.translatable("hatmod.key.unbound");
        }
        Component name = key.getTranslatedKeyMessage();
        String raw = name.getString();
        return raw.matches("[A-Za-z0-9]")
                ? Component.translatable("hatmod.key.typed", name)
                : name;
    }

    /** 打拍键给玩家看的名字（默认「V 键」，改键后跟改）。 */
    public static Component beatKeyName() {
        return keyName(BEAT_TAP);
    }

    /** 「使用」键给玩家看的名字（默认「鼠标右键」，改键后跟改）。 */
    public static Component useKeyName() {
        return keyName(Minecraft.getInstance().options.keyUse);
    }

    /** 强制起手键给玩家看的名字（默认「P 键」，改键后跟改）。 */
    public static Component forceStartKeyName() {
        return keyName(FORCE_START);
    }

    /** 打拍键 / 右键现在按着没有（长音的「按住」判定用）。 */
    private static boolean isHolding(Minecraft minecraft) {
        // 代打开着 = 一直在按住，不然长音的黄条在代打里永远不动
        return BrainrotQteClient.autoTap()
                || BEAT_TAP.isDown() || minecraft.options.keyUse.isDown();
    }

    /**
     * 长音逐刻记账：这一下按住了就把「这一刻」标上。
     *
     * <p>不等「服务端确认头拍」再起算 —— 那个确认要一来一回两刻，黄条会一直慢半拍。
     * 死条反正画成灰的，这里多标的几刻也不会露出来。
     * 松手的刻**不标**，渲染时那几刻就是红的。
     */
    private static void trackHold(boolean holding) {
        int age = BrainrotQteClient.age();
        int prev = lastHoldTick;
        lastHoldTick = age;
        int hold = BrainrotBeats.holdAt(age);
        if (!holding || hold < 0 || hold >= HOLD_HELD.length) {
            return;
        }
        int start = BrainrotBeats.holdStart(hold);
        // 上一刻之后到这一刻：掉帧时 age 可能一次跳好几刻，按住的话就一起补上
        int from = prev < 0 || age - prev > 4 ? age : Math.max(start, prev + 1);
        for (int t = from; t <= age; t++) {
            int bit = t - start;
            if (bit >= 0 && bit < 64) {
                HOLD_HELD[hold] |= 1L << bit;
            }
        }
    }

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
        // 松开右键才放开「这次按下已经报过一拍」的锁：按住期间一直锁着（见 onRightClickEmpty）
        if (!minecraft.options.keyUse.isDown()) {
            useTapSent = false;
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
            lastHolding = false;
            lastHoldTick = -1;
            return;
        }
        // 长音的「按住」：只在变化时报一次，服务端拿它决定这段涨不涨连击
        boolean holding = isHolding(minecraft);
        if (holding != lastHolding) {
            lastHolding = holding;
            HatNetwork.sendQteHold(holding);
        }
        trackHold(holding);
        while (BEAT_TAP.consumeClick()) {
            BrainrotQteClient.tap();
        }
    }

    /** 空手右键也算一拍（键位之外的备用输入）。**一次按下只算一次**，按住不放不算连打。 */
    @SubscribeEvent
    public static void onRightClickEmpty(PlayerInteractEvent.RightClickEmpty event) {
        if (useTapSent) {
            return;
        }
        useTapSent = true;
        BrainrotQteClient.tap();
    }

    /** 空手右键对着方块时走的是 RightClickBlock（不是 Empty），一样算一拍（同样一次按下只算一次）。 */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (useTapSent || !event.getEntity().getMainHandItem().isEmpty()) {
            return;
        }
        useTapSent = true;
        BrainrotQteClient.tap();
    }

    /**
     * 原始鼠标按下：右键自己的 4 刻冷却（Minecraft 里那个私有计数器）会把连按吞掉，
     * 所以打拍直接听**鼠标原始输入**，按得再密也接得住。
     */
    @SubscribeEvent
    public static void onMouseButton(net.neoforged.neoforge.client.event.InputEvent.MouseButton.Pre event) {
        if (Minecraft.getInstance().screen != null) {
            return;                         // 界面里点鼠标不算打拍
        }
        if (event.getButton() == org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT
                && event.getAction() == org.lwjgl.glfw.GLFW.GLFW_PRESS) {
            // 这一下「按下」已经记过一拍了：同一次长按后面重复抛的交互事件不再算
            useTapSent = true;
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

    /** 开局压暗的时长：0–8 秒满强度，8–14 秒淡出（20 刻 = 1 秒）。 */
    private static final int INTRO_DIM_HOLD = 160;
    private static final int INTRO_DIM_FADE = 120;

    /**
     * 开局的「昏暗」不再用原版 Darkness 药水（那玩意儿是直接挖掉视野，太极端），
     * 改成一层冷色 + 暗角的画面滤镜：世界还在，只是像隔着一层夜色。
     *
     * <p>NeoForge 在 {@link RenderGuiEvent.Pre} 画 —— 此时世界已经画完、原版 HUD 还没开始，
     * 所以只压世界，不压血条 / 快捷栏 / 判定条。Forge 版注册在 VIGNETTE 下方，效果等价。
     */
    @SubscribeEvent
    public static void onRenderGuiPre(RenderGuiEvent.Pre event) {
        renderIntroDim(event.getGuiGraphics());
    }

    /** 画开局那层冷色滤镜（0–14 秒，之后完全透明）。 */
    public static void renderIntroDim(GuiGraphics graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !BrainrotQteClient.active()) {
            return;
        }
        int age = BrainrotQteClient.age();
        int end = INTRO_DIM_HOLD + INTRO_DIM_FADE;
        if (age < 0 || age >= end) {
            return;
        }
        float k = age < INTRO_DIM_HOLD ? 1.0F
                : 1.0F - (age - INTRO_DIM_HOLD) / (float) INTRO_DIM_FADE;
        k = Math.max(0.0F, Math.min(1.0F, k));
        int w = graphics.guiWidth();
        int h = graphics.guiHeight();
        // 全屏均匀压暗：整块视野一起暗下来，而不是只压四周
        int base = Math.round(104 * k);
        graphics.fill(0, 0, w, h, (base << 24) | 0x06101E);
    }

    /** 倒计时的三个词：青 → 金 → 绿，GO 再放大一号。 */
    private static final String[] COUNTDOWN_TEXT = {"READY", "SET", "GO"};
    private static final int[] COUNTDOWN_COLORS = {0x55FFFF, 0xFFAA00, 0x55FF55};

    /** 画判定轨道（NeoForge 走 RenderGuiEvent，Forge 注册成 GUI 覆盖层后也调这里）。 */
    public static void render(GuiGraphics graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !BrainrotQteClient.active()) {
            return;
        }
        int age = BrainrotQteClient.age();
        // 闪避次数涨了就记一下：奖励面板在 dodge 那一行旁边闪个 +1
        long now = System.currentTimeMillis();
        int dodgeCount = BrainrotQteClient.dodges();
        if (lastDodgeCount >= 0 && dodgeCount > lastDodgeCount) {
            dodgePlusUntil = now + 1400L;
        }
        lastDodgeCount = dodgeCount;
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

        // 换了一轮：上一轮的过线颜色作废
        int epoch = BrainrotQteClient.epoch();
        if (epoch != judgedEpoch) {
            judgedEpoch = epoch;
            JUDGED_RGB.clear();
            java.util.Arrays.fill(HOLD_HELD, 0L);
        }
        // 最近一次判定打了什么、落在哪一拍
        int rating = BrainrotQteClient.rating();
        int since = age - BrainrotQteClient.ratingTick();
        int judgedBeatTick = -1;
        if (rating != 0 && since >= 0 && since <= 24) {
            // 只认服务端报的那一拍：长音里每 5 刻刷一次的 PERFECT 不带拍号（reported < 0），
            // 以前「就近猜」会把旁边那颗键染成金色 —— 那颗其实还没按。
            int reported = BrainrotQteClient.judgedTick();
            judgedBeatTick = reported;
            if (reported >= 0) {
                // 这一颗的颜色就此定死（PERFECT / GOOD / MISS 各一种），后面不再变
                JUDGED_RGB.put(judgedBeatTick, ratingRgb(rating));
            }
            if (JUDGED_RGB.size() > 512) {
                JUDGED_RGB.entrySet().removeIf(e -> e.getKey() < age - 400);
            }
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
        // 前奏链路：条 / 打拍反馈 / 歌词在**前奏第一句人声之前 2 拍**完全就绪。
        // 淡入从就绪前 3 秒开始；更早的时候屏幕是干净的。
        int hudStart = BrainrotBeats.hudStartTick();
        int hudReady = BrainrotBeats.hudReadyTick();
        if (age < hudStart) {
            return;
        }
        float reveal = Math.max(0.0F, Math.min(1.0F,
                (age - hudStart) / (float) (hudReady - hudStart)));
        float lyricReveal = reveal;
        // 最后那段黑暗：不再放键，整条判定条用约 5 秒慢慢淡出去
        boolean keysOff = age >= BrainrotBeats.FINAL_DARK;
        if (keysOff) {
            reveal *= Math.max(0.0F, 1.0F - (age - BrainrotBeats.FINAL_DARK) / 100.0F);
        }

        // 「Nope / too / late」这一排字**从开始渲染到结束**（含淡出尾巴）整段：
        // 记分表、判定条、歌词全部收起来 —— 这一段屏幕上只留这三个词，
        // 有点像按下 F1 的那种"世界安静了"。词一消失，三样东西一起恢复。
        int speechAt = speechAnchor(age);
        boolean speechOnly = speechAt >= 0;
        if (speechOnly) {
            reveal = 0.0F;
        }

        // ---------- 歌词：判定条上方一行，卡拉 OK 式（唱过的亮、没唱的暗，下面是下一句预告） ----------
        // 「Nope / too / late」在场期间整行收起：这一段屏幕上只留那三个词。
        if (speechAt < 0) {
            drawLyrics(graphics, font, cx, y, age, lyricReveal);
        }

        // 光束期：谱面跑得更快，条和键一起换成冷色
        boolean beamPhase = BrainrotBeats.inBeam(age);
        float ppt = PIXELS_PER_TICK * BrainrotBeats.scrollFactor(age);
        // 可见窗口跟着速度一起换算：轨道半宽固定，跑得快就只剩更少的刻能看见
        int leadTicks = Math.round(TRACK_HALF / ppt);
        int trailTicks = Math.round(20.0F / ppt);
        int barRgb = beamPhase ? BEAM_BAR_RGB : 0x9FA6C8;
        int barBounce = 0;
        if (rating != 0 && since >= 0 && since <= 8) {
            barRgb = rating == RATING_PERFECT ? perfectRgb()
                    : rating == RATING_GOOD ? goodRgb() : 0xFF5555;
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

        // ---------- 演示键 / 实体键 ----------
        // 前奏人声之前那 2 拍是演示键（半透明，按了不算）。
        // 其余位置如果被编辑器标成示范键，走下面的 DEMO 表，同样只画不判。
        int revealAlpha = Math.round(255 * reveal);
        for (int i = 0; i < BrainrotBeats.DEMO_BEATS; i++) {
            int demoBeat = BrainrotBeats.demoTick(i);
            // 谱面自己已经在这一刻排了真键时，别再叠一个幽灵键（编辑器重排过的谱面会撞）
            if (BrainrotBeats.hasNoteNear(demoBeat, 2)
                    || BrainrotBeats.hasDemoNear(demoBeat, 2)) {
                continue;
            }
            int delta = demoBeat - age;
            if (delta < 0 || delta > leadTicks) {
                continue;
            }
            int x = cx + Math.round(delta * ppt);
            int alpha = Math.round(0x55 * reveal);
            if (alpha > 0) {
                graphics.fill(x - 1, barY - 6, x + 3, barY + 7, (alpha << 24) | 0xFFFFFF);
            }
        }
        for (int i = 0; i < BrainrotBeats.count(); i++) {
            if (keysOff) {
                break;
            }
            int tick = BrainrotBeats.tickAt(i);
            int delta = tick - age;
            if (delta < -trailTicks || delta > leadTicks) {
                continue;
            }
            int x = cx + Math.round(delta * ppt);
            boolean judged = tick == judgedBeatTick && delta < 0;
            int rgb;
            int bodyAlpha;
            if (delta >= 0) {
                // 人声键 = 红色（光束期转冷色）；乐器 / 鼓点 = 青色 —— 打的是什么一眼分得出
                boolean hot = Math.abs(delta) <= 4;
                boolean vocal = BrainrotBeats.isVocal(tick);
                if (!vocal) {
                    rgb = hot ? INSTRUMENT_KEY_HOT_RGB : INSTRUMENT_KEY_RGB;
                } else if (BrainrotBeats.inIntro(age)) {
                    // 前奏段：人声键用淡红，和正片的正红分开
                    rgb = hot ? INTRO_VOCAL_KEY_HOT_RGB : INTRO_VOCAL_KEY_RGB;
                } else {
                    rgb = beamPhase ? (hot ? BEAM_KEY_HOT_RGB : BEAM_KEY_RGB)
                            : (hot ? 0xFF2020 : 0xD22C2C);
                }
                bodyAlpha = revealAlpha;
            } else {
                bodyAlpha = (int) (0xDD * (1.0F + delta / 8.0F) * reveal);
                if (bodyAlpha <= 0) {
                    continue;
                }
                // 过线之后颜色**定死**：判定成什么色就一直是什么色，不随「最近一次判定」
                // 换人而变回默认色，直到它滑出屏幕
                Integer fixed = JUDGED_RGB.get(tick);
                rgb = fixed != null ? fixed : (judged ? ratingRgb(rating) : 0xB03030);
            }
            // 「NOPE / too / late」那三个键画成纯白：只在**还没过线**的时候；
            // 过了线就跟着判定色走，不再闪回白色
            boolean speechKey = delta >= 0 && BrainrotBeats.isSpeechKey(tick);
            if (speechKey) {
                rgb = 0xFFFFFF;
            }
            // 小节头（每 4 拍）画高两像素、宽两像素：重音看得出来，拍子才有「骨架」
            boolean strong = !judged && BrainrotBeats.isBarBeat(tick);
            int top = judged ? barY - 11
                    : (strong ? barY - 10 : (delta >= 0 && Math.abs(delta) <= 4 ? barY - 8 : barY - 6));
            // 外发光（白键就是白晕，在深色条上很跳）
            int glowAlpha = Math.max(0, bodyAlpha / 3);
            graphics.fill(x - (strong ? 3 : 2), top - 1, x + (strong ? 5 : 4), barY + 8,
                    (glowAlpha << 24) | rgb);
            // 本体
            graphics.fill(x - (strong ? 1 : 0), top, x + (strong ? 3 : 2), barY + 7,
                    (bodyAlpha << 24) | rgb);
            // 顶端亮点（还没到的那拍才画，像音游里的音符头）
            if (delta >= 0) {
                graphics.fill(x - 1, top - 2, x + 3, top, (bodyAlpha << 24) | rgb);
            }
        }

        // ---------- 伴奏层：牛仔帽（可选，不判连击，打中给白色光柱） ----------
        ItemStack hatIcon = accIcon();
        for (int i = 0; i < BrainrotBeats.bonusCount(); i++) {
            if (keysOff) {
                break;
            }
            int tick = BrainrotBeats.bonusTick(i);
            int delta = tick - age;
            // 留 6 刻给「自动按下」的白闪
            if (delta < -6 || delta > leadTicks) {
                continue;
            }
            int x = cx + Math.round(delta * ppt);
            if (Math.round(255 * reveal) < 20) {
                continue;
            }
            int hatY = barY + 12;
            // 光照期才画底光：外圈一层更宽的柔光 + 内圈亮白底，帽子像被打着了；
            // 没光照时**一点底光都不留**，图标压成偏冷的深灰 —— 两种状态差一大截。
            // 只在还没过线时画：一过线连底光一起撤掉，不在判定条后面留一块方块
            if (beamPhase && delta >= 0) {
                int soft = Math.round(0x22 * reveal);
                if (soft > 0) {
                    graphics.fill(x - 10, hatY - 3, x + 10, hatY + 14, (soft << 24) | 0xFFFFFF);
                }
                int halo = Math.round(0x7A * reveal);
                if (halo > 0) {
                    graphics.fill(x - 7, hatY - 1, x + 7, hatY + 12, (halo << 24) | 0xFFFFFF);
                }
            }
            if (delta >= 0) {
                var pose = graphics.pose();
                pose.pushPose();
                pose.translate(x - 5.5F, hatY, 0.0F);
                pose.scale(0.7F, 0.7F, 1.0F);
                graphics.renderItem(hatIcon, 0, 0);
                pose.popPose();
                if (beamPhase) {
                    // 光照期：灰罩撤掉，叠一层亮白把帽子图标点起来
                    int lit = Math.round(0x6E * reveal);
                    if (lit > 0) {
                        graphics.fill(x - 6, hatY - 1, x + 6, hatY + 12, (lit << 24) | 0xFFFFFF);
                    }
                } else {
                    // 没光柱：压一层偏冷的深灰，明显比有光时暗一档，但帽子轮廓还看得清
                    int gray = Math.round(0x46 * reveal);
                    if (gray > 0) {
                        graphics.fill(x - 6, hatY - 1, x + 6, hatY + 12, (gray << 24) | 0x4A5260);
                    }
                }
            } else {
                // 过线：帽子本体和底光**立刻消失**（不再在判定条后面滞留一块方块），
                // 只在判定线上打一圈扩散的白光 —— 这就是「打中了」的响应特效。
                float k = Math.min(1.0F, -delta / 6.0F);
                int flash = Math.round(0xE8 * reveal * (1.0F - k));
                if (flash > 0) {
                    int cyHat = hatY + 5;
                    int r = 3 + Math.round(9 * k);
                    graphics.fill(cx - r, cyHat - 1, cx + r, cyHat + 2, (flash << 24) | 0xFFFFFF);
                    graphics.fill(cx - 1, cyHat - r, cx + 2, cyHat + r, (flash << 24) | 0xFFFFFF);
                    // 判定线同时亮一下：一眼看到这一拍被自动打了
                    graphics.fill(cx - 1, barY - 4, cx + 1, barY + 5, (flash << 24) | 0xFFFFFF);
                }
            }
        }

        // ---------- 示范键（ghost）：只画不算分 ----------
        // 和 TICKS 分开存：谱面编辑器可以把任意位置标成示范键，导出后走这张表。
        for (int i = 0; i < BrainrotBeats.demoNoteCount(); i++) {
            if (keysOff) {
                break;
            }
            int tick = BrainrotBeats.demoNoteTick(i);
            int delta = tick - age;
            // 到判定线就消失，不穿过去；按了也不算分
            if (delta < 0 || delta > leadTicks) {
                continue;
            }
            int x = cx + Math.round(delta * ppt);
            int ghostAlpha = Math.round(0x55 * reveal);
            if (ghostAlpha > 0) {
                graphics.fill(x - 1, barY - 6, x + 3, barY + 7, (ghostAlpha << 24) | 0xFFFFFF);
            }
        }

        // ---------- 示范长音：白色虚线长条，只画不判 ----------
        // 编辑器里标成「示范长音」的段落走这张表：不判、不加连击，只告诉玩家这里要按住。
        for (int h = 0; h < BrainrotBeats.demoHoldCount(); h++) {
            if (keysOff) {
                break;
            }
            int start = BrainrotBeats.demoHoldStart(h);
            int end = BrainrotBeats.demoHoldEnd(h);
            int headDelta = start - age;
            int tailDelta = end - age;
            if (headDelta > leadTicks * 5 / 4 || tailDelta < -trailTicks) {
                continue;
            }
            int hx = cx + Math.round(headDelta * ppt);
            int tx = cx + Math.round(tailDelta * ppt);
            int left = Math.min(hx, tx);
            int right = Math.max(hx, tx);
            // 底条：半透明白，比实键长音淡一档
            int bodyAlpha = Math.round(0x33 * reveal);
            if (bodyAlpha > 0 && right > left) {
                graphics.fill(left, barY - 4, right, barY + 5, (bodyAlpha << 24) | 0xFFFFFF);
            }
            // 虚线：每 6 像素亮 3 像素 —— 一眼能和实键长音分开
            if (right > left) {
                for (int x = left; x < right; x += 6) {
                    int dashEnd = Math.min(x + 3, right);
                    graphics.fill(x, barY - 1, dashEnd, barY + 2, fade(0x9A, reveal) | 0xFFFFFF);
                }
            }
            // 头：空心白块，到点也不消失（示范键不判定）
            int headAlpha = Math.round(0x99 * reveal);
            if (headAlpha > 0) {
                graphics.fill(hx - 2, barY - 8, hx + 4, barY + 9, (headAlpha << 24) | 0xFFFFFF);
                graphics.fill(hx - 1, barY - 6, hx + 3, barY + 7, fade(0x55, reveal) | 0x101018);
            }
        }

        // ---------- 长音（1:08–1:10）：一根从「头」拖到「尾」的长条 ----------
        // 头拍踩上、并且**真的按住**：服务端按按住的那几刻每 5 刻涨一层连击，同时让光柱保持涌动态。
        if (!keysOff) {
            for (int h = 0; h < BrainrotBeats.HOLD_COUNT; h++) {
                int headTick = BrainrotBeats.holdHeadTick(h);
                int holdEnd = BrainrotBeats.holdEnd(h);
                int headDelta = headTick - age;
                int tailDelta = holdEnd - age;
                if (headDelta > leadTicks * 5 / 4 || tailDelta < -trailTicks) {
                    continue;
                }
                int hx = cx + Math.round(headDelta * ppt);
                int tx = cx + Math.round(tailDelta * ppt);
                int left = Math.min(hx, tx);
                int right = Math.max(hx, tx);
                // 头拍一过就一直用「已过线」的亮色，直到整条滑出屏幕 ——
                // 以前按住那段结束（age >= holdEnd）又会暗回去，看起来像颜色在跳。
                boolean passed = age >= headTick;
                int rest = beamPhase ? (passed ? BEAM_KEY_HOT_RGB : BEAM_KEY_RGB)
                        : (passed ? 0xFF2020 : 0xB02A2A);
                // 头拍真踩上了没有：
                //   踩上了 = 这根条「有效」，**真正按住**的那一段（不是过线长度）变黄；
                //   过窗还没踩上 = 作废（失去不补），整条暗成灰，连击也不会再涨。
                boolean headHit = BrainrotQteClient.holdHeadHit(h);
                boolean dead = !headHit && passed
                        && age > headTick + BrainrotBeats.lateWindowAt(
                                BrainrotBeats.holdHeadIndex(h)) + 4;
                if (dead) {
                    graphics.fill(left, barY - 4, right, barY + 5, fade(0x22, reveal) | 0x4C4C55);
                    graphics.fill(left, barY - 1, right, barY + 2, fade(0x7A, reveal) | 0x4C4C55);
                } else if (headHit && passed) {
                    // 逐刻上色：**按住的那几刻黄、松手划过的那几刻红** ——
                    // 一路按到底整根黄；中间松一次手，那个位置就留一段红（黄红黄）。
                    int done = Math.min(holdEnd, age);
                    long mask = HOLD_HELD[h];
                    int t = headTick;
                    while (t < done) {
                        int bit = t - headTick;
                        boolean held = bit < 64 && (mask & (1L << bit)) != 0L;
                        int runEnd = t + 1;
                        while (runEnd < done) {
                            int b = runEnd - headTick;
                            boolean next = b < 64 && (mask & (1L << b)) != 0L;
                            if (next != held) {
                                break;
                            }
                            runEnd++;
                        }
                        int x0 = cx + Math.round((t - age) * ppt);
                        int x1 = cx + Math.round((runEnd - age) * ppt);
                        int seg = held ? 0xFFE14D : 0x7A1010;
                        graphics.fill(x0, barY - 4, x1, barY + 5,
                                fade(held ? 0x55 : 0x33, reveal) | seg);
                        graphics.fill(x0, barY - 1, x1, barY + 2,
                                fade(held ? 0xEE : 0xCC, reveal) | seg);
                        t = runEnd;
                    }
                    // 还没划到的那一段：原来的红（光柱期是冷色）
                    int cut = Math.max(left,
                            Math.min(right, hx + Math.round((done - headTick) * ppt)));
                    if (right > cut) {
                        graphics.fill(cut, barY - 4, right, barY + 5, fade(0x44, reveal) | rest);
                        graphics.fill(cut, barY - 1, right, barY + 2, fade(0xCC, reveal) | rest);
                    }
                } else {
                    graphics.fill(left, barY - 4, right, barY + 5, fade(0x44, reveal) | rest);
                    graphics.fill(left, barY - 1, right, barY + 2, fade(0xCC, reveal) | rest);
                }
                // 头部亮点（就踩在它上面起手）：白 = 还没踩，黄 = 踩上了，灰 = 作废
                graphics.fill(hx - 1, barY - 9, hx + 3, barY + 10,
                        fade(0xFF, reveal) | (dead ? 0x3C3C44 : headHit ? 0xFFD700 : 0xFFFFFF));
            }
        }

        // ---------- 节拍奖励面板：闪避 / 伤害加成 / 当前吃到的增益 ----------
        // 摆在判定条左上，把「打拍挣到了什么」直接写在屏幕上。
        if (reveal > 0.05F) {
            java.util.List<Component> lines = new java.util.ArrayList<>();
            lines.add(Component.translatable("hatmod.qte.reward.title"));
            lines.add(Component.translatable("hatmod.qte.reward.hits", BrainrotQteClient.hits()));
            lines.add(Component.translatable("hatmod.qte.reward.score", BrainrotQteClient.score()));
            lines.add(Component.translatable("hatmod.qte.reward.judge",
                    BrainrotQteClient.perfects(), BrainrotQteClient.goods()));
            lines.add(Component.translatable("hatmod.qte.reward.miss", BrainrotQteClient.misses()));
            // 行号别写死：上面加一行（踩对数），下面 +1 提示就会跑到别的行去
            int dodgeLine = lines.size();
            lines.add(Component.translatable("hatmod.qte.reward.dodge", dodgeCount));
            // 攒闪避的打拍计数：伴奏和主旋律共用同一个 0/10，**只在光柱期间显示** ——
            // 光柱之外本来也不累计（服务端 BrainrotQte 那边就卡在 inBeam 上）。
            if (BrainrotBeats.inBeam(age)) {
                lines.add(Component.translatable("hatmod.qte.reward.acc",
                        BrainrotQteClient.acc(), BrainrotBeats.BONUS_PER_DODGE));
            }
            // 不封顶：连击打得高就该显示多少，999% 那种上限会让人以为「打满了」
            lines.add(Component.translatable("hatmod.qte.reward.bonus",
                    BrainrotQteClient.combo() * 2));
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
            int panelW = 0;
            for (Component line : lines) {
                panelW = Math.max(panelW, font.width(line));
            }
            panelW += 10;
            // 闪 +1 的时候多留一格，别把提示裁掉
            if (now < dodgePlusUntil) {
                panelW += 14;
            }
            int panelH = lines.size() * 10 + 8;
            // 贴着判定条**往上长**：行数变多（踩对 / 得分 / 判定统计）也不会压到轨道上
            int panelY = Math.max(4, y - panelH - 6);
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
                // 闪避 +1：贴在闪避那一行右边，闪一下再淡出
                if (i == dodgeLine && now < dodgePlusUntil) {
                    long leftMs = dodgePlusUntil - now;
                    int alpha = (int) Math.min(0xFFL, leftMs * 0xFFL / 1400L);
                    alpha = Math.round(alpha * reveal);
                    if (alpha > 0) {
                        graphics.drawString(font, "+1",
                                panelX + 1 + font.width(lines.get(i)) + 4, lineY,
                                (alpha << 24) | 0xFFD54A, true);
                    }
                }
                lineY += 10;
            }
        }

        // ---------- 判定线：菱形 + 外圈光晕 ----------
        int lineRgb = 0xFFFFFF;
        if (rating != 0 && since >= 0 && since <= 8) {
            lineRgb = rating == RATING_PERFECT ? perfectRgb()
                    : rating == RATING_GOOD ? goodRgb() : 0xFF5555;
        }
        // 由外到内三层：光晕 → 半透 → 实心菱形
        graphics.fill(cx - 5, barY - 2, cx + 5, barY + 3, fade(0x33, reveal) | 0x101018);
        graphics.fill(cx - 3, barY - 6, cx + 3, barY + 7, fade(0x44, reveal) | lineRgb);
        graphics.fill(cx - 2, barY - 9, cx + 2, barY + 10, fade(0xAA, reveal) | lineRgb);
        graphics.fill(cx - 1, barY - 10, cx + 1, barY + 11, fade(0xFF, reveal) | lineRgb);

        // ---------- 整屏反馈 ----------
        if (rating == RATING_PERFECT && since >= 0 && since <= 10) {
            flashEdges(graphics, (int) (80 * (1.0F - since / 10.0F)), perfectRgb());
        }
        if (rating == RATING_GOOD && since >= 0 && since <= 8) {
            flashEdges(graphics, (int) (45 * (1.0F - since / 8.0F)), goodRgb());
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
        for (int p = 0; !speechOnly && p < BrainrotQteClient.popupCount(); p++) {
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
            int color = popupRating == RATING_PERFECT ? perfectRgb()
                    : popupRating == RATING_GOOD ? goodRgb() : 0xFF5555;
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
        // 连击板：**只显示 > 2 的连击**（一两连不刷屏；断了直接消失）
        int combo = BrainrotQteClient.combo();
        float plateReveal = reveal;
        if (combo > 2 && plateReveal > 0.01F) {
            Component comboText = Component.translatable("hatmod.qte.combo", combo)
                    .withStyle(ChatFormatting.BOLD, ChatFormatting.YELLOW);
            int bonus = combo * 2;
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

        // ---------- READY / SET / GO：第一次人声前 5 拍起，一拍一个词 ----------
        drawCountdown(graphics, font, cx, height, age);

        // ---------- 热手提示 ----------
        if (age >= BrainrotBeats.hudStartTick() && age < BrainrotBeats.INTRO_END) {
            Component hint = beatTapHint();
            graphics.drawString(font, hint, cx - font.width(hint) / 2, y + 14, 0xFFB9C2D6, true);
        }
    }

    /**
     * READY / SET / GO：从第一次人声往前 {@link BrainrotBeats#COUNTDOWN_LEAD_BEATS} 拍起，
     * 一拍一个词，一个接一个（屏幕上永远只有一个）。
     */
    private static void drawCountdown(GuiGraphics graphics, Font font, int cx, int height, int age) {
        for (int i = 0; i < COUNTDOWN_TEXT.length; i++) {
            int at = BrainrotBeats.countdownTick(i);
            int until = i + 1 < COUNTDOWN_TEXT.length
                    ? BrainrotBeats.countdownTick(i + 1)
                    : BrainrotBeats.introVocalTick();
            int beatAge = age - at;
            if (beatAge < 0 || age >= until) {
                continue;
            }
            int wordWidth = font.width(COUNTDOWN_TEXT[i]);
            float pop = Math.min(1.0F, beatAge / 3.0F);
            float scale = (1.0F + (1.0F - pop) * 1.2F) * (i == 2 ? 2.0F : 1.6F);
            float fadeOut = Math.max(0.0F, Math.min(1.0F, (until - (float) age) / 2.0F));
            int alpha = (int) (255.0F * Math.min(1.0F, pop + 0.25F) * fadeOut);
            if (alpha <= 0) {
                continue;
            }
            Component text = Component.literal(COUNTDOWN_TEXT[i]).withStyle(ChatFormatting.BOLD);
            var pose = graphics.pose();
            pose.pushPose();
            pose.translate(cx, (float) (lyricLineY(height) - 42), 0.0F);
            pose.scale(scale, scale, 1.0F);
            graphics.drawString(font, text, -wordWidth / 2, -font.lineHeight / 2,
                    (alpha << 24) | COUNTDOWN_COLORS[i], true);
            pose.popPose();
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

    /**
     * 这一刻是不是处在「Nope / too / late」那一排字的显示窗口里；在的话返回这一句的锚点刻
     * （第一个词那一刻），不在返回 -1。窗口和 {@link #drawSpeechRow} 用的是同一套。
     */
    private static int speechAnchor(int age) {
        return BrainrotBeats.speechAnchor(age);
    }

    /**
     * 「Nope / too / late」—— **一排大字**，三个词从头就在，按中哪个哪个亮。
     *
     * <p>这三拍已经由服务端代按（见 {@code BrainrotQte#autoTapSpeech}），玩家不需要做任何事，
     * 所以这里不再是「暗影 + 按中变白」那套交互显示，就是一句一句打出来的白字幕：
     * 「Nope」→「too」→「late」，各自带 3 刻淡入，位置固定不跳。
     *
     * @param hitSlot 保留参数（已经没有交互判定了，调用方还传着）
     */
    private static void drawSpeechRow(GuiGraphics graphics, Font font, int cx, int y,
                                      int age, int hitSlot) {
        int first = BrainrotBeats.NO_TOO_LATE_FIRST;
        int second = BrainrotBeats.NO_TOO_LATE_SECOND;
        int anchor = Math.abs(age - second) <= Math.abs(age - first) ? second : first;
        int last = anchor + BrainrotBeats.SPEECH_STEP * (BrainrotBeats.SPEECH_WORDS.length - 1);
        if (age < anchor - 8 || age > last + BrainrotBeats.SPEECH_TAIL) {
            return;
        }
        // 正常字号的 2 倍，后面再压一层黑底
        final float scale = 2.0F;
        final int gap = 8;
        int total = -gap;
        for (String word : BrainrotBeats.SPEECH_WORDS) {
            total += Math.round(font.width(word) * scale) + gap;
        }
        // 摆在**判定条那一行**（{@code y}）：这一段条本来是藏起来的，位置正好空出来。
        float rowY = y;
        float x = Math.round(cx - total / 2.0F);
        // 黑底：第一个词出口那一刻**整块压上**，不淡入 —— 三个词是跟着人声砸出来的，
        // 淡入会把开口那一瞬磨圆。整条托住三个词（2 倍字号压在画面上才看得清）。
        if (age >= anchor) {
            int padX = Math.round(7 * scale);
            int padY = Math.round(4 * scale);
            int halfHeight = Math.round(font.lineHeight * scale / 2.0F);
            graphics.fill(Math.round(x) - padX, Math.round(rowY) - halfHeight - padY,
                    Math.round(x) + total + padX, Math.round(rowY) + halfHeight + padY,
                    0xFF000000);
        }
        for (int i = 0; i < BrainrotBeats.SPEECH_WORDS.length; i++) {
            String word = BrainrotBeats.SPEECH_WORDS[i];
            int wPix = Math.round(font.width(word) * scale);
            int tick = anchor + i * BrainrotBeats.SPEECH_STEP;
            // 这三拍已经由服务端代按，玩家什么都不用做 —— 就是从左往右一行行打字幕。
            // 还没轮到它的词一个字都不画；位置照占，后面的词不会往左跳。
            if (age < tick) {
                x += wPix + gap;
                continue;
            }
            // 到点整字弹出，不淡入：这三个词是念出来的，出口那一刻就是全白
            int alpha = 255;
            var pose = graphics.pose();
            pose.pushPose();
            pose.translate(x + wPix / 2.0F, rowY, 0.0F);
            pose.scale(scale, scale, 1.0F);
            graphics.drawString(font, word, -font.width(word) / 2, -font.lineHeight / 2,
                    (alpha << 24) | 0xFFFFFF, true);
            pose.popPose();
            x += wPix + gap;
        }
    }

    /** 把一个透明度值按 {@code factor}（0~1）缩放，返回 ARGB 的高 8 位。 */
    /** 伴奏层的图标：牛仔帽。 */
    private static ItemStack accIcon;

    private static ItemStack accIcon() {
        if (accIcon == null) {
            accIcon = new ItemStack(com.hatmod.HatItems.BLACK_HAT.get());
        }
        return accIcon;
    }

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
