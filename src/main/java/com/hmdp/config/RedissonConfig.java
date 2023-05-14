package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author zuodong
 * @create 2023-03-25 15:40
 */
@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient(){
        //配置
        Config config = new Config();
        config.useSingleServer().setAddress("redis://192.168.48.128:6379").setPassword("abc123");
        //创建RedissonClient对象
        return Redisson.create(config);
    }

    @Bean
    public RedissonClient redissonClient2(){
        //配置
        Config config = new Config();
        config.useSingleServer().setAddress("redis://192.168.48.128:6380");
        //创建RedissonClient对象
        return Redisson.create(config);
    }

    @Bean
    public RedissonClient redissonClient3(){
        //配置
        Config config = new Config();
        config.useSingleServer().setAddress("redis://192.168.48.128:6381");
        //创建RedissonClient对象
        return Redisson.create(config);
    }

}
