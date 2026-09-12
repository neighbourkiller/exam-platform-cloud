package com.ekusys.exam.content.service;

import com.ekusys.exam.content.api.*;
import com.ekusys.exam.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuestionCorrectionService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public QuestionCorrectionService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }
    public QuestionCorrectionView view(Long id) { return read(id, false); }

    private QuestionCorrectionView read(Long id, boolean lock) {
        var rows = jdbc.query("select id,type,content,options_json,answer,creator_id from question where id=?"
            + (lock ? " for update" : ""), (rs, n) -> new QuestionCorrectionView(rs.getLong("id"),
                rs.getString("type"), rs.getString("content"), rs.getString("options_json"),
                rs.getString("answer"), null, rs.getObject("creator_id", Long.class)), id);
        if (rows.isEmpty()) return null;
        var q = rows.getFirst();
        return new QuestionCorrectionView(q.questionId(), q.type(), q.content(), q.optionsJson(),
            q.answer(), hash(java.util.Arrays.asList(q.type(), q.content(), q.optionsJson(), q.answer())), q.creatorId());
    }

    @Transactional
    public QuestionCorrectionResult correct(Long id, QuestionCorrectionCommand command) {
        if (command.operatorId() == null || command.operationId() == null || command.operationId().isBlank() || command.operationId().length() > 64
            || command.answer() == null || command.answer().isBlank() || command.answer().length() > 10000) {
            throw new BusinessException("无效的题库纠错请求");
        }
        var q = read(id, true);
        String hash = hash(command);
        var previous = jdbc.queryForList("select * from question_answer_correction where operation_id=?", command.operationId());
        if (!previous.isEmpty()) {
            var row = previous.getFirst();
            if (!hash.equals(row.get("request_hash")) || !id.equals(((Number) row.get("question_id")).longValue())) {
                throw new BusinessException("纠错请求标识已用于其他内容");
            }
            return new QuestionCorrectionResult((String) row.get("status"), (String) row.get("message"));
        }
        String status = "APPLIED";
        String message = "题库答案已同步";
        if (q == null) { status = "DELETED"; message = "题库题目不存在或已删除"; }
        else if (!command.administrator() && !Objects.equals(q.creatorId(), command.operatorId())) {
            status = "FORBIDDEN"; message = "仅题目创建者或管理员可修改题库";
        } else if (!Objects.equals(q.fingerprint(), command.expectedFingerprint())) {
            status = "CONFLICT"; message = "题库题目已变化，请核对后重试";
        } else if (!List.of("SINGLE", "MULTI", "JUDGE", "BLANK").contains(q.type())) {
            status = "CONFLICT"; message = "题库题型不是客观题";
        } else {
            jdbc.update("update question set answer=?,update_time=current_timestamp(3) where id=?", command.answer(), id);
        }
        jdbc.update("""
            insert into question_answer_correction(operation_id,question_id,operator_id,request_hash,
                previous_answer,corrected_answer,status,message) values(?,?,?,?,?,?,?,?)
            """, command.operationId(), id, command.operatorId(), hash, q == null ? null : q.answer(),
            command.answer(), status, message);
        return new QuestionCorrectionResult(status, message);
    }
    private String hash(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(mapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("无法计算题库版本指纹", e); }
    }
}
