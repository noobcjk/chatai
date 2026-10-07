package com.ai.client.gui;

import com.ai.client.ai.HttpAiService;
import com.ai.client.config.ChataiConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/**
 * AI 设置界面：填写 Base URL / API Key / 模型 / 系统提示词，并可测试通信。
 */
public class ChatAiSettingsScreen extends Screen {

    private static final int DIM_COLOR = 0x80000000;
    private static final int PANEL_BG = 0xF0121216;
    private static final int PANEL_BORDER = 0xFF3A3A44;
    private static final int TITLE_BAR = 0xFF1B1B22;
    private static final int ACCENT = 0xFF7FB2FF;
    private static final int LABEL_COLOR = 0xFFB8B8C2;
    private static final int HINT_COLOR = 0xFF8A8A94;
    private static final int SUCCESS_COLOR = 0xFF8FD18F;
    private static final int FAIL_COLOR = 0xFFFF6B6B;

    private static final int PANEL_MAX_W = 420;
    private static final int PADDING = 24;
    private static final int TITLE_AREA = 34;
    private static final int ROW_H = 34;
    private static final int ROW_COUNT = 7;
    private static final int FOOTER_AREA = 58;

    private final Screen parent;

    private TextFieldWidget baseUrlField;
    private TextFieldWidget apiKeyField;
    private TextFieldWidget modelField;
    private TextFieldWidget systemPromptField;
    private ButtonWidget saveButton;
    private ButtonWidget testButton;
    private ButtonWidget backButton;
    private ButtonWidget deleteModeButton;
    private ButtonWidget shellModeButton;
    private ButtonWidget shellRulesButton;

    private String status = "";
    private int statusColor = HINT_COLOR;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;

