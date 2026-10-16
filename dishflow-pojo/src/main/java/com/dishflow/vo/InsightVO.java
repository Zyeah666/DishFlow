package com.dishflow.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 经营洞察返回体。
 * <p>
 * degraded=true 表示这次没能给出建议（AI 关闭/超时/返回非法/出现未提供的数字被整条丢弃），
 * 前端拿到 degraded=true 或 suggestions 为空时直接把这张卡片隐藏，
 * 工作台的其余统计数据不受任何影响。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InsightVO implements Serializable {

    private String begin;

    private String end;

    /**
     * 3 条经营建议，模型原文
     */
    private List<String> suggestions;

    /**
     * 1 条风险提示
     */
    private String risk;

    /**
     * 本次结果是否来自缓存
     */
    private Boolean fromCache;

    /**
     * 是否降级（没有可用建议）
     */
    private boolean degraded;

    /**
     * 降级原因，仅调试用
     */
    private String degradedReason;

    public static InsightVO degraded(String begin, String end, String reason) {
        return InsightVO.builder()
                .begin(begin)
                .end(end)
                .degraded(true)
                .degradedReason(reason)
                .build();
    }
}
