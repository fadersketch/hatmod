package com.hatmod.client;

import com.hatmod.HatNetwork;
import com.hatmod.HatSettings;
import com.hatmod.HatItems;
import com.hatmod.HatType;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 牛仔帽调参界面。
 *
 * <p>上面一排按钮切换要调哪顶帽子，下面六行分别对应：蓄力刻数、照射刻数、
 * 每刻固定伤害、附加百分比、百分比下限、伤害类型 id。点「保存」把当前这顶帽子的
 * 六个值发给服务端（服务端夹范围、写 {@code config/hatmod.json}、再广播回所有人）。
 *
 * <p>选中「全」时前三个框改成三条路线的帧伤，后三个仍是它自己那份配置，
 * 中间那行还会多出一个「强制路线」按钮。
 *
 * <p>界面上的当前值来自 {@link ClientHatSettings}（服务端同步过来的副本），
 * 找不到时按出厂值显示。「恢复默认」只是把框里填回 {@code HatType} 的出厂值，
 * 要点「保存」才真的生效。
 *
 * <p>点「保存」做两件事：把这份值发给服务端（权威），并<b>立刻写回客户端副本</b>，
 * 所以关掉界面再打开看到的就是刚填的值，不用等服务端回包。服务端落盘后会广播回来，
 * 以服务端夹过范围的最终值为准。
 *
 * <p>同名的 {@code /hatmod tune ...} 命令走原版命令通道，是调参器的兜底路径：
 * 万一某个整合包里自定义网络包出了问题，命令一样能改。
 */
public class HatTunerScreen extends Screen {

    private static final String[] FIELD_KEYS = {
            "hatmod.tuner.charge",
            "hatmod.tuner.flash",
            "hatmod.tuner.damage",
            "hatmod.tuner.ratio",
            "hatmod.tuner.floor",
            "hatmod.tuner.type",
    };

    /** 「脑蚀」页只显示前三行：每帧伤害倍率 / Teto 帧步进 / Teto 画质。 */
    private static final String[] BRAINROT_KEYS = {
            "hatmod.tuner.brainrotScale",
            "hatmod.tuner.tetoFrameStep",
            "hatmod.tuner.tetoQuality",
    };
    /** 「脑蚀」页显示几行。 */
    private static final int BRAINROT_ROWS = BRAINROT_KEYS.length;

    /** 选中「全」时，前三个框改成这三条路线的帧伤（标签也跟着换）。 */
    private static final String[] ALL_ROUTE_KEYS = {
            "hatmod.tuner.route.black",
            "hatmod.tuner.route.white",
            "hatmod.tuner.route.red",
    };

    /*
     * 垂直排版按「最坏情况 320×240」来定：自动 GUI 缩放只保证这么大。
     * 之前把底部三个按钮、中间那行说明和「强制路线」都往下堆，矮窗口里必然叠在一起
     * （说明甚至压到按钮上面）。现在每一块都占死一条自己的带子：
     *   标题 / 帽子按钮 / 「强制路线」/ 六行输入框 / 状态行 / 底部按钮，从上到下依次排开，
     * 行距按屏幕高度摊，底部按钮永远排在状态行之下、绝不出屏。
     */
    private static final int TITLE_Y = 8;
    private static final int HAT_ROW_Y = 30;
    private static final int FORCED_ROW_Y = 52;
    private static final int ROWS_TOP = 74;
    /** 状态行留给底部按钮的空档（按钮上沿与六行末行之间的净距）。 */
    private static final int STATUS_GAP = 11;
    private static final int ROW_H_MIN = 18;
    private static final int ROW_H_MAX = 26;
    private static final int FIELD_H = 16;
    private static final int FIELD_W = 200;
    private static final int BUTTON_H = 20;

    private final EditBox[] fields = new EditBox[FIELD_KEYS.length];
    private final Button[] hatButtons = new Button[HatType.values().length];

    /** 「全」页专用：强制路线的循环按钮（随机 → 黑 → 白 → 红）。 */
    private Button forcedRouteButton;
    /** 「自动打拍」调试开关：只在「脑蚀」页出现。 */
    private Button autoTapButton;
    /** 强制路线的待保存值：{@code random} / black / white / red。 */
    private String forcedRoute;

    private HatType selected = HatType.BLACK;

