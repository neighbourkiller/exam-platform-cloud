package com.ekusys.exam.admin.dto;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OperationAuditLogView {

    private Long id;
    private Long operatorId;
    private String operatorUsername;
    private String operatorRoles;
    private String action;
    private String targetType;
    private String targetId;
    private String requestMethod;
    private String requestPath;
    private String requestIp;
    private String detail;
    private String status;
    private String errorMessage;
    private LocalDateTime operateTime;
}
