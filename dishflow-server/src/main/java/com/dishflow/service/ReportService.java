package com.dishflow.service;

import com.dishflow.vo.OrderReportVO;
import com.dishflow.vo.SalesTop10ReportVO;
import com.dishflow.vo.TurnoverReportVO;
import com.dishflow.vo.UserReportVO;

import javax.servlet.http.HttpServletResponse;
import java.time.LocalDate;

public interface ReportService {
    TurnoverReportVO getTurnover(LocalDate begin, LocalDate end);

    UserReportVO getUserStatistics(LocalDate begin, LocalDate end);

    /**
     * 根据时间区间统计订单数量
     * @param begin
     * @param end
     * @return
     */
    OrderReportVO getOrderStatistics(LocalDate begin, LocalDate end);
    /**
     * 查询指定时间区间内的销量排名top10
     * @param begin
     * @param end
     * @return
     */
    SalesTop10ReportVO getSalesTop10(LocalDate begin, LocalDate end);
    /**
     * 导出近30天的运营数据报表
     * @param response
     **/
    void exportBusinessData(HttpServletResponse response);
}
