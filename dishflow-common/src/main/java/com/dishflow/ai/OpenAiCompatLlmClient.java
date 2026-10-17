package com.dishflow.ai;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.dishflow.properties.AiProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * OpenAI 兼容协议的 LLM 客户端。
 * <p>
 * 为什么自己写而不用 Spring AI：本服务在 Spring Boot 2.7（javax 命名空间），
 * 而 Spring AI 1.x 要求 Spring Boot 3.4/3.5、2.x 要求 Boot 4，全系列要求 JDK 17+；
 * 为一个"单次结构化补全"去做 Boot 2→3 的 jakarta 全量迁移，成本远大于收益。
 * DeepSeek / 通义 / Kimi / 智谱都提供 OpenAI 兼容端点，所以这里一份实现即可通吃，
 * 换供应商只需改 base-url、model、api-key 三行配置。
 * <p>
 * 注意本类不抛业务异常给上层调用方以外的地方：所有失败都收敛成 AiException，
 * 由 AiOrderServiceImpl 决定重试还是降级。
 */
@Slf4j
public class OpenAiCompatLlmClient implements LlmClient {

    private final AiProperties properties;
    private final String endpoint;
    private final CloseableHttpClient httpClient;

    public OpenAiCompatLlmClient(AiProperties properties) {
        this.properties = properties;
        this.endpoint = resolveEndpoint(properties.getBaseUrl());

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(properties.getTimeoutMs())
                .setConnectionRequestTimeout(properties.getTimeoutMs())
                .setSocketTimeout(properties.getTimeoutMs())
                .build();
        // 连接池由 HttpClient 内部管理，超时后 socket 会被强制中断，不会无限等待
        this.httpClient = HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .setMaxConnTotal(20)
                .setMaxConnPerRoute(10)
                .build();
    }

    /**
     * base-url 允许写成 https://api.deepseek.com 或 https://api.deepseek.com/v1 两种形式，
     * 两种形式下 chat/completions 都是直接拼接（DeepSeek 的扁平端点和 /v1 端点都可用）。
     */
    private static String resolveEndpoint(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/chat/completions";
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        if (properties.getApiKey() == null || properties.getApiKey().trim().isEmpty()) {
            throw new AiException(AiException.Kind.NOT_CONFIGURED,
                    "sky.ai.provider=deepseek 但 sky.ai.api-key 未配置");
        }

        long begin = System.currentTimeMillis();
        String body = buildRequestBody(request);

        HttpPost post = new HttpPost(endpoint);
        post.setHeader("Content-Type", "application/json");
        post.setHeader("Authorization", "Bearer " + properties.getApiKey());
        post.setEntity(new StringEntity(body, StandardCharsets.UTF_8));
        // 允许调用方按场景覆盖延迟预算（下单解析要快，工作台洞察可以慢一点）
        if (request.getTimeoutMs() != null && request.getTimeoutMs() != properties.getTimeoutMs()) {
            post.setConfig(RequestConfig.custom()
                    .setConnectTimeout(request.getTimeoutMs())
                    .setConnectionRequestTimeout(request.getTimeoutMs())
                    .setSocketTimeout(request.getTimeoutMs())
                    .build());
        }

        try (CloseableHttpResponse response = httpClient.execute(post)) {
            int status = response.getStatusLine().getStatusCode();
            String text = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);

            if (status >= 500) {
                throw new AiException(AiException.Kind.SERVER_ERROR, "模型服务返回 " + status);
            }
            if (status >= 400) {
                // 401 key 错 / 402 余额不足 / 429 限流 / 400 参数错：重试没有意义
                throw new AiException(AiException.Kind.CLIENT_ERROR,
                        "模型服务返回 " + status + "：" + abbreviate(text, 200));
            }

            LlmResponse result = parseResponse(text);
            result.setLatencyMs(System.currentTimeMillis() - begin);
            return result;

        } catch (SocketTimeoutException e) {
            throw new AiException(AiException.Kind.TIMEOUT,
                    "调用超时 " + properties.getTimeoutMs() + "ms", e);
        } catch (AiException e) {
            throw e;
        } catch (IOException e) {
            throw new AiException(AiException.Kind.NETWORK, "网络异常：" + e.getMessage(), e);
        }
    }

    private String buildRequestBody(LlmRequest request) {
        JSONObject payload = new JSONObject();
        payload.put("model", request.getModel() == null ? properties.getModel() : request.getModel());
        payload.put("temperature", 0);
        payload.put("stream", false);
        payload.put("max_tokens",
                request.getMaxTokens() == null ? properties.getMaxTokens() : request.getMaxTokens());

        // 深度思考对"结构化抽取"是纯浪费：实测同一请求 thinking 开启时 completion_tokens 159、
        // 关闭后 30，延迟从 0.9s 降到 0.69s，所以默认关掉。
        if (properties.isDisableThinking()) {
            JSONObject thinking = new JSONObject();
            thinking.put("type", "disabled");
            payload.put("thinking", thinking);
        }
        if (request.isJsonMode()) {
            JSONObject format = new JSONObject();
            format.put("type", "json_object");
            payload.put("response_format", format);
        }

        JSONArray messages = new JSONArray();
        messages.add(message("system", request.getSystemPrompt()));
        messages.add(message("user", request.getUserContent()));
        payload.put("messages", messages);

        return payload.toJSONString();
    }

    private JSONObject message(String role, String content) {
        JSONObject m = new JSONObject();
        m.put("role", role);
        m.put("content", content == null ? "" : content);
        return m;
    }

    private LlmResponse parseResponse(String text) {
        JSONObject json;
        try {
            json = JSON.parseObject(text);
        } catch (Exception e) {
            throw new AiException(AiException.Kind.BAD_RESPONSE, "响应不是合法 JSON：" + abbreviate(text, 200), e);
        }
        JSONArray choices = json == null ? null : json.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            // DeepSeek 出错时返回 {"error":{...}}，没有 choices
            JSONObject error = json == null ? null : json.getJSONObject("error");
            throw new AiException(AiException.Kind.BAD_RESPONSE,
                    "响应缺少 choices" + (error == null ? "" : "：" + error.getString("message")));
        }
        JSONObject message = choices.getJSONObject(0).getJSONObject("message");
        String content = message == null ? null : message.getString("content");
        if (content == null || content.trim().isEmpty()) {
            throw new AiException(AiException.Kind.EMPTY_CONTENT, "模型返回空内容");
        }

        JSONObject usage = json.getJSONObject("usage");
        return LlmResponse.builder()
                .content(content.trim())
                .model(json.getString("model"))
                .finishReason(choices.getJSONObject(0).getString("finish_reason"))
                .promptTokens(usage == null ? null : usage.getInteger("prompt_tokens"))
                .completionTokens(usage == null ? null : usage.getInteger("completion_tokens"))
                .build();
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    @Override
    public String name() {
        return "openai-compat(" + properties.getModel() + ")";
    }
}
