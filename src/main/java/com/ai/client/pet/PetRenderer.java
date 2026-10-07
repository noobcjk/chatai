package com.ai.client.pet;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.List;

/**
 * 宠物的绘制：立绘 + 平板读数 + 米饭盆 / 铁盆 + 火控雷达名牌 + 飘字。
 *
 * <p>平板读数的文字通过一个仿射矩阵贴在立绘里那块斜着的黑屏上：原脚本给的
 * 四个角在 1024 贴图坐标系里恰好构成平行四边形（TL + TR + BL ≈ BR），所以
 * 用 TL/TR/BL 三个角就能精确地把文字映射上去。</p>
 */
public final class PetRenderer {

    private static final int TEXT_ON_PAD = 0xFF8FE3A0;
    /** 平板读数上方的标题（比读数暗一点）。 */
    private static final int TEXT_DIM_ON_PAD = 0xFF4E8A5C;
    private static final int LABEL_COLOR = 0xFF9AA0AA;
    private static final int RADAR_GREEN = 0xFF39FF6A;
    private static final int RADAR_DIM = 0x8039FF6A;

    private PetRenderer() {
    }

    public static void render(DrawContext context, TextRenderer textRenderer, PetManager pet) {
        if (!pet.visible()) {
            return;
        }
        double s = pet.size() / (double) PetAssets.SPRITE_SIZE;
        double x0 = pet.petLeft();
        double y0 = pet.petTop();
        double shake = pet.shakeOffset();

        renderLabel(context, textRenderer, pet);
        renderSprite(context, textRenderer, pet, x0 + shake, y0);
        // 平板读数必须画在立绘"之上"，否则会被不透明的立绘盖住（之前就是这么丢的）
        renderTablet(context, pet, x0 + shake, y0, s);
        // 盆统一画在立绘之上：原脚本就是先画角色再画盆，铁盆扣头/拖到身上时不该被角色挡住
        renderBowls(context, pet);
        renderFloaters(context, textRenderer, pet);
        renderRadar(context, textRenderer, pet);
    }

    /** 立绘上方一行小字，说明平板当前读数类型。 */
    private static void renderLabel(DrawContext context, TextRenderer textRenderer, PetManager pet) {
        String label = pet.readoutLabel();
        int w = textRenderer.getWidth(label);
        int x = (int) (pet.petLeft() + pet.size() / 2 - w / 2.0);
        int y = (int) (pet.petTop() - 11);
        context.drawTextWithShadow(textRenderer, Text.literal(label), x, y, LABEL_COLOR);
    }

    private static void renderSprite(DrawContext context, TextRenderer textRenderer,
                                     PetManager pet, double x0, double y0) {
        double size = pet.size();
        double squash = pet.squashScale();
        double drawH = size * squash;
        double drawY = y0 + size - drawH;

        double flash = pet.hitFlash();
        // 1.21.4 的 DrawContext 没有 setShaderColor，改为在 drawTexture 上传颜色（ARGB 乘法调制）。
        // 红闪：压低绿 / 蓝通道即可得到偏红的叠加。
        int tint = 0xFFFFFFFF;
        if (flash > 0) {
            int k = (int) (255 * (1.0 - 0.55 * flash));
            tint = 0xFF000000 | (0xFF << 16) | (k << 8) | k;
        }
        // 注意：drawTexture 的 textureWidth/textureHeight 决定采样区域，不是贴图真实尺寸。
        // 只有让 width == textureWidth 时 UV 才是 0~1（完整贴图），否则只会取到左上角一小块。
        int w = Math.max(1, (int) Math.round(size));
        int h = Math.max(1, (int) Math.round(drawH));
        context.drawTexture(RenderLayer::getGuiTextured, pet.expression().texture(),
                (int) Math.round(x0), (int) Math.round(drawY), 0.0F, 0.0F,
                w, h, w, h, tint);
    }

