package com.ekusys.exam.content;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.mybatis.spring.annotation.MapperScan;

@EnableFeignClients
@MapperScan("com.ekusys.exam.repository.mapper")
@SpringBootApplication(scanBasePackages = "com.ekusys.exam")
public class ContentApplication {
    public static void main(String[] args) { SpringApplication.run(ContentApplication.class, args); }
}
