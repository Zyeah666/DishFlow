package com.dishflow.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
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
import com.dishflow.service.InsightService;
import com.dishflow.service.ReportService;
import com.dishflow.vo.InsightVO;
import com.dishflow.vo.OrderReportVO;
import com.dishflow.vo.SalesTop10ReportVO;
import com.dishflow.vo.TurnoverReportVO;
import com.dishflow.vo.UserReportVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把报表数字变成经营建议。
 * <p>
 * 这个场景的核心风险不是"调不通"，而是**数值幻觉**：大模型很乐意在分析里补一句
 * "营业额同比增长 23%"，而这个 23% 是它编的。所以这里的分工是强制的：
 * <b>所有数字只能来自 SQL 统计，模型只做解释，不做计算</b>；
 * 模型返回后还要做一次"输出数字必须都出现在输入里"的白名单校验，
 * 出现未被告知的数字就判定为幻觉，整条结果丢弃、前端隐藏卡片。
 * <p>
 * 其余链路（开关、缓存、配额、重试、降级、流水）与 AiOrderServiceImpl 一致。
 */
@Service
@Slf4j
public class InsightServiceImpl implements InsightService {

    @Autowired
    private LlmClient llmClient;
    @Autowired
    private ReportService reportService;
    @Autowired
    private RedisTemplate redisTemplate;
    @Autowired
    private AiProperties aiProperties;
    @Autowired
    private AiCallLogMapper aiCallLogMapper;

    /** 提取文本中的数字 token，用于幻觉校验 */
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

    private static final Duration INSIGHT_CACHE_TTL = Duration.ofHours(1);
    private static final int MAX_SUGGESTIONS = 3;
    private static final int MAX_SENTENCE_LENGTH = 60;
    /**
     * 洞察场景的延迟预算。产出一段结论比解析几个标签要慢，1.5 秒实测不够用。
     */
    private static final int INSIGHT_TIMEOUT_MS = 5000;

    @Override
    public InsightVO insight(LocalDate begin, LocalDate end) {
        String beginStr = begin.toString();
        String endStr = end.toString();

        try {
            if (!aiProperties.isEnabled()) {
                return InsightVO.degraded(beginStr, endStr, "sky.ai.enabled=false");
            }

            String cacheKey = "ai:insight:" + beginStr + ":" + endStr;
            InsightVO cached = readCache(cacheKey);
            if (cached != null) {
                record(AiConstant.STATUS_CACHE_HIT, beginStr + "~" + endStr, null, null, null);
                return cached;
            }

            if (!tryAcquireQuota()) {
                record(AiConstant.STATUS_QUOTA_EXCEEDED, beginStr + "~" + endStr, null, null, "超过每日配额");
                return InsightVO.degraded(beginStr, endStr, "quota_exceeded");
            }

            // ① 数字全部由 SQL 算好，作为唯一事实来源
            String statsJson = buildStatsJson(begin, end);

            LlmRequest request = LlmRequest.builder()
                    .scene(AiConstant.SCENE_INSIGHT)
                    .systemPrompt(AiPrompts.INSIGHT_SYSTEM_PROMPT)
                    .userContent(AiPrompts.wrapStats(statsJson))
                    .jsonMode(true)
                    .maxTokens(300)
                    // 洞察是工作台上一张独立异步加载的卡片，可以给模型多等 4 秒；
                    // 下单链路里的备注解析仍然用默认的 1.5 秒硬超时
                    .timeoutMs(INSIGHT_TIMEOUT_MS)
                    .build();

            LlmResponse response;
            try {
                response = chatWithRetry(request);
            } catch (AiException e) {
                record(statusOf(e), statsJson, null, null, e.getKind() + ": " + e.getMessage());
                log.warn("AI 经营洞察降级：{} - {}", e.getKind(), e.getMessage());
                return InsightVO.degraded(beginStr, endStr, String.valueOf(e.getKind()));
            }

            // ② 结构校验 + 数值幻觉校验
            InsightVO vo;
            try {
                vo = toInsight(response.getContent(), statsJson, beginStr, endStr);
            } catch (IllegalArgumentException e) {
                record(AiConstant.STATUS_BAD_JSON, statsJson, response, response.getContent(), e.getMessage());
                log.warn("AI 经营洞察结果不可信，已丢弃：{}", e.getMessage());
                return InsightVO.degraded(beginStr, endStr, e.getMessage());
            }

            vo.setFromCache(false);
            writeCache(cacheKey, vo);
            record(AiConstant.STATUS_OK, statsJson, response, response.getContent(), null);
            return vo;

        } catch (Exception e) {
            log.error("AI 经营洞察出现未预期异常，已降级", e);
            return InsightVO.degraded(beginStr, endStr, "unexpected:" + e.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------ 组装事实数据

    /**
     * 复用已有的四个报表方法，不重复写统计 SQL。
     */
    private String buildStatsJson(LocalDate begin, LocalDate end) {
        TurnoverReportVO turnover = reportService.getTurnover(begin, end);
        UserReportVO user = reportService.getUserStatistics(begin, end);
        OrderReportVO order = reportService.getOrderStatistics(begin, end);
        SalesTop10ReportVO top10 = reportService.getSalesTop10(begin, end);

        JSONObject stats = new JSONObject(true);
        stats.put("range", begin + "~" + end);

        JSONObject turnoverObj = new JSONObject(true);
        turnoverObj.put("dates", turnover.getDateList());
        turnoverObj.put("amounts", turnover.getTurnoverList());
        stats.put("turnover", turnoverObj);

        JSONObject userObj = new JSONObject(true);
        userObj.put("dates", user.getDateList());
        userObj.put("totalUsers", user.getTotalUserList());
        userObj.put("newUsers", user.getNewUserList());
        stats.put("user", userObj);

        JSONObject orderObj = new JSONObject(true);
        orderObj.put("dates", order.getDateList());
        orderObj.put("orderCounts", order.getOrderCountList());
        orderObj.put("validOrderCounts", order.getValidOrderCountList());
        orderObj.put("totalOrderCount", order.getTotalOrderCount());
        orderObj.put("validOrderCount", order.getValidOrderCount());
        orderObj.put("completionRate", order.getOrderCompletionRate());
        stats.put("order", orderObj);

        JSONObject salesObj = new JSONObject(true);
        salesObj.put("names", top10.getNameList());
        salesObj.put("numbers", top10.getNumberList());
        stats.put("salesTop10", salesObj);

        return stats.toJSONString();
    }

    // ------------------------------------------------------------------ 结果校验

    private InsightVO toInsight(String content, String statsJson, String begin, String end) {
        JSONObject json;
        try {
            json = JSON.parseObject(content);
        } catch (Exception e) {
            throw new IllegalArgumentException("bad_json：" + abbreviate(content, 120));
        }
        if (json == null) {
            throw new IllegalArgumentException("bad_json：解析结果为 null");
        }

        List<String> suggestions = new ArrayList<>();
        JSONArray raw = json.getJSONArray("suggestions");
        if (raw != null) {
            for (int i = 0; i < raw.size() && suggestions.size() < MAX_SUGGESTIONS; i++) {
                String s = sanitizeSentence(raw.getString(i));
                if (s != null) {
                    suggestions.add(s);
                }
            }
        }
        String risk = sanitizeSentence(json.getString("risk"));

        if (suggestions.isEmpty() && risk == null) {
            throw new IllegalArgumentException("empty_result：模型没有给出可用结论");
        }

        // 数值幻觉校验：输出里出现的每一个数字，都必须是输入事实里已经存在的
        Set<String> allowed = collectNumbers(statsJson);
        Set<String> used = new LinkedHashSet<>();
        for (String s : suggestions) {
            used.addAll(collectNumbers(s));
        }
        if (risk != null) {
            used.addAll(collectNumbers(risk));
        }
        if (!allowed.containsAll(used)) {
            Set<String> illegal = new HashSet<>(used);
            illegal.removeAll(allowed);
            throw new IllegalArgumentException("number_hallucination：模型引用了未提供的数字 " + illegal);
        }

        return InsightVO.builder()
                .begin(begin)
                .end(end)
                .suggestions(suggestions)
                .risk(risk)
                .degraded(false)
                .build();
    }

    /**
     * 去掉引号、换行和可能的指令残留，并限制长度。
     */
    private String sanitizeSentence(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.replaceAll("[\\r\\n\\t\"']", " ").trim();
        text = text.replaceAll("\\s+", " ");
        if (text.isEmpty()) {
            return null;
        }
        if (text.length() > MAX_SENTENCE_LENGTH) {
            text = text.substring(0, MAX_SENTENCE_LENGTH);
        }
        return text;
    }

    private static Set<String> collectNumbers(String text) {
        Set<String> numbers = new HashSet<>();
        if (text == null) {
            return numbers;
        }
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            numbers.add(m.group());
        }
        return numbers;
    }

    // ------------------------------------------------------------------ 通用链路（与备注解析一致）

    private InsightVO readCache(String key) {
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached instanceof String) {
                InsightVO vo = JSON.parseObject((String) cached, InsightVO.class);
                if (vo != null) {
                    vo.setFromCache(true);
                }
                return vo;
            }
        } catch (Exception e) {
            log.warn("读取洞察缓存失败，按未命中处理：{}", e.getMessage());
        }
        return null;
    }

