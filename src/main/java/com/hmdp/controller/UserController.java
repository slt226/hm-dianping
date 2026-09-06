package com.hmdp.controller;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.entity.UserInfo;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 发送手机验证码
     */
    @PostMapping("code")
    public Result sendCode(@RequestParam("phone") String phone) {
        // 发送短信验证码并保存验证码
        return userService.sendcode(phone);
    }

    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    @PostMapping("/login")
    public Result login(@RequestBody LoginFormDTO loginForm){
        //实现登录功能
        return userService.log(loginForm);
    }
    /**
     * 登出功能
     * @return 无
     */
    @PostMapping("/logout")
    public Result logout(HttpServletRequest request){
        // 1.获取请求头中的 token
        String token = request.getHeader("authorization");
        // 2.删除 Redis 中的登录态
        if (StrUtil.isNotBlank(token)) {
            stringRedisTemplate.delete(LOGIN_USER_KEY + token);
        }
        // 3.清理 ThreadLocal
        UserHolder.removeUser();
        return Result.ok();
    }

    @GetMapping("/me")
    public Result me(){
        // 获取当前登录的用户并返回
        UserDTO user = UserHolder.getUser();
        return Result.ok(user);
    }

    @GetMapping("/info/{id}")
    public Result info(@PathVariable("id") Long userId){
        // 查询详情
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            // 没有详情，应该是第一次查看详情
            return Result.ok();
        }
        info.setCreateTime(null);
        info.setUpdateTime(null);
        // 返回
        return Result.ok(info);
    }

    /**
     * 根据 id 查询用户基本信息
     */
    @GetMapping("/{id}")
    public Result queryUserById(@PathVariable("id") Long userId){
        // 1.查询用户
        User user = userService.getById(userId);
        if (user == null) {
            return Result.fail("用户不存在");
        }
        // 2.转为 UserDTO，避免返回敏感字段
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 3.返回
        return Result.ok(userDTO);
    }

    /**
     * 用户签到（BitMap 实现）
     * <p>
     * Redis key = sign:userId:yyyyMM，每月一个 BitMap；
     * offset = 当天是本月第几天 - 1，置 1 表示已签到。
     */
    @PostMapping("/sign")
    public Result sign() {
        // 1. 获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2. 获取当前日期
        LocalDateTime now = LocalDateTime.now();
        // 3. 拼接 Redis key：sign:userId:yyyyMM
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = RedisConstants.USER_SIGN_KEY + userId + keySuffix;
        // 4. 今天是本月的第几天（1-31）
        int dayOfMonth = now.getDayOfMonth();
        // 5. 写入签到：SETBIT key (dayOfMonth-1) 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    /**
     * 签到统计：统计本月截止今天为止的【连续】签到天数
     * <p>
     * 用 BITFIELD 一次性取出本月 1 号到今天的所有签到位（一个十进制数），
     * 低位 = 今天，逐位右移判断连续为 1 的天数即为连续签到天数。
     */
    @GetMapping("/sign/count")
    public Result signCount() {
        // 1. 获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2. 获取当前日期
        LocalDateTime now = LocalDateTime.now();
        // 3. 拼接 key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = RedisConstants.USER_SIGN_KEY + userId + keySuffix;
        // 4. 今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5. 用 BITFIELD 取出本月 1 号~今天共 dayOfMonth 个位（无符号）
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result == null || result.isEmpty()) {
            // 没有任何签到记录
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        // 6. 统计连续签到天数：今天(最低位)往前数，遇到 0 就中断
        int count = 0;
        while (true) {
            // 与 1 做与运算，得到最后一位（即今天）
            if ((num & 1) == 0) {
                break;
            } else {
                count++;
            }
            // 右移一位，准备判断前一天
            num >>>= 1;
        }
        return Result.ok(count);
    }

    /**
     * 今日 UV：统计 uv:yyyyMMdd（今日）的独立访客数（HyperLogLog）。
     * 用于前端「我的」页直接展示今日 UV 一行数字。
     */
    @GetMapping("/uv/today")
    public Result getTodayUv() {
        String key = RedisConstants.USER_UV_KEY
                + LocalDate.now(ZoneOffset.ofHours(8)).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        Long uv = stringRedisTemplate.opsForHyperLogLog().size(key);
        return Result.ok(uv == null ? 0L : uv);
    }

    /**
     * UV 统计：统计指定时间范围内的独立访客数（HyperLogLog）
     * <p>
     * 每日 UV 存在 uv:yyyyMMdd（HyperLogLog），统计时用 PFMERGE 把多天合并到
     * 临时 key uv:count，再 PFCOUNT 得到去重后的独立访客数。
     *
     * @param start 起始时间（毫秒时间戳，含）
     * @param end   结束时间（毫秒时间戳，含）
     */
    @GetMapping("/uv")
    public Result getUv(@RequestParam("start") Long start, @RequestParam("end") Long end) {
        // 1. 准备 key 集合
        List<String> keyList = new ArrayList<>();
        // 2. 把毫秒时间戳按东八区转成本地日期
        LocalDate beginDate = Instant.ofEpochMilli(start).atZone(ZoneOffset.ofHours(8)).toLocalDate();
        LocalDate endDate = Instant.ofEpochMilli(end).atZone(ZoneOffset.ofHours(8)).toLocalDate();
        // 3. 遍历 [beginDate, endDate] 每天，拼出当天的 UV key
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyyMMdd");
        for (LocalDate date = beginDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            keyList.add(RedisConstants.USER_UV_KEY + date.format(fmt));
        }
        // 4. 合并统计（union = PFMERGE + PFCOUNT），结果写入临时 key uv:count
        Long uv = stringRedisTemplate.opsForHyperLogLog().union("uv:count", keyList.toArray(new String[0]));
        return Result.ok(uv);
    }
}
