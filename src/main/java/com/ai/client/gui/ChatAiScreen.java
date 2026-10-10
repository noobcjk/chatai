package com.ai.client.gui;

import com.ai.client.ai.ChatMessage;
import com.ai.client.ai.ChatSession;
import com.ai.client.ai.HttpAiService;
import com.ai.client.config.ChataiConfig;
import com.ai.client.config.ChataiHistory;
import com.ai.client.pet.PetManager;
import com.ai.client.pet.PetRenderer;
import com.ai.client.shell.RishShell;
import com.ai.client.skill.SkillRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * AI 对话界面。
 *
 * <ul>
 *     <li>整块屏幕铺一层 75% 透明的黑色作为 GUI 背景，不再画实体面板；</li>
 *     <li>左上角是设置 / 宠物等功能按钮，左下角是宠物立绘；</li>
 *     <li>右侧是聊天列：会话标签、消息区、模型 / 推理强度条、输入框；</li>
 *     <li>回复为流式逐字显示；</li>
 *     <li>消息区支持滚轮 / 拖拽 / 翻页键滚动；</li>
 *     <li>对话历史保存在 config/chatai-history.json。</li>
 * </ul>
 */
public class ChatAiScreen extends Screen {

    /** 聊天列占屏幕宽度的比例（75%）。 */
    private static final float PANEL_RATIO = 0.75F;
    /** 整块屏幕的遮罩：0x40 = 64/255 ≈ 25% 不透明度（即 75% 透明）。 */
    private static final int DIM_COLOR = 0x40000000;

    private static final int ACCENT = 0xFF7FB2FF;
    private static final int PANEL_BORDER = 0xFF3A3A44;
    private static final int USER_COLOR = 0xFF8FD18F;
    private static final int AI_COLOR = 0xFF9FC7FF;
    private static final int HINT_COLOR = 0xFF8A8A94;
    private static final int SCROLLBAR_COLOR = 0xFF66667A;
    private static final int SCROLLBAR_TRACK = 0x40000000;
    private static final int TAB_BG = 0xFF23232C;
    private static final int TAB_ACTIVE_BG = 0xFF2F3340;
    private static final int TAB_HOVER_BG = 0xFF2A2A34;
    private static final int TAB_TEXT = 0xFFB8B8C2;
    private static final int TAB_TEXT_ACTIVE = 0xFFFFFFFF;
    private static final int TAB_CLOSE = 0xFF8A8A94;
    private static final int TAB_CLOSE_HOVER = 0xFFFF8A8A;
    private static final int ACTION_BG = 0xFF23232C;
    private static final int ACTION_BG_HOVER = 0xFF34343F;
    private static final int ACTION_ICON = 0xFFB8B8C2;
    private static final int ACTION_ICON_HOVER = 0xFFFFFFFF;
    private static final int ACTION_BORDER = 0xFF3A3A44;
    /** 「思考」折叠块的头行（可点击）颜色。 */
    private static final int THINK_HEADER_COLOR = 0xFFB9A8FF;
    /** 展开后的思考正文颜色（比正文更暗，避免喧宾夺主）。 */
    private static final int THINK_BODY_COLOR = 0xFF8A8A9A;
    /** 代码块背景条。 */
    private static final int CODE_BG = 0xFF1E1E28;
    /** 代码块文字颜色（比正文亮一点，衬深色底）。 */
    private static final int CODE_TEXT_COLOR = 0xFFD4D4DC;
    /** 代码块右上角「复制」按钮的尺寸与边距。 */
    private static final int CODE_COPY_W = 14;
    private static final int CODE_COPY_H = 12;
    private static final int CODE_COPY_MARGIN = 2;
    /** 模组真实执行过工具留下的记录行：单独配色 + 背景条，和模型自己说的话区分开。 */
    private static final int TOOL_TEXT_COLOR = 0xFFE3C07A;
    private static final int TOOL_BG = 0xFF2A2620;
    /** 摘要头行颜色（比正文淡一点的长绿）。 */
    private static final int SUMMARY_HEADER_COLOR = 0xFF9FD8B0;
    /** 展开后的摘要正文颜色。 */
    private static final int SUMMARY_BODY_COLOR = 0xFF8A9A90;
    /** 摘要头行右侧「删除摘要」按钮的宽度。 */
    private static final int SUMMARY_DELETE_W = 40;
    /** 压缩时保留最近多少条消息不折进摘要。 */
    private static final int COMPRESS_KEEP_RECENT = 6;
    /** 技能面板尺寸。 */
    private static final int SKILL_POPUP_W = 220;
    private static final int SKILL_ROW_H = 16;
    private static final int SKILL_POPUP_PAD = 3;

    private static final int PADDING = 10;
    private static final int TAB_H = 18;
    private static final int TAB_GAP = 3;
    private static final int TAB_MIN_W = 44;
    private static final int TAB_MAX_W = 108;
    private static final int NEW_TAB_W = 20;
    private static final int CLOSE_W = 10;
    private static final int INPUT_H = 20;
    private static final int EFFORT_H = 20;
    private static final int ROW_GAP = 4;
    private static final int SEND_W = 60;
    private static final int SETTINGS_W = 40;
    private static final int SETTINGS_H = 16;
    private static final int SCROLLBAR_W = 3;
    /** 消息之间的空隙，悬浮操作按钮就放在这条空隙里。 */
    private static final int MESSAGE_GAP = 16;
    private static final int MSG_BTN_W = 14;
    private static final int MSG_BTN_H = 12;
    private static final int MSG_BTN_GAP = 2;
    private static final int MSG_BTN_PAD = 2;
    /** 一格滚轮滚动的像素。 */
    private static final double WHEEL_STEP = 14.0;

    private static final long OPEN_ANIM_MS = 220L;

    private final HttpAiService aiService = new HttpAiService(ChataiConfig.get());
    /** 全部对话会话（至少一个）。 */
    private final List<ChatSession> sessions = new ArrayList<>();
    private int activeSession;
    /** 流式回复所属的会话；切换会话时不会串台。 */
    private ChatSession streamingSession;

    /** 上一帧的时间戳，用来算宠物动画的 dt。 */
    private long lastFrameNanos;

    private TextFieldWidget inputField;
    private ButtonWidget sendButton;
    /** 等待回复时替换发送按钮的「停止」按钮。 */
    private ButtonWidget stopButton;
    private ButtonWidget settingsButton;
    private ButtonWidget petButton;
    /** 手动把旧消息折成摘要。 */
    private ButtonWidget compressButton;
    /** 打开技能面板。 */
    private ButtonWidget skillButton;
    private ReasoningEffortBar effortBar;

    private long openTime;
    private boolean historyLoaded;

    /** 正在流式接收的回复；null 表示当前没有流式内容。 */
    private StringBuilder streamingBuffer;
    /** 正在流式接收的思考内容；null 表示没有。 */
    private StringBuilder streamingReasoning;
    private boolean waiting;
    /** 当前流式请求的句柄；点「停止」时 cancel(true)。 */
    private CompletableFuture<String> streamFuture;
    /** 是否为用户手动停止，用于把收尾提示从「请求失败」改成「已停止」。 */
    private boolean streamCancelled;
    /** 正在静默请求自动命名的会话 id，避免重复发起。 */
    private final Set<String> namingInFlight = new HashSet<>();
    /** 正在等待压缩结果的会话 id，避免重复发起。 */
    private final Set<String> compressInFlight = new HashSet<>();
    /** 摘要块是否展开显示正文。 */
    private boolean summaryExpanded;
    /** 每帧算出的摘要头行屏幕范围，用于点击展开 / 收起。 */
    private int[] summaryHeaderBounds;
    /** 摘要头行右侧「删除摘要」按钮的屏幕矩形；没画时为 null。 */
    private int[] summaryDeleteBounds;
    /** 鼠标是否压在「删除摘要」按钮上（用于提示）。 */
    private boolean hoverSummaryDelete;

    /** 技能面板是否展开。 */
    private boolean skillPopupOpen;
    private int skillPopupHover = -1;
    // 技能面板几何，每帧重算
    private int skillPopupX;
    private int skillPopupY;
    private int skillPopupW;
    private int skillPopupH;

    /** 已手动展开思考块的消息（按对象身份记录）。 */
    private final Set<ChatMessage> expandedThinking =
            Collections.newSetFromMap(new IdentityHashMap<>());
    /** 每帧算出的「思考」头行屏幕范围：消息下标 → [y1, y2)，用于点击命中。 */
    private final Map<Integer, int[]> thinkingHeaderBounds = new HashMap<>();

    /** 是否自动跟随最新消息（滚到底部）。 */
    private boolean followOutput = true;
    private int scroll;
    private int contentHeight;

    // 消息视口矩形，每帧由 updateLayout() 更新
    private int viewX1;
    private int viewY1;
    private int viewX2;
    private int viewY2;

    // 滚动条位置（不可滚动时 barVisible = false）
    private boolean barVisible;
    private int barTrackX;
    private int barY;
    private int barH;

    // 拖拽状态
    private boolean dragging;
    private double dragStartY;
    private int dragStartScroll;
    private double dragPixelsPerScroll = 1.0;

    // 会话标签栏（每帧 updateLayout 重算）
    private int tabsY;
    private final List<TabRect> tabRects = new ArrayList<>();
    private int newTabX;
    private int newTabW;

