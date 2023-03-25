package com.hmdp.utils;

/**
 * @author zuodong
 * @create 2023-03-24 15:29
 */

public interface ILock {

    /**
     * 尝试获取锁
     * @param timeoutSec  锁持有的超时时间，过期后自动释放
     * @return  true 表示获取锁成功 ； false 表示获取锁失败
     */
    boolean tryLock(long timeoutSec);

    /**
     *  释放锁
     */
    void unlock();
}
