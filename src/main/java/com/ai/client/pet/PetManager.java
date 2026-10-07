package com.ai.client.pet;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 宠物的大脑：表情状态机、扣血动画、米饭盆 / 铁盆物理、火控雷达锁定的调度。
 *
 * <p>所有数值与节奏沿用原 DSH 脚本（{@code dsh_pet.ps1}），但把「余额下降」换成了
 * 「累计消耗 token 每满 10 万扣 1 点血」。充值玩法（余额上升掉米饭盆）保留，但默认
 * 平板显示累计 token，只有切到余额模式才会轮询余额。</p>
 *
 * <p>坐标单位是 GUI 逻辑像素，宠物固定在屏幕左下角。</p>
 */
public final class PetManager {

    // —— 节奏常量（原脚本同名常量）——
    /** 每累计这么多「输出」token 扣 1 点血（只算模型回复，不含 prompt）。 */
    public static final long TOKENS_PER_HP = 5_000L;
    /** 两次扣血动画之间的间隔（秒）。 */
    private static final double CUE_GAP_SEC = 0.2;
    /** 扣血结束后紧张表情的保持时间（秒）。 */
    private static final double NERVOUS_HOLD_SEC = 1.0;
    /** 喂食后开心表情的保持时间（秒）。 */
    private static final double HAPPY_HOLD_SEC = 2.0;
    /** 米饭盆多久没被领就锁定（秒）。 */
    private static final double BOWL_WAIT_SEC = 10.0;
    /** 锁定后多久开始吸附（秒）。 */
    private static final double LOCK_DELAY_SEC = 1.0;
    /** 吸附加速度 / 速度上限（px/s²、px/s）。 */
    private static final double SUCK_ACCEL = 960.0;
    private static final double SUCK_MAX_SPEED = 2400.0;
    /** 重力（px/s²）。 */
    private static final double GRAVITY = 1500.0;
    /** 余额轮询间隔（秒）。 */
    private static final double POLL_INTERVAL_SEC = 2.0;
    /** 双击判定。 */
    private static final long DOUBLE_CLICK_MS = 400L;
    private static final double DOUBLE_CLICK_SLOP = 24.0;

    /** 铁盆尺寸：散落时 = 0.44 倍立绘宽，扣头上时 = 0.95 倍（等比放大，盖住头顶）。 */
    private static final double IRON_LOOSE_W = 0.44;
    private static final double IRON_HEAD_W = 0.95;
    /** 铁盆扣头的锚点（相对立绘左上角的比例，原脚本用放置工具量的）。 */
    private static final double IRON_HEAD_CX = 0.5558;
    private static final double IRON_HEAD_RIM_Y = 0.32;
    /** 铁盆扣头时的倾斜角（度，屏幕坐标顺时针）。 */
    private static final double IRON_HEAD_TILT_DEG = 6.23;

    private static final PetManager INSTANCE = new PetManager();

    public static PetManager get() {
        return INSTANCE;
    }

    private final PetConfig config = PetConfig.get();

    private PetExpression expression = PetExpression.HAPPY;
    private double nervousT;
    private double happyT;
    /** 头顶被扣盆 / 摘盆时的弹一下，1 → 0。 */
    private double headSquashT;

    /** 排队中的扣血次数与到下一次扣血的倒计时。 */
    private int pendingCues;
    private double cueGap;

    private double hitFlashT;
    private double shakeT;

    private final List<Floater> floaters = new ArrayList<>();
    private final List<Bowl> bowls = new ArrayList<>();

    private boolean lockAll;

    // 鼠标拖拽
    private boolean dragging;
    private Bowl dragged;
    private double dragOffX;
    private double dragOffY;

    // 双击头部
    private long lastHeadClickMs = -100000L;
    private double lastHeadClickX;
    private double lastHeadClickY;

    // 屏幕尺寸（每帧更新）
    private int screenW = 320;
    private int screenH = 240;

    // 累计 token 扣血进度
    private long lastLossCount;
    private boolean initialized;

    // 余额轮询
    private boolean polling;
    private double pollTimer = POLL_INTERVAL_SEC;
    private String balanceError;

    private PetManager() {
    }

    // ------------------------------------------------------------------
    // 几何
    // ------------------------------------------------------------------

