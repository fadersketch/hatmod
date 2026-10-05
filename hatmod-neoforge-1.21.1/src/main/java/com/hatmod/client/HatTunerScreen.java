package com.hatmod.client;

import com.hatmod.HatNetwork;
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

    private static final int ROW_H = 24;
    private static final int FIELD_H = 18;
    private static final int FIELD_W = 200;
    private static final int FIRST_ROW_Y = 64;

    private final EditBox[] fields = new EditBox[FIELD_KEYS.length];
    private final Button[] hatButtons = new Button[HatType.values().length];

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

        // 帽子切换
        HatType[] types = HatType.values();
        int buttonW = 100;
        int totalW = buttonW * types.length + 4 * (types.length - 1);
        int startX = this.width / 2 - totalW / 2;
        for (int i = 0; i < types.length; i++) {
            HatType type = types[i];
            hatButtons[i] = Button.builder(
                            Component.translatable("hatmod.tuner.hat." + type.id()),
                            button -> select(type))
                    .bounds(startX + i * (buttonW + 4), 36, buttonW, 20)
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

        fillFromSelection();
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
        fields[0].setValue(Integer.toString(snapshot.chargeTicks));
        fields[1].setValue(Integer.toString(snapshot.flashTicks));
        fields[2].setValue(format(snapshot.damagePerTick));
        fields[3].setValue(format(snapshot.healthDamageRatio));
        fields[4].setValue(format(snapshot.healthDamageFloor));
        fields[5].setValue(snapshot.damageType);

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
        fields[0].setValue(Integer.toString(this.selected.chargeTicks()));
        fields[1].setValue(Integer.toString(this.selected.flashTicks()));
        fields[2].setValue(format(this.selected.damagePerTick()));
        fields[3].setValue(format(com.hatmod.HatSettings.DEFAULT_HEALTH_DAMAGE_RATIO));
        fields[4].setValue(format(com.hatmod.HatSettings.DEFAULT_HEALTH_DAMAGE_FLOOR));
        fields[5].setValue(com.hatmod.HatSettings.DEFAULT_DAMAGE_TYPE);
    }

    private void save() {
        if (this.minecraft == null || this.minecraft.player == null) {
            return;
        }
        Integer chargeTicks = parseInt(0);
        Integer flashTicks = parseInt(1);
        Float damagePerTick = parseFloat(2);
        Float ratio = parseFloat(3);
        Float floor = parseFloat(4);
        if (chargeTicks == null || flashTicks == null || damagePerTick == null
                || ratio == null || floor == null) {
            complain("hatmod.tuner.badNumber");
            return;
        }
        String damageType = normalizeNumber(fields[5].getValue()).replace('\uFF1A', ':');
        if (damageType.isEmpty()) {
            complain("hatmod.tuner.badType");
            return;
        }

        HatNetwork.sendSettingsUpdate(this.selected, chargeTicks, flashTicks,
                damagePerTick, ratio, floor, damageType, this.ownBgmPriority);
        // 本地立刻回显：界面上「当前值」马上就是刚填的这份，不用等服务端回包。
        // 真正生效的仍然是服务端那份 —— 服务端改完会广播回来覆盖这里；
        // 万一被拒（非创造/非 OP），回包里带的也是服务端的真实值，界面会自己纠回来。
        ClientHatSettings.set(this.selected, chargeTicks, flashTicks,
                damagePerTick, ratio, floor, damageType);
        ClientHatSettings.setOwnBgmPriority(this.ownBgmPriority);
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
        for (int i = 0; i < FIELD_KEYS.length; i++) {
            int y = FIRST_ROW_Y + i * ROW_H + (FIELD_H - this.font.lineHeight) / 2 + 1;
            graphics.drawString(this.font, Component.translatable(FIELD_KEYS[i]), labelX, y, 0xA0A0A0);
        }

        // 把界面上正在编辑的帽子写在按钮那排右边，省得看按钮置灰猜
        Component editing = Component.translatable("hatmod.tuner.editing",
                Component.translatable("hatmod.tuner.hat." + this.selected.id()));
        graphics.drawCenteredString(this.font, editing, this.width / 2, bottomY() - 12, 0x808080);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
