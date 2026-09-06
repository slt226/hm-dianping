package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Resource
    private IFollowService followService;

    /**
     * 查询是否已关注
     */
    @GetMapping("/or/not/{userId}")
    public Result queryFollow(@PathVariable("userId") Long userId) {
        return followService.queryFollow(userId);
    }

    /**
     * 关注/取关
     */
    @PutMapping("/{userId}/{isFollow}")
    public Result follow(@PathVariable("userId") Long userId, @PathVariable("isFollow") Boolean isFollow) {
        return followService.follow(userId, isFollow);
    }

    /**
     * 查询共同关注
     */
    @GetMapping("/common/{userId}")
    public Result queryCommonFollow(@PathVariable("userId") Long userId) {
        return followService.queryCommonFollow(userId);
    }

    /**
     * 查询关注数/粉丝数
     */
    @GetMapping("/count/{userId}")
    public Result queryCount(@PathVariable("userId") Long userId) {
        return followService.queryCount(userId);
    }
}