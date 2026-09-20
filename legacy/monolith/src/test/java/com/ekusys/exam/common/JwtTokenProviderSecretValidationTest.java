package com.ekusys.exam.common;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ekusys.exam.common.security.JwtProperties;
import com.ekusys.exam.common.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;

class JwtTokenProviderSecretValidationTest {

    @Test
    void shouldRejectBlankSecret() {
        JwtTokenProvider provider = new JwtTokenProvider(jwtProperties(""));

        assertThrows(IllegalStateException.class, provider::validateSecret);
    }

    @Test
    void shouldRejectShortSecret() {
        JwtTokenProvider provider = new JwtTokenProvider(jwtProperties("short-secret"));

        assertThrows(IllegalStateException.class, provider::validateSecret);
    }

    @Test
    void shouldAcceptSecretWithAtLeast32Bytes() {
        JwtTokenProvider provider = new JwtTokenProvider(jwtProperties("dev-only-exam-secret-key-32bytes!"));

        assertDoesNotThrow(provider::validateSecret);
    }

    private JwtProperties jwtProperties(String secret) {
        JwtProperties properties = new JwtProperties();
        properties.setIssuer("exam-system");
        properties.setSecret(secret);
        properties.setAccessTokenExpireMinutes(120);
        properties.setRefreshTokenExpireDays(7);
        return properties;
    }
}
