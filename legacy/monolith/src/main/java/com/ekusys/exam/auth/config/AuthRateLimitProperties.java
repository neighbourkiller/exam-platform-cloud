package com.ekusys.exam.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.auth.rate-limit")
public class AuthRateLimitProperties {

    private String keyPrefix = "auth:rate-limit:";
    private long windowSeconds = 60;
    private long loginLimit = 10;
    private long refreshLimit = 30;

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public long getWindowSeconds() {
        return windowSeconds;
    }

    public void setWindowSeconds(long windowSeconds) {
        this.windowSeconds = windowSeconds;
    }

    public long getLoginLimit() {
        return loginLimit;
    }

    public void setLoginLimit(long loginLimit) {
        this.loginLimit = loginLimit;
    }

    public long getRefreshLimit() {
        return refreshLimit;
    }

    public void setRefreshLimit(long refreshLimit) {
        this.refreshLimit = refreshLimit;
    }
}
