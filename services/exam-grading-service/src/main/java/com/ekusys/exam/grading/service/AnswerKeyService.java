package com.ekusys.exam.grading.service;

import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.common.util.AnswerJudgeUtil;
import com.ekusys.exam.content.api.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AnswerKeyService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public AnswerKeyService(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    // All callers hold a transaction; activation and first grading share this lock.
    public long lock(Long examId) {
        jdbc.update("insert ignore into grading_exam_key(exam_id) values(?)", examId);
        return jdbc.queryForObject("select current_version from grading_exam_key where exam_id=? for update", Long.class, examId);
    }
    public void baseline(Long examId, PaperSnapshotView paper) {
        Map<Long, String> answers = new TreeMap<>();
        for (var q : paper.questions()) if (AnswerJudgeUtil.isObjectiveType(q.type())) answers.put(q.questionId(), q.answer());
        jdbc.update("""
            insert ignore into grading_key_version(exam_id,version,snapshot_id,reason,answers_json)
            values(?,0,?,'原始试卷快照',?)
            """, examId, paper.snapshotId(), json(answers));
    }
    public Map<Long, String> answers(Long examId, long version) {
        var rows = jdbc.queryForList("select answers_json from grading_key_version where exam_id=? and version=?", String.class, examId, version);
        if (rows.isEmpty()) throw new BusinessException("答案版本不存在");
        try { return mapper.readValue(rows.getFirst(), new TypeReference<TreeMap<Long, String>>() {}); }
        catch (Exception e) { throw new IllegalStateException("无法读取答案版本", e); }
    }
    public String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("无法保存判分记录", e); }
    }
    public String validate(PaperSnapshotQuestion question, String answer) {
        if (!AnswerJudgeUtil.isObjectiveType(question.type()) || answer == null || answer.isBlank() || answer.length() > 10000) {
            throw new BusinessException("只允许修改客观题的非空标准答案");
        }
        String normalized = answer.trim();
        if ("BLANK".equals(question.type())) return normalized;
        Set<String> options = new HashSet<>();
        try {
            var nodes = mapper.readTree(question.optionsJson());
            if (!nodes.isArray()) throw new IllegalArgumentException();
            for (var node : nodes) options.add(node.path("label").asText().trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) { throw new BusinessException("快照选项格式错误，无法纠错"); }
        List<String> selected = Arrays.stream(normalized.split(",", -1)).map(String::trim)
            .map(v -> v.toUpperCase(Locale.ROOT)).toList();
        if (selected.isEmpty() || selected.stream().anyMatch(v -> v.isBlank() || !options.contains(v))
            || new HashSet<>(selected).size() != selected.size()
            || (!"MULTI".equals(question.type()) && selected.size() != 1)) {
            throw new BusinessException("标准答案必须是快照中的有效选项，且不能重复");
        }
        return String.join(",", selected.stream().sorted().toList());
    }
}