    /** 标签矩形（左闭右开）。 */
    private record TabRect(int x1, int x2) {
    }

    /** 消息悬浮操作。 */
    private enum Action {
        COPY,
        DELETE,
        REGENERATE
    }

    /** 每条消息在屏幕上的纵向范围 [top, bottom)（含下方操作条空隙），用于悬浮按钮命中测试。 */
    private final List<int[]> msgBounds = new ArrayList<>();
    private int hoverMsg = -1;
    private int hoverBtn = -1;
    /** 当前悬停的操作按钮，用于画提示。 */
    private Action hoverAction;
    /** buildLines 时记录的代码块行区间，用来反推它的屏幕矩形。 */
    private final List<CodeBlockRange> codeBlockRanges = new ArrayList<>();
    /** 本帧各代码块「复制」按钮的屏幕矩形与要复制的文本。 */
    private final List<CodeCopyButton> codeCopyButtons = new ArrayList<>();
    /** 悬停中的代码复制按钮下标；-1 表示没有。 */
    private int hoverCodeCopy = -1;

    public ChatAiScreen() {
        super(Text.translatable("gui.chatai.title"));
    }

    @Override
    protected void init() {
        this.openTime = System.currentTimeMillis();
        this.lastFrameNanos = 0L;
        // 每轮对话结束后把 token 用量喂给宠物（用于扣血统计）
        this.aiService.setUsageListener(tokens -> PetManager.get().onTokensUsed(tokens));

        if (!this.historyLoaded) {
            this.historyLoaded = true;
            this.sessions.addAll(ChataiHistory.load());
            if (this.sessions.isEmpty()) {
                ChatSession session = new ChatSession();
                session.messages.add(ChatMessage.assistant("你好，我是 AI 助手。输入内容后点击“发送”。"));
                this.sessions.add(session);
            }
            this.activeSession = 0;
        }

        this.inputField = new TextFieldWidget(this.textRenderer, 0, 0, 100, INPUT_H,
                Text.translatable("gui.chatai.input"));
        this.inputField.setMaxLength(2000);
        this.inputField.setPlaceholder(Text.translatable("gui.chatai.input.hint"));
        this.inputField.setChangedListener(text -> refreshSendButton());
        this.addDrawableChild(this.inputField);

        this.sendButton = ButtonWidget.builder(Text.translatable("gui.chatai.send"), button -> sendMessage())
                .dimensions(0, 0, SEND_W, INPUT_H)
                .build();
        this.addDrawableChild(this.sendButton);

        this.stopButton = ButtonWidget.builder(Text.translatable("gui.chatai.stop"), button -> stopStream())
                .dimensions(0, 0, SEND_W, INPUT_H)
                .build();
        this.addDrawableChild(this.stopButton);

        this.settingsButton = ButtonWidget.builder(Text.translatable("gui.chatai.settings"),
                        button -> MinecraftClient.getInstance().setScreen(new ChatAiSettingsScreen(this)))
                .dimensions(0, 0, SETTINGS_W, SETTINGS_H)
                .build();
        this.addDrawableChild(this.settingsButton);

        this.petButton = ButtonWidget.builder(Text.translatable("gui.chatai.pet"),
                        button -> MinecraftClient.getInstance().setScreen(new PetSettingsScreen(this)))
                .dimensions(0, 0, SETTINGS_W, SETTINGS_H)
                .build();
        this.addDrawableChild(this.petButton);

        this.compressButton = ButtonWidget.builder(Text.translatable("gui.chatai.compress"),
                        button -> compressNow())
                .dimensions(0, 0, SETTINGS_W, SETTINGS_H)
                .build();
        this.addDrawableChild(this.compressButton);

        this.skillButton = ButtonWidget.builder(Text.translatable("gui.chatai.skills"),
                        button -> toggleSkillPopup())
                .dimensions(0, 0, SETTINGS_W, SETTINGS_H)
                .build();
        this.addDrawableChild(this.skillButton);

        // 底部「模型 + 推理强度」控件，最后加入以便弹层绘制在其他控件之上
        this.effortBar = new ReasoningEffortBar(this.textRenderer, ChataiConfig.get());
        this.addDrawableChild(this.effortBar);

        this.setInitialFocus(this.inputField);
        updateLayout();
    }

    // ------------------------------------------------------------------
    // 布局与动画
    // ------------------------------------------------------------------

    /** 缓动后的展开进度，取值 [0, 1]。 */
    private float progress() {
        long elapsed = System.currentTimeMillis() - this.openTime;
        float t = Math.min(1F, elapsed / (float) OPEN_ANIM_MS);
        // ease-out cubic：开始快、结束慢
        return 1F - (1F - t) * (1F - t) * (1F - t);
    }

    private int finalWidth() {
        return Math.round(this.width * PANEL_RATIO);
    }

    private int finalHeight() {
        // 高度拉满：只保留上下各 PADDING 的边距
        return Math.max(1, this.height - PADDING * 2);
    }

    private void updateLayout() {
        float p = progress();

        // —— 聊天列：贴在屏幕右侧，打开时从右边缘滑入 ——
        int finalW = finalWidth();
        int finalX = this.width - finalW;
        int ax = finalX + Math.round((1F - p) * (finalW + PADDING));

        int top = PADDING;
        int bottom = top + finalHeight();
        int inputY = bottom - INPUT_H;
        int effortY = inputY - ROW_GAP - EFFORT_H;

        // —— 左上角的功能按钮 ——
        this.settingsButton.setPosition(PADDING, PADDING);
        this.petButton.setPosition(PADDING + SETTINGS_W + 4, PADDING);
        this.compressButton.setPosition(PADDING + (SETTINGS_W + 4) * 2, PADDING);
        // 「技能」挪到「设置」正下方：并排第 4 个会压到右侧会话标题栏
        this.skillButton.setPosition(PADDING, PADDING + SETTINGS_H + 4);

        // —— 聊天列底部：输入框 / 发送 ——
        this.inputField.setX(ax);
        this.inputField.setY(inputY);
        this.inputField.setWidth(finalW - SEND_W - 6);
        this.sendButton.setPosition(ax + finalW - SEND_W, inputY);
        this.stopButton.setPosition(ax + finalW - SEND_W, inputY);

        // —— 底部模型 / 推理强度控件 ——
        this.effortBar.setX(ax);
        this.effortBar.setY(effortY);
        this.effortBar.setWidth(finalW);

        // —— 会话标签栏（聊天列顶部）——
        this.tabsY = top;
        layoutTabs(ax, finalW);

        // —— 消息视口 ——
        this.viewX1 = ax;
        this.viewX2 = ax + finalW - SCROLLBAR_W - 2;
        this.viewY1 = this.tabsY + TAB_H + 6;
        this.viewY2 = effortY - ROW_GAP;

        // 面板滑到位之后再显示控件，避免半路闪现
        boolean ready = p >= 0.5F;
        this.inputField.visible = ready;
        this.inputField.setEditable(ready && !this.waiting);
        this.sendButton.visible = ready && !this.waiting;
        this.stopButton.visible = ready && this.waiting;
        this.effortBar.visible = ready;
        this.effortBar.active = ready;
        updateCompressButton(ready);
        this.skillButton.visible = ready;
        this.skillButton.active = ready;
        refreshSendButton();
    }

    /** 同步「压缩」按钮的可用状态与文案：压缩中显示进度、没有可压内容时置灰。 */
    private void updateCompressButton(boolean ready) {
        boolean running = this.compressInFlight.contains(active().id);
        this.compressButton.visible = ready;
        this.compressButton.active = ready && !this.waiting && !running && canCompress();
        this.compressButton.setMessage(Text.translatable(
                running ? "gui.chatai.compress.running" : "gui.chatai.compress"));
    }

    /** 当前会话是否还有可折进摘要的旧消息。 */
    private boolean canCompress() {
        return canCompress(active());
    }

    /** 指定会话是否还有可折进摘要的旧消息。 */
    private static boolean canCompress(ChatSession session) {
        return compressEnd(session) > session.summaryCount;
    }

    /** 压缩后保留的边界：摘要覆盖到 messages 里的第几条（不含）。 */
    private static int compressEnd(ChatSession session) {
        return Math.max(session.summaryCount, session.messages.size() - COMPRESS_KEEP_RECENT);
    }

    private void refreshSendButton() {
        this.sendButton.active = !this.waiting
                && this.inputField != null
                && !this.inputField.getText().isBlank();
    }

    // ------------------------------------------------------------------
    // 多会话
    // ------------------------------------------------------------------

    /** 等宽排布标签，并算好“+”按钮的位置。 */
    private void layoutTabs(int left, int panelW) {
        this.tabRects.clear();
        int n = this.sessions.size();
        this.newTabW = NEW_TAB_W;

        int inner = panelW - this.newTabW - TAB_GAP;
        int tabW = n <= 0 ? TAB_MAX_W : (inner - TAB_GAP * (n - 1)) / n;
        tabW = Math.max(TAB_MIN_W, Math.min(TAB_MAX_W, tabW));

        int x = left;
        for (int i = 0; i < n; i++) {
            this.tabRects.add(new TabRect(x, x + tabW));
            x += tabW + TAB_GAP;
        }
        this.newTabX = x;
    }

    private ChatSession active() {
        return this.sessions.get(this.activeSession);
    }

