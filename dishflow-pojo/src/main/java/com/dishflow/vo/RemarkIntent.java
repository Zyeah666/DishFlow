package com.dishflow.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 订单备注的结构化解析结果。
 * <p>
 * 每个枚举字段都保留 "unknown" 这个显式取值，原因是：AI 没识别出来 ≠ 用户没要求，
 * 把"不确定"表达成一个值，才能在评测里统计出"模型有多少次真的没听懂"，
 * 而不是偷偷给一个默认值让商家以为没问题。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RemarkIntent implements Serializable {

    public static final String UNKNOWN = "unknown";

    public static final String SPICY_NONE = "none";
    public static final String SPICY_LESS = "less";
    public static final String SPICY_NORMAL = "normal";
    public static final String SPICY_MORE = "more";

    public static final String UTENSIL_NEED = "need";
    public static final String UTENSIL_SKIP = "skip";
    public static final String UTENSIL_NONE = "none";

    public static final String DELIVERY_FRONT_DESK = "front_desk";
    public static final String DELIVERY_PICKUP = "pickup";
    public static final String DELIVERY_CALL = "call";
    public static final String DELIVERY_KNOCK = "knock";

    /**
     * 辣度：none|less|normal|more|unknown
     */
    @Builder.Default
    private String spicy = UNKNOWN;

    /**
     * 要求去掉的食材，空列表表示"用户没提"
     */
    @Builder.Default
    private List<String> noIngredients = new ArrayList<>();

    /**
     * 餐具需求：need|skip|none|unknown
     */
    @Builder.Default
    private String utensil = UNKNOWN;

    /**
     * 配送说明：front_desk|pickup|call|knock|unknown
     */
    @Builder.Default
    private String deliveryNote = UNKNOWN;

    /**
     * 是否加急
     */
    @Builder.Default
    private boolean urgent = false;

    /**
     * 降级时返回的"什么都没有"的实例：全 unknown，前端就不显示 AI 标签，
     * 但订单照常创建，商家仍然能看到原始备注文本。
     */
    public static RemarkIntent empty() {
        return RemarkIntent.builder().build();
    }

    /**
     * 是否解析出了任何有效信息（用于决定是否落库、是否算作一次成功识别）。
     */
    public boolean hasAnyHint() {
        return !UNKNOWN.equals(spicy)
                || (noIngredients != null && !noIngredients.isEmpty())
                || !UNKNOWN.equals(utensil)
                || !UNKNOWN.equals(deliveryNote)
                || urgent;
    }
}
