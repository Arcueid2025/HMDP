package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    /**
    开始时间戳
   */

    private static final long BEGIN_TIMESTAMP = 1640995200L;
    /**
     * 序列号位数
     */
    private static final int COUNT_BITS = 32;
    private final StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public Long nextId(String keyPrefix) {

        //1.生成时间戳
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = nowSecond - BEGIN_TIMESTAMP;

        //2.生成序列号
        //2.1获取当前日期，精确到天
        String day = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + day);
        //3.拼接并返回
        return timeStamp << COUNT_BITS | count;

    }

    public static void main(String[] args) {
        LocalDateTime time = LocalDateTime.of(2026, 5, 22, 20, 52, 55);
        long second = time.toEpochSecond(ZoneOffset.UTC);
        System.out.println("second = " + second);


    }
}
