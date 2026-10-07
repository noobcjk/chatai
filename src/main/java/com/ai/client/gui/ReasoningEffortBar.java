package com.ai.client.gui;

import com.ai.client.config.ChataiConfig;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;

import java.util.List;

/**
 * 对话界面底部的「模型 + 推理强度」控件（移植自 dsh-reasoning-effort）。
 *
 * <ul>
 *     <li>左侧是模型芯片，点击展开模型列表；</li>
 *     <li>右侧是推理强度滑块，档位来自当前模型声明的 {@code efforts}，
 *         拖动或点击轨道后吸附到最近的有效档位；</li>
 *     <li>模型不足两档（未声明）时不画滑块，只显示提示，与 DSH 侧一致。</li>
 * </ul>
 */
public class ReasoningEffortBar extends ClickableWidget {

    private static final int CHIP_MIN_W = 92;
    private static final int CHIP_MAX_W = 148;
    private static final int GAP = 8;
    private static final int TRACK_H = 4;
    private static final int NOTCH_H = 8;
    private static final int HANDLE_W = 5;
    private static final int HANDLE_H = 12;
    private static final int POPUP_ROW_H = 16;
    private static final int POPUP_PAD = 3;
    private static final int POPUP_W = 180;

    private static final int BAR_BG = 0xFF1B1B22;
    private static final int BORDER = 0xFF3A3A44;
    private static final int HOVER_BG = 0xFF2A2A34;
    private static final int TRACK_BG = 0xFF2A2A34;
    private static final int TRACK_FILL = 0xFF7FB2FF;
    private static final int NOTCH = 0x66FFFFFF;
    private static final int HANDLE = 0xFFE6ECFF;
    private static final int HANDLE_EDGE = 0xFF11141A;
    private static final int TEXT = 0xFFB8B8C2;
    private static final int DIM_TEXT = 0xFF8A8A94;
    private static final int ACCENT = 0xFF7FB2FF;
    private static final int POPUP_BG = 0xFF141418;
    private static final int POPUP_HOVER = 0xFF2A2A34;

    private final ChataiConfig config;
    private final TextRenderer textRenderer;

    private List<ChataiConfig.ModelPreset> models = List.of();
    private List<String> levels = List.of();
    /** 吸附后的档位下标；-1 表示当前模型没有档位。 */
    private int index = -1;
    /** 拖动中的连续位置 [0,1]，用于让手柄跟手，松手后吸附。 */
    private float ratio;

    private boolean dragging;
    private boolean popupOpen;
    private int popupHover = -1;

    // 每帧计算出的几何，供命中测试复用
    private int chipX;
    private int chipW;
    private int trackX1;
    private int trackX2;
    private int popupX;
    private int popupY;
    private int popupH;

    public ReasoningEffortBar(TextRenderer textRenderer, ChataiConfig config) {
        super(0, 0, 10, 20, Text.empty());
        this.textRenderer = textRenderer;
        this.config = config;
        refresh();
    }

    /** 重新读取模型 / 档位，并把当前档位收敛到合法值。 */
    public void refresh() {
        this.config.ensureEffortValid();
        this.models = this.config.resolvedModels();
        this.levels = this.config.currentEffortLabels();
        this.index = this.levels.indexOf(this.config.reasoningEffort);
        this.ratio = ratioForIndex(this.index);
    }

    /** 当前模型是否没有可用的推理档位（此时不显示滑块）。 */
    public boolean hasNoLevels() {
        return this.levels.size() < 2;
    }

    /** 模型列表弹层是否展开。 */
    public boolean isPopupOpen() {
        return this.popupOpen;
    }

    /** 键盘 +1 / -1 档。 */
    public void step(int delta) {
        if (this.levels.size() < 2) {
            return;
        }
        int next = Math.max(0, Math.min(this.levels.size() - 1, this.index + delta));
        commit(next);
    }

    private void commit(int newIndex) {
        this.index = newIndex;
        this.ratio = ratioForIndex(newIndex);
        String label = this.levels.get(newIndex);
        if (!label.equals(this.config.reasoningEffort)) {
            this.config.reasoningEffort = label;
            this.config.save();
        }
        refresh();
    }

    private void selectModel(int row) {
        if (row < 0 || row >= this.models.size()) {
            return;
        }
        String id = this.models.get(row).id;
        if (!id.equals(this.config.model)) {
            this.config.model = id;
            this.config.reasoningEffort = "";
            this.config.ensureEffortValid();
            this.config.save();
        }
        refresh();
    }

    private float ratioForIndex(int i) {
        if (this.levels.size() < 2 || i < 0) {
            return 0F;
        }
        return i / (float) (this.levels.size() - 1);
    }

