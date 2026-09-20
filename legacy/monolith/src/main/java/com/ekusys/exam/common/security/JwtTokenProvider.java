package com.ekusys.exam.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class JwtTokenProvider {

    private static final int MIN_SECRET_LENGTH = 32;
    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);

    private final JwtProperties properties;

    public JwtTokenProvider(JwtProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void validateSecret() {
        String secret = properties.getSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                "JWT secret 未配置。请设置环境变量 JWT_SECRET（至少 " + MIN_SECRET_LENGTH + " 个字符）");
        }
        int secretLength = secret.getBytes(StandardCharsets.UTF_8).length;
        if (secretLength < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                "JWT secret 长度不足 " + MIN_SECRET_LENGTH + " 字节，存在安全风险。请设置一个更长的 JWT_SECRET 环境变量");
        }
        log.info("JWT secret 校验通过 (长度={}字节)", secretLength);
    }

    public String createAccessToken(LoginUser user) {
        Instant now = Instant.now();
        Instant expires = now.plus(properties.getAccessTokenExpireMinutes(), ChronoUnit.MINUTES);
        return Jwts.builder()
            .issuer(properties.getIssuer())
            .subject(user.getUsername())
            .claim("uid", user.getUserId())
            .claim("roles", user.getRoles())
            .claim("typ", "access")
            .claim("tokenVersion", tokenVersion(user))
            .id(UUID.randomUUID().toString())
            .issuedAt(Date.from(now))
            .expiration(Date.from(expires))
            .signWith(getSignKey(), SignatureAlgorithm.HS256)
            .compact();
    }

    public String createRefreshToken(LoginUser user, String tokenId) {
        Instant now = Instant.now();
        Instant expires = now.plus(properties.getRefreshTokenExpireDays(), ChronoUnit.DAYS);
        return Jwts.builder()
            .issuer(properties.getIssuer())
            .subject(user.getUsername())
            .claim("uid", user.getUserId())
            .claim("roles", user.getRoles())
            .claim("typ", "refresh")
            .claim("tokenVersion", tokenVersion(user))
            .id(tokenId)
            .issuedAt(Date.from(now))
            .expiration(Date.from(expires))
            .signWith(getSignKey(), SignatureAlgorithm.HS256)
            .compact();
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
            .verifyWith(getSignKey())
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }

    public LoginUser parseLoginUser(String token) {
        Claims claims = parseClaims(token);
        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) claims.get("roles", List.class);
        return LoginUser.builder()
            .userId(Long.valueOf(claims.get("uid").toString()))
            .username(claims.getSubject())
            .enabled(true)
            .roles(roles)
            .tokenVersion(readTokenVersion(claims))
            .build();
    }

    public boolean isRefreshToken(String token) {
        Claims claims = parseClaims(token);
        return "refresh".equals(claims.get("typ", String.class));
    }

    public boolean isAccessToken(String token) {
        Claims claims = parseClaims(token);
        return "access".equals(claims.get("typ", String.class));
    }

    public String getTokenId(String token) {
        Claims claims = parseClaims(token);
        return claims.getId();
    }

    public Instant getExpiration(String token) {
        Claims claims = parseClaims(token);
        Date expiration = claims.getExpiration();
        return expiration == null ? null : expiration.toInstant();
    }

    private SecretKey getSignKey() {
        byte[] raw = properties.getSecret().getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(raw);
    }

    private long tokenVersion(LoginUser user) {
        return user == null || user.getTokenVersion() == null ? 0L : user.getTokenVersion();
    }

    private Long readTokenVersion(Claims claims) {
        Object value = claims.get("tokenVersion");
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        return Long.valueOf(value.toString());
    }
}
