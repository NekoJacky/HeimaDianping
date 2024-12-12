package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 获取 session 中的用户
        HttpSession session = request.getSession();
        Object user = session.getAttribute("user");
        // 校验用户
        // 用户不存在，拦截
        // 用户存在，保存用户信息到 ThreadLocal ，放行
        if (user == null) {
            // 用户不存在，返回 401
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        UserHolder.saveUser((UserDTO) user);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
