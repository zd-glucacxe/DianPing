package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * @author zuodong
 * @create 2023-03-24 15:32
 */

public class SimpleRedisLock implements ILock{

    private String name;
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 构造器函数注入
     * @param name
     * @param stringRedisTemplate
     */
    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }



    private static final String KEY_PREFIX = "lock:";
    /**
     * 这里用了static final 关键字，同一个JVM中UUID是相同的，通过不同的线程ID来区分
     */
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "—";
    /**
     * 定义了一个名为 UNLOCK_SCRIPT 的静态常量，它是一个 Redis Lua 脚本对象。
     * 这个对象是使用 DefaultRedisScript 类创建的
     */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    /**
     * 在类加载时初始化 UNLOCK_SCRIPT 常量。
     * 使用 setLocation() 方法将 unlock.lua 资源文件加载到 DefaultRedisScript 对象中。
     * 使用 setResultType() 方法将该脚本的返回类型设置为 long.class
     */
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(long.class);
    }


    @Override
    public boolean tryLock(long timeoutSec) {
        //获取当前线程的标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        //获取锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name,
                threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }


    @Override
    public void unlock() {
        //调用Lua脚本
        stringRedisTemplate.execute(
                //要执行的 Redis Lua 脚本对象
                UNLOCK_SCRIPT,
                //参数列表，这里使用了 Collections.singletonList() 方法
                // 将锁的名称和当前线程的 ID 封装成一个列表
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId());
    }
//
//    @Override
//    public void unlock() {
//        //获取线程标识
//        String threadId = ID_PREFIX + Thread.currentThread().getId();
//        //获取锁中的标识
//        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        //判断标识是否一致
//        if (threadId.equals(id)) {
//        //释放锁
//        stringRedisTemplate.delete(KEY_PREFIX + name);
//        }
//    }
}
