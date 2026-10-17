package com.dishflow.controller.admin;

import com.dishflow.constant.JwtClaimsConstant;
import com.dishflow.dto.EmployeeDTO;
import com.dishflow.dto.EmployeeLoginDTO;
import com.dishflow.dto.EmployeePageQueryDTO;
import com.dishflow.entity.Employee;
import com.dishflow.properties.JwtProperties;
import com.dishflow.result.PageResult;
import com.dishflow.result.Result;
import com.dishflow.service.EmployeeService;
import com.dishflow.utils.JwtUtil;
import com.dishflow.vo.EmployeeLoginVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 员工管理
 */
@RestController
@RequestMapping("/admin/employee")
@Slf4j
public class EmployeeController {

    @Autowired
    private EmployeeService employeeService;
    @Autowired
    private JwtProperties jwtProperties;

    /**
     * 登录
     *
     * @param employeeLoginDTO
     * @return
     */
    @PostMapping("/login")
    public Result<EmployeeLoginVO> login(@RequestBody EmployeeLoginDTO employeeLoginDTO) {
        log.info("员工登录：{}", employeeLoginDTO);

        Employee employee = employeeService.login(employeeLoginDTO);

        //登录成功后，生成jwt令牌
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.EMP_ID, employee.getId());
        String token = JwtUtil.createJWT(
                jwtProperties.getAdminSecretKey(),
                jwtProperties.getAdminTtl(),
                claims);

        EmployeeLoginVO employeeLoginVO = EmployeeLoginVO.builder()
                .id(employee.getId())
                .userName(employee.getUsername())
                .name(employee.getName())
                .token(token)
                .build();

        return Result.success(employeeLoginVO);
    }

    /**
     * 退出
     *
     * @return
     */
    @PostMapping("/logout")
    public Result<String> logout() {
        return Result.success();
    }

    /**
     * 新增员工
     *
     */
    @PostMapping
    public Result save(@RequestBody EmployeeDTO employeeDTO) {
        employeeService.save(employeeDTO);
        return Result.success();
    }
    @GetMapping("/page")
    public Result<PageResult> page(EmployeePageQueryDTO employeePageQueryDTO) {
        PageResult pageResult = employeeService.page(employeePageQueryDTO);
        return Result.success(pageResult);
    }
    @PostMapping("/status/{status}")
    public Result startAndStop( @PathVariable Integer status,Long id) {
        employeeService.startAndStop(status,id);
        return Result.success();
    }
    @GetMapping("/{id}")
    public Result getById(@PathVariable Long id) {
        Employee employee = employeeService.getById(id);
        return Result.success(employee);
    }
    @PutMapping
    public Result update(@RequestBody EmployeeDTO employeeDTO) {
        employeeService.update(employeeDTO);
        return Result.success();

    }

}
