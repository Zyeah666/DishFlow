package com.dishflow.ai;

/**
 * 大模型聊天补全抽象。
 * <p>
 * 设计意图和业务层边界：
 * 1. 业务层（AiOrderServiceImpl / InsightServiceImpl）只依赖本接口，不知道底下是 DeepSeek、
 *    通义还是本地假实现，因此换供应商只改配置不改代码；
 * 2. 本接口不带任何业务语义（没有"备注""标签"这些概念），它只负责"发一次补全请求、拿回一段文本"，
 *    把文本变成结构化对象是业务层的职责；
 * 3. 与项目里已有的 PayChannel / MockPayChannel 是同一套模式：接口抽象 + @ConditionalOnProperty
 *    按配置注入实现 + 默认走无需外部依赖的实现。
 */
public interface LlmClient {

    /**
     * 发起一次对话补全。
     *
     * @param request 提示词与参数
     * @return 模型回复文本 + token 用量 + 本次耗时
     * @throws AiException 网络失败、超时、非 200、响应结构不合法时抛出，由调用方决定重试与降级
     */
    LlmResponse chat(LlmRequest request);

    /**
     * 实现名，写日志和 ai_call_log 用。
     */
    String name();
}
