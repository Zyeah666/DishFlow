package com.dishflow.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.dishflow.constant.MessageConstant;
import com.dishflow.constant.PasswordConstant;
import com.dishflow.constant.StatusConstant;
import com.dishflow.dto.EmployeeDTO;
import com.dishflow.dto.EmployeeLoginDTO;
import com.dishflow.dto.EmployeePageQueryDTO;
import com.dishflow.entity.Employee;
import com.dishflow.exception.AccountLockedException;
import com.dishflow.exception.AccountNotFoundException;
import com.dishflow.exception.PasswordErrorException;
import com.dishflow.mapper.EmployeeMapper;
import com.dishflow.result.PageResult;
import com.dishflow.service.EmployeeService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.util.List;

@Service
public class EmployeeServiceImpl implements EmployeeService {

    @Autowired
    private EmployeeMapper employeeMapper;

    /**
     * 员工登录
     *
     * @param employeeLoginDTO
     * @return
     */
    public Employee login(EmployeeLoginDTO employeeLoginDTO) {
        String username = employeeLoginDTO.getUsername();
        String password = employeeLoginDTO.getPassword();

        //1、根据用户名查询数据库中的数据
        Employee employee = employeeMapper.getByUsername(username);

        //2、处理各种异常情况（用户名不存在、密码不对、账号被锁定）
        if (employee == null) {
            //账号不存在
            throw new AccountNotFoundException(MessageConstant.ACCOUNT_NOT_FOUND);
        }

        //密码比对
        // TODO 后期需要进行md5加密，然后再进行比对
        if (!password.equals(employee.getPassword())) {
            //密码错误
            throw new PasswordErrorException(MessageConstant.PASSWORD_ERROR);
        }

        if (employee.getStatus() == StatusConstant.DISABLE) {
            //账号被锁定
            throw new AccountLockedException(MessageConstant.ACCOUNT_LOCKED);
        }

        //3、返回实体对象
        return employee;
    }

    @Override    // 注解：表示重写父类或接口中的方法

    /**
     * 新增员工的方法
     * 接收一个EmployeeDTO对象作为参数
     * @param employeeDTO 员工数据传输对象，包含需要保存的员工信息
     */
    public void save(EmployeeDTO employeeDTO) {    // 声明一个保存员工信息的方法，接收EmployeeDTO类型的参数
        Employee employee = new Employee();
        BeanUtils.copyProperties(employeeDTO, employee);  // 使用BeanUtils工具类将employeeDTO对象中的属性值复制到employee对象中
        employee.setStatus(StatusConstant.ENABLE);
        employee.setPassword(DigestUtils.md5DigestAsHex(PasswordConstant.DEFAULT_PASSWORD.getBytes()));
//        //设置当前记录的创建时间和修改时间
//        employee.setCreateTime(LocalDateTime.now());
//        employee.setUpdateTime(LocalDateTime.now());
//        //设置当前记录创建人id和修改人id
//        employee.setCreateUser(BaseContext.getCurrentId());
//        employee.setUpdateUser(BaseContext.getCurrentId());

        employeeMapper.save(employee);    // 调用employeeMapper的save方法，将employeeDTO对象中的数据保存到数据库中
    }

    @Override
    /**
     * 分页查询
     *
     * @param employeePageQueryDTO
     * @return
     */
    public PageResult page(EmployeePageQueryDTO employeePageQueryDTO) {
        PageHelper.startPage(employeePageQueryDTO.getPage(), employeePageQueryDTO.getPageSize());
        Page<Employee> page = (Page<Employee>) employeeMapper.pageQuery(employeePageQueryDTO);
        long total = page.getTotal();
        List<Employee> records = page.getResult();

        return new PageResult(total, records);
    }

    /**
     * 启用禁用员工账号
     * @param status
     * @param id
     */
    @Override
    public void startAndStop(Integer status, Long id) {
        Employee employee = Employee.builder()
                .status(status)
                .id(id)
                .build();
        employeeMapper.update(employee);
    }

    /**
     * 查询回显
     * @param id
     * @return
     */
    @Override
    public Employee getById(Long id) {
        Employee employee = employeeMapper.getById(id);
        employee.setPassword("****");
        return employee;
    }

    /**
     * 更新员工信息
     * @param employeeDTO
     */
    @Override
    public void update(EmployeeDTO employeeDTO) {
        Employee employee = new Employee();
        BeanUtils.copyProperties(employeeDTO, employee);

//        employee.setUpdateTime(LocalDateTime.now());
//        employee.setUpdateUser(BaseContext.getCurrentId());

        employeeMapper.update(employee);
    }
}
