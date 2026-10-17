package com.dishflow.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.dishflow.constant.MessageConstant;
import com.dishflow.constant.StatusConstant;
import com.dishflow.dto.DishDTO;
import com.dishflow.dto.DishPageQueryDTO;
import com.dishflow.entity.Dish;
import com.dishflow.entity.DishFlavor;
import com.dishflow.exception.DeletionNotAllowedException;
import com.dishflow.mapper.DishFlavorMapper;
import com.dishflow.mapper.DishMapper;
import com.dishflow.mapper.SetmealDishMapper;
import com.dishflow.result.PageResult;
import com.dishflow.service.DishService;
import com.dishflow.vo.DishVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class DishServiceImpl implements DishService {
    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private DishFlavorMapper dishFlavorMapper;
    @Autowired
    private SetmealDishMapper setmealDishMapper;
    /**
     * 保存带有口味信息的菜品信息
     * @param dishDTO 包含菜品和口味信息的DTO对象
     */
    @Override
    @Transactional
    public void saveWithFlavor(DishDTO dishDTO) {
        /**
         * 保存菜品信息
         */
        Dish dish = new Dish();
        BeanUtils.copyProperties(dishDTO, dish);
        dishMapper.insert(dish);
        /**
         * 保存口味信息
         */
        Long dishId = dish.getId();
        List<DishFlavor> flavors = dishDTO.getFlavors();
        if (flavors != null&& !flavors.isEmpty()) {
            flavors.forEach(dishFlavor -> {
                dishFlavor.setDishId(dishId);
            });
            dishFlavorMapper.insertBatch(flavors);
        }
    }

    /**
     * 分页条件查询
     * @param dishPageQueryDTO
     * @return
     */
    @Override
    public PageResult page(DishPageQueryDTO dishPageQueryDTO) {
        PageHelper.startPage(dishPageQueryDTO.getPage(), dishPageQueryDTO.getPageSize());
        Page<DishVO> page = dishMapper.page(dishPageQueryDTO);
        return new PageResult(page.getTotal(), page.getResult());
    }

    /**
     * 根据id删除菜品
     * @param ids
     */
    @Override
    @Transactional
    public void delete(List<Long> ids) {
        for (Long id : ids) {  //判断菜品是否在售
            Dish dish =dishMapper.getById(id);
            if (dish.getStatus()==1){
                throw new DeletionNotAllowedException(MessageConstant.DISH_ON_SALE);
            }
        }
        List<Long> setmealIds = setmealDishMapper.getSetmealIdsByDishIds(ids);
        if (setmealIds != null && !setmealIds.isEmpty()) {  //判断菜品是否被套餐关联
            throw new DeletionNotAllowedException(MessageConstant.DISH_BE_RELATED_BY_SETMEAL);
        }
        dishMapper.deleteByIds(ids); //删除菜品
        dishFlavorMapper.deleteByDishIds(ids); //删除口味
    }

    /**
     * 根据id查询菜品信息
     * @param id
     * @return
     */
    @Override
    public DishVO getById(Long id) {
        Dish dish = dishMapper.getById(id); //查询菜品基本信息
        List<DishFlavor> flavors = dishFlavorMapper.getByDishId(id);  //查询口味信息
        DishVO dishVO = new DishVO(); //封装菜品完整信息
        BeanUtils.copyProperties(dish, dishVO); //将菜品基本信息复制到VO对象中
        dishVO.setFlavors(flavors); //将口味信息复制到VO对象中
        return dishVO;
    }

    /**
     * 根据id更新菜品信息
     * @param dishDTO
     */
    @Override
    @Transactional
    public void updateWithFlavor(DishDTO dishDTO) {
        Dish dish = new Dish();
        BeanUtils.copyProperties(dishDTO, dish);
        dishMapper.updateById(dish);//更新菜品信息

        Long dishId = dish.getId();
        dishFlavorMapper.deleteByDishId(dishId);

        List<DishFlavor> flavors = dishDTO.getFlavors();
        if (flavors != null && !flavors.isEmpty()) {//删除口味信息

            flavors.forEach(dishFlavor -> {
                dishFlavor.setDishId(dishId);
            });
            dishFlavorMapper.insertBatch(flavors); //新增口味信息
        }
    }
    /**
     * 根据分类id查询菜品
     * @param categoryId
     * @return
     */
    public List<Dish> list(Long categoryId) {
        Dish dish = Dish.builder()
                .categoryId(categoryId)
                .status(StatusConstant.ENABLE)
                .build();
        return dishMapper.list(dish);
    }
    /**
     * 根据id修改菜品状态
     * @param status
     * @param id
     */
    @Override
    public void startOrStop(Integer status, Long id) {
        Dish dish = Dish.builder()
                .status(status)
                .id(id)
                .build();
        dishMapper.updateById(dish);
    }

    /**
     * 条件查询菜品和口味
     * @param dish
     * @return
     */
    public List<DishVO> listWithFlavor(Dish dish) {
        List<Dish> dishList = dishMapper.list(dish);

        List<DishVO> dishVOList = new ArrayList<>();

        for (Dish d : dishList) {
            DishVO dishVO = new DishVO();
            BeanUtils.copyProperties(d,dishVO);

            //根据菜品id查询对应的口味
            List<DishFlavor> flavors = dishFlavorMapper.getByDishId(d.getId());

            dishVO.setFlavors(flavors);
            dishVOList.add(dishVO);
        }

        return dishVOList;
    }
}
