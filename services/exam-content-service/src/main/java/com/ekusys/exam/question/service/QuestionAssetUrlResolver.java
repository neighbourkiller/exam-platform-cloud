package com.ekusys.exam.question.service;

import com.ekusys.exam.common.config.MinioProperties;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.repository.entity.QuestionAsset;
import org.springframework.stereotype.Service;

@Service
public class QuestionAssetUrlResolver {

    private final MinioProperties minioProperties;

    public QuestionAssetUrlResolver(MinioProperties minioProperties) {
        this.minioProperties = minioProperties;
    }

    public String resolve(QuestionAsset asset) {
        if (asset == null) {
            return null;
        }
        String objectKey = trimToNull(asset.getObjectKey());
        if (objectKey == null) {
            return asset.getUrl();
        }
        String endpoint = currentEndpoint();
        String bucket = trimToNull(minioProperties.getBucket());
        if (endpoint == null || bucket == null) {
            return asset.getUrl();
        }
        return buildUrl(endpoint, bucket, objectKey);
    }

    public String buildPublicUrl(String objectKey) {
        String endpoint = currentEndpoint();
        if (endpoint == null) {
            throw new BusinessException("MinIO endpoint 未配置");
        }
        String bucket = trimToNull(minioProperties.getBucket());
        if (bucket == null) {
            throw new BusinessException("MinIO bucket 未配置");
        }
        String key = trimToNull(objectKey);
        if (key == null) {
            throw new BusinessException("MinIO objectKey 未配置");
        }
        return buildUrl(endpoint, bucket, key);
    }

    private String currentEndpoint() {
        String endpoint = trimToNull(minioProperties.getPublicEndpoint());
        if (endpoint == null) {
            endpoint = trimToNull(minioProperties.getEndpoint());
        }
        return endpoint;
    }

    private String buildUrl(String endpoint, String bucket, String objectKey) {
        String normalizedEndpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        String normalizedKey = objectKey.startsWith("/") ? objectKey.substring(1) : objectKey;
        return normalizedEndpoint + "/" + bucket + "/" + normalizedKey;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
