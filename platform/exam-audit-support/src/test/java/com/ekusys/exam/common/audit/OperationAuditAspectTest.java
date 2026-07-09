package com.ekusys.exam.common.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.ekusys.exam.common.api.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class OperationAuditAspectTest {
    @AfterEach
    void cleanup() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    @Test
    void recordsSuccessfulOperationWithJwtAndSpelValues() {
        AuditOutboxService outbox = mock(AuditOutboxService.class);
        AuditedTarget target = proxy(outbox);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/items");
        request.addHeader("X-Forwarded-For", "10.0.0.8, 10.0.0.9");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        Jwt jwt = new Jwt("token", Instant.now(), Instant.now().plusSeconds(60),
            java.util.Map.of("alg", "none"), java.util.Map.of("sub", "admin", "uid", 7L));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
            jwt, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")), "admin"));

        target.create("paper");

        ArgumentCaptor<AuditEventData> data = ArgumentCaptor.forClass(AuditEventData.class);
        verify(outbox).record(data.capture(), isNull());
        assertThat(data.getValue().operatorId()).isEqualTo(7L);
        assertThat(data.getValue().operatorUsername()).isEqualTo("admin");
        assertThat(data.getValue().operatorRoles()).isEqualTo("ADMIN");
        assertThat(data.getValue().targetId()).isEqualTo("42");
        assertThat(data.getValue().detail()).isEqualTo("paper");
        assertThat(data.getValue().requestIp()).isEqualTo("10.0.0.8");
        assertThat(data.getValue().status()).isEqualTo("SUCCESS");
    }

    @Test
    void recordsFailureAndRethrowsOriginalException() {
        AuditOutboxService outbox = mock(AuditOutboxService.class);
        AuditedTarget target = proxy(outbox);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        assertThatThrownBy(target::fail).isInstanceOf(IllegalStateException.class).hasMessage("boom");

        ArgumentCaptor<AuditEventData> data = ArgumentCaptor.forClass(AuditEventData.class);
        verify(outbox).record(data.capture(), any());
        assertThat(data.getValue().status()).isEqualTo("FAILED");
        assertThat(data.getValue().errorMessage()).isEqualTo("boom");
    }

    private AuditedTarget proxy(AuditOutboxService outbox) {
        AspectJProxyFactory factory = new AspectJProxyFactory(new AuditedTarget());
        factory.addAspect(new OperationAuditAspect(outbox, new ObjectMapper()));
        return factory.getProxy();
    }

    static class AuditedTarget {
        @AuditOperation(action = "ITEM_CREATE", targetType = "ITEM", targetId = "#result.data", detail = "#name")
        public ApiResponse<Long> create(String name) {
            return ApiResponse.ok(42L);
        }

        @AuditOperation(action = "ITEM_FAIL", targetType = "ITEM")
        public void fail() {
            throw new IllegalStateException("boom");
        }
    }
}
