package com.dishflow.controller.admin;

import com.dishflow.result.Result;
import com.dishflow.service.InsightService;
import com.dishflow.service.WorkspaceService;
import com.dishflow.vo.BusinessDataVO;
import com.dishflow.vo.DishOverViewVO;
import com.dishflow.vo.InsightVO;
import com.dishflow.vo.OrderOverViewVO;
import com.dishflow.vo.SetmealOverViewVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 工作台
 */
@RestController
@RequestMapping("/admin/workspace")
@Slf4j
@Api(tags = "工作台相关接口")
public class WorkSpaceController {

    @Autowired
    private WorkspaceService workspaceService;
    @Autowired
    private InsightService insightService;

    /**
     * 工作台今日数据查询
     * @return
     */
    @GetMapping("/businessData")
    @ApiOperation("工作台今日数据查询")
    public Result<BusinessDataVO> businessData(){
        //获得当天的开始时间
        LocalDateTime begin = LocalDateTime.now().with(LocalTime.MIN);
        //获得当天的结束时间
        LocalDateTime end = LocalDateTime.now().with(LocalTime.MAX);

        BusinessDataVO businessDataVO = workspaceService.getBusinessData(begin, end);
        return Result.success(businessDataVO);
    }

    /**
     * 查询订单管理数据
     * @return
     */
    @GetMapping("/overviewOrders")
    @ApiOperation("查询订单管理数据")
    public Result<OrderOverViewVO> orderOverView(){
        return Result.success(workspaceService.getOrderOverView());
    }

    /**
     * 查询菜品总览
     * @return
     */
    @GetMapping("/overviewDishes")
    @ApiOperation("查询菜品总览")
    public Result<DishOverViewVO> dishOverView(){
        return Result.success(workspaceService.getDishOverView());
    }

    /**
     * 查询套餐总览
     * @return
     */
    @GetMapping("/overviewSetmeals")
    @ApiOperation("查询套餐总览")
    public Result<SetmealOverViewVO> setmealOverView(){
        return Result.success(workspaceService.getSetmealOverView());
    }

    /**
     * AI 经营洞察。
     * <p>
     * 故意做成独立接口，而不是塞进 /businessData：AI 调用可能要等一秒左右，
     * 而工作台其它卡片必须秒开。拆开后 AI 失败只影响这一张小卡片
     * （返回 degraded=true，前端直接隐藏），不会连带整个工作台。
     *
     * @param begin 起始日期，默认近 7 天
     * @param end   结束日期，默认今天
     */
    @GetMapping("/insight")
    @ApiOperation("AI 经营洞察（数字来自报表统计，模型只做解释）")
    public Result<InsightVO> insight(
            @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate begin,
            @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate end) {
        LocalDate actualEnd = end == null ? LocalDate.now() : end;
        LocalDate actualBegin = begin == null ? actualEnd.minusDays(6) : begin;
        return Result.success(insightService.insight(actualBegin, actualEnd));
    }
}
