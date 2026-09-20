package com.ekusys.exam.repository.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ekusys.exam.common.model.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("operation_audit_log")
public class OperationAuditLog extends BaseEntity {

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
