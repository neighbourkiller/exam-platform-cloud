package com.ekusys.exam.academic;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.mybatis.spring.annotation.MapperScan;

@EnableFeignClients
@MapperScan("com.ekusys.exam.repository.mapper")
@SpringBootApplication(scanBasePackages = "com.ekusys.exam")
public class AcademicApplication {
    public static void main(String[] args) { SpringApplication.run(AcademicApplication.class, args); }
}
