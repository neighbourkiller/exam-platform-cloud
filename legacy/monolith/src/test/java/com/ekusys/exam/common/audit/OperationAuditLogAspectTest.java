package com.ekusys.exam.common.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ekusys.exam.admin.dto.ResetPasswordRequest;
import com.ekusys.exam.admin.dto.UserCreateRequest;
import com.ekusys.exam.common.api.ApiResponse;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.common.security.SecurityUtils;
import com.ekusys.exam.repository.entity.OperationAuditLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.util.List;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@ExtendWith(MockitoExtension.class)
class OperationAuditLogAspectTest {

    @Mock
    private OperationAuditLogService operationAuditLogService;

    private OperationAuditLogAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new OperationAuditLogAspect(operationAuditLogService, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void shouldRecordSuccessfulOperation() throws Throwable {
        Method method = AuditStubController.class.getDeclaredMethod("createUser", UserCreateRequest.class);
        ProceedingJoinPoint joinPoint = mockJoinPoint(method, new Object[]{buildCreateRequest("alice")}, ApiResponse.ok("创建成功", 1001L), null);
        bindRequest("POST", "/api/v1/admin/users", "10.0.0.8");

        try (MockedStatic<SecurityUtils> mocked = org.mockito.Mockito.mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getCurrentUserId).thenReturn(99L);
            mocked.when(SecurityUtils::getCurrentUsername).thenReturn("admin");
            mocked.when(SecurityUtils::getCurrentRoles).thenReturn(List.of("ADMIN"));

            aspect.around(joinPoint, method.getAnnotation(AuditOperation.class));
        }

        ArgumentCaptor<OperationAuditLog> captor = ArgumentCaptor.forClass(OperationAuditLog.class);
        verify(operationAuditLogService).record(captor.capture());
        OperationAuditLog auditLog = captor.getValue();
        assertEquals("USER_CREATE", auditLog.getAction());
        assertEquals("USER", auditLog.getTargetType());
        assertEquals("1001", auditLog.getTargetId());
        assertEquals("alice", auditLog.getDetail());
        assertEquals("SUCCESS", auditLog.getStatus());
        assertEquals("admin", auditLog.getOperatorUsername());
        assertEquals("ADMIN", auditLog.getOperatorRoles());
        assertEquals("/api/v1/admin/users", auditLog.getRequestPath());
        assertEquals("10.0.0.8", auditLog.getRequestIp());
    }

    @Test
    void shouldRecordFailedOperationWithoutLeakingPassword() throws Throwable {
        Method method = AuditStubController.class.getDeclaredMethod("resetPassword", Long.class, ResetPasswordRequest.class);
        ResetPasswordRequest request = new ResetPasswordRequest();
        request.setPassword("super-secret");
        ProceedingJoinPoint joinPoint = mockJoinPoint(method, new Object[]{88L, request}, null, new BusinessException("用户不存在"));
        bindRequest("POST", "/api/v1/admin/users/88/reset-password", "127.0.0.1");

        try (MockedStatic<SecurityUtils> mocked = org.mockito.Mockito.mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getCurrentUserId).thenReturn(99L);
            mocked.when(SecurityUtils::getCurrentUsername).thenReturn("admin");
            mocked.when(SecurityUtils::getCurrentRoles).thenReturn(List.of("ADMIN"));

            BusinessException ex = assertThrows(BusinessException.class,
                () -> aspect.around(joinPoint, method.getAnnotation(AuditOperation.class)));
            assertEquals("用户不存在", ex.getMessage());
        }

        ArgumentCaptor<OperationAuditLog> captor = ArgumentCaptor.forClass(OperationAuditLog.class);
        verify(operationAuditLogService).record(captor.capture());
        OperationAuditLog auditLog = captor.getValue();
        assertEquals("USER_RESET_PASSWORD", auditLog.getAction());
        assertEquals("88", auditLog.getTargetId());
        assertEquals("reset-password", auditLog.getDetail());
        assertEquals("FAILED", auditLog.getStatus());
        assertEquals("用户不存在", auditLog.getErrorMessage());
    }

    private ProceedingJoinPoint mockJoinPoint(Method method, Object[] args, Object result, Throwable throwable) throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(args);
        if (throwable == null) {
            when(joinPoint.proceed()).thenReturn(result);
        } else {
            when(joinPoint.proceed()).thenThrow(throwable);
        }
        return joinPoint;
    }

    private void bindRequest(String method, String uri, String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.addHeader("X-Forwarded-For", ip);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private UserCreateRequest buildCreateRequest(String username) {
        UserCreateRequest request = new UserCreateRequest();
        request.setUsername(username);
        request.setRealName("Alice");
        request.setRoleIds(List.of(1L));
        return request;
    }

    static class AuditStubController {

        @AuditOperation(action = "USER_CREATE", targetType = "USER", targetId = "#result.data", detail = "#request.username")
        public ApiResponse<Long> createUser(UserCreateRequest request) {
            return ApiResponse.ok(1L);
        }

        @AuditOperation(action = "USER_RESET_PASSWORD", targetType = "USER", targetId = "#userId", detail = "'reset-password'")
        public ApiResponse<Void> resetPassword(Long userId, ResetPasswordRequest request) {
            return ApiResponse.ok(null);
        }
    }
}