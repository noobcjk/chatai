package com.ai.client.pet;

import net.minecraft.util.Identifier;

/** 宠物用到的贴图资源。 */
public final class PetAssets {

    // 表情差分：11 开心 / 22 紧张 / 12 傲娇 / 21 冷脸
    public static final Identifier EXPR_HAPPY = texture("expression_11");
    public static final Identifier EXPR_NERVOUS = texture("expression_22");
    public static final Identifier EXPR_ALOOF = texture("expression_12");
    public static final Identifier EXPR_CALM = texture("expression_21");

    public static final Identifier RICE = texture("rice");
    public static final Identifier IRON_BOWL = texture("iron_bowl");

    /** 立绘贴图边长（正方形，与源图同为 1024，未再缩放）。 */
    public static final int SPRITE_SIZE = 1024;

    /** 铁盆贴图的高 / 宽（源图内容原尺寸 2811×1000，未缩放、未改比例）。 */
    public static final double IRON_ASPECT = 1000.0 / 2811.0;

    /**
     * 平板黑屏四角在贴图坐标系里的位置，顺序固定 TL, TR, BR, BL。
     * 数值来自原脚本 Relayout()，按 1024 立绘测量（即贴图像素坐标）。
     */
    public static final double[] PAD_QX = { 549.6, 949.72, 984.72, 584.6 };
    public static final double[] PAD_QY = { 706.2, 642.0, 860.2, 924.32 };

    private PetAssets() {
    }

    private static Identifier texture(String name) {
        return new Identifier("chatai", "textures/gui/pet/" + name + ".png");
    }
}