    private List<ChatMessage> messages() {
        return active().messages;
    }

    /** 标签上显示的标题：优先用存下来的，否则用第一条用户消息。 */
    private static String sessionTitle(ChatSession session) {
        if (session.title != null && !session.title.isBlank()) {
            return session.title;
        }
        for (ChatMessage message : session.messages) {
            if (message.role() == ChatMessage.Role.USER) {
                return deriveTitle(message.content());
            }
        }
        return Text.translatable("gui.chatai.newchat").getString();
    }

    private static String deriveTitle(String text) {
        String oneLine = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (oneLine.isEmpty()) {
            return Text.translatable("gui.chatai.newchat").getString();
        }
        return oneLine.length() > 14 ? oneLine.substring(0, 14) + "…" : oneLine;
    }

    private void switchSession(int index) {
        if (index < 0 || index >= this.sessions.size() || index == this.activeSession) {
            return;
        }
        this.activeSession = index;
        this.followOutput = true;
        this.scroll = 0;
    }

    private void newSession() {
        this.sessions.add(new ChatSession());
        this.activeSession = this.sessions.size() - 1;
        this.followOutput = true;
        this.scroll = 0;
        ChataiHistory.save(this.sessions);
    }

    private void closeSession(int index) {
        // 流式回复期间不允许关闭，避免回复丢失 / 串台
        if (this.waiting || this.sessions.size() <= 1 || index < 0 || index >= this.sessions.size()) {
            return;
        }
        this.sessions.remove(index);
        if (this.activeSession >= this.sessions.size()) {
            this.activeSession = this.sessions.size() - 1;
        }
        this.followOutput = true;
        this.scroll = 0;
        ChataiHistory.save(this.sessions);
    }

    private int viewportWidth() {
        return this.viewX2 - this.viewX1;
    }

    private int viewportHeight() {
        return this.viewY2 - this.viewY1;
    }

    private int maxScroll() {
        return Math.max(0, this.contentHeight - Math.max(0, viewportHeight()));
    }

    /** 滚动到指定位置；滚到底部时恢复自动跟随。 */
    private void scrollTo(double value) {
        int max = maxScroll();
        this.scroll = (int) Math.max(0, Math.min(max, value));
        this.followOutput = this.scroll >= max;
    }

    private void scrollBy(double delta) {
        scrollTo(this.scroll + delta);
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // 1) 整块屏幕的遮罩（0x40 ≈ 25% 不透明度，即 75% 透明）
        context.fill(0, 0, this.width, this.height, DIM_COLOR);

        // 2) 右侧聊天列（标签栏 + 消息区）
        updateLayout();
        if (progress() >= 0.5F) {
            renderSessionTabs(context, mouseX, mouseY);
            renderMessages(context, mouseX, mouseY);
        }

        // 3) 控件（左上角按钮 / 输入框 / 模型条）
        super.render(context, mouseX, mouseY, delta);

        // 4) 先手动 flush，再画立绘。fill / 文字 / 控件先按 RenderLayer 入队，立绘的
        //    textured 图层在它们之后才创建，flush 后自然叠在最上层。放在控件之后
        //    flush，立绘就落在所有 GUI 之上，不会被遮罩压暗或盖住。
        context.draw();

        // 5) 左下角的余额宠物（最后绘制，确保不会被任何 GUI 覆盖）
        long now = System.nanoTime();
        double dt = this.lastFrameNanos == 0L ? 0.0 : (now - this.lastFrameNanos) / 1.0E9;
        this.lastFrameNanos = now;
        PetManager pet = PetManager.get();
        pet.update(dt, this.width, this.height);
        PetRenderer.render(context, this.textRenderer, pet);

        // 6) 模型没有声明推理档位时，悬停给出配置指引
        if (this.effortBar != null && this.effortBar.visible && this.effortBar.hasNoLevels()
                && !this.effortBar.isPopupOpen() && this.effortBar.isMouseOver(mouseX, mouseY)) {
            context.drawTooltip(this.textRenderer, Text.translatable("gui.chatai.effort.hint"), mouseX, mouseY);
        }

        // 6) 模型列表弹层最后绘制：确保盖在消息 / 控件 / 宠物之上
        if (this.effortBar != null && this.effortBar.visible) {
            this.effortBar.renderOverlay(context, mouseX, mouseY);
        }

        // 6.1) 技能面板弹层
        if (this.skillPopupOpen) {
            renderSkillPopup(context, mouseX, mouseY);
            if (this.skillPopupHover >= 0 && this.skillPopupHover < SkillRegistry.skills().size()) {
                context.drawTooltip(this.textRenderer,
                        Text.literal(SkillRegistry.skills().get(this.skillPopupHover).description()),
                        mouseX, mouseY);
            }
        }

        // 7) 消息操作按钮的提示
        if (this.hoverAction != null) {
            context.drawTooltip(this.textRenderer,
                    Text.translatable(actionKey(this.hoverAction)), mouseX, mouseY);
        }

        // 8) 代码块复制按钮的提示
        if (this.hoverCodeCopy >= 0) {
            context.drawTooltip(this.textRenderer,
                    Text.translatable("gui.chatai.action.copycode"), mouseX, mouseY);
        }
    }

    private static String actionKey(Action action) {
        return switch (action) {
            case COPY -> "gui.chatai.action.copy";
            case DELETE -> "gui.chatai.action.delete";
            case REGENERATE -> "gui.chatai.action.regenerate";
        };
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // 遮罩已在 render() 里画过，这里覆盖为空，避免原版背景叠加导致超过 50%。
    }

    /** 会话标签栏：等宽标签 + 末尾的「+」新建按钮。 */
    private void renderSessionTabs(DrawContext context, int mouseX, int mouseY) {
        int textPad = 5;
        for (int i = 0; i < this.tabRects.size(); i++) {
            TabRect rect = this.tabRects.get(i);
            boolean activeTab = i == this.activeSession;
            boolean hover = mouseX >= rect.x1() && mouseX < rect.x2()
                    && mouseY >= this.tabsY && mouseY < this.tabsY + TAB_H;
            boolean showClose = this.sessions.size() > 1;
            boolean closeHover = hover && showClose && !this.waiting
                    && mouseX >= rect.x2() - CLOSE_W - 3;

            context.fill(rect.x1(), this.tabsY, rect.x2(), this.tabsY + TAB_H,
                    activeTab ? TAB_ACTIVE_BG : (hover ? TAB_HOVER_BG : TAB_BG));
            if (activeTab) {
                context.fill(rect.x1(), this.tabsY + TAB_H - 2, rect.x2(), this.tabsY + TAB_H, ACCENT);
            }

            int reserved = showClose ? CLOSE_W + 5 : 0;
            String title = this.textRenderer.trimToWidth(sessionTitle(this.sessions.get(i)),
                    rect.x2() - rect.x1() - textPad - reserved);
            context.drawTextWithShadow(this.textRenderer, title, rect.x1() + textPad,
                    this.tabsY + (TAB_H - 8) / 2, activeTab ? TAB_TEXT_ACTIVE : TAB_TEXT);

            if (showClose) {
                drawCloseMark(context, rect.x2() - CLOSE_W - 2, this.tabsY + TAB_H / 2, closeHover);
            }
        }

        // 「+」新建会话
        boolean newHover = mouseX >= this.newTabX && mouseX < this.newTabX + this.newTabW
                && mouseY >= this.tabsY && mouseY < this.tabsY + TAB_H;
        context.fill(this.newTabX, this.tabsY, this.newTabX + this.newTabW, this.tabsY + TAB_H,
                newHover ? TAB_HOVER_BG : TAB_BG);
        drawBorder(context, this.newTabX, this.tabsY, this.newTabX + this.newTabW, this.tabsY + TAB_H, PANEL_BORDER);
        int cx = this.newTabX + this.newTabW / 2;
        int cy = this.tabsY + TAB_H / 2;
        int plus = newHover ? ACCENT : TAB_TEXT;
        context.fill(cx - 4, cy, cx + 5, cy + 1, plus);
        context.fill(cx, cy - 4, cx + 1, cy + 5, plus);
    }

    private static void drawCloseMark(DrawContext context, int x, int cy, boolean hover) {
        int color = hover ? TAB_CLOSE_HOVER : TAB_CLOSE;
        for (int i = 0; i < 7; i++) {
            context.fill(x + i, cy - 3 + i, x + i + 1, cy - 2 + i, color);
            context.fill(x + 6 - i, cy - 3 + i, x + 7 - i, cy - 2 + i, color);
        }
    }

