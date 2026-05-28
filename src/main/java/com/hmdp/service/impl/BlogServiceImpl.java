package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

/**
 * 博客服务实现类
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryHotBlog(Integer current) {
        // 1. 按点赞数量倒序分页查询热门博客
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));

        // 2. 查询博客作者信息，并判断当前登录用户是否点赞
        List<Blog> records = page.getRecords();
        records.forEach(blog -> {
            queryBlogUser(blog);
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        // 1. 根据 id 查询博客
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在");
        }

        // 2. 查询博客作者信息
        queryBlogUser(blog);
        // 3. 判断当前登录用户是否点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    @Override
    public Result likeBlog(Long id) {
        // 1. 获取当前登录用户

        Long userId = UserHolder.getUser().getId();
        String key = BLOG_LIKED_KEY + id;

        // 2. 判断当前用户是否已经点赞，ZSet 的 score 不为空表示已点赞
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            // 3. 未点赞：数据库点赞数 +1，并把用户 id 写入 ZSet，score 用点赞时间
            boolean isSuccess = update()
                    .setSql("liked = liked + 1")
                    .eq("id", id)
                    .update();
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            // 4. 已点赞：数据库点赞数 -1，并从 ZSet 中移除用户 id
            boolean isSuccess = update()
                    .setSql("liked = liked - 1")
                    .eq("id", id)
                    .update();
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;
        //1.查询top5的点赞用户
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(top5 == null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }

        //2.解析其中的用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);

        //3.根据用户id查询用户
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        //4.返回结果
        return Result.ok(userDTOS);
    }

    private void isBlogLiked(Blog blog) {
        // 首页热门博客允许未登录访问，未登录时直接标记为未点赞
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            //用户未登录，无需查询是否点赞
            return;
        }

        // 查询当前用户是否在博客点赞 ZSet 中
        String key = "blog:liked:" + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, user.getId().toString());
        blog.setIsLike(score != null);
    }

    private void queryBlogUser(Blog blog) {
        // 根据博客中的 userId 查询作者信息，并回填到 Blog 展示字段
        User user = userService.getById(blog.getUserId());
        if (user == null) {
            return;
        }
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
