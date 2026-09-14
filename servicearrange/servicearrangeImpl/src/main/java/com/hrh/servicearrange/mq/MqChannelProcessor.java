package com.hrh.servicearrange.mq;

import org.springframework.cloud.stream.annotation.Input;
import org.springframework.cloud.stream.annotation.Output;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.SubscribableChannel;

/**
 * @author huangrenhui
 * @date 2022/3/9
 * @flow
 */
public interface MqChannelProcessor {
    String OUTPUT = "output_channel";

    String TASK_RUN_INPUT = "task_run_input_channel";
    String TASK_RESULT_INPUT = "task_result_input_channel";

    @Input(TASK_RESULT_INPUT)
    SubscribableChannel taskResultConsumer();

    @Input(TASK_RUN_INPUT)
    SubscribableChannel taskRunConsumer();

    @Output(OUTPUT)
    MessageChannel taskProductor();
}