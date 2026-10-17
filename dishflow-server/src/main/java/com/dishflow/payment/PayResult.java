package com.dishflow.payment;

import com.dishflow.vo.OrderPaymentVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 支付渠道的返回结果。
 * <p>
 * 之所以要把"给前端的支付参数"和"是否已扣款"分开返回，是因为两种渠道的时序不同：
 * 微信是异步的 —— 先返回签名参数让小程序拉起收银台，扣款结果由微信服务器异步回调通知；
 * mock 是同步的 —— 调用即视为扣款成功，业务层可以立刻推进订单状态。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayResult implements Serializable {

    /**
     * 返回给小程序、用于 wx.requestPayment 的参数（mock 渠道为占位值）
     */
    private OrderPaymentVO clientParams;

    /**
     * 本次调用是否已经完成扣款
     */
    private boolean settled;

    /**
     * settled=true 时的交易流水号；微信渠道由异步回调带回来，此处为 null
     */
    private String transactionId;
}