    /** 立绘实际边长，按屏幕做了上限裁剪，避免小窗口下糊满屏。 */
    public double size() {
        // 聊天面板固定在右下角、占 75% 宽，左侧会空出约 25% 的竖条给宠物；
        // 这里把立绘也限制在这条竖条内（再减去左边距与面板的 padding），避免立绘压到聊天框。
        double freeLeft = this.screenW * 0.25 - 16;
        return Math.min(PetConfig.PET_SIZE,
                Math.min(this.screenH * 0.62, Math.max(48, freeLeft)));
    }

    public double petLeft() {
        return 6;
    }

    public double petTop() {
        return this.screenH - size() - 6;
    }

    /** 头部中心（原脚本测得的 0.5558w / 0.3639w，铁盆就是扣在这个位置）。 */
    public double headX() {
        return petLeft() + size() * IRON_HEAD_CX;
    }

    public double headY() {
        return petTop() + size() * IRON_HEAD_RIM_Y;
    }

    /** 铁盆扣头后的底边（盆沿）所在的 y。 */
    public double ironRimY() {
        return headY();
    }

    /** 身体中心，喂食判定用。 */
    public double bodyX() {
        return petLeft() + size() * 0.47;
    }

    public double bodyY() {
        return petTop() + size() * 0.55;
    }

    public int screenW() {
        return this.screenW;
    }

    public int screenH() {
        return this.screenH;
    }

    // ------------------------------------------------------------------
    // 对外状态（渲染用）
    // ------------------------------------------------------------------

    public PetExpression expression() {
        return this.expression;
    }

    public boolean visible() {
        return this.config.showPet;
    }

    public List<Bowl> bowls() {
        return this.bowls;
    }

    public List<Floater> floaters() {
        return this.floaters;
    }

    /** 红闪强度 [0,1]。 */
    public double hitFlash() {
        return Math.max(0, Math.min(1, this.hitFlashT / 0.35));
    }

    /** 抖动在水平方向的偏移（px）。 */
    public double shakeOffset() {
        if (this.shakeT <= 0) {
            return 0;
        }
        double amp = 3.0 * (this.shakeT / 0.35);
        return Math.sin(this.shakeT * 90.0) * amp;
    }

    /** 头顶盆时的竖向压缩比例（1 = 无）。 */
    public double squashScale() {
        if (this.headSquashT <= 0) {
            return 1.0;
        }
        double t = this.headSquashT / 0.5;      // 1 → 0
        return 1.0 - 0.12 * Math.sin(t * Math.PI);
    }

    /** 平板上的读数。 */
    public String readoutText() {
        if (this.config.readoutTokenMode) {
            return formatTokens(this.config.totalTokens);
        }
        if (this.config.lastBalance < 0) {
            return "--";
        }
        return String.format(Locale.ROOT, "%.2f", this.config.lastBalance);
    }

    /** 平板读数上方的标题，画在平板黑屏里。 */
    public String readoutTitle() {
        return this.config.readoutTokenMode ? "使用Token" : "余额";
    }

    /** 平板读数的说明，画在宠物上方。 */
    public String readoutLabel() {
        return this.config.readoutTokenMode ? "TOKEN" : "BALANCE";
    }

    public static String formatTokens(long tokens) {
        if (tokens >= 100_000_000L) {
            return String.format(Locale.ROOT, "%.1f亿", tokens / 100_000_000.0);
        }
        if (tokens >= 10_000L) {
            return String.format(Locale.ROOT, "%.1f万", tokens / 10_000.0);
        }
        return Long.toString(tokens);
    }

    // ------------------------------------------------------------------
    // 主循环
    // ------------------------------------------------------------------

    /** 每帧调用（仅在打开对话界面时）。 */
    public void update(double dt, int width, int height) {
        this.screenW = Math.max(1, width);
        this.screenH = Math.max(1, height);

        if (dt <= 0) {
            return;
        }
        // 首次打开 / 长时间未更新：避免积压的扣血动画一次性炸开
        if (dt > 0.5) {
            this.pendingCues = Math.min(this.pendingCues, 40);
            dt = Math.min(dt, 0.05);
        }

        ensureInit();
        tickTimers(dt);
        tickCues(dt);
        tickBowls(dt);
        tickBalance(dt);
        updateExpression();
    }

    private synchronized void ensureInit() {
        if (this.initialized) {
            return;
        }
        this.initialized = true;
        long loss = this.config.totalTokens / TOKENS_PER_HP;
        this.lastLossCount = loss;
        this.config.hpLossCount = loss;
        this.config.hp = Math.max(0, this.config.maxHp - (int) loss);
    }

