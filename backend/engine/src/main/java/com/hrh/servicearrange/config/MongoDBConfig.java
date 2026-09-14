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
        //设置每个连接地址的最大连接数
//        builder.connectionsPerHost(10);
        //设置连接的超时时间
//        builder.connectTimeout(5000);
        //设置读写的超时时间
//        builder.socketTimeout(5000);
        
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

