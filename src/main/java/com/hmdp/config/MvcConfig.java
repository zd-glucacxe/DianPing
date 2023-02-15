package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

/**
 * @author zuodong
 * @create 2023-02-07 16:47
 */
@Configuration
public class MvcConfig implements WebMvcConfigurer {

//  @Resource
//  private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private LoginInterceptor loginInterceptor;


    @Autowired
    private RefreshTokenInterceptor refreshTokenInterceptor;
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginInterceptor)
        .excludePathPatterns("/user/code",
                             "/user/login",
                             "/blog/hot",
                             "/shop/**",
                             "/shop-type/**",
                             "upload/**",
                             "/voucher/**"

        ).order(1);
        /**
         * order(0);设置拦截器的先后顺序
         */
        registry.addInterceptor(refreshTokenInterceptor).addPathPatterns("/**").order(0);
    }
}
