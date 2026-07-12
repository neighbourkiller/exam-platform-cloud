package com.ekusys.exam.common.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ekusys.exam.common.web.ClientIpUtils;
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
import org.slf4j.MDC;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Aspect
@Component
public class OperationAuditAspect {
    private static final Logger log = LoggerFactory.getLogger(OperationAuditAspect.class);
    private static final int MAX_ERROR_MESSAGE_LENGTH = 500;

    private final AuditOutboxService outboxService;
    private final ObjectMapper objectMapper;
    private final ParameterNameDiscoverer parameterNames = new DefaultParameterNameDiscoverer();
    private final ExpressionParser expressionParser = new SpelExpressionParser();

    public OperationAuditAspect(AuditOutboxService outboxService, ObjectMapper objectMapper) {
        this.outboxService = outboxService;
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
        } catch (Throwable exception) {
            failure = exception;
            throw exception;
        } finally {
            AuditEventData data = buildData(joinPoint, method, request, auditOperation, result, failure);
            try {
                outboxService.record(data, MDC.get("traceId"));
            } catch (Exception exception) {
                log.warn("Failed to append audit event: action={}, targetType={}, targetId={}",
                    data.action(), data.targetType(), data.targetId(), exception);
            }
        }
    }

    private AuditEventData buildData(ProceedingJoinPoint joinPoint,
                                     Method method,
                                     HttpServletRequest request,
                                     AuditOperation operation,
                                     Object result,
                                     Throwable failure) {
        StandardEvaluationContext context = evaluationContext(method, joinPoint.getArgs(), result);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return new AuditEventData(
            resolveUserId(authentication),
            authentication == null ? null : authentication.getName(),
            resolveRoles(authentication),
            operation.action(),
            operation.targetType(),
            stringValue(evaluate(operation.targetId(), context)),
            request == null ? null : request.getMethod(),
            request == null ? null : request.getRequestURI(),
            resolveRequestIp(request),
            stringValue(evaluate(operation.detail(), context)),
            failure == null ? "SUCCESS" : "FAILED",
            failure == null ? null : truncate(failure.getMessage(), MAX_ERROR_MESSAGE_LENGTH),
            LocalDateTime.now()
        );
    }

    private StandardEvaluationContext evaluationContext(Method method, Object[] args, Object result) {
        StandardEvaluationContext context = new StandardEvaluationContext();
        String[] names = parameterNames.getParameterNames(method);
        for (int index = 0; index < args.length; index++) {
            context.setVariable("a" + index, args[index]);
            context.setVariable("p" + index, args[index]);
            if (names != null && index < names.length) {
                context.setVariable(names[index], args[index]);
            }
        }
        context.setVariable("args", args);
        context.setVariable("result", result);
        return context;
    }

    private Object evaluate(String expression, StandardEvaluationContext context) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        try {
            return expressionParser.parseExpression(expression).getValue(context);
        } catch (Exception exception) {
            log.warn("Failed to evaluate audit expression: {}", expression, exception);
            return null;
        }
    }

    private Long resolveUserId(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof Jwt jwt) {
            Number uid = jwt.getClaim("uid");
            return uid == null ? null : uid.longValue();
        }
        try {
            Object value = principal.getClass().getMethod("getUserId").invoke(principal);
            return value instanceof Number number ? number.longValue() : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private String resolveRoles(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        List<String> roles = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .filter(authority -> authority.startsWith("ROLE_"))
            .map(authority -> authority.substring(5))
            .toList();
        return roles.isEmpty() ? null : String.join(",", roles);
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) {
            return String.valueOf(value);
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return String.valueOf(value);
        }
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    private String resolveRequestIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        return ClientIpUtils.normalizeLiteral(request.getHeader(ClientIpUtils.CLIENT_IP_HEADER))
            .orElseGet(request::getRemoteAddr);
    }

    private String truncate(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
