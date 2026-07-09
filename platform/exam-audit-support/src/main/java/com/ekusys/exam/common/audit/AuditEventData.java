package com.ekusys.exam.common.audit;

import java.time.LocalDateTime;

public record AuditEventData(
    Long operatorId,
    String operatorUsername,
    String operatorRoles,
    String action,
    String targetType,
    String targetId,
    String requestMethod,
    String requestPath,
    String requestIp,
    String detail,
    String status,
    String errorMessage,
    LocalDateTime operateTime
) {
}
