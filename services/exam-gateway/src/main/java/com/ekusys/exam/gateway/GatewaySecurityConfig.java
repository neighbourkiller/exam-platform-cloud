package com.ekusys.exam.gateway;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
public class GatewaySecurityConfig {
    @Bean
    ReactiveJwtDecoder gatewayJwtDecoder(
        @Value("${exam.security.jwt.issuer:exam-system}") String issuer,
        @Value("${exam.security.jwt.public-key:}") String inlineKey,
        @Value("${exam.security.jwt.public-key-location:}") String keyLocation
    ) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withPublicKey(readPublicKey(inlineKey, keyLocation)).build();
        OAuth2TokenValidator<Jwt> typeValidator = jwt -> "access".equals(jwt.getClaimAsString("typ"))
            ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Unsupported token type", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer), typeValidator));
        return decoder;
    }

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .authorizeExchange(exchanges -> exchanges
                .pathMatchers("/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout",
                    "/api/v1/auth/jwks", "/api/v1/health/**", "/actuator/health").permitAll()
                .anyExchange().authenticated())
            .oauth2ResourceServer(resourceServer -> resourceServer.jwt(jwt -> { }))
            .build();
    }

    private RSAPublicKey readPublicKey(String inlineKey, String keyLocation) {
        try {
            String pem = inlineKey;
            if ((pem == null || pem.isBlank()) && keyLocation != null && !keyLocation.isBlank()) {
                String path = keyLocation.startsWith("file:") ? keyLocation.substring(5) : keyLocation;
                pem = Files.readString(Path.of(path));
            }
            if (pem == null || pem.isBlank()) throw new IllegalStateException("JWT public key is not configured");
            String encoded = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid JWT RSA public key", exception);
        }
    }
}
