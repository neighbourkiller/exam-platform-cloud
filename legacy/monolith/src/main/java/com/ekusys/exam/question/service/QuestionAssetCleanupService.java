package com.ekusys.exam.question.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ekusys.exam.common.config.AssetCleanupProperties;
import com.ekusys.exam.common.config.MinioProperties;
import com.ekusys.exam.repository.entity.QuestionAsset;
import com.ekusys.exam.repository.mapper.QuestionAssetMapper;
import io.minio.BucketExistsArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Item;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class QuestionAssetCleanupService {

    private static final Logger log = LoggerFactory.getLogger(QuestionAssetCleanupService.class);

    private final QuestionAssetMapper questionAssetMapper;
    private final MinioClient minioClient;
    private final MinioProperties minioProperties;
    private final AssetCleanupProperties assetCleanupProperties;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public QuestionAssetCleanupService(QuestionAssetMapper questionAssetMapper,
                                       MinioClient minioClient,
                                       MinioProperties minioProperties,
                                       AssetCleanupProperties assetCleanupProperties) {
        this.questionAssetMapper = questionAssetMapper;
        this.minioClient = minioClient;
        this.minioProperties = minioProperties;
        this.assetCleanupProperties = assetCleanupProperties;
    }

    public CleanupReport cleanupNow(String trigger) {
        if (!assetCleanupProperties.isEnabled()) {
            return CleanupReport.skipped(trigger, "disabled");
        }
        if (!running.compareAndSet(false, true)) {
            return CleanupReport.skipped(trigger, "already-running");
        }

        try {
            CleanupReport report = new CleanupReport(trigger);
            cleanupOrphanAssets(report);
            cleanupUnusedMinioObjects(report);
            log.info(
                "asset cleanup done trigger={}, orphanScanned={}, orphanDeleted={}, orphanRetained={}, minioScanned={}, minioDeleted={}, minioFailed={}",
                report.trigger(),
                report.orphanScanned(),
                report.orphanDeleted(),
                report.orphanRetained(),
                report.minioScanned(),
                report.minioDeleted(),
                report.minioFailed()
            );
            return report;
        } finally {
            running.set(false);
        }
    }

    private void cleanupOrphanAssets(CleanupReport report) {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(assetCleanupProperties.getOrphanGraceMinutes());
        List<QuestionAsset> orphans = questionAssetMapper.selectList(
            new LambdaQueryWrapper<QuestionAsset>()
                .isNull(QuestionAsset::getQuestionId)
                .le(QuestionAsset::getCreateTime, cutoff)
        );
        report.setOrphanScanned(orphans.size());

        for (QuestionAsset orphan : orphans) {
            if (canDeleteOrphan(orphan)) {
                if (orphan.getId() != null) {
                    questionAssetMapper.deleteById(orphan.getId());
                }
                report.incOrphanDeleted();
            } else {
                report.incOrphanRetained();
            }
        }
    }

    private void cleanupUnusedMinioObjects(CleanupReport report) {
        String prefix = normalizePrefix(assetCleanupProperties.getMinioPrefix());

        Set<String> referencedKeys = loadReferencedObjectKeys();
        List<String> objectKeys;
        try {
            objectKeys = listMinioObjectKeys(prefix);
        } catch (Exception ex) {
            log.warn("asset cleanup list minio objects failed, prefix={}, reason={}", prefix, ex.getMessage());
            return;
        }
        report.setMinioScanned(objectKeys.size());

        for (String objectKey : objectKeys) {
            if (referencedKeys.contains(objectKey)) {
                continue;
            }
            try {
                removeObject(objectKey, true);
                report.incMinioDeleted();
            } catch (Exception ex) {
                report.incMinioFailed();
                log.warn("asset cleanup remove minio object failed, key={}, reason={}", objectKey, ex.getMessage());
            }
        }
    }

    private boolean canDeleteOrphan(QuestionAsset orphan) {
        String objectKey = trimToNull(orphan.getObjectKey());
        if (objectKey == null) {
            return true;
        }
        try {
            removeObject(objectKey, true);
            return true;
        } catch (Exception ex) {
            log.warn("asset cleanup orphan remove failed, assetId={}, key={}, reason={}",
                orphan.getId(), objectKey, ex.getMessage());
            return false;
        }
    }

    List<String> listMinioObjectKeys(String prefix) throws Exception {
        String bucket = minioProperties.getBucket();
        if (bucket == null || bucket.isBlank()) {
            return List.of();
        }
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            return List.of();
        }

        List<String> keys = new ArrayList<>();
        Iterable<Result<Item>> results = minioClient.listObjects(
            ListObjectsArgs.builder()
                .bucket(bucket)
                .prefix(prefix)
                .recursive(true)
                .build()
        );
        for (Result<Item> result : results) {
            Item item = result.get();
            if (item == null || item.isDir()) {
                continue;
            }
            keys.add(item.objectName());
        }
        return keys;
    }

    private Set<String> loadReferencedObjectKeys() {
        List<QuestionAsset> assets = questionAssetMapper.selectList(
            new LambdaQueryWrapper<QuestionAsset>().isNotNull(QuestionAsset::getObjectKey)
        );
        Set<String> keys = new HashSet<>();
        for (QuestionAsset asset : assets) {
            String key = trimToNull(asset.getObjectKey());
            if (key != null) {
                keys.add(key);
            }
        }
        return keys;
    }

    private void removeObject(String objectKey, boolean ignoreNotFound) throws Exception {
        String bucket = minioProperties.getBucket();
        if (bucket == null || bucket.isBlank()) {
            return;
        }
        try {
            minioClient.removeObject(
                RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build()
            );
        } catch (Exception ex) {
            if (ignoreNotFound && isObjectMissing(ex)) {
                return;
            }
            throw ex;
        }
    }

    private boolean isObjectMissing(Exception ex) {
        if (ex instanceof ErrorResponseException errorResponseException) {
            String code = errorResponseException.errorResponse() == null ? null : errorResponseException.errorResponse().code();
            if ("NoSuchKey".equals(code) || "NoSuchBucket".equals(code)) {
                return true;
            }
        }
        String message = ex.getMessage();
        if (message == null) {
            return false;
        }
        return message.contains("NoSuchKey")
            || message.contains("NoSuchBucket")
            || message.contains("404");
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizePrefix(String prefix) {
        String normalized = trimToNull(prefix);
        return normalized == null ? "question/" : normalized;
    }

    public static final class CleanupReport {

        private final String trigger;
        private final boolean skipped;
        private final String skipReason;
        private int orphanScanned;
        private int orphanDeleted;
        private int orphanRetained;
        private int minioScanned;
        private int minioDeleted;
        private int minioFailed;

        private CleanupReport(String trigger) {
            this(trigger, false, null);
        }

        private CleanupReport(String trigger, boolean skipped, String skipReason) {
            this.trigger = trigger;
            this.skipped = skipped;
            this.skipReason = skipReason;
        }

        public static CleanupReport skipped(String trigger, String reason) {
            return new CleanupReport(trigger, true, reason);
        }

        public String trigger() {
            return trigger;
        }

        public boolean skipped() {
            return skipped;
        }

        public String skipReason() {
            return skipReason;
        }

        public int orphanScanned() {
            return orphanScanned;
        }

        public void setOrphanScanned(int orphanScanned) {
            this.orphanScanned = orphanScanned;
        }

        public int orphanDeleted() {
            return orphanDeleted;
        }

        public void incOrphanDeleted() {
            this.orphanDeleted++;
        }

        public int orphanRetained() {
            return orphanRetained;
        }

        public void incOrphanRetained() {
            this.orphanRetained++;
        }

        public int minioScanned() {
            return minioScanned;
        }

        public void setMinioScanned(int minioScanned) {
            this.minioScanned = minioScanned;
        }

        public int minioDeleted() {
            return minioDeleted;
        }

        public void incMinioDeleted() {
            this.minioDeleted++;
        }

        public int minioFailed() {
            return minioFailed;
        }

        public void incMinioFailed() {
            this.minioFailed++;
        }
    }
}
