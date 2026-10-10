package com.ai.client.gui;

import com.ai.client.config.ChataiConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;

/** 文件工作区设置页：file 工具的相对路径基准，同时也是它的读写边界。 */
public class WorkspaceScreen extends SettingPanelScreen {

    private TextFieldWidget field;

    public WorkspaceScreen(Screen parent) {
        super(parent, "gui.chatai.settings.workspace", 1, 26);
    }

    @Override
    protected void initContent() {
        this.field = addField(this.fieldX, this.firstRowY + 6, this.fieldW, ChataiConfig.get().workspace);
        setInitialFocus(this.field);
        addBottomButton("gui.chatai.settings.save", 70, this::save);
    }

    private void save() {
        ChataiConfig config = ChataiConfig.get();
        String value = this.field.getText().trim();
        config.workspace = value.isEmpty() ? "/sdcard" : value;
        config.save();
        saved();
    }

    @Override
    protected void renderContent(DrawContext context) {
        drawHints(context, this.firstRowY + ROW_H,
                "gui.chatai.settings.workspaceHint1", "gui.chatai.settings.workspaceHint2");
    }
}
