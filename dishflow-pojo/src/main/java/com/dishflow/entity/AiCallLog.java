package com.dishflow.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 调用流水。每一次"要不要调用、调用结果如何、花了多少 token"都记一条。
 * <p>
 * 这张表的用途不是装饰：简历和面试里所有关于延迟、命中率、降级率、成本的数字，
 * 都必须是能从这张表 select 出来的，而不是拍脑袋写的。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiCallLog implements Serializable {

    private Long id;

    /**
     * 场景：remark_parse / insight
     */
    private String scene;

    /**
     * 结果状态：ok / cache_hit / skipped / quota_exceeded / timeout / bad_json / degraded
     */
    private String status;

    /**
     * 实际使用的客户端与模型，例如 openai-compat(deepseek-flash) 或 mock-rule
     */
    private String model;

    private Integer promptTokens;

    private Integer completionTokens;

    /**
     * 本次调用耗时（毫秒）；缓存命中时为 0
     */
    private Integer latencyMs;

    /**
     * 送进去的文本（已截断并脱敏，最长 255）
     */
    private String rawInput;

    /**
     * 模型原始返回（用于事后分析 bad case，最长 1000）
     */
    private String rawOutput;

    private String errorMsg;

    private LocalDateTime createTime;
}
