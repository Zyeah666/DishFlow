package com.dishflow.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import com.dishflow.enumeration.OperationType;

/**
 * 自定义注解，用于标识需要进行自动填充的方法
 * 该注解可以应用于方法上，并在运行时保留
 */
@Target({ElementType.METHOD})  // 注解的目标元素类型为方法
@Retention(RetentionPolicy.RUNTIME)  // 注解的保留策略为运行时
public @interface AutoFill {

    /**
     * 定义注解的属性，用于指定操作类型
     * @return 返回操作类型的枚举值
     */
    OperationType value();  // 必须属性，用于指定操作类型
}
