package com.hrh.servicearrange.mq;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 进程内实例锁。
 * <p>
 * 作用：在 MQ 消费线程与本地直连分片线程之间保证「同一实例同一时刻只有一个推进者」，
 * 覆盖 MQ ↔ 本地切换的瞬间（此时既可能有 MQ 消息在途，也可能有本地任务在队列里）。
 * <p>
 * 跨节点串行仍由 MQ 模式下的 ZooKeeper 锁负责；本锁只解决单进程内并发。
 */
@Component
public class InstanceLock {

    /**
     * 实例锁 map。实例数量有限，锁对象很小；如需长期运行可增加过期清理。
     */
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ReentrantLock lockOf(String instId) {
        return locks.computeIfAbsent(String.valueOf(instId), key -> new ReentrantLock());
    }
}
