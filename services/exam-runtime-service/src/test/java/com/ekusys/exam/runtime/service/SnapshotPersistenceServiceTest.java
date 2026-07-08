package com.ekusys.exam.runtime.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ekusys.exam.exam.dto.AnswerPayload;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class SnapshotPersistenceServiceTest {
    private JdbcTemplate jdbc;
    private SnapshotPersistenceService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        service = new SnapshotPersistenceService(jdbc);
    }

    @Test
    void staleVersionDoesNotReplacePersistedAnswers() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        when(jdbc.queryForList(anyString(), any(Class.class), any(Object[].class)))
            .thenReturn(List.of(120L));

        long storedVersion = service.persistDraft(1L, 2L, List.of(answer("old")), 100L);

        assertEquals(120L, storedVersion);
        verify(jdbc, never()).queryForObject(anyString(), any(Class.class), any(Object[].class));
    }

    @Test
    void newerVersionUsesConditionalClaimBeforeReplacingAnswers() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForObject(anyString(), any(Class.class), any(Object[].class))).thenReturn(99L);

        long storedVersion = service.persistDraft(1L, 2L, List.of(answer("new")), 130L);

        assertEquals(130L, storedVersion);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.atLeastOnce()).update(sql.capture(), any(Object[].class));
        String statements = String.join("\n", sql.getAllValues());
        org.junit.jupiter.api.Assertions.assertTrue(statements.contains("draft_version<?"));
        org.junit.jupiter.api.Assertions.assertTrue(statements.contains("delete from submission_answer"));
        org.junit.jupiter.api.Assertions.assertTrue(statements.contains("insert into submission_answer"));
    }

    private AnswerPayload answer(String text) {
        AnswerPayload answer = new AnswerPayload();
        answer.setQuestionId(11L);
        answer.setAnswerText(text);
        return answer;
    }
}