    private void renderMessages(DrawContext context, int mouseX, int mouseY) {
        int viewW = viewportWidth();
        int viewH = viewportHeight();
        if (viewW <= 0 || viewH <= 0) {
            this.contentHeight = 0;
            this.barVisible = false;
            this.msgBounds.clear();
            this.thinkingHeaderBounds.clear();
            this.codeCopyButtons.clear();
            this.summaryHeaderBounds = null;
            this.summaryDeleteBounds = null;
            this.hoverSummaryDelete = false;
            this.hoverMsg = -1;
            this.hoverBtn = -1;
            this.hoverCodeCopy = -1;
            return;
        }

        List<Line> lines = buildLines(viewW);
        // 每行顶部的累计高度，用来把代码块的行区间换算成屏幕 Y
        int[] lineTops = new int[lines.size() + 1];
        int total = 0;
        for (int i = 0; i < lines.size(); i++) {
            lineTops[i] = total;
            total += lines.get(i).height();
        }
        lineTops[lines.size()] = total;
        this.contentHeight = total;

        int maxScroll = Math.max(0, total - viewH);
        if (this.followOutput) {
            this.scroll = maxScroll;
        }
        this.scroll = Math.max(0, Math.min(maxScroll, this.scroll));

        context.enableScissor(this.viewX1, this.viewY1, this.viewX2, this.viewY2);
        this.summaryHeaderBounds = null;
        this.summaryDeleteBounds = null;
        this.hoverSummaryDelete = false;
        int y = this.viewY1 - this.scroll;
        for (Line line : lines) {
            int index = line.messageIndex();
            if (index >= 0 && index < this.msgBounds.size()) {
                int[] bounds = this.msgBounds.get(index);
                bounds[0] = Math.min(bounds[0], y);
                bounds[1] = Math.max(bounds[1], y + line.height());
                if (line.kind() == LineKind.THINK_HEADER) {
                    // 记下头行的屏幕范围，供点击展开 / 收起命中
                    this.thinkingHeaderBounds.put(index, new int[]{y, y + line.height()});
                }
            }
            if (line.kind() == LineKind.SUMMARY_HEADER) {
                this.summaryHeaderBounds = new int[]{y, y + line.height()};
            }
            if (y + line.height() >= this.viewY1 && y <= this.viewY2) {
                if (line.bgColor() != 0) {
                    // 代码块：先铺一条与行等高的深色背景条
                    context.fill(this.viewX1, y, this.viewX2, y + line.height(), line.bgColor());
                }
                if (line.kind() == LineKind.CODE || line.kind() == LineKind.TOOL) {
                    // 深色底上不描阴影，保持清晰
                    context.drawText(this.textRenderer, line.text(), this.viewX1, y, line.color(), false);
                } else {
                    context.drawTextWithShadow(this.textRenderer, line.text(), this.viewX1, y, line.color());
                }
            }
            y += line.height();
        }

        // 摘要头行右侧的「删除摘要」按钮
        if (this.summaryHeaderBounds != null) {
            int hy1 = this.summaryHeaderBounds[0];
            int hy2 = this.summaryHeaderBounds[1];
            if (hy2 > this.viewY1 && hy1 < this.viewY2) {
                int x2 = this.viewX2 - 2;
                int x1 = x2 - SUMMARY_DELETE_W;
                int by1 = hy1 + 1;
                int by2 = hy2 - 1;
                boolean hover = mouseX >= x1 && mouseX < x2 && mouseY >= by1 && mouseY < by2;
                this.hoverSummaryDelete = hover;
                this.summaryDeleteBounds = new int[]{x1, by1, x2, by2};
                context.fill(x1, by1, x2, by2, hover ? ACTION_BG_HOVER : ACTION_BG);
                drawBorder(context, x1, by1, x2, by2, ACTION_BORDER);
                String label = Text.translatable("gui.chatai.summary.delete").getString();
                int tw = this.textRenderer.getWidth(label);
                context.drawTextWithShadow(this.textRenderer, label,
                        x1 + (x2 - x1 - tw) / 2, by1 + (by2 - by1 - 8) / 2,
                        hover ? ACTION_ICON_HOVER : ACTION_ICON);
            }
        }

        // 代码块右上角的「复制」按钮：鼠标压在某个代码块上时才显示
        this.codeCopyButtons.clear();
        this.hoverCodeCopy = -1;
        for (CodeBlockRange range : this.codeBlockRanges) {
            int blockTop = this.viewY1 - this.scroll + lineTops[range.startLine()];
            int blockBottom = this.viewY1 - this.scroll + lineTops[range.endLine()];
            if (mouseX < this.viewX1 || mouseX > this.viewX2
                    || mouseY < blockTop || mouseY >= blockBottom) {
                continue;
            }
            int x2 = this.viewX2 - CODE_COPY_MARGIN;
            int x1 = x2 - CODE_COPY_W;
            int y1 = blockTop + CODE_COPY_MARGIN;
            int y2 = y1 + CODE_COPY_H;
            boolean hover = mouseX >= x1 && mouseX < x2 && mouseY >= y1 && mouseY < y2;
            if (hover) {
                this.hoverCodeCopy = this.codeCopyButtons.size();
            }
            this.codeCopyButtons.add(new CodeCopyButton(x1, y1, x2, y2, range.code()));
            context.fill(x1, y1, x2, y2, hover ? ACTION_BG_HOVER : ACTION_BG);
            drawBorder(context, x1, y1, x2, y2, ACTION_BORDER);
            drawCopyIcon(context, x1 + (CODE_COPY_W - 8) / 2, y1 + (CODE_COPY_H - 8) / 2,
                    hover ? ACTION_ICON_HOVER : ACTION_ICON);
        }

        // 悬浮到某条消息时，在它下方的空隙里画操作按钮
        boolean popupOpen = (this.effortBar != null && this.effortBar.isPopupOpen()) || this.skillPopupOpen;
        this.hoverMsg = popupOpen ? -1 : messageAt(mouseX, mouseY);
        this.hoverBtn = -1;
        this.hoverAction = null;
        if (this.hoverMsg >= 0) {
            List<Action> actions = actionsFor(this.hoverMsg);
            int stripY = actionStripY(this.hoverMsg);
            if (mouseY >= stripY && mouseY < stripY + MSG_BTN_H) {
                int rel = (int) (mouseX - this.viewX1);
                int step = MSG_BTN_W + MSG_BTN_GAP;
                int index = rel / step;
                if (index >= 0 && index < actions.size() && rel % step < MSG_BTN_W) {
                    this.hoverBtn = index;
                    this.hoverAction = actions.get(index);
                }
            }
            renderMessageActions(context, actions, stripY);
        }
        context.disableScissor();

        // 滚动条（画在视口右侧，不参与裁剪）
        if (maxScroll > 0) {
            this.barVisible = true;
            this.barTrackX = this.viewX2 + 2;
            this.barH = Math.max(16, viewH * viewH / total);
            this.barY = this.viewY1 + (viewH - this.barH) * this.scroll / maxScroll;
            context.fill(this.barTrackX, this.viewY1, this.barTrackX + SCROLLBAR_W, this.viewY2, SCROLLBAR_TRACK);
            context.fill(this.barTrackX, this.barY, this.barTrackX + SCROLLBAR_W, this.barY + this.barH, SCROLLBAR_COLOR);
        } else {
            this.barVisible = false;
        }
    }

    /** 行类型：普通正文 / 工具执行记录 / 可点击的「思考」头行 / 展开后的思考正文 / 代码块 / 摘要头行与正文。 */
    private enum LineKind {
        TEXT,
        TOOL,
        THINK_HEADER,
        THINK_BODY,
        CODE,
        SUMMARY_HEADER,
        SUMMARY_BODY
    }

    /** 一「行」显示单元；{@code bgColor} 非 0 时先铺一层同高背景条（代码块与工具记录用）。 */
    private record Line(OrderedText text, int color, int height, int messageIndex, LineKind kind, int bgColor) {
        Line(OrderedText text, int color, int height, int messageIndex, LineKind kind) {
            this(text, color, height, messageIndex, kind, 0);
        }
    }

    /** 代码块在 {@code lines} 里的行区间，用于按累计行高反推它的屏幕矩形。 */
    private record CodeBlockRange(int startLine, int endLine, String code) {
    }

    /** 代码块「复制」按钮的屏幕矩形，以及点击时要写入剪贴板的文本。 */
    private record CodeCopyButton(int x1, int y1, int x2, int y2, String code) {
    }

    private List<Line> buildLines(int width) {
        List<Line> lines = new ArrayList<>();
        int lineHeight = this.textRenderer.fontHeight + 2;

        List<ChatMessage> list = messages();
        ChatSession session = active();
        this.msgBounds.clear();
        this.thinkingHeaderBounds.clear();
        this.codeBlockRanges.clear();

        // 摘要块放在最前面：头行可点击展开 / 收起，右侧带「删除摘要」
        if (!session.summary.isBlank()) {
            String header = Text.translatable(this.summaryExpanded
                    ? "gui.chatai.summary.collapse" : "gui.chatai.summary.expand").getString();
            lines.add(new Line(Text.literal(header).asOrderedText(), SUMMARY_HEADER_COLOR,
                    lineHeight, -1, LineKind.SUMMARY_HEADER));
            if (this.summaryExpanded) {
                for (OrderedText wrapped : this.textRenderer.wrapLines(
                        Text.literal(session.summary), width)) {
                    lines.add(new Line(wrapped, SUMMARY_BODY_COLOR, lineHeight, -1, LineKind.SUMMARY_BODY));
                }
                lines.add(new Line(Text.empty().asOrderedText(), 0, ROW_GAP, -1, LineKind.TEXT));
            }
        }

        for (int i = 0; i < list.size(); i++) {
            this.msgBounds.add(new int[]{Integer.MAX_VALUE, Integer.MIN_VALUE});
            ChatMessage message = list.get(i);
            if (session.hidden.contains(message)) {
                continue; // 只在界面隐藏，仍参与上下文
            }
            // 思考块放在正文之前，是否展开由用户点击头行决定
            if (message.role() == ChatMessage.Role.ASSISTANT && message.hasReasoning()) {
                appendThinking(lines, i, message.reasoning(), width, lineHeight,
                        this.expandedThinking.contains(message));
            }
            appendMessage(lines, i, message.role(), message.content(), width, lineHeight);
        }

        // 只有当前会话的流式回复才显示在这里（切到别的会话不会串台）
        if (this.streamingBuffer != null && this.streamingSession == active()) {
            // 思考内容在流式期间默认展开，方便实时看到推理过程
            if (this.streamingReasoning != null && this.streamingReasoning.length() > 0) {
                appendThinking(lines, -1, this.streamingReasoning.toString(), width, lineHeight, true);
            }
            if (this.streamingBuffer.length() == 0) {
                // 还没有任何内容：作为独立一行显示"正在思考…"，跟随内容一起滚动，
                // 避免像以前那样固定在视口底部、和最后一条消息的"AI："前缀重叠。
                for (OrderedText wrapped : this.textRenderer.wrapLines(
                        Text.translatable("gui.chatai.thinking"), width)) {
                    lines.add(new Line(wrapped, HINT_COLOR, lineHeight, -1, LineKind.TEXT));
                }
            } else {
                appendMessage(lines, -1, ChatMessage.Role.ASSISTANT,
                        this.streamingBuffer + "\u258D", width, lineHeight); // 结尾光标
            }
        }
        return lines;
    }

