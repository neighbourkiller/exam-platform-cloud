package com.ekusys.exam.content.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ekusys.exam.content.api.PaperSnapshotView;
import java.sql.ResultSet;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class PaperSnapshotServiceTest {
    @Test
    @SuppressWarnings("unchecked")
    void deliveryLoadsSnapshotQuestionsAndAllAssetsWithThreeQueries() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PaperDeliveryCacheService cache = mock(PaperDeliveryCacheService.class);
        when(cache.get(eq(11L), any())).thenAnswer(invocation ->
            ((Supplier<PaperSnapshotView>) invocation.getArgument(1)).get()
        );

        ResultSet snapshot = mock(ResultSet.class);
        when(snapshot.getLong("id")).thenReturn(11L);
        when(snapshot.getLong("paper_id")).thenReturn(21L);
        when(snapshot.getLong("version")).thenReturn(1L);
        when(snapshot.getString("name")).thenReturn("Java");
        when(snapshot.getLong("subject_id")).thenReturn(31L);
        when(snapshot.getInt("total_score")).thenReturn(100);
        when(jdbc.query(
            contains("from paper_snapshot where"), any(RowMapper.class), eq(11L)
        )).thenAnswer(invocation -> List.of(
            ((RowMapper<?>) invocation.getArgument(1)).mapRow(snapshot, 0)
        ));

        ResultSet question = mock(ResultSet.class);
        when(question.getLong("question_id")).thenReturn(41L);
        when(question.getString("type")).thenReturn("SINGLE");
        when(question.getString("difficulty")).thenReturn("EASY");
        when(question.getString("content")).thenReturn("题目");
        when(question.getString("options_json")).thenReturn("[\"A\"]");
        when(question.getString("answer")).thenReturn("A");
        when(question.getString("analysis")).thenReturn("解析");
        when(question.getInt("score")).thenReturn(10);
        when(question.getInt("sort_order")).thenReturn(1);
        when(jdbc.query(
            contains("from paper_snapshot_question"), any(RowMapper.class), eq(11L)
        )).thenAnswer(invocation -> List.of(
            ((RowMapper<?>) invocation.getArgument(1)).mapRow(question, 0)
        ));

        ResultSet asset = mock(ResultSet.class);
        when(asset.getLong("question_id")).thenReturn(41L);
        when(asset.getLong("source_asset_id")).thenReturn(51L);
        when(asset.getString("url")).thenReturn("https://example.test/a.png");
        when(asset.getString("object_key")).thenReturn("a.png");
        when(asset.getString("original_name")).thenReturn("a.png");
        when(asset.getObject("size")).thenReturn(10L);
        when(asset.getString("file_type")).thenReturn("IMAGE");
        when(jdbc.query(
            contains("from paper_snapshot_asset"), any(RowMapper.class), eq(11L)
        )).thenAnswer(invocation -> List.of(
            ((RowMapper<?>) invocation.getArgument(1)).mapRow(asset, 0)
        ));

        PaperSnapshotView result = new PaperSnapshotService(jdbc, cache).get(11L, false);

        assertThat(result.questions()).hasSize(1);
        assertThat(result.questions().getFirst().assets()).hasSize(1);
        assertThat(result.questions().getFirst().answer()).isNull();
        assertThat(result.questions().getFirst().analysis()).isNull();
        verify(jdbc, times(1)).query(
            contains("from paper_snapshot_asset"), any(RowMapper.class), eq(11L)
        );
    }
}
