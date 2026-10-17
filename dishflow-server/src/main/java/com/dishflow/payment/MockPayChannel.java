package com.dishflow.payment;

import com.dishflow.entity.Orders;
import com.dishflow.vo.OrderPaymentVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 本地演示用的模拟支付渠道：调用即扣款成功，不依赖微信商户号。
 * <p>
 * 默认启用（matchIfMissing = true），也就是说 yml 里不写 sky.pay.channel 时走的就是这里，
 * 避免误配置成 wechat 又缺商户证书导致下单支付直接报错。
 * <p>
 * 返回的 signType 固定为 "MOCK"，小程序端据此跳过 wx.requestPayment，直接进支付成功页
 * （已在 mp-weixin/pages/pay/index.js 的 handleSave 里做了对应判断）。
 */
@Component
@ConditionalOnProperty(name = "sky.pay.channel", havingValue = "mock", matchIfMissing = true)
@Slf4j
public class MockPayChannel implements PayChannel {

    /**
     * 给小程序识别用的标记
     */
    public static final String MOCK_SIGN_TYPE = "MOCK";

    @Override
    public PayResult pay(Orders orders) {
        log.info("模拟支付渠道发起支付，订单号：{}，金额：{}，支付方式：{}",
                orders.getNumber(), orders.getAmount(), orders.getPayMethod());

        long now = System.currentTimeMillis();
        OrderPaymentVO clientParams = OrderPaymentVO.builder()
                .timeStamp(String.valueOf(now / 1000))
                .nonceStr("mock" + now)
                .packageStr("prepay_id=mock")
                .signType(MOCK_SIGN_TYPE)
                .paySign("MOCK_SIGN")
                .build();

        return PayResult.builder()
                .clientParams(clientParams)
                // 同步扣款成功，业务层可以立刻把订单推进到"待接单"
                .settled(true)
                .transactionId("MOCKTXN" + now)
                .build();
    }
}