    private void appendMessage(List<Line> lines, int messageIndex, ChatMessage.Role role, String content,
                               int width, int lineHeight) {
        boolean user = role == ChatMessage.Role.USER;
        String prefix = user ? "你：" : "AI：";
        int color = user ? USER_COLOR : AI_COLOR;

        // 按 ``` 把消息切成正文段 / 代码段，代码段渲染成深色块
        boolean prefixPending = true;
        for (MarkdownParser.Segment segment : MarkdownParser.parse(content)) {
            if (segment.kind() == MarkdownParser.Kind.CODE) {
                if (prefixPending) {
                    addTextLines(lines, prefix, color, messageIndex, lineHeight, width);
                    prefixPending = false;
                }
                appendCodeBlock(lines, messageIndex, segment.text(), width, lineHeight);
                continue;
            }
            String text = prefixPending ? prefix + segment.text() : segment.text();
            prefixPending = false;
            if (!text.isEmpty()) {
                addTextLines(lines, text, color, messageIndex, lineHeight, width);
            }
        }
        if (prefixPending) {
            addTextLines(lines, prefix, color, messageIndex, lineHeight, width);
        }

        // 消息末尾留出一段空隙，悬浮按钮就画在这里
        lines.add(new Line(Text.empty().asOrderedText(), 0, MESSAGE_GAP, messageIndex, LineKind.TEXT));
    }

    /** 正文按宽度自动换行后追加成若干显示行；工具记录行单独配色加底。 */
    private void addTextLines(List<Line> lines, String text, int color, int messageIndex,
                              int lineHeight, int width) {
        for (String raw : text.split("\n", -1)) {
            boolean tool = isToolTraceLine(raw);
            int lineColor = tool ? TOOL_TEXT_COLOR : color;
            for (OrderedText wrapped : this.textRenderer.wrapLines(Text.literal(raw), width)) {
                lines.add(new Line(wrapped, lineColor, lineHeight, messageIndex,
                        tool ? LineKind.TOOL : LineKind.TEXT, tool ? TOOL_BG : 0));
            }
        }
    }

    /** 是否是工具执行记录行。模型自己编造的这种行也会被一并标出来，方便一眼分辨。 */
    private static boolean isToolTraceLine(String line) {
        return line.startsWith(HttpAiService.TOOL_TRACE_MARK);
    }

    /** 追加一个代码块：深色背景条；不自动换行，超宽按像素宽度硬折并保留原有缩进。 */
    private void appendCodeBlock(List<Line> lines, int messageIndex, String code, int width, int lineHeight) {
        int startLine = lines.size();
        if (code.isEmpty()) {
            // 空代码块（例如刚打出 ``` 还没有内容）也给一条背景条，避免闪烁
            lines.add(new Line(Text.empty().asOrderedText(), CODE_TEXT_COLOR,
                    lineHeight, messageIndex, LineKind.CODE, CODE_BG));
        } else {
            for (String rawLine : code.split("\n", -1)) {
                for (String piece : foldCodeLine(rawLine, width)) {
                    lines.add(new Line(Text.literal(piece).asOrderedText(), CODE_TEXT_COLOR,
                            lineHeight, messageIndex, LineKind.CODE, CODE_BG));
                }
            }
        }
        // 记下行区间，稍后按累计行高算出屏幕矩形，用来摆「复制」按钮
        this.codeBlockRanges.add(new CodeBlockRange(startLine, lines.size(), code));
    }

    /** 代码行不做词级换行：按像素宽度硬折，保留每行原有缩进。 */
    private List<String> foldCodeLine(String line, int width) {
        List<String> pieces = new ArrayList<>();
        if (line.isEmpty()) {
            pieces.add("");
            return pieces;
        }
        int start = 0;
        while (start < line.length()) {
            int end = start;
            while (end < line.length()
                    && this.textRenderer.getWidth(line.substring(start, end + 1)) <= width) {
                end++;
            }
            if (end == start) {
                end = start + 1; // 单个字符就超宽，也得往前推一格，避免死循环
            }
            pieces.add(line.substring(start, end));
            start = end;
        }
        return pieces;
    }

    /**
     * 追加一个「思考」折叠块：头行可点击，展开后跟着思考正文。
     *
     * <p>{@code messageIndex} 为 -1 表示这是流式中的临时块（不可点击、固定展开）。</p>
     */
    private void appendThinking(List<Line> lines, int messageIndex, String reasoning,
                                int width, int lineHeight, boolean expanded) {
        String label = expanded ? "[-] 思考（点击收起）" : "[+] 思考（点击展开）";
        lines.add(new Line(Text.literal(label).asOrderedText(), THINK_HEADER_COLOR,
                lineHeight, messageIndex, LineKind.THINK_HEADER));
        if (!expanded) {
            return;
        }
        for (OrderedText wrapped : this.textRenderer.wrapLines(Text.literal(reasoning), width)) {
            lines.add(new Line(wrapped, THINK_BODY_COLOR, lineHeight, messageIndex, LineKind.THINK_BODY));
        }
    }

    // ------------------------------------------------------------------
    // 消息悬浮操作
    // ------------------------------------------------------------------

    /** 鼠标所在的消息下标；不在任何消息上时返回 -1。 */
    private int messageAt(double mouseX, double mouseY) {
        if (mouseX < this.viewX1 || mouseX > this.viewX2) {
            return -1;
        }
        for (int i = 0; i < this.msgBounds.size(); i++) {
            int[] bounds = this.msgBounds.get(i);
            if (bounds[0] > bounds[1]) {
                continue; // 被界面隐藏的消息
            }
            if (mouseY >= bounds[0] && mouseY < bounds[1]) {
                return i;
            }
        }
        return -1;
    }

    /** 操作按钮条顶部的屏幕 Y。 */
    private int actionStripY(int messageIndex) {
        int[] bounds = this.msgBounds.get(messageIndex);
        return bounds[1] - MESSAGE_GAP + MSG_BTN_PAD;
    }

    /** 该消息可用的操作。重新生成只对 AI 消息开放。 */
    private List<Action> actionsFor(int messageIndex) {
        List<Action> actions = new ArrayList<>();
        actions.add(Action.COPY);
        actions.add(Action.DELETE);
        ChatMessage message = messages().get(messageIndex);
        if (message.role() == ChatMessage.Role.ASSISTANT && messageIndex > 0 && !this.waiting) {
            actions.add(Action.REGENERATE);
        }
        return actions;
    }

    private void renderMessageActions(DrawContext context, List<Action> actions, int stripY) {
        int x = this.viewX1;
        for (int i = 0; i < actions.size(); i++) {
            boolean hover = i == this.hoverBtn;
            context.fill(x, stripY, x + MSG_BTN_W, stripY + MSG_BTN_H, hover ? ACTION_BG_HOVER : ACTION_BG);
            drawBorder(context, x, stripY, x + MSG_BTN_W, stripY + MSG_BTN_H, ACTION_BORDER);
            int color = hover ? ACTION_ICON_HOVER : ACTION_ICON;
            int ix = x + (MSG_BTN_W - 8) / 2;
            int iy = stripY + (MSG_BTN_H - 8) / 2;
            switch (actions.get(i)) {
                case COPY -> drawCopyIcon(context, ix, iy, color);
                case DELETE -> drawTrashIcon(context, ix, iy, color);
                case REGENERATE -> drawRegenIcon(context, ix, iy, color);
            }
            x += MSG_BTN_W + MSG_BTN_GAP;
        }
    }

    /** 命中则执行操作；返回 true 表示已消费这次点击。 */
    private boolean handleMessageAction(double mouseX, double mouseY) {
        int index = messageAt(mouseX, mouseY);
        if (index < 0) {
            return false;
        }
        List<Action> actions = actionsFor(index);
        int stripY = actionStripY(index);
        if (mouseY < stripY || mouseY >= stripY + MSG_BTN_H) {
            return false;
        }
        int rel = (int) (mouseX - this.viewX1);
        int step = MSG_BTN_W + MSG_BTN_GAP;
        int button = rel / step;
        if (button < 0 || button >= actions.size() || rel % step >= MSG_BTN_W) {
            return false;
        }
        performAction(index, actions.get(button));
        return true;
    }

