package com.hmdp.utils;

public interface ILock {

    /**
     * 尝试获取锁
     *
     * @param timeoutSec 锁的自动释放时间，单位：秒
     * @return true 表示获取锁成功，false 表示获取锁失败
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unlock();
}
