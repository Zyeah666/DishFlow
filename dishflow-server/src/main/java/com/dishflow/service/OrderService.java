package com.dishflow.service;

import com.dishflow.dto.*;
import com.dishflow.result.PageResult;
import com.dishflow.vo.OrderPaymentVO;
import com.dishflow.vo.OrderStatisticsVO;
import com.dishflow.vo.OrderSubmitVO;
import com.dishflow.vo.OrderVO;

public interface OrderService {
    /**
     * 提交订单
     * @param ordersSubmitDTO
     * @return
     */
    OrderSubmitVO submit(OrdersSubmitDTO ordersSubmitDTO);

    /**
     * 分页查询订单
     * @param page
     * @param pageSize
     * @param status
     * @return
     */
    PageResult page(int page, int pageSize, Integer status);

    /**
     * 根据id查询订单详情
     * @param id
     * @return
     */
    OrderVO details(Long id);

    /**
     * 用户取消订单
     * @param id
     */
    void usercancel(Long id);

    /**
     * 订单确认
     * @param id
     */
    void repetition(Long id);

    /**
     * 条件查询
     * @param ordersPageQueryDTO
     * @return
     */
    PageResult conditionSearch(OrdersPageQueryDTO ordersPageQueryDTO);

    /**
     * 订单统计
     * @return
     */
    OrderStatisticsVO statistics();

    /**
     * 管理端接单
     * @param ordersConfirmDTO
     */
    void confirm(OrdersConfirmDTO ordersConfirmDTO);

    /**
     * 管理端拒单
     * @param ordersRejectionDTO
     */
    void rejection(OrdersRejectionDTO ordersRejectionDTO);

    /**
     * 管理端取消订单
     * @param ordersCancelDTO
     */
    void cancel(OrdersCancelDTO ordersCancelDTO);

    /**
     * 订单派送
     * @param id
     */
    void delivery(Long id);

    /**
     * 订单完成
     * @param id
     */
    void complete(Long id);

    /**
     * 用户发起订单支付
     * @param ordersPaymentDTO
     * @return 给小程序调起支付用的参数
     */
    OrderPaymentVO payment(OrdersPaymentDTO ordersPaymentDTO);

    /**
     * 支付成功处理：把订单从"待付款"推进到"待接单"。
     * mock 渠道由 payment() 同步调用；真实微信由异步回调调用，逻辑只有一份。
     * @param transactionId 第三方交易流水号
     * @param orderId       订单id
     */
    void paySuccess(String transactionId, Long orderId);

    /**
     * 催单提醒
     * @param id
     */
    void reminder(Long id);
}
