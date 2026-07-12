package com.ekusys.exam.grading.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ekusys.exam.common.outbox.OutboxEventWriter;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class GradingOutboxServiceTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void gradeCompletedEventKeepsExistingWireShape() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        OutboxEventWriter writer = mock(OutboxEventWriter.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getLong("runtime_submission_id")).thenReturn(99L);
        when(resultSet.getLong("exam_id")).thenReturn(11L);
        when(resultSet.getLong("student_id")).thenReturn(7L);
        when(resultSet.getString("exam_name")).thenReturn("期末考试");
        when(resultSet.getObject("submitted_at", LocalDateTime.class))
            .thenReturn(LocalDateTime.of(2026, 7, 12, 10, 0));
        when(resultSet.getInt("objective_score")).thenReturn(80);
        when(resultSet.getInt("subjective_score")).thenReturn(10);
        when(resultSet.getInt("total_score")).thenReturn(90);
        when(resultSet.getBoolean("pass_flag")).thenReturn(true);
        when(resultSet.getString("status")).thenReturn("GRADED");
        when(resultSet.getObject("completed_at", LocalDateTime.class))
            .thenReturn(LocalDateTime.of(2026, 7, 12, 11, 0));
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(99L)))
            .thenAnswer(invocation -> ((RowMapper) invocation.getArgument(1)).mapRow(resultSet, 0));
        when(jdbc.query(anyString(), any(RowMapper.class), eq(99L))).thenReturn(List.of());
        GradingOutboxService service = new GradingOutboxService(jdbc, writer);

        service.gradeCompleted(99L);

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(writer).append(any(String.class), eq("GRADE"), eq("99"), eq("GradeCompleted"), event.capture());
        Map<String, Object> payload = (Map<String, Object>) event.getValue();
        Map<String, Object> data = (Map<String, Object>) payload.get("data");
        assertThat(payload.get("producer")).isEqualTo("exam-grading-service");
        assertThat(data.get("submissionId")).isEqualTo(99L);
        assertThat(data.get("totalScore")).isEqualTo(90);
        assertThat(data.get("questionResults")).isEqualTo(List.of());
    }
}
