package com.dishflow.payment;

import com.alibaba.fastjson.JSONObject;
import com.dishflow.entity.Orders;
import com.dishflow.entity.User;
import com.dishflow.exception.OrderBusinessException;
import com.dishflow.mapper.UserMapper;
import com.dishflow.utils.WeChatPayUtil;
import com.dishflow.vo.OrderPaymentVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 微信支付渠道（需要商户号 + APIv3 密钥 + 商户证书，个人主体申请不到，默认不启用）。
 * <p>
 * 把 yml 里的 sky.pay.channel 改成 wechat 才会注入这个实现。
 * 注意：微信是异步扣款，这里只负责"生成预支付交易单 + 签名"，
 * 订单状态必须等微信服务器回调 paySuccess(...) 之后才推进，所以 settled 恒为 false。
 */
@Component
@ConditionalOnProperty(name = "sky.pay.channel", havingValue = "wechat")
@Slf4j
public class WeChatPayChannel implements PayChannel {

    @Autowired
    private WeChatPayUtil weChatPayUtil;
    @Autowired
    private UserMapper userMapper;

    @Override
    public PayResult pay(Orders orders) {
        User user = userMapper.getById(orders.getUserId());
        if (user == null || user.getOpenid() == null) {
            throw new OrderBusinessException("当前用户缺少微信 openid，无法调用微信支付");
        }

        try {
            JSONObject json = weChatPayUtil.pay(
                    orders.getNumber(),
                    orders.getAmount(),
                    "外卖-" + orders.getNumber(),
                    user.getOpenid());

            OrderPaymentVO clientParams = OrderPaymentVO.builder()
                    .timeStamp(json.getString("timeStamp"))
                    .nonceStr(json.getString("nonceStr"))
                    .packageStr(json.getString("package"))
                    .signType(json.getString("signType"))
                    .paySign(json.getString("paySign"))
                    .build();

            return PayResult.builder()
                    .clientParams(clientParams)
                    // 异步渠道：此刻还没收到钱，等回调
                    .settled(false)
                    .build();
        } catch (Exception e) {
            log.error("微信支付下单失败", e);
            throw new OrderBusinessException("微信支付不可用：" + e.getMessage());
        }
    }
}