    /** 是不是停在「脑蚀」页：那一页六行里只留第一行（每帧伤害倍率）。 */
    private boolean brainrotTab;
    /** 「脑蚀」页的按钮。 */
    private Button brainrotButton;

    /** 全局开关「自己的 BGM 优先」在界面上的待保存状态。 */
    private boolean ownBgmPriority;
    private Button ownBgmButton;

    /** 「合成表」页开着没有。 */
    private boolean showRecipes;
    private Button recipeButton;
    /** 合成表翻到第几页（0 起）。 */
    private int recipePage;

    public HatTunerScreen() {
        super(Component.translatable("hatmod.tuner.title"));
    }

    /** 左侧标签的可用起点。窄窗口往里收，免得标签被切在屏幕外。 */
    private int left() {
        return Math.max(6, this.width / 2 - 170);
    }

    /** 输入框左边缘：保持旧版「屏幕中线 -40」的位置，宽窗口下跟以前一模一样。 */
    private int fieldX() {
        return this.width / 2 - 40;
    }

    private int fieldW() {
        return Math.max(80, Math.min(FIELD_W, this.width - fieldX() - 8));
    }

    /**
     * 六行输入框的行距：按屏幕高度摊。
     *
     * <p>先把状态行和底部按钮的位置预留出来，剩下的高度除以六。矮窗口（240）压到 21 上下，
     * 高窗口最多散到 26。这样底部那排按钮永远有位置，不会再和「强制路线」或说明文字叠在一起。
     */
    private int rowH() {
        int avail = (this.height - 28) - STATUS_GAP - ROWS_TOP;
        return Math.max(ROW_H_MIN, Math.min(ROW_H_MAX, avail / FIELD_KEYS.length));
    }

    private int rowY(int index) {
        return ROWS_TOP + index * rowH();
    }

    private int bottomY() {
        return Math.min(ROWS_TOP + FIELD_KEYS.length * rowH() + 10, this.height - 28);
    }

    /**
     * 状态行的 y：永远排在六行输入框之下，同时尽量贴着底部按钮。
     *
     * <p>取二者的大值，万一窗口比 320×240 还矮（正常 GUI 缩放不会），也不会反过来压到
     * 最后一行输入框上。
     */
    private int statusY() {
        int belowRows = ROWS_TOP + (FIELD_KEYS.length - 1) * rowH() + FIELD_H + 2;
        return Math.max(belowRows, bottomY() - STATUS_GAP);
    }