    /** 命中某条消息的「思考」头行则切换展开状态；返回 true 表示已消费。 */
    private boolean handleThinkingClick(double mouseX, double mouseY) {
        if (this.effortBar != null && this.effortBar.isPopupOpen()) {
            return false;
        }
        if (mouseX < this.viewX1 || mouseX > this.viewX2
                || mouseY < this.viewY1 || mouseY > this.viewY2) {
            return false;
        }
        for (Map.Entry<Integer, int[]> entry : this.thinkingHeaderBounds.entrySet()) {
            int[] range = entry.getValue();
            if (range == null || mouseY < range[0] || mouseY >= range[1]) {
                continue;
            }
            int index = entry.getKey();
            if (index < 0 || index >= messages().size()) {
                return false;
            }
            ChatMessage message = messages().get(index);
            if (!this.expandedThinking.remove(message)) {
                this.expandedThinking.add(message);
            }
            return true;
        }
        return false;
    }

    /** 命中摘要头行则展开 / 收起；命中右侧按钮则删除摘要。返回 true 表示已消费。 */
    private boolean handleSummaryClick(double mouseX, double mouseY) {
        if (this.summaryDeleteBounds != null
                && mouseX >= this.summaryDeleteBounds[0] && mouseX < this.summaryDeleteBounds[2]
                && mouseY >= this.summaryDeleteBounds[1] && mouseY < this.summaryDeleteBounds[3]) {
            ChatSession session = active();
            session.summary = "";
            session.summaryCount = 0;
            this.summaryExpanded = false;
            ChataiHistory.save(this.sessions);
            return true;
        }
        if (this.summaryHeaderBounds != null
                && mouseX >= this.viewX1 && mouseX <= this.viewX2
                && mouseY >= this.summaryHeaderBounds[0] && mouseY < this.summaryHeaderBounds[1]) {
            this.summaryExpanded = !this.summaryExpanded;
            return true;
        }
        return false;
    }

    /** 技能面板开关；打开时重新扫描目录，方便新放进来的技能立刻可见。 */
    private void toggleSkillPopup() {
        this.skillPopupOpen = !this.skillPopupOpen;
        if (this.skillPopupOpen) {
            SkillRegistry.reload();
        }
    }

    /** 命中技能行则切换该技能的启用状态；点在面板外则收起。返回 true 表示已消费。 */
    private boolean handleSkillPopupClick(double mouseX, double mouseY) {
        if (!this.skillPopupOpen) {
            return false;
        }
        layoutSkillPopup();
        int x1 = this.skillPopupX;
        int y1 = this.skillPopupY;
        int x2 = x1 + this.skillPopupW;
        int y2 = y1 + this.skillPopupH;
        if (mouseX < x1 || mouseX > x2 || mouseY < y1 || mouseY > y2) {
            this.skillPopupOpen = false;
            return false;
        }
        int rowY = y1 + SKILL_POPUP_PAD + SKILL_ROW_H;
        for (SkillRegistry.Skill skill : SkillRegistry.skills()) {
            if (mouseY >= rowY && mouseY < rowY + SKILL_ROW_H) {
                toggleSkill(skill.name());
                return true;
            }
            rowY += SKILL_ROW_H;
        }
        return true; // 点在标题 / 空白上，吞掉即可
    }

    /** 切换技能启用状态并落盘。 */
    private void toggleSkill(String name) {
        ChataiConfig config = ChataiConfig.get();
        if (config.enabledSkills == null) {
            config.enabledSkills = new ArrayList<>();
        }
        if (!config.enabledSkills.remove(name)) {
            config.enabledSkills.add(name);
        }
        config.save();
    }

    /** 技能面板几何：挂在「技能」按钮正下方，超出屏幕时贴边。 */
    private void layoutSkillPopup() {
        int rows = Math.max(1, SkillRegistry.skills().size());
        this.skillPopupW = SKILL_POPUP_W;
        this.skillPopupH = SKILL_POPUP_PAD * 2 + SKILL_ROW_H * (rows + 1);
        this.skillPopupX = this.skillButton.getX();
        this.skillPopupY = this.skillButton.getY() + this.skillButton.getHeight() + 4;
        if (this.skillPopupX + this.skillPopupW > this.width) {
            this.skillPopupX = Math.max(0, this.width - this.skillPopupW);
        }
        if (this.skillPopupY + this.skillPopupH > this.height) {
            this.skillPopupY = Math.max(0, this.height - this.skillPopupH);
        }
    }

    /** 画技能面板：标题 + 每个技能一行，启用打勾并高亮。 */
    private void renderSkillPopup(DrawContext context, int mouseX, int mouseY) {
        layoutSkillPopup();
        int x1 = this.skillPopupX;
        int y1 = this.skillPopupY;
        int x2 = x1 + this.skillPopupW;
        int y2 = y1 + this.skillPopupH;

        context.fill(x1, y1, x2, y2, ACTION_BG);
        drawBorder(context, x1, y1, x2, y2, PANEL_BORDER);

        int titleY = y1 + SKILL_POPUP_PAD;
        context.drawTextWithShadow(this.textRenderer, Text.translatable("gui.chatai.skill.title"),
                x1 + SKILL_POPUP_PAD + 1, titleY + (SKILL_ROW_H - 8) / 2, ACCENT);

        List<SkillRegistry.Skill> skills = SkillRegistry.skills();
        List<String> enabled = ChataiConfig.get().enabledSkills;
        this.skillPopupHover = -1;
        int rowY = titleY + SKILL_ROW_H;
        if (skills.isEmpty()) {
            String hint = this.textRenderer.trimToWidth(
                    Text.translatable("gui.chatai.skill.empty").getString(), this.skillPopupW - 6);
            context.drawTextWithShadow(this.textRenderer, hint,
                    x1 + SKILL_POPUP_PAD, rowY + (SKILL_ROW_H - 8) / 2, HINT_COLOR);
            return;
        }
        for (int i = 0; i < skills.size(); i++) {
            SkillRegistry.Skill skill = skills.get(i);
            boolean hover = mouseX >= x1 && mouseX < x2 && mouseY >= rowY && mouseY < rowY + SKILL_ROW_H;
            if (hover) {
                this.skillPopupHover = i;
                context.fill(x1 + 1, rowY, x2 - 1, rowY + SKILL_ROW_H, TAB_HOVER_BG);
            }
            boolean on = enabled != null && enabled.contains(skill.name());
            String label = this.textRenderer.trimToWidth(
                    (on ? "[x] " : "[ ] ") + skill.name(), this.skillPopupW - 8);
            context.drawTextWithShadow(this.textRenderer, label,
                    x1 + SKILL_POPUP_PAD, rowY + (SKILL_ROW_H - 8) / 2, on ? ACCENT : TAB_TEXT);
            rowY += SKILL_ROW_H;
        }
    }

    /** 命中代码块的「复制」按钮则把该段代码写入剪贴板；返回 true 表示已消费。 */
    private boolean handleCodeCopyClick(double mouseX, double mouseY) {
        for (CodeCopyButton button : this.codeCopyButtons) {
            if (mouseX >= button.x1() && mouseX < button.x2()
                    && mouseY >= button.y1() && mouseY < button.y2()) {
                MinecraftClient.getInstance().keyboard.setClipboard(button.code());
                return true;
            }
        }
        return false;
    }

    private void performAction(int index, Action action) {
        List<ChatMessage> list = messages();
        if (index < 0 || index >= list.size()) {
            return;
        }
        switch (action) {
            case COPY -> MinecraftClient.getInstance().keyboard.setClipboard(list.get(index).content());
            case DELETE -> deleteMessage(index);
            case REGENERATE -> regenerate(index);
        }
    }

    /** 删除单条消息：默认只在界面隐藏，可在设置里改成同步从上下文移除。 */
    private void deleteMessage(int index) {
        if (this.waiting) {
            return;
        }
        ChatSession session = active();
        if (index < 0 || index >= session.messages.size()) {
            return;
        }
        ChatMessage message = session.messages.get(index);
        if (ChataiConfig.get().deleteRemovesFromContext) {
            session.messages.remove(index);
            session.hidden.remove(message);
        } else {
            session.hidden.add(message);
        }
        ChataiHistory.save(this.sessions);
        this.followOutput = true;
    }

    /** 删掉这条 AI 回复（连同它之后的消息）并重新请求。 */
    private void regenerate(int index) {
        if (this.waiting) {
            return;
        }
        ChatSession session = active();
        List<ChatMessage> list = session.messages;
        if (index < 0 || index >= list.size()
                || list.get(index).role() != ChatMessage.Role.ASSISTANT) {
            return;
        }
        list.subList(index, list.size()).clear();
        session.hidden.removeIf(hidden -> list.stream().noneMatch(message -> message == hidden));
        if (list.isEmpty() || list.get(list.size() - 1).role() != ChatMessage.Role.USER) {
            return;
        }
        this.followOutput = true;
        startStream(session);
    }

