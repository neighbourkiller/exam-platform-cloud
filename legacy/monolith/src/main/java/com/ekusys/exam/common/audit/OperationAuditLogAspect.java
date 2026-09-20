package com.ekusys.exam.common.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ekusys.exam.common.security.SecurityUtils;
import com.ekusys.exam.repository.entity.OperationAuditLog;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Aspect
@Component
public class OperationAuditLogAspect {

    private static final Logger log = LoggerFactory.getLogger(OperationAuditLogAspect.class);
    private static final int MAX_ERROR_MESSAGE_LENGTH = 500;

    private final OperationAuditLogService operationAuditLogService;
    private final ObjectMapper objectMapper;
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();
    private final ExpressionParser expressionParser = new SpelExpressionParser();

    public OperationAuditLogAspect(OperationAuditLogService operationAuditLogService,
                                   ObjectMapper objectMapper) {
        this.operationAuditLogService = operationAuditLogService;
        this.objectMapper = objectMapper;
    }

    @Around("@annotation(auditOperation)")
    public Object around(ProceedingJoinPoint joinPoint, AuditOperation auditOperation) throws Throwable {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        HttpServletRequest request = currentRequest();
        Object result = null;
        Throwable failure = null;

        try {
            result = joinPoint.proceed();
            return result;
        } catch (Throwable ex) {
            failure = ex;
            throw ex;
        } finally {
            OperationAuditLog auditLog = buildAuditLog(joinPoint, method, request, auditOperation, result, failure);
            try {
                operationAuditLogService.record(auditLog);
            } catch (Exception ex) {
                log.warn("Failed to persist operation audit log: action={}, targetType={}, targetId={}",
                    auditLog.getAction(), auditLog.getTargetType(), auditLog.getTargetId(), ex);
            }
        }
    }

    private OperationAuditLog buildAuditLog(ProceedingJoinPoint joinPoint,
                                            Method method,
                                            HttpServletRequest request,
                                            AuditOperation auditOperation,
                                            Object result,
                                            Throwable failure) {
        StandardEvaluationContext evaluationContext = buildEvaluationContext(method, joinPoint.getArgs(), result);
        OperationAuditLog auditLog = new OperationAuditLog();
        auditLog.setOperatorId(SecurityUtils.getCurrentUserId());
        auditLog.setOperatorUsername(SecurityUtils.getCurrentUsername());
        auditLog.setOperatorRoles(joinRoles(SecurityUtils.getCurrentRoles()));
        auditLog.setAction(auditOperation.action());
        auditLog.setTargetType(auditOperation.targetType());
        auditLog.setTargetId(stringValue(evaluateExpression(auditOperation.targetId(), evaluationContext)));
        auditLog.setRequestMethod(request == null ? null : request.getMethod());
        auditLog.setRequestPath(request == null ? null : request.getRequestURI());
        auditLog.setRequestIp(resolveRequestIp(request));
        auditLog.setDetail(stringValue(evaluateExpression(auditOperation.detail(), evaluationContext)));
        auditLog.setStatus(failure == null ? "SUCCESS" : "FAILED");
        auditLog.setErrorMessage(failure == null ? null : truncate(failure.getMessage(), MAX_ERROR_MESSAGE_LENGTH));
        auditLog.setOperateTime(LocalDateTime.now());
        return auditLog;
    }

    private StandardEvaluationContext buildEvaluationContext(Method method, Object[] args, Object result) {
        StandardEvaluationContext context = new StandardEvaluationContext();
        String[] parameterNames = parameterNameDiscoverer.getParameterNames(method);
        if (parameterNames != null) {
            for (int i = 0; i < parameterNames.length && i < args.length; i++) {
                context.setVariable(parameterNames[i], args[i]);
            }
        }
        context.setVariable("args", args);
        context.setVariable("result", result);
        return context;
    }

    private Object evaluateExpression(String expression, StandardEvaluationContext context) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        try {
            return expressionParser.parseExpression(expression).getValue(context);
        } catch (Exception ex) {
            log.warn("Failed to evaluate audit expression: {}", expression, ex);
            return null;
        }
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) {
            return String.valueOf(value);
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return String.valueOf(value);
        }
    }

    private String joinRoles(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return null;
        }
        return String.join(",", roles);
    }

    private HttpServletRequest currentRequest() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attributes == null ? null : attributes.getRequest();
    }

    private String resolveRequestIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            for (String part : forwardedFor.split(",")) {
                String candidate = part == null ? "" : part.trim();
                if (!candidate.isEmpty()) {
                    return candidate;
                }
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
