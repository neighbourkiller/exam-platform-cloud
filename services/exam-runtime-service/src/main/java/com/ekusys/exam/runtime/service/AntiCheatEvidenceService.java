package com.ekusys.exam.runtime.service;

import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.common.security.SecurityUtils;
import com.ekusys.exam.exam.dto.AntiCheatEvidenceUploadView;
import com.ekusys.exam.runtime.config.RuntimeMinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AntiCheatEvidenceService {
    private static final long MAX_BYTES = 2 * 1024 * 1024L;
    private static final Set<String> SOURCES = Set.of("SCREEN", "CAMERA");

    private final JdbcTemplate jdbc;
    private final MinioClient minio;
    private final RuntimeMinioProperties properties;

    public AntiCheatEvidenceService(JdbcTemplate jdbc, MinioClient minio, RuntimeMinioProperties properties) {
        this.jdbc = jdbc;
        this.minio = minio;
        this.properties = properties;
    }

    public AntiCheatEvidenceUploadView upload(Long examId, MultipartFile file, String source, String eventType) {
        Long studentId = SecurityUtils.getCurrentUserId();
        Integer active = jdbc.queryForObject(
            "select count(*) from exam_session where exam_id=? and student_id=? and status='ANSWERING'",
            Integer.class, examId, studentId);
        if (active == null || active == 0) throw new BusinessException("会话已结束");
        if (file == null || file.isEmpty()) throw new BusinessException("请选择要上传的证据图片");
        if (file.getSize() > MAX_BYTES) throw new BusinessException("证据图片不能超过2MB");
        String contentType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        if (!contentType.startsWith("image/")) throw new BusinessException("证据文件必须是图片");
        String normalizedSource = source == null ? "" : source.trim().toUpperCase(Locale.ROOT);
        if (!SOURCES.contains(normalizedSource)) throw new BusinessException("无效的证据来源");
        String normalizedType = eventType == null ? "" : eventType.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
        if (normalizedType.isBlank() || normalizedType.length() > 64) throw new BusinessException("无效的异常事件类型");
        String objectKey = "%s/%d/%d/%s/%s-%s.%s".formatted(properties.getEvidencePrefix(), examId, studentId,
            LocalDate.now().toString().replace("-", "/"), normalizedType.toLowerCase(Locale.ROOT),
            UUID.randomUUID().toString().replace("-", ""), extension(contentType));
        try {
            if (!minio.bucketExists(BucketExistsArgs.builder().bucket(properties.getBucket()).build())) {
                minio.makeBucket(MakeBucketArgs.builder().bucket(properties.getBucket()).build());
            }
            try (InputStream stream = file.getInputStream()) {
                minio.putObject(PutObjectArgs.builder().bucket(properties.getBucket()).object(objectKey)
                    .stream(stream, file.getSize(), -1).contentType(contentType).build());
            }
        } catch (Exception exception) {
            throw new BusinessException("证据图片上传失败");
        }
        String url = properties.getEndpoint().replaceAll("/$", "") + "/" + properties.getBucket() + "/" + objectKey;
        return new AntiCheatEvidenceUploadView(url, objectKey, normalizedSource, contentType, file.getSize());
    }

    private String extension(String contentType) {
        return switch (contentType.toLowerCase(Locale.ROOT)) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> "jpg";
        };
    }
}
