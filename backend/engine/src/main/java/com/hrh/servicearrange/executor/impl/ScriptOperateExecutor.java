package com.hrh.servicearrange.executor.impl;

import cn.hutool.extra.spring.SpringUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hrh.servicearrange.dao.InstLogDao;
import com.hrh.servicearrange.dao.TaskDao;
import com.hrh.servicearrange.entity.Inst;
import com.hrh.servicearrange.entity.InstLog;
import com.hrh.servicearrange.entity.Task;
import com.hrh.servicearrange.executor.ErrorClass;
import com.hrh.servicearrange.executor.ErrorClassifier;
import com.hrh.servicearrange.executor.Execute;
import com.hrh.servicearrange.parser.annotation.CellType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service(CellType.FUN_SHELL)
public class ScriptOperateExecutor implements Execute {

    private static final long MAX_TIMEOUT_MS = 30_000L;

    @Override
    public Task runProcess(Task task, Inst inst, TaskDao taskDao, InstLogDao instLogDao) {
        return runScript(task, instLogDao, null);
    }

    static Task runScript(Task task, InstLogDao instLogDao, List<String> commandOverride) {
        try {
            JSONObject inputs = JSONUtil.parseObj(task.getInputs());
            Long timeoutVal = inputs.getLong("timeout");
            long timeout = timeoutVal == null ? 10000L : timeoutVal;
            timeout = Math.max(1000L, Math.min(timeout, MAX_TIMEOUT_MS));
            List<String> cmd = commandOverride;
            if (cmd == null || cmd.isEmpty()) {
                cmd = buildCommand(task.getType(), inputs);
            }
            if (cmd.isEmpty()) {
                //配置缺失属于不可恢复的错误，直接判 FATAL，避免无意义重试
                task.setErrorClass(ErrorClass.FATAL.name());
                task.setErrorCode("CMD_EMPTY");
                task.setErrorMessage("脚本命令为空");
                task.setState(Task.STATE_FAIL);
                instLogDao.save(new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(), InstLog.LEVEL_ERRO, "脚本命令为空"));
                return task;
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            //先并发消费输出再等待进程结束：输出写满管道缓冲区时子进程会阻塞在 write，
            //若先 waitFor 会一直等到超时，把已成功的脚本误判为 TIMEOUT
            String[] outHolder = new String[1];
            Thread reader = new Thread(() -> outHolder[0] = readQuietly(process, 256 * 1024), "script-stdout-reader");
            reader.setDaemon(true);
            reader.start();
            boolean finished;
            try {
                finished = process.waitFor(timeout, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw ie;
            }
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);
                reader.join(3000L);
                //脚本超时通常是下游或机器负载问题，按可重试处理，由 RetryRules 限定次数
                task.setErrorClass(ErrorClass.RETRYABLE.name());
                task.setErrorCode("TIMEOUT");
                task.setErrorMessage("脚本超时 " + timeout + "ms");
                task.setState(Task.STATE_FAIL);
                instLogDao.save(new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(), InstLog.LEVEL_ERRO, "脚本超时 " + timeout + "ms"));
                return task;
            }
            reader.join(3000L);
            String out = outHolder[0] == null ? "" : outHolder[0];
            int code = process.exitValue();
            JSONObject result = JSONUtil.createObj().set("exitCode", code).set("stdout", out);
            Task.Result outputs = task.getOutputs();
            if (outputs == null) {
                outputs = new Task.Result();
                task.setOutputs(outputs);
            }
            outputs.setContentType("application/json");
            outputs.setValue(JSONUtil.toJsonStr(result));
            if (code == 0) {
                task.setErrorClass(ErrorClass.SUCCESS.name());
                task.setErrorMessage(null);
                task.setState(Task.STATE_SUCCESS);
            } else {
                //按 task.error-mapping.script 的 exit-code 分类；未声明的退出码取该协议的 default-action（当前配为 optimistic → 可重试）
                applyExitCode(task, code, out);
                task.setState(Task.STATE_FAIL);
            }
            instLogDao.save(new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(), InstLog.LEVEL_INFO, "脚本退出码 " + code));
        } catch (Exception e) {
            ErrorClassifier classifier = classifier();
            if (classifier != null) {
                ErrorClassifier.Verdict verdict = classifier.classify(task, "script", e);
                task.setErrorClass(verdict.getErrorClass().name());
                task.setErrorCode(verdict.getCode());
                task.setErrorMessage(verdict.getMessage());
            }
            task.setState(Task.STATE_FAIL);
            instLogDao.save(new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(), InstLog.LEVEL_ERRO, "脚本执行失败：" + e.getMessage()));
        }
        return task;
    }

    /**
     * 按 task.error-mapping.script 的 exit-code 规则把退出码映射为三态分类。
     */
    private static void applyExitCode(Task task, int code, String out) {
        ErrorClassifier classifier = classifier();
        if (classifier == null) {
            return;
        }
        ErrorClassifier.Verdict verdict = classifier.classify(task, "script", code, out, null);
        task.setErrorClass(verdict.getErrorClass().name());
        task.setErrorCode(verdict.getCode());
        task.setErrorMessage(verdict.getMessage());
    }

    /**
     * 静态方法无法走依赖注入，从 Spring 容器取分类器；容器不可用时返回 null 并保持原判定行为。
     */
    private static ErrorClassifier classifier() {
        try {
            return SpringUtil.getBean(ErrorClassifier.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> buildCommand(String type, JSONObject inputs) {
        List<String> cmd = new ArrayList<>();
        String script = inputs.getStr("script");
        if (CellType.FUN_PYTHON.equals(type)) {
            String interpreter = inputs.getStr("interpreter", "python");
            cmd.add(interpreter);
            if (!StringUtils.isEmpty(script)) {
                cmd.add("-c");
                cmd.add(script);
            }
            return cmd;
        }
        if (CellType.FUN_JAR.equals(type)) {
            cmd.add("java");
            cmd.add("-jar");
            String jar = inputs.getStr("jarPath", inputs.getStr("url"));
            if (!StringUtils.isEmpty(jar)) {
                cmd.add(jar);
            }
            return cmd;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (windows) {
            cmd.add("cmd");
            cmd.add("/c");
            cmd.add(StringUtils.isEmpty(script) ? "echo empty" : script);
        } else {
            cmd.add("/bin/sh");
            cmd.add("-c");
            cmd.add(StringUtils.isEmpty(script) ? "echo empty" : script);
        }
        return cmd;
    }

    /**
     * 读线程专用：进程被强杀时流可能抛异常，读取失败按空输出处理，不影响主流程的退出码判定。
     */
    private static String readQuietly(Process process, int max) {
        try {
            return readLimited(process.getInputStream(), max);
        } catch (Exception e) {
            return "";
        }
    }

    private static String readLimited(InputStream in, int max) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        int total = 0;
        while ((n = in.read(buf)) >= 0) {
            int allow = Math.min(n, max - total);
            if (allow > 0) {
                bos.write(buf, 0, allow);
                total += allow;
            }
            if (total >= max) {
                break;
            }
        }
        return bos.toString(StandardCharsets.UTF_8.name());
    }
}

@Service(CellType.FUN_PYTHON)
class PythonOperateExecutor implements Execute {
    @Override
    public Task runProcess(Task task, Inst inst, TaskDao taskDao, InstLogDao instLogDao) {
        return ScriptOperateExecutor.runScript(task, instLogDao, null);
    }
}

@Service(CellType.FUN_JAR)
class JarOperateExecutor implements Execute {
    @Override
    public Task runProcess(Task task, Inst inst, TaskDao taskDao, InstLogDao instLogDao) {
        return ScriptOperateExecutor.runScript(task, instLogDao, null);
    }
}
