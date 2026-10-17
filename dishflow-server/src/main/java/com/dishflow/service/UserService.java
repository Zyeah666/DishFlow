package com.dishflow.service;

import com.dishflow.dto.UserLoginDTO;
import com.dishflow.entity.User;

public interface UserService {
/**
 * 微信登录方法
 * @param userLoginDTO 用户登录数据传输对象，包含微信登录所需的信息
 * @return User 登录成功后返回的用户对象
 */
    User wxLogin(UserLoginDTO userLoginDTO);
}
