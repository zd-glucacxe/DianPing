package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

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

    @Override
    public Result queryById(Long id) {
        //缓存穿透
        //Shop shop = queryWithPassThrough(id);

        //互斥锁解决缓存击穿
        Shop shop = queryWithMutex(id);
        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        // 7.返回
        return Result.ok(shop);
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
