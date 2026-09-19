package com.ekusys.exam.runtime.service;

import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.exam.dto.AnswerPayload;
import com.ekusys.exam.exam.dto.SnapshotRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SnapshotDraftPayloadService {
    static final String CODEC = "GZIP_JSON_V1";
    static final String SESSION_ENDED_CODE = "EXAM_SESSION_ENDED";
    static final String CLIENT_CONFLICT_CODE = "EXAM_CLIENT_CONFLICT";
    static final String SEQUENCE_CONFLICT_CODE = "SNAPSHOT_SEQUENCE_CONFLICT";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SnapshotDraftPayloadService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * 将答案规范化、压缩并计算摘要。该方法不访问数据库，调用方可以在事务外执行，
     * 使锁内事务只承担验收和持久化。
     */
    public PreparedDraft prepare(List<AnswerPayload> answers) {
        EncodedDraft encoded = encode(answers);
        return new PreparedDraft(encoded.answers(), encoded.payload(), encoded.sha256());
    }

    @Transactional
    public Acceptance accept(Long sessionId, Long examId, Long studentId, SnapshotRequest request) {
        return accept(sessionId, examId, studentId, request, prepare(request.getAnswers()));
    }

    /**
     * 只在这里执行数据库验收。此方法由外部服务调用，事务代理不会被同类方法调用绕过。
     */
    @Transactional
    public Acceptance accept(Long sessionId, Long examId, Long studentId,
                             SnapshotRequest request, PreparedDraft prepared) {
        SessionFence fence = jdbc.query(
            """
                select s.status,s.deadline_time,s.active_client_id,s.active_client_token,
                       sub.id submission_id
                  from exam_session s
                  join submission sub on sub.exam_id=s.exam_id and sub.student_id=s.student_id
                 where s.id=? and s.exam_id=? and s.student_id=?
                 for update
                """,
            (rs, rowNum) -> new SessionFence(
                rs.getString("status"),
                rs.getObject("deadline_time", LocalDateTime.class),
                rs.getString("active_client_id"),
                rs.getString("active_client_token"),
                rs.getLong("submission_id")
            ),
            sessionId, examId, studentId
        ).stream().findFirst().orElseThrow(() -> new BusinessException("考试会话不存在"));

        // FOR UPDATE 可能在锁等待后才返回，不能使用锁等待前的语句时间判断截止。
        LocalDateTime dbNow = jdbc.queryForObject("select current_timestamp(3)", LocalDateTime.class);
        if (!"ANSWERING".equals(fence.status()) || fence.deadline() == null
            || dbNow == null || !fence.deadline().isAfter(dbNow)) {
            throw new BusinessException(SESSION_ENDED_CODE, "考试会话已结束");
        }
        if (!request.getClientId().equals(fence.clientId())
            || !request.getLeaseToken().equals(fence.leaseToken())) {
            throw new BusinessException(CLIENT_CONFLICT_CODE, "当前设备不再持有考试租约");
        }

        long clientSequence = resolveClientSequence(request, dbNow);
        EncodedDraft encoded = new EncodedDraft(
            prepared.answers(), prepared.payload(), prepared.sha256()
        );
        StoredDraft stored = loadLocked(fence.submissionId());
        if (stored != null) {
            if (clientSequence == stored.clientSequence()) {
                if (!request.getClientId().equals(stored.clientId())
                    || !encoded.sha256().equalsIgnoreCase(stored.sha256())) {
                    throw new BusinessException(SEQUENCE_CONFLICT_CODE, "同一快照序列对应不同答案内容");
                }
                return Acceptance.accepted(
                    stored.serverRevision(), stored.clientSequence(), stored.acceptedAt(),
                    fence.deadline(), decode(stored)
                );
            }
            if (clientSequence < stored.clientSequence()
                || request.getBaseServerRevision() != null
                && request.getBaseServerRevision() != stored.serverRevision()) {
                return Acceptance.rejected(
                    stored.serverRevision(), stored.clientSequence(), dbNow, fence.deadline()
                );
            }
        } else if (request.getBaseServerRevision() != null
            && request.getBaseServerRevision() != 0L) {
            return Acceptance.rejected(0L, 0L, dbNow, fence.deadline());
        }

        long nextRevision = stored == null ? 1L : stored.serverRevision() + 1L;
        jdbc.update(
            """
                insert into submission_draft_payload(
                    submission_id,client_id,client_sequence,server_revision,codec,payload,
                    payload_sha256,accepted_at,created_at,updated_at
                ) values(?,?,?,?,?,?,?,?,?,?)
                on duplicate key update client_id=values(client_id),
                    client_sequence=values(client_sequence),server_revision=values(server_revision),
                    codec=values(codec),payload=values(payload),payload_sha256=values(payload_sha256),
                    accepted_at=values(accepted_at),updated_at=values(updated_at)
                """,
            fence.submissionId(), request.getClientId(), clientSequence, nextRevision, CODEC,
            encoded.payload(), encoded.sha256(), dbNow, dbNow, dbNow
        );
        jdbc.update(
            "update submission set draft_version=greatest(draft_version,?),update_time=? where id=?",
            clientSequence, dbNow, fence.submissionId()
        );
        jdbc.update(
            "update exam_session set last_snapshot_time=?,update_time=? where id=?",
            dbNow, dbNow, sessionId
        );
        return Acceptance.accepted(
            nextRevision, clientSequence, dbNow, fence.deadline(),
            new SnapshotDraft(encoded.answers(), nextRevision, dbNow)
        );
    }

    public SnapshotDraft loadLatest(Long examId, Long studentId) {
        List<StoredDraft> rows = jdbc.query(
            """
                select d.client_id,d.client_sequence,d.server_revision,d.codec,d.payload,
                       d.payload_sha256,d.accepted_at
                  from submission_draft_payload d
                  join submission s on s.id=d.submission_id
                 where s.exam_id=? and s.student_id=?
                """,
            (rs, rowNum) -> stored(rs), examId, studentId
        );
        return rows.isEmpty() ? null : decode(rows.getFirst());
    }

    public SnapshotDraft loadLatestBySubmissionId(Long submissionId) {
        if (submissionId == null) {
            return null;
        }
        List<StoredDraft> rows = jdbc.query(
            """
                select client_id,client_sequence,server_revision,codec,payload,
                       payload_sha256,accepted_at
                  from submission_draft_payload
                 where submission_id=?
                """,
            (rs, rowNum) -> stored(rs), submissionId
        );
        return rows.isEmpty() ? null : decode(rows.getFirst());
    }

    private StoredDraft loadLocked(Long submissionId) {
        List<StoredDraft> rows = jdbc.query(
            """
                select client_id,client_sequence,server_revision,codec,payload,payload_sha256,accepted_at
                  from submission_draft_payload
                 where submission_id=?
                 for update
                """,
            (rs, rowNum) -> stored(rs), submissionId
        );
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private StoredDraft stored(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new StoredDraft(
            rs.getString("client_id"), rs.getLong("client_sequence"),
            rs.getLong("server_revision"), rs.getString("codec"), rs.getBytes("payload"),
            rs.getString("payload_sha256"), rs.getObject("accepted_at", LocalDateTime.class)
        );
    }

    private long resolveClientSequence(SnapshotRequest request, LocalDateTime dbNow) {
        if (request.getClientSequence() != null) return request.getClientSequence();
        if (request.getSnapshotVersion() != null) return request.getSnapshotVersion();
        if (request.getClientTimestamp() != null) return request.getClientTimestamp();
        return RuntimeTime.epochMillis(dbNow);
    }

    private EncodedDraft encode(List<AnswerPayload> answers) {
        Map<Long, String> answerMap = new LinkedHashMap<>();
        answers.stream()
            .sorted(Comparator.comparing(AnswerPayload::getQuestionId))
            .forEach(answer -> answerMap.put(
                answer.getQuestionId(), answer.getAnswerText() == null ? "" : answer.getAnswerText()
            ));
        try {
            byte[] canonical = objectMapper.writeValueAsBytes(new DraftDocument(answerMap));
            return new EncodedDraft(Map.copyOf(answerMap), gzip(canonical), sha256(canonical));
        } catch (IOException exception) {
            throw new IllegalStateException("草稿载荷编码失败", exception);
        }
    }

    private SnapshotDraft decode(StoredDraft stored) {
        if (!CODEC.equals(stored.codec())) {
            throw new IllegalStateException("不支持的草稿载荷编码格式: " + stored.codec());
        }
        try {
            byte[] canonical = gunzip(stored.payload());
            if (!sha256(canonical).equalsIgnoreCase(stored.sha256())) {
                throw new IllegalStateException("草稿载荷校验失败");
            }
            DraftDocument document = objectMapper.readValue(canonical, DraftDocument.class);
            return new SnapshotDraft(Map.copyOf(document.answers()), stored.serverRevision(), stored.acceptedAt());
        } catch (IOException exception) {
            throw new IllegalStateException("草稿载荷解码失败", exception);
        }
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

    public record Acceptance(boolean accepted, long serverRevision, long storedClientSequence,
                             LocalDateTime acceptedAt, LocalDateTime deadline, SnapshotDraft draft) {
        static Acceptance accepted(long revision, long sequence, LocalDateTime acceptedAt,
                                   LocalDateTime deadline, SnapshotDraft draft) {
            return new Acceptance(true, revision, sequence, acceptedAt, deadline, draft);
        }

        static Acceptance rejected(long revision, long sequence, LocalDateTime now,
                                   LocalDateTime deadline) {
            return new Acceptance(false, revision, sequence, now, deadline, null);
        }
    }

    private record SessionFence(String status, LocalDateTime deadline, String clientId,
                                String leaseToken, Long submissionId) {
    }

    private record StoredDraft(String clientId, long clientSequence, long serverRevision,
                               String codec, byte[] payload, String sha256,
                               LocalDateTime acceptedAt) {
    }

    private record EncodedDraft(Map<Long, String> answers, byte[] payload, String sha256) {
    }

    public record PreparedDraft(Map<Long, String> answers, byte[] payload, String sha256) {
    }

    private record DraftDocument(Map<Long, String> answers) {
    }
}
