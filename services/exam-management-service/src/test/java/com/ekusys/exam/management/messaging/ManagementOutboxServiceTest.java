package com.ekusys.exam.management.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ekusys.exam.academic.api.SubjectSummary;
import com.ekusys.exam.common.outbox.OutboxEventWriter;
import com.ekusys.exam.content.api.PaperSnapshotView;
import com.ekusys.exam.repository.entity.Exam;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class ManagementOutboxServiceTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void examPublishedEventKeepsExistingWireShape() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        OutboxEventWriter writer = mock(OutboxEventWriter.class);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(11L))).thenReturn(List.of());
        ManagementOutboxService service = new ManagementOutboxService(jdbc, writer);
        Exam exam = new Exam();
        exam.setId(11L);
        exam.setName("期末考试");
        exam.setStartTime(LocalDateTime.of(2026, 7, 12, 10, 0));
        exam.setEndTime(LocalDateTime.of(2026, 7, 12, 12, 0));
        exam.setDurationMinutes(120);
        exam.setPassScore(60);
        exam.setStatus("PUBLISHED");
        exam.setPublisherId(7L);
        PaperSnapshotView paper = new PaperSnapshotView(21L, 31L, 1, "试卷", 41L, 100, List.of());

        service.examPublished(exam, paper, new SubjectSummary(41L, "Java", null), Map.of());

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(writer).append(any(String.class), eq("EXAM"), eq("11"), eq("ExamPublished"), event.capture());
        Map<String, Object> payload = (Map<String, Object>) event.getValue();
        Map<String, Object> data = (Map<String, Object>) payload.get("data");
        assertThat(payload.get("producer")).isEqualTo("exam-management-service");
        assertThat(payload.get("aggregateId")).isEqualTo("11");
        assertThat(data.get("examId")).isEqualTo(11L);
        assertThat(data.get("subjectName")).isEqualTo("Java");
        assertThat(data.get("candidates")).isEqualTo(List.of());
    }
}
