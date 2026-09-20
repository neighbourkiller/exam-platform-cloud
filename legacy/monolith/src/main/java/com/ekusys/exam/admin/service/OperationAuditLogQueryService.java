package com.ekusys.exam.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ekusys.exam.admin.dto.OperationAuditLogView;
import com.ekusys.exam.common.api.PageResponse;
import com.ekusys.exam.repository.entity.OperationAuditLog;
import com.ekusys.exam.repository.mapper.OperationAuditLogMapper;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

@Service
public class OperationAuditLogQueryService {

    private final OperationAuditLogMapper operationAuditLogMapper;

    public OperationAuditLogQueryService(OperationAuditLogMapper operationAuditLogMapper) {
        this.operationAuditLogMapper = operationAuditLogMapper;
    }

    public PageResponse<OperationAuditLogView> query(long pageNum,
                                                     long pageSize,
                                                     String operatorKeyword,
                                                     String action,
                                                     String targetType,
                                                     String targetId,
                                                     String status,
                                                     LocalDateTime startTime,
                                                     LocalDateTime endTime) {
        LambdaQueryWrapper<OperationAuditLog> wrapper = new LambdaQueryWrapper<OperationAuditLog>()
            .orderByDesc(OperationAuditLog::getOperateTime);
        if (operatorKeyword != null && !operatorKeyword.isBlank()) {
            wrapper.like(OperationAuditLog::getOperatorUsername, operatorKeyword.trim());
        }
        if (action != null && !action.isBlank()) {
            wrapper.eq(OperationAuditLog::getAction, action.trim());
        }
        if (targetType != null && !targetType.isBlank()) {
            wrapper.eq(OperationAuditLog::getTargetType, targetType.trim());
        }
        if (targetId != null && !targetId.isBlank()) {
            wrapper.eq(OperationAuditLog::getTargetId, targetId.trim());
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(OperationAuditLog::getStatus, status.trim());
        }
        if (startTime != null) {
            wrapper.ge(OperationAuditLog::getOperateTime, startTime);
        }
        if (endTime != null) {
            wrapper.le(OperationAuditLog::getOperateTime, endTime);
        }

        Page<OperationAuditLog> page = operationAuditLogMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return PageResponse.<OperationAuditLogView>builder()
            .pageNum(page.getCurrent())
            .pageSize(page.getSize())
            .total(page.getTotal())
            .records(page.getRecords().stream().map(this::toView).toList())
            .build();
    }

    private OperationAuditLogView toView(OperationAuditLog log) {
        return OperationAuditLogView.builder()
            .id(log.getId())
            .operatorId(log.getOperatorId())
            .operatorUsername(log.getOperatorUsername())
            .operatorRoles(log.getOperatorRoles())
            .action(log.getAction())
            .targetType(log.getTargetType())
            .targetId(log.getTargetId())
            .requestMethod(log.getRequestMethod())
            .requestPath(log.getRequestPath())
            .requestIp(log.getRequestIp())
            .detail(log.getDetail())
            .status(log.getStatus())
            .errorMessage(log.getErrorMessage())
            .operateTime(log.getOperateTime())
            .build();
    }
}
