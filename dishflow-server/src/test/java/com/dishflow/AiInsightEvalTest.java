package com.dishflow;

import com.dishflow.ai.LlmClient;
import com.dishflow.ai.LlmRequest;
import com.dishflow.ai.LlmResponse;
import com.dishflow.service.InsightService;
import com.dishflow.vo.InsightVO;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 场景 C（经营洞察）的评测：核心指标不是"像不像人话"，而是**数值幻觉有没有被拦住**。
 * <p>
 * 两部分：
 * A. 反向验证 —— 故意用一句"不带约束"的提示词去问模型，证明它确实会编数字。
 *    如果这一步编不出数字，那"白名单校验"就是自欺欺人；先证明问题存在，再证明防护有效。
 * B. 正向验证 —— 走真实的 insightService.insight，统计有多少次被数字校验丢弃、多少次通过。
 * <p>
 * 需要真实模型，因此默认不跑：设置系统属性 -Dai.eval=true 才执行（会产生调用费用）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
// 评测目的是观察模型的"完整输出"，所以这里把超时从生产的 1500ms 放宽到 8000ms；
// 线上去掉这个覆盖就会回到"超时就降级"的真实行为（见 AiDegradationTest）。
@TestPropertySource(properties = {"sky.ai.timeout-ms=8000", "sky.ai.max-tokens=400"})
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "AI_EVAL", matches = "true")
@Slf4j
class AiInsightEvalTest {

    @Autowired
    private InsightService insightService;
    @Autowired
    private LlmClient llmClient;
    @Autowired
    private RedisTemplate redisTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");
    private static final String STATS = "{\"turnover\":{\"amounts\":\"2938.5,3120.0,2810.25\"},"
            + "\"order\":{\"totalOrderCount\":128,\"validOrderCount\":121,\"completionRate\":94.53},"
            + "\"range\":\"2026-10-04~2026-10-10\"}";

    @Test
    void evaluateInsight() throws Exception {
        StringBuilder report = new StringBuilder();

        // ---------------- A. 先证明"数值幻觉"是真问题 ----------------
        LlmRequest naked = LlmRequest.builder()
                .scene("insight_probe")
                // 关键：这里故意不给"只能引用输入数字"的约束
                .systemPrompt("分析这组餐饮门店经营数据，给出增长情况和结论。")
                .userContent(STATS)
                .jsonMode(false)
                .maxTokens(300)
                .build();

        LlmResponse nakedResp;
        try {
            nakedResp = llmClient.chat(naked);
        } catch (com.dishflow.ai.AiException e) {
            report.append("A. 对照组本次没跑成（").append(e.getKind()).append("：").append(e.getMessage())
                    .append("），跳过对照部分\n\n");
            nakedResp = null;
        }

        if (nakedResp != null) {
            Set<String> allowed = numbers(STATS);
            Set<String> invented = new LinkedHashSet<>(numbers(nakedResp.getContent()));
            invented.removeAll(allowed);

            report.append("A. 无约束提示词（对照组）：模型输出里出现的、输入中不存在的数字 = ")
                    .append(invented).append('\n');
            report.append("   模型原文：").append(abbrev(nakedResp.getContent(), 300)).append("\n\n");
        }

        // ---------------- B. 走真实链路，统计拦截情况 ----------------
        int runs = 8;
        int ok = 0;
        int rejected = 0;
        int degraded = 0;
        List<String> details = new ArrayList<>();
        // 取基线，避免把上一轮评测的流水算进本轮
        int baseHallucination = countHallucinationBlocked();
        int baseBadJson = countInsightBadJson();

        for (int i = 0; i < runs; i++) {
            // 每次清掉缓存，保证是真实调用而不是命中上一条
            Set<Object> keys = redisTemplate.keys("ai:insight:*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }

            LocalDate end = LocalDate.now();
            InsightVO vo = insightService.insight(end.minusDays(6), end);
            if (vo.isDegraded()) {
                degraded++;
                details.add("  第 " + (i + 1) + " 次：degraded，原因 " + vo.getDegradedReason());
            } else if (vo.getSuggestions() != null && !vo.getSuggestions().isEmpty()) {
                ok++;
                if (details.size() < 3) {
                    details.add("  第 " + (i + 1) + " 次通过：" + String.join(" / ", vo.getSuggestions())
                            + " || 风险：" + vo.getRisk());
                }
            } else {
                degraded++;
            }
        }

        int hallucinationBlocked = countHallucinationBlocked() - baseHallucination;
        int badJsonTotal = countInsightBadJson() - baseBadJson;

        report.append("B. 真实链路 ").append(runs).append(" 次运行：通过 ")
                .append(ok).append(" 次，降级 ").append(degraded).append(" 次\n");
        report.append("   本轮因数值幻觉被白名单拦截 = ").append(hallucinationBlocked)
                .append(" 次（本轮 bad_json 合计 ").append(badJsonTotal).append(" 次）\n");
        for (String d : details) {
            report.append(d).append('\n');
        }

        Files.write(Paths.get("target", "ai-insight-eval.txt"),
                report.toString().getBytes(StandardCharsets.UTF_8));
        log.info("\n{}", report);
    }

    private int countHallucinationBlocked() {
        Integer c = jdbcTemplate.queryForObject(
                "select count(*) from ai_call_log where scene='insight' and status='bad_json' "
                        + "and error_msg like 'number_hallucination%'", Integer.class);
        return c == null ? 0 : c;
    }

    private int countInsightBadJson() {
        Integer c = jdbcTemplate.queryForObject(
                "select count(*) from ai_call_log where scene='insight' and status='bad_json'", Integer.class);
        return c == null ? 0 : c;
    }

    private static Set<String> numbers(String text) {
        Set<String> set = new LinkedHashSet<>();
        if (text == null) {
            return set;
        }
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            set.add(m.group());
        }
        return set;
    }

    private static String abbrev(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "...");
    }
}
