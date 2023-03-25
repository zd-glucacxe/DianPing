package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * @author DaVinci
 */
@Data
public class RedisData {
    /**
     * 逻辑过期时间
     */
    private LocalDateTime expireTime;
    /**
     * 存储数据的对象
     */
    private Object data;
}
