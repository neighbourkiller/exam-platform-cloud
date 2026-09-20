package com.ekusys.exam.auth.dto;

import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AuthTokens {

    private String accessToken;
    private String refreshToken;
    private String tokenType;
    private List<String> roles;
}
