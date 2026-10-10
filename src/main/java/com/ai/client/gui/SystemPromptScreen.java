package com.ai.client.gui;

import com.ai.client.config.ChataiConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;

/** 系统提示词编辑页。 */
public class SystemPromptScreen extends SettingPanelScreen {

    private TextFieldWidget field;

    public SystemPromptScreen(Screen parent) {
        super(parent, "gui.chatai.settings.systemPrompt", 1, 22);
    }

    @Override
    protected void initContent() {
        this.field = addField(this.fieldX, this.firstRowY + 6, this.fieldW, ChataiConfig.get().systemPrompt);
        setInitialFocus(this.field);
        addBottomButton("gui.chatai.settings.save", 70, this::save);
    }

    private void save() {
        ChataiConfig config = ChataiConfig.get();
        config.systemPrompt = this.field.getText();
        config.save();
        saved();
    }

    @Override
    protected void renderContent(DrawContext context) {
        drawHints(context, this.firstRowY + ROW_H, "gui.chatai.settings.systemPromptHint");
    }
}
