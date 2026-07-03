package com.ekusys.exam.runtime.config;

import io.minio.MinioClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RuntimeMinioProperties.class)
public class RuntimeMinioConfig {
    @Bean
    MinioClient runtimeMinioClient(RuntimeMinioProperties properties) {
        return MinioClient.builder().endpoint(properties.getEndpoint())
            .credentials(properties.getAccessKey(), properties.getSecretKey()).build();
    }
}