    @Override
    protected void init() {
        int left = left();
        int fieldX = fieldX();
        int fieldW = fieldW();

        // 帽子切换：按钮总宽按屏宽收着排，保证四顶帽子 +「脑蚀」在最小 GUI 缩放（320 宽）下也放得下
        HatType[] types = HatType.values();
        int gap = 4;
        int count = types.length + 1;
        int buttonW = Math.min(90, (Math.min(this.width, 360) - gap * (count - 1)) / count);
        int totalW = buttonW * count + gap * (count - 1);
        int startX = this.width / 2 - totalW / 2;
        for (int i = 0; i < types.length; i++) {
            HatType type = types[i];
            hatButtons[i] = Button.builder(
                            Component.translatable("hatmod.tuner.hat." + type.id()),
                            button -> select(type))
                    .bounds(startX + i * (buttonW + gap), HAT_ROW_Y, buttonW, BUTTON_H)
                    .build();
            addRenderableWidget(hatButtons[i]);
        }
        this.brainrotButton = Button.builder(Component.translatable("hatmod.tuner.hat.brainrot"),
                        button -> selectBrainrot())
                .bounds(startX + types.length * (buttonW + gap), HAT_ROW_Y, buttonW, BUTTON_H)
                .build();
        addRenderableWidget(this.brainrotButton);

        // 六个输入框
        for (int i = 0; i < FIELD_KEYS.length; i++) {
            EditBox box = new EditBox(this.font, fieldX, rowY(i), fieldW, FIELD_H,
                    Component.translatable(FIELD_KEYS[i]));
            box.setMaxLength(64);
            fields[i] = addRenderableWidget(box);
        }

        // 底部按钮
        int bottom = bottomY();
        // 只留「保存 / 恢复默认」两个：关界面按 Esc 就行，那个「完成」是多余的
        addRenderableWidget(Button.builder(Component.translatable("hatmod.tuner.save"), button -> save())
                .bounds(this.width / 2 - 106, bottom, 100, BUTTON_H).build());
        addRenderableWidget(Button.builder(Component.translatable("hatmod.tuner.reset"), button -> resetFields())
                .bounds(this.width / 2 + 6, bottom, 100, BUTTON_H).build());

        // 「合成表」：左上角一个开关，点开就把整条合成链画成工作台
        this.recipeButton = Button.builder(recipeLabel(), button -> {
                    this.showRecipes = !this.showRecipes;
                    button.setMessage(recipeLabel());
                })
                .bounds(left(), 6, 70, BUTTON_H)
                .build();
        addRenderableWidget(this.recipeButton);

        // 全局开关「自己的 BGM 优先」：放在右上角，和标题同一行，不占用中间排版
        this.ownBgmPriority = ClientHatSettings.ownBgmPriority();
        this.ownBgmButton = Button.builder(ownBgmLabel(), button -> {
            this.ownBgmPriority = !this.ownBgmPriority;
            button.setMessage(ownBgmLabel());
        }).bounds(this.width - 118, TITLE_Y, 108, BUTTON_H).build();
        addRenderableWidget(this.ownBgmButton);

        // 「全」页专用：强制路线循环按钮（随机 → 黑 → 白 → 红）。只在选中「全」时可见；
        // 它占的那一行永远留着，所以切帽子时上下六行不会跳。
        this.forcedRoute = ClientHatSettings.forcedRoute();
        this.forcedRouteButton = Button.builder(forcedRouteLabel(), button -> {
            this.forcedRoute = nextForcedRoute(this.forcedRoute);
            button.setMessage(forcedRouteLabel());
        }).bounds(this.width / 2 - 90, FORCED_ROW_Y, 180, BUTTON_H).build();
        addRenderableWidget(this.forcedRouteButton);

        // 「脑蚀」页专用：自动打拍（调试用）。它和「强制路线」抢同一行，但两者不会同时出现。
        this.autoTapButton = Button.builder(autoTapLabel(), button -> {
            BrainrotQteClient.toggleAutoTap();
            button.setMessage(autoTapLabel());
        }).bounds(this.width / 2 - 90, FORCED_ROW_Y, 180, BUTTON_H).build();
        addRenderableWidget(this.autoTapButton);

        fillFromSelection();
    }

    /** 强制路线循环：随机 → 黑 → 白 → 红 → 随机。 */
    private static String nextForcedRoute(String current) {
        if (HatType.BLACK.id().equals(current)) {
            return HatType.WHITE.id();
        }
        if (HatType.WHITE.id().equals(current)) {
            return HatType.RED.id();
        }
        if (HatType.RED.id().equals(current)) {
            return HatSettings.ROUTE_RANDOM;
        }
        return HatType.BLACK.id();
    }

    /** 强制路线按钮上的文字：名字 + 当前（待保存的）值。 */
    private Component forcedRouteLabel() {
        String valueKey = HatSettings.ROUTE_RANDOM.equals(this.forcedRoute)
                ? "hatmod.tuner.route.random"
                : "hatmod.tuner.hat." + this.forcedRoute;
        return Component.translatable("hatmod.tuner.forcedRoute")
                .append(Component.literal(": "))
                .append(Component.translatable(valueKey));
    }

    /** 开关按钮上的文字：名字 + 当前（待保存的）状态。 */
    private Component ownBgmLabel() {
        return Component.translatable("hatmod.tuner.ownBgm")
                .append(Component.literal(": "))
                .append(Component.translatable(this.ownBgmPriority ? "hatmod.tuner.on" : "hatmod.tuner.off"));
    }

    /** 切换帽子：把框里的值换成那顶帽子的当前值。 */
    private void select(HatType type) {
        this.brainrotTab = false;
        this.selected = type;
        fillFromSelection();
    }

    /** 切到「脑蚀」页：六行里只留第一行，填它的每帧伤害倍率。 */
    private void selectBrainrot() {
        this.brainrotTab = true;
        fillFromSelection();
    }

