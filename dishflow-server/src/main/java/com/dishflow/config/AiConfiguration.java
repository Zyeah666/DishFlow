package com.dishflow.config;

import com.dishflow.ai.LlmClient;
import com.dishflow.ai.MockLlmClient;
import com.dishflow.ai.OpenAiCompatLlmClient;
import com.dishflow.properties.AiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 客户端装配。
 * <p>
 * 与 OssConfiguration、PayChannel 的注入方式保持一致：接口在 sky-common，
 * 具体用哪个实现由 sky.ai.provider 决定，业务代码只注入 LlmClient 接口。
 * <p>
 * 默认（不配置 provider 时）走 MockLlmClient —— 这样刚 clone 下来、还没申请 API key
 * 的人也能直接把项目跑起来，不会出现"缺配置就启动失败"。
 */
@Configuration
@Slf4j
public class AiConfiguration {

    @Bean
    @ConditionalOnProperty(name = "sky.ai.provider", havingValue = "deepseek")
    public LlmClient openAiCompatLlmClient(AiProperties aiProperties) {
        log.info("AI 客户端：OpenAI 兼容协议，base-url={}，model={}，超时={}ms",
                aiProperties.getBaseUrl(), aiProperties.getModel(), aiProperties.getTimeoutMs());
        return new OpenAiCompatLlmClient(aiProperties);
    }

    @Bean
    @ConditionalOnProperty(name = "sky.ai.provider", havingValue = "mock", matchIfMissing = true)
    public LlmClient mockLlmClient() {
        log.info("AI 客户端：使用离线 Mock 实现（关键词规则），不产生任何外部调用与费用");
        return new MockLlmClient();
    }
}
