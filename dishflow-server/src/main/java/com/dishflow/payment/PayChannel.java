package com.dishflow.payment;

import com.dishflow.entity.Orders;

/**
 * 支付渠道抽象。
 * <p>
 * 业务层只依赖这个接口，不关心底下是微信还是本地模拟，
 * 通过配置 sky.pay.channel = mock | wechat 决定注入哪个实现。
 */
public interface PayChannel {

    /**
     * 发起支付。
     *
     * @param orders 待支付的订单（状态必须是待付款）
     * @return 支付结果：给小程序的支付参数 + 本次是否已经扣款成功
     */
    PayResult pay(Orders orders);
}
