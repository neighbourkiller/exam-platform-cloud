package com.ekusys.exam.common.security;

import feign.RequestInterceptor;
import feign.Retryer;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(ServiceIdentityProperties.class)
@ConditionalOnProperty(prefix = "exam.security.service", name = "enabled", havingValue = "true")
public class ServiceIdentityConfiguration {
    @Bean("serviceIdentityRestClientBuilder")
    @LoadBalanced
    RestClient.Builder serviceIdentityRestClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    ServiceTokenProvider serviceTokenProvider(
        @Qualifier("serviceIdentityRestClientBuilder") RestClient.Builder builder,
        ServiceIdentityProperties properties
    ) {
        return new ServiceTokenProvider(builder.build(), properties);
    }

    @Bean
    RequestInterceptor serviceIdentityRequestInterceptor(ServiceTokenProvider tokenProvider) {
        return template -> template.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.token());
    }

    @Bean
    Retryer feignRetryer() {
        return Retryer.NEVER_RETRY;
    }

    static final class ServiceTokenProvider {
        private final RestClient restClient;
        private final ServiceIdentityProperties properties;
        private volatile CachedToken cached;

        ServiceTokenProvider(RestClient restClient, ServiceIdentityProperties properties) {
            this.restClient = restClient;
            this.properties = properties;
        }

        String token() {
            if (properties.getClientId() == null || properties.getClientId().isBlank()
                || properties.getClientSecret() == null || properties.getClientSecret().isBlank()) {
                throw new IllegalStateException("服务身份 client-id/client-secret 未配置");
            }
            CachedToken current = cached;
            if (current != null && current.expiresAt().isAfter(Instant.now().plusSeconds(15))) {
                return current.value();
            }
            synchronized (this) {
                current = cached;
                if (current != null && current.expiresAt().isAfter(Instant.now().plusSeconds(15))) {
                    return current.value();
                }
                TokenResponse response = restClient.post()
                    .uri(properties.getTokenUrl())
                    .body(Map.of("clientId", properties.getClientId(), "clientSecret", properties.getClientSecret()))
                    .retrieve()
                    .body(TokenResponse.class);
                if (response == null || response.accessToken() == null) {
                    throw new IllegalStateException("IAM 未返回服务令牌");
                }
                cached = new CachedToken(response.accessToken(), Instant.now().plusSeconds(response.expiresIn()));
                return cached.value();
            }
        }

        private record TokenResponse(String accessToken, long expiresIn) {
        }

        private record CachedToken(String value, Instant expiresAt) {
        }
    }
}
