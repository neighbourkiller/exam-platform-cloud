package com.ekusys.exam.management;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableFeignClients
@EnableScheduling
@MapperScan("com.ekusys.exam.repository.mapper")
@SpringBootApplication(scanBasePackages = "com.ekusys.exam")
public class ManagementApplication {
    public static void main(String[] args) { SpringApplication.run(ManagementApplication.class, args); }
}
