package com.ekusys.exam.management.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ekusys.exam.management.api.RuntimeExamMetadata;
import com.ekusys.exam.management.config.ExamCacheProperties;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class ExamCachePrewarmSchedulerTest {
    @Test
    @SuppressWarnings("unchecked")
    void warmsMetadataAndPaperForUpcomingExam() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ExamManagementService examService = mock(ExamManagementService.class);
        ExamCacheWarmService warmService = mock(ExamCacheWarmService.class);
        ExamCacheProperties properties = new ExamCacheProperties();
        LocalDateTime now = LocalDateTime.of(2026, 7, 24, 9, 50);
        when(jdbc.queryForObject("select current_timestamp(3)", LocalDateTime.class)).thenReturn(now);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("id")).thenReturn(11L);
        when(rs.getLong("paper_snapshot_id")).thenReturn(21L);
        when(jdbc.query(
            anyString(), any(RowMapper.class), eq(now), eq(now.plusMinutes(10)), eq(now), eq(100), eq(0)
        )).thenAnswer(invocation -> List.of(
            ((RowMapper<?>) invocation.getArgument(1)).mapRow(rs, 0)
        ));

        new ExamCachePrewarmScheduler(jdbc, examService, warmService, properties)
            .prewarmUpcomingExams();

        verify(examService).runtimeMetadata(11L);
        verify(warmService).warmPaper(11L, 21L);
    }

    @Test
    void disabledCacheSkipsDatabaseScan() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ExamManagementService examService = mock(ExamManagementService.class);
        ExamCacheWarmService warmService = mock(ExamCacheWarmService.class);
        ExamCacheProperties properties = new ExamCacheProperties();
        properties.setEnabled(false);

        new ExamCachePrewarmScheduler(jdbc, examService, warmService, properties)
            .prewarmUpcomingExams();

        verifyNoInteractions(jdbc, examService, warmService);
    }
}
