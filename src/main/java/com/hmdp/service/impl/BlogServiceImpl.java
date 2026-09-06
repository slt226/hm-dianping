package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    @Lazy
    private IFollowService followService;

    /**
     * 查询热门笔记
     */
    @Override
    public Result queryHotBlog(Integer current) {
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();
        // 为每条笔记填充用户信息和点赞状态
        records.forEach(blog -> {
            queryBlogUser(blog);
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    /**
     * 查询当前用户的笔记（并填充点赞状态）
     */
    @Override
    public Result queryMyBlog(Integer current) {
        Long userId = UserHolder.getUser().getId();
        Page<Blog> page = query()
                .eq("user_id", userId)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();
        // 为每条笔记填充用户信息和点赞状态
        records.forEach(blog -> {
            queryBlogUser(blog);
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    /**
     * 查询指定用户的笔记（分页，并填充用户信息和点赞状态）
     */
    @Override
    public Result queryBlogOfUser(Long userId, Integer current) {
        Page<Blog> page = query()
                .eq("user_id", userId)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();
        // 为每条笔记填充用户信息和点赞状态
        records.forEach(blog -> {
            queryBlogUser(blog);
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    /**
     * 查看笔记详情
     */
    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }
        queryBlogUser(blog);
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    /**
     * 点赞功能（Redis ZSet 实现一人一赞 + 排行榜）
     */
    @Override
    public Result likeBlog(Long id) {
        // 1. 查询笔记是否存在
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }
        // 2. 获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        // 3. 判断当前用户是否已经点赞（ZSCORE 返回 null 表示未点赞）
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            // 3.1 未点赞：数据库点赞数+1，Redis ZSet 记录点赞时间
            boolean success = update().setSql("liked = liked + 1").eq("id", id).update();
            if (success) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            // 3.2 已点赞：数据库点赞数-1，Redis ZSet 移除用户
            boolean success = update().setSql("liked = liked - 1").eq("id", id).update();
            if (success) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 点赞排行榜（按点赞时间排序，最早点赞的排前面）
     */
    @Override
    public Result queryBlogLikes(Long id) {
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        // 1. 查询 ZSet 中点赞时间最早的 Top5 用户
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 2. 解析用户ID
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        // 3. 查询用户信息（WHERE id IN (id1, id2, ...) ORDER BY FIELD(id, id1, id2, ...)）
        String idStr = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        List<User> users = userService.query()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")")
                .list();
        // 4. 转为 UserDTO（脱敏，不返回密码、手机号等）
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
     * 保存笔记并推送给所有粉丝的 Feed（推模式）
     */
    @Override
    public Result saveBlog(Blog blog) {
        // 1. 保存笔记到数据库
        save(blog);
        // 2. 查询当前作者的所有粉丝
        List<Follow> follows = followService.query()
                .eq("follow_user_id", blog.getUserId())
                .list();
        // 3. 把新笔记 id 推送到每个粉丝的 Feed 收件箱
        if (follows != null && !follows.isEmpty()) {
            for (Follow follow : follows) {
                String key = RedisConstants.FEED_KEY + follow.getUserId();
                stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), blog.getId().doubleValue());
            }
        }
        // 4. 返回笔记 id
        return Result.ok(blog.getId());
    }

    /**
     * 查询当前用户关注流的笔记（滚动分页，推模式收件箱）
     */
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 1. 获取当前登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.ok();
        }
        Long userId = user.getId();
        String key = RedisConstants.FEED_KEY + userId;
        // 2. 从收件箱倒序查询（ZREVRANGEBYSCORE），每次取 2 条，支持滚动
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        // 3. 解析笔记 id，并计算下一次查询的游标（minTime / offset）
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            ids.add(Long.valueOf(tuple.getValue()));
            long time = tuple.getScore().longValue();
            if (time == minTime) {
                os++;
            } else {
                minTime = time;
                os = 1;
            }
        }
        // 4. 查询笔记（保持 ZSet 中的顺序）
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")")
                .list();
        for (Blog blog : blogs) {
            queryBlogUser(blog);
            isBlogLiked(blog);
        }
        // 5. 封装滚动结果返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setMinTime(minTime);
        r.setOffset(os);
        return Result.ok(r);
    }

    /**
     * 填充笔记作者信息（名称、头像）
     */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        if (user != null) {
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
        }
    }

    /**
     * 判断当前用户是否已点赞该笔记
     */
    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // 未登录，不查询点赞状态
            return;
        }
        Long userId = user.getId();
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }
}
