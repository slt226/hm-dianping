package com.hmdp.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Resource
    @Lazy
    private IBlogService blogService;

    /**
     * 关注/取关
     */
    @Override
    @Transactional
    public Result follow(Long followUserId, Boolean isFollow) {
        // 1.获取当前登录用户
        UserDTO userDTO = UserHolder.getUser();
        if (userDTO == null) {
            return Result.fail("未登录");
        }
        Long userId = userDTO.getId();

        String followsKey = RedisConstants.FOLLOWS_KEY + userId;
        String fansKey = RedisConstants.FANS_KEY + followUserId;

        if (isFollow) {
            // 2.关注：保存到数据库
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            baseMapper.insert(follow);
            // 3.保存到Redis Set：当前用户的关注集合 + 被关注用户的粉丝集合
            try {
                stringRedisTemplate.opsForSet().add(followsKey, followUserId.toString());
                stringRedisTemplate.opsForSet().add(fansKey, userId.toString());
            } catch (Exception e) {
                log.error("关注写入Redis失败", e);
            }
            // 4.关注成功后，把被关注用户的历史笔记推送进当前用户的 Feed 收件箱（推模式）
            try {
                pushHistoryBlogToFeed(userId, followUserId);
            } catch (Exception e) {
                log.error("关注回填Feed失败", e);
            }
        } else {
            // 2.取关：从数据库删除（使用 QueryWrapper 避免高版本 JDK 下 LambdaQueryWrapper 的反射兼容性问题）
            baseMapper.delete(new QueryWrapper<Follow>()
                    .eq("user_id", userId)
                    .eq("follow_user_id", followUserId));
            // 3.从Redis Set中移除
            try {
                stringRedisTemplate.opsForSet().remove(followsKey, followUserId.toString());
                stringRedisTemplate.opsForSet().remove(fansKey, userId.toString());
            } catch (Exception e) {
                log.error("取关删除Redis失败", e);
            }
        }
        return Result.ok();
    }

    /**
     * 查询是否已关注
     */
    @Override
    public Result queryFollow(Long followUserId) {
        // 1.获取当前登录用户
        UserDTO userDTO = UserHolder.getUser();
        if (userDTO == null) {
            return Result.ok(false);
        }
        Long userId = userDTO.getId();
        String followsKey = RedisConstants.FOLLOWS_KEY + userId;

        // 2.先从Redis判断
        try {
            Boolean isFollow = stringRedisTemplate.opsForSet().isMember(followsKey, followUserId.toString());
            if (isFollow != null) {
                return Result.ok(isFollow);
            }
        } catch (Exception e) {
            log.error("查询关注状态Redis异常，回退数据库查询", e);
        }

        // 3.Redis不存在，从数据库查询并回填缓存（使用 QueryWrapper 避免高版本 JDK 下 LambdaQueryWrapper 的反射兼容性问题）
        Integer count = baseMapper.selectCount(new QueryWrapper<Follow>()
                .eq("user_id", userId)
                .eq("follow_user_id", followUserId));
        boolean followed = count != null && count > 0;
        if (followed) {
            try {
                stringRedisTemplate.opsForSet().add(followsKey, followUserId.toString());
            } catch (Exception e) {
                log.error("回填关注缓存失败", e);
            }
        }
        return Result.ok(followed);
    }

    /**
     * 查询共同关注（使用Redis SINTER实现Set交集）
     */
    @Override
    public Result queryCommonFollow(Long userId) {
        // 1.获取当前登录用户
        UserDTO userDTO = UserHolder.getUser();
        if (userDTO == null) {
            return Result.ok(Collections.emptyList());
        }
        Long currentUserId = userDTO.getId();
        String followsKey1 = RedisConstants.FOLLOWS_KEY + currentUserId;
        String followsKey2 = RedisConstants.FOLLOWS_KEY + userId;

        // 2.使用SINTER获取两个关注集合的交集
        Set<String> intersect;
        try {
            intersect = stringRedisTemplate.opsForSet().intersect(followsKey1, followsKey2);
        } catch (Exception e) {
            log.error("查询共同关注Redis异常", e);
            return Result.ok(Collections.emptyList());
        }
        if (intersect == null || intersect.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        // 3.解析用户ID列表
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());

        // 4.查询用户信息
        List<User> users = userService.listByIds(ids);
        if (CollectionUtil.isEmpty(users)) {
            return Result.ok(Collections.emptyList());
        }

        // 5.转为UserDTO返回
        List<UserDTO> userDTOS = users.stream().map(user -> {
            UserDTO dto = new UserDTO();
            dto.setId(user.getId());
            dto.setNickName(user.getNickName());
            dto.setIcon(user.getIcon());
            return dto;
        }).collect(Collectors.toList());

        return Result.ok(userDTOS);
    }

    /**
     * 查询指定用户的关注数和粉丝数
     */
    @Override
    public Result queryCount(Long userId) {
        String followsKey = RedisConstants.FOLLOWS_KEY + userId;
        String fansKey = RedisConstants.FANS_KEY + userId;
        Long followCount = stringRedisTemplate.opsForSet().size(followsKey);
        Long fansCount = stringRedisTemplate.opsForSet().size(fansKey);
        Map<String, Long> map = new HashMap<>();
        map.put("followCount", followCount == null ? 0L : followCount);
        map.put("fansCount", fansCount == null ? 0L : fansCount);
        return Result.ok(map);
    }

    /**
     * 关注成功后，把被关注用户的历史笔记（最新3条）推送进当前用户的 Feed 收件箱
     * 推模式：score 用笔记 id（自增，越大越新），避免时间戳相同导致排序混乱
     */
    private void pushHistoryBlogToFeed(Long userId, Long followUserId) {
        List<Blog> blogs = blogService.query()
                .eq("user_id", followUserId)
                .orderByDesc("id")
                .last("LIMIT 3")
                .list();
        if (blogs == null || blogs.isEmpty()) {
            return;
        }
        String feedKey = RedisConstants.FEED_KEY + userId;
        for (Blog blog : blogs) {
            stringRedisTemplate.opsForZSet().add(feedKey, blog.getId().toString(), blog.getId().doubleValue());
        }
    }
}