    private void tickTimers(double dt) {
        this.hitFlashT = Math.max(0, this.hitFlashT - dt);
        this.shakeT = Math.max(0, this.shakeT - dt);
        this.nervousT = Math.max(0, this.nervousT - dt);
        this.happyT = Math.max(0, this.happyT - dt);
        this.headSquashT = Math.max(0, this.headSquashT - dt);

        for (int i = this.floaters.size() - 1; i >= 0; i--) {
            Floater f = this.floaters.get(i);
            f.age += dt;
            f.y += f.vy * dt;
            if (f.age >= f.life) {
                this.floaters.remove(i);
            }
        }
    }

    private void tickCues(double dt) {
        this.cueGap -= dt;
        if (this.pendingCues > 0 && this.cueGap <= 0) {
            this.pendingCues--;
            this.cueGap = CUE_GAP_SEC;
            fireCue();
        }
    }

    private void fireCue() {
        this.hitFlashT = 0.35;
        this.shakeT = 0.35;
        this.nervousT = NERVOUS_HOLD_SEC;
        play(PetSounds.HIT);
        addFloater("-1", 0xFFFF5555, this.headX(), this.headY() - size() * 0.22);
    }

    private void updateExpression() {
        boolean ironOnHead = ironOnHead();
        boolean liveRice = hasLiveRice();
        PetExpression next;
        if (ironOnHead && !liveRice) {
            next = PetExpression.CALM;
        } else if (this.nervousT > 0) {
            next = PetExpression.NERVOUS;
        } else if (this.lockAll) {
            next = PetExpression.ALOOF;
        } else {
            next = PetExpression.HAPPY;
        }
        this.expression = next;
    }

    // ------------------------------------------------------------------
    // 扣血 / token
    // ------------------------------------------------------------------

    /** 一次请求结束后上报本次模型输出的 token 数（completion）。 */
    public synchronized void onTokensUsed(long tokens) {
        if (tokens <= 0) {
            return;
        }
        ensureInit();
        this.config.totalTokens += tokens;

        long want = this.config.totalTokens / TOKENS_PER_HP;
        int added = 0;
        while (this.lastLossCount < want) {
            this.lastLossCount++;
            added++;
        }
        if (added > 0) {
            this.pendingCues = Math.min(this.pendingCues + added, 200);
            this.config.hpLossCount = this.lastLossCount;
            this.config.hp = Math.max(0, this.config.maxHp - (int) this.lastLossCount);
        }
        this.config.save();
    }

    /** 清零累计 token 与扣血进度（设置里的「重置」）。 */
    public void resetTokens() {
        this.config.totalTokens = 0;
        this.config.hpLossCount = 0;
        this.config.hp = this.config.maxHp;
        this.lastLossCount = 0;
        this.pendingCues = 0;
        this.config.save();
    }

    // ------------------------------------------------------------------
    // 余额轮询（仅余额模式）
    // ------------------------------------------------------------------

