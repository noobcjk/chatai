package com.ai.client.gui;

import com.ai.client.config.ChataiConfig;
import com.ai.client.tool.GameTools;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * shell 工具的白名单编辑页：命令名白名单 + 符号白名单。
 *
 * <p>两项都留空即用内置默认；填了就以这里的为准。命令名按空格或逗号分隔，管道每段取第一个词。</p>
 */
public class ShellRulesScreen extends Screen {

    private static final int DIM_COLOR = 0x80000000;
    private static final int PANEL_BG = 0xF0121216;
    private static final int PANEL_BORDER = 0xFF3A3A44;
    private static final int TITLE_BAR = 0xFF1B1B22;
    private static final int ACCENT = 0xFF7FB2FF;
    private static final int LABEL_COLOR = 0xFFB8B8C2;
    private static final int HINT_COLOR = 0xFF8A8A94;
    private static final int SUCCESS_COLOR = 0xFF8FD18F;

    private static final int PANEL_MAX_W = 420;
    private static final int PADDING = 24;
    private static final int TITLE_AREA = 34;
    private static final int ROW_H = 34;
    private static final int ROW_COUNT = 2;
    private static final int HINT_AREA = 28;
    private static final int FOOTER_AREA = 58;

    private final Screen parent;

    private TextFieldWidget commandsField;
    private TextFieldWidget charsField;

    private String status = "";
    private int statusColor = HINT_COLOR;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;

