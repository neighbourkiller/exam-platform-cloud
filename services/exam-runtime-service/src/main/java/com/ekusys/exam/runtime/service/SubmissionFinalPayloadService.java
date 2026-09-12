package com.ekusys.exam.runtime.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.ekusys.exam.exam.dto.AnswerPayload;
import com.ekusys.exam.runtime.api.GradingAnswerInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SubmissionFinalPayloadService {
    public static final String CODEC = "GZIP_JSON_V1";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SubmissionFinalPayloadService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public EncodedFinalAnswers encode(List<AnswerPayload> answers, long snapshotVersion) {
        Map<Long, String> answerMap = new TreeMap<>();
        for (AnswerPayload answer : answers) {
            answerMap.put(answer.getQuestionId(), answer.getAnswerText() == null ? "" : answer.getAnswerText());
        }
        return encode(answerMap, snapshotVersion);
    }

    public EncodedFinalAnswers encode(Map<Long, String> answers, long snapshotVersion) {
        List<FinalAnswerEntry> entries = new ArrayList<>();
        answers.entrySet().stream()
            .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
            .forEach(entry -> entries.add(new FinalAnswerEntry(
                IdWorker.getId(), entry.getKey(), entry.getValue() == null ? "" : entry.getValue()
            )));
        try {
            byte[] canonical = objectMapper.writeValueAsBytes(
                new FinalAnswerDocument(Math.max(0L, snapshotVersion), List.copyOf(entries))
            );
            return new EncodedFinalAnswers(
                Math.max(0L, snapshotVersion), gzip(canonical), sha256(canonical)
            );
        } catch (IOException exception) {
            throw new IllegalStateException("最终答案编码失败", exception);
        }
    }

    public void store(Long submissionId, String source, EncodedFinalAnswers encoded) {
        int inserted = jdbc.update(
            """
                insert into submission_final_payload(
                    submission_id,source,snapshot_version,codec,payload,payload_sha256,
                    finalized_at,created_at
                ) values(?,?,?,?,?,?,current_timestamp(3),current_timestamp(3))
                """,
            submissionId, source, encoded.snapshotVersion(), CODEC,
            encoded.payload(), encoded.sha256()
        );
        if (inserted != 1) {
            throw new IllegalStateException("最终答案写入失败");
        }
    }

    public void store(Long submissionId, String source, EncodedFinalAnswers encoded,
                      LocalDateTime finalizedAt) {
        int inserted = jdbc.update(
            """
                insert into submission_final_payload(
                    submission_id,source,snapshot_version,codec,payload,payload_sha256,
                    finalized_at,created_at
                ) values(?,?,?,?,?,?,?,current_timestamp(3))
                """,
            submissionId, source, encoded.snapshotVersion(), CODEC,
            encoded.payload(), encoded.sha256(), finalizedAt
        );
        if (inserted != 1) {
            throw new IllegalStateException("最终答案写入失败");
        }
    }

    public List<GradingAnswerInput> loadForGrading(Long submissionId) {
        List<StoredPayload> rows = jdbc.query(
            """
                select codec,payload,payload_sha256
                  from submission_final_payload
                 where submission_id=?
                """,
            (rs, rowNum) -> new StoredPayload(
                rs.getString("codec"), rs.getBytes("payload"), rs.getString("payload_sha256")
            ),
            submissionId
        );
        if (rows.isEmpty()) {
            return List.of();
        }
        StoredPayload stored = rows.getFirst();
        if (!CODEC.equals(stored.codec())) {
            throw new IllegalStateException("不支持的最终答案编码格式: " + stored.codec());
        }
        try {
            byte[] canonical = gunzip(stored.payload());
            if (!sha256(canonical).equalsIgnoreCase(stored.sha256())) {
                throw new IllegalStateException("最终答案校验失败");
            }
            FinalAnswerDocument document = objectMapper.readValue(canonical, FinalAnswerDocument.class);
            return document.answers().stream()
                .map(answer -> new GradingAnswerInput(
                    answer.answerId(), answer.questionId(), answer.answerText()
                ))
                .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("最终答案解码失败", exception);
        }
    }

    public boolean hasPayload(Long submissionId) {
        Integer count = jdbc.queryForObject(
            "select count(*) from submission_final_payload where submission_id=?",
            Integer.class,
            submissionId
        );
        return count != null && count > 0;
    }

    private byte[] gzip(byte[] value) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(value);
        }
        return output.toByteArray();
    }

    private byte[] gunzip(byte[] value) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(value))) {
            return gzip.readAllBytes();
        }
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 不支持 SHA-256", exception);
        }
    }

    public record EncodedFinalAnswers(long snapshotVersion, byte[] payload, String sha256) {
    }

    public record FinalAnswerDocument(long snapshotVersion, List<FinalAnswerEntry> answers) {
    }

    public record FinalAnswerEntry(Long answerId, Long questionId, String answerText) {
    }

    private record StoredPayload(String codec, byte[] payload, String sha256) {
    }
}
