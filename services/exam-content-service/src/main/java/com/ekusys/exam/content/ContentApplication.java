package com.ekusys.exam.content;

import com.ekusys.exam.content.config.ExamCacheProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.mybatis.spring.annotation.MapperScan;

@EnableFeignClients
@EnableScheduling
@EnableConfigurationProperties(ExamCacheProperties.class)
@MapperScan("com.ekusys.exam.repository.mapper")
@SpringBootApplication(scanBasePackages = "com.ekusys.exam")
public class ContentApplication {
    public static void main(String[] args) { SpringApplication.run(ContentApplication.class, args); }
}
