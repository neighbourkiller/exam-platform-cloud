package com.ekusys.exam.exam;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ekusys.exam.auth.config.AuthRateLimitProperties;
import com.ekusys.exam.common.security.AuthRateLimitFilter;
import com.ekusys.exam.common.security.JwtAuthenticationFilter;
import com.ekusys.exam.common.security.AuthRateLimitService;
import com.ekusys.exam.exam.controller.ExamController;
import com.ekusys.exam.exam.dto.ProctoringDispositionView;
import com.ekusys.exam.exam.dto.StartExamResponse;
import com.ekusys.exam.exam.dto.SubmitResultView;
import com.ekusys.exam.exam.dto.AntiCheatEvidenceUploadView;
import com.ekusys.exam.exam.service.ExamAntiCheatEvidenceService;
import com.ekusys.exam.exam.service.ExamProctoringService;
import com.ekusys.exam.exam.service.ExamService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

@WebMvcTest(value = ExamController.class, excludeFilters = @ComponentScan.Filter(
    type = FilterType.ASSIGNABLE_TYPE,
    classes = {JwtAuthenticationFilter.class, AuthRateLimitFilter.class}
))
@AutoConfigureMockMvc(addFilters = false)
class ExamControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private ExamService examService;

    @MockitoBean
    private ExamProctoringService examProctoringService;

    @MockitoBean
    private ExamAntiCheatEvidenceService examAntiCheatEvidenceService;

    @MockitoBean
    private AuthRateLimitService authRateLimitService;

    @MockitoBean
    private AuthRateLimitProperties authRateLimitProperties;

    @Test
    void startShouldReturnExamPayload() throws Exception {
        when(examService.startExam(1L)).thenReturn(StartExamResponse.builder()
            .examId(1L)
            .examName("Java期末")
            .resumed(true)
            .durationMinutes(60)
            .startTime(LocalDateTime.of(2026, 4, 1, 10, 0))
            .endTime(LocalDateTime.of(2026, 4, 1, 11, 0))
            .draftUpdatedAt(LocalDateTime.of(2026, 4, 1, 10, 20))
            .questions(List.of())
            .build());

        mockMvc.perform(post("/api/v1/exams/1/start"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.examId").value(1))
            .andExpect(jsonPath("$.data.examName").value("Java期末"))
            .andExpect(jsonPath("$.data.resumed").value(true))
            .andExpect(jsonPath("$.data.draftUpdatedAt").exists());
    }

    @Test
    void submitShouldReturnProcessingResult() throws Exception {
        when(examService.submit(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.any())).thenReturn(
            SubmitResultView.builder()
                .submissionId(99L)
                .status("PROCESSING")
                .build()
        );

        mockMvc.perform(post("/api/v1/exams/1/submit")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of(
                    "answers", List.of(java.util.Map.of("questionId", 1, "answerText", "A"))
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.submissionId").value(99))
            .andExpect(jsonPath("$.data.totalScore").doesNotExist())
            .andExpect(jsonPath("$.data.status").value("PROCESSING"));
    }

    @Test
    void submitShouldReturnFallbackStatusWhenSyncDowngraded() throws Exception {
        when(examService.submit(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.any())).thenReturn(
            SubmitResultView.builder()
                .submissionId(100L)
                .objectiveScore(60)
                .subjectiveScore(0)
                .totalScore(60)
                .passFlag(true)
                .status("GRADED")
                .build()
        );

        mockMvc.perform(post("/api/v1/exams/1/submit")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of(
                    "answers", List.of(java.util.Map.of("questionId", 1, "answerText", "A"))
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.submissionId").value(100))
            .andExpect(jsonPath("$.data.totalScore").value(60))
            .andExpect(jsonPath("$.data.status").value("GRADED"));
    }

    @Test
    void updateProctoringDispositionShouldReturnSavedDisposition() throws Exception {
        when(examProctoringService.updateStudentDisposition(
            org.mockito.ArgumentMatchers.eq(1L),
            org.mockito.ArgumentMatchers.eq(1001L),
            org.mockito.ArgumentMatchers.any()
        )).thenReturn(ProctoringDispositionView.builder()
            .status("CONFIRMED")
            .remark("已核查")
            .handledBy(200L)
            .handledByName("teacher1")
            .handledAt(LocalDateTime.of(2026, 4, 16, 12, 0))
            .build());

        mockMvc.perform(put("/api/v1/exams/1/proctoring/students/1001/disposition")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of(
                    "status", "CONFIRMED",
                    "remark", "已核查"
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.data.remark").value("已核查"))
            .andExpect(jsonPath("$.data.handledByName").value("teacher1"));
    }

    @Test
    void uploadAntiCheatEvidenceShouldReturnEvidenceUrl() throws Exception {
        when(examAntiCheatEvidenceService.upload(
            org.mockito.ArgumentMatchers.eq(1L),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq("SCREEN"),
            org.mockito.ArgumentMatchers.eq("FULLSCREEN_EXIT")
        )).thenReturn(AntiCheatEvidenceUploadView.builder()
            .url("http://127.0.0.1:19000/question-images/proctoring/1/2/a.jpg")
            .objectKey("proctoring/1/2/a.jpg")
            .source("SCREEN")
            .contentType("image/jpeg")
            .size(12L)
            .build());

        MockMultipartFile file = new MockMultipartFile(
            "file",
            "evidence.jpg",
            "image/jpeg",
            new byte[] {1, 2, 3}
        );

        mockMvc.perform(multipart("/api/v1/exams/1/anti-cheat-evidence")
                .file(file)
                .param("source", "SCREEN")
                .param("eventType", "FULLSCREEN_EXIT"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.source").value("SCREEN"))
            .andExpect(jsonPath("$.data.url").value("http://127.0.0.1:19000/question-images/proctoring/1/2/a.jpg"));
    }
}