    private void writeCache(String key, InsightVO vo) {
        try {
            redisTemplate.opsForValue().set(key, JSON.toJSONString(vo), INSIGHT_CACHE_TTL);
        } catch (Exception e) {
            log.warn("写入洞察缓存失败：{}", e.getMessage());
        }
    }

    private boolean tryAcquireQuota() {
        String key = AiConstant.CACHE_KEY_QUOTA_PREFIX + LocalDate.now();
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, Duration.ofDays(1));
            }
            return count == null || count <= aiProperties.getDailyQuota();
        } catch (Exception e) {
            return true;
        }
    }

    private LlmResponse chatWithRetry(LlmRequest request) {
        try {
            return llmClient.chat(request);
        } catch (AiException first) {
            if (!first.isRetryable()) {
                throw first;
            }
            log.warn("AI 洞察调用失败（{}），重试一次", first.getKind());
            return llmClient.chat(request);
        }
    }

    private static String statusOf(AiException e) {
        switch (e.getKind()) {
            case TIMEOUT:
            case NETWORK:
            case SERVER_ERROR:
                return AiConstant.STATUS_TIMEOUT;
            default:
                return AiConstant.STATUS_DEGRADED;
        }
    }

    private void record(String status, String input, LlmResponse response, String rawOutput, String errorMsg) {
        try {
            aiCallLogMapper.insert(AiCallLog.builder()
                    .scene(AiConstant.SCENE_INSIGHT)
                    .status(status)
                    .model(llmClient.name())
                    .promptTokens(response == null ? 0 : response.getPromptTokens())
                    .completionTokens(response == null ? 0 : response.getCompletionTokens())
                    .latencyMs(response == null || response.getLatencyMs() == null
                            ? 0 : response.getLatencyMs().intValue())
                    .rawInput(abbreviate(input, 255))
                    .rawOutput(abbreviate(rawOutput, 1000))
                    .errorMsg(abbreviate(errorMsg, 255))
                    .build());
        } catch (Exception e) {
            log.warn("写入 ai_call_log 失败：{}", e.getMessage());
        }
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
