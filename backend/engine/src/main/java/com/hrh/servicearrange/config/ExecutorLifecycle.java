package com.hrh.servicearrange.config;

import com.hrh.servicearrange.executor.Execute;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.concurrent.TimeUnit;

/**
 * 节点执行线程池的生命周期收口：停机时停止接收新任务并等待在途任务收尾，
 * 避免「接口常量线程池无人关闭」导致进程退出时任务半途而废。
 */
@Slf4j
@Component
public class ExecutorLifecycle {

    private static final long AWAIT_SECONDS = 10L;

    @PreDestroy
    public void shutdown() {
        try {
            Execute.TASK_POOL.shutdown();
            if (!Execute.TASK_POOL.awaitTermination(AWAIT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("节点执行线程池在 {}s 内未结束，强制关闭", AWAIT_SECONDS);
                Execute.TASK_POOL.shutdownNow();
            }
        } catch (InterruptedException e) {
            Execute.TASK_POOL.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
