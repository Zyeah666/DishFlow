package com.dishflow.service;

import com.dishflow.entity.OrderDetail;
import com.dishflow.entity.Orders;
import com.dishflow.entity.ShoppingCart;
import com.dishflow.mapper.OrderDetailMapper;
import com.dishflow.mapper.OrderMapper;
import com.dishflow.mapper.ShoppingCartMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 下单落库的事务边界。
 * <p>
 * 为什么单独拆一个 bean 而不是直接在 OrderServiceImpl.submit 上加 @Transactional：
 * submit 现在需要在写库之前调用大模型解析备注，而这是一次最长 1.5 秒的外部 HTTP 请求。
 * Spring 的事务在方法进入时就已经从 Druid 连接池借出了连接，如果把这 1.5 秒包在事务里，
 * 高并发下单时连接会被长时间空占，连接池耗尽后整个服务（包括与 AI 无关的接口）都会卡死。
 * <p>
 * 所以这里的划分是：
 * OrderServiceImpl.submit = 编排（校验 → 调 AI → 提交事务），不带 @Transactional；
 * 本类 = 纯数据库写，带 @Transactional，方法里不允许出现任何外部调用。
 * <p>
 * 另外注意：@Transactional 依赖 Spring 代理生效，同类内部方法互相调用是不生效的，
 * 这也是把它拆成独立 bean 的第二个原因。
 */
@Service
@Slf4j
public class OrderSubmitTxService {

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderDetailMapper orderDetailMapper;
    @Autowired
    private ShoppingCartMapper shoppingCartMapper;

    /**
     * 在一个事务里完成：写订单主表 → 批量写明细 → 清空购物车。
     * 任何一步失败整体回滚，不会出现"订单有了但明细缺几条"或"购物车被清了这个用户却下不了单"。
     *
     * @param order    已经组装好、尚未落库的订单
     * @param cartList 当前用户购物车内容
     * @return 落库后的订单（含自增主键 id）
     */
    @Transactional
    public Orders createOrder(Orders order, List<ShoppingCart> cartList) {
        orderMapper.insert(order);

        ArrayList<Object> orderDetailList = new ArrayList<>();
        for (ShoppingCart cart : cartList) {
            OrderDetail orderDetail = new OrderDetail();
            BeanUtils.copyProperties(cart, orderDetail);
            orderDetail.setOrderId(order.getId());
            orderDetailList.add(orderDetail);
        }
        orderDetailMapper.insertBatch(orderDetailList);

        shoppingCartMapper.deleteByUserId(order.getUserId());

        log.info("订单落库完成，orderId={}，明细 {} 条，购物车已清空", order.getId(), orderDetailList.size());
        return order;
    }
}
