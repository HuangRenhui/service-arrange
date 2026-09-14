package com.hrh.servicearrange;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.cloud.netflix.eureka.EnableEurekaClient;
import org.springframework.web.WebApplicationInitializer;

/**
 * @author huangrenhui
 * @date 2022/2/21
 * @flow
 */
@EnableEurekaClient
@SpringBootApplication
public class ServiceArrangeApplication extends SpringBootServletInitializer implements WebApplicationInitializer {
    public static void main(String[] args) {
        SpringApplication.run(ServiceArrangeApplication.class, args);
    }
}
