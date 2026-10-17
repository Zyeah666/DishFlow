package com.dishflow.service.impl;

import com.dishflow.context.BaseContext;
import com.dishflow.dto.ShoppingCartDTO;
import com.dishflow.entity.Dish;
import com.dishflow.entity.Setmeal;
import com.dishflow.entity.ShoppingCart;
import com.dishflow.mapper.DishMapper;
import com.dishflow.mapper.SetmealMapper;
import com.dishflow.mapper.ShoppingCartMapper;
import com.dishflow.service.ShoppingCartService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ShoppingCartServiceImpl implements ShoppingCartService {

    @Autowired
    private ShoppingCartMapper shoppingCartMapper;
    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private SetmealMapper setmealMapper;


    /**
     * 添加商品到购物车的方法
     * @param shoppingCartDTO 包含商品信息的购物车数据传输对象
     */
    @Override
    public void add(ShoppingCartDTO shoppingCartDTO) {
        ShoppingCart shoppingCart = new ShoppingCart();
        BeanUtils.copyProperties(shoppingCartDTO,shoppingCart);
        shoppingCart.setUserId(BaseContext.getCurrentId());

        //根据用户id和商品(菜品，套餐，口味)id查询购物车中是否已有该商品
        List<ShoppingCart> list = shoppingCartMapper.list(shoppingCart);
        //判断如果购物车中有商品则数量加1，通过购物车表id
        if(list!=null&&list.size()==1){
            ShoppingCart cart = list.get(0);
            cart.setNumber(cart.getNumber()+1);
            shoppingCartMapper.updateNumberById(cart);
        }else{//如果购物车中没有商品则新增一条记录
            //判断加入购物车的是菜品还是套餐
            Long dishId = shoppingCart.getDishId();
            if(dishId!=null){//是菜品
                Dish dish = dishMapper.getById(shoppingCartDTO.getDishId());
                shoppingCart.setName(dish.getName());
                shoppingCart.setImage(dish.getImage());
                shoppingCart.setAmount(dish.getPrice());
            }else{//是套餐
                Setmeal setmeal = setmealMapper.getById(shoppingCartDTO.getSetmealId());
                shoppingCart.setName(setmeal.getName());
                shoppingCart.setImage(setmeal.getImage());
                shoppingCart.setAmount(setmeal.getPrice());
            }
            shoppingCart.setCreateTime(LocalDateTime.now());
            shoppingCart.setNumber(1);
            shoppingCartMapper.insert(shoppingCart);
        }

    }
    /**
     * 处理购物车商品减少的请求
     * @param shoppingCartDTO
     */
    @Override
    public void sub(ShoppingCartDTO shoppingCartDTO) {
        ShoppingCart shoppingCart = new ShoppingCart();
        BeanUtils.copyProperties(shoppingCartDTO,shoppingCart);
        shoppingCart.setUserId(BaseContext.getCurrentId());

        List<ShoppingCart> list = shoppingCartMapper.list(shoppingCart);
        ShoppingCart cart = list.get(0);
        Integer number = cart.getNumber();
        //判断如果购物车中商品数量=1则删除该商品，否则数量减1
        if(number==1){
            shoppingCartMapper.deleteById(cart.getId());
        }else{
            cart.setNumber(number-1);
            shoppingCartMapper.updateNumberById(cart);
        }
    }
    /**
     * 查看购物车
     * @return
     */
    public List<ShoppingCart> showShoppingCart() {
        return shoppingCartMapper.list(ShoppingCart.
                builder().
                userId(BaseContext.getCurrentId()).
                build());
    }
    /**
     * 清空购物车商品
     */
    public void cleanShoppingCart() {
        shoppingCartMapper.deleteByUserId(BaseContext.getCurrentId());
    }
}