    /** 把读数画到立绘里的平板黑屏上。 */
    private static void renderTablet(DrawContext context, PetManager pet, double x0, double y0, double s) {
        double[] qx = PetAssets.PAD_QX;
        double[] qy = PetAssets.PAD_QY;

        double tlx = x0 + qx[0] * s, tly = y0 + qy[0] * s;
        double trx = x0 + qx[1] * s, trY = y0 + qy[1] * s;
        double blx = x0 + qx[3] * s, bly = y0 + qy[3] * s;

        double ux = trx - tlx, uy = trY - tly;
        double vx = blx - tlx, vy = bly - tly;
        double localW = Math.hypot(ux, uy);
        double localH = Math.hypot(vx, vy);
        if (localW < 4 || localH < 4) {
            return;
        }

        MatrixStack matrices = context.getMatrices();
        matrices.push();
        Matrix4f m = matrices.peek().getPositionMatrix();
        // JOML 里平移量在 m30/m31（第 3 列），不是 m03/m13；写成 m03/m13 会把平移丢进透视行，
        // 文字会被映射到屏幕外，这正是之前平板读数一直不显示的原因。
        // 列向量：col0 = 平板横向边 (ux,uy)，col1 = 平板竖向边 (vx,vy)，col3 = 左上角 (tlx,tly)。
        Matrix4f basis = new Matrix4f();
        basis.m00((float) ux);
        basis.m01((float) uy);
        basis.m10((float) vx);
        basis.m11((float) vy);
        basis.m30((float) tlx);
        basis.m31((float) tly);
        m.mul(basis);
        m.scale((float) (1.0 / localW), (float) (1.0 / localH), 1.0F);

        // 平板上分两行：上面是标题（使用Token / 余额），下面是读数
        String title = pet.readoutTitle();
        String text = pet.readoutText();
        TextRenderer tr = net.minecraft.client.MinecraftClient.getInstance().textRenderer;
        int titleW = Math.max(1, tr.getWidth(title));
        int textW = Math.max(1, tr.getWidth(text));
        int maxW = Math.max(titleW, textW);
        // 两行一起缩放到刚好放得下：既不超过黑屏宽度，也不超过黑屏高度。
        float lineNeed = tr.fontHeight * 2.0F + 2.0F;
        float fontScale = (float) Math.min(1.6, Math.min(localH / lineNeed, (localW - 4.0) / maxW));
        fontScale = Math.max(0.35F, fontScale);
        float lineH = tr.fontHeight * fontScale;
        float titleX = (float) ((localW - titleW * fontScale) / 2.0);
        float titleY = (float) ((localH - lineH * 2.0) / 2.0);
        float textX = (float) ((localW - textW * fontScale) / 2.0);
        float textY = titleY + lineH;
        if (Math.abs(fontScale - 1.0F) > 0.02F) {
            m.scale(fontScale, fontScale, 1.0F);
            titleX /= fontScale;
            titleY /= fontScale;
            textX /= fontScale;
            textY /= fontScale;
        }
        context.drawText(tr, Text.literal(title), Math.round(titleX), Math.round(titleY), TEXT_DIM_ON_PAD, false);
        context.drawText(tr, Text.literal(text), Math.round(textX), Math.round(textY), TEXT_ON_PAD, false);

        matrices.pop();
    }

    private static void renderBowls(DrawContext context, PetManager pet) {
        // 立绘在受击抖动时会水平偏移，扣在头上的盆必须跟着走同一段偏移，
        // 否则盆和"已擦发"的洞口错位，头侧会露出一条世界背景。
        double shake = pet.shakeOffset();
        for (PetManager.Bowl bowl : pet.bowls()) {
            float alpha = bowl.alpha();
            if (alpha <= 0.02F) {
                continue;
            }
            // 渐隐用 drawTexture 的 ARGB 颜色实现（1.21.4 没有 setShaderColor）
            int a = (int) (alpha * 255) & 0xFF;
            int tint = (a << 24) | 0xFFFFFF;
            if (bowl.onHead) {
                MatrixStack matrices = context.getMatrices();
                matrices.push();
                matrices.translate(shake, 0, 0);
                matrices.translate(bowl.centerX(), bowl.centerY(), 0);
                matrices.multiply(new Quaternionf().rotationZ(
                        (float) Math.toRadians(PetManager.ironHeadTiltDeg())));
                matrices.translate(-bowl.centerX(), -bowl.centerY(), 0);
                drawBowlTexture(context, bowl, tint);
                matrices.pop();
            } else {
                drawBowlTexture(context, bowl, tint);
            }
        }
    }

    private static void drawBowlTexture(DrawContext context, PetManager.Bowl bowl, int tint) {
        int w = Math.max(1, (int) Math.round(bowl.w));
        int h = Math.max(1, (int) Math.round(bowl.h));
        context.drawTexture(RenderLayer::getGuiTextured, bowl.iron ? PetAssets.IRON_BOWL : PetAssets.RICE,
                (int) Math.round(bowl.x), (int) Math.round(bowl.y), 0.0F, 0.0F,
                w, h, w, h, tint);
    }

    private static void renderFloaters(DrawContext context, TextRenderer textRenderer, PetManager pet) {
        for (PetManager.Floater f : pet.floaters()) {
            float alpha = f.alpha();
            if (alpha <= 0.02F) {
                continue;
            }
            int a = (int) (alpha * 255) << 24;
            if ("heart".equals(f.text)) {
                drawHeart(context, f.x, f.y, (int) (alpha * 255));
            } else {
                net.minecraft.text.OrderedText ordered = Text.literal(f.text).asOrderedText();
                int w = textRenderer.getWidth(ordered);
                context.drawTextWithShadow(textRenderer, ordered,
                        (int) (f.x - w / 2.0), (int) f.y, a | (f.color & 0xFFFFFF));
            }
        }
    }

    private static void drawHeart(DrawContext context, double cx, double cy, int alpha) {
        int color = (alpha << 24) | 0xFF6B9A;
        int x = (int) cx;
        int y = (int) cy;
        context.fill(x, y + 1, x + 3, y + 3, color);
        context.fill(x + 3, y + 1, x + 6, y + 3, color);
        context.fill(x, y + 3, x + 6, y + 5, color);
        context.fill(x + 1, y + 5, x + 5, y + 6, color);
        context.fill(x + 2, y + 6, x + 4, y + 7, color);
    }

