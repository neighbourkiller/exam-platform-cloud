package com.ekusys.exam.common.security;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ekusys.exam.auth.config.AuthRateLimitProperties;
import com.ekusys.exam.common.web.ClientIpUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthRateLimitFilterTest {

    @Test
    void usesCanonicalGatewayClientIp() throws Exception {
        AuthRateLimitService limiter = mock(AuthRateLimitService.class);
        AuthRateLimitProperties properties = new AuthRateLimitProperties();
        when(limiter.isAllowed("login", "198.51.100.7", properties.getLoginLimit())).thenReturn(true);
        AuthRateLimitFilter filter = new AuthRateLimitFilter(limiter, properties, new ObjectMapper());
        MockHttpServletRequest request = loginRequest("10.0.0.5");
        request.addHeader(ClientIpUtils.CLIENT_IP_HEADER, "198.51.100.7");
        request.addHeader("X-Forwarded-For", "203.0.113.99");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        verify(limiter).isAllowed("login", "198.51.100.7", properties.getLoginLimit());
    }

    @Test
    void ignoresInvalidCanonicalAndRawForwardedHeaders() throws Exception {
        AuthRateLimitService limiter = mock(AuthRateLimitService.class);
        AuthRateLimitProperties properties = new AuthRateLimitProperties();
        when(limiter.isAllowed("login", "10.0.0.5", properties.getLoginLimit())).thenReturn(true);
        AuthRateLimitFilter filter = new AuthRateLimitFilter(limiter, properties, new ObjectMapper());
        MockHttpServletRequest request = loginRequest("10.0.0.5");
        request.addHeader(ClientIpUtils.CLIENT_IP_HEADER, "198.51.100.7:1234");
        request.addHeader("X-Forwarded-For", "203.0.113.99");
        request.addHeader("X-Real-IP", "203.0.113.98");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        verify(limiter).isAllowed("login", "10.0.0.5", properties.getLoginLimit());
    }

    private MockHttpServletRequest loginRequest(String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setServletPath("/api/v1/auth/login");
        request.setRemoteAddr(remoteAddress);
        return request;
    }
}
