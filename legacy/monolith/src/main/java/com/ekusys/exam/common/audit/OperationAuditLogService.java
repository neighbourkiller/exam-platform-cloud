package com.ekusys.exam.common.audit;

import com.ekusys.exam.repository.entity.OperationAuditLog;
import com.ekusys.exam.repository.mapper.OperationAuditLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationAuditLogService {

    private final OperationAuditLogMapper operationAuditLogMapper;

    public OperationAuditLogService(OperationAuditLogMapper operationAuditLogMapper) {
        this.operationAuditLogMapper = operationAuditLogMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(OperationAuditLog auditLog) {
        if (auditLog == null) {
            return;
        }
        operationAuditLogMapper.insert(auditLog);
    }
}
