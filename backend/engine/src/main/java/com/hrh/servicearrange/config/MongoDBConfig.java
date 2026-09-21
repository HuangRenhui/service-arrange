package com.hrh.servicearrange.config;

import com.mongodb.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.convert.CustomConversions;
import org.springframework.data.mongodb.MongoDbFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.config.AbstractMongoConfiguration;
import org.springframework.data.mongodb.core.WriteConcernResolver;
import org.springframework.data.mongodb.core.convert.DbRefResolver;
import org.springframework.data.mongodb.core.convert.DefaultDbRefResolver;
import org.springframework.data.mongodb.core.convert.DefaultMongoTypeMapper;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

@Getter
@Setter
@Configuration
public class MongoDBConfig extends AbstractMongoConfiguration {

    @Value("${spring.data.mongodb.database}")
    private String database;

    @Value("${spring.data.mongodb.host}")
    private String host;

    @Value("${spring.data.mongodb.port}")
    private Integer port;

    @Value("${spring.data.mongodb.username}")
    private String username;

    @Value("${spring.data.mongodb.password}")
    private String password;

    @Value("${spring.data.mongodb.connections-per-host:50}")
    private Integer mongoConnections;

    @Value("${spring.data.mongodb.connect-timeout:5000}")
    private Integer mongoConnectTimeout;

    @Value("${spring.data.mongodb.socket-timeout:30000}")
    private Integer mongoSocketTimeout;

    @Value("${spring.data.mongodb.max-wait-time:5000}")
    private Integer mongoMaxWaitTime;

    @Value("${spring.data.mongodb.max-idle-time:60000}")
    private Integer mongoMaxIdleTime;

    @Bean
    MongoTransactionManager transactionManager(MongoDbFactory dbFactory) {
        return new MongoTransactionManager(dbFactory);
    }

    @Bean 
    public WriteConcernResolver writeConcernResolver() { 
        return action -> { 
         return WriteConcern.MAJORITY; 
        }; 
    } 
    

    
    @Override
    public MongoClient mongoClient() {
        MongoClientOptions.Builder builder = new MongoClientOptions.Builder();
        //设置每个连接地址的最大连接数：限制单机连接上限，避免高负载下连接数无限增长
        builder.connectionsPerHost(mongoConnections);
        //设置连接的超时时间：单位毫秒，避免 broker/DB 不可达时线程长期阻塞
        builder.connectTimeout(mongoConnectTimeout);
        //设置读写的超时时间：单位毫秒，避免慢查询长期占用消费线程
        builder.socketTimeout(mongoSocketTimeout);
        //设置等待可用连接的最大时间：单位毫秒，连接池被占满时快速失败而不是无限等待
        builder.maxWaitTime(mongoMaxWaitTime);
        //设置空闲连接超时时间：单位毫秒，回收长时间不用的连接
        builder.maxConnectionIdleTime(mongoMaxIdleTime);

        builder.writeConcern(WriteConcern.MAJORITY);
        //replication.enableMajorityReadConcern 
//        builder.readConcern(ReadConcern.MAJORITY);
        //创建一个用户认证信息
        MongoCredential credential = MongoCredential.createCredential(username,database,password.toCharArray());
        //封装MongoDB的地址和端口
        ServerAddress address = new ServerAddress(host, port);
        return new MongoClient(address,credential,builder.build());
    }

    @Override
    protected String getDatabaseName() {
        return database;
    }
    
  //删除_class 属性的配置 
    @Bean
    public MappingMongoConverter mappingMongoConverter(MongoDbFactory factory, MongoMappingContext context, BeanFactory beanFactory) {
        DbRefResolver dbRefResolver = new DefaultDbRefResolver(factory);
        MappingMongoConverter mappingConverter = new MappingMongoConverter(dbRefResolver, context);
        try {//
            mappingConverter.setCustomConversions(beanFactory.getBean(CustomConversions.class));
        } catch (NoSuchBeanDefinitionException ignore) {
        }
 
        // Don't save _class to mongo
        mappingConverter.setTypeMapper(new DefaultMongoTypeMapper(null));
 
        return mappingConverter;
    }
}

