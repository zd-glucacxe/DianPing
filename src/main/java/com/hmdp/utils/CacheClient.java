package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.events.Event;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * @author zuodong
 * @create 2023-03-01 18:24
 */

@Slf4j
@Component
public class CacheClient {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

//    public CacheClient(StringRedisTemplate stringRedisTemplate) {
//        this.stringRedisTemplate = stringRedisTemplate;
//    }

    public void set(String key, Object value, Long time, TimeUnit unit){
        //把对象序列化为字符串
        String jsonStr = JSONUtil.toJsonStr(value);
        stringRedisTemplate.opsForValue().set(key,jsonStr,time,unit);
    }

    /**
     * 重建缓存
     *
     * 逻辑过期
     * 将过期时间添加到Redis中
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        //写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }






    /**
     * 缓存穿透工具类
     *
     * @param <R>           R 为函数返回值类型
     * @param <ID>          ID 为参数类型
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallback    Function<ID, R> 传一个有参有返回值的函数 用Function<>
     * @param time
     * @param unit
     * @return
     */
    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){

        String key = keyPrefix + id;

        // 1.从redis查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2.判断是否存在
        //isNotBlank() 只有是字符串才返回ture
        if (StrUtil.isNotBlank(json)) {
            // 3.存在，直接返回
                //return R类型  type 为R类型
            return JSONUtil.toBean(json, type);
        }
        //判断命中的是否是空值
        if (json != null){
            //不为null，即说明为""
            return null;
        }

        // 4.不存在，根据id查询数据库
        //apply方法只是简单地对转换过的数据进行传递而已
        R r = dbFallback.apply(id);

        // 5.数据库不存在，返回错误
        if (r == null) {
            //将空值写入Redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            //返回错误
            return null;
        }

        // 6.数据库存在，将数据写入redis
        this.set(key, r, time, unit);
        // 7.返回
        return r;
    }


    /**
     * 缓存重建的线程池
     * @return
     */
    private static ThreadPoolExecutor exectorPool() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                5,
                //根据自己的处理器数量+1
                Runtime.getRuntime().availableProcessors()+1,
                2L,
                TimeUnit.SECONDS,
                new LinkedBlockingDeque<>(3),
                Executors.defaultThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }



    /**
     * 逻辑过期解决缓存击穿工具类
     *
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallBack
     * @param time
     * @param unit
     * @param <R>
     * @param <ID>
     * @return
     */
    public <R,ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallBack, Long time, TimeUnit unit){

        String key = keyPrefix + id;

        // 1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2.判断是否存在
        //isBlank() 为空
        if (StrUtil.isBlank(json)) {
            // 3.未命中，直接返回
            return null;
        }

        // 4.命中,需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        //本来是Object 强转为JsonObject
        JSONObject data = (JSONObject) redisData.getData();
        //得到店铺信息
        R r = JSONUtil.toBean(data, type);
        //过期时间
        LocalDateTime expireTime = redisData.getExpireTime();

        // 5.判断是否过期, 过期时间是否是再当前时间之后  A.isAfter(B)---> A的时间在B的时间之后?
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 5.1 未过期，直接返回商铺信息
            return r;
        }
        // 5.2 已过期，需要缓存重建 过期时间比当前时间小
        // 6.缓存重建
        // 6.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 6.2获取互斥锁是否成功
        if (isLock) {
            //双重校验是必须的，因为可能线程一正准备去重建时，线程二已经重建完成并释放了锁
            // 6.3成功，开启独立线程，实现缓存重建
            exectorPool().execute(() -> {
                try {
                    //重建缓存
                     //查询数据库
                    R r1 = dbFallBack.apply(id);

                    //写入Redis
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }
            });

        }
        // 6.4失败，直接返回过期的商铺信息
        return r;
    }


    /**
     * 获取互斥锁
     * @param key
     * @return
     */
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放互斥锁
     * @param key
     */
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }





}
