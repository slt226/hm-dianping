package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.service.IBlogService;
import cn.hutool.core.util.StrUtil;
import com.hmdp.utils.UserHolder;
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
@RequestMapping("/blog")
public class BlogController {

    @Resource
    private IBlogService blogService;

    /**
     * 发布探店笔记（并推送给粉丝 Feed）
     */
    @PostMapping
    public Result saveBlog(@RequestBody Blog blog) {
        // 1.校验参数
        if (blog.getShopId() == null) {
            return Result.fail("请选择关联商户");
        }
        if (StrUtil.isBlank(blog.getTitle())) {
            return Result.fail("请输入标题");
        }
        // 2.获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 3.保存探店博文并推送给粉丝
        return blogService.saveBlog(blog);
    }

    /**
     * 点赞功能（一人一赞，再次点击取消点赞）
     */
    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable("id") Long id) {
        return blogService.likeBlog(id);
    }

    /**
     * 点赞排行榜（按点赞时间排序，最早点赞的 Top5）
     */
    @GetMapping("/likes/{id}")
    public Result queryBlogLikes(@PathVariable("id") Long id) {
        return blogService.queryBlogLikes(id);
    }

    /**
     * 查看笔记详情
     */
    @GetMapping("/{id}")
    public Result queryBlogById(@PathVariable("id") Long id) {
        return blogService.queryBlogById(id);
    }

    /**
     * 查询我的笔记
     */
    @GetMapping("/of/me")
    public Result queryMyBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.queryMyBlog(current);
    }

    /**
     * 查询指定用户的笔记
     */
    @GetMapping("/of/user")
    public Result queryBlogOfUser(@RequestParam("id") Long userId,
                                  @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.queryBlogOfUser(userId, current);
    }

    /**
     * 查询热门笔记
     */
    @GetMapping("/hot")
    public Result queryHotBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.queryHotBlog(current);
    }

    /**
     * 查询关注流（滚动分页）
     */
    @GetMapping("/of/follow")
    public Result queryBlogOfFollow(@RequestParam("lastId") Long max,
                                    @RequestParam(value = "offset", defaultValue = "0") Integer offset) {
        return blogService.queryBlogOfFollow(max, offset);
    }
}
