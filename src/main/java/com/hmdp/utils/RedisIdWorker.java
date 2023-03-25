package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * @author zuodong
 * @create 2023-03-08 17:00
 */

@Component
public class RedisIdWorker {

    /**
     * 开始时间戳
     */
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    /**
     * 序列号的位数
     */
    private static final long COUNT_BITS = 32;


    @Resource
    private StringRedisTemplate stringRedisTemplate;



    public long nextId(String keyPrefix){
        //1. 生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        //2. 生成序列号
        //2.1.获取当前日期，精确到天(避免超过32位的上限，方便做统计)
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //2.2.自增长
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        //3. 拼接并返回(时间戳左移序列号所占的位数)
        return timestamp << COUNT_BITS | count;
    }


}
