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
 * AI 设置主页面：接口地址 / API Key / 模型，以及进入各子页的入口。
 *
 * <p>系统提示词、删除行为、shell 权限、shell 白名单、文件工作区都拆成了独立子页，
 * 免得一屏塞不下、上下被裁掉。</p>
 */
public class ChatAiSettingsScreen extends SettingPanelScreen {

    /** 主页面的输入框行数（Base URL / API Key / 模型）。 */
    private static final int FIELD_ROWS = 3;
    /** 子页入口排成几列。 */
    private static final int ENTRY_COLS = 3;
    private static final int ENTRY_H = 20;
    private static final int ENTRY_GAP = 4;

    private TextFieldWidget baseUrlField;
    private TextFieldWidget apiKeyField;
    private TextFieldWidget modelField;
    private ButtonWidget testButton;

    public ChatAiSettingsScreen(Screen parent) {
        super(parent, "gui.chatai.settings.title", FIELD_ROWS, 58);
    }

    @Override
    protected void initContent() {
        ChataiConfig config = ChataiConfig.get();

        this.baseUrlField = addField(this.fieldX, this.firstRowY + 11, this.fieldW, config.baseUrl);
        this.apiKeyField = addField(this.fieldX, this.firstRowY + ROW_H + 11, this.fieldW, config.apiKey);
        // API Key 用 * 号显示，避免录屏/截图时泄露
        this.apiKeyField.setRenderTextProvider((text, index) ->
                Text.literal("*".repeat(text.length())).asOrderedText());
        this.modelField = addField(this.fieldX, this.firstRowY + ROW_H * 2 + 11, this.fieldW, config.model);

        addEntryButton(0, 0, "gui.chatai.settings.systemPrompt",
                () -> MinecraftClient.getInstance().setScreen(new SystemPromptScreen(this)));
        addEntryButton(1, 0, "gui.chatai.settings.deleteMode",
                () -> MinecraftClient.getInstance().setScreen(new DeleteModeScreen(this)));
        addEntryButton(2, 0, "gui.chatai.settings.shellMode",
                () -> MinecraftClient.getInstance().setScreen(new ShellModeScreen(this)));
        addEntryButton(0, 1, "gui.chatai.settings.shellRules",
                () -> MinecraftClient.getInstance().setScreen(new ShellRulesScreen(this)));
        addEntryButton(1, 1, "gui.chatai.settings.workspace",
                () -> MinecraftClient.getInstance().setScreen(new WorkspaceScreen(this)));

        addBottomButton("gui.chatai.settings.save", 70, this::save);
        this.testButton = addBottomButton("gui.chatai.settings.test", 80, this::testConnection);

        setInitialFocus(this.baseUrlField);
    }

    /** 在入口按钮网格里放一个按钮，点开对应子页。 */
    private void addEntryButton(int col, int row, String labelKey, Runnable action) {
        int width = (this.fieldW - (ENTRY_COLS - 1) * ENTRY_GAP) / ENTRY_COLS;
        int x = this.fieldX + col * (width + ENTRY_GAP);
        int y = this.firstRowY + FIELD_ROWS * ROW_H + 8 + row * (ENTRY_H + ENTRY_GAP);
        addDrawableChild(ButtonWidget.builder(Text.translatable(labelKey), b -> action.run())
                .dimensions(x, y, width, ENTRY_H).build());
    }

    // ------------------------------------------------------------------
    // 行为
    // ------------------------------------------------------------------

    private void save() {
        ChataiConfig config = ChataiConfig.get();
        config.baseUrl = this.baseUrlField.getText().trim();
        config.apiKey = this.apiKeyField.getText().trim();
        config.model = this.modelField.getText().trim();
        config.save();
        saved();
    }

    private void testConnection() {
        // 用输入框里的当前值测试，不要求先保存
        ChataiConfig temp = new ChataiConfig();
        temp.baseUrl = this.baseUrlField.getText().trim();
        temp.apiKey = this.apiKeyField.getText().trim();
        temp.model = this.modelField.getText().trim();

        setStatus(Text.translatable("gui.chatai.settings.testing").getString(), HINT_COLOR);
        this.testButton.active = false;
        this.testButton.setMessage(Text.translatable("gui.chatai.settings.testing"));

        new HttpAiService(temp).testConnection().whenComplete((reply, error) ->
                MinecraftClient.getInstance().execute(() -> {
                    this.testButton.active = true;
                    this.testButton.setMessage(Text.translatable("gui.chatai.settings.test"));
                    if (error != null) {
                        setStatus(Text.translatable("gui.chatai.settings.testFailed").getString()
                                + rootMessage(error), FAIL_COLOR);
                    } else {
                        String content = reply == null || reply.isBlank()
                                ? Text.translatable("gui.chatai.settings.testBlank").getString()
                                : reply.replace('\n', ' ');
                        setStatus(Text.translatable("gui.chatai.settings.testOk", content).getString(),
                                SUCCESS_COLOR);
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

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    @Override
    protected void renderContent(DrawContext context) {
        drawLabel(context, "gui.chatai.settings.baseUrl", this.fieldX, this.firstRowY);
        drawLabel(context, "gui.chatai.settings.apiKey", this.fieldX, this.firstRowY + ROW_H);
        drawLabel(context, "gui.chatai.settings.model", this.fieldX, this.firstRowY + ROW_H * 2);
    }
}
