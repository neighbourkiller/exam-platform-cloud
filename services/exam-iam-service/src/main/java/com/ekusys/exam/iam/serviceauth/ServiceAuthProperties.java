package com.ekusys.exam.iam.serviceauth;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.service-auth")
public class ServiceAuthProperties {
    private String sharedSecret;
    private List<String> allowedClients = List.of();
    private long tokenTtlSeconds = 300;

    public String getSharedSecret() {
        return sharedSecret;
    }

    public void setSharedSecret(String sharedSecret) {
        this.sharedSecret = sharedSecret;
    }

    public List<String> getAllowedClients() {
        return allowedClients;
    }

    public void setAllowedClients(List<String> allowedClients) {
        this.allowedClients = allowedClients;
    }

    public long getTokenTtlSeconds() {
        return tokenTtlSeconds;
    }

    public void setTokenTtlSeconds(long tokenTtlSeconds) {
        this.tokenTtlSeconds = tokenTtlSeconds;
    }
}
