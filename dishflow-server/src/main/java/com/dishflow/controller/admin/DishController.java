package com.dishflow.controller.admin;

import com.dishflow.dto.DishDTO;
import com.dishflow.dto.DishPageQueryDTO;
import com.dishflow.entity.Dish;
import com.dishflow.result.PageResult;
import com.dishflow.result.Result;
import com.dishflow.service.DishService;
import com.dishflow.vo.DishVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;


@RestController("adminDishController")
@RequestMapping("/admin/dish")
@Slf4j
public class DishController {
    @Autowired
    private DishService dishService;
    @Autowired
    private RedisTemplate redisTemplate;

    /**
     * 根据模式匹配清理Redis缓存
     * @param pattern 匹配键的模式，可以使用通配符，如"user:*"表示所有以"user:"开头的键
     */
    private void cleanCache(String pattern){
        // 根据传入的模式获取所有匹配的键集合
        Set keys = redisTemplate.keys(pattern);
        // 删除获取到的所有键
        redisTemplate.delete(keys);
    }

    /**
     * 新增菜品
     * @param dishDTO
     * @return
     */
    @PostMapping
    public Result save(@RequestBody DishDTO dishDTO){
        dishService.saveWithFlavor(dishDTO);

        //清理缓存数据
        String key = "dish_" + dishDTO.getCategoryId();
        cleanCache(key);

        return Result.success();
    }
    /**
     * 分页查询菜品信息
     * @param dishPageQueryDTO
     * @return
     */
    @GetMapping("/page")
    public Result<PageResult> page(DishPageQueryDTO dishPageQueryDTO){
        PageResult page = dishService.page(dishPageQueryDTO);
        return Result.success(page);
    }

    /**
     * 根据id批量删除菜品信息
     * @param ids
     * @return
     */
    @DeleteMapping
    public Result delete(@RequestParam List<Long> ids){
        dishService.delete(ids);
        //清理缓存数据
        cleanCache("dish_*");

        return Result.success();
    }

    /**
     * 根据id查询菜品信息
     * @param id
     * @return
     */
    @GetMapping("/{id}")
    public Result<DishVO> getById(@PathVariable Long id){
        DishVO dishVO = dishService.getById(id);
        return Result.success(dishVO);
   }

    /**
     * 根据id更新菜品信息
     * @param dishDTO
     * @return
     */
   @PutMapping
   public Result update(@RequestBody DishDTO dishDTO){
        dishService.updateWithFlavor(dishDTO);
       //清理缓存数据
       cleanCache("dish_*");

        return Result.success();
   }
    /**
     * 根据分类id查询菜品
     * @param categoryId
     * @return
     */
    @GetMapping("/list")
    public Result<List<Dish>> list(Long categoryId){
        List<Dish> list = dishService.list(categoryId);
        return Result.success(list);
    }

    /**
     * 启用或禁用菜品
     * @param status
     * @param id
     * @return
     */
    @PostMapping("/status/{status}")
    public Result startOrStop(@PathVariable Integer status,Long id){
        dishService.startOrStop(status,id);
        //清理缓存数据
        cleanCache("dish_*");

        return Result.success();

    }


}
