package com.ai.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置类子页面的公共外壳：居中面板、标题栏、底部按钮行、底部状态文字。
 *
 * <p>子类只管两件事：在 {@link #initContent()} 里加控件，在 {@link #renderContent} 里画标签。
 * 面板尺寸、返回按钮、状态行都由这里统一处理，行高跟主设置页保持一致，来回切页不会跳。</p>
 */
public abstract class SettingPanelScreen extends Screen {

    /** 单个控件行的标准高度，跟主设置页对齐。 */
    protected static final int ROW_H = 34;
    /** 底部按钮行的高度与间距。 */
    private static final int BUTTON_H = 20;
    private static final int BUTTON_GAP = 8;

    protected static final int ACCENT = 0xFF7FB2FF;
    protected static final int LABEL_COLOR = 0xFFB8B8C2;
    protected static final int HINT_COLOR = 0xFF8A8A94;
    protected static final int SUCCESS_COLOR = 0xFF8FD18F;
    protected static final int FAIL_COLOR = 0xFFFF6B6B;

    private static final int DIM_COLOR = 0x80000000;
    private static final int PANEL_BG = 0xF0121216;
    private static final int PANEL_BORDER = 0xFF3A3A44;
    private static final int TITLE_BAR = 0xFF1B1B22;

    private static final int PANEL_MAX_W = 420;
    private static final int PADDING = 24;
    private static final int TITLE_AREA = 34;
    private static final int FOOTER_AREA = 58;

    private final Screen parent;
    /** 内容区占几行（每行 {@link #ROW_H} 高），用来算面板高度。 */
    private final int rowCount;
    /** 内容区下方额外留白，给提示文字之类的用。 */
    private final int extraHeight;

    private final List<ButtonWidget> bottomButtons = new ArrayList<>();

    /** 布局结果，{@link #initContent()} 里直接用。 */
    protected int panelX;
    protected int panelY;
    protected int panelW;
    protected int panelH;
    protected int fieldX;
    protected int fieldW;
    protected int firstRowY;

    private String status = "";
    private int statusColor = HINT_COLOR;

    /**
     * @param parent      返回时回到哪个页面
     * @param titleKey    标题的翻译键
     * @param rowCount    内容区占几行
     * @param extraHeight 内容区下方额外留白（像素）
     */
    protected SettingPanelScreen(Screen parent, String titleKey, int rowCount, int extraHeight) {
        super(Text.translatable(titleKey));
        this.parent = parent;
        this.rowCount = rowCount;
        this.extraHeight = extraHeight;
    }

    @Override
    protected final void init() {
        // 窗口尺寸变化会重新走一遍 init，按钮列表要清掉重加
        this.bottomButtons.clear();

        this.panelW = Math.min(this.width - 40, PANEL_MAX_W);
        this.panelH = TITLE_AREA + this.rowCount * ROW_H + this.extraHeight + FOOTER_AREA;
        this.panelX = (this.width - this.panelW) / 2;
        // 面板比屏幕高时从上往下放，保证标题栏可见
        this.panelY = Math.max(8, (this.height - this.panelH) / 2);
        this.fieldX = this.panelX + PADDING;
        this.fieldW = this.panelW - PADDING * 2;
        this.firstRowY = this.panelY + TITLE_AREA;

        initContent();

        // 返回键固定加在最后
        this.bottomButtons.add(ButtonWidget.builder(Text.translatable("gui.chatai.settings.back"), b -> close())
                .dimensions(0, 0, 60, BUTTON_H).build());

        int total = -BUTTON_GAP;
        for (ButtonWidget button : this.bottomButtons) {
            total += button.getWidth() + BUTTON_GAP;
        }
        int x = this.panelX + (this.panelW - total) / 2;
        int y = this.panelY + this.panelH - 46;
        for (ButtonWidget button : this.bottomButtons) {
            button.setPosition(x, y);
            addDrawableChild(button);
            x += button.getWidth() + BUTTON_GAP;
        }
    }

    /** 子类在这里加自己的控件；上面那些布局字段已经算好了。 */
    protected abstract void initContent();

    /** 子类在这里画标签等静态内容；先于控件绘制，所以文字在控件下层。 */
    protected void renderContent(DrawContext context) {
    }

    /** 往底部按钮行加一个按钮（返回键由基类自动加在最后）。 */
    protected ButtonWidget addBottomButton(String labelKey, int width, Runnable action) {
        ButtonWidget button = ButtonWidget.builder(Text.translatable(labelKey), b -> action.run())
                .dimensions(0, 0, width, BUTTON_H).build();
        this.bottomButtons.add(button);
        return button;
    }

    /** 按固定行高放一个文本框。 */
    protected TextFieldWidget addField(int x, int y, int width, String value) {
        TextFieldWidget field = new TextFieldWidget(this.textRenderer, x, y, width, 18, Text.empty());
        field.setMaxLength(512);
        field.setText(value == null ? "" : value);
        addDrawableChild(field);
        return field;
    }

    /** 画一行标签。 */
    protected void drawLabel(DrawContext context, String key, int x, int y) {
        context.drawTextWithShadow(this.textRenderer, Text.translatable(key), x, y, LABEL_COLOR);
    }

    /** 从 {@code y} 开始逐行画提示文字，超宽会截断。 */
    protected void drawHints(DrawContext context, int y, String... keys) {
        for (String key : keys) {
            String line = Text.translatable(key).getString();
            context.drawTextWithShadow(this.textRenderer,
                    this.textRenderer.trimToWidth(line, this.fieldW), this.fieldX, y, HINT_COLOR);
            y += 11;
        }
    }

    protected void setStatus(String text, int color) {
        this.status = text == null ? "" : text;
        this.statusColor = color;
    }

    protected void saved() {
        setStatus(Text.translatable("gui.chatai.settings.saved").getString(), SUCCESS_COLOR);
    }

    @Override
    public void close() {
        MinecraftClient.getInstance().setScreen(this.parent);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, DIM_COLOR);

        context.fill(this.panelX + 2, this.panelY + 2, this.panelX + this.panelW + 2, this.panelY + this.panelH + 2, 0x50000000);
        context.fill(this.panelX, this.panelY, this.panelX + this.panelW, this.panelY + this.panelH, PANEL_BG);
        drawBorder(context, this.panelX, this.panelY, this.panelX + this.panelW, this.panelY + this.panelH, PANEL_BORDER);
        context.fill(this.panelX, this.panelY, this.panelX + this.panelW, this.panelY + 26, TITLE_BAR);
        context.drawTextWithShadow(this.textRenderer, this.title, this.panelX + PADDING / 2, this.panelY + 9, ACCENT);

        renderContent(context);

        super.render(context, mouseX, mouseY, delta);

        if (!this.status.isEmpty()) {
            String trimmed = this.textRenderer.trimToWidth(this.status, this.fieldW);
            context.drawTextWithShadow(this.textRenderer, trimmed,
                    this.fieldX, this.panelY + this.panelH - 20, this.statusColor);
        }
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
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
