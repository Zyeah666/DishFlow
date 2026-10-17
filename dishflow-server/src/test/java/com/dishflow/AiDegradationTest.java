package com.dishflow;

import com.dishflow.service.AiOrderService;
import com.dishflow.service.InsightService;
import com.dishflow.vo.InsightVO;
import com.dishflow.vo.RemarkIntent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 不可用时的降级验证。
 * <p>
 * 这组断言是这个模块真正的卖点：接大模型不难，难的是"模型挂了、超时了、返回乱码了，
 * 下单和经营报表不能跟着一起挂"。这里故意把 base-url 指向一个必然连不上的地址、
 * 把超时压到 300ms，模拟线上模型服务不可用的情况，然后要求：
 * 1) 业务方法不抛异常；
 * 2) 返回的是全 unknown 的空标签，而不是 null（调用方不用做判空）；
 * 3) 订单链路和洞察卡片各自按自己的降级契约行事。
 * <p>
 * 不花一分钱：全程不会成功调用到真实模型。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "sky.ai.provider=deepseek",                    // 走真实 HTTP 客户端
        "sky.ai.base-url=http://127.0.0.1:1",          // 但地址必然连不上
        "sky.ai.timeout-ms=300",
        "sky.ai.daily-quota=2000"
})
class AiDegradationTest {

    @Autowired
    private AiOrderService aiOrderService;
    @Autowired
    private InsightService insightService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private RedisTemplate redisTemplate;

    @Test
    @DisplayName("模型服务不可用时，备注解析降级为全 unknown 且不抛异常")
    void degradesWhenModelUnavailable() {
        // 先清掉解析缓存，否则这条备注可能命中上一轮评测留下的结果，测的就不是降级路径了
        clearAiCache();

        RemarkIntent intent = assertDoesNotThrow(() -> aiOrderService.parseRemark("不要辣，放前台"));

        assertNotNull(intent, "降级也必须返回对象，调用方不需要判 null");
        assertEquals(RemarkIntent.UNKNOWN, intent.getSpicy());
        assertEquals(RemarkIntent.UNKNOWN, intent.getUtensil());
        assertEquals(RemarkIntent.UNKNOWN, intent.getDeliveryNote());
        assertEquals(0, intent.getNoIngredients().size());
        assertFalse(intent.isUrgent());
        assertFalse(intent.hasAnyHint(), "降级结果不应该被当成有效标签落库");

        Integer degradedRows = jdbcTemplate.queryForObject(
                "select count(*) from ai_call_log where scene='remark_parse' and status in ('timeout','degraded')",
                Integer.class);
        assertNotNull(degradedRows);
        assertFalse(degradedRows == 0, "降级必须留下流水记录，否则线上无法发现 AI 在静默失败");
    }

    @Test
    @DisplayName("空备注直接短路，不产生模型调用")
    void skipsEmptyRemark() {
        Integer before = countCalls(AiSql.STATUS_REMARK);

        RemarkIntent intent = aiOrderService.parseRemark("   ");
        assertNotNull(intent);
        assertFalse(intent.hasAnyHint());

        Integer after = countCalls(AiSql.STATUS_REMARK);
        // 只会多一条 skipped 流水，不会出现真实调用
        assertEquals(before + 1, after);
        String lastStatus = jdbcTemplate.queryForObject(
                "select status from ai_call_log where scene='remark_parse' order by id desc limit 1", String.class);
        assertEquals("skipped", lastStatus, "空备注应该是 skipped 而不是 timeout，否则说明省调用没生效");
    }

    @Test
    @DisplayName("模型不可用时经营洞察返回 degraded，而不是异常或空白页")
    void insightDegrades() {
        // 洞察结果有 1 小时缓存，先清掉，保证这次走的真的是"调用失败"分支
        clearAiCache();

        LocalDate end = LocalDate.now();
        InsightVO vo = assertDoesNotThrow(() -> insightService.insight(end.minusDays(6), end));

        assertNotNull(vo);
        assertTrue(vo.isDegraded(), "模型连不上时必须是降级，不能装作有结论");
        assertNotNull(vo.getDegradedReason(), "降级要带上原因，方便排查");
    }

    /**
     * 清掉 AI 相关缓存，保证测试打的是真实调用路径而不是命中缓存。
     */
    private void clearAiCache() {
        try {
            for (String pattern : new String[]{"ai:remark:*", "ai:insight:*"}) {
                Set<Object> keys = redisTemplate.keys(pattern);
                if (keys != null && !keys.isEmpty()) {
                    redisTemplate.delete(keys);
                }
            }
        } catch (Exception ignore) {
            // Redis 不可用时测试仍然有意义，只是可能命中缓存
        }
    }

    private Integer countCalls(String scene) {
        Integer c = jdbcTemplate.queryForObject("select count(*) from ai_call_log where scene=?",
                Integer.class, scene);
        return c == null ? 0 : c;
    }

    private static final class AiSql {
        static final String STATUS_REMARK = "remark_parse";
    }
}