    public ShellRulesScreen(Screen parent) {
        super(Text.translatable("gui.chatai.shellrules.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        ChataiConfig config = ChataiConfig.get();

        this.panelW = Math.min(this.width - 40, PANEL_MAX_W);
        this.panelH = TITLE_AREA + ROW_COUNT * ROW_H + HINT_AREA + FOOTER_AREA;
        this.panelX = (this.width - this.panelW) / 2;
        this.panelY = (this.height - this.panelH) / 2;

        int fieldX = this.panelX + PADDING;
        int fieldW = this.panelW - PADDING * 2;
        int firstRowY = this.panelY + TITLE_AREA;

        this.commandsField = addField(fieldX, firstRowY, fieldW,
                String.join(" ", currentCommands(config)));
        this.charsField = addField(fieldX, firstRowY + ROW_H, fieldW, currentChars(config));

        int buttonsY = this.panelY + this.panelH - 46;
        int totalW = 70 + 8 + 80 + 8 + 60;
        int buttonX = this.panelX + (this.panelW - totalW) / 2;

        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.chatai.shellrules.save"), b -> save())
                .dimensions(buttonX, buttonsY, 70, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.chatai.shellrules.reset"), b -> resetToDefault())
                .dimensions(buttonX + 78, buttonsY, 80, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.chatai.settings.back"), b -> close())
                .dimensions(buttonX + 166, buttonsY, 60, 20).build());

        setInitialFocus(this.commandsField);
    }

    private TextFieldWidget addField(int x, int rowY, int width, String value) {
        TextFieldWidget field = new TextFieldWidget(this.textRenderer, x, rowY + 11, width, 18, Text.empty());
        field.setMaxLength(512);
        field.setText(value == null ? "" : value);
        addDrawableChild(field);
        return field;
    }

    /** 配置为空时界面上显示内置默认，保存时若没改就重新存回空。 */
    private static List<String> currentCommands(ChataiConfig config) {
        List<String> configured = config.shellAllowlist;
        return configured == null || configured.isEmpty() ? GameTools.DEFAULT_SHELL_ALLOWLIST : configured;
    }

    private static String currentChars(ChataiConfig config) {
        String configured = config.shellAllowedChars;
        return configured == null || configured.isBlank() ? GameTools.DEFAULT_SHELL_ALLOWED_CHARS : configured;
    }

    // ------------------------------------------------------------------
    // 行为
    // ------------------------------------------------------------------

    private void save() {
        ChataiConfig config = ChataiConfig.get();

        List<String> commands = parseCommands(this.commandsField.getText());
        // 与默认一致就存空，这样以后调整内置默认值能自动生效
        config.shellAllowlist = commands.equals(GameTools.DEFAULT_SHELL_ALLOWLIST)
                ? new ArrayList<>() : commands;

        String chars = this.charsField.getText().trim();
        config.shellAllowedChars = chars.equals(GameTools.DEFAULT_SHELL_ALLOWED_CHARS) ? "" : chars;
        config.save();

        this.status = Text.translatable("gui.chatai.settings.saved").getString();
        this.statusColor = SUCCESS_COLOR;
    }

    private void resetToDefault() {
        this.commandsField.setText(String.join(" ", GameTools.DEFAULT_SHELL_ALLOWLIST));
        this.charsField.setText(GameTools.DEFAULT_SHELL_ALLOWED_CHARS);
        this.status = "";
    }

    /** 按空格 / 逗号切分，去空、去重，保持输入顺序。 */
    private static List<String> parseCommands(String raw) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (raw != null) {
            for (String part : raw.split("[,\\s]+")) {
                String name = part.trim();
                if (!name.isEmpty()) {
                    set.add(name);
                }
            }
        }
        return new ArrayList<>(set);
    }

    @Override
    public void close() {
        MinecraftClient.getInstance().setScreen(this.parent);
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, DIM_COLOR);

        context.fill(this.panelX + 2, this.panelY + 2, this.panelX + this.panelW + 2, this.panelY + this.panelH + 2, 0x50000000);
        context.fill(this.panelX, this.panelY, this.panelX + this.panelW, this.panelY + this.panelH, PANEL_BG);
        drawBorder(context, this.panelX, this.panelY, this.panelX + this.panelW, this.panelY + this.panelH, PANEL_BORDER);
        context.fill(this.panelX, this.panelY, this.panelX + this.panelW, this.panelY + 26, TITLE_BAR);
        context.drawTextWithShadow(this.textRenderer, this.title, this.panelX + PADDING / 2, this.panelY + 9, ACCENT);

        int fieldX = this.panelX + PADDING;
        int firstRowY = this.panelY + TITLE_AREA;
        context.drawTextWithShadow(this.textRenderer, Text.translatable("gui.chatai.shellrules.commands"),
                fieldX, firstRowY, LABEL_COLOR);
        context.drawTextWithShadow(this.textRenderer, Text.translatable("gui.chatai.shellrules.chars"),
                fieldX, firstRowY + ROW_H, LABEL_COLOR);

        int hintY = firstRowY + ROW_COUNT * ROW_H;
        for (String line : hintLines()) {
            String trimmed = this.textRenderer.trimToWidth(line, this.panelW - PADDING * 2);
            context.drawTextWithShadow(this.textRenderer, trimmed, fieldX, hintY, HINT_COLOR);
            hintY += 11;
        }

        super.render(context, mouseX, mouseY, delta);

        if (!this.status.isEmpty()) {
            String trimmed = this.textRenderer.trimToWidth(this.status, this.panelW - PADDING * 2);
            context.drawTextWithShadow(this.textRenderer, trimmed,
                    fieldX, this.panelY + this.panelH - 20, this.statusColor);
        }
    }

    private static List<String> hintLines() {
        return List.of(
                Text.translatable("gui.chatai.shellrules.hint1").getString(),
                Text.translatable("gui.chatai.shellrules.hint2").getString());
    }

    @Override
    public void renderBackground(DrawContext context) {
        // 遮罩已在 render() 中绘制
    }

    private static void drawBorder(DrawContext context, int x1, int y1, int x2, int y2, int color) {
        context.fill(x1, y1, x2, y1 + 1, color);
        context.fill(x1, y2 - 1, x2, y2, color);
        context.fill(x1, y1, x1 + 1, y2, color);
        context.fill(x2 - 1, y1, x2, y2, color);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
