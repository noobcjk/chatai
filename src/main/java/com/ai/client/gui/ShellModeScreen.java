package com.ai.client.gui;

import com.ai.client.config.ChataiConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** shell 权限设置页：执行前确认，还是自动同意。file 的写操作也走这个开关。 */
public class ShellModeScreen extends SettingPanelScreen {

    private ButtonWidget toggle;

    public ShellModeScreen(Screen parent) {
        super(parent, "gui.chatai.settings.shellMode", 1, 26);
    }

    @Override
    protected void initContent() {
        this.toggle = ButtonWidget.builder(toggleText(), b -> toggle())
                .dimensions(this.fieldX, this.firstRowY + 6, this.fieldW, 18).build();
        addDrawableChild(this.toggle);
    }

    private void toggle() {
        ChataiConfig config = ChataiConfig.get();
        config.shellAutoApprove = !config.shellAutoApprove;
        config.save();
        this.toggle.setMessage(toggleText());
        saved();
    }

    private static Text toggleText() {
        return Text.translatable(ChataiConfig.get().shellAutoApprove
                ? "gui.chatai.settings.shellAuto" : "gui.chatai.settings.shellConfirm");
    }

    @Override
    protected void renderContent(DrawContext context) {
        drawHints(context, this.firstRowY + ROW_H,
                "gui.chatai.settings.shellHint1", "gui.chatai.settings.shellHint2");
    }
}
