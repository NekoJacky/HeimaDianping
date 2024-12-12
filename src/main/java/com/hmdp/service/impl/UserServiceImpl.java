package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("Invalid phone number format");
        }
        // 生成并保存验证码
        String code = RandomUtil.randomNumbers(4);
        session.setAttribute("phone", phone);
        session.setAttribute("code", code);
        // 发送验证码
        // 手机验证码的发动需要使用第三方平台，方便起见我们直接输出到控制台
        log.debug("短信验证码为：" + code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 校验手机号和验证码
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("Invalid phone number format");
        }
        String code = loginForm.getCode();
        Object sessionPhone = session.getAttribute("phone");
        Object sessionCode = session.getAttribute("code");
        if(sessionPhone == null || sessionCode == null ||
                !sessionPhone.toString().equals(phone) ||
                !sessionCode.toString().equals(code)) {
            return Result.fail("Invalid phone or code");
        }
        // 查询用户，判断用户给是否存在
        User user = query().eq("phone", phone).one();
        if(user == null) {
            // 用户不存在，进行注册
            user = this.signUp(phone);
        }
        // 用户存在，进行登录（保存用户到session）
        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        return Result.ok();
    }

    private User signUp(String phone) {
        // 创建用户，写入需要的的用户信息
        User user = new User();
        user.setPhone(phone);
        user.setPassword(USER_NICK_NAME_PREFIX + RandomUtil.randomNumbers(6));
        // 保存用户
        save(user);
        return user;
    }
}
