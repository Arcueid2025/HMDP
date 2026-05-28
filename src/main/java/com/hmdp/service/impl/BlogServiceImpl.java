package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
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
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * 博客服务实现类
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

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

    @Override
    public Result saveBlog(Blog blog) {
        // 1. 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 2. 保存探店笔记
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("新增笔记失败");
        }

        //3.查询笔记作者的所有粉丝
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();

        //4.推送笔记id给所有粉丝
        for (Follow follow : follows) {
            //4.1.获取粉丝id
            Long userId = follow.getUserId();

            //4.2.推送
            String key = FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }


        // 3. 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();

        //2.查询收件箱
        String key = FEED_KEY  + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);

        //3.非空判断

        if(typedTuples == null || typedTuples.isEmpty()){
            ScrollResult r = new ScrollResult();
            r.setList(Collections.emptyList());
            r.setMinTime(0L);
            r.setOffset(0);
            return Result.ok(r);
        }
        //4。解析数据：blogId、minTime（时间戳）、offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;

        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            //4.1 获取ID
            ids.add(Long.valueOf(tuple.getValue()));

            //4.2 获取分数(时间戳)
            long time = tuple.getScore().longValue();
            if(time == minTime){
                os++;
            }else{
                minTime = time;
                os = 1;

            }


        }

        //5.根据id查询blog
        String idStr = StrUtil.join(",", ids);

        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();

        for (Blog blog : blogs) {
            // 5.1. 查询博客作者信息
            queryBlogUser(blog);
            // 5.1. 判断当前登录用户是否点赞
            isBlogLiked(blog);
        }

        //6.封装并返回
        ScrollResult r = new ScrollResult();
        r.setOffset(os);
        r.setList(blogs);
        r.setMinTime(minTime);
        return Result.ok(r);
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
