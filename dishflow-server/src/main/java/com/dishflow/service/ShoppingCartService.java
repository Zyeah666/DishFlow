package com.dishflow.service;

import com.dishflow.dto.ShoppingCartDTO;
import com.dishflow.entity.ShoppingCart;

import java.util.List;

public interface ShoppingCartService {
    /**
     * 向购物车添加商品的方法
     * @param shoppingCartDTO 购物车数据传输对象，包含要添加的商品信息
     */
    void add(ShoppingCartDTO shoppingCartDTO);

    void sub(ShoppingCartDTO shoppingCartDTO);
    /**
     * 查看购物车
     * @return
     */
    List<ShoppingCart> showShoppingCart();
    /**
     * 清空购物车商品
     */
    void cleanShoppingCart();
}
