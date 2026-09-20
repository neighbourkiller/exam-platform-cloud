package com.ekusys.exam.exam.service;

import com.ekusys.exam.common.config.MinioProperties;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.exam.dto.AntiCheatEvidenceUploadView;
import com.ekusys.exam.question.service.QuestionAssetUrlResolver;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.SetBucketPolicyArgs;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ExamAntiCheatEvidenceService {

    private static final Set<String> SOURCES = Set.of("SCREEN", "CAMERA");
    private static final long MAX_EVIDENCE_BYTES = 2 * 1024 * 1024L;

    private final ExamAccessService examAccessService;
    private final ExamSessionService examSessionService;
    private final MinioClient minioClient;
    private final MinioProperties minioProperties;
    private final QuestionAssetUrlResolver assetUrlResolver;
    private volatile boolean bucketInitialized = false;

    public ExamAntiCheatEvidenceService(ExamAccessService examAccessService,
                                        ExamSessionService examSessionService,
                                        MinioClient minioClient,
                                        MinioProperties minioProperties,
                                        QuestionAssetUrlResolver assetUrlResolver) {
        this.examAccessService = examAccessService;
        this.examSessionService = examSessionService;
        this.minioClient = minioClient;
        this.minioProperties = minioProperties;
        this.assetUrlResolver = assetUrlResolver;
    }

    public AntiCheatEvidenceUploadView upload(Long examId, MultipartFile file, String source, String eventType) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请选择要上传的证据图片");
        }
        if (file.getSize() > MAX_EVIDENCE_BYTES) {
            throw new BusinessException("证据图片不能超过2MB");
        }
        String normalizedSource = normalizeSource(source);
        String normalizedEventType = normalizeEventType(eventType);
        String contentType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        if (!contentType.startsWith("image/")) {
            throw new BusinessException("证据文件必须是图片");
        }

        Long studentId = examAccessService.getCurrentUserId();
        examAccessService.checkStudentAccess(examId, studentId);
        examSessionService.requireActiveSession(examId, studentId);

        ensureBucketReady();
        String objectKey = buildObjectKey(examId, studentId, normalizedSource, normalizedEventType, contentType);
        try (InputStream inputStream = file.getInputStream()) {
            minioClient.putObject(PutObjectArgs.builder()
                .bucket(minioProperties.getBucket())
                .object(objectKey)
                .stream(inputStream, file.getSize(), -1)
                .contentType(contentType)
                .build());
        } catch (Exception e) {
            throw new BusinessException("证据图片上传失败: " + e.getMessage());
        }

        return AntiCheatEvidenceUploadView.builder()
            .url(assetUrlResolver.buildPublicUrl(objectKey))
            .objectKey(objectKey)
            .source(normalizedSource)
            .contentType(contentType)
            .size(file.getSize())
            .build();
    }

    private String normalizeSource(String source) {
        String normalized = source == null ? "" : source.trim().toUpperCase(Locale.ROOT);
        if (!SOURCES.contains(normalized)) {
            throw new BusinessException("无效的证据来源");
        }
        return normalized;
    }

    private String normalizeEventType(String eventType) {
        String normalized = eventType == null ? "" : eventType.trim().toUpperCase(Locale.ROOT);
        if (normalized.isBlank() || normalized.length() > 64) {
            throw new BusinessException("无效的异常事件类型");
        }
        return normalized.replaceAll("[^A-Z0-9_]", "_");
    }

    private String buildObjectKey(Long examId, Long studentId, String source, String eventType, String contentType) {
        String datePath = LocalDate.now().toString().replace("-", "/");
        String extension = extensionOf(contentType);
        return "proctoring/%d/%d/%s/%s-%s-%s.%s".formatted(
            examId,
            studentId,
            datePath,
            eventType.toLowerCase(Locale.ROOT),
            source.toLowerCase(Locale.ROOT),
            UUID.randomUUID().toString().replace("-", ""),
            extension
        );
    }

    private String extensionOf(String contentType) {
        return switch (contentType.toLowerCase(Locale.ROOT)) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> "jpg";
        };
    }

    private void ensureBucketReady() {
        if (bucketInitialized) {
            return;
        }
        synchronized (this) {
            if (bucketInitialized) {
                return;
            }
            String bucket = minioProperties.getBucket();
            if (bucket == null || bucket.isBlank()) {
                throw new BusinessException("MinIO bucket 未配置");
            }
            try {
                boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
                if (!exists) {
                    minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                }
                if (minioProperties.isPublicRead()) {
                    minioClient.setBucketPolicy(SetBucketPolicyArgs.builder()
                        .bucket(bucket)
                        .config(buildPublicReadPolicy(bucket))
                        .build());
                }
                bucketInitialized = true;
            } catch (Exception e) {
                throw new BusinessException("初始化 MinIO bucket 失败: " + e.getMessage());
            }
        }
    }

    private String buildPublicReadPolicy(String bucket) {
        return """
            {
              "Version":"2012-10-17",
              "Statement":[
                {
                  "Effect":"Allow",
                  "Principal":{"AWS":["*"]},
                  "Action":["s3:GetObject"],
                  "Resource":["arn:aws:s3:::%s/*"]
                }
              ]
            }
            """.formatted(bucket);
    }
}
