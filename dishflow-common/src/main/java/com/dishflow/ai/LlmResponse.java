package com.dishflow.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 一次补全的结果。
 * <p>
 * token 用量和耗时是刻意保留的：经营洞察的调用成本、备注解析的 P95 延迟，
 * 都靠这几个字段落进 ai_call_log 后才能算出来，而不是拍脑袋写进简历。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmResponse implements Serializable {

    /**
     * 模型回复正文（已是 choices[0].message.content）
     */
    private String content;

    private String model;

    private String finishReason;

    private Integer promptTokens;

    private Integer completionTokens;

    /**
     * 本次调用耗时（毫秒），由客户端自己掐表，包含网络往返。
     */
    private Long latencyMs;
}
