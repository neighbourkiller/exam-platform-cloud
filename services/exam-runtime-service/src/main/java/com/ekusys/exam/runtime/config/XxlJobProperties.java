package com.ekusys.exam.runtime.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "xxl.job")
public record XxlJobProperties(String adminAddresses, String accessToken, Executor executor) {
    public record Executor(String appname, String address, String ip, int port,
                           String logPath, int logRetentionDays) {
    }
}
