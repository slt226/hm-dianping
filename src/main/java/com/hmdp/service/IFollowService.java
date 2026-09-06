package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IFollowService extends IService<Follow> {

    /**
     * 关注/取关
     * @param followUserId 被关注的用户ID
     * @param isFollow     true=关注, false=取关
     */
    Result follow(Long followUserId, Boolean isFollow);

    /**
     * 查询当前用户是否已关注指定用户
     * @param followUserId 被关注的用户ID
     */
    Result queryFollow(Long followUserId);

    /**
     * 查询共同关注
     * @param userId 目标用户ID
     */
    Result queryCommonFollow(Long userId);

    /**
     * 查询指定用户的关注数和粉丝数
     * @param userId 用户ID
     */
    Result queryCount(Long userId);
}