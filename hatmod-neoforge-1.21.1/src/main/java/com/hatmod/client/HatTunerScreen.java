package com.hatmod.client;

import com.hatmod.HatNetwork;
import com.hatmod.HatSettings;
import com.hatmod.HatType;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 牛仔帽调参界面。
 *
 * <p>上面一排按钮切换要调哪顶帽子，下面六行分别对应：蓄力刻数、照射刻数、
 * 每刻固定伤害、附加百分比、百分比下限、伤害类型 id。点「保存」把当前这顶帽子的
 * 六个值发给服务端（服务端夹范围、写 {@code config/hatmod.json}、再广播回所有人）。
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

    /** 选中「全」时，前三个框改成这三条路线的帧伤（标签也跟着换）。 */
    private static final String[] ALL_ROUTE_KEYS = {
            "hatmod.tuner.route.black",
            "hatmod.tuner.route.white",
            "hatmod.tuner.route.red",
    };

    private static final int ROW_H = 24;
    private static final int FIELD_H = 18;
    private static final int FIELD_W = 200;
    private static final int FIRST_ROW_Y = 64;

    private final EditBox[] fields = new EditBox[FIELD_KEYS.length];
    private final Button[] hatButtons = new Button[HatType.values().length];

    /** 「全」页专用：强制路线的循环按钮（随机 → 黑 → 白 → 红）。 */
    private Button forcedRouteButton;
    /** 强制路线的待保存值：{@code random} / black / white / red。 */
    private String forcedRoute;

    private HatType selected = HatType.BLACK;

    /** 「自己的 BGM 优先」的待保存值（点「保存」才发给服务端）。 */
    private boolean ownBgmPriority;

    private Button ownBgmButton;

    public HatTunerScreen() {
        super(Component.translatable("hatmod.tuner.title"));
    }

    private int left() {
        return this.width / 2 - 170;
    }

    /**
     * 底部三个按钮那一行的 y。
     *
     * <p>界面高度不够时往上收：自动 GUI 缩放最小只保证 320×240，按固定值排的话
     * 按钮会贴着屏幕下边缘（甚至被裁掉），点起来很别扭。
     */
    private int bottomY() {
        return Math.min(FIRST_ROW_Y + FIELD_KEYS.length * ROW_H + 12, this.height - 28);
    }

    @Override
    protected void init() {
        int left = left();

        // 帽子切换：按钮总宽按屏宽收着排，保证四顶帽子在最小 GUI 缩放（320 宽）下也放得下
        HatType[] types = HatType.values();
        int gap = 4;
        int buttonW = Math.min(100, (Math.min(this.width, 360) - gap * (types.length - 1)) / types.length);
        int totalW = buttonW * types.length + gap * (types.length - 1);
        int startX = this.width / 2 - totalW / 2;
        for (int i = 0; i < types.length; i++) {
            HatType type = types[i];
            hatButtons[i] = Button.builder(
                            Component.translatable("hatmod.tuner.hat." + type.id()),
                            button -> select(type))
                    .bounds(startX + i * (buttonW + gap), 36, buttonW, 20)
                    .build();
            addRenderableWidget(hatButtons[i]);
        }

        // 六个输入框
        for (int i = 0; i < FIELD_KEYS.length; i++) {
            EditBox box = new EditBox(this.font, left + 130, FIRST_ROW_Y + i * ROW_H, FIELD_W, FIELD_H,
                    Component.translatable(FIELD_KEYS[i]));
            box.setMaxLength(64);
            fields[i] = addRenderableWidget(box);
        }

        // 底部按钮
        int bottom = bottomY();
        addRenderableWidget(Button.builder(Component.translatable("hatmod.tuner.save"), button -> save())
                .bounds(this.width / 2 - 156, bottom, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("hatmod.tuner.reset"), button -> resetFields())
                .bounds(this.width / 2 - 50, bottom, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(this.width / 2 + 56, bottom, 100, 20).build());

        // 全局开关「自己的 BGM 优先」：放在右上角，不动原来的排版
        this.ownBgmPriority = ClientHatSettings.ownBgmPriority();
        this.ownBgmButton = Button.builder(ownBgmLabel(), button -> {
            this.ownBgmPriority = !this.ownBgmPriority;
            button.setMessage(ownBgmLabel());
        }).bounds(this.width - 118, 10, 108, 20).build();
        addRenderableWidget(this.ownBgmButton);

        // 「全」页专用：强制路线循环按钮（随机 → 黑 → 白 → 红）。只在选中「全」时可见。
        // y 夹一下，保证最小 GUI 缩放（320×240）下也不会掉出屏幕外。
        this.forcedRoute = ClientHatSettings.forcedRoute();
        int routeY = Math.min(bottomY() + 22, Math.max(0, this.height - 22));
        this.forcedRouteButton = Button.builder(forcedRouteLabel(), button -> {
            this.forcedRoute = nextForcedRoute(this.forcedRoute);
            button.setMessage(forcedRouteLabel());
        }).bounds(this.width / 2 - 90, routeY, 180, 20).build();
        addRenderableWidget(this.forcedRouteButton);

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
        this.selected = type;
        fillFromSelection();
    }

    private void fillFromSelection() {
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

        // 蓄力/照射两个框在「全」页不适用（路线驱动），禁掉免得以为填了会生效
        fields[0].setEditable(this.selected != HatType.ALL);
        fields[1].setEditable(this.selected != HatType.ALL);

        if (this.forcedRouteButton != null) {
            this.forcedRoute = ClientHatSettings.forcedRoute();
            this.forcedRouteButton.visible = this.selected == HatType.ALL;
            this.forcedRouteButton.active = this.selected == HatType.ALL;
            this.forcedRouteButton.setMessage(forcedRouteLabel());
        }

        HatType[] types = HatType.values();
        for (int i = 0; i < types.length; i++) {
            if (hatButtons[i] != null) {
                // 当前正在编辑的那顶按下去没有意义，置灰当高亮用
                hatButtons[i].active = types[i] != this.selected;
            }
        }
    }

    /** 「恢复默认」只改界面，点保存才真的生效。 */
    private void resetFields() {
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
                routeBlack, routeWhite, routeRed, forced);
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

        graphics.drawCenteredString(this.font, this.title, this.width / 2, 14, 0xFFFFFF);

        int labelX = left() + 4;
        boolean all = this.selected == HatType.ALL;
        for (int i = 0; i < FIELD_KEYS.length; i++) {
            // 「全」页：前三个框的标签换成黑/白/红三条路线的帧伤
            String key = all && i < ALL_ROUTE_KEYS.length ? ALL_ROUTE_KEYS[i] : FIELD_KEYS[i];
            int y = FIRST_ROW_Y + i * ROW_H + (FIELD_H - this.font.lineHeight) / 2 + 1;
            graphics.drawString(this.font, Component.translatable(key), labelX, y, 0xA0A0A0);
        }

        // 把界面上正在编辑的帽子写在按钮那排右边，省得看按钮置灰猜
        Component editing = Component.translatable("hatmod.tuner.editing",
                Component.translatable("hatmod.tuner.hat." + this.selected.id()));
        graphics.drawCenteredString(this.font, editing, this.width / 2, bottomY() - 12, 0x808080);

        // 「全」页的说明：蓄力/照射由路线决定（框已禁用），前三个框是三条路线的帧伤
        if (all) {
            int noteY = Math.min(bottomY() + 46, Math.max(0, this.height - 10));
            graphics.drawCenteredString(this.font, Component.translatable("hatmod.tuner.allRouteNote"),
                    this.width / 2, noteY, 0xB06060);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
