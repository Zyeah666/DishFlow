package com.dishflow.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 能力配置。
 * <p>
 * provider = mock（默认）时不访问外网，任何环境都能启动和演示；
 * provider = deepseek 时才需要真实 api-key，key 走 application-dev.yml（已被 .gitignore 忽略）。
 */
@Component
@ConfigurationProperties(prefix = "sky.ai")
@Data
public class AiProperties {

    /**
     * 总开关。false 时所有 AI 调用直接短路返回空结果，业务链路完全不受影响。
     */
    private boolean enabled = true;

    /**
     * 具体实现：mock | deepseek（deepseek 走 OpenAI 兼容协议，改 base-url 即可换供应商）
     */
    private String provider = "mock";

    private String baseUrl = "https://api.deepseek.com";

    private String apiKey;

    private String model = "deepseek-flash";

    /**
     * 连接/读取超时（毫秒）。下单链路同步调用，必须给一个短且有界的超时。
     */
    private int timeoutMs = 1500;

    /**
     * 单次回复的最大 token，防止模型开始写文章把费用烧起来。
     */
    private int maxTokens = 200;

    /**
     * 每日调用配额，超了直接降级，不再花钱。
     */
    private int dailyQuota = 2000;

    /**
     * 送给模型的文本最大长度，超长截断（既是成本护栏也是注入护栏）。
     */
    private int maxInputLength = 200;

    /**
     * 是否关闭深度思考。结构化抽取任务不需要推理链，关掉可显著降低耗时与输出 token。
     */
    private boolean disableThinking = true;
}
