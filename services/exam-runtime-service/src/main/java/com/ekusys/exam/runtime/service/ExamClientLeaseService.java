package com.ekusys.exam.runtime.service;

import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.exam.dto.ExamClientLeaseView;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ExamClientLeaseService {
    public static final String REQUIRED_CODE = "EXAM_CLIENT_REQUIRED";
    public static final String CONFLICT_CODE = "EXAM_CLIENT_CONFLICT";
    private static final int HEARTBEAT_INTERVAL_SECONDS = 15;
    private static final int LEASE_TIMEOUT_SECONDS = 90;
    private static final int MAX_CLIENT_ID_LENGTH = 128;
    private static final int MAX_TOKEN_LENGTH = 128;

    private final JdbcTemplate jdbc;

    public ExamClientLeaseService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ExamClientLeaseView createInitialLease(String clientId, LocalDateTime now) {
        validateClientId(clientId);
        return newLease(now);
    }

    public ExamClientLeaseView acquire(Long sessionId, String clientId, String leaseToken, LocalDateTime now) {
        validateClientId(clientId);
        if (hasText(leaseToken)) {
            validateToken(leaseToken);
            ExamClientLeaseView renewed = newLease(now);
            int updated = jdbc.update(
                """
                    update exam_session
                       set active_client_token=?,active_client_lease_until=?,
                           active_client_last_seen=?,update_time=?
                     where id=? and status='ANSWERING'
                       and active_client_id=? and active_client_token=?
                    """,
                renewed.getLeaseToken(), renewed.getLeaseExpiresAt(),
                now, now, sessionId, clientId, leaseToken
            );
            if (updated == 1) {
                return renewed;
            }
        }

        ExamClientLeaseView acquired = newLease(now);
        int updated = jdbc.update(
            """
                update exam_session
                   set active_client_id=?,active_client_token=?,active_client_lease_until=?,
                       active_client_last_seen=?,update_time=?
                 where id=? and status='ANSWERING'
                   and (active_client_id is null
                    or active_client_token is null
                    or active_client_lease_until is null
                    or active_client_lease_until<=?)
                """,
            clientId, acquired.getLeaseToken(), acquired.getLeaseExpiresAt(),
            now, now, sessionId, now
        );
        if (updated == 1) {
            return acquired;
        }
        throw conflict();
    }

    public ExamClientLeaseView renew(Long sessionId, String clientId, String leaseToken, LocalDateTime now) {
        validateClientId(clientId);
        validateToken(leaseToken);
        ExamClientLeaseView renewed = newLease(now);
        int updated = jdbc.update(
            """
                update exam_session
                   set active_client_token=?,active_client_lease_until=?,
                       active_client_last_seen=?,update_time=?
                 where id=? and status='ANSWERING'
                   and active_client_id=? and active_client_token=?
                """,
            renewed.getLeaseToken(), renewed.getLeaseExpiresAt(),
            now, now, sessionId, clientId, leaseToken
        );
        if (updated != 1) {
            throw conflict();
        }
        return renewed;
    }

    public void requireCurrent(Long sessionId, String clientId, String leaseToken, LocalDateTime now) {
        validateClientId(clientId);
        validateToken(leaseToken);
        int updated = jdbc.update(
            """
                update exam_session
                   set active_client_last_seen=?,update_time=?
                 where id=? and status='ANSWERING'
                   and active_client_id=? and active_client_token=?
                """,
            now, now, sessionId, clientId, leaseToken
        );
        if (updated != 1) {
            throw conflict();
        }
    }

    public void clear(Long sessionId) {
        jdbc.update(
            """
                update exam_session
                   set active_client_id=null,active_client_token=null,
                       active_client_lease_until=null,active_client_last_seen=null,
                       update_time=current_timestamp(3)
                 where id=?
                """,
            sessionId
        );
    }

    private ExamClientLeaseView newLease(LocalDateTime now) {
        return ExamClientLeaseView.builder()
            .leaseToken(UUID.randomUUID().toString())
            .leaseExpiresAt(now.plusSeconds(LEASE_TIMEOUT_SECONDS))
            .heartbeatIntervalSeconds(HEARTBEAT_INTERVAL_SECONDS)
            .leaseTimeoutSeconds(LEASE_TIMEOUT_SECONDS)
            .build();
    }

    private void validateClientId(String clientId) {
        if (!hasText(clientId) || clientId.length() > MAX_CLIENT_ID_LENGTH) {
            throw new BusinessException(REQUIRED_CODE, "考试客户端标识缺失，请刷新考试页面后重试");
        }
    }

    private void validateToken(String leaseToken) {
        if (!hasText(leaseToken) || leaseToken.length() > MAX_TOKEN_LENGTH) {
            throw new BusinessException(REQUIRED_CODE, "考试窗口租约已失效，请返回考试列表后重新进入");
        }
    }

    private BusinessException conflict() {
        return new BusinessException(CONFLICT_CODE, "本场考试已在其他窗口答题，请回到原窗口继续作答或等待租约过期后重试");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
