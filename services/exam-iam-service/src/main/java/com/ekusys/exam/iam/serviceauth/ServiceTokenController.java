package com.ekusys.exam.iam.serviceauth;

import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.common.security.JwtTokenProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/service-tokens")
@EnableConfigurationProperties(ServiceAuthProperties.class)
public class ServiceTokenController {
    private final JwtTokenProvider tokenProvider;
    private final ServiceAuthProperties properties;

    public ServiceTokenController(JwtTokenProvider tokenProvider, ServiceAuthProperties properties) {
        this.tokenProvider = tokenProvider;
        this.properties = properties;
    }

    @PostMapping
    public TokenResponse issue(@Valid @RequestBody TokenRequest request) {
        if (!properties.getAllowedClients().contains(request.clientId())
            || !constantTimeEquals(properties.getSharedSecret(), request.clientSecret())) {
            throw new BusinessException("服务身份认证失败");
        }
        String token = tokenProvider.createServiceToken(
            request.clientId(), List.of("internal"), properties.getTokenTtlSeconds());
        return new TokenResponse(token, properties.getTokenTtlSeconds());
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) return false;
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    public record TokenRequest(@NotBlank String clientId, @NotBlank String clientSecret) {
    }

    public record TokenResponse(String accessToken, long expiresIn) {
    }
}
