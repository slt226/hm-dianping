package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    Result queryHotBlog(Integer current);

    Result queryMyBlog(Integer current);

    /**
     * 查询指定用户的笔记（分页）
     */
    Result queryBlogOfUser(Long userId, Integer current);

    Result queryBlogById(Long id);

    Result likeBlog(Long id);

    Result queryBlogLikes(Long id);

    /**
     * 保存笔记并推送给所有粉丝的 Feed（推模式）
     */
    Result saveBlog(Blog blog);

    /**
     * 查询当前用户关注流的笔记（滚动分页）
     * @param max    上一次查询的最小时间戳（作为本次查询的上界）
     * @param offset 偏移量（跳过相同时间戳的条数）
     */
    Result queryBlogOfFollow(Long max, Integer offset);
}