    private void tickBalance(double dt) {
        if (this.config.readoutTokenMode) {
            return;
        }
        this.pollTimer -= dt;
        if (this.pollTimer > 0 || this.polling) {
            return;
        }
        this.pollTimer = POLL_INTERVAL_SEC;

        String key = this.config.effectiveBalanceKey();
        if (key == null || key.isBlank()) {
            this.balanceError = "未配置 Key";
            return;
        }
        this.polling = true;
        BalancePoller.fetch(this.config.balanceUrl, key).whenComplete((balance, error) ->
                MinecraftClient.getInstance().execute(() -> {
                    this.polling = false;
                    if (error != null) {
                        this.balanceError = rootMessage(error);
                        return;
                    }
                    this.balanceError = null;
                    double prev = this.config.lastBalance;
                    this.config.lastBalance = balance;
                    // 余额上升 = 充值 → 掉一个米饭盆（充值玩法，默认闲置）
                    if (prev >= 0 && balance > prev + 1e-6) {
                        spawnRice();
                    }
                    this.config.save();
                }));
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    // ------------------------------------------------------------------
    // 盆
    // ------------------------------------------------------------------

    public boolean spawnRice() {
        double s = riceSize();
        double x = petLeft() + size() + 30;
        double y = -s - 10;
        Bowl bowl = new Bowl(false, x, y, s, s);
        bowl.vx = -40;
        this.bowls.add(bowl);
        return true;
    }

    /** 掉落一个铁盆（测试用；正常流程由吃掉米饭盆触发）。 */
    public void spawnIron() {
        if (hasIron()) {
            return;
        }
        double w = Math.max(24, size() * IRON_LOOSE_W);
        double h = w * PetAssets.IRON_ASPECT;
        Bowl bowl = new Bowl(true, petLeft() + size() + 30, -h - 10, w, h);
        bowl.vx = -40;
        this.bowls.add(bowl);
    }

    /** 米饭盆画成正方形（与原脚本一致，原图上下留白也照旧保留）。 */
    private double riceSize() {
        return Math.max(16, size() * 0.30);
    }

    private boolean hasIron() {
        for (Bowl b : this.bowls) {
            if (b.iron && b.fadeT < 0) {
                return true;
            }
        }
        return false;
    }

    private boolean ironOnHead() {
        return headPot() != null;
    }

    /** 当前扣在头上的铁盆；没有则返回 null。 */
    private Bowl headPot() {
        for (Bowl b : this.bowls) {
            if (b.iron && b.onHead && b.fadeT < 0) {
                return b;
            }
        }
        return null;
    }

    private boolean hasLiveRice() {
        for (Bowl b : this.bowls) {
            if (!b.iron && b.fadeT < 0 && !b.onHead) {
                return true;
            }
        }
        return false;
    }

    private void tickBowls(double dt) {
        boolean anyLock = false;
        for (Bowl b : this.bowls) {
            if (b.locked) {
                anyLock = true;
                break;
            }
        }
        // 只要有一个盆满 10 秒，所有米饭盆一起锁定
        if (!anyLock) {
            for (Bowl b : this.bowls) {
                if (!b.iron && b.fadeT < 0 && !b.onHead && b.wait >= BOWL_WAIT_SEC) {
                    anyLock = true;
                    break;
                }
            }
        }
        this.lockAll = anyLock;
        if (anyLock) {
            for (Bowl b : this.bowls) {
                if (!b.iron && b.fadeT < 0 && !b.onHead) {
                    b.locked = true;
                }
            }
        }

        for (int i = this.bowls.size() - 1; i >= 0; i--) {
            Bowl b = this.bowls.get(i);
            if (b.fadeT >= 0) {
                b.fadeT += dt;
                if (b.fadeT > 0.5) {
                    this.bowls.remove(i);
                }
                continue;
            }
            if (b.onHead) {
                layoutIronOnHead(b);
                continue;
            }
            if (b == this.dragged) {
                continue;
            }

            b.wait += dt;

            if (b.locked) {
                b.lockT += dt;
                if (!b.iron && b.lockT >= LOCK_DELAY_SEC) {
                    suck(b, dt);
                }
                continue;
            }
            if (b.sucked) {
                suck(b, dt);
                continue;
            }
            physics(b, dt);
        }
    }

    /**
     * 铁盆扣在头上：换成"头上尺寸"（0.7391 倍立绘宽，比散落时大），并跟着立绘的压扁一起动。
     * 原脚本里盆沿（底边）固定在头部锚点，盆身挂在上方。
     */
    private void layoutIronOnHead(Bowl b) {
        double squash = squashScale();
        double anchor = petTop() + size();
        double w = Math.max(24, size() * IRON_HEAD_W);
        // 保持盆体本身的高宽比，只做等比放大，不拉伸变形
        double h = Math.max(10, w * PetAssets.IRON_ASPECT) * squash;
        double rim = anchor + (ironRimY() - anchor) * squash;
        b.w = w;
        b.h = h;
        b.x = ironHeadCenterX() - w / 2.0;
        b.y = rim - h;
    }

    /** 铁盆扣头时盆沿中心的 x（= 头部中心 x，原脚本测量值）。 */
    public double ironHeadCenterX() {
        return headX();
    }

    /** 头盆倾斜角（度），渲染用。 */
    public static double ironHeadTiltDeg() {
        return IRON_HEAD_TILT_DEG;
    }

    private void suck(Bowl b, double dt) {
        double dx = bodyX() - b.centerX();
        double dy = bodyY() - b.centerY();
        double dist = Math.max(1e-3, Math.hypot(dx, dy));
        b.suckSpeed = Math.min(SUCK_MAX_SPEED, b.suckSpeed + SUCK_ACCEL * dt);
        b.vx = dx / dist * b.suckSpeed;
        b.vy = dy / dist * b.suckSpeed;
        b.x += b.vx * dt;
        b.y += b.vy * dt;
        if (dist < Math.max(b.w, b.h) * 0.6) {
            eat(b);
        }
    }

    private void physics(Bowl b, double dt) {
        b.vy += GRAVITY * dt;
        b.x += b.vx * dt;
        b.y += b.vy * dt;

        double floorY = this.screenH - b.h - 4;
        if (b.y > floorY) {
            b.y = floorY;
            if (Math.abs(b.vy) > 60) {
                b.vy = -b.vy * 0.45;
            } else {
                b.vy = 0;
            }
            b.vx *= 0.8;
        }
        // 米饭盆撞到扣在头上的铁盆：从盆的右侧弹开，向右飞出去
        if (!b.iron) {
            Bowl pot = headPot();
            if (pot != null && overlaps(b, pot)) {
                b.x = pot.x + pot.w + 1;
                b.vx = Math.max(160.0, Math.abs(b.vx)) + 140.0;
                b.vy = -180.0;
            }
        }
        if (b.x < 2) {
            b.x = 2;
            b.vx = Math.abs(b.vx) * 0.5;
        }
        double rightLimit = this.screenW - b.w - 2;
        if (b.x > rightLimit) {
            b.x = rightLimit;
            b.vx = -Math.abs(b.vx) * 0.5;
        }
        if (b.y < -b.h * 4) {
            b.y = -b.h * 4;
            b.vy = 0;
        }
    }

    /** 两个盆的矩形是否相交。 */
    private static boolean overlaps(Bowl a, Bowl b) {
        return a.x < b.x + b.w && a.x + a.w > b.x && a.y < b.y + b.h && a.y + a.h > b.y;
    }

    private void eat(Bowl b) {
        this.bowls.remove(b);
        this.happyT = HAPPY_HOLD_SEC;
        play(PetSounds.FEED);
        addHeart(b.centerX(), b.y);
        // 吃掉米饭盆掉一个铁盆，同时只允许一个
        if (!hasIron()) {
            double w = Math.max(24, size() * IRON_LOOSE_W);
            double h = w * PetAssets.IRON_ASPECT;
            Bowl iron = new Bowl(true, b.centerX() - w / 2.0, b.y + b.h - h, w, h);
            iron.vy = -80;
            this.bowls.add(iron);
        }
        if (this.dragged == b) {
            this.dragged = null;
            this.dragging = false;
        }
    }

    // ------------------------------------------------------------------
    // 鼠标交互
    // ------------------------------------------------------------------

    /** @return true 表示事件已被宠物消费。 */
    public boolean mousePressed(double mx, double my, int button) {
        if (!visible() || button != 0) {
            return false;
        }
        for (int i = this.bowls.size() - 1; i >= 0; i--) {
            Bowl b = this.bowls.get(i);
            if (b.fadeT >= 0 || b.onHead) {
                continue;
            }
            if (hitsBowl(mx, my, b)) {
                this.dragging = true;
                this.dragged = b;
                this.dragOffX = mx - b.x;
                this.dragOffY = my - b.y;
                b.vx = 0;
                b.vy = 0;
                b.wait = 0;
                return true;
            }
        }
        if (hitsHead(mx, my) && ironOnHead()) {
            long now = System.currentTimeMillis();
            boolean quick = now - this.lastHeadClickMs <= DOUBLE_CLICK_MS
                    && Math.abs(mx - this.lastHeadClickX) <= DOUBLE_CLICK_SLOP
                    && Math.abs(my - this.lastHeadClickY) <= DOUBLE_CLICK_SLOP;
            if (quick) {
                this.lastHeadClickMs = -100000L;
                knockIronOff();
            } else {
                this.lastHeadClickMs = now;
                this.lastHeadClickX = mx;
                this.lastHeadClickY = my;
            }
            return true;
        }
        return false;
    }

    public boolean mouseDragged(double mx, double my) {
        if (!this.dragging || this.dragged == null) {
            return false;
        }
        this.dragged.x = mx - this.dragOffX;
        this.dragged.y = my - this.dragOffY;
        return true;
    }

    public boolean mouseReleased(double mx, double my) {
        if (!this.dragging || this.dragged == null) {
            return false;
        }
        Bowl b = this.dragged;
        this.dragging = false;
        this.dragged = null;

        if (b.iron) {
            // 判定的是"盆沿"（底边中点）而不是鼠标点，手感更接近原脚本
            if (overHead(mx, my + b.h / 2.0)) {
                b.onHead = true;
                b.vx = 0;
                b.vy = 0;
                b.locked = false;
                layoutIronOnHead(b);
                this.headSquashT = 0.5;
            }
            return true;
        }
        // 米饭盆放到扣在头上的铁盆上 → 从右侧弹开，而不是被吃掉
        Bowl pot = headPot();
        if (pot != null && overlaps(b, pot)) {
            b.x = pot.x + pot.w + 1;
            b.vx = Math.max(160.0, Math.abs(b.vx)) + 140.0;
            b.vy = -180.0;
            return true;
        }
        if (overBody(mx, my)) {
            eat(b);
            return true;
        }
        b.vy = 60;
        return true;
    }

    private void knockIronOff() {
        boolean any = false;
        for (Bowl b : this.bowls) {
            if (b.iron && b.onHead && b.fadeT < 0) {
                b.onHead = false;
                b.fadeT = 1e-6;
                any = true;
            }
        }
        if (any) {
            this.headSquashT = 0.5;
        }
    }

    private boolean hitsBowl(double mx, double my, Bowl b) {
        return mx >= b.x && mx <= b.x + b.w && my >= b.y && my <= b.y + b.h;
    }

    private boolean hitsHead(double mx, double my) {
        return overHead(mx, my);
    }

    private boolean overHead(double mx, double my) {
        double halfW = size() * 0.26;
        double halfH = size() * 0.18;
        return Math.abs(mx - headX()) <= halfW && Math.abs(my - headY()) <= halfH;
    }

    private boolean overBody(double mx, double my) {
        double rx = size() * 0.34;
        double ry = size() * 0.46;
        double dx = (mx - bodyX()) / rx;
        double dy = (my - bodyY()) / ry;
        return dx * dx + dy * dy <= 1.0;
    }

    // ------------------------------------------------------------------
    // 音效 / 飘字
    // ------------------------------------------------------------------

    private void play(net.minecraft.sound.SoundEvent event) {
        if (!this.config.soundEnabled) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        client.getSoundManager().play(PositionedSoundInstance.master(event, 1.0F,
                PetConfig.SOUND_VOLUME));
    }

    private void addFloater(String text, int color, double x, double y) {
        this.floaters.add(new Floater(text, color, x, y));
    }

    private void addHeart(double x, double y) {
        addFloater("heart", 0xFFFF6B9A, x, y);
    }

    /** 测试用：立刻触发一次扣血动画（不改真实数据）。 */
    public void debugCue() {
        fireCue();
    }

    // ------------------------------------------------------------------
    // 数据结构
    // ------------------------------------------------------------------

    /** 米饭盆 / 铁盆。(x, y) 是贴图左上角，铁盆是宽浅的，所以宽高分开存。 */
    public static final class Bowl {
        public final boolean iron;
        public double x;
        public double y;
        public double w;
        public double h;
        public double vx;
        public double vy;
        public boolean onHead;
        public boolean locked;
        boolean sucked;
        double lockT;
        double wait;
        double suckSpeed;
        /** >= 0 表示正在渐隐消失。 */
        public double fadeT = -1;

        Bowl(boolean iron, double x, double y, double w, double h) {
            this.iron = iron;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        public double centerX() {
            return this.x + this.w / 2;
        }

        public double centerY() {
            return this.y + this.h / 2;
        }

        public float alpha() {
            if (this.fadeT < 0) {
                return 1.0F;
            }
            return (float) Math.max(0.0, 1.0 - this.fadeT / 0.5);
        }
    }

    /** 头顶飘字（扣血红字 / 喂食爱心）。 */
    public static final class Floater {
        public final String text;
        public final int color;
        public double x;
        public double y;
        public final double vy = -26.0;
        public final double life = 1.0;
        public double age;

        Floater(String text, int color, double x, double y) {
            this.text = text;
            this.color = color;
            this.x = x;
            this.y = y;
        }

        public float alpha() {
            return (float) Math.max(0.0, 1.0 - this.age / this.life);
        }
    }
}