    /** 《战争雷霆》风格目标名牌：绿色方括号 + 距离 / 接近率 / 相对高度 + 方向环。 */
    private static void renderRadar(DrawContext context, TextRenderer textRenderer, PetManager pet) {
        List<PetManager.Bowl> bowls = pet.bowls();
        boolean any = false;
        for (PetManager.Bowl bowl : bowls) {
            if (!bowl.locked || bowl.iron) {
                continue;
            }
            any = true;
            double x1 = bowl.x - 6;
            double y1 = bowl.y - 6;
            double x2 = bowl.x + bowl.w + 6;
            double y2 = bowl.y + bowl.h + 6;
            drawBrackets(context, x1, y1, x2, y2);
            drawReadouts(context, textRenderer, pet, bowl, x2 + 4, y1);
        }
        if (any) {
            drawDirectionRing(context, pet);
        }
    }

    private static void drawBrackets(DrawContext context, double x1, double y1, double x2, double y2) {
        int len = 8;
        // 左上
        context.fill((int) x1, (int) y1, (int) x1 + len, (int) y1 + 1, RADAR_GREEN);
        context.fill((int) x1, (int) y1, (int) x1 + 1, (int) y1 + len, RADAR_GREEN);
        // 右上
        context.fill((int) x2 - len, (int) y1, (int) x2, (int) y1 + 1, RADAR_GREEN);
        context.fill((int) x2 - 1, (int) y1, (int) x2, (int) y1 + len, RADAR_GREEN);
        // 左下
        context.fill((int) x1, (int) y2 - 1, (int) x1 + len, (int) y2, RADAR_GREEN);
        context.fill((int) x1, (int) y2 - len, (int) x1 + 1, (int) y2, RADAR_GREEN);
        // 右下
        context.fill((int) x2 - len, (int) y2 - 1, (int) x2, (int) y2, RADAR_GREEN);
        context.fill((int) x2 - 1, (int) y2 - len, (int) x2, (int) y2, RADAR_GREEN);
    }

    private static void drawReadouts(DrawContext context, TextRenderer textRenderer,
                                     PetManager pet, PetManager.Bowl bowl, double tx, double ty) {
        double dx = bowl.centerX() - pet.bodyX();
        double dy = bowl.centerY() - pet.bodyY();
        double dist = Math.max(1e-3, Math.hypot(dx, dy));
        double closing = -(dx * bowl.vx + dy * bowl.vy) / dist;
        double relAlt = pet.bodyY() - bowl.centerY();

        String[] lines = {
                String.format(java.util.Locale.ROOT, "D  %.2f kpx", dist / 1000.0),
                String.format(java.util.Locale.ROOT, "VC %+.0f px/s", closing),
                String.format(java.util.Locale.ROOT, "H  %+.0f px", relAlt),
        };
        int y = (int) ty;
        for (String line : lines) {
            context.drawTextWithShadow(textRenderer, Text.literal(line), (int) tx, y, RADAR_GREEN);
            y += 10;
        }
    }

    private static void drawDirectionRing(DrawContext context, PetManager pet) {
        double cx = pet.bodyX();
        double cy = pet.bodyY() - pet.size() * 0.72;
        double r = 9;
        for (int i = 0; i < 24; i++) {
            double a = Math.PI * 2 * i / 24.0;
            int px = (int) Math.round(cx + Math.cos(a) * r);
            int py = (int) Math.round(cy + Math.sin(a) * r);
            context.fill(px, py, px + 1, py + 1, RADAR_DIM);
        }
        // 指针指向最近的锁定盆
        double bx = pet.bodyX() + 0;
        double by = 0;
        double best = Double.MAX_VALUE;
        for (PetManager.Bowl bowl : pet.bowls()) {
            if (!bowl.locked || bowl.iron) {
                continue;
            }
            double d = Math.hypot(bowl.centerX() - cx, bowl.centerY() - cy);
            if (d < best) {
                best = d;
                bx = bowl.centerX();
                by = bowl.centerY();
            }
        }
        double ang = Math.atan2(by - cy, bx - cx);
        int nx = (int) Math.round(cx + Math.cos(ang) * r);
        int ny = (int) Math.round(cy + Math.sin(ang) * r);
        drawLine(context, (int) cx, (int) cy, nx, ny, RADAR_GREEN);
    }

    private static void drawLine(DrawContext context, int x1, int y1, int x2, int y2, int color) {
        int dx = Math.abs(x2 - x1);
        int dy = Math.abs(y2 - y1);
        int steps = Math.max(dx, dy);
        if (steps == 0) {
            context.fill(x1, y1, x1 + 1, y1 + 1, color);
            return;
        }
        for (int i = 0; i <= steps; i++) {
            int x = x1 + (x2 - x1) * i / steps;
            int y = y1 + (y2 - y1) * i / steps;
            context.fill(x, y, x + 1, y + 1, color);
        }
    }
}
