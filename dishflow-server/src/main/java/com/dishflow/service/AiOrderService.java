package com.dishflow.service;

import com.dishflow.vo.RemarkIntent;

/**
 * AI 订单辅助能力。
 */
public interface AiOrderService {

    /**
     * 把用户手写的订单备注解析成结构化标签。
     * <p>
     * 契约（很重要）：本方法**永不抛异常**。AI 不可用、超时、返回乱码、配额用尽，
     * 一律返回全 unknown 的空结果。下单是主链路，AI 是增强项，增强项不能拖垮主链路。
     *
     * @param remark 用户输入的原始备注，可能为 null、可能包含注入式指令
     * @return 结构化意图，永不为 null
     */
    RemarkIntent parseRemark(String remark);
}
