package com.ekusys.exam.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ekusys.exam.common.api.ApiResponse;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.content.api.PaperSnapshotView;
import com.ekusys.exam.management.api.RuntimeExamMetadata;
import com.ekusys.exam.management.client.AcademicRosterClient;
import com.ekusys.exam.management.client.ContentSnapshotClient;
import com.ekusys.exam.management.messaging.ManagementOutboxService;
import com.ekusys.exam.repository.mapper.ExamMapper;
import com.ekusys.exam.repository.mapper.ExamTargetClassMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class ExamManagementServiceAdmissionTest {
    @Test
    @SuppressWarnings("unchecked")
    void publishedCandidateGetsMetadataAndDeliveryWithoutCandidateList() throws Exception {
        Fixture fixture = fixture("PUBLISHED", true);
        PaperSnapshotView paper = new PaperSnapshotView(21L, 31L, 1L, "Java", 41L, 100, List.of());
        when(fixture.content.delivery(21L)).thenReturn(ApiResponse.ok(paper));

        var result = fixture.service.admission(11L, 7L);

        assertThat(result.metadata()).isEqualTo(fixture.metadata);
        assertThat(result.paper()).isEqualTo(paper);
    }

    @Test
    void terminatedExamIsRejectedBeforeContentCall() throws Exception {
        Fixture fixture = fixture("TERMINATED", true);

        assertThatThrownBy(() -> fixture.service.admission(11L, 7L))
            .isInstanceOf(BusinessException.class)
            .hasMessage("考试未发布或已终止");

        verifyNoInteractions(fixture.content);
    }

    private Fixture fixture(String status, boolean eligible) throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("status")).thenReturn(status);
        when(rs.getBoolean("eligible")).thenReturn(eligible);
        when(jdbc.query(
            contains("exists("), any(RowMapper.class), eq(7L), eq(11L)
        )).thenAnswer(invocation -> List.of(
            ((RowMapper<?>) invocation.getArgument(1)).mapRow(rs, 0)
        ));
        RuntimeExamMetadata metadata = new RuntimeExamMetadata(
            11L, "Java", LocalDateTime.of(2026, 7, 24, 10, 0),
            LocalDateTime.of(2026, 7, 24, 12, 0), 120, 60,
            21L, 1L, 31L, "STANDARD", null
        );
        ExamMetadataCacheService metadataCache = mock(ExamMetadataCacheService.class);
        when(metadataCache.get(eq(11L), any())).thenReturn(metadata);
        ContentSnapshotClient content = mock(ContentSnapshotClient.class);
        ExamManagementService service = new ExamManagementService(
            mock(ExamMapper.class), mock(ExamTargetClassMapper.class), jdbc,
            mock(AcademicRosterClient.class), content, new ObjectMapper(),
            mock(ManagementOutboxService.class), metadataCache, mock(ExamCacheWarmService.class)
        );
        return new Fixture(service, content, metadata);
    }

    private record Fixture(ExamManagementService service, ContentSnapshotClient content,
                           RuntimeExamMetadata metadata) {
    }
}
