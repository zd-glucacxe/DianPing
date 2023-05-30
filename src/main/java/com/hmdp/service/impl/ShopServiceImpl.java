package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.*;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {


        String key = CACHE_SHOP_KEY + id;

        // 1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2.判断是否存在
        //isNotBlank() 只有是字符串才返回ture
        if (StrUtil.isNotBlank(shopJson)) {
            // 3.存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        //判断命中的是否是空值
        if (shopJson != null){
            //不为null，即说明为""
            return null;
        }

        // 4.不存在，根据id查询数据库
        Shop shop = getById(id);

        // 5.数据库不存在，返回错误
        if (shop == null) {
            //将空值写入Redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            //返回错误
            return null;
        }

        // 6.数据库存在，将数据写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 7.返回
        return Result.ok(shop);


        //缓存穿透
        //Shop shop = queryWithPassThrough(id);
        //工具类解决缓存穿透

        //lambda表达式   new Function<Long, Shop>() {
        //            @Override
        //            public Shop apply(Long id2) {
        //                return getById(id2);
        //            }
        //            简写为：  id2 -> getById(id2)
        //Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, id2 -> getById(id2), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);

        //逻辑过期解决缓存击穿
        //Shop shop = queryWithLogicalExpire(id);

        // 最终版
        //工具类解决缓存击穿
//        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, id2 -> getById(id2), 20L, TimeUnit.SECONDS);
//        if (shop == null) {
//            return Result.fail("店铺不存在！");
//        }
//        // 7.返回
//        return Result.ok(shop);
    }

    /**
     * 缓存重建的线程池
     *  不建议使用JUC创建线程池
     *  建议手动创建线程池
     */
    //private static final ExecutorService Cache_EXECUTOR_SERVICE = Executors.newFixedThreadPool(10);
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
     * 逻辑过期解决缓存击穿
     * @param id
     * @return
     */

    public Shop queryWithLogicalExpire(Long id){

        String key = CACHE_SHOP_KEY + id;

        // 1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2.判断是否存在
        //isBlank() 为空
        if (StrUtil.isBlank(shopJson)) {
            // 3.未命中，直接返回
            return null;
        }

        // 4.命中,需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        //本来是Object 强转为JsonObject
        JSONObject data = (JSONObject) redisData.getData();
        //得到店铺信息
        Shop shop = JSONUtil.toBean(data, Shop.class);
        //过期时间
        LocalDateTime expireTime = redisData.getExpireTime();

        // 5.判断是否过期, 过期时间是否是再当前时间之后  A.isAfter(B)---> A的时间在B的时间之后?
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 5.1 未过期，直接返回商铺信息
            return shop;
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
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }
            });

        }
        // 6.4失败，直接返回过期的商铺信息
        return shop;
    }

    /**
     * 互斥锁 + double check 解决缓存击穿
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id){
        String key = CACHE_SHOP_KEY + id;

        // 1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2.判断是否存在
        //isNotBlank() 只有是字符串才返回ture
        if (StrUtil.isNotBlank(shopJson)) {
            // 3.存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //判断命中的是否是空值(空串)
        if (shopJson != null){
            //不为null，即说明为""
            return null;
        }

        // 4.未命中，实现缓存重建
        // 4.1、获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            // 4.2、判断获取锁是否成功
            if(!isLock){
                // 4.3、失败，则休眠并重试
                Thread.sleep(50);
                /*
                 * 递归等待
                 * 这个return可以理解为后面的代码都不需要了，直接重新执行以便queryMutex方法
                 */
                return queryWithMutex(id);
            }

            // 4.4、成功，根据id查询数据库
            /**
             * 获取锁成功应该再次检测redis缓存是否还存在，做doubleCheck,如果存在则无需重建缓存。
             *
             * 双检测是因为如果线程2执行到获取锁成功的时候 证明线程1已经把缓存写入redis了
             * 但这个动作是在线程2获取锁成功的时候完成的
             */
            synchronized (this){
                // 1.从redis查询商铺缓存
                String shopJsonTwo = stringRedisTemplate.opsForValue().get(key);

                // 2.判断是否存在
                //isNotBlank() 只有是字符串才返回ture
                if (StrUtil.isNotBlank(shopJsonTwo)) {
                    // 3.存在，直接返回
                    return JSONUtil.toBean(shopJsonTwo, Shop.class);
                }
                //redis既没有key的缓存,但查出来信息不为null,则为“”
                if (shopJson != null){
                    //不为null，即说明为""
                    return null;
                }

                //未命中缓存
                shop = getById(id);
                //模拟重建的延时
                Thread.sleep(200);
                // 5.数据库不存在，返回错误
                if (shop == null) {
                    //将空值写入Redis
                    stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                    //返回错误
                    return null;
                }

                // 6.数据库存在，将数据写入redis
                stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
                }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 7.释放互斥锁
            unlock(lockKey);
        }
        // 8.返回
        return shop;
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


    /**
     * 重建缓存
     * 将过期时间添加到Redis中
     */
    public void saveShop2Redis(Long id, long expireSeconds) throws InterruptedException {
        //1.查询店铺数据
        Shop shop = getById(id);
        Thread.sleep(200);
        //2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));

    }

    /**
     * 缓存穿透解决
     * @param id
     * @return
     */
    public Shop queryWithPassThrough(Long id){

        String key = CACHE_SHOP_KEY + id;

        // 1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2.判断是否存在
        //isNotBlank() 只有是字符串才返回ture
        if (StrUtil.isNotBlank(shopJson)) {
            // 3.存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //判断命中的是否是空值
        if (shopJson != null){
            //不为null，即说明为""
            return null;
        }

        // 4.不存在，根据id查询数据库
        Shop shop = getById(id);

        // 5.数据库不存在，返回错误
        if (shop == null) {
            //将空值写入Redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            //返回错误
            return null;
        }

        // 6.数据库存在，将数据写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 7.返回
        return shop;
    }

    /**
     * 更新商铺信息
     * 采用删除策略，来解决双写问题
     * @param shop
     * @return
     */
    @Override
    @Transactional  //事务回滚
    public Result update(Shop shop) {
        Long shopId = shop.getId();

        if (shopId == null) {
            return Result.fail("店铺id不能为空!");
        }

        //1. 更新数据库
        updateById(shop);

        //2. 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());

        return Result.ok();
    }
}
