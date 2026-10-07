package com.ai.client.gui;

import com.ai.client.pet.PetConfig;
import com.ai.client.pet.PetManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * 宠物设置：平板读数模式（累计 token / 余额）、音效、显示开关，
 * 以及几个用来演示扣血和盆的测试按钮。
 */
public class PetSettingsScreen extends Screen {

    private static final int DIM_COLOR = 0x80000000;
    private static final int PANEL_BG = 0xF0121216;
    private static final int PANEL_BORDER = 0xFF3A3A44;
    private static final int TITLE_BAR = 0xFF1B1B22;
    private static final int ACCENT = 0xFF7FB2FF;
    private static final int HINT_COLOR = 0xFF8A8A94;

    private static final int PANEL_MAX_W = 400;
    private static final int PADDING = 14;
    private static final int TITLE_AREA = 30;
    private static final int ROW_H = 26;
    private static final int COLS = 2;
    private static final int ROWS = 4;
    private static final int FOOTER = 30;

    private final Screen parent;
    private final PetConfig config = PetConfig.get();

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;

    private ButtonWidget readoutButton;
    private ButtonWidget soundButton;
    private ButtonWidget showButton;

    public PetSettingsScreen(Screen parent) {
        super(Text.translatable("gui.chatai.pet.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.panelW = Math.min(this.width - 40, PANEL_MAX_W);
        this.panelH = TITLE_AREA + ROWS * ROW_H + FOOTER;
        this.panelX = (this.width - this.panelW) / 2;
        this.panelY = (this.height - this.panelH) / 2;

        int cellW = (this.panelW - PADDING * 2 - 8) / COLS;

        this.readoutButton = add(cell(0, cellW), 0, b -> {
            this.config.readoutTokenMode = !this.config.readoutTokenMode;
            this.config.save();
            refreshLabels();
        });
        this.soundButton = add(cell(1, cellW), 0, b -> {
            this.config.soundEnabled = !this.config.soundEnabled;
            this.config.save();
            refreshLabels();
        });

        this.showButton = add(cell(0, cellW), 1, b -> {
            this.config.showPet = !this.config.showPet;
            this.config.save();
            refreshLabels();
        });
        add(cell(1, cellW), 1, b -> PetManager.get().debugCue())
                .setMessage(Text.translatable("gui.chatai.pet.test.cue"));

        add(cell(0, cellW), 2, b -> PetManager.get().spawnRice())
                .setMessage(Text.translatable("gui.chatai.pet.test.rice"));
        add(cell(1, cellW), 2, b -> PetManager.get().spawnIron())
                .setMessage(Text.translatable("gui.chatai.pet.test.iron"));

        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.chatai.pet.reset"), b -> {
            PetManager.get().resetTokens();
            refreshLabels();
        }).dimensions(this.panelX + PADDING, rowY(3), this.panelW - PADDING * 2, 18).build());

        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.chatai.pet.back"), b -> close())
                .dimensions(this.panelX + this.panelW - PADDING - 60,
                        this.panelY + this.panelH - 24, 60, 18).build());

        refreshLabels();
    }

    private int cell(int col, int cellW) {
        return this.panelX + PADDING + col * (cellW + 8);
    }

    private int rowY(int row) {
        return this.panelY + TITLE_AREA + row * ROW_H + 2;
    }

    private ButtonWidget add(int x, int row, java.util.function.Consumer<ButtonWidget> action) {
        int cellW = (this.panelW - PADDING * 2 - 8) / COLS;
        ButtonWidget button = ButtonWidget.builder(Text.empty(), b -> action.accept(b))
                .dimensions(x, rowY(row), cellW, 18).build();
        addDrawableChild(button);
        return button;
    }

    private void refreshLabels() {
        this.readoutButton.setMessage(Text.translatable(this.config.readoutTokenMode
                ? "gui.chatai.pet.readout.token" : "gui.chatai.pet.readout.balance"));
        this.soundButton.setMessage(Text.translatable(this.config.soundEnabled
                ? "gui.chatai.pet.sound.on" : "gui.chatai.pet.sound.off"));
        this.showButton.setMessage(Text.translatable(this.config.showPet
                ? "gui.chatai.pet.show.on" : "gui.chatai.pet.show.off"));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, DIM_COLOR);

        context.fill(this.panelX + 2, this.panelY + 2, this.panelX + this.panelW + 2,
                this.panelY + this.panelH + 2, 0x50000000);
        context.fill(this.panelX, this.panelY, this.panelX + this.panelW, this.panelY + this.panelH, PANEL_BG);
        drawBorder(context, this.panelX, this.panelY, this.panelX + this.panelW, this.panelY + this.panelH);
        context.fill(this.panelX, this.panelY, this.panelX + this.panelW, this.panelY + 24, TITLE_BAR);
        context.drawTextWithShadow(this.textRenderer, this.title, this.panelX + PADDING / 2, this.panelY + 8, ACCENT);

        super.render(context, mouseX, mouseY, delta);

        String status = Text.translatable("gui.chatai.pet.status",
                PetManager.formatTokens(this.config.totalTokens),
                Integer.toString(this.config.hp),
                Integer.toString(this.config.maxHp),
                Integer.toString(PetConfig.PET_SIZE)).getString();
        context.drawTextWithShadow(this.textRenderer, this.textRenderer.trimToWidth(status, this.panelW - PADDING * 2),
                this.panelX + PADDING, this.panelY + this.panelH - 20, HINT_COLOR);
    }

    @Override
    public void renderBackground(DrawContext context) {
        // 遮罩已在 render() 中绘制
    }

    private static void drawBorder(DrawContext context, int x1, int y1, int x2, int y2) {
        context.fill(x1, y1, x2, y1 + 1, PANEL_BORDER);
        context.fill(x1, y2 - 1, x2, y2, PANEL_BORDER);
        context.fill(x1, y1, x1 + 1, y2, PANEL_BORDER);
        context.fill(x2 - 1, y1, x2, y2, PANEL_BORDER);
    }

    @Override
    public void close() {
        MinecraftClient.getInstance().setScreen(this.parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
