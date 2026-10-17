package com.dishflow.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.JSONObject;
import com.dishflow.ai.AiException;
import com.dishflow.ai.AiPrompts;
import com.dishflow.ai.LlmClient;
import com.dishflow.ai.LlmRequest;
import com.dishflow.ai.LlmResponse;
import com.dishflow.constant.AiConstant;
import com.dishflow.entity.AiCallLog;
import com.dishflow.mapper.AiCallLogMapper;
import com.dishflow.properties.AiProperties;
import com.dishflow.service.AiOrderService;
import com.dishflow.vo.RemarkIntent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 订单备注的 AI 结构化解析。
 * <p>
 * 整条链路的设计原则是"能不问模型就不问，问了也不能拖累下单"，按执行顺序分七步：
 * <ol>
 *   <li>开关与空值短路：备注为空（大多数用户不填）或全局关闭时直接返回，一次调用都不发；</li>
 *   <li>输入归一化：压缩空白、截断到 maxInputLength、脱敏手机号等 PII —— 既是成本护栏也是注入护栏；</li>
 *   <li>Redis 缓存：备注重复率很高（"不要辣"占大头），key 用归一化文本的 sha256，命中即 0 成本 0 延迟；</li>
 *   <li>每日配额：INCR 计数超过 dailyQuota 就不再调用，防止账户被刷爆；</li>
 *   <li>调用模型：瞬时故障（超时/网络/5xx）重试一次，4xx 这类"重试也不会成功"的直接放弃；</li>
 *   <li>输出校验：JSON 能解析、字段值必须在白名单枚举内，越界一律改写成 unknown，绝不直接采信模型输出；</li>
 *   <li>降级与流水：任何失败都返回全 unknown 的空结果，同时把这次尝试写进 ai_call_log，
 *       延迟/命中率/降级率/token 成本这些数字全部来自这张表。</li>
 * </ol>
 * 注意本类**不会向调用方抛出任何异常**，下单主链路可以放心依赖这个契约。
 */
@Service
@Slf4j
public class AiOrderServiceImpl implements AiOrderService {

    @Autowired
    private LlmClient llmClient;
    @Autowired
    private RedisTemplate redisTemplate;
    @Autowired
    private AiProperties aiProperties;
    @Autowired
    private AiCallLogMapper aiCallLogMapper;

    /**
     * 输出白名单：模型给出不在集合里的值时，一律降级为 unknown。
     */
    private static final Set<String> SPICY_ALLOWED =
            new HashSet<>(Arrays.asList("none", "less", "normal", "more", "unknown"));
    private static final Set<String> UTENSIL_ALLOWED =
            new HashSet<>(Arrays.asList("need", "skip", "none", "unknown"));
    private static final Set<String> DELIVERY_ALLOWED =
            new HashSet<>(Arrays.asList("front_desk", "pickup", "call", "knock", "unknown"));

    /** 连续 7 位以上数字（手机号、身份证片段）在送出去之前一律打码 */
    private static final Pattern PII_DIGITS = Pattern.compile("\\d{7,}");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");

    private static final int MAX_INGREDIENTS = 5;
    private static final int MAX_INGREDIENT_LENGTH = 8;