    public ChatAiSettingsScreen(Screen parent) {
        super(Text.translatable("gui.chatai.settings.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        ChataiConfig config = ChataiConfig.get();

        this.panelW = Math.min(this.width - 40, PANEL_MAX_W);
        this.panelH = TITLE_AREA + ROW_COUNT * ROW_H + FOOTER_AREA;
        this.panelX = (this.width - this.panelW) / 2;
        this.panelY = (this.height - this.panelH) / 2;

        int fieldX = this.panelX + PADDING;
        int fieldW = this.panelW - PADDING * 2;
        int firstRowY = this.panelY + TITLE_AREA;

        this.baseUrlField = addField(fieldX, firstRowY, fieldW, config.baseUrl);
        this.apiKeyField = addField(fieldX, firstRowY + ROW_H, fieldW, config.apiKey);
        // API Key 用 * 号显示，避免录屏/截图时泄露
        this.apiKeyField.setRenderTextProvider((text, index) ->
                Text.literal("*".repeat(text.length())).asOrderedText());
        this.modelField = addField(fieldX, firstRowY + ROW_H * 2, fieldW, config.model);
        this.systemPromptField = addField(fieldX, firstRowY + ROW_H * 3, fieldW, config.systemPrompt);

        // 删除消息时是否同步从上下文移除
        this.deleteModeButton = ButtonWidget.builder(deleteModeText(), b -> toggleDeleteMode())
                .dimensions(fieldX, firstRowY + ROW_H * 4 + 11, fieldW, 18).build();
        addDrawableChild(this.deleteModeButton);

        // shell 工具的确认策略
        this.shellModeButton = ButtonWidget.builder(shellModeText(), b -> toggleShellMode())
                .dimensions(fieldX, firstRowY + ROW_H * 5 + 11, fieldW, 18).build();
        addDrawableChild(this.shellModeButton);

        // shell 命令名 / 符号白名单的编辑入口
        this.shellRulesButton = ButtonWidget.builder(Text.translatable("gui.chatai.settings.shellRulesEdit"),
                        b -> MinecraftClient.getInstance().setScreen(new ShellRulesScreen(this)))
                .dimensions(fieldX, firstRowY + ROW_H * 6 + 11, fieldW, 18).build();
        addDrawableChild(this.shellRulesButton);

        int buttonsY = this.panelY + this.panelH - 46;
        int totalW = 70 + 8 + 80 + 8 + 60;
        int buttonX = this.panelX + (this.panelW - totalW) / 2;

        this.saveButton = ButtonWidget.builder(Text.translatable("gui.chatai.settings.save"), b -> save())
                .dimensions(buttonX, buttonsY, 70, 20).build();
        this.testButton = ButtonWidget.builder(Text.translatable("gui.chatai.settings.test"), b -> testConnection())
                .dimensions(buttonX + 78, buttonsY, 80, 20).build();
        this.backButton = ButtonWidget.builder(Text.translatable("gui.chatai.settings.back"), b -> close())
                .dimensions(buttonX + 166, buttonsY, 60, 20).build();

        addDrawableChild(this.saveButton);
        addDrawableChild(this.testButton);
        addDrawableChild(this.backButton);

        setInitialFocus(this.baseUrlField);
    }

    private TextFieldWidget addField(int x, int rowY, int width, String value) {
        TextFieldWidget field = new TextFieldWidget(this.textRenderer, x, rowY + 11, width, 18, Text.empty());
        field.setMaxLength(512);
        field.setText(value == null ? "" : value);
        addDrawableChild(field);
        return field;
    }

    // ------------------------------------------------------------------
    // 行为
    // ------------------------------------------------------------------

    private void save() {
        ChataiConfig config = ChataiConfig.get();
        config.baseUrl = this.baseUrlField.getText().trim();
        config.apiKey = this.apiKeyField.getText().trim();
        config.model = this.modelField.getText().trim();
        config.systemPrompt = this.systemPromptField.getText();
        config.save();

        this.status = Text.translatable("gui.chatai.settings.saved").getString();
        this.statusColor = SUCCESS_COLOR;
    }

    private void toggleDeleteMode() {
        ChataiConfig config = ChataiConfig.get();
        config.deleteRemovesFromContext = !config.deleteRemovesFromContext;
        config.save();
        this.deleteModeButton.setMessage(deleteModeText());
    }

    private static Text deleteModeText() {
        return Text.translatable(ChataiConfig.get().deleteRemovesFromContext
                ? "gui.chatai.settings.deleteSync" : "gui.chatai.settings.deleteUiOnly");
    }

    private void toggleShellMode() {
        ChataiConfig config = ChataiConfig.get();
        config.shellAutoApprove = !config.shellAutoApprove;
        config.save();
        this.shellModeButton.setMessage(shellModeText());
    }

    private static Text shellModeText() {
        return Text.translatable(ChataiConfig.get().shellAutoApprove
                ? "gui.chatai.settings.shellAuto" : "gui.chatai.settings.shellConfirm");
    }

    private void testConnection() {
        // 用输入框里的当前值测试，不要求先保存
        ChataiConfig temp = new ChataiConfig();
        temp.baseUrl = this.baseUrlField.getText().trim();
        temp.apiKey = this.apiKeyField.getText().trim();
        temp.model = this.modelField.getText().trim();
        temp.systemPrompt = this.systemPromptField.getText();

        this.status = Text.translatable("gui.chatai.settings.testing").getString();
        this.statusColor = HINT_COLOR;
        this.testButton.active = false;
        this.testButton.setMessage(Text.translatable("gui.chatai.settings.testing"));

        new HttpAiService(temp).testConnection().whenComplete((reply, error) ->
                MinecraftClient.getInstance().execute(() -> {
                    this.testButton.active = true;
                    this.testButton.setMessage(Text.translatable("gui.chatai.settings.test"));
                    if (error != null) {
                        this.status = Text.translatable("gui.chatai.settings.testFailed").getString()
                                + rootMessage(error);
                        this.statusColor = FAIL_COLOR;
                    } else {
                        String content = reply == null || reply.isBlank()
                                ? Text.translatable("gui.chatai.settings.testBlank").getString()
                                : reply.replace('\n', ' ');
                        this.status = Text.translatable("gui.chatai.settings.testOk", content).getString();
                        this.statusColor = SUCCESS_COLOR;
                    }
                }));
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
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
        drawLabel(context, "gui.chatai.settings.baseUrl", fieldX, firstRowY);
        drawLabel(context, "gui.chatai.settings.apiKey", fieldX, firstRowY + ROW_H);
        drawLabel(context, "gui.chatai.settings.model", fieldX, firstRowY + ROW_H * 2);
        drawLabel(context, "gui.chatai.settings.systemPrompt", fieldX, firstRowY + ROW_H * 3);
        drawLabel(context, "gui.chatai.settings.deleteMode", fieldX, firstRowY + ROW_H * 4);
        drawLabel(context, "gui.chatai.settings.shellMode", fieldX, firstRowY + ROW_H * 5);
        drawLabel(context, "gui.chatai.settings.shellRules", fieldX, firstRowY + ROW_H * 6);

        super.render(context, mouseX, mouseY, delta);

        if (!this.status.isEmpty()) {
            String trimmed = this.textRenderer.trimToWidth(this.status, this.panelW - PADDING * 2);
            context.drawTextWithShadow(this.textRenderer, trimmed,
                    fieldX, this.panelY + this.panelH - 20, this.statusColor);
        }
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // 遮罩已在 render() 中绘制
    }

    private void drawLabel(DrawContext context, String key, int x, int y) {
        context.drawTextWithShadow(this.textRenderer, Text.translatable(key), x, y, LABEL_COLOR);
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
