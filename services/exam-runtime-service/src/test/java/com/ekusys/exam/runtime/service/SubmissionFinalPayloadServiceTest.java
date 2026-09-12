package com.ekusys.exam.runtime.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class SubmissionFinalPayloadServiceTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void gzipPayloadRoundTripsWithChecksumAndStableQuestionOrder() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SubmissionFinalPayloadService service = new SubmissionFinalPayloadService(
            jdbc, new ObjectMapper().findAndRegisterModules()
        );
        var encoded = service.encode(Map.of(20L, "B", 10L, "A"), 12L);
        when(jdbc.update(contains("insert into submission_final_payload"), any(Object[].class)))
            .thenReturn(1);

        service.store(99L, "TIMEOUT", encoded);

        ArgumentCaptor<Object[]> stored = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("insert into submission_final_payload"), stored.capture());
        assertThat(stored.getValue()[0]).isEqualTo(99L);
        assertThat(stored.getValue()[1]).isEqualTo("TIMEOUT");
        assertThat(stored.getValue()[2]).isEqualTo(12L);
        assertThat(stored.getValue()[3]).isEqualTo(SubmissionFinalPayloadService.CODEC);

        doAnswer(invocation -> {
            RowMapper mapper = invocation.getArgument(1);
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.getString("codec")).thenReturn(SubmissionFinalPayloadService.CODEC);
            when(resultSet.getBytes("payload")).thenReturn(encoded.payload());
            when(resultSet.getString("payload_sha256")).thenReturn(encoded.sha256());
            return List.of(mapper.mapRow(resultSet, 0));
        }).when(jdbc).query(
            contains("from submission_final_payload"), any(RowMapper.class), eq(99L)
        );

        var answers = service.loadForGrading(99L);

        assertThat(answers).extracting(answer -> answer.questionId()).containsExactly(10L, 20L);
        assertThat(answers).extracting(answer -> answer.answerText()).containsExactly("A", "B");
        assertThat(answers).allSatisfy(answer -> assertThat(answer.answerId()).isPositive());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void supportsFiveHundredAnswersNearOneMegabyteBoundary() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SubmissionFinalPayloadService service = new SubmissionFinalPayloadService(
            jdbc, new ObjectMapper().findAndRegisterModules()
        );
        Map<Long, String> source = new LinkedHashMap<>();
        IntStream.range(0, 500).forEach(index -> source.put(
            (long) index + 1,
            ("answer-%04d-".formatted(index) + "x".repeat(1_988))
        ));
        var encoded = service.encode(source, 500L);

        doAnswer(invocation -> {
            RowMapper mapper = invocation.getArgument(1);
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.getString("codec")).thenReturn(SubmissionFinalPayloadService.CODEC);
            when(resultSet.getBytes("payload")).thenReturn(encoded.payload());
            when(resultSet.getString("payload_sha256")).thenReturn(encoded.sha256());
            return List.of(mapper.mapRow(resultSet, 0));
        }).when(jdbc).query(
            contains("from submission_final_payload"), any(RowMapper.class), eq(100L)
        );

        var decoded = service.loadForGrading(100L);

        assertThat(decoded).hasSize(500);
        assertThat(decoded.getFirst().answerText()).hasSize(2_000);
        assertThat(decoded.getLast().answerText()).hasSize(2_000);
        assertThat(encoded.sha256()).hasSize(64);
    }

    @Test
    void storesKnownFinalizationTimeWithoutReadingItBack() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SubmissionFinalPayloadService service = new SubmissionFinalPayloadService(
            jdbc, new ObjectMapper().findAndRegisterModules()
        );
        var encoded = service.encode(Map.of(10L, "A"), 12L);
        LocalDateTime finalizedAt = LocalDateTime.of(2026, 9, 12, 12, 0);
        when(jdbc.update(contains("values(?,?,?,?,?,?,?,current_timestamp(3))"), any(Object[].class)))
            .thenReturn(1);

        service.store(99L, "TIMEOUT", encoded, finalizedAt);

        ArgumentCaptor<Object[]> stored = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(
            contains("values(?,?,?,?,?,?,?,current_timestamp(3))"), stored.capture()
        );
        assertThat(stored.getValue()[6]).isEqualTo(finalizedAt);
    }
}
