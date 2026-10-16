package com.dishflow.service.impl;

import com.alibaba.fastjson.JSON;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.github.xiaoymin.knife4j.core.util.CollectionUtils;
import com.dishflow.constant.MessageConstant;
import com.dishflow.context.BaseContext;
import com.dishflow.dto.*;
import com.dishflow.entity.AddressBook;
import com.dishflow.entity.OrderDetail;
import com.dishflow.entity.Orders;
import com.dishflow.entity.ShoppingCart;
import com.dishflow.exception.AddressBookBusinessException;
import com.dishflow.exception.OrderBusinessException;
import com.dishflow.exception.ShoppingCartBusinessException;
import com.dishflow.mapper.AddressBookMapper;
import com.dishflow.mapper.OrderDetailMapper;
import com.dishflow.mapper.OrderMapper;
import com.dishflow.mapper.ShoppingCartMapper;
import com.dishflow.payment.PayChannel;
import com.dishflow.payment.PayResult;
import com.dishflow.result.PageResult;
import com.dishflow.service.AiOrderService;
import com.dishflow.service.OrderService;
import com.dishflow.service.OrderSubmitTxService;
import com.dishflow.utils.WeChatPayUtil;
import com.dishflow.vo.OrderPaymentVO;
import com.dishflow.vo.OrderStatisticsVO;
import com.dishflow.vo.OrderSubmitVO;
import com.dishflow.vo.OrderVO;
import com.dishflow.vo.RemarkIntent;
import com.dishflow.websocket.WebSocketServer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class OrderServiceImpl implements OrderService {

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderDetailMapper orderDetailMapper;
    @Autowired
    private ShoppingCartMapper shoppingCartMapper;
    @Autowired
    private AddressBookMapper addressBookMapper;
    /**
     * 支付渠道：由 sky.pay.channel 决定注入 mock 还是微信实现
     */
    @Autowired
    private PayChannel payChannel;
    @Autowired
    private WeChatPayUtil weChatPayUtil;
    /**
     * AI 备注解析：只负责"给一段文本，返回结构化标签"，失败时返回空标签，不影响下单
     */
    @Autowired
    private AiOrderService aiOrderService;
    /**
     * 下单的多表写入 + 事务边界，独立成 bean 是为了让 AI 调用能留在事务之外
     */
    @Autowired
    private OrderSubmitTxService orderSubmitTxService;

    /**
     * 用户下单
     * <p>
     * 本方法故意**不加 @Transactional**：它是编排层，要在写库之前调用大模型解析备注。
     * 事务一开就会占用一个 Druid 连接直到方法返回，而 AI 调用最长要等 1.5 秒，
     * 把它包进事务等于用数据库连接去陪一条外部网络请求，并发稍高连接池就被占空。
     * 真正的多表写入放在 OrderSubmitTxService.createOrder 里，用事务包住。
     *
     * @param ordersSubmitDTO
     * @return
     */
    @Override
    public OrderSubmitVO submit(OrdersSubmitDTO ordersSubmitDTO) {
        //异常判断
        AddressBook addressBook = addressBookMapper.getById(ordersSubmitDTO.getAddressBookId());
        if (addressBook == null) {
            throw new AddressBookBusinessException(MessageConstant.ADDRESS_BOOK_IS_NULL);
        }

        Long userId = BaseContext.getCurrentId();
        ShoppingCart shoppingCart = new ShoppingCart();
        shoppingCart.setUserId(userId);

        List<ShoppingCart> list = shoppingCartMapper.list(shoppingCart);

        if (list == null || list.isEmpty()) {
            throw new ShoppingCartBusinessException(MessageConstant.SHOPPING_CART_IS_NULL);
        }

        //构造订单数据
        Orders order = new Orders();
        // 注意：实体里 packAmount / tablewareNumber 是原始类型 int，而 DTO 里是 Integer。
        // 客户端不传这两个字段时 BeanUtils.copyProperties 会因为 null 拆箱直接抛 NPE（整单 500），
        // 所以先把 null 归一成默认值再拷贝。
        if (ordersSubmitDTO.getPackAmount() == null) {
            ordersSubmitDTO.setPackAmount(0);
        }
        if (ordersSubmitDTO.getTablewareNumber() == null) {
            ordersSubmitDTO.setTablewareNumber(1);
        }
        BeanUtils.copyProperties(ordersSubmitDTO, order);
        order.setPhone(addressBook.getPhone());
        order.setAddress(addressBook.getDetail());
        order.setConsignee(addressBook.getConsignee());
        order.setNumber(String.valueOf(System.currentTimeMillis()));
        order.setUserId(userId);
        order.setStatus(Orders.PENDING_PAYMENT);
        order.setPayStatus(Orders.UN_PAID);
        order.setOrderTime(LocalDateTime.now());

        // ★ AI 解析备注：发生在事务之外。parseRemark 内部有超时、重试和降级，
        //   最坏情况返回全 unknown，绝不会把下单请求带崩。
        RemarkIntent intent = aiOrderService.parseRemark(ordersSubmitDTO.getRemark());
        if (intent != null && intent.hasAnyHint()) {
            order.setAiTags(JSON.toJSONString(intent));
        }

        //插入订单、订单明细、清空购物车：这三步在一个事务里完成
        orderSubmitTxService.createOrder(order, list);

        //封装返回结果
        OrderSubmitVO orderSubmitVO = OrderSubmitVO.builder()
                .id(order.getId())
                .orderNumber(order.getNumber())
                .orderAmount(order.getAmount())
                .orderTime(LocalDateTime.now())
                .build();

        return orderSubmitVO;
    }

    /**
     * 分页查询订单信息
     * @param page 当前页码
     * @param pageSize 每页显示条数
     * @param status 订单状态，可为null
     * @return PageResult 包含总记录数和当前页数据列表
     */
    @Override
    public PageResult page(int page, int pageSize, Integer status) {
        // 使用PageHelper设置分页参数
        PageHelper.startPage(page, pageSize);
        // 创建订单查询条件对象
        OrdersPageQueryDTO ordersPageQueryDTO = new OrdersPageQueryDTO();
        // 设置当前用户ID
        ordersPageQueryDTO.setUserId(BaseContext.getCurrentId());
        // 设置订单状态
        ordersPageQueryDTO.setStatus(status);

        // 执行分页查询，获取订单列表
        Page<Orders> list = orderMapper.list(ordersPageQueryDTO);

        // 创建订单VO列表
        List<OrderVO> objects = new ArrayList();

        // 判断查询结果是否为空且是否有记录
        if (list != null && list.getTotal() > 0) {
            // 遍历订单列表
            for (Orders orders : list) {
                Long orderId = orders.getId();// 订单id

                // 查询订单明细
                List<OrderDetail> orderDetails = orderDetailMapper.getByOrderId(orderId);

                OrderVO orderVO = new OrderVO();
                BeanUtils.copyProperties(orders, orderVO);
                orderVO.setOrderDetailList(orderDetails);

                objects.add(orderVO);
            }

        }
        return new PageResult(list.getTotal(), objects);
    }

    /**
     * 查询订单详情
     * @param id 订单ID
     * @return 订单详情对象(OrderVO)
     */
    @Override
    public OrderVO details(Long id) {
        // 根据用户ID查询订单基本信息
        Orders order = orderMapper.getById(id);
        // 根据订单ID查询订单详情列表
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(order.getId());
        // 创建订单视图对象
        OrderVO orderVO = new OrderVO();
        // 将订单基本信息复制到订单视图对象中
        BeanUtils.copyProperties(order, orderVO);
        // 设置订单详情列表到订单视图对象中
        orderVO.setOrderDetailList(orderDetailList);
        // 返回订单详情视图对象
        return orderVO;
    }

    /**
     * 用户取消订单
     * @param id
     */
    @Override
    public void usercancel(Long id) {
        // 根据id查询订单
        Orders ordersDB = orderMapper.getById(id);

        // 校验订单是否存在
        if (ordersDB == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        //订单状态 1待付款 2待接单 3已接单 4派送中 5已完成 6已取消
        if (ordersDB.getStatus() > 2) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        Orders orders = new Orders();
        orders.setId(ordersDB.getId());

        // 订单处于待接单状态下取消，需要进行退款
        if (ordersDB.getStatus().equals(Orders.TO_BE_CONFIRMED)) {
            //调用微信支付退款接口
//            weChatPayUtil.refund (
//                    ordersDB.getNumber(), //商户订单号
//                    ordersDB.getNumber(), //商户退款单号
//                    new BigDecimal(0.01),//退款金额，单位 元
//                    new BigDecimal(0.01));//原订单金额

            //支付状态修改为 退款
            orders.setPayStatus(Orders.REFUND);
        }

        // 更新订单状态、取消原因、取消时间
        orders.setStatus(Orders.CANCELLED);
        orders.setCancelReason("用户取消");
        orders.setCancelTime(LocalDateTime.now());
        orderMapper.update(orders);
    }
    /**
     * 再来一单
     *
     * @param id
     */
    @Override
    public void repetition(Long id) {
        // 查询当前用户id
        Long userId = BaseContext.getCurrentId();

        // 根据订单id查询当前订单详情
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(id);

        // 将订单详情对象转换为购物车对象
        List<ShoppingCart> shoppingCartList = orderDetailList.stream().map(x -> {
            ShoppingCart shoppingCart = new ShoppingCart();

            // 将原订单详情里面的菜品信息重新复制到购物车对象中
            BeanUtils.copyProperties(x, shoppingCart, "id");
            shoppingCart.setUserId(userId);
            shoppingCart.setCreateTime(LocalDateTime.now());

            return shoppingCart;
        }).collect(Collectors.toList());

        // 将购物车对象批量添加到数据库
        shoppingCartMapper.insertBatch(shoppingCartList);
    }

    /**
     * 条件查询
     * @param ordersPageQueryDTO
     * @return
     */
    @Override
    public PageResult conditionSearch(OrdersPageQueryDTO ordersPageQueryDTO) {
        PageHelper.startPage(ordersPageQueryDTO.getPage(), ordersPageQueryDTO.getPageSize());

        Page<Orders> page = orderMapper.list(ordersPageQueryDTO);

        // 部分订单状态，需要额外返回订单菜品信息，将Orders转化为OrderVO
        List<OrderVO> orderVOList = getOrderVOList(page);

        return new PageResult(page.getTotal(), orderVOList);
    }

            /**
             * 根据分页查询结果，封装为OrderVO列表
             * @param page
             * @return
             */
            private List<OrderVO> getOrderVOList(Page<Orders> page) {
                // 需要返回订单菜品信息，自定义OrderVO响应结果
                List<OrderVO> orderVOList = new ArrayList<>();

                List<Orders> ordersList = page.getResult();
                if (!CollectionUtils.isEmpty(ordersList)) {
                    for (Orders orders : ordersList) {
                        // 将共同字段复制到OrderVO
                        OrderVO orderVO = new OrderVO();
                        BeanUtils.copyProperties(orders, orderVO);
                        String orderDishes = getOrderDishesStr(orders);

                        // 将订单菜品信息封装到orderVO中，并添加到orderVOList
                        orderVO.setOrderDishes(orderDishes);
                        orderVOList.add(orderVO);
                    }
                }
                return orderVOList;
            }

            /**
             * 根据订单id获取菜品信息字符串
             *
             * @param orders
             * @return
             */
            private String getOrderDishesStr(Orders orders) {
                // 查询订单菜品详情信息（订单中的菜品和数量）
                List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(orders.getId());

                // 将每一条订单菜品信息拼接为字符串（格式：宫保鸡丁*3；）
                List<String> orderDishList = orderDetailList.stream().map(x -> {
                    String orderDish = x.getName() + "*" + x.getNumber() + ";";
                    return orderDish;
                }).collect(Collectors.toList());

                // 将该订单对应的所有菜品信息拼接在一起
                return String.join("", orderDishList);
            }

    /**
     * 重写父类的statistics方法，用于获取订单统计数据
     * @return 返回包含订单统计信息的OrderStatisticsVO对象
     */
    @Override
    public OrderStatisticsVO statistics() {
        // 根据状态，分别查询出待接单、待派送、派送中的订单数量
        Integer toBeConfirmed = orderMapper.countStatus(Orders.TO_BE_CONFIRMED);
        Integer confirmed = orderMapper.countStatus(Orders.CONFIRMED);
        Integer deliveryInProgress = orderMapper.countStatus(Orders.DELIVERY_IN_PROGRESS);

        // 将查询出的数据封装到orderStatisticsVO中响应
        OrderStatisticsVO orderStatisticsVO = new OrderStatisticsVO();
    // 设置待接单订单数量
        orderStatisticsVO.setToBeConfirmed(toBeConfirmed);
    // 设置已确认订单数量
        orderStatisticsVO.setConfirmed(confirmed);
    // 设置派送中订单数量
        orderStatisticsVO.setDeliveryInProgress(deliveryInProgress);
    // 返回封装好的订单统计数据
        return orderStatisticsVO;
    }

    /**
     * 确认订单的方法
     * @param ordersConfirmDTO 包含订单确认信息的DTO对象，其中包含订单ID等信息
     */
    @Override
    public void confirm(OrdersConfirmDTO ordersConfirmDTO) {
    // 创建订单对象，使用建造者模式构建
        Orders orders = Orders.builder()
                .id(ordersConfirmDTO.getId())  // 设置订单ID，使用DTO中的ID
                .status(Orders.CONFIRMED)     // 设置订单状态为已确认
                .build();                     // 构建订单对象
    // 更新订单信息到数据库
        orderMapper.update(orders);
    }

    /**
     * 拒单
     *
     * @param ordersRejectionDTO
     */
    @Override
    public void rejection(OrdersRejectionDTO ordersRejectionDTO) {
        // 根据id查询订单
        Orders ordersDB = orderMapper.getById(ordersRejectionDTO.getId());

        // 订单只有存在且状态为2（待接单）才可以拒单
        if (ordersDB == null || !ordersDB.getStatus().equals(Orders.TO_BE_CONFIRMED)) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        //支付状态
        Integer payStatus = ordersDB.getPayStatus();
        if (payStatus == Orders.PAID) {
            //用户已支付，需要退款
//            String refund = weChatPayUtil.refund(
//                    ordersDB.getNumber(),
//                    ordersDB.getNumber(),
//                    new BigDecimal(0.01),
//                    new BigDecimal(0.01));
        }

        // 拒单需要退款，根据订单id更新订单状态、拒单原因、取消时间
        Orders orders = new Orders();
        orders.setId(ordersDB.getId());
        orders.setStatus(Orders.CANCELLED);
        orders.setRejectionReason(ordersRejectionDTO.getRejectionReason());
        orders.setCancelTime(LocalDateTime.now());

        orderMapper.update(orders);
    }

    /**
     * 管理端取消订单
     * @param ordersCancelDTO
     */
    @Override
    public void cancel(OrdersCancelDTO ordersCancelDTO) {
        // 根据id查询订单
        Orders ordersDB = orderMapper.getById(ordersCancelDTO.getId());

        //支付状态
        Integer payStatus = ordersDB.getPayStatus();
        if (payStatus == 1) {
            //用户已支付，需要退款
//            String refund = weChatPayUtil.refund(
//                    ordersDB.getNumber(),
//                    ordersDB.getNumber(),
//                    new BigDecimal(0.01),
//                    new BigDecimal(0.01));
//            log.info("申请退款：{}", refund);
        }

        // 管理端取消订单需要退款，根据订单id更新订单状态、取消原因、取消时间
        Orders orders = new Orders();
        orders.setId(ordersCancelDTO.getId());
        orders.setStatus(Orders.CANCELLED);
        orders.setCancelReason(ordersCancelDTO.getCancelReason());
        orders.setCancelTime(LocalDateTime.now());
        orderMapper.update(orders);
    }

    /**
     * 派送订单
     *
     * @param id
     */
    @Override
    public void delivery(Long id) {
        // 根据id查询订单
        Orders ordersDB = orderMapper.getById(id);

        // 校验订单是否存在，并且状态为3
        if (ordersDB == null || !ordersDB.getStatus().equals(Orders.CONFIRMED)) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        Orders orders = new Orders();
        orders.setId(ordersDB.getId());
        // 更新订单状态,状态转为派送中
        orders.setStatus(Orders.DELIVERY_IN_PROGRESS);
        orderMapper.update(orders);
    }

    /**
     * 完成订单
     *
     * @param id
     */
    @Override
    public void complete(Long id) {
        // 根据id查询订单
        Orders ordersDB = orderMapper.getById(id);

        // 校验订单是否存在，并且状态为4
        if (ordersDB == null || !ordersDB.getStatus().equals(Orders.DELIVERY_IN_PROGRESS)) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        Orders orders = new Orders();
        orders.setId(ordersDB.getId());
        // 更新订单状态,状态转为完成
        orders.setStatus(Orders.COMPLETED);
        orders.setDeliveryTime(LocalDateTime.now());

        orderMapper.update(orders);
    }

    /**
     * 用户发起支付
     *
     * @param ordersPaymentDTO
     * @return
     */
    @Override
    public OrderPaymentVO payment(OrdersPaymentDTO ordersPaymentDTO) {
        log.info("订单支付：{}", ordersPaymentDTO);

        // 按订单编号查订单
        Orders ordersDB = orderMapper.getByNumber(ordersPaymentDTO.getOrderNumber());
        // 订单不存在、或者不是当前登录用户的订单，一律按"不存在"处理，避免泄露别人的订单号
        if (ordersDB == null || !ordersDB.getUserId().equals(BaseContext.getCurrentId())) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        // 只有"待付款"的订单可以支付：拦住重复支付、给已取消/已接单订单付款
        if (!ordersDB.getStatus().equals(Orders.PENDING_PAYMENT)) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        // 交给支付渠道处理，业务层不关心底下是微信还是模拟
        PayResult payResult = payChannel.pay(ordersDB);
        log.info("支付渠道：{}，是否已扣款：{}", payChannel.getClass().getSimpleName(), payResult.isSettled());

        if (payResult.isSettled()) {
            // 模拟渠道：调用即扣款成功，直接走支付成功逻辑
            paySuccess(payResult.getTransactionId(), ordersDB.getId());
        }
        // 微信渠道：此刻还没收到钱，等异步回调调 paySuccess，这里只把签名参数返回给小程序

        return payResult.getClientParams();
    }

    @Autowired
    private WebSocketServer webSocketServer;
    /**
     * 支付成功处理。
     * mock 渠道由 payment() 同步调用；真实场景由微信支付结果通知（回调）调用。
     * 两种渠道共用这一份逻辑，所以业务分支只有一处。
     *
     * @param transactionId 第三方交易流水号
     * @param orderId       订单id
     */
    @Override
    @Transactional
    public void paySuccess(String transactionId, Long orderId) {
        Orders ordersDB = orderMapper.getById(orderId);
        if (ordersDB == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        // 幂等：微信会重复推送回调，已经处理过的订单直接返回，避免重复记账/重复推进状态
        if (!ordersDB.getStatus().equals(Orders.PENDING_PAYMENT) || ordersDB.getPayStatus().equals(Orders.PAID)) {
            log.info("订单已处理过支付结果，忽略。orderId={}，transactionId={}", orderId, transactionId);
            return;
        }

        Orders orders = new Orders();
        orders.setId(ordersDB.getId());
        orders.setPayStatus(Orders.PAID);
        // 状态由待付款转换为待接单，管理端"接单"按钮这时才可点
        orders.setStatus(Orders.TO_BE_CONFIRMED);
        orders.setCheckoutTime(LocalDateTime.now());
        orderMapper.update(orders);

        Map map = new HashMap();
        map.put("type", 1);//消息类型，1表示来单提醒
        map.put("orderId", orders.getId());
        // 推给商家的是本店可见的订单编号，不是第三方流水号（mock 渠道下流水号是 MOCKTXN...，商家看不懂）
        map.put("content", "订单号：" + ordersDB.getNumber());
        // 顺带把下单时 AI 解析出的结构化标签推过去，后厨不用逐条读备注
        map.put("aiTags", ordersDB.getAiTags());

        //通过WebSocket实现来单提醒，向客户端浏览器推送消息
        webSocketServer.sendToAllClient(JSON.toJSONString(map));

        log.info("支付成功，交易流水号：{}，订单 {} 已转为待接单", transactionId, ordersDB.getNumber());
    }

    /**
     * 催单处理。
     * 用户点击催单按钮时调用，向管理端推送催单提醒。
     * @param id 订单id
     * @param id
     */
    @Override
    public void reminder(Long id) {
        Orders ordersDB = orderMapper.getById(id);
        if (ordersDB == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        //基于WebSocket实现催单
        Map map = new HashMap();
        map.put("type", 2);//2代表用户催单
        map.put("orderId", id);
        map.put("content", "订单号：" + ordersDB.getNumber());
        webSocketServer.sendToAllClient(JSON.toJSONString(map));

    }
}
