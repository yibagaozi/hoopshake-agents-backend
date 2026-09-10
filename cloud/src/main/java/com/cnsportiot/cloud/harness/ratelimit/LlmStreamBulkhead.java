package com.cnsportiot.cloud.harness.ratelimit;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 全局 LLM 流并发舱壁
 * 限住同时进行的对话流数:一次流量尖峰不至于把内存/线程/提供方一起打爆,学生端 + 教师端共享同一配额
 * {@code maxPermits ≤ 0} 表示不限(no-op)。{@link #tryAcquire()} 非阻塞:拿不到即返回 false,
 * 调用方快速失败(42900),而不是排队堆积。占用横跨整条流,收尾时 {@link #release()}
 */
public final class LlmStreamBulkhead {

    private final int maxPermits;
    private final Semaphore semaphore;   // null = 不限
    private final AtomicLong rejected = new AtomicLong();

    public LlmStreamBulkhead(int maxPermits) {
        this.maxPermits = maxPermits;
        this.semaphore = maxPermits > 0 ? new Semaphore(maxPermits, true) : null;
    }

    /** 非阻塞获取一个许可;满载返回 false(记一次拒绝)。不限时恒 true */
    public boolean tryAcquire() {
        if (semaphore == null) {
            return true;
        }
        boolean ok = semaphore.tryAcquire();
        if (!ok) {
            rejected.incrementAndGet();
        }
        return ok;
    }

    /** 归还一个许可。必须与成功的 {@link #tryAcquire()} 一一对应 */
    public void release() {
        if (semaphore != null) {
            semaphore.release();
        }
    }

    /** 当前在途流数(供指标 gauge) */
    public int active() {
        return semaphore == null ? 0 : maxPermits - semaphore.availablePermits();
    }

    /** 剩余可用许可 */
    public int available() {
        return semaphore == null ? Integer.MAX_VALUE : semaphore.availablePermits();
    }

    public int maxPermits() {
        return maxPermits;
    }

    /** 因满载被拒的累计数(供指标 gauge) */
    public long rejectedCount() {
        return rejected.get();
    }
}
