package com.ekusys.exam.auth.dto;

import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AuthResponse {

    private String accessToken;
    private String tokenType;
    private List<String> roles;

    public static AuthResponse from(AuthTokens tokens) {
        return AuthResponse.builder()
            .accessToken(tokens.getAccessToken())
            .tokenType(tokens.getTokenType())
            .roles(tokens.getRoles())
            .build();
    }
}
