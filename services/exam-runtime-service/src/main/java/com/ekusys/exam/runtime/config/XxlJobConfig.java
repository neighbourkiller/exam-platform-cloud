package com.ekusys.exam.runtime.config;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(XxlJobProperties.class)
public class XxlJobConfig {
    @Bean
    XxlJobSpringExecutor xxlJobExecutor(XxlJobProperties properties) {
        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(properties.adminAddresses());
        executor.setAccessToken(properties.accessToken());
        executor.setAppname(properties.executor().appname());
        executor.setAddress(properties.executor().address());
        executor.setIp(properties.executor().ip());
        executor.setPort(properties.executor().port());
        executor.setLogPath(properties.executor().logPath());
        executor.setLogRetentionDays(properties.executor().logRetentionDays());
        return executor;
    }
}
