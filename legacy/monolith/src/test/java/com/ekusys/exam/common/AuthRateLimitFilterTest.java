package com.ekusys.exam.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ekusys.exam.auth.config.AuthRateLimitProperties;
import com.ekusys.exam.common.security.AuthRateLimitFilter;
import com.ekusys.exam.common.security.AuthRateLimitService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
class AuthRateLimitFilterTest {

    @Mock
    private AuthRateLimitService authRateLimitService;

    @Test
    void shouldReturn429WhenLoginRateLimited() throws Exception {
        AuthRateLimitProperties properties = new AuthRateLimitProperties();
        AuthRateLimitFilter filter = new AuthRateLimitFilter(authRateLimitService, properties, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setServletPath("/api/v1/auth/login");
        request.addHeader("X-Forwarded-For", "203.0.113.5, 10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(authRateLimitService.isAllowed("login", "203.0.113.5", properties.getLoginLimit())).thenReturn(false);

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(429, response.getStatus());
        assertTrue(response.getContentAsString().contains("RATE_LIMITED"));
        verify(authRateLimitService).isAllowed("login", "203.0.113.5", properties.getLoginLimit());
    }

    @Test
    void shouldAllowRefreshWhenWithinLimit() throws Exception {
        AuthRateLimitProperties properties = new AuthRateLimitProperties();
        AuthRateLimitFilter filter = new AuthRateLimitFilter(authRateLimitService, properties, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/refresh");
        request.setServletPath("/api/v1/auth/refresh");
        request.addHeader("X-Real-IP", "198.51.100.9");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        when(authRateLimitService.isAllowed("refresh", "198.51.100.9", properties.getRefreshLimit())).thenReturn(true);

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        verify(authRateLimitService).isAllowed("refresh", "198.51.100.9", properties.getRefreshLimit());
    }
}
