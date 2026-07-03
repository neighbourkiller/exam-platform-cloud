package com.ekusys.exam.admin.dto;

import java.time.LocalDateTime;

public record OperationAuditLogView(Long id, Long operatorId, String operatorUsername, String operatorRoles,
                                    String action, String targetType, String targetId, String requestMethod,
                                    String requestPath, String requestIp, String detail, String status,
                                    String errorMessage, LocalDateTime operateTime) {
}
