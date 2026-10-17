package com.dishflow.service;

import com.dishflow.dto.DishDTO;
import com.dishflow.dto.DishPageQueryDTO;
import com.dishflow.entity.Dish;
import com.dishflow.result.PageResult;
import com.dishflow.vo.DishVO;

import java.util.List;

public interface DishService {
    /**
     * 根据特色保存菜品信息
     * @param dishDTO 菜品数据传输对象，包含需要保存的菜品信息
     */
    void saveWithFlavor(DishDTO dishDTO);

    /**
     * 分页查询菜品信息
     * @param dishPageQueryDTO
     * @return
     */
    PageResult page (DishPageQueryDTO dishPageQueryDTO);

    /**
     * 根据id批量删除菜品信息
     * @param ids
     */
    void delete(List<Long> ids);

    /**
     * 根据id查询菜品信息
     * @param id
     * @return
     */
    DishVO getById(Long id);

    /**
     * 根据id更新菜品信息
     * @param dishDTO
     */
    void updateWithFlavor(DishDTO dishDTO);
    /**
     * 根据分类id查询菜品
     * @param categoryId
     * @return
     */
    List<Dish> list(Long categoryId);

    /**
     * 根据id启停菜品
     * @param status
     * @param id
     */
    void startOrStop(Integer status, Long id);

    /**
     * 条件查询菜品和口味
     * @param dish
     * @return
     */
    List<DishVO> listWithFlavor(Dish dish);
}