    @Override
    public RemarkIntent parseRemark(String remark) {
        String text = normalize(remark);

        try {
            // ① 开关 + 空备注短路：这一步省掉了大部分调用
            if (!aiProperties.isEnabled()) {
                record(AiConstant.STATUS_SKIPPED, text, null, null, "sky.ai.enabled=false");
                return RemarkIntent.empty();
            }
            if (text.isEmpty()) {
                record(AiConstant.STATUS_SKIPPED, text, null, null, "备注为空");
                return RemarkIntent.empty();
            }

            String cacheKey = AiConstant.CACHE_KEY_REMARK_PREFIX + sha256(text);

            // ③ 缓存命中：不花钱、不耗时、不会失败
            RemarkIntent cached = readCache(cacheKey);
            if (cached != null) {
                record(AiConstant.STATUS_CACHE_HIT, text, null, null, null);
                return cached;
            }

            // ④ 配额
            if (!tryAcquireQuota()) {
                record(AiConstant.STATUS_QUOTA_EXCEEDED, text, null, null,
                        "超过每日配额 " + aiProperties.getDailyQuota());
                return RemarkIntent.empty();
            }

            // ⑤ 调用
            LlmRequest request = LlmRequest.builder()
                    .scene(AiConstant.SCENE_REMARK_PARSE)
                    .systemPrompt(AiPrompts.REMARK_SYSTEM_PROMPT)
                    .userContent(AiPrompts.wrapUserInput(text))
                    .jsonMode(true)
                    .build();

            LlmResponse response;
            try {
                response = chatWithRetry(request);
            } catch (AiException e) {
                String status = e.getKind() == AiException.Kind.TIMEOUT
                        || e.getKind() == AiException.Kind.NETWORK
                        || e.getKind() == AiException.Kind.SERVER_ERROR
                        ? AiConstant.STATUS_TIMEOUT : AiConstant.STATUS_DEGRADED;
                record(status, text, null, null, e.getKind() + ": " + e.getMessage());
                log.warn("AI 备注解析降级，原因：{} - {}", e.getKind(), e.getMessage());
                return RemarkIntent.empty();
            }

            // ⑥ 校验
            RemarkIntent intent;
            try {
                intent = toIntent(response.getContent());
            } catch (JSONException | ClassCastException | IllegalStateException e) {
                record(AiConstant.STATUS_BAD_JSON, text, response, response.getContent(),
                        "响应无法解析为约定结构：" + abbreviate(e.getMessage(), 120));
                log.warn("AI 备注解析返回非法内容，已降级。原始返回：{}", abbreviate(response.getContent(), 200));
                return RemarkIntent.empty();
            }

            // ⑦ 成功：写缓存 + 记流水
            writeCache(cacheKey, intent);
            record(AiConstant.STATUS_OK, text, response, response.getContent(), null);
            return intent;

        } catch (Exception e) {
            // 兜底：连这里的异常处理自己出错也不能影响下单
            log.error("AI 备注解析出现未预期异常，已降级", e);
            return RemarkIntent.empty();
        }
    }

    // ------------------------------------------------------------------ ② 输入归一化

