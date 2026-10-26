package com.hrh.servicearrange.executor.impl;

import cn.hutool.json.JSONArray;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.Properties;

@Service(CellType.OPERATOR_SQL)
public class SqlOperateExecutor implements Execute {

    /**
     * 错误码分类器：把 SQLState 映射为 SUCCESS / RETRYABLE / FATAL，
     * 用于区分死锁（可重试）与语法错误（重试无意义）
     */
    @Autowired
    private ErrorClassifier errorClassifier;

    @Override
    public Task runProcess(Task task, Inst inst, TaskDao taskDao, InstLogDao instLogDao) {
        Connection conn = null;
        Statement stmt = null;
        try {
            JSONObject inputs = JSONUtil.parseObj(task.getInputs());
            String jdbcUrl = inputs.getStr("jdbcUrl", inputs.getStr("url"));
            String sql = inputs.getStr("sql", inputs.getStr("script"));
            if (StringUtils.isEmpty(jdbcUrl) || StringUtils.isEmpty(sql)) {
                //配置缺失属于不可恢复的错误，直接判 FATAL，避免无意义重试
                task.setErrorClass(ErrorClass.FATAL.name());
                task.setErrorCode("CONFIG_MISSING");
                task.setErrorMessage("SQL 节点需要 jdbcUrl 与 sql");
                task.setState(Task.STATE_FAIL);
                instLogDao.save(new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(), InstLog.LEVEL_ERRO, "SQL 节点需要 jdbcUrl 与 sql"));
                return task;
            }
            tryLoadDriver(jdbcUrl);
            Properties props = new Properties();
            if (!StringUtils.isEmpty(inputs.getStr("username"))) {
                props.setProperty("user", inputs.getStr("username"));
            }
            if (!StringUtils.isEmpty(inputs.getStr("password"))) {
                props.setProperty("password", inputs.getStr("password"));
            }
            DriverManager.setLoginTimeout(5);
            conn = DriverManager.getConnection(jdbcUrl, props);
            stmt = conn.createStatement();
            stmt.setQueryTimeout(15);
            boolean hasResult = stmt.execute(sql);
            JSONObject out = JSONUtil.createObj();
            if (hasResult) {
                ResultSet rs = stmt.getResultSet();
                JSONArray rows = JSONUtil.createArray();
                ResultSetMetaData meta = rs.getMetaData();
                int cols = meta.getColumnCount();
                int count = 0;
                while (rs.next() && count < 500) {
                    JSONObject row = JSONUtil.createObj();
                    for (int i = 1; i <= cols; i++) {
                        row.set(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                    count++;
                }
                out.set("rows", rows);
            } else {
                out.set("updateCount", stmt.getUpdateCount());
            }
            task.getOutputs().setContentType("application/json");
            task.getOutputs().setValue(JSONUtil.toJsonStr(out));
            task.setErrorClass(ErrorClass.SUCCESS.name());
            task.setErrorMessage(null);
            task.setState(Task.STATE_SUCCESS);
        } catch (Exception e) {
            //按 task.error-mapping.sql 的 sqlstate 分类：40001/40P01 等可重试，42xxx/07xxx 等不可重试
            ErrorClassifier.Verdict verdict = errorClassifier.classify(task, "sql", e);
            task.setErrorClass(verdict.getErrorClass().name());
            task.setErrorCode(verdict.getCode());
            task.setErrorMessage(verdict.getMessage());
            task.setState(Task.STATE_FAIL);
            instLogDao.save(new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(), InstLog.LEVEL_ERRO,
                    "SQL 执行失败（" + verdict.getErrorClass() + "）：" + e.getMessage()));
        } finally {
            try { if (stmt != null) stmt.close(); } catch (Exception ignored) {}
            try { if (conn != null) conn.close(); } catch (Exception ignored) {}
        }
        return task;
    }

    private void tryLoadDriver(String jdbcUrl) {
        try {
            if (jdbcUrl.startsWith("jdbc:mysql")) {
                Class.forName("com.mysql.cj.jdbc.Driver");
            } else if (jdbcUrl.startsWith("jdbc:postgresql")) {
                Class.forName("org.postgresql.Driver");
            } else if (jdbcUrl.startsWith("jdbc:oracle")) {
                Class.forName("oracle.jdbc.OracleDriver");
            }
        } catch (ClassNotFoundException ignored) {
        }
    }
}
