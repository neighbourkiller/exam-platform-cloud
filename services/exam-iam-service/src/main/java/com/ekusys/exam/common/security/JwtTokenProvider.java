package com.ekusys.exam.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import jakarta.annotation.PostConstruct;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class JwtTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);

    private final JwtProperties properties;

    public JwtTokenProvider(JwtProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void validateSecret() {
        privateKey();
        publicKey();
        log.info("JWT RSA key pair validation passed");
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
            .signWith(privateKey(), Jwts.SIG.RS256)
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
            .signWith(privateKey(), Jwts.SIG.RS256)
            .compact();
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
            .verifyWith(publicKey())
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

    public String createServiceToken(String serviceId, List<String> scopes, long expiresInSeconds) {
        Instant now = Instant.now();
        return Jwts.builder()
            .issuer(properties.getIssuer())
            .subject(serviceId)
            .claim("roles", List.of("SERVICE"))
            .claim("scope", String.join(" ", scopes))
            .claim("typ", "service")
            .id(UUID.randomUUID().toString())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(expiresInSeconds)))
            .signWith(privateKey(), Jwts.SIG.RS256)
            .compact();
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

    private PrivateKey privateKey() {
        try {
            byte[] bytes = Base64.getDecoder().decode(normalizePem(
                resolvePem(properties.getPrivateKey(), properties.getPrivateKeyLocation()), "PRIVATE KEY"));
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException("Invalid JWT RSA private key", ex);
        }
    }

    private PublicKey publicKey() {
        try {
            byte[] bytes = Base64.getDecoder().decode(normalizePem(
                resolvePem(properties.getPublicKey(), properties.getPublicKeyLocation()), "PUBLIC KEY"));
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException("Invalid JWT RSA public key", ex);
        }
    }

    private String normalizePem(String value, String type) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("JWT " + type + " is not configured");
        }
        return value.replace("-----BEGIN " + type + "-----", "")
            .replace("-----END " + type + "-----", "")
            .replaceAll("\\s", "");
    }

    private String resolvePem(String value, String location) {
        if (value != null && !value.isBlank()) {
            return value;
        }
        if (location == null || location.isBlank()) {
            return value;
        }
        try {
            String path = location.startsWith("file:") ? location.substring(5) : location;
            return Files.readString(Path.of(path));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to read JWT key file", exception);
        }
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
