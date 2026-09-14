package com.hrh.servicearrange.mq;

/**
 * @author huangrenhui
 * @date 2022/3/9
 * @flow
 */
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cn.hutool.core.thread.ThreadUtil;
import org.apache.curator.framework.CuratorFramework;
import org.apache.zookeeper.CreateMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

@Component
public class TestMQAndZkCommandLineRunner implements CommandLineRunner{

    @Autowired
    private MqChannelProcessor processor;
    @Value("${task.run_queue_name}")
    private String taskRun_queue_name;
    @Value("${task.result_queue_name}")
    private String taskResult_queue_name;

    @Autowired
    CuratorFramework curatorFramework;

    @Override
    public void run(String... args) throws Exception {
//        JSONObject obj = JSONUtil.createObj();
//        obj.set("id", 101);
//        obj.set("name", "张三");
//        obj.set("sex", "man");
//
//        Map<String, Object> properties = new HashMap<>();
//        properties.put("receiveSign", taskRun_queue_name);
//        MessageHeaders mhs = new MessageHeaders(properties);
//        processor.taskProductor().send(MessageBuilder.createMessage(obj, mhs));
//
//        JSONObject obj2 = JSONUtil.createObj();
//        obj2.set("id", 102);
//        obj2.set("name", "莉莉丝");
//        obj2.set("sex", "woman");
//        Map<String, Object> properties2 = new HashMap<>();
//        properties2.put("receiveSign", taskResult_queue_name);
//        MessageHeaders mhs2 = new MessageHeaders(properties2);
//        processor.taskProductor().send(MessageBuilder.createMessage(obj2, mhs2));
//
//        try {
//            byte[] bytes = new byte[1];
//            bytes[0] = 1;
//            String lockNode = curatorFramework.create()
//                    .withMode(CreateMode.EPHEMERAL)
//                    .forPath("/hrh-test", bytes);
//            System.out.println("lockNode:"+lockNode);
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//        ThreadUtil.safeSleep(1000000);
//        List<String> list = curatorFramework.getChildren().forPath("/hrh-test");
//        System.out.println("=====================");
//        list.forEach(System.out::println);
//        curatorFramework.delete().guaranteed().deletingChildrenIfNeeded().forPath("/hrh-test");
    }

}

