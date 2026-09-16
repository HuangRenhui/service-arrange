package com.hrh.servicearrange;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.web.WebApplicationInitializer;

/**
 * @author huangrenhui
 * @date 2022/2/21
 * @flow
 */
@SpringBootApplication
public class ServiceArrangeApplication extends SpringBootServletInitializer implements WebApplicationInitializer {
    public static void main(String[] args) {
        SpringApplication.run(ServiceArrangeApplication.class, args);
    }
}
