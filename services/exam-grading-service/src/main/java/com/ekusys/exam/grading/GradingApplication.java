package com.ekusys.exam.grading;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableFeignClients
@EnableScheduling
@SpringBootApplication(scanBasePackages = "com.ekusys.exam")
public class GradingApplication {
    public static void main(String[] args) { SpringApplication.run(GradingApplication.class, args); }
}
