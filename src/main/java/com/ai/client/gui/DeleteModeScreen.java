package com.ai.client.gui;

import com.ai.client.config.ChataiConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** 删除消息时的行为设置页：只从界面隐藏，还是连上下文一起删。 */
public class DeleteModeScreen extends SettingPanelScreen {

    private ButtonWidget toggle;

    public DeleteModeScreen(Screen parent) {
        super(parent, "gui.chatai.settings.deleteMode", 1, 26);
    }

    @Override
    protected void initContent() {
        this.toggle = ButtonWidget.builder(toggleText(), b -> toggle())
                .dimensions(this.fieldX, this.firstRowY + 6, this.fieldW, 18).build();
        addDrawableChild(this.toggle);
    }

    private void toggle() {
        ChataiConfig config = ChataiConfig.get();
        config.deleteRemovesFromContext = !config.deleteRemovesFromContext;
        config.save();
        this.toggle.setMessage(toggleText());
        saved();
    }

    private static Text toggleText() {
        return Text.translatable(ChataiConfig.get().deleteRemovesFromContext
                ? "gui.chatai.settings.deleteSync" : "gui.chatai.settings.deleteUiOnly");
    }

    @Override
    protected void renderContent(DrawContext context) {
        drawHints(context, this.firstRowY + ROW_H,
                "gui.chatai.settings.deleteHint1", "gui.chatai.settings.deleteHint2");
    }
}
