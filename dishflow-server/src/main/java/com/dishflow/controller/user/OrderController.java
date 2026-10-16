package com.dishflow.controller.user;

import com.dishflow.dto.OrdersPaymentDTO;
import com.dishflow.dto.OrdersSubmitDTO;
import com.dishflow.result.PageResult;
import com.dishflow.result.Result;
import com.dishflow.service.OrderService;
import com.dishflow.vo.OrderPaymentVO;
import com.dishflow.vo.OrderSubmitVO;
import com.dishflow.vo.OrderVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController("userOrderController")
@RequestMapping("/user/order")
@Slf4j
public class OrderController {
    @Autowired
    private OrderService orderService;

    /**
     * 用户下单
     *
     * @return
     */
    @PostMapping("/submit")
    public Result<OrderSubmitVO> submit(@RequestBody OrdersSubmitDTO ordersSubmitDTO){
        log.info("用户下单:{}",ordersSubmitDTO);
        OrderSubmitVO orderSubmitVO = orderService.submit(ordersSubmitDTO);

        return Result.success(orderSubmitVO);
    }

    /**
     * 订单支付
     * 与小程序端契约保持一致：PUT /user/order/payment，订单以请求体里的 orderNumber 为准
     *
     * @param ordersPaymentDTO
     * @return
     */
    @PutMapping("/payment")
    public Result<OrderPaymentVO> payment(@RequestBody OrdersPaymentDTO ordersPaymentDTO) {
        log.info("订单支付：{}", ordersPaymentDTO);
        OrderPaymentVO orderPaymentVO = orderService.payment(ordersPaymentDTO);
        log.info("生成预支付交易单：{}", orderPaymentVO);
        return Result.success(orderPaymentVO);
    }
    /**
     * 获取历史订单分页列表
     * 该方法用于处理获取历史订单的请求，并返回分页结果
     * 通过HTTP GET请求访问，路径为/historyOrders
     * @return 返回一个包含分页结果的对象
     */
    @GetMapping("/historyOrders")
    public Result<PageResult> page(int page, int pageSize, Integer status){
        PageResult pageResult = orderService.page(page,pageSize,status);
        return Result.success(pageResult);
    }
    /**
     * 获取订单详情的接口方法
     * @param id 订单ID，通过路径变量传递
     * @return 返回一个Result对象，其中包含订单详情的OrderVO数据
     */
    @GetMapping("/orderDetail/{id}")
    public Result<OrderVO> details(@PathVariable Long id) {
        // 调用orderService的details方法获取订单详情
        OrderVO orderVO = orderService.details(id);
        // 返回成功响应，包含订单详情数据
        return Result.success(orderVO);
    }

    /**
     * 取消订单
     * @param id
     * @return
     */
    @PutMapping("/cancel/{id}")
    public Result cancel(Long id){
        orderService.usercancel(id);
        return Result.success();
    }
    /**
     * 再来一单
     *
     * @param id
     * @return
     */
    @PostMapping("/repetition/{id}")
    public Result repetition(@PathVariable Long id) {
        orderService.repetition(id);
        return Result.success();
    }
    /**
     * 用户催单
     *
     * @param id
     * @return
     */
    @GetMapping("/reminder/{id}")
    public Result reminder(@PathVariable("id") Long id) {
        orderService.reminder(id);
        return Result.success();
    }
}
