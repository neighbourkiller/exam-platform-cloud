package com.ekusys.exam.iam.config;

import com.ekusys.exam.common.security.JwtTokenProvider;
import feign.RequestInterceptor;
import feign.Retryer;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;

@Configuration
public class IamFeignSecurityConfig {
    @Bean
    RequestInterceptor iamServiceIdentityInterceptor(JwtTokenProvider tokenProvider) {
        return template -> template.header(HttpHeaders.AUTHORIZATION,
            "Bearer " + tokenProvider.createServiceToken("exam-iam-service", List.of("internal"), 300));
    }

    @Bean
    Retryer iamFeignRetryer() {
        return Retryer.NEVER_RETRY;
    }
}
