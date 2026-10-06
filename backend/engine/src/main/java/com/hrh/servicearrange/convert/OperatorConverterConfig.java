package com.hrh.servicearrange.convert;

import com.hrh.servicearrange.parser.annotation.CellType;
import com.hrh.servicearrange.parser.annotation.TaskType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OperatorConverterConfig {

    @Bean(name = CellType.FUN_DATAMAP + TaskType.suffix)
    public TaskInterface datamapTask() {
        return new JsonPayloadTask();
    }

    @Bean(name = CellType.FUN_TRANSFORM + TaskType.suffix)
    public TaskInterface transformTask() {
        return new JsonPayloadTask();
    }

    @Bean(name = CellType.FUN_DATARESULT + TaskType.suffix)
    public TaskInterface dataResultTask() {
        return new JsonPayloadTask();
    }

    @Bean(name = CellType.FUN_DECISION + TaskType.suffix)
    public TaskInterface decisionTask() {
        return new JsonPayloadTask();
    }

    @Bean(name = CellType.FUN_SLEEP + TaskType.suffix)
    public TaskInterface sleepTask() {
        return new JsonPayloadTask();
    }

    @Bean(name = CellType.FUN_JSON2XML + TaskType.suffix)
    public TaskInterface json2xmlTask() {
        return new JsonPayloadTask();
    }

    @Bean(name = CellType.FUN_XML2JSON + TaskType.suffix)
    public TaskInterface xml2jsonTask() {
        return new JsonPayloadTask();
    }

    @Bean(name = CellType.FUN_SHELL + TaskType.suffix)
    public TaskInterface shellTask() {
        return new JsonPayloadTask();
    }

    @Bean(name = CellType.FUN_JAR + TaskType.suffix)
    public TaskInterface jarTask() {
        return new JsonPayloadTask();
    }

    @Bean(name = CellType.FUN_PYTHON + TaskType.suffix)
    public TaskInterface pythonTask() {
        return new JsonPayloadTask();
    }

    @Bean(name = CellType.FUN_COMPENSATE_DATAMAP + TaskType.suffix)
    public TaskInterface compensateDatamapTask() {
        return new JsonPayloadTask();
    }

    @Bean(name = CellType.OPERATOR_HTTP + TaskType.suffix)
    public TaskInterface httpTask() {
        return new JsonPayloadTask();
    }

    @Bean(name = CellType.OPERATOR_HTTP_COMPENSATE + TaskType.suffix)
    public TaskInterface httpCompensateTask() {
        return new JsonPayloadTask();
    }

    @Bean(name = CellType.OPERATOR_DUBBO + TaskType.suffix)
    public TaskInterface dubboTask() {
        return new JsonPayloadTask();
    }

    @Bean(name = CellType.OPERATOR_DUBBO_COMPENSATE + TaskType.suffix)
    public TaskInterface dubboCompensateTask() {
        return new JsonPayloadTask();
    }

    @Bean(name = CellType.OPERATOR_WEBSERVICE + TaskType.suffix)
    public TaskInterface webserviceTask() {
        return new JsonPayloadTask();
    }

    @Bean(name = CellType.OPERATOR_WEBSERVICE_COMPENSATE + TaskType.suffix)
    public TaskInterface webserviceCompensateTask() {
        return new JsonPayloadTask();
    }

    @Bean(name = CellType.OPERATOR_SQL + TaskType.suffix)
    public TaskInterface sqlTask() {
        return new JsonPayloadTask();
    }

    @Bean(name = CellType.NODE_OP + TaskType.suffix)
    public TaskInterface nodeOpTask() {
        return new JsonPayloadTask();
    }
}
