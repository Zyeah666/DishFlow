package com.dishflow.controller.user;

import com.dishflow.dto.ShoppingCartDTO;
import com.dishflow.entity.ShoppingCart;
import com.dishflow.result.Result;
import com.dishflow.service.ShoppingCartService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/user/shoppingCart")
public class ShoppingCartController {
    @Autowired
    private ShoppingCartService shoppingCartService;

    /**
     * 添加商品到购物车的接口方法
     * @param shoppingCartDTO 购物车数据传输对象，包含要添加的商品信息
     * @return 返回操作结果，成功时返回成功状态
     */
    @PostMapping("/add")
    public Result add(@RequestBody ShoppingCartDTO shoppingCartDTO){
    // 调用购物车服务层的add方法，将商品添加到购物车
        shoppingCartService.add(shoppingCartDTO);
    // 返回操作成功的结果
        return Result.success();
    }
    /**
     * 处理购物车商品减少的请求
     * @param shoppingCartDTO 购物车数据传输对象，包含需要减少的商品信息
     * @return 返回操作成功的结果
     */
    @PostMapping("/sub")
    public Result sub (@RequestBody ShoppingCartDTO shoppingCartDTO) {
    // 调用服务层方法处理购物车商品减少逻辑
        shoppingCartService.sub(shoppingCartDTO);
    // 返回操作成功的结果
        return Result.success();
    }
    /**
     * 查看购物车
     * @return
     */
    @GetMapping("/list")
    public Result<List<ShoppingCart>> list(){
        return Result.success(shoppingCartService.showShoppingCart());
    }
    /**
     * 清空购物车商品
     * @return
     */
    @DeleteMapping("/clean")
    public Result<String> clean(){
        shoppingCartService.cleanShoppingCart();
        return Result.success();
    }

}
