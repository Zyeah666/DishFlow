package com.dishflow;

import com.dishflow.payment.PayChannel;
import com.dishflow.payment.WeChatPayChannel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证支付渠道是按配置 sky.pay.channel 在启动时选出来的。
 * <p>
 * 这个类把属性覆盖成 wechat，所以容器里注入到的应该是 WeChatPayChannel；
 * PaymentFlowTest 里没有覆盖（走 application.yml 的 mock），注入到的是 MockPayChannel。
 * 两个类用的是两个不同的 Spring 容器。
 */
@SpringBootTest(
        properties = "sky.pay.channel=wechat",
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class PayChannelSelectionTest {

    @Autowired
    private PayChannel payChannel;

    @Test
    void wechatChannelIsSelectedWhenConfigured() {
        System.out.println("当前注入的支付渠道实现 = " + payChannel.getClass().getName());
        assertTrue(payChannel instanceof WeChatPayChannel, "配置成 wechat 时应该注入微信渠道");
    }
}
