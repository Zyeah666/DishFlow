package com.dishflow.controller.admin;

import com.alibaba.fastjson.JSON;
import com.dishflow.constant.AiConstant;
import com.dishflow.entity.AiCallLog;
import com.dishflow.entity.Orders;
import com.dishflow.mapper.AiCallLogMapper;
import com.dishflow.mapper.OrderMapper;
import com.dishflow.result.Result;
import com.dishflow.service.AiOrderService;
import com.dishflow.vo.RemarkIntent;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI 能力的运维入口（管理端）。
 * <p>
 * 为什么需要这几个接口：模型提示词不是一次写对的，改完 few-shot 之后必须能把历史数据重算一遍，
 * 才能对比"改进前 / 改进后"的识别率；而 stats 和 failures 是为了让评测有据可查 ——
 * 任何关于命中率、降级率、延迟的说法，都应该能在这里当场查出来。
 */
@RestController
@RequestMapping("/admin/ai")
@Slf4j
@Api(tags = "管理端-AI 辅助运维")
public class AiController {

    @Autowired
    private AiOrderService aiOrderService;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private AiCallLogMapper aiCallLogMapper;

    /**
     * 重跑单个订单的备注解析
     */
    @PostMapping("/reparse/order/{orderId}")
    @ApiOperation("重跑单条订单备注解析")
    public Result<RemarkIntent> reparse(@PathVariable Long orderId) {
        Orders orders = orderMapper.getById(orderId);
        if (orders == null) {
            return Result.error("订单不存在");
        }
        RemarkIntent intent = aiOrderService.parseRemark(orders.getRemark());
        orderMapper.updateAiTags(orderId, intent.hasAnyHint() ? JSON.toJSONString(intent) : null);
        return Result.success(intent);
    }

    /**
     * 批量回填：把"有备注但还没解析过"的历史订单重算一遍
     */
    @PostMapping("/reparse/history")
    @ApiOperation("批量重跑历史订单备注解析")
    public Result<Map<String, Object>> reparseHistory(@RequestParam(defaultValue = "50") int limit) {
        List<Orders> ordersList = orderMapper.listUnparsedRemarks(limit);
        int recognized = 0;
        List<String> samples = new ArrayList<>();
        for (Orders orders : ordersList) {
            RemarkIntent intent = aiOrderService.parseRemark(orders.getRemark());
            if (intent.hasAnyHint()) {
                orderMapper.updateAiTags(orders.getId(), JSON.toJSONString(intent));
                recognized++;
                if (samples.size() < 10) {
                    samples.add(orders.getRemark() + "  →  " + JSON.toJSONString(intent));
                }
            }
        }

        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("scanned", ordersList.size());
        summary.put("recognized", recognized);
        summary.put("samples", samples);
        return Result.success(summary);
    }

    /**
     * 调用流水统计：简历里所有关于命中率、延迟、成本的数字都应该能在这里查到
     */
    @GetMapping("/stats")
    @ApiOperation("AI 调用流水统计")
    public Result<Map<String, Object>> stats() {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("remarkParseByStatus", aiCallLogMapper.statByStatus(AiConstant.SCENE_REMARK_PARSE));
        result.put("remarkParseRealCall", aiCallLogMapper.statRealCalls(AiConstant.SCENE_REMARK_PARSE));
        result.put("insightByStatus", aiCallLogMapper.statByStatus(AiConstant.SCENE_INSIGHT));
        return Result.success(result);
    }

    /**
     * 最近的失败样本，用于迭代 few-shot
     */
    @GetMapping("/failures")
    @ApiOperation("AI 失败样本")
    public Result<List<AiCallLog>> failures(@RequestParam(defaultValue = "20") int limit) {
        return Result.success(aiCallLogMapper.listRecentFailures(limit));
    }

    /**
     * 直接把一段文本丢进来试解析，调提示词时不用真的下单
     */
    @PostMapping("/parse")
    @ApiOperation("试解析一段备注文本")
    public Result<RemarkIntent> parse(@RequestParam String remark) {
        return Result.success(aiOrderService.parseRemark(remark));
    }
}
