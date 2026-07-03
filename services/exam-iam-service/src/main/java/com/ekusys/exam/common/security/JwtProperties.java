package com.ekusys.exam.common.security;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    @NotBlank
    private String issuer;

    private String privateKey;

    private String publicKey;

    private String privateKeyLocation;
    private String publicKeyLocation;

    private long accessTokenExpireMinutes;
    private long refreshTokenExpireDays;
}