    private void fillFromSelection() {
        if (this.brainrotTab) {
            fields[0].setValue(format(ClientHatSettings.brainrotDamageScale()));
            // 第二、三行是 Teto 的渲染参数（纯客户端，不走服务端）：帧步进 / 画质
            fields[1].setValue(Integer.toString(TetoRenderSettings.frameStep()));
            fields[2].setValue(Integer.toString(TetoRenderSettings.quality()));
            for (int i = BRAINROT_ROWS; i < fields.length; i++) {
                fields[i].setValue("");
            }
            setFieldVisibility();
            updateButtons();
            if (this.forcedRouteButton != null) {
                this.forcedRouteButton.visible = false;
                this.forcedRouteButton.active = false;
            }
            return;
        }
        ClientHatSettings.Snapshot snapshot = ClientHatSettings.get(this.selected);
        if (this.selected == HatType.ALL) {
            // 「全」页：前三个框改成三条路线的帧伤（蓄力/照射是路线驱动的，不在这里调），
            // 后三个（附加% / 下限% / 伤害类型）仍是「全」自己那份配置。
            fields[0].setValue(format(ClientHatSettings.routeDamage(HatType.BLACK)));
            fields[1].setValue(format(ClientHatSettings.routeDamage(HatType.WHITE)));
            fields[2].setValue(format(ClientHatSettings.routeDamage(HatType.RED)));
        } else {
            fields[0].setValue(Integer.toString(snapshot.chargeTicks));
            fields[1].setValue(Integer.toString(snapshot.flashTicks));
            fields[2].setValue(format(snapshot.damagePerTick));
        }
        fields[3].setValue(format(snapshot.healthDamageRatio));
        fields[4].setValue(format(snapshot.healthDamageFloor));
        fields[5].setValue(snapshot.damageType);

        if (this.forcedRouteButton != null) {
            this.forcedRoute = ClientHatSettings.forcedRoute();
            this.forcedRouteButton.visible = this.selected == HatType.ALL;
            this.forcedRouteButton.active = this.selected == HatType.ALL;
            this.forcedRouteButton.setMessage(forcedRouteLabel());
        }

        setFieldVisibility();
        updateButtons();
    }

    /** 「脑蚀」页只留前三行；其它页六行全显示。 */
    private void setFieldVisibility() {
        for (int i = 0; i < fields.length; i++) {
            fields[i].visible = !this.brainrotTab || i < BRAINROT_ROWS;
        }
    }

    /** 当前正在编辑的那个按钮置灰（当高亮用）。 */
    private void updateButtons() {
        HatType[] types = HatType.values();
        for (int i = 0; i < types.length; i++) {
            if (hatButtons[i] != null) {
                hatButtons[i].active = !this.brainrotTab && types[i] != this.selected;
            }
        }
        if (this.brainrotButton != null) {
            this.brainrotButton.active = !this.brainrotTab;
        }
        if (this.autoTapButton != null) {
            this.autoTapButton.visible = this.brainrotTab;
            this.autoTapButton.active = this.brainrotTab;
            this.autoTapButton.setMessage(autoTapLabel());
        }
    }

    /** 自动打拍按钮上的文字：名字 + 当前开关。 */
    private Component autoTapLabel() {
        return Component.translatable("hatmod.tuner.autoTap")
                .append(Component.literal(": "))
                .append(Component.translatable(
                        BrainrotQteClient.autoTap() ? "hatmod.tuner.on" : "hatmod.tuner.off"));
    }

    /** 「恢复默认」只改界面，点保存才真的生效。 */
    private void resetFields() {
        if (this.brainrotTab) {
            fields[0].setValue(format(HatSettings.DEFAULT_BRAINROT_DAMAGE_SCALE));
            fields[1].setValue(Integer.toString(TetoRenderSettings.DEFAULT_FRAME_STEP));
            fields[2].setValue(Integer.toString(TetoRenderSettings.DEFAULT_QUALITY));
            return;
        }
        if (this.selected == HatType.ALL) {
            fields[0].setValue(format(HatSettings.DEFAULT_ROUTE_DAMAGE_BLACK));
            fields[1].setValue(format(HatSettings.DEFAULT_ROUTE_DAMAGE_WHITE));
            fields[2].setValue(format(HatSettings.DEFAULT_ROUTE_DAMAGE_RED));
            this.forcedRoute = HatSettings.ROUTE_RANDOM;
            if (this.forcedRouteButton != null) {
                this.forcedRouteButton.setMessage(forcedRouteLabel());
            }
        } else {
            fields[0].setValue(Integer.toString(this.selected.chargeTicks()));
            fields[1].setValue(Integer.toString(this.selected.flashTicks()));
            fields[2].setValue(format(this.selected.damagePerTick()));
        }
        fields[3].setValue(format(HatSettings.DEFAULT_HEALTH_DAMAGE_RATIO));
        fields[4].setValue(format(HatSettings.DEFAULT_HEALTH_DAMAGE_FLOOR));
        fields[5].setValue(HatSettings.DEFAULT_DAMAGE_TYPE);
    }

