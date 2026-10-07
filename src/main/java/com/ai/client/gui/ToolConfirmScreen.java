package com.ai.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 危险工具执行的确认框：列出即将执行的命令，玩家点「执行」才真的发出去。
 *
 * <p>无论点按钮还是按 ESC 关闭，都会把结果完成给等待中的 {@link CompletableFuture}，
 * 并回到打开确认框之前的界面。</p>
 */
public class ToolConfirmScreen extends Screen {

    private static final int WIDTH = 300;
    private static final int BUTTON_W = 100;
    private static final int BUTTON_H = 20;

    private final Screen parent;
    private final String toolName;
    private final String detail;
    private final CompletableFuture<Boolean> answer;

    public ToolConfirmScreen(Screen parent, String toolName, String detail,
                             CompletableFuture<Boolean> answer) {
        super(Text.translatable("gui.chatai.tool.confirm.title"));
        this.parent = parent;
        this.toolName = toolName;
        this.detail = detail;
        this.answer = answer;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int buttonY = this.height - 40;
        this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.chatai.tool.confirm.run"),
                        button -> decide(true))
                .dimensions(centerX - BUTTON_W - 4, buttonY, BUTTON_W, BUTTON_H)
                .build());
        this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.chatai.tool.confirm.cancel"),
                        button -> decide(false))
                .dimensions(centerX + 4, buttonY, BUTTON_W, BUTTON_H)
                .build());
    }

    private void decide(boolean allow) {
        this.answer.complete(allow);
        MinecraftClient.getInstance().setScreen(this.parent);
    }

    @Override
    public void close() {
        decide(false);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        int centerX = this.width / 2;
        int left = centerX - WIDTH / 2;
        int top = 40;

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, centerX, top, 0xFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("gui.chatai.tool.confirm.tool", this.toolName),
                centerX, top + 16, 0xFF8A8A94);

        // 命令可能比较长，按面板宽度折行显示
        List<OrderedText> lines = this.textRenderer.wrapLines(Text.literal(this.detail), WIDTH);
        int y = top + 38;
        for (OrderedText line : lines) {
            if (y > this.height - 60) {
                break;
            }
            context.drawCenteredTextWithShadow(this.textRenderer, line, centerX, y, 0xFFFFD27F);
            y += 11;
        }
    }
}
