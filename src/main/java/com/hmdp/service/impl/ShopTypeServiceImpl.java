package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOPType_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryforShopList() {
        String key = CACHE_SHOPType_KEY;
        // 1.从redis查询商铺类型缓存
        List<String> shopTypeList = stringRedisTemplate.opsForList().range(CACHE_SHOPType_KEY,0,9);

        // 2.判断是否存在
        if (shopTypeList != null && !shopTypeList.isEmpty()) {
          // 3.存在，直接返回
            List<ShopType> shopType = JSONUtil.toList(shopTypeList.get(0), ShopType.class);
            return Result.ok(shopType);
        }

        // 4.不存在，查询数据库
        List<ShopType> shopType = query().orderByAsc("sort").list();

        // 5.数据库不存在，返回404
        if (shopType == null || shopType.isEmpty() ) {
            return Result.fail("店铺类型不存在!");
        }

        // 6.数据库存在，将数据写入redis
        stringRedisTemplate.opsForList().leftPush(CACHE_SHOPType_KEY,JSONUtil.toJsonStr(shopType));

        // 7.返回
        return Result.ok(shopType);

    }
}
