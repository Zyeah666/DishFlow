package com.dishflow.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 一次补全请求的参数。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmRequest implements Serializable {

    /**
     * 业务场景，仅用于：1) MockLlmClient 返回对应场景的假数据；2) ai_call_log 归类统计。
     */
    private String scene;

    /**
     * system 提示词：角色 + 输出契约 + 防注入声明。
     */
    private String systemPrompt;

    /**
     * user 内容：待处理的业务数据，调用方需自行用分隔标签包裹。
     */
    private String userContent;

    /**
     * 是否要求模型返回合法 JSON（OpenAI 兼容协议的 response_format.json_object）。
     */
    private boolean jsonMode;

    /**
     * 为空时取 AiProperties.maxTokens。
     */
    private Integer maxTokens;

    /**
     * 为空时取 AiProperties.model。
     */
    private String model;

    /**
     * 单次调用的延迟预算（毫秒），为空时取 AiProperties.timeoutMs。
     * <p>
     * 不同场景的预算本来就不一样：下单备注解析在用户等结果的关键路径上，必须短（默认 1500ms）；
     * 工作台的经营洞察是独立异步加载的卡片，可以让它多等一会儿，换来更高的成功率。
     */
    private Integer timeoutMs;
}
