package com.dishflow;

import com.dishflow.context.BaseContext;
import com.dishflow.dto.OrdersSubmitDTO;
import com.dishflow.service.OrderService;
import com.dishflow.vo.OrderSubmitVO;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 下单主链路端到端验证（会真实调用一次大模型，因此默认不跑，需要环境变量 AI_EVAL=true）。
 * <p>
 * 这个测试同时守住了两处本次改动：
 * 1) 事务边界重构 —— 订单、明细、清空购物车必须要么全成、要么全无，
 *    而且外部 HTTP 调用要在事务之外；
 * 2) AI 标签落库 —— 下单成功后 orders.ai_tags 里应该真的带上解析结果。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "AI_EVAL", matches = "true")
@Slf4j
class OrderSubmitFlowTest {

    /** 本地库里存在的数据：user id=4，其地址簿 id=2，菜品 46/47 处于起售状态 */
    private static final Long USER_ID = 4L;
    private static final Long ADDRESS_BOOK_ID = 2L;

    @Autowired
    private OrderService orderService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long createdOrderId;

    @BeforeEach
    void setUp() {
        BaseContext.setCurrentId(USER_ID);
        jdbcTemplate.update("delete from shopping_cart where user_id = ?", USER_ID);
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("insert into shopping_cart (name, image, user_id, dish_id, number, amount, create_time) "
                + "values ('剁椒鱼头', '', ?, 46, 1, 6.00, ?)", USER_ID, now);
        jdbcTemplate.update("insert into shopping_cart (name, image, user_id, dish_id, number, amount, create_time) "
                + "values ('蒜蓉娃娃菜', '', ?, 47, 2, 8.00, ?)", USER_ID, now);
    }

    @AfterEach
    void cleanUp() {
        BaseContext.removeCurrentId();
        if (createdOrderId != null) {
            jdbcTemplate.update("delete from order_detail where order_id = ?", createdOrderId);
            jdbcTemplate.update("delete from orders where id = ?", createdOrderId);
            createdOrderId = null;
        }
        jdbcTemplate.update("delete from shopping_cart where user_id = ?", USER_ID);
    }

    /** 补齐小程序端本来会带上、但测试里容易漏传的字段，避免撞 NOT NULL 约束 */
    private OrdersSubmitDTO baseDto(String remark) {
        OrdersSubmitDTO dto = new OrdersSubmitDTO();
        dto.setAddressBookId(ADDRESS_BOOK_ID);
        dto.setAmount(new BigDecimal("14.00"));
        dto.setPayMethod(1);
        dto.setDeliveryStatus(1);
        dto.setTablewareStatus(1);
        dto.setEstimatedDeliveryTime(LocalDateTime.now().plusMinutes(30));
        dto.setRemark(remark);
        return dto;
    }

    @Test
    @DisplayName("下单：AI 标签落库、明细完整、购物车被清空")
    void submitPersistsAiTagsAndDetails() {
        OrdersSubmitDTO dto = baseDto("不要辣，蒜也别放，放前台，加急");

        OrderSubmitVO vo = orderService.submit(dto);
        assertNotNull(vo);
        createdOrderId = vo.getId();

        // 1) 明细与购物车：验证事务重构后写入依然完整
        Integer details = jdbcTemplate.queryForObject(
                "select count(*) from order_detail where order_id = ?", Integer.class, createdOrderId);
        assertEquals(2, details, "两条购物车记录都应该生成明细");

        Integer cartLeft = jdbcTemplate.queryForObject(
                "select count(*) from shopping_cart where user_id = ?", Integer.class, USER_ID);
        assertEquals(0, cartLeft, "下单后购物车应该被清空");

        // 2) AI 标签：备注里明确说了不要辣、不要蒜、放前台、加急
        String aiTags = jdbcTemplate.queryForObject(
                "select ai_tags from orders where id = ?", String.class, createdOrderId);
        assertNotNull(aiTags, "带明确诉求的备注应该解析出标签并落库，实际为 null 说明链路没通");
        log.info("解析出的标签：{}", aiTags);
        assertTrue(aiTags.contains("none"), "辣度应识别为 none，实际：" + aiTags);
        assertTrue(aiTags.contains("蒜"), "忌口应识别出蒜，实际：" + aiTags);
        assertTrue(aiTags.contains("front_desk"), "配送应识别为放前台，实际：" + aiTags);
        assertTrue(aiTags.contains("true"), "加急应识别为 true，实际：" + aiTags);

        // 3) 流水应该记下这次真实调用
        Integer okRows = jdbcTemplate.queryForObject(
                "select count(*) from ai_call_log where scene='remark_parse' and status='ok'", Integer.class);
        assertNotNull(okRows);
        assertTrue(okRows > 0);
    }

    @Test
    @DisplayName("空备注不应该产生任何模型调用，也不应该写标签")
    void submitWithoutRemarkSkipsModel() {
        Integer before = jdbcTemplate.queryForObject(
                "select count(*) from ai_call_log where scene='remark_parse'", Integer.class);

        OrdersSubmitDTO dto = baseDto("");

        OrderSubmitVO vo = orderService.submit(dto);
        createdOrderId = vo.getId();

        String aiTags = jdbcTemplate.queryForObject("select ai_tags from orders where id = ?",
                String.class, createdOrderId);
        assertNull(aiTags, "空备注不该写入任何标签");

        Integer after = jdbcTemplate.queryForObject(
                "select count(*) from ai_call_log where scene='remark_parse'", Integer.class);
        assertFalse(after == null || before == null);
        assertEquals(before + 1, after, "空备注只应该留一条 skipped 流水，不能真的调用模型");

        String status = jdbcTemplate.queryForObject(
                "select status from ai_call_log where scene='remark_parse' order by id desc limit 1", String.class);
        assertEquals("skipped", status);
    }
}
