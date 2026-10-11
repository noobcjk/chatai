package com.ai.client.gui;

import com.ai.client.file.SdcardFiles;
import com.ai.client.item.NbtItems;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * 加载 NBT 物品页。
 *
 * <p>内容有两种给法，二选一：填工作区里的文件路径（读文件内容），或者在下面手写 SNBT。
 * 填了路径就以文件为准。读到的内容既能是 json 包装，也能是裸 SNBT —— 判断逻辑在
 * {@link NbtItems#applyText(String)} 里。加载是玩家手动点的，不用再弹确认框。</p>
 */
public class NbtLoadScreen extends SettingPanelScreen {

    /** 结果区最多显示几行，多出来的截掉（详细内容会写进聊天记录的场景里再补）。 */
    private static final int RESULT_LINES = 4;

    private TextFieldWidget pathField;
    private TextFieldWidget contentField;

    /** 加载结果，按行显示；为空时显示提示文字。 */
    private List<String> resultLines = new ArrayList<>();
    private int resultColor = HINT_COLOR;

    public NbtLoadScreen(Screen parent) {
        super(parent, "gui.chatai.nbt.title", 2, 46);
    }

    @Override
    protected void initContent() {
        this.pathField = addField(this.fieldX, this.firstRowY + 11, this.fieldW, "");
        this.pathField.setMaxLength(512);
        this.pathField.setPlaceholder(Text.translatable("gui.chatai.nbt.pathHint"));

        this.contentField = addField(this.fieldX, this.firstRowY + ROW_H + 11, this.fieldW, "");
        // SNBT 可以很长，给足空间；显示是单行横向滚动，跟系统提示词那页一样
        this.contentField.setMaxLength(65536);
        this.contentField.setPlaceholder(Text.translatable("gui.chatai.nbt.contentHint"));

        setInitialFocus(this.contentField);
        addBottomButton("gui.chatai.nbt.load", 70, this::loadNow);
    }

    private void loadNow() {
        String path = this.pathField.getText().trim();
        String content = this.contentField.getText();
        if (!path.isEmpty()) {
            try {
                content = SdcardFiles.readText(path, NbtItems.MAX_TEXT_BYTES);
            } catch (Exception e) {
                show(false, "读取文件失败：" + e.getMessage());
                return;
            }
        }
        if (content == null || content.isBlank()) {
            show(false, "先填文件路径，或者在下面写 NBT 内容。");
            return;
        }
        NbtItems.Result result = NbtItems.applyText(content);
        show(result.ok(), result.text());
    }

    private void show(boolean ok, String text) {
        this.resultColor = ok ? SUCCESS_COLOR : FAIL_COLOR;
        this.resultLines = new ArrayList<>();
        if (text == null) {
            return;
        }
        for (String line : text.split("\n")) {
            if (!line.isBlank()) {
                this.resultLines.add(line);
            }
        }
    }

    @Override
    protected void renderContent(DrawContext context) {
        drawLabel(context, "gui.chatai.nbt.path", this.fieldX, this.firstRowY);
        drawLabel(context, "gui.chatai.nbt.content", this.fieldX, this.firstRowY + ROW_H);

        int y = this.firstRowY + ROW_H * 2;
        if (this.resultLines.isEmpty()) {
            drawHints(context, y, "gui.chatai.nbt.hint1", "gui.chatai.nbt.hint2", "gui.chatai.nbt.hint3");
            return;
        }
        int shown = 0;
        for (String line : this.resultLines) {
            if (shown >= RESULT_LINES) {
                break;
            }
            context.drawTextWithShadow(this.textRenderer,
                    this.textRenderer.trimToWidth(line, this.fieldW), this.fieldX, y, this.resultColor);
            y += 11;
            shown++;
        }
    }
}
