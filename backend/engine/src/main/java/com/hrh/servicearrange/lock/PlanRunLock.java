package com.hrh.servicearrange.lock;

import cn.hutool.core.util.StrUtil;
import com.hrh.servicearrange.dao.InstDao;
import com.hrh.servicearrange.entity.Inst;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 流程运行准入锁：同一 {@code planId} 同一时刻只允许一个实例处于运行态。
 * <p>
 * <b>为什么用 ZK 而不是查库</b>：准入是「先到先得」的原子语义。查库存在两个请求同时查不到运行中实例、
 * 随后各建一个实例的竞态；ZK 的 {@code create(EPHEMERAL)} 由服务端保证唯一性，天然互斥。
 * <p>
 * <b>锁节点</b>：{@code /PLAN_RUNNING_{planId}}，数据为持有者 {@code instId}，EPHEMERAL 随会话自动释放。
 * <p>
 * <b>自愈</b>：若节点存在但属主实例已终态 / 已删除（如进程崩溃导致状态残留），视为陈旧锁，清理后重新抢占，
 * 避免「实例失败后流程被永久锁死」。
 * <p>
 * <b>降级</b>：ZK 不可用时返回 {@link Result#isZkUnavailable()}，由调用方回退按库内状态校验，保证主链路不被锁组件拖垮。
 */
@Slf4j
@Component
public class PlanRunLock {

    /**
     * ZK 未连接时的最长等待（毫秒），超出即降级为库内校验
     */
    private static final int CONNECT_WAIT_MS = 500;

    @Autowired
    private CuratorFramework curatorFramework;

    @Autowired
    private InstDao instDao;

    /**
     * 尝试抢占流程准入锁。
     *
     * @param planId 流程 id；为空（本地 classpath DSL 调试）时不拦截
     * @param instId 本次要运行的实例 id，写入锁节点数据用于归属校验与陈旧判定
     */
    public Result tryLock(String planId, String instId) {
        if (StrUtil.isBlank(planId) || StrUtil.isBlank(instId)) {
            return Result.acquired();
        }
        if (!ensureConnected()) {
            log.warn("ZooKeeper 未连接，流程准入降级为库内状态校验, planId={}", planId);
            return Result.zkUnavailable();
        }
        String path = path(planId);
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                curatorFramework.create().withMode(CreateMode.EPHEMERAL)
                        .forPath(path, instId.getBytes(StandardCharsets.UTF_8));
                log.info("流程准入锁获取成功, planId={}, instId={}", planId, instId);
                return Result.acquired();
            } catch (KeeperException.NodeExistsException e) {
                String owner = readOwner(path);
                if (attempt == 0 && isStale(owner)) {
                    log.warn("流程准入锁属主已结束，清理陈旧锁后重试, planId={}, staleOwner={}", planId, owner);
                    deleteQuietly(path);
                    continue;
                }
                log.info("流程准入锁被占用, planId={}, ownerInstId={}", planId, owner);
                return Result.held(owner);
            } catch (Exception e) {
                //ZK 不可用（连接失败 / 会话过期 / 超时）：交给调用方按库内状态兜底，避免锁组件拖垮主链路
                log.warn("流程准入锁不可用，降级为库内状态校验, planId={}, msg={}", planId, e.getMessage());
                return Result.zkUnavailable();
            }
        }
        return Result.zkUnavailable();
    }

    /**
     * 释放准入锁：仅当锁节点属主就是当前实例时才删除，避免误删其它实例持有的锁。
     */
    public void release(String planId, String instId) {
        if (StrUtil.isBlank(planId) || StrUtil.isBlank(instId)) {
            return;
        }
        //ZK 断开时不等待（避免取消/删除接口被连接超时拖住），属主终态后由下次抢占自愈清理
        if (!ensureConnected()) {
            log.debug("ZooKeeper 未连接，跳过准入锁释放, planId={}, instId={}", planId, instId);
            return;
        }
        String path = path(planId);
        try {
            String owner = readOwner(path);
            if (owner == null) {
                return;
            }
            if (!owner.equals(instId)) {
                log.debug("准入锁属主非当前实例，跳过释放, planId={}, owner={}, instId={}", planId, owner, instId);
                return;
            }
            curatorFramework.delete().forPath(path);
            log.info("流程准入锁已释放, planId={}, instId={}", planId, instId);
        } catch (KeeperException.NoNodeException e) {
            //已被自愈逻辑或会话释放，忽略
        } catch (Exception e) {
            log.warn("流程准入锁释放失败（将在属主终态后自愈）, planId={}, instId={}, msg={}", planId, instId, e.getMessage());
        }
    }

    /**
     * ZK 未连接时最多等待 {@link #CONNECT_WAIT_MS} 毫秒，避免 create/delete 卡在默认 15s 连接超时上
     */
    private boolean ensureConnected() {
        try {
            if (curatorFramework.getZookeeperClient().isConnected()) {
                return true;
            }
            return curatorFramework.blockUntilConnected(CONNECT_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.debug("ZooKeeper 连接检查失败, msg={}", e.getMessage());
            return false;
        }
    }

    private String path(String planId) {
        return Inst.ZK_PLAN_LOCK_PREFIX + planId;
    }

    private String readOwner(String path) {
        try {
            byte[] data = curatorFramework.getData().forPath(path);
            return data == null ? "" : new String(data, StandardCharsets.UTF_8);
        } catch (KeeperException.NoNodeException e) {
            return null;
        } catch (Exception e) {
            log.debug("读取准入锁属主失败, path={}, msg={}", path, e.getMessage());
            return "";
        }
    }

    /**
     * 陈旧锁判定：属主为空、实例已不存在、或实例已是终态，都可以被新请求清理
     */
    private boolean isStale(String owner) {
        if (StrUtil.isBlank(owner)) {
            return true;
        }
        try {
            Inst inst = instDao.findById(owner).orElse(null);
            if (inst == null) {
                return true;
            }
            return !Inst.STATE_WAITE.equals(inst.getState()) && !Inst.STATE_RUNNING.equals(inst.getState());
        } catch (Exception e) {
            //查不到状态时保守处理：认为锁仍未释放
            log.debug("查询准入锁属主状态失败, owner={}, msg={}", owner, e.getMessage());
            return false;
        }
    }

    private void deleteQuietly(String path) {
        try {
            curatorFramework.delete().forPath(path);
        } catch (Exception e) {
            log.debug("清理陈旧准入锁失败, path={}, msg={}", path, e.getMessage());
        }
    }

    /**
     * 抢锁结果
     */
    @Getter
    public static class Result {

        /**
         * 是否拿到锁
         */
        private final boolean acquired;

        /**
         * ZK 是否不可用（调用方需回退库内校验）
         */
        private final boolean zkUnavailable;

        /**
         * 锁的持有者实例 id（被占用时有值）
         */
        private final String ownerInstId;

        private Result(boolean acquired, boolean zkUnavailable, String ownerInstId) {
            this.acquired = acquired;
            this.zkUnavailable = zkUnavailable;
            this.ownerInstId = ownerInstId;
        }

        static Result acquired() {
            return new Result(true, false, null);
        }

        static Result held(String ownerInstId) {
            return new Result(false, false, ownerInstId);
        }

        static Result zkUnavailable() {
            return new Result(false, true, null);
        }
    }
}
