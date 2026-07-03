package com.ekusys.exam.common.security;

import com.ekusys.exam.auth.config.AuthRateLimitProperties;
import com.ekusys.exam.common.api.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final String LOGIN_PATH = "/api/v1/auth/login";
    private static final String REFRESH_PATH = "/api/v1/auth/refresh";

    private final AuthRateLimitService authRateLimitService;
    private final AuthRateLimitProperties properties;
    private final ObjectMapper objectMapper;

    public AuthRateLimitFilter(AuthRateLimitService authRateLimitService,
                               AuthRateLimitProperties properties,
                               ObjectMapper objectMapper) {
        this.authRateLimitService = authRateLimitService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        String action = resolveAction(request);
        if (action == null) {
            filterChain.doFilter(request, response);
            return;
        }

        long limit = "login".equals(action) ? properties.getLoginLimit() : properties.getRefreshLimit();
        String clientIp = extractClientIp(request);
        if (authRateLimitService.isAllowed(action, clientIp, limit)) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ApiResponse.fail("RATE_LIMITED", "请求过于频繁，请稍后再试"));
    }

    private String resolveAction(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return null;
        }
        String path = request.getServletPath();
        if (LOGIN_PATH.equals(path)) {
            return "login";
        }
        if (REFRESH_PATH.equals(path)) {
            return "refresh";
        }
        return null;
    }

    private String extractClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            String[] parts = forwardedFor.split(",");
            for (String part : parts) {
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
}