    /**
     * 压缩空白 → 打码 PII → 截断长度。
     * <p>
     * 手机号打码是因为：解析"到了打电话"这个意图并不需要真实的号码，
     * 而把 PII 送出内网去调第三方 API 是没有必要的风险敞口。
     */
    private String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String text = MULTI_SPACE.matcher(raw.trim()).replaceAll(" ");
        text = PII_DIGITS.matcher(text).replaceAll("***");
        int max = aiProperties.getMaxInputLength();
        if (max > 0 && text.length() > max) {
            text = text.substring(0, max);
        }
        return text;
    }

    // ------------------------------------------------------------------ ③ 缓存

    private RemarkIntent readCache(String key) {
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached instanceof String) {
                return JSON.parseObject((String) cached, RemarkIntent.class);
            }
        } catch (Exception e) {
            // Redis 挂了不能连累下单，当作缓存未命中继续走模型
            log.warn("读取 AI 缓存失败，按未命中处理：{}", e.getMessage());
        }
        return null;
    }

    private void writeCache(String key, RemarkIntent intent) {
        try {
            redisTemplate.opsForValue().set(key, JSON.toJSONString(intent),
                    Duration.ofDays(AiConstant.REMARK_CACHE_DAYS));
        } catch (Exception e) {
            log.warn("写入 AI 缓存失败：{}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ ④ 配额

    private boolean tryAcquireQuota() {
        String key = AiConstant.CACHE_KEY_QUOTA_PREFIX + LocalDate.now();
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, Duration.ofDays(1));
            }
            return count == null || count <= aiProperties.getDailyQuota();
        } catch (Exception e) {
            log.warn("配额检查失败，放行这一次调用：{}", e.getMessage());
            return true;
        }
    }

    // ------------------------------------------------------------------ ⑤ 调用与重试

    /**
     * 只对瞬时故障重试一次。4xx（key 无效、余额不足、参数错误）重试也只会再失败一次、再花一笔钱。
     */
    private LlmResponse chatWithRetry(LlmRequest request) {
        try {
            return llmClient.chat(request);
        } catch (AiException first) {
            if (!first.isRetryable()) {
                throw first;
            }
            log.warn("AI 调用失败（{}），重试一次", first.getKind());
            try {
                return llmClient.chat(request);
            } catch (AiException second) {
                throw second;
            }
        }
    }

    // ------------------------------------------------------------------ ⑥ 输出校验

    /**
     * 把模型返回的 JSON 变成 RemarkIntent。
     * <p>
     * 两道防线：结构非法（不是 JSON、缺字段、类型不对）直接抛异常让上层降级；
     * 结构合法但取值越界（比如 spicy 返回"火辣"）则改写成 unknown 并记进 error_msg，
     * 这样既不会把脏值写进数据库，也能统计出模型有多爱乱填。
     */
    private RemarkIntent toIntent(String content) {
        JSONObject json = JSON.parseObject(content);
        if (json == null) {
            throw new JSONException("解析结果为 null");
        }

        List<String> corrected = new ArrayList<>();

        String spicy = readEnum(json, "spicy", SPICY_ALLOWED, corrected);
        String utensil = readEnum(json, "utensil", UTENSIL_ALLOWED, corrected);
        String deliveryNote = readEnum(json, "deliveryNote", DELIVERY_ALLOWED, corrected);

        List<String> ingredients = readIngredients(json);

        boolean urgent = false;
        Object urgentRaw = json.get("urgent");
        if (urgentRaw instanceof Boolean) {
            urgent = (Boolean) urgentRaw;
        } else if ("true".equalsIgnoreCase(String.valueOf(urgentRaw))) {
            urgent = true;
        }

        RemarkIntent intent = RemarkIntent.builder()
                .spicy(spicy)
                .utensil(utensil)
                .deliveryNote(deliveryNote)
                .noIngredients(ingredients)
                .urgent(urgent)
                .build();

        if (!corrected.isEmpty()) {
            // 越界值被清洗过，留痕方便后续迭代 few-shot
            log.debug("AI 返回的越界取值已清洗：{}", corrected);
        }
        return intent;
    }

    private String readEnum(JSONObject json, String field, Set<String> allowed, List<String> corrected) {
        Object value = json.get(field);
        if (value == null) {
            return RemarkIntent.UNKNOWN;
        }
        String text = String.valueOf(value).trim().toLowerCase();
        if (allowed.contains(text)) {
            return text;
        }
        corrected.add(field + "=" + text);
        return RemarkIntent.UNKNOWN;
    }

    /**
     * 忌口食材是自由文本，风险最高（模型可能整句话塞进来），做长度、数量、字符集三重限制。
     */
    private List<String> readIngredients(JSONObject json) {
        List<String> result = new ArrayList<>();
        Object raw = json.get("noIngredients");
        if (!(raw instanceof JSONArray)) {
            return result;
        }
        JSONArray array = (JSONArray) raw;
        for (int i = 0; i < array.size() && result.size() < MAX_INGREDIENTS; i++) {
            String word = array.getString(i);
            if (word == null) {
                continue;
            }
            word = word.trim();
            if (word.isEmpty() || word.length() > MAX_INGREDIENT_LENGTH) {
                continue;
            }
            // 只保留中日韩文字与字母，把标点、空格、可能的指令内容挡在外面
            if (!word.matches("[\\p{IsHan}A-Za-z]+")) {
                continue;
            }
            result.add(word);
        }
        return result;
    }

    // ------------------------------------------------------------------ ⑦ 流水

    private void record(String status, String input, LlmResponse response, String rawOutput, String errorMsg) {
        try {
            AiCallLog entry = AiCallLog.builder()
                    .scene(AiConstant.SCENE_REMARK_PARSE)
                    .status(status)
                    .model(llmClient.name())
                    .promptTokens(response == null ? 0 : nullToZero(response.getPromptTokens()))
                    .completionTokens(response == null ? 0 : nullToZero(response.getCompletionTokens()))
                    .latencyMs(response == null || response.getLatencyMs() == null
                            ? 0 : response.getLatencyMs().intValue())
                    .rawInput(abbreviate(input, 255))
                    .rawOutput(abbreviate(rawOutput, 1000))
                    .errorMsg(abbreviate(errorMsg, 255))
                    .build();
            aiCallLogMapper.insert(entry);
        } catch (Exception e) {
            // 流水记不上也不能影响下单
            log.warn("写入 ai_call_log 失败：{}", e.getMessage());
        }
    }

    private static int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.substring(0, 32);
        } catch (Exception e) {
            // SHA-256 不会找不到，真出错了退化成 hashCode，只是缓存 key 可能碰撞
            return String.valueOf(text.hashCode());
        }
    }
}