    private static void drawBorder(DrawContext context, int x1, int y1, int x2, int y2, int color) {
        context.fill(x1, y1, x2, y1 + 1, color);
        context.fill(x1, y2 - 1, x2, y2, color);
        context.fill(x1, y1, x1 + 1, y2, color);
        context.fill(x2 - 1, y1, x2, y2, color);
    }

    /** 复制图标：两张叠起来的纸（8×8）。 */
    private static void drawCopyIcon(DrawContext context, int x, int y, int color) {
        // 后面那张
        context.fill(x + 2, y, x + 8, y + 1, color);
        context.fill(x + 2, y, x + 3, y + 5, color);
        context.fill(x + 7, y, x + 8, y + 5, color);
        context.fill(x + 2, y + 4, x + 6, y + 5, color);
        // 前面那张
        context.fill(x, y + 3, x + 6, y + 4, color);
        context.fill(x, y + 3, x + 1, y + 8, color);
        context.fill(x + 5, y + 3, x + 6, y + 8, color);
        context.fill(x, y + 7, x + 6, y + 8, color);
    }

    /** 删除图标：垃圾桶（8×8）。 */
    private static void drawTrashIcon(DrawContext context, int x, int y, int color) {
        context.fill(x + 2, y, x + 6, y + 1, color);      // 把手
        context.fill(x, y + 1, x + 8, y + 2, color);      // 盖子
        context.fill(x + 1, y + 2, x + 2, y + 8, color);  // 左壁
        context.fill(x + 6, y + 2, x + 7, y + 8, color);  // 右壁
        context.fill(x + 1, y + 7, x + 7, y + 8, color);  // 底
        context.fill(x + 3, y + 3, x + 4, y + 6, color);  // 内纹
        context.fill(x + 5, y + 3, x + 6, y + 6, color);  // 内纹
    }

    /** 重新生成图标：带箭头的回转圈（8×8）。 */
    private static void drawRegenIcon(DrawContext context, int x, int y, int color) {
        context.fill(x + 2, y + 1, x + 5, y + 2, color);  // 顶
        context.fill(x + 5, y + 2, x + 6, y + 3, color);  // 右上
        context.fill(x + 5, y + 5, x + 6, y + 7, color);  // 右下
        context.fill(x + 2, y + 7, x + 5, y + 8, color);  // 底
        context.fill(x + 1, y + 3, x + 2, y + 6, color);  // 左
        context.fill(x + 4, y, x + 8, y + 1, color);      // 箭头横
        context.fill(x + 7, y + 1, x + 8, y + 3, color);  // 箭头竖
    }

    // ------------------------------------------------------------------
    // 交互
    // ------------------------------------------------------------------

    private void sendMessage() {
        if (this.waiting || this.inputField == null) {
            return;
        }
        String text = this.inputField.getText().trim();
        if (text.isEmpty()) {
            return;
        }

        // 测试入口：/getshell 在本地跑 rish，不发给模型
        if (text.equals("/getshell") || text.startsWith("/getshell ")) {
            this.inputField.setText("");
            runGetShell(active(), text);
            return;
        }

        ChatSession session = active();
        session.messages.add(ChatMessage.user(text));
        this.inputField.setText("");
        this.followOutput = true;
        startStream(session);
    }

    /**
     * 测试用：{@code /getshell} 直接调 rish，结果贴回对话里。
     *
     * <p>不带参数时跑固定探针；带参数时把参数原样丢给远端 shell（只用于验证环境）。</p>
     */
    private void runGetShell(ChatSession session, String text) {
        session.messages.add(ChatMessage.user(text));
        ChatMessage pending = ChatMessage.assistant("正在测试 rish…");
        session.messages.add(pending);
        this.followOutput = true;

        String extra = text.substring("/getshell".length()).trim();
        Thread worker = new Thread(() -> {
            String report = extra.isEmpty()
                    ? RishShell.probe()
                    : RishShell.runCommand(extra).output();
            if (report.isBlank()) {
                report = "（无输出）";
            }
            String result = report;
            MinecraftClient.getInstance().execute(() -> {
                int index = session.messages.lastIndexOf(pending);
                if (index >= 0) {
                    session.messages.set(index, ChatMessage.assistant(result));
                } else {
                    session.messages.add(ChatMessage.assistant(result));
                }
                ChataiHistory.save(this.sessions);
                this.followOutput = true;
            });
        }, "chatai-getshell");
        worker.setDaemon(true);
        worker.start();
    }

    /** 把当前会话的消息（末尾须是用户消息）发给模型并开始流式接收。 */
    private void startStream(ChatSession session) {
        this.waiting = true;
        this.streamCancelled = false;
        this.streamingSession = session;
        StringBuilder buffer = new StringBuilder();
        StringBuilder reasoningBuffer = new StringBuilder();
        this.streamingBuffer = buffer;
        this.streamingReasoning = reasoningBuffer;
        this.followOutput = true;
        refreshSendButton();

        // 回调在后台线程触发，统一切回客户端主线程再改 UI。
        // 这里捕获本次的 buffer 快照：被「停止」后旧请求可能还会吐几条，不能污染下一次回复。
        this.streamFuture = this.aiService.stream(List.copyOf(session.messages),
                        delta -> MinecraftClient.getInstance().execute(() -> buffer.append(delta)),
                        reasoning -> MinecraftClient.getInstance().execute(() -> reasoningBuffer.append(reasoning)),
                        session.summary, session.summaryCount,
                        this::confirmTool,
                        activity -> MinecraftClient.getInstance().execute(() -> buffer.append(activity)))
                .whenComplete((full, error) ->
                        MinecraftClient.getInstance().execute(() -> finishStream(error)));
    }

    /**
     * 危险工具的确认桥：在客户端线程弹确认框，玩家点「执行」才放行。
     *
     * <p>工具在后台线程被调用，这里切回客户端线程再开界面；玩家选择后完成 future，
     * 后台线程继续。即使当前开着别的界面（设置等），确认框也会弹到最上层，不视为拒绝。</p>
     */
    private CompletableFuture<Boolean> confirmTool(String toolName, String detail) {
        MinecraftClient client = MinecraftClient.getInstance();
        CompletableFuture<Boolean> answer = new CompletableFuture<>();
        client.execute(() -> {
            // 不能因为玩家临时开了别的界面（设置、宠物等）就直接判为拒绝，否则工具这一轮
            // 会被无故中断、整段生成看着就像「被强制停止」。确认框照常弹到最上层，
            // 处理完回到本对话界面。
            client.setScreen(new ToolConfirmScreen(this, toolName, detail, answer));
        });
        return answer;
    }

    /** 用户点「停止」：取消请求；已收到的半截内容由 finishStream 落库。 */
    private void stopStream() {
        if (!this.waiting || this.streamFuture == null) {
            return;
        }
        this.streamCancelled = true;
        this.streamFuture.cancel(true);
    }

    private void finishStream(Throwable error) {
        ChatSession target = this.streamingSession == null ? active() : this.streamingSession;
        StringBuilder buffer = this.streamingBuffer;
        StringBuilder reasoningBuffer = this.streamingReasoning;
        this.streamingBuffer = null;
        this.streamingReasoning = null;
        this.streamingSession = null;
        this.streamFuture = null;
        this.waiting = false;

        String streamed = buffer == null ? "" : buffer.toString();
        String reasoning = reasoningBuffer == null ? "" : reasoningBuffer.toString();
        if (error != null) {
            String prefix = streamed.isEmpty() ? "" : streamed + "\n";
            String note = this.streamCancelled ? "（已停止生成）" : "请求失败：" + rootMessage(error);
            target.messages.add(ChatMessage.assistant(prefix + note, reasoning));
        } else if (streamed.isEmpty()) {
            target.messages.add(ChatMessage.assistant("（空回复）", reasoning));
        } else {
            target.messages.add(ChatMessage.assistant(streamed, reasoning));
        }
        this.streamCancelled = false;

        ChataiHistory.save(this.sessions);
        this.followOutput = true;
        refreshSendButton();

        // 第一次问答（刚好两条消息）结束后，静默让模型给会话起个短标题
        if (error == null) {
            maybeAutoName(target);
            // 上下文过长时静默把较旧的部分折成摘要
            maybeAutoCompress(target);
        }
    }

    // ------------------------------------------------------------------
    // 上下文压缩
    // ------------------------------------------------------------------

    /** 上下文超过阈值时静默压缩；未超过或没可压内容时什么都不做。 */
    private void maybeAutoCompress(ChatSession session) {
        int threshold = ChataiConfig.get().compressAtChars;
        if (threshold <= 0 || this.waiting || !canCompress(session)) {
            return;
        }
        if (contextChars(session) < threshold) {
            return;
        }
        compress(session);
    }

    /** 「压缩」按钮：手动把当前会话的旧消息折成摘要。 */
    private void compressNow() {
        if (this.waiting) {
            return;
        }
        ChatSession session = active();
        if (canCompress(session)) {
            compress(session);
        }
    }

