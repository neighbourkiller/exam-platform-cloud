package com.ekusys.exam.question.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ekusys.exam.common.config.AssetCleanupProperties;
import com.ekusys.exam.common.config.MinioProperties;
import com.ekusys.exam.question.service.QuestionAssetCleanupService.CleanupReport;
import com.ekusys.exam.repository.entity.QuestionAsset;
import com.ekusys.exam.repository.mapper.QuestionAssetMapper;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QuestionAssetCleanupServiceTest {

    @Mock
    private QuestionAssetMapper questionAssetMapper;

    @Mock
    private MinioClient minioClient;

    private MinioProperties minioProperties;
    private AssetCleanupProperties assetCleanupProperties;
    private QuestionAssetCleanupService cleanupService;

    @BeforeEach
    void setUp() {
        minioProperties = new MinioProperties();
        minioProperties.setBucket("question-images");

        assetCleanupProperties = new AssetCleanupProperties();
        assetCleanupProperties.setEnabled(true);
        assetCleanupProperties.setStartupRun(true);
        assetCleanupProperties.setOrphanGraceMinutes(30);
        assetCleanupProperties.setMinioPrefix("question/");

        cleanupService = new QuestionAssetCleanupService(
            questionAssetMapper, minioClient, minioProperties, assetCleanupProperties
        );
    }

    @Test
    void cleanupShouldDeleteOrphansAndUnusedObjects() throws Exception {
        QuestionAsset orphan = new QuestionAsset();
        orphan.setId(11L);
        orphan.setQuestionId(null);
        orphan.setObjectKey("question/orphan.png");
        orphan.setCreateTime(LocalDateTime.now().minusHours(2));

        QuestionAsset referenced = new QuestionAsset();
        referenced.setObjectKey("question/keep.png");

        when(questionAssetMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(List.of(orphan), List.of(referenced));

        QuestionAssetCleanupService serviceSpy = spy(cleanupService);
        doReturn(List.of("question/keep.png", "question/free.png"))
            .when(serviceSpy).listMinioObjectKeys("question/");

        CleanupReport report = serviceSpy.cleanupNow("scheduled");

        assertEquals(1, report.orphanScanned());
        assertEquals(1, report.orphanDeleted());
        assertEquals(0, report.orphanRetained());
        assertEquals(2, report.minioScanned());
        assertEquals(1, report.minioDeleted());
        assertEquals(0, report.minioFailed());
        verify(questionAssetMapper).deleteById(11L);
        verify(minioClient, times(2)).removeObject(any(RemoveObjectArgs.class));
    }

    @Test
    void cleanupShouldKeepRowWhenOrphanObjectDeleteFailed() throws Exception {
        QuestionAsset orphan = new QuestionAsset();
        orphan.setId(22L);
        orphan.setQuestionId(null);
        orphan.setObjectKey("question/will-fail.png");
        orphan.setCreateTime(LocalDateTime.now().minusHours(3));

        when(questionAssetMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(List.of(orphan), List.of());

        QuestionAssetCleanupService serviceSpy = spy(cleanupService);
        doReturn(List.of()).when(serviceSpy).listMinioObjectKeys(anyString());
        doThrow(new RuntimeException("network-error"))
            .when(minioClient).removeObject(any(RemoveObjectArgs.class));

        CleanupReport report = serviceSpy.cleanupNow("scheduled");

        assertEquals(1, report.orphanScanned());
        assertEquals(0, report.orphanDeleted());
        assertEquals(1, report.orphanRetained());
        verify(questionAssetMapper, never()).deleteById(22L);
    }

    @Test
    void cleanupShouldDeleteRowWhenObjectAlreadyMissing() throws Exception {
        QuestionAsset orphan = new QuestionAsset();
        orphan.setId(33L);
        orphan.setQuestionId(null);
        orphan.setObjectKey("question/missing.png");
        orphan.setCreateTime(LocalDateTime.now().minusHours(2));

        when(questionAssetMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(List.of(orphan), List.of());

        QuestionAssetCleanupService serviceSpy = spy(cleanupService);
        doReturn(List.of()).when(serviceSpy).listMinioObjectKeys(anyString());
        doThrow(new RuntimeException("NoSuchKey"))
            .when(minioClient).removeObject(any(RemoveObjectArgs.class));

        CleanupReport report = serviceSpy.cleanupNow("scheduled");

        assertEquals(1, report.orphanDeleted());
        verify(questionAssetMapper).deleteById(33L);
    }

    @Test
    void cleanupShouldSkipWhenAlreadyRunning() throws Exception {
        setRunningFlag(cleanupService, true);

        CleanupReport report = cleanupService.cleanupNow("scheduled");

        assertTrue(report.skipped());
        assertEquals("already-running", report.skipReason());
    }

    @Test
    void cleanupShouldSkipWhenDisabled() {
        assetCleanupProperties.setEnabled(false);

        CleanupReport report = cleanupService.cleanupNow("scheduled");

        assertTrue(report.skipped());
        assertEquals("disabled", report.skipReason());
    }

    private void setRunningFlag(QuestionAssetCleanupService service, boolean value) throws Exception {
        Field field = QuestionAssetCleanupService.class.getDeclaredField("running");
        field.setAccessible(true);
        AtomicBoolean running = (AtomicBoolean) field.get(service);
        running.set(value);
    }
}
