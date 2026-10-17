package com.dishflow.service;

import com.dishflow.vo.InsightVO;

import java.time.LocalDate;

/**
 * 经营数据 AI 洞察。
 */
public interface InsightService {

    /**
     * 把已统计好的经营数据交给大模型，让它给出可执行的建议与风险提示。
     * <p>
     * 契约：**永不抛异常**。失败一律返回 degraded=true 的空结果，
     * 工作台的其它卡片照常显示，不会因为 AI 挂掉而白屏。
     *
     * @param begin 统计起始日期（含）
     * @param end   统计结束日期（含）
     */
    InsightVO insight(LocalDate begin, LocalDate end);
}