    /** 把未被摘要覆盖的较旧部分交给模型压成摘要，成功后写回会话。 */
    private void compress(ChatSession session) {
        if (!canCompress(session) || !this.compressInFlight.add(session.id)) {
            return;
        }
        int end = compressEnd(session);
        String prompt = buildSummaryPrompt(session, session.summary, session.summaryCount, end);
        this.aiService.complete(prompt, 800).whenComplete((text, error) ->
                MinecraftClient.getInstance().execute(() -> {
                    this.compressInFlight.remove(session.id);
                    if (error != null || text == null || text.isBlank()) {
                        return;
                    }
                    session.summary = text.trim();
                    session.summaryCount = Math.max(session.summaryCount, end);
                    ChataiHistory.save(this.sessions);
                    if (session == active()) {
                        this.followOutput = true;
                    }
                }));
    }

    /** 拼压缩用的提示词：已有摘要 + 本次要折进摘要的新消息。 */
    private static String buildSummaryPrompt(ChatSession session, String previous, int from, int end) {
        StringBuilder sb = new StringBuilder();
        sb.append("请把下面的对话压缩成一段简洁的摘要，保留关键信息、结论、用户偏好与待办事项，")
                .append("用陈述句陈述，不要逐条复述，不要加标题。直接输出摘要正文。\n\n");
        if (previous != null && !previous.isBlank()) {
            sb.append("【已有摘要】\n").append(previous.trim()).append("\n\n");
        }
        sb.append("【新增对话】\n");
        for (int i = Math.max(0, from); i < Math.min(end, session.messages.size()); i++) {
            ChatMessage message = session.messages.get(i);
            sb.append(message.role() == ChatMessage.Role.USER ? "用户：" : "助手：")
                    .append(message.content()).append('\n');
        }
        return sb.toString();
    }

    /** 未被摘要覆盖的消息总字符数，用来判断是否该自动压缩。 */
    private static int contextChars(ChatSession session) {
        int total = 0;
        for (int i = Math.max(0, session.summaryCount); i < session.messages.size(); i++) {
            total += session.messages.get(i).content().length();
        }
        return total;
    }

    /**
     * 会话还没标题、且刚好完成第一轮问答时，静默请求模型总结一个 4~8 字的标题。
     *
     * <p>请求失败或返回空都静默忽略，标签继续用「第一条用户消息前 14 字」兜底。</p>
     */
    private void maybeAutoName(ChatSession session) {
        if (session.title != null && !session.title.isBlank()) {
            return;
        }
        if (session.messages.size() != 2
                || session.messages.get(0).role() != ChatMessage.Role.USER
                || session.messages.get(1).role() != ChatMessage.Role.ASSISTANT) {
            return;
        }
        if (!this.namingInFlight.add(session.id)) {
            return;
        }

        String prompt = "请用4-8个字总结以下对话的标题，只输出标题本身，"
                + "不要标点、引号、序号或任何解释：\n"
                + "用户：" + session.messages.get(0).content() + "\n"
                + "助手：" + session.messages.get(1).content();

        this.aiService.complete(prompt, 24).whenComplete((title, error) ->
                MinecraftClient.getInstance().execute(() -> {
                    this.namingInFlight.remove(session.id);
                    if (error != null || title == null || title.isBlank()) {
                        return;
                    }
                    String clean = cleanTitle(title);
                    if (clean.isEmpty() || (session.title != null && !session.title.isBlank())) {
                        return;
                    }
                    session.title = clean;
                    ChataiHistory.save(this.sessions);
                }));
    }

    /** 清洗自动命名结果：去掉换行、引号、反引号与尾部标点，并限制长度。 */
    private static String cleanTitle(String raw) {
        String text = raw.replace('\n', ' ').replace('\r', ' ').trim();
        text = text.replaceAll("^[\"'`“”‘’《〈【\\[]+", "")
                .replaceAll("[\"'`“”‘’》〉】\\]]+$", "")
                .replaceAll("[，。,.！!？?；;：:、\\s]+$", "")
                .trim();
        return text.length() > 20 ? text.substring(0, 20) : text;
    }

    /** 剥掉 CompletionException 包装，取最里层异常信息。 */
    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 回车不再发送，统一走界面上的「发送」按钮，方便输入多段长 prompt
        // 推理强度滑块获得焦点时，左右方向键切档
        if (this.effortBar != null && this.effortBar.isFocused()
                && (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT)) {
            this.effortBar.step(keyCode == GLFW.GLFW_KEY_RIGHT ? 1 : -1);
            return true;
        }

        // 输入框获得焦点时，方向键留给光标移动
        if (this.inputField != null && !this.inputField.isFocused() && maxScroll() > 0) {
            int page = Math.max(1, viewportHeight() - this.textRenderer.fontHeight);
            switch (keyCode) {
                case GLFW.GLFW_KEY_UP -> {
                    scrollBy(-WHEEL_STEP);
                    return true;
                }
                case GLFW.GLFW_KEY_DOWN -> {
                    scrollBy(WHEEL_STEP);
                    return true;
                }
                case GLFW.GLFW_KEY_PAGE_UP -> {
                    scrollBy(-page);
                    return true;
                }
                case GLFW.GLFW_KEY_PAGE_DOWN -> {
                    scrollBy(page);
                    return true;
                }
                case GLFW.GLFW_KEY_HOME -> {
                    scrollTo(0);
                    return true;
                }
                case GLFW.GLFW_KEY_END -> {
                    scrollTo(maxScroll());
                    return true;
                }
                default -> {
                }
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        // 滚轮：向上为正，内容向上滚（看更早的消息）
        scrollBy(-verticalAmount * WHEEL_STEP);
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 会话标签栏优先（位于面板顶部，不与其它控件重叠）
        if (button == 0 && handleTabClick(mouseX, mouseY)) {
            return true;
        }
        // 宠物优先：点中盆或头顶才消费，其余照旧走下面的逻辑
        if (PetManager.get().mousePressed(mouseX, mouseY, button)) {
            return true;
        }
        // 先让输入框 / 按钮等控件处理
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        // 技能面板：点中某行切换启用；点在面板外则收起
        if (button == 0 && handleSkillPopupClick(mouseX, mouseY)) {
            return true;
        }
        // 摘要头行：点击展开 / 收起；右侧按钮删除摘要
        if (button == 0 && handleSummaryClick(mouseX, mouseY)) {
            return true;
        }
        // 「思考」头行：点击展开 / 收起
        if (button == 0 && handleThinkingClick(mouseX, mouseY)) {
            return true;
        }
        // 代码块右上角的「复制」按钮
        if (button == 0 && handleCodeCopyClick(mouseX, mouseY)) {
            return true;
        }
        // 消息悬浮操作按钮
        if (button == 0 && handleMessageAction(mouseX, mouseY)) {
            return true;
        }
        if (button != 0 || maxScroll() <= 0) {
            return false;
        }

        // 点在滚动条上：按比例跳转并开始拖动
        if (this.barVisible && isOverScrollbar(mouseX, mouseY)) {
            int trackH = Math.max(1, viewportHeight() - this.barH);
            double ratio = (double) maxScroll() / trackH;
            this.dragging = true;
            this.dragStartY = mouseY;
            this.dragStartScroll = this.scroll;
            this.dragPixelsPerScroll = ratio;
            scrollTo((mouseY - this.viewY1 - this.barH / 2.0) * ratio);
            this.dragStartY = mouseY;
            this.dragStartScroll = this.scroll;
            return true;
        }

        // 点在消息区：按住拖动即可滚动
        if (mouseX >= this.viewX1 && mouseX <= this.viewX2
                && mouseY >= this.viewY1 && mouseY <= this.viewY2) {
            this.dragging = true;
            this.dragStartY = mouseY;
            this.dragStartScroll = this.scroll;
            this.dragPixelsPerScroll = 1.0;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (PetManager.get().mouseDragged(mouseX, mouseY)) {
            return true;
        }
        if (this.dragging) {
            scrollTo(this.dragStartScroll + (mouseY - this.dragStartY) * this.dragPixelsPerScroll);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (PetManager.get().mouseReleased(mouseX, mouseY)) {
            return true;
        }
        if (this.dragging && button == 0) {
            this.dragging = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private boolean isOverScrollbar(double mouseX, double mouseY) {
        return mouseX >= this.barTrackX - 1 && mouseX <= this.barTrackX + SCROLLBAR_W + 1
                && mouseY >= this.viewY1 && mouseY <= this.viewY2;
    }

    /** 处理会话标签栏的点击；返回 true 表示已消费。 */
    private boolean handleTabClick(double mouseX, double mouseY) {
        // 模型弹层展开时，先让它自己处理点击（关闭 / 选模型）
        if (this.effortBar != null && this.effortBar.isPopupOpen()) {
            return false;
        }
        if (mouseY < this.tabsY || mouseY >= this.tabsY + TAB_H) {
            return false;
        }
        for (int i = 0; i < this.tabRects.size(); i++) {
            TabRect rect = this.tabRects.get(i);
            if (mouseX >= rect.x1() && mouseX < rect.x2()) {
                boolean close = this.sessions.size() > 1 && !this.waiting
                        && mouseX >= rect.x2() - CLOSE_W - 3;
                if (close) {
                    closeSession(i);
                } else {
                    switchSession(i);
                }
                return true;
            }
        }
        if (mouseX >= this.newTabX && mouseX < this.newTabX + this.newTabW) {
            newSession();
            return true;
        }
        return false;
    }

    @Override
    public void close() {
        ChataiHistory.save(this.sessions);
        super.close();
    }

    @Override
    public boolean shouldPause() {
        // 打开界面时不让单人游戏暂停，方便边看边聊
        return false;
    }
}
