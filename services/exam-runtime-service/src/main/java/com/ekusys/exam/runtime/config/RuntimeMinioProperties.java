package com.ekusys.exam.runtime.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.minio")
public class RuntimeMinioProperties {
    private String endpoint;
    private String accessKey;
    private String secretKey;
    private String bucket = "question-images";
    private String evidencePrefix = "proctoring";

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public String getEvidencePrefix() { return evidencePrefix; }
    public void setEvidencePrefix(String evidencePrefix) { this.evidencePrefix = evidencePrefix; }
}
