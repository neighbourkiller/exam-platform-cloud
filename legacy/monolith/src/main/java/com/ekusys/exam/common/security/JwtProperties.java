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

    @NotBlank
    private String secret;

    private long accessTokenExpireMinutes;
    private long refreshTokenExpireDays;
}
