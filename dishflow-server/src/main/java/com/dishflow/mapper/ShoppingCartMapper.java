package com.dishflow.mapper;

import com.dishflow.entity.ShoppingCart;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ShoppingCartMapper {

    /**
     * 根据购物车对象查询相关的购物车列表
     *
     * @param shoppingCart 购物车对象，作为查询条件
     * @return 返回购物车列表，包含符合条件的所有购物车对象
     */
    List<ShoppingCart> list(ShoppingCart shoppingCart);

    /**
     * 根据购物车ID更新购物车中的商品数量
     * @param cart 包含购物车ID和商品数量的实体对象
     */
    @Update("update shopping_cart set number = #{number} where id = #{id}")
    void updateNumberById(ShoppingCart cart);

    /**
     * 向系统添加一个购物车的接口方法
     * 该方法用于将一个新的购物车实例添加到系统中
     *
     * @param shoppingCart 要添加的购物车对象，包含购物车中的商品信息、数量等相关数据
     */
    @Insert("insert into shopping_cart (name, user_id, dish_id, setmeal_id, dish_flavor, number, amount, image, create_time) " +
            " values (#{name},#{userId},#{dishId},#{setmealId},#{dishFlavor},#{number},#{amount},#{image},#{createTime})")
    void insert(ShoppingCart shoppingCart);

    @Delete("delete from shopping_cart where id = #{id}")
    void deleteById(Long id);
    /**
     * 根据用户id删除购物车数据
     *
     * @param userId
     */
    @Delete("delete from shopping_cart where user_id = #{userId}")
    void deleteByUserId(Long userId);

    void insertBatch(List<ShoppingCart> shoppingCartList);
}
