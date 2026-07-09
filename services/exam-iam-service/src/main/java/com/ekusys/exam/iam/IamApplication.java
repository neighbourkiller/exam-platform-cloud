package com.ekusys.exam.iam;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableFeignClients
@EnableScheduling
@MapperScan("com.ekusys.exam.repository.mapper")
@SpringBootApplication(scanBasePackages = "com.ekusys.exam")
public class IamApplication {
    public static void main(String[] args) { SpringApplication.run(IamApplication.class, args); }
}