    private void save() {
        if (this.minecraft == null || this.minecraft.player == null) {
            return;
        }
        if (this.brainrotTab) {
            saveBrainrot();
            return;
        }
        Integer chargeTicks;
        Integer flashTicks;
        Float damagePerTick;
        if (this.selected == HatType.ALL) {
            // 「全」页前三个框是路线帧伤；蓄力/照射是路线驱动的，原样提交「全」自己那份占位值
            ClientHatSettings.Snapshot snapshot = ClientHatSettings.get(HatType.ALL);
            chargeTicks = snapshot.chargeTicks;
            flashTicks = snapshot.flashTicks;
            damagePerTick = snapshot.damagePerTick;
        } else {
            chargeTicks = parseInt(0);
            flashTicks = parseInt(1);
            damagePerTick = parseFloat(2);
        }
        Float ratio = parseFloat(3);
        Float floor = parseFloat(4);
        if (chargeTicks == null || flashTicks == null || damagePerTick == null
                || ratio == null || floor == null) {
            complain("hatmod.tuner.badNumber");
            return;
        }
        // 「全」页的三条路线帧伤：只有选中「全」时才从框里读（那时前三个框就是它们）；
        // 编辑别的帽子时前三个框是蓄力/照射/帧伤，不能拿来当路线帧伤，沿用客户端当前值。
        float routeBlack;
        float routeWhite;
        float routeRed;
        if (this.selected == HatType.ALL) {
            Float parsedBlack = parseFloat(0);
            Float parsedWhite = parseFloat(1);
            Float parsedRed = parseFloat(2);
            if (parsedBlack == null || parsedWhite == null || parsedRed == null) {
                complain("hatmod.tuner.badNumber");
                return;
            }
            routeBlack = parsedBlack;
            routeWhite = parsedWhite;
            routeRed = parsedRed;
        } else {
            routeBlack = ClientHatSettings.routeDamage(HatType.BLACK);
            routeWhite = ClientHatSettings.routeDamage(HatType.WHITE);
            routeRed = ClientHatSettings.routeDamage(HatType.RED);
        }
        String damageType = normalizeNumber(fields[5].getValue()).replace('\uFF1A', ':');
        if (damageType.isEmpty()) {
            complain("hatmod.tuner.badType");
            return;
        }

        String forced = this.forcedRoute == null ? HatSettings.ROUTE_RANDOM : this.forcedRoute;
        HatNetwork.sendSettingsUpdate(this.selected, chargeTicks, flashTicks,
                damagePerTick, ratio, floor, damageType, this.ownBgmPriority,
                routeBlack, routeWhite, routeRed, forced,
                ClientHatSettings.brainrotDamageScale());
        // 本地立刻回显：界面上「当前值」马上就是刚填的这份，不用等服务端回包。
        // 真正生效的仍然是服务端那份 —— 服务端改完会广播回来覆盖这里；
        // 万一被拒（非创造/非 OP），回包里带的也是服务端的真实值，界面会自己纠回来。
        ClientHatSettings.set(this.selected, chargeTicks, flashTicks,
                damagePerTick, ratio, floor, damageType);
        ClientHatSettings.setOwnBgmPriority(this.ownBgmPriority);
        // 强制路线只在「全」页有意义，也只在那一页改
        ClientHatSettings.setRoute(routeBlack, routeWhite, routeRed,
                this.selected == HatType.ALL ? forced : ClientHatSettings.forcedRoute());
        onClose();
    }

    /**
     * 「脑蚀」页只改一个倍率：其它参数原样提交（和「全」页提交占位值是同一个思路）。
     */
    private void saveBrainrot() {
        Float scale = parseFloat(0);
        Integer frameStep = parseInt(1);
        Integer quality = parseInt(2);
        if (scale == null || frameStep == null || quality == null) {
            complain("hatmod.tuner.badNumber");
            return;
        }
        // 这两项是纯客户端的渲染参数，直接落 config/hatmod-client.json，不用发给服务端
        TetoRenderSettings.setFrameStep(frameStep);
        TetoRenderSettings.setQuality(quality);
        ClientHatSettings.Snapshot snapshot = ClientHatSettings.get(this.selected);
        HatNetwork.sendSettingsUpdate(this.selected, snapshot.chargeTicks, snapshot.flashTicks,
                snapshot.damagePerTick, snapshot.healthDamageRatio, snapshot.healthDamageFloor,
                snapshot.damageType, this.ownBgmPriority,
                ClientHatSettings.routeDamage(HatType.BLACK), ClientHatSettings.routeDamage(HatType.WHITE),
                ClientHatSettings.routeDamage(HatType.RED), ClientHatSettings.forcedRoute(), scale);
        ClientHatSettings.setBrainrotDamageScale(scale);
        onClose();
    }

