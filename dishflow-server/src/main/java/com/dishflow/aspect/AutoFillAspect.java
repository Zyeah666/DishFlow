package com.dishflow.aspect;

import com.dishflow.annotation.AutoFill;
import com.dishflow.constant.AutoFillConstant;
import com.dishflow.context.BaseContext;
import com.dishflow.enumeration.OperationType;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

/**
 * 自动填充切面类
 * 用于处理带有@AutoFill注解的方法，自动填充公共字段
 */
@Aspect
@Slf4j
@Component
public class AutoFillAspect {

    /**
     * 切入点：匹配所有com.dishflow.mapper包下的方法，并且方法上有@AutoFill注解
     * 使用execution表达式指定切入点，并添加@annotation条件确保只有带有@AutoFill注解的方法才会被切面处理
     */
    @Pointcut("execution(* com.dishflow.mapper.*.*(..)) && @annotation(com.dishflow.annotation.AutoFill)")
    public void autoFillCut(){}
    /**
     * 前置通知：在目标方法执行前执行
     * 当切入点匹配的方法被调用时，会自动执行此方法，用于处理公共字段的自动填充
     */
    @Before("autoFillCut()") // 切入点表达式，指定哪些方法需要被拦截
    public void autoFill(JoinPoint joinPoint){
        log.info("自动填充公共字段"); // 记录日志，表示自动填充公共字段的操作开始
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();;//方法签名对象
        AutoFill autoFill = signature.getMethod().getAnnotation(AutoFill.class);//获得方法上的注解对象
        OperationType operationType = autoFill.value();

        Object[] args = joinPoint.getArgs();
        if(args == null || args.length == 0){
            return;
        }

        Object entity = args[0];
        //准备赋值的数据
        LocalDateTime now = LocalDateTime.now();
        Long currentId = BaseContext.getCurrentId();

        if(operationType == OperationType.INSERT){
            try {
                Method setCreateTime = entity.getClass().getDeclaredMethod(AutoFillConstant.SET_CREATE_TIME, LocalDateTime.class);
                Method setCreateUser = entity.getClass().getDeclaredMethod(AutoFillConstant.SET_CREATE_USER, Long.class);
                Method setUpdateTime = entity.getClass().getDeclaredMethod(AutoFillConstant.SET_UPDATE_TIME, LocalDateTime.class);
                Method setUpdateUser = entity.getClass().getDeclaredMethod(AutoFillConstant.SET_UPDATE_USER, Long.class);

                //通过反射为对象属性赋值
                setCreateTime.invoke(entity,now);
                setCreateUser.invoke(entity,currentId);
                setUpdateTime.invoke(entity,now);
                setUpdateUser.invoke(entity,currentId);
            } catch (Exception e) {
                e.printStackTrace();
            }

        }else if(operationType == OperationType.UPDATE){
            //为2个公共字段赋值
            try {
                Method setUpdateTime = entity.getClass().getDeclaredMethod(AutoFillConstant.SET_UPDATE_TIME, LocalDateTime.class);
                Method setUpdateUser = entity.getClass().getDeclaredMethod(AutoFillConstant.SET_UPDATE_USER, Long.class);

                //通过反射为对象属性赋值
                setUpdateTime.invoke(entity,now);
                setUpdateUser.invoke(entity,currentId);
            } catch (Exception e) {
                e.printStackTrace();
            }

        }




    }
}
