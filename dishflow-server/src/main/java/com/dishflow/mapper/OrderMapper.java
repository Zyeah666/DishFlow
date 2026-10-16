package com.dishflow.mapper;

import com.github.pagehelper.Page;
import com.dishflow.dto.GoodsSalesDTO;
import com.dishflow.dto.OrdersPageQueryDTO;
import com.dishflow.entity.Orders;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface OrderMapper {
    void insert(Orders order);

    Page<Orders> list(OrdersPageQueryDTO ordersPageQueryDTO);

    @Select("select * from orders where id = #{id}")
    Orders getById(Long id);

    /**
     * 单独更新 AI 标签列：给"重跑解析"接口用。
     * 不复用 update 方法是因为它的 <set> 里只列举了状态相关字段，
     * 而重跑只应该改动 ai_tags，不应该顺手把订单状态一起写回去。
     */
    @Update("update orders set ai_tags = #{aiTags} where id = #{id}")
    void updateAiTags(@Param("id") Long id, @Param("aiTags") String aiTags);

    /**
     * 有备注但还没有解析结果的订单，用于"重跑解析"批量回填。
     * 典型用途：调完 few-shot 之后把历史订单重算一遍，对比改进前后的识别率。
     */
    @Select("select * from orders where remark is not null and remark <> '' and ai_tags is null " +
            "order by id desc limit #{limit}")
    List<Orders> listUnparsedRemarks(int limit);

    /**
     * 根据订单编号查询订单（支付、支付结果轮询都按编号查）
     * @param orderNumber
     * @return
     */
    @Select("select * from orders where number = #{orderNumber}")
    Orders getByNumber(String orderNumber);

    void update(Orders orders);

    @Select("select count(id) from orders where status = #{status}")
    Integer countStatus(Integer toBeConfirmed);

    @Select("select * from orders where status = #{status} and orders.order_time < #{time}")
    List<Orders> getByStatus(Integer status, LocalDateTime time);

    /**
     * 根据动态条件统计订单金额
     * @param map
     * @return
     */
    Double sumByMap(Map map);
    /**
     *根据动态条件统计订单数量
     * @param map
     */
    Integer countByMap(Map map);
    /**
     * 查询商品销量排名
     * @param begin
     * @param end
     */
    List<GoodsSalesDTO> getSalesTop10(LocalDateTime begin, LocalDateTime end);
}