    private void complain(String key) {
        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.displayClientMessage(
                    Component.translatable(key).withStyle(ChatFormatting.RED), false);
        }
    }

    private Integer parseInt(int index) {
        try {
            return Integer.valueOf(Integer.parseInt(normalizeNumber(fields[index].getValue())));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Float parseFloat(int index) {
        try {
            return Float.valueOf(Float.parseFloat(normalizeNumber(fields[index].getValue())));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /**
     * 全角转半角 + 去掉空白。
     *
     * <p>中文输入法下（IMBlocker 没拦住的时候）很容易把 {@code 100} 打成全角的 {@code １００}，
     * {@code Integer.parseInt} 会直接抛 {@code NumberFormatException}，
     * 表现出来就是「点了保存什么都没发生、数值还是原来的」——踩过一次，这里统一转一下。
     * 半角字符原样保留，所以英文键盘输入不受影响。
     */
    private static String normalizeNumber(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '\uFF10' && c <= '\uFF19') {
                out.append((char) (c - '\uFF10' + '0'));      // ０-９ -> 0-9
            } else if (c == '\uFF0E' || c == '\u3002') {
                out.append('.');                              // ．。 -> .
            } else if (c == '\uFF0D' || c == '\u2212') {
                out.append('-');                              // －− -> -
            } else if (c == '\uFF0B') {
                out.append('+');                              // ＋ -> +
            } else if (!Character.isWhitespace(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** 20.0 显示成 20，0.05 保持 0.05 —— 少点没用的尾数。 */
    private static String format(float value) {
        if (value == Math.round(value)) {
            return Integer.toString(Math.round(value));
        }
        return Float.toString(value);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(this.font, this.title, this.width / 2, TITLE_Y, 0xFFFFFF);

        int labelX = left() + 4;
        boolean all = this.selected == HatType.ALL;
        for (int i = 0; i < FIELD_KEYS.length; i++) {
            // 「脑蚀」页只有第一行；「全」页前三个框的标签换成黑/白/红三条路线的帧伤
            if (this.brainrotTab && i >= BRAINROT_ROWS) {
                continue;
            }
            String key = this.brainrotTab
                    ? BRAINROT_KEYS[i]
                    : (all && i < ALL_ROUTE_KEYS.length ? ALL_ROUTE_KEYS[i] : FIELD_KEYS[i]);
            int y = rowY(i) + (FIELD_H - this.font.lineHeight) / 2 + 1;
            graphics.drawString(this.font, Component.translatable(key), labelX, y, 0xA0A0A0);
        }

        // 底部按钮上方那一行：平时写正在编辑哪顶，选中「全」时换成它自己那句说明
        // （前三个框的含义 + 蓄力/照射为什么不在这一页调）。只占一行，不会顶到按钮。
        Component status = this.brainrotTab
                // 「脑蚀」页直接把 Teto 的实际情况写出来：多少帧/秒、多大分辨率
                ? Component.literal(TetoRenderSettings.describe())
                : all
                ? Component.translatable("hatmod.tuner.allRouteNote")
                : Component.translatable("hatmod.tuner.editing",
                        Component.translatable("hatmod.tuner.hat." + this.selected.id()));
        graphics.drawCenteredString(this.font, status, this.width / 2, statusY(),
                all ? 0xB06060 : 0x808080);

        if (this.showRecipes) {
            renderRecipes(graphics);
        }
    }

    private Component recipeLabel() {
        return Component.translatable(this.showRecipes
                ? "hatmod.tuner.recipesBack" : "hatmod.tuner.recipes");
    }

    /** 配方格：钻头用 L/E/N/D/S，帽子用 T（= 钻头）。 */
    private static ItemStack recipeItem(char key) {
        switch (key) {
            case 'L':
                return new ItemStack(Items.LEATHER);
            case 'B':
                return new ItemStack(Items.BLACK_DYE);
            case 'W':
                return new ItemStack(Items.WHITE_DYE);
            case 'R':
                return new ItemStack(Items.RED_DYE);
            case 'E':
                return new ItemStack(Items.ECHO_SHARD);
            case 'N':
                return new ItemStack(Items.NETHER_STAR);
            case 'D':
                return new ItemStack(Items.BLACK_DYE);
            case 'S':
                return new ItemStack(Items.SCULK_CATALYST);
            case 'T':
                return new ItemStack(HatItems.MYSTERIOUS_DRILL.get());
            default:
                return ItemStack.EMPTY;
        }
    }

    /** 合成表一共几页（每页两个配方）。 */
    private static final int RECIPE_PAGES = 3;

    /**
     * 合成表页：把**全部帽子**的合成链画成真正的 3×3 工作台。
     *
     * <p>第 1 页：脑蚀那条链（材料 → 神秘红毛钻头 → 脑蚀）；
     * 第 2 页：黑 / 白（同一套版型，只换染料）；
     * 第 3 页：红 / 全（「全」是三顶帽子的融合，位置随意，不限定 3×3 摆位）。
     * 用底部的 ‹ › 翻页。
     */
    private void renderRecipes(GuiGraphics graphics) {
        graphics.fill(0, 0, this.width, this.height, 0xC008080C);
        int panelW = Math.min(340, this.width - 16);
        int panelH = Math.min(210, this.height - 24);
        int x0 = (this.width - panelW) / 2;
        int y0 = (this.height - panelH) / 2;
        graphics.fill(x0, y0, x0 + panelW, y0 + panelH, 0xFF15151C);
        graphics.fill(x0, y0, x0 + panelW, y0 + 1, 0xFF8B5CF6);
        graphics.fill(x0, y0 + panelH - 1, x0 + panelW, y0 + panelH, 0xFF8B5CF6);
        graphics.drawCenteredString(this.font, Component.translatable("hatmod.tuner.recipesTitle"),
                this.width / 2, y0 + 6, 0xFFFFFF);

        int cell = 22;
        int gridX = x0 + 18;
        int firstY = y0 + 24;
        int secondY = firstY + cell * 3 + 22;
        int page = Math.max(0, Math.min(RECIPE_PAGES - 1, this.recipePage));

        String note;
        if (page == 0) {
            drawCraftingRow(graphics, gridX, firstY, cell, new String[]{"LEL", "NDN", " S "},
                    new ItemStack(HatItems.MYSTERIOUS_DRILL.get()),
                    Component.translatable("hatmod.tuner.recipeDrill"));
            drawCraftingRow(graphics, gridX, secondY, cell, new String[]{" T ", "TTT"},
                    new ItemStack(HatItems.BRAIN_HAT.get()),
                    Component.translatable("hatmod.tuner.recipeHat"));
            note = "hatmod.tuner.recipeNote";
        } else if (page == 1) {
            drawCraftingRow(graphics, gridX, firstY, cell, new String[]{"LLL", "LBN", "NNN"},
                    new ItemStack(HatItems.BLACK_HAT.get()),
                    Component.translatable("hatmod.tuner.hat.black_hat"));
            drawCraftingRow(graphics, gridX, secondY, cell, new String[]{"LLL", "LWN", "NNN"},
                    new ItemStack(HatItems.WHITE_HAT.get()),
                    Component.translatable("hatmod.tuner.hat.white_hat"));
            note = "hatmod.tuner.hatRecipeNote";
        } else {
            drawCraftingRow(graphics, gridX, firstY, cell, new String[]{"LLL", "LRN", "NNN"},
                    new ItemStack(HatItems.RED_HAT.get()),
                    Component.translatable("hatmod.tuner.hat.red_hat"));
            drawFusionRow(graphics, gridX, secondY, cell,
                    Component.translatable("hatmod.tuner.hat.all_hat"));
            note = "hatmod.tuner.fusionNote";
        }

        // 翻页：底部 ‹ 页码 ›；热区和这里保持一致（见 mouseClicked）
        int navY = recipeNavY();
        int cx = this.width / 2;
        if (page > 0) {
            graphics.drawString(this.font, "<", cx - 62, navY, 0xFFFFFF);
        }
        if (page < RECIPE_PAGES - 1) {
            graphics.drawString(this.font, ">", cx + 54, navY, 0xFFFFFF);
        }
        graphics.drawCenteredString(this.font,
                Component.translatable("hatmod.tuner.recipePage", page + 1, RECIPE_PAGES),
                cx, navY, 0x9A9AA8);
        graphics.drawString(this.font, Component.translatable(note),
                x0 + 10, y0 + panelH - 14, 0x9A9AA8);
    }

    /** 翻页那一行的 y（面板底部往上 30 像素；和点击热区共用）。 */
    private int recipeNavY() {
        int panelH = Math.min(210, this.height - 24);
        return (this.height - panelH) / 2 + panelH - 30;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.showRecipes) {
            // 合成表开着时整块面板吃掉点击，别让底下被盖住的输入框抢到焦点
            int navY = recipeNavY();
            int cx = this.width / 2;
            if (mouseY >= navY - 2 && mouseY <= navY + 10) {
                if (mouseX >= cx - 66 && mouseX <= cx - 46) {
                    this.recipePage = Math.max(0, this.recipePage - 1);
                    return true;
                }
                if (mouseX >= cx + 50 && mouseX <= cx + 70) {
                    this.recipePage = Math.min(RECIPE_PAGES - 1, this.recipePage + 1);
                    return true;
                }
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 一行配方：3×3 格 + 箭头 + 产物格 + 名字。 */
    private void drawCraftingRow(GuiGraphics graphics, int x, int y, int cell,
                                  String[] pattern, ItemStack result, Component label) {
        for (int row = 0; row < 3; row++) {
            String line = row < pattern.length ? pattern[row] : "";
            for (int col = 0; col < 3; col++) {
                int sx = x + col * cell;
                int sy = y + row * cell;
                // 格子：深色底 + 上缘高光，看起来像工作台的槽
                graphics.fill(sx, sy, sx + cell - 2, sy + cell - 2, 0xFF262631);
                graphics.fill(sx, sy, sx + cell - 2, sy + 1, 0xFF3C3C4C);
                char key = col < line.length() ? line.charAt(col) : ' ';
                graphics.renderItem(recipeItem(key), sx + 3, sy + 3);
            }
        }
        int outX = drawArrow(graphics, x + cell * 3 + 6, y + cell + 2) + 8;
        int outY = y + cell - 2;
        drawResultSlot(graphics, outX, outY, cell, result);
        graphics.drawString(this.font, label, outX + cell + 8, outY + 6, 0xFFFFFF);
    }

    /** 融合配方那一行：三顶帽子并排（位置随意）+ 箭头 + 「全」。 */
    private void drawFusionRow(GuiGraphics graphics, int x, int y, int cell, Component label) {
        ItemStack[] parts = {
                new ItemStack(HatItems.BLACK_HAT.get()),
                new ItemStack(HatItems.WHITE_HAT.get()),
                new ItemStack(HatItems.RED_HAT.get()),
        };
        for (int i = 0; i < parts.length; i++) {
            int sx = x + i * cell;
            graphics.fill(sx, y, sx + cell - 2, y + cell - 2, 0xFF262631);
            graphics.fill(sx, y, sx + cell - 2, y + 1, 0xFF3C3C4C);
            graphics.renderItem(parts[i], sx + 3, y + 3);
        }
        int outX = drawArrow(graphics, x + cell * 3 + 6, y + cell / 2 - 2) + 8;
        drawResultSlot(graphics, outX, y - 2, cell, new ItemStack(HatItems.ALL_HAT.get()));
        graphics.drawString(this.font, label, outX + cell + 8, y + 4, 0xFFFFFF);
    }

    /** 箭头，返回它右边缘的 x（调用方据此摆产物格）。 */
    private int drawArrow(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + 12, y + 2, 0xFF9A9AAA);
        graphics.fill(x + 10, y - 3, x + 12, y + 5, 0xFF9A9AAA);
        graphics.fill(x + 12, y - 1, x + 14, y + 3, 0xFF9A9AAA);
        return x + 14;
    }

    private void drawResultSlot(GuiGraphics graphics, int x, int y, int cell, ItemStack result) {
        graphics.fill(x, y, x + cell, y + cell, 0xFF3A2E52);
        graphics.fill(x, y, x + cell, y + 1, 0xFF8B5CF6);
        graphics.renderItem(result, x + 3, y + 3);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
