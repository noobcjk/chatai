package com.ai.client.pet;

import net.minecraft.util.Identifier;

/**
 * 四种表情差分。贴图文件名沿用原 DSH 脚本的约定。
 *
 * <p>优先级（高到低）：冷脸（铁盆扣头）→ 紧张（扣血动画）→ 傲娇（米饭盆超时未领）→ 开心（默认）。</p>
 */
public enum PetExpression {
    /** expression_11：默认，开心。 */
    HAPPY(PetAssets.EXPR_HAPPY),
    /** expression_12：米饭盆 10 秒未领取。 */
    ALOOF(PetAssets.EXPR_ALOOF),
    /** expression_21：铁盆扣在头上。 */
    CALM(PetAssets.EXPR_CALM),
    /** expression_22：扣血动画播放中。 */
    NERVOUS(PetAssets.EXPR_NERVOUS);

    private final Identifier texture;

    PetExpression(Identifier texture) {
        this.texture = texture;
    }

    public Identifier texture() {
        return this.texture;
    }
}
