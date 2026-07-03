package com.ekusys.exam.content.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.content.api.PaperSnapshotQuestion;
import com.ekusys.exam.content.api.PaperSnapshotAsset;
import com.ekusys.exam.content.api.PaperSnapshotView;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaperSnapshotService {
    private final JdbcTemplate jdbc;
    public PaperSnapshotService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public PaperSnapshotView create(Long paperId) {
        List<PaperRow> papers = jdbc.query("select id,name,subject_id,total_score from paper where id=?",
            (rs,n) -> new PaperRow(rs.getLong("id"),rs.getString("name"),rs.getLong("subject_id"),rs.getInt("total_score")), paperId);
        if (papers.isEmpty()) throw new BusinessException("试卷不存在");
        PaperRow paper=papers.getFirst();
        Long version=jdbc.queryForObject("select coalesce(max(version),0)+1 from paper_snapshot where paper_id=?",Long.class,paperId);
        long snapshotId=IdWorker.getId();
        jdbc.update("insert into paper_snapshot(id,paper_id,version,name,subject_id,total_score,created_at) values(?,?,?,?,?,?,current_timestamp(3))",
            snapshotId,paperId,version,paper.name(),paper.subjectId(),paper.totalScore());
        List<PaperSnapshotQuestion> questions=jdbc.query("""
            select q.id,q.type,q.difficulty,q.content,q.options_json,q.answer,q.analysis,pq.score,pq.sort_order
              from paper_question pq join question q on q.id=pq.question_id
             where pq.paper_id=? order by pq.sort_order,pq.id
            """,(rs,n)->new PaperSnapshotQuestion(rs.getLong("id"),rs.getString("type"),rs.getString("difficulty"),
                rs.getString("content"),rs.getString("options_json"),rs.getString("answer"),rs.getString("analysis"),
                rs.getInt("score"),rs.getInt("sort_order")),paperId);
        for(PaperSnapshotQuestion q:questions) jdbc.update("""
            insert into paper_snapshot_question(id,snapshot_id,question_id,type,difficulty,content,options_json,answer,analysis,score,sort_order)
            values(?,?,?,?,?,?,?,?,?,?,?)
            """,IdWorker.getId(),snapshotId,q.questionId(),q.type(),q.difficulty(),q.content(),q.optionsJson(),q.answer(),q.analysis(),q.score(),q.sortOrder());
        for(PaperSnapshotQuestion q:questions) jdbc.query("select * from question_asset where question_id=? order by id",
            (RowCallbackHandler) rs -> jdbc.update("insert into paper_snapshot_asset(id,snapshot_id,question_id,source_asset_id,file_type,url,object_key,original_name,content_type,size) values(?,?,?,?,?,?,?,?,?,?)",
                IdWorker.getId(),snapshotId,q.questionId(),rs.getLong("id"),rs.getString("file_type"),rs.getString("url"),
                rs.getString("object_key"),rs.getString("original_name"),rs.getString("content_type"),rs.getObject("size")),q.questionId());
        return get(snapshotId,true);
    }

    public PaperSnapshotView get(Long id, boolean grading) {
        List<PaperSnapshotView> values=jdbc.query("select * from paper_snapshot where id=?",(rs,n)->new PaperSnapshotView(
            rs.getLong("id"),rs.getLong("paper_id"),rs.getLong("version"),rs.getString("name"),rs.getLong("subject_id"),
            rs.getInt("total_score"),List.of()),id);
        if(values.isEmpty()) throw new BusinessException("试卷快照不存在");
        PaperSnapshotView value=values.getFirst();
        List<PaperSnapshotQuestion> questions=jdbc.query("select * from paper_snapshot_question where snapshot_id=? order by sort_order,id",
            (rs,n)->new PaperSnapshotQuestion(rs.getLong("question_id"),rs.getString("type"),rs.getString("difficulty"),rs.getString("content"),
                rs.getString("options_json"),rs.getString("answer"),rs.getString("analysis"),rs.getInt("score"),rs.getInt("sort_order")),id);
        questions=questions.stream().map(question->new PaperSnapshotQuestion(question.questionId(),question.type(),question.difficulty(),
            question.content(),question.optionsJson(),question.answer(),question.analysis(),question.score(),question.sortOrder(),
            jdbc.query("select source_asset_id,url,object_key,original_name,size,file_type from paper_snapshot_asset where snapshot_id=? and question_id=? order by id",
                (rs,n)->new PaperSnapshotAsset(String.valueOf(rs.getLong("source_asset_id")),rs.getString("url"),
                    rs.getString("object_key"),rs.getString("original_name"),(Long)rs.getObject("size"),rs.getString("file_type")),id,question.questionId()))).toList();
        if(!grading) questions=questions.stream().map(PaperSnapshotQuestion::deliveryView).toList();
        return new PaperSnapshotView(value.snapshotId(),value.paperId(),value.version(),value.name(),value.subjectId(),value.totalScore(),questions);
    }

    private record PaperRow(Long id,String name,Long subjectId,int totalScore) {}
}
