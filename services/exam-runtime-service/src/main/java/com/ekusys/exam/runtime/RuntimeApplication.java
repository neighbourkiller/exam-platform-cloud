package com.ekusys.exam.runtime;

import com.ekusys.exam.runtime.config.SnapshotProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableFeignClients
@EnableScheduling
@EnableConfigurationProperties(SnapshotProperties.class)
@MapperScan("com.ekusys.exam.runtime.repository")
@SpringBootApplication(scanBasePackages = "com.ekusys.exam")
public class RuntimeApplication {
    public static void main(String[] args) { SpringApplication.run(RuntimeApplication.class, args); }
}
