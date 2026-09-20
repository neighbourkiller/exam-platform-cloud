package com.ekusys.exam.exam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ekusys.exam.common.config.MinioProperties;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.exam.dto.AntiCheatEvidenceUploadView;
import com.ekusys.exam.exam.service.ExamAccessService;
import com.ekusys.exam.exam.service.ExamAntiCheatEvidenceService;
import com.ekusys.exam.exam.service.ExamSessionService;
import com.ekusys.exam.question.service.QuestionAssetUrlResolver;
import com.ekusys.exam.repository.entity.ExamSession;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class ExamAntiCheatEvidenceServiceTest {

    @Mock
    private ExamAccessService examAccessService;

    @Mock
    private ExamSessionService examSessionService;

    @Mock
    private MinioClient minioClient;

    private ExamAntiCheatEvidenceService evidenceService;

    @BeforeEach
    void setUp() {
        MinioProperties minioProperties = new MinioProperties();
        minioProperties.setEndpoint("http://127.0.0.1:19000");
        minioProperties.setBucket("question-images");
        minioProperties.setPublicRead(false);
        evidenceService = new ExamAntiCheatEvidenceService(
            examAccessService,
            examSessionService,
            minioClient,
            minioProperties,
            new QuestionAssetUrlResolver(minioProperties)
        );
    }

    @Test
    void uploadShouldRejectEmptyFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "empty.jpg", "image/jpeg", new byte[] {});

        assertThrows(BusinessException.class, () -> evidenceService.upload(1L, file, "SCREEN", "FULLSCREEN_EXIT"));
        verify(minioClient, never()).putObject(any(PutObjectArgs.class));
    }

    @Test
    void uploadShouldRejectNonImageFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "note.txt", "text/plain", new byte[] {1});

        assertThrows(BusinessException.class, () -> evidenceService.upload(1L, file, "SCREEN", "FULLSCREEN_EXIT"));
        verify(minioClient, never()).putObject(any(PutObjectArgs.class));
    }

    @Test
    void uploadShouldRejectWhenStudentHasNoAccess() throws Exception {
        MockMultipartFile file = evidenceFile();
        when(examAccessService.getCurrentUserId()).thenReturn(2L);
        org.mockito.Mockito.doThrow(new BusinessException("无考试访问权限"))
            .when(examAccessService).checkStudentAccess(1L, 2L);

        assertThrows(BusinessException.class, () -> evidenceService.upload(1L, file, "SCREEN", "FULLSCREEN_EXIT"));
        verify(minioClient, never()).putObject(any(PutObjectArgs.class));
    }

    @Test
    void uploadShouldRejectWhenSessionInactive() throws Exception {
        MockMultipartFile file = evidenceFile();
        when(examAccessService.getCurrentUserId()).thenReturn(2L);
        org.mockito.Mockito.doThrow(new BusinessException("会话已结束"))
            .when(examSessionService).requireActiveSession(1L, 2L);

        assertThrows(BusinessException.class, () -> evidenceService.upload(1L, file, "SCREEN", "FULLSCREEN_EXIT"));
        verify(minioClient, never()).putObject(any(PutObjectArgs.class));
    }

    @Test
    void uploadShouldPutImageToMinioAndReturnPublicUrl() throws Exception {
        MockMultipartFile file = evidenceFile();
        when(examAccessService.getCurrentUserId()).thenReturn(2L);
        when(examSessionService.requireActiveSession(1L, 2L)).thenReturn(activeSession());
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);

        AntiCheatEvidenceUploadView view = evidenceService.upload(1L, file, "camera", "camera_stream_ended");

        assertEquals("CAMERA", view.getSource());
        assertEquals("image/jpeg", view.getContentType());
        assertEquals(3L, view.getSize());
        org.junit.jupiter.api.Assertions.assertTrue(view.getObjectKey().startsWith("proctoring/1/2/"));
        org.junit.jupiter.api.Assertions.assertTrue(view.getUrl().startsWith("http://127.0.0.1:19000/question-images/proctoring/1/2/"));
        verify(examAccessService).checkStudentAccess(1L, 2L);
        verify(examSessionService).requireActiveSession(1L, 2L);
        verify(minioClient).putObject(any(PutObjectArgs.class));
    }

    private MockMultipartFile evidenceFile() {
        return new MockMultipartFile("file", "evidence.jpg", "image/jpeg", new byte[] {1, 2, 3});
    }

    private ExamSession activeSession() {
        ExamSession session = new ExamSession();
        session.setExamId(1L);
        session.setStudentId(2L);
        session.setStartTime(LocalDateTime.now().minusMinutes(5));
        session.setDeadlineTime(LocalDateTime.now().plusMinutes(55));
        return session;
    }
}
