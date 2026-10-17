package com.dishflow.service;

import com.dishflow.dto.EmployeeDTO;
import com.dishflow.dto.EmployeeLoginDTO;
import com.dishflow.dto.EmployeePageQueryDTO;
import com.dishflow.entity.Employee;
import com.dishflow.result.PageResult;

public interface EmployeeService {

    /**
     * 员工登录
     * @param employeeLoginDTO
     * @return
     */
    Employee login(EmployeeLoginDTO employeeLoginDTO);

    /**
     * 新增员工的方法
     * 接收一个EmployeeDTO对象作为参数
     *
     * @param employeeDTO 包含员工信息的DTO对象，用于传递员工数据
     */
    void save(EmployeeDTO employeeDTO);
    /**
    分页查询
    */
    PageResult page(EmployeePageQueryDTO employeePageQueryDTO);
    /**
     * 启用禁用员工
     */
    void startAndStop(Integer status, Long id);

    /**
     * 根据id查询员工信息
     * @param id
     * @return
     */
    Employee getById(Long id);

    /**
     * 修改员工信息
     * @param employeeDTO
     */
    void update(EmployeeDTO employeeDTO);
}