    private int nearestIndex(float r) {
        if (this.levels.size() < 2) {
            return -1;
        }
        int i = Math.round(Math.max(0F, Math.min(1F, r)) * (this.levels.size() - 1));
        return Math.max(0, Math.min(this.levels.size() - 1, i));
    }

    private float ratioForMouse(double mouseX) {
        int width = this.trackX2 - this.trackX1;
        if (width <= 0) {
            return 0F;
        }
        return (float) Math.max(0.0, Math.min(1.0, (mouseX - this.trackX1) / width));
    }

    private String currentModelName() {
        String current = this.config.model == null ? "" : this.config.model;
        for (ChataiConfig.ModelPreset preset : this.models) {
            if (preset.id.equals(current)) {
                return preset.name == null || preset.name.isBlank() ? preset.id : preset.name;
            }
        }
        return current;
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    @Override
    protected void renderButton(DrawContext context, int mouseX, int mouseY, float delta) {
        int x = getX();
        int y = getY();
        int h = getHeight();
        int w = getWidth();
        int textY = y + (h - 8) / 2;

        // 模型芯片固定在右侧，推理强度滑块占据左侧剩余空间
        this.chipW = Math.max(CHIP_MIN_W, Math.min(CHIP_MAX_W, w / 3));
        this.chipX = x + w - this.chipW;

        // —— 推理强度（左）——
        String label = Text.translatable("gui.chatai.effort").getString();
        context.drawTextWithShadow(this.textRenderer, label, x, textY, TEXT);

        boolean hasLevels = this.levels.size() >= 2;
        String value = hasLevels && this.index >= 0
                ? this.levels.get(this.index)
                : Text.translatable("gui.chatai.effort.none").getString();
        int valueW = this.textRenderer.getWidth(value);
        int leftRight = this.chipX - GAP;
        context.drawTextWithShadow(this.textRenderer, value, leftRight - valueW, textY,
                hasLevels ? ACCENT : DIM_TEXT);

        int trackLeft = x + this.textRenderer.getWidth(label) + 8;
        int trackRight = leftRight - valueW - 8;
        this.trackX1 = trackLeft;
        this.trackX2 = trackRight;

        if (hasLevels && trackRight - trackLeft >= 12) {
            int cy = y + h / 2;
            context.fill(trackLeft, cy - TRACK_H / 2, trackRight, cy - TRACK_H / 2 + TRACK_H, TRACK_BG);

            int handleX = trackLeft + Math.round((trackRight - trackLeft) * Math.max(0F, Math.min(1F, this.ratio)));
            context.fill(trackLeft, cy - TRACK_H / 2, handleX, cy - TRACK_H / 2 + TRACK_H, TRACK_FILL);

            for (int i = 0; i < this.levels.size(); i++) {
                int nx = trackLeft + Math.round((trackRight - trackLeft) * ratioForIndex(i));
                context.fill(nx, cy - NOTCH_H / 2, nx + 1, cy + NOTCH_H / 2, NOTCH);
            }

            int hx = handleX - HANDLE_W / 2;
            context.fill(hx, cy - HANDLE_H / 2, hx + HANDLE_W, cy + HANDLE_H / 2, HANDLE);
            drawBorder(context, hx, cy - HANDLE_H / 2, hx + HANDLE_W, cy + HANDLE_H / 2, HANDLE_EDGE);
        }

        // —— 模型芯片（右）——
        boolean chipHover = isHover(mouseX, mouseY, this.chipX, y, this.chipW, h);
        context.fill(this.chipX, y, this.chipX + this.chipW, y + h,
                chipHover || this.popupOpen ? HOVER_BG : BAR_BG);
        drawBorder(context, this.chipX, y, this.chipX + this.chipW, y + h, BORDER);
        String modelName = this.textRenderer.trimToWidth(currentModelName(), this.chipW - 18);
        context.drawTextWithShadow(this.textRenderer, modelName, this.chipX + 5, textY,
                chipHover || this.popupOpen ? ACCENT : TEXT);
        drawArrow(context, this.chipX + this.chipW - 9, y + h / 2, this.popupOpen);
    }

    /**
     * 绘制模型列表弹层。
     *
     * <p>由界面在所有内容（消息 / 控件 / 宠物）之后调用，保证弹层始终在最上层，
     * 不会被 AI 输出文字盖住。</p>
     */
    public void renderOverlay(DrawContext context, int mouseX, int mouseY) {
        this.popupHover = popupRowAt(mouseX, mouseY);
        if (!this.popupOpen) {
            return;
        }
        // 弹层右对齐到右侧的模型芯片
        this.popupX = Math.max(getX(), this.chipX + this.chipW - POPUP_W);
        this.popupH = POPUP_PAD * 2 + this.models.size() * POPUP_ROW_H;
        this.popupY = Math.max(2, getY() - 2 - this.popupH);

        // 先把已排队的文字（消息 / 控件标签）刷到画面，确保弹层背景压在它们之上
        context.draw();

        context.fill(this.popupX + 2, this.popupY + 2, this.popupX + POPUP_W + 2, this.popupY + this.popupH + 2, 0x50000000);
        context.fill(this.popupX, this.popupY, this.popupX + POPUP_W, this.popupY + this.popupH, POPUP_BG);
        drawBorder(context, this.popupX, this.popupY, this.popupX + POPUP_W, this.popupY + this.popupH, BORDER);

        int rowY = this.popupY + POPUP_PAD;
        for (int i = 0; i < this.models.size(); i++) {
            ChataiConfig.ModelPreset preset = this.models.get(i);
            boolean current = preset.id.equals(this.config.model);
            if (i == this.popupHover) {
                context.fill(this.popupX + 1, rowY, this.popupX + POPUP_W - 1, rowY + POPUP_ROW_H, POPUP_HOVER);
            }
            String name = preset.name == null || preset.name.isBlank() ? preset.id : preset.name;
            int color = current ? ACCENT : TEXT;
            context.drawTextWithShadow(this.textRenderer,
                    this.textRenderer.trimToWidth(name, POPUP_W - 26), this.popupX + 6, rowY + (POPUP_ROW_H - 8) / 2, color);
            if (current) {
                // 当前模型标记（避免用字体不一定有的符号）
                context.fill(this.popupX + POPUP_W - 12, rowY + POPUP_ROW_H / 2 - 2,
                        this.popupX + POPUP_W - 8, rowY + POPUP_ROW_H / 2 + 2, ACCENT);
            }
            rowY += POPUP_ROW_H;
        }
    }

    private static void drawArrow(DrawContext context, int cx, int cy, boolean up) {
        int color = 0xFFB8B8C2;
        for (int i = 0; i < 3; i++) {
            int half = up ? i : 2 - i;
            context.fill(cx - half, cy - 2 + i, cx + half + 1, cy - 2 + i + 1, color);
        }
    }

    private static boolean isHover(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private static void drawBorder(DrawContext context, int x1, int y1, int x2, int y2, int color) {
        context.fill(x1, y1, x2, y1 + 1, color);
        context.fill(x1, y2 - 1, x2, y2, color);
        context.fill(x1, y1, x1 + 1, y2, color);
        context.fill(x2 - 1, y1, x2, y2, color);
    }

    // ------------------------------------------------------------------
    // 交互
    // ------------------------------------------------------------------

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        if (super.isMouseOver(mouseX, mouseY)) {
            return true;
        }
        return this.popupOpen && mouseX >= this.popupX && mouseX < this.popupX + POPUP_W
                && mouseY >= this.popupY && mouseY < this.popupY + this.popupH;
    }

    private int popupRowAt(double mouseX, double mouseY) {
        if (!this.popupOpen || mouseX < this.popupX || mouseX >= this.popupX + POPUP_W
                || mouseY < this.popupY || mouseY >= this.popupY + this.popupH) {
            return -1;
        }
        int row = (int) (mouseY - this.popupY - POPUP_PAD) / POPUP_ROW_H;
        return row >= 0 && row < this.models.size() ? row : -1;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || !this.active || !this.visible) {
            return false;
        }
        // 弹层打开时，任何点击都先关掉弹层；点中条目则切换模型
        if (this.popupOpen) {
            selectModel(popupRowAt(mouseX, mouseY));
            this.popupOpen = false;
            return true;
        }
        if (isHover(mouseX, mouseY, this.chipX, getY(), this.chipW, getHeight())) {
            if (this.models.size() > 1) {
                this.popupOpen = true;
            }
            setFocused(true);
            return true;
        }
        if (this.levels.size() >= 2 && this.trackX2 > this.trackX1
                && mouseX >= this.trackX1 - 2 && mouseX <= this.trackX2 + 2
                && mouseY >= getY() && mouseY < getY() + getHeight()) {
            setFocused(true);
            this.dragging = true;
            this.ratio = ratioForMouse(mouseX);
            this.index = nearestIndex(this.ratio);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (this.dragging && button == 0) {
            this.ratio = ratioForMouse(mouseX);
            this.index = nearestIndex(this.ratio);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (this.dragging && button == 0) {
            this.dragging = false;
            int snapped = nearestIndex(this.ratio);
            if (snapped >= 0) {
                commit(snapped);
            } else {
                this.ratio = ratioForIndex(this.index);
            }
            return true;
        }
        return false;
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        builder.put(NarrationPart.TITLE, getMessage());
        if (this.levels.size() >= 2 && this.index >= 0) {
            builder.put(NarrationPart.HINT, Text.literal(this.levels.get(this.index)));
        }
    }
}
