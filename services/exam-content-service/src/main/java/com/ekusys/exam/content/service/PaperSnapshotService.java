package com.ekusys.exam.content.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.content.api.PaperSnapshotAsset;
import com.ekusys.exam.content.api.PaperSnapshotQuestion;
import com.ekusys.exam.content.api.PaperSnapshotView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class PaperSnapshotService {
    private final JdbcTemplate jdbc;
    private final PaperDeliveryCacheService cache;

    public PaperSnapshotService(JdbcTemplate jdbc, PaperDeliveryCacheService cache) {
        this.jdbc = jdbc;
        this.cache = cache;
    }

    @Transactional
    public PaperSnapshotView create(Long paperId) {
        List<PaperRow> papers = jdbc.query(
            "select id,name,subject_id,total_score from paper where id=?",
            (rs, rowNum) -> new PaperRow(
                rs.getLong("id"), rs.getString("name"), rs.getLong("subject_id"), rs.getInt("total_score")
            ),
            paperId
        );
        if (papers.isEmpty()) {
            throw new BusinessException("试卷不存在");
        }
        PaperRow paper = papers.getFirst();
        Long version = jdbc.queryForObject(
            "select coalesce(max(version),0)+1 from paper_snapshot where paper_id=?",
            Long.class,
            paperId
        );
        long snapshotId = IdWorker.getId();
        jdbc.update(
            """
                insert into paper_snapshot(id,paper_id,version,name,subject_id,total_score,created_at)
                values(?,?,?,?,?,?,current_timestamp(3))
                """,
            snapshotId, paperId, version, paper.name(), paper.subjectId(), paper.totalScore()
        );
        List<PaperSnapshotQuestion> questions = jdbc.query(
            """
                select q.id,q.type,q.difficulty,q.content,q.options_json,q.answer,q.analysis,pq.score,pq.sort_order
                  from paper_question pq join question q on q.id=pq.question_id
                 where pq.paper_id=? order by pq.sort_order,pq.id
                """,
            (rs, rowNum) -> new PaperSnapshotQuestion(
                rs.getLong("id"), rs.getString("type"), rs.getString("difficulty"),
                rs.getString("content"), rs.getString("options_json"), rs.getString("answer"),
                rs.getString("analysis"), rs.getInt("score"), rs.getInt("sort_order")
            ),
            paperId
        );
        for (PaperSnapshotQuestion question : questions) {
            jdbc.update(
                """
                    insert into paper_snapshot_question(
                        id,snapshot_id,question_id,type,difficulty,content,options_json,answer,analysis,score,sort_order
                    ) values(?,?,?,?,?,?,?,?,?,?,?)
                    """,
                IdWorker.getId(), snapshotId, question.questionId(), question.type(), question.difficulty(),
                question.content(), question.optionsJson(), question.answer(), question.analysis(),
                question.score(), question.sortOrder()
            );
        }
        List<SourceAssetRow> assets = jdbc.query(
            """
                select qa.question_id,qa.id,qa.file_type,qa.url,qa.object_key,qa.original_name,
                       qa.content_type,qa.size
                  from paper_question pq
                  join question_asset qa on qa.question_id=pq.question_id
                 where pq.paper_id=?
                 order by pq.sort_order,qa.id
                """,
            (rs, rowNum) -> new SourceAssetRow(
                rs.getLong("question_id"), rs.getLong("id"), rs.getString("file_type"),
                rs.getString("url"), rs.getString("object_key"), rs.getString("original_name"),
                rs.getString("content_type"), (Long) rs.getObject("size")
            ),
            paperId
        );
        for (SourceAssetRow asset : assets) {
            jdbc.update(
                """
                    insert into paper_snapshot_asset(
                        id,snapshot_id,question_id,source_asset_id,file_type,url,object_key,
                        original_name,content_type,size
                    ) values(?,?,?,?,?,?,?,?,?,?)
                    """,
                IdWorker.getId(), snapshotId, asset.questionId(), asset.sourceAssetId(), asset.fileType(),
                asset.url(), asset.objectKey(), asset.originalName(), asset.contentType(), asset.size()
            );
        }
        PaperSnapshotView snapshot = loadFromDatabase(snapshotId, true);
        afterCommit(() -> cache.put(snapshotId, snapshot.deliveryView()));
        return snapshot;
    }

    public PaperSnapshotView get(Long id, boolean grading) {
        if (grading) {
            return loadFromDatabase(id, true);
        }
        return cache.get(id, () -> loadFromDatabase(id, false));
    }

    public void warm(Long id) {
        get(id, false);
    }

    private PaperSnapshotView loadFromDatabase(Long id, boolean grading) {
        List<PaperSnapshotView> values = jdbc.query(
            "select * from paper_snapshot where id=?",
            (rs, rowNum) -> new PaperSnapshotView(
                rs.getLong("id"), rs.getLong("paper_id"), rs.getLong("version"), rs.getString("name"),
                rs.getLong("subject_id"), rs.getInt("total_score"), List.of()
            ),
            id
        );
        if (values.isEmpty()) {
            throw new BusinessException("试卷快照不存在");
        }
        PaperSnapshotView value = values.getFirst();
        List<PaperSnapshotQuestion> questions = jdbc.query(
            "select * from paper_snapshot_question where snapshot_id=? order by sort_order,id",
            (rs, rowNum) -> new PaperSnapshotQuestion(
                rs.getLong("question_id"), rs.getString("type"), rs.getString("difficulty"),
                rs.getString("content"), rs.getString("options_json"), rs.getString("answer"),
                rs.getString("analysis"), rs.getInt("score"), rs.getInt("sort_order")
            ),
            id
        );
        Map<Long, List<PaperSnapshotAsset>> assetsByQuestion = jdbc.query(
            """
                select question_id,source_asset_id,url,object_key,original_name,size,file_type
                  from paper_snapshot_asset
                 where snapshot_id=?
                 order by question_id,id
                """,
            (rs, rowNum) -> new SnapshotAssetRow(
                rs.getLong("question_id"),
                new PaperSnapshotAsset(
                    String.valueOf(rs.getLong("source_asset_id")), rs.getString("url"),
                    rs.getString("object_key"), rs.getString("original_name"),
                    (Long) rs.getObject("size"), rs.getString("file_type")
                )
            ),
            id
        ).stream().collect(Collectors.groupingBy(
            SnapshotAssetRow::questionId,
            LinkedHashMap::new,
            Collectors.mapping(SnapshotAssetRow::asset, Collectors.toList())
        ));
        List<PaperSnapshotQuestion> hydrated = questions.stream()
            .map(question -> new PaperSnapshotQuestion(
                question.questionId(), question.type(), question.difficulty(), question.content(),
                question.optionsJson(), question.answer(), question.analysis(), question.score(),
                question.sortOrder(), assetsByQuestion.getOrDefault(question.questionId(), List.of())
            ))
            .toList();
        if (!grading) {
            hydrated = hydrated.stream().map(PaperSnapshotQuestion::deliveryView).toList();
        }
        return new PaperSnapshotView(
            value.snapshotId(), value.paperId(), value.version(), value.name(),
            value.subjectId(), value.totalScore(), hydrated
        );
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private record PaperRow(Long id, String name, Long subjectId, int totalScore) {
    }

    private record SourceAssetRow(Long questionId, Long sourceAssetId, String fileType, String url,
                                  String objectKey, String originalName, String contentType, Long size) {
    }

    private record SnapshotAssetRow(Long questionId, PaperSnapshotAsset asset) {
    }
}
