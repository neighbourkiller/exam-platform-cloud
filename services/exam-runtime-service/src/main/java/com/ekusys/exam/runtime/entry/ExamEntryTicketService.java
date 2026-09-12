package com.ekusys.exam.runtime.entry;

import com.ekusys.exam.runtime.config.ExamEntryProperties;
import com.ekusys.exam.runtime.service.RuntimeTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ExamEntryTicketService {
    private final StringRedisTemplate redis;
    private final ExamEntryProperties properties;

    public ExamEntryTicketService(StringRedisTemplate redis, ExamEntryProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public EntryTicket issue(Long examId, Long studentId, String clientId, int slot,
                             LocalDateTime scheduledAt, LocalDateTime examEnd, LocalDateTime now) {
        Duration ttl = Duration.between(now, examEnd.plusMinutes(properties.safeTicketRetentionMinutes()));
        if (ttl.isNegative() || ttl.isZero()) {
            throw new ExamEntryException(HttpStatus.GONE, "EXAM_ENTRY_CLOSED", "本场考试已结束");
        }
        String referenceKey = referenceKey(examId, studentId, clientId);
        String proposed = UUID.randomUUID().toString();
        try {
            Boolean created = redis.opsForValue().setIfAbsent(referenceKey, proposed, ttl);
            String token = Boolean.TRUE.equals(created) ? proposed : redis.opsForValue().get(referenceKey);
            if (token == null || token.isBlank()) {
                throw unavailable();
            }
            Map<String, String> values = new LinkedHashMap<>();
            values.put("examId", String.valueOf(examId));
            values.put("studentId", String.valueOf(studentId));
            values.put("clientId", clientId);
            values.put("slot", String.valueOf(slot));
            values.put("scheduledAt", String.valueOf(epochMillis(scheduledAt)));
            String ticketKey = ticketKey(token);
            redis.opsForHash().putAll(ticketKey, values);
            redis.expire(ticketKey, ttl);
            redis.expire(referenceKey, ttl);
            return new EntryTicket(
                token, examId, studentId, clientId, slot, scheduledAt, Boolean.TRUE.equals(created)
            );
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    public EntryTicket require(String token, Long examId, Long studentId, String clientId) {
        try {
            Map<Object, Object> values = redis.opsForHash().entries(ticketKey(token));
            if (values.isEmpty()) {
                throw new ExamEntryException(
                    HttpStatus.FORBIDDEN, "EXAM_ENTRY_TOKEN_INVALID", "候场票据已失效，请重新进入候场"
                );
            }
            EntryTicket ticket = new EntryTicket(
                token,
                Long.valueOf(String.valueOf(values.get("examId"))),
                Long.valueOf(String.valueOf(values.get("studentId"))),
                String.valueOf(values.get("clientId")),
                Integer.parseInt(String.valueOf(values.get("slot"))),
                localDateTime(Long.parseLong(String.valueOf(values.get("scheduledAt")))),
                false
            );
            if (!examId.equals(ticket.examId()) || !studentId.equals(ticket.studentId())
                || !clientId.equals(ticket.clientId())) {
                throw new ExamEntryException(
                    HttpStatus.FORBIDDEN, "EXAM_ENTRY_TOKEN_INVALID", "候场票据与当前考试客户端不匹配"
                );
            }
            return ticket;
        } catch (ExamEntryException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw unavailable();
        } catch (RuntimeException exception) {
            throw new ExamEntryException(
                HttpStatus.FORBIDDEN, "EXAM_ENTRY_TOKEN_INVALID", "候场票据内容无效，请重新进入候场"
            );
        }
    }

    static int stableSlot(Long examId, Long studentId, int slotCount) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                .digest((examId + ":" + studentId).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 不支持 SHA-256", exception);
        }
        long value = 0L;
        for (int index = 0; index < Long.BYTES; index++) {
            value = (value << Byte.SIZE) | (digest[index] & 0xffL);
        }
        return (int) Math.floorMod(value, (long) Math.max(1, slotCount));
    }

    private ExamEntryException unavailable() {
        return new ExamEntryException(
            HttpStatus.SERVICE_UNAVAILABLE, "EXAM_ENTRY_UNAVAILABLE",
            "考试候场服务暂时不可用，请稍后重试", 500L
        );
    }

    private String referenceKey(Long examId, Long studentId, String clientId) {
        return "exam:entry-ticket-ref:{" + examId + ":" + studentId + "}:" + sha256(clientId);
    }

    private String ticketKey(String token) {
        return "exam:entry-ticket:" + sha256(token == null ? "" : token);
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 不支持 SHA-256", exception);
        }
    }

    private long epochMillis(LocalDateTime value) {
        return RuntimeTime.epochMillis(value);
    }

    private LocalDateTime localDateTime(long value) {
        return RuntimeTime.localDateTime(value);
    }

    public record EntryTicket(String token, Long examId, Long studentId, String clientId,
                              int slot, LocalDateTime scheduledAt, boolean newlyIssued) {
    }
}
