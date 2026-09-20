package com.ekusys.exam.question.service;

import com.ekusys.exam.common.config.MinioProperties;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.common.security.SecurityUtils;
import com.ekusys.exam.question.dto.QuestionImageUploadView;
import com.ekusys.exam.repository.entity.QuestionAsset;
import com.ekusys.exam.repository.mapper.QuestionAssetMapper;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.SetBucketPolicyArgs;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class QuestionImageService {

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;
    private final QuestionAssetUrlResolver assetUrlResolver;
    private final QuestionAssetMapper questionAssetMapper;
    private volatile boolean bucketInitialized = false;

    public QuestionImageService(MinioClient minioClient,
                                MinioProperties minioProperties,
                                QuestionAssetUrlResolver assetUrlResolver,
                                QuestionAssetMapper questionAssetMapper) {
        this.minioClient = minioClient;
        this.minioProperties = minioProperties;
        this.assetUrlResolver = assetUrlResolver;
        this.questionAssetMapper = questionAssetMapper;
    }

    public QuestionImageUploadView upload(MultipartFile file, Long questionId) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请选择要上传的图片");
        }
        String contentType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        String fileType = detectFileType(contentType);

        ensureBucketReady();
        String objectKey = buildObjectKey(file.getOriginalFilename());

        try (InputStream inputStream = file.getInputStream()) {
            minioClient.putObject(PutObjectArgs.builder()
                .bucket(minioProperties.getBucket())
                .object(objectKey)
                .stream(inputStream, file.getSize(), -1)
                .contentType(contentType)
                .build());
        } catch (Exception e) {
            throw new BusinessException("图片上传失败: " + e.getMessage());
        }

        String url = assetUrlResolver.buildPublicUrl(objectKey);
        QuestionAsset asset = new QuestionAsset();
        asset.setQuestionId(questionId);
        asset.setUploaderId(SecurityUtils.getCurrentUserId() == null ? 0L : SecurityUtils.getCurrentUserId());
        asset.setFileType(fileType);
        asset.setUrl(url);
        asset.setObjectKey(objectKey);
        asset.setOriginalName(file.getOriginalFilename());
        asset.setContentType(contentType);
        asset.setSize(file.getSize());
        questionAssetMapper.insert(asset);

        return QuestionImageUploadView.builder()
            .assetId(asset.getId() == null ? null : String.valueOf(asset.getId()))
            .url(url)
            .objectKey(objectKey)
            .originalName(file.getOriginalFilename())
            .size(file.getSize())
            .fileType(fileType)
            .build();
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

    private String buildObjectKey(String originalFilename) {
        String datePath = LocalDate.now().toString().replace("-", "/");
        String extension = "";
        if (originalFilename != null) {
            int index = originalFilename.lastIndexOf('.');
            if (index > -1) {
                extension = originalFilename.substring(index).toLowerCase();
            }
        }
        return "question/" + datePath + "/" + UUID.randomUUID().toString().replace("-", "") + extension;
    }

    private String detectFileType(String contentType) {
        if (contentType.startsWith("image/")) {
            return "IMAGE";
        }
        if (contentType.startsWith("video/")) {
            return "VIDEO";
        }
        return "ATTACHMENT";
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
