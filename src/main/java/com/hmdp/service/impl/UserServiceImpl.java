package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_CODE_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_CODE_TTL;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendcode(String phone) {
        // 1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误!");
        }
        // 3.符合，生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 4.保存验证码到 Redis，有效期 2 分钟
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // 5.发送验证码
        log.debug("发送短信验证码成功,验证码为{}", code);
        // 6.返回OK
        return Result.ok();
    }

    @Override
    public Result log(LoginFormDTO loginForm) {
        // 1.校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误!");
        }
        // 3.从 Redis 获取验证码并校验
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)) {
            // 4.不一致，报错
            return Result.fail("验证码错误!");
        }
        // 5.一致，根据手机号查询用户
        User user = query().eq("phone", phone).one();
        // 6.判断用户是否存在
        if (user == null) {
            // 7.不存在，创建新用户并保存
            user = createUserWithPhone(phone);
        }
        // 8.保存用户信息到 Redis
        // 8.1.随机生成 token，作为登录令牌
        String token = IdUtil.fastSimpleUUID();
        // 8.2.将 User 对象转为 UserDTO 存储，避免敏感字段泄露
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 8.3.存储到 Redis，key 为 login:token:{token}，有效期 10 小时
        stringRedisTemplate.opsForValue().set(LOGIN_USER_KEY + token, JSONUtil.toJsonStr(userDTO), LOGIN_USER_TTL, TimeUnit.SECONDS);
        // 9.返回 token，前端保存后每次请求携带
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        // 1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName("user_" + RandomUtil.randomString(10));
        // 2.保存用户
        save(user);
        return user;
    }
}
