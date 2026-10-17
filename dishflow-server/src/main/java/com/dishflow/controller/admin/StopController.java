package com.dishflow.controller.admin;

import com.dishflow.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;



/**
 * 店铺状态管理控制器
 * 提供设置和获取店铺状态的接口
 */
@RestController("adminShopController")
@RequestMapping("/admin/shop")
@Slf4j
public class StopController {
    @Autowired
    private RedisTemplate redisTemplate;

    /**
     * 设置店铺状态
     * @param status
     * @return
     */
    @PutMapping("/{status}")
    public Result setStatus(@PathVariable Integer status) {
        log.info("设置店铺状态：{}", status);
        redisTemplate.opsForValue().set("shop:status", status);
        return Result.success();
    }
    /**
     * 获取店铺状态
     * @return
     */
    @GetMapping("/status")
    public Result<Integer> getStatus() {
        Integer st = (Integer) redisTemplate.opsForValue().get("shop:status");
        return Result.success(st);
    }
}
