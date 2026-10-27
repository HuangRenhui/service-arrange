package com.hrh.servicearrange.mq;

import com.hrh.servicearrange.config.TaskDispatchProperties;
import com.hrh.servicearrange.entity.Task;
import com.hrh.servicearrange.vo.Task4MQ;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 任务投递统一入口：按 {@code task.dispatch.mode} 在 MQ 与本地直连之间切换。
 * <p>
 * <b>模式语义</b>
 * <ul>
 *     <li>{@code mq}：只投 MQ；投递失败且 {@code degrade-to-local=true} 时降级本地；</li>
 *     <li>{@code local}：只走进程内直连，不创建任何 MQ 连接；</li>
 *     <li>{@code auto}：MQ 可达则投 MQ；不可达或投递失败则降级本地，MQ 恢复后自动切回。</li>
 * </ul>
 * <p>
 * <b>本地直连的串行保证</b>：按 {@code instId} 哈希到固定的单线程分片，同一实例的任务串行处理；
 * 分片线程执行意味着「节点执行 → 结果推进 → 下游任务」不再是同步递归，长流程不会压爆调用栈。
 */
@Slf4j
@Component
public class TaskDispatcher {

    @Autowired
    private TaskDispatchProperties dispatchProperties;

    @Autowired
    private MqAvailability mqAvailability;

    @Autowired
    private MqTaskSender mqTaskSender;

    @Autowired
    private InstanceLock instanceLock;

    /**
     * 用 ObjectProvider 延迟取 Consumer，避免 TaskProductor → Dispatcher → Consumer → TaskProductor 的构造期循环依赖
     */
    @Autowired
    private ObjectProvider<TaskRunConsumer> taskRunConsumer;

    @Autowired
    private ObjectProvider<TaskResultConsumer> taskResultConsumer;

    private ExecutorService[] lanes;

    @PostConstruct
    public void init() {
        int configured = dispatchProperties.getLocalThreads();
        int size = configured > 0 ? configured : Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
        lanes = new ExecutorService[size];
        AtomicInteger seq = new AtomicInteger();
        for (int i = 0; i < size; i++) {
            final int index = i;
            ThreadFactory factory = r -> {
                Thread t = new Thread(r, "servea-local-dispatch-" + index + "-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            };
            lanes[i] = Executors.newSingleThreadExecutor(factory);
        }
        log.info("任务投递初始化：mode={}, degradeToLocal={}, 本地分片线程数={}",
                dispatchProperties.normalizedMode(), dispatchProperties.isDegradeToLocal(), size);
    }

    @PreDestroy
    public void destroy() {
        if (lanes == null) {
            return;
        }
        for (ExecutorService lane : lanes) {
            lane.shutdownNow();
        }
    }

    /**
     * 投递「待执行任务」
     */
    public void dispatchRun(Task task) {
        dispatch(task, true);
    }

    /**
     * 投递「任务执行结果」，用于推进下游节点
     */
    public void dispatchResult(Task task) {
        dispatch(task, false);
    }

    /**
     * 当前实际生效的投递方式，便于日志与健康检查排查
     */
    public String currentChannel() {
        String mode = dispatchProperties.normalizedMode();
        if (TaskDispatchProperties.MODE_LOCAL.equals(mode)) {
            return TaskDispatchProperties.MODE_LOCAL;
        }
        return mqAvailability.isAvailable() ? TaskDispatchProperties.MODE_MQ : TaskDispatchProperties.MODE_LOCAL;
    }

    private void dispatch(Task task, boolean run) {
        if (task == null || task.getId() == null) {
            return;
        }
        String mode = dispatchProperties.normalizedMode();
        if (TaskDispatchProperties.MODE_LOCAL.equals(mode)) {
            submitLocal(task, run);
            return;
        }
        boolean degrade = dispatchProperties.isDegradeToLocal();
        if (TaskDispatchProperties.MODE_AUTO.equals(mode) && !mqAvailability.isAvailable()) {
            if (degrade) {
                log.debug("MQ 不可用，任务走本地直连, taskId={}, instId={}", task.getId(), task.getInstId());
                submitLocal(task, run);
                return;
            }
            throw new IllegalStateException("MQ 不可用且未开启本地降级（task.dispatch.degrade-to-local=false），taskId=" + task.getId());
        }
        try {
            if (run) {
                mqTaskSender.sendRun(new Task4MQ(task.getId(), task.getType()));
            } else {
                mqTaskSender.sendResult(task);
            }
        } catch (RuntimeException e) {
            //投递失败：标记不可用并按需降级，保证任务不丢
            mqAvailability.markUnavailable("投递失败：" + e.getMessage());
            if (!degrade) {
                throw e;
            }
            log.warn("MQ 投递失败，降级本地直连, taskId={}, instId={}, reason={}",
                    task.getId(), task.getInstId(), e.getMessage());
            submitLocal(task, run);
        }
    }

    private void submitLocal(Task task, boolean run) {
        if (lanes == null || lanes.length == 0) {
            executeLocal(task, run);
            return;
        }
        ExecutorService lane = lanes[laneIndex(task.getInstId())];
        try {
            lane.execute(() -> executeLocal(task, run));
        } catch (RejectedExecutionException e) {
            //线程池已关闭（应用停机）：退化为调用方线程兜底，避免任务丢失
            log.warn("本地直连线程池已拒绝，改用调用方线程执行, taskId={}", task.getId());
            executeLocal(task, run);
        }
    }

    private void executeLocal(Task task, boolean run) {
        //与 MQ 消费线程共用进程内实例锁，覆盖 MQ ↔ 本地切换瞬间的同实例并发
        ReentrantLock lock = instanceLock.lockOf(task.getInstId());
        lock.lock();
        try {
            if (run) {
                taskRunConsumer.getObject().runLocal(new Task4MQ(task.getId(), task.getType()));
            } else {
                taskResultConsumer.getObject().runLocal(task.getId(), task.getInstId(), task.getState(),
                        task.getOutputs(), task.getRetryRules());
            }
        } catch (Exception e) {
            log.error("本地直连执行失败, taskId={}, instId={}", task.getId(), task.getInstId(), e);
        } finally {
            lock.unlock();
        }
    }

    private int laneIndex(String instId) {
        int hash = String.valueOf(instId).hashCode();
        return Math.floorMod(hash, lanes.length);
    }
}
