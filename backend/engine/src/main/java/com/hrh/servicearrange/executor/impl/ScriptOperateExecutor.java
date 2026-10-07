package com.hrh.servicearrange.executor.impl;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hrh.servicearrange.dao.InstLogDao;
import com.hrh.servicearrange.dao.TaskDao;
import com.hrh.servicearrange.entity.Inst;
import com.hrh.servicearrange.entity.InstLog;
import com.hrh.servicearrange.entity.Task;
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
                task.setState(Task.STATE_FAIL);
                instLogDao.save(new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(), InstLog.LEVEL_ERRO, "脚本命令为空"));
                return task;
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(timeout, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                task.setState(Task.STATE_FAIL);
                instLogDao.save(new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(), InstLog.LEVEL_ERRO, "脚本超时 " + timeout + "ms"));
                return task;
            }
            String out = readLimited(process.getInputStream(), 256 * 1024);
            int code = process.exitValue();
            JSONObject result = JSONUtil.createObj().set("exitCode", code).set("stdout", out);
            task.getOutputs().setContentType("application/json");
            task.getOutputs().setValue(JSONUtil.toJsonStr(result));
            task.setState(code == 0 ? Task.STATE_SUCCESS : Task.STATE_FAIL);
            instLogDao.save(new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(), InstLog.LEVEL_INFO, "脚本退出码 " + code));
        } catch (Exception e) {
            task.setState(Task.STATE_FAIL);
            instLogDao.save(new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(), InstLog.LEVEL_ERRO, "脚本执行失败：" + e.getMessage()));
        }
        return task;
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
