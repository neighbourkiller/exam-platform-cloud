package com.ekusys.exam.runtime.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.client-lease")
public class ClientLeaseProperties {
    private int heartbeatIntervalSeconds = 30;
    private int leaseTimeoutSeconds = 90;
    private int persistenceIntervalSeconds = 60;

    public int getHeartbeatIntervalSeconds() {
        return heartbeatIntervalSeconds;
    }

    public void setHeartbeatIntervalSeconds(int heartbeatIntervalSeconds) {
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
    }

    public int getLeaseTimeoutSeconds() {
        return leaseTimeoutSeconds;
    }

    public void setLeaseTimeoutSeconds(int leaseTimeoutSeconds) {
        this.leaseTimeoutSeconds = leaseTimeoutSeconds;
    }

    public int getPersistenceIntervalSeconds() {
        return persistenceIntervalSeconds;
    }

    public void setPersistenceIntervalSeconds(int persistenceIntervalSeconds) {
        this.persistenceIntervalSeconds = persistenceIntervalSeconds;
    }

    public int safeHeartbeatIntervalSeconds() {
        return Math.max(5, heartbeatIntervalSeconds);
    }

    public int safeLeaseTimeoutSeconds() {
        return Math.max(safeHeartbeatIntervalSeconds() * 2, leaseTimeoutSeconds);
    }

    public int safePersistenceIntervalSeconds() {
        return Math.max(safeHeartbeatIntervalSeconds(), persistenceIntervalSeconds);
    }
}
