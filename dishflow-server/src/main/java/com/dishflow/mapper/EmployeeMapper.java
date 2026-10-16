package com.dishflow.mapper;

import com.dishflow.annotation.AutoFill;
import com.dishflow.dto.EmployeePageQueryDTO;
import com.dishflow.entity.Employee;
import com.dishflow.enumeration.OperationType;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface EmployeeMapper {

    /**
     * 根据用户名查询员工
     * @param username
     * @return
     */
    @Select("select * from employee where username = #{username}")
    Employee getByUsername(String username);

    /**
     * 新增员工数据
     * @param employee
     */
    @AutoFill(value = OperationType.INSERT)
    void save(Employee employee);

    /**
     * 分页查询
     * @param employeePageQueryDTO
     */
    List<Employee> pageQuery(EmployeePageQueryDTO employeePageQueryDTO);

    /**
     * 修改员工信息
     * 启动和停止方法
     * @param employee
     */
    @AutoFill(value = OperationType.UPDATE)
    void update(Employee employee);
    /**
     * 根据id查询员工信息
     * @param id
     * @return
     */
    Employee getById(Long id);

}
