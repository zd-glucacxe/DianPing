package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public Result seckillVoucher(Long voucherId) {
        //1. 查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        //2. 判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            //未开始
            return Result.fail("秒杀尚未开始！");
        }

        //3.判断秒杀是否已经结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            //已经结束
            return Result.fail("秒杀已经结束！");
        }

        //4. 判断库存是否充足
        if (voucher.getStock() < 1) {
            // 库存不足
            return Result.fail("库存不足！");
        }


//        Long userId = UserHolder.getUser().getId();
//        synchronized (userId.toString().intern()) {
//            //获取代理对象(事务)
//            return this.createVoucherOrder(voucherId);
//        }

        Long userId = UserHolder.getUser().getId();
        //创建锁对象
        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        //获取锁
        boolean isLock = lock.tryLock(1200);
        //判断是否获取锁成功
        if (!isLock) {
            //获取锁失败, 返回错误
            return Result.fail("不允许重复下单！！");

        }
        try {
            //获取代理对象(事务)
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            //释放锁
            lock.unlock();
        }


        //无法解决集群环境下的并发问题
        /*synchronized (userId.toString().intern()) {
            /**
             * 在 seckillVoucher 方法中，使用了 AopContext.currentProxy() 获取当前代理对象，并将其强制转换为 IVoucherOrderService 类型的代理对象，
             * 然后调用 createVoucherOrder 方法。这样可以确保 createVoucherOrder 方法在事务中执行。
             * 在 Spring AOP 中，代理对象是基于接口的代理对象或者是基于类的代理对象。当目标对象实现了接口时，Spring AOP 会使用基于接口的代理对象；当目标对象没有实现接口时，Spring AOP 会使用基于类的代理对象。
             * 在这里，VoucherOrderServiceImpl 类实现了 IVoucherOrderService 接口，因此在运行时，Spring AOP 会动态生成一个实现了 IVoucherOrderService 接口的代理对象。因此，在 seckillVoucher 方法中，使用 AopContext.currentProxy() 获取的是 IVoucherOrderService 接口的代理对象，而不是 VoucherOrderServiceImpl 类的代理对象。

            //获取代理对象(事务)
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
        */



    }

    @Transactional
    @Override
    public  Result createVoucherOrder(Long voucherId) {
            //5. 一人一单
            Long userId = UserHolder.getUser().getId();

            //intern() 确保当用户id一样是同一个对象

            //5.1 查询订单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            //5.2 判断订单是否存在
            if (count > 0) {
                //用户已经购买过
                return Result.fail("用户已经购买过一次！");
            }

            //6. 扣减库存   更新数据操作
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock -1")
                    // where voucher_id = ? and stock > 0
                    .eq("voucher_id", voucherId).gt("stock",0)
                    .update();
            if(!success){
                //扣减失败
                return Result.fail("库存不足！");
            }

            //7. 创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            //7.1.订单id
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            //7.2.用户id
            voucherOrder.setUserId(userId);
            //7.3.代金券id
            voucherOrder.setVoucherId(voucherId);

            //订单写入数据库   插入数据操作
            save(voucherOrder);

            //8. 返回订单ID
            return Result.ok(orderId);
    }
}
