package com.dishflow;

import com.dishflow.context.BaseContext;
import com.dishflow.dto.OrdersPaymentDTO;
import com.dishflow.entity.AddressBook;
import com.dishflow.entity.Orders;
import com.dishflow.entity.User;
import com.dishflow.mapper.AddressBookMapper;
import com.dishflow.mapper.OrderMapper;
import com.dishflow.mapper.UserMapper;
import com.dishflow.service.OrderService;
import com.dishflow.vo.OrderPaymentVO;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模拟支付链路的集成测试。
 * <p>
 * 自己插入测试用户和测试订单，跑完在 @AfterEach 里删干净，不留残留数据。
 * 注意：.gitignore 里有 **&#47;test/ ，这个文件默认不会进仓库。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
public class PaymentFlowTest {

    @Autowired
    private OrderService orderService;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private AddressBookMapper addressBookMapper;
    @Autowired
    private com.dishflow.payment.PayChannel payChannel;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;
    private Long orderId;
    private String orderNumber;

    @BeforeEach
    void prepare() {
        String openid = "paymentflowtest_" + System.currentTimeMillis();
        userMapper.insert(User.builder().openid(openid).createTime(LocalDateTime.now()).build());
        userId = userMapper.selectByOpenid(openid).getId();
        // 模拟用户端拦截器解析出的当前登录用户
        BaseContext.setCurrentId(userId);

        // orders.address_book_id 是 NOT NULL，先造一条收货地址
        AddressBook addressBook = AddressBook.builder()
                .userId(userId)
                .consignee("测试收件人")
                .phone("13900000000")
                .sex("1")
                .provinceName("广东省")
                .cityName("深圳市")
                .districtName("南山区")
                .detail("paymentflowtest 测试地址")
                .label("公司")
                .isDefault(0)
                .build();
        addressBookMapper.insert(addressBook);
        Long addressBookId = addressBookMapper.list(AddressBook.builder().userId(userId).build())
                .get(0).getId();

        orderNumber = "PFT" + System.currentTimeMillis();
        Orders order = new Orders();
        order.setNumber(orderNumber);
        order.setUserId(userId);
        order.setAddressBookId(addressBookId);
        order.setStatus(Orders.PENDING_PAYMENT);
        order.setPayStatus(Orders.UN_PAID);
        order.setPayMethod(2);
        order.setAmount(new BigDecimal("29.90"));
        order.setOrderTime(LocalDateTime.now());
        order.setUserName("paymentflowtest");
        order.setPhone("13900000000");
        order.setAddress("测试地址");
        order.setConsignee("测试收件人");
        // 这几个字段在 orders 表里是 NOT NULL，真实下单时由小程序传过来
        order.setRemark("");
        order.setEstimatedDeliveryTime(LocalDateTime.now().plusMinutes(30));
        order.setDeliveryStatus(1);
        order.setPackAmount(1);
        order.setTablewareNumber(0);
        order.setTablewareStatus(0);
        orderMapper.insert(order);

        Orders saved = orderMapper.getByNumber(orderNumber);
        assertNotNull(saved, "测试订单应该已经插入");
        orderId = saved.getId();
    }

    /**
     * mock 渠道：发起支付即扣款成功，订单从"待付款"进入"待接单"
     */
    @Test
    void mockPaymentSettlesOrder() {
        // 默认配置 sky.pay.channel=mock，容器里注入的应该就是模拟渠道
        assertTrue(payChannel instanceof com.dishflow.payment.MockPayChannel, "默认应该注入 MockPayChannel");
        assertEquals(Orders.PENDING_PAYMENT, orderMapper.getById(orderId).getStatus());

        OrdersPaymentDTO dto = new OrdersPaymentDTO();
        dto.setOrderNumber(orderNumber);
        dto.setPayMethod(2);

        OrderPaymentVO vo = orderService.payment(dto);
        // 小程序端可以根据 signType 判断要不要调 wx.requestPayment
        assertEquals("MOCK", vo.getSignType());

        Orders after = orderMapper.getById(orderId);
        assertEquals(Orders.TO_BE_CONFIRMED, after.getStatus(), "支付后订单应变为待接单");
        assertEquals(Orders.PAID, after.getPayStatus(), "支付状态应变为已支付");
        assertNotNull(after.getCheckoutTime(), "应记录支付时间");
    }

    /**
     * 支付成功处理必须幂等：微信会重复推送同一个回调
     */
    @Test
    void paySuccessIsIdempotent() {
        orderService.paySuccess("MOCKTXN_FIRST", orderId);
        Orders once = orderMapper.getById(orderId);

        orderService.paySuccess("MOCKTXN_SECOND", orderId);
        Orders twice = orderMapper.getById(orderId);

        assertEquals(once.getStatus(), twice.getStatus());
        assertEquals(once.getCheckoutTime(), twice.getCheckoutTime(), "重复回调不应该改动已经结算的订单");
        assertEquals(Orders.TO_BE_CONFIRMED, twice.getStatus());
    }

    /**
     * 已经支付的订单不能再次支付；别人的订单当作不存在
     */
    @Test
    void rejectRepeatedAndForeignPayment() {
        OrdersPaymentDTO dto = new OrdersPaymentDTO();
        dto.setOrderNumber(orderNumber);
        dto.setPayMethod(2);
        orderService.payment(dto);

        assertThrows(RuntimeException.class, () -> orderService.payment(dto), "重复支付应被拒绝");

        // 换成另一个登录用户，同一个订单号应当按"订单不存在"处理
        BaseContext.setCurrentId(userId + 100000L);
        assertThrows(RuntimeException.class, () -> orderService.payment(dto), "越权支付应被拒绝");
    }

    @AfterEach
    void cleanUp() {
        if (orderId != null) {
            jdbcTemplate.update("delete from order_detail where order_id = ?", orderId);
            jdbcTemplate.update("delete from orders where id = ?", orderId);
        }
        if (userId != null) {
            jdbcTemplate.update("delete from shopping_cart where user_id = ?", userId);
            jdbcTemplate.update("delete from address_book where user_id = ?", userId);
            jdbcTemplate.update("delete from user where id = ?", userId);
        }
        BaseContext.setCurrentId(null);
    }
}
