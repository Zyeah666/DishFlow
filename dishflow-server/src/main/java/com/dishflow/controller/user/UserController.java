package com.dishflow.controller.user;

import com.dishflow.constant.JwtClaimsConstant;
import com.dishflow.dto.UserLoginDTO;
import com.dishflow.entity.User;
import com.dishflow.properties.JwtProperties;
import com.dishflow.result.Result;
import com.dishflow.service.UserService;
import com.dishflow.utils.JwtUtil;
import com.dishflow.vo.UserLoginVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController("user")
@RequestMapping("/user/user")
@Slf4j
public class UserController {
    @Autowired
    private UserService userService;
    @Autowired
    private JwtProperties jwtProperties;


    @PostMapping("/login")
    public Result<UserLoginVO> login(@RequestBody UserLoginDTO userLoginDTO) {
        log.info("用户登录，用户名：{}", userLoginDTO.getCode());
        //微信登陆
        User user = userService.wxLogin(userLoginDTO);
        //生成jwt令牌
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.USER_ID, user.getId());
        String token = JwtUtil.createJWT(
                jwtProperties.getUserSecretKey(),
                jwtProperties.getUserTtl(),
                claims);

        UserLoginVO userLoginVO = UserLoginVO.builder()
                .id(user.getId())
                .openid(user.getOpenid())
                .token(token)
                .build();

        return Result.success(userLoginVO);

    }

}

