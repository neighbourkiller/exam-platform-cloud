package com.ekusys.exam.iam.api;

public record ServiceTokenResponse(String accessToken, long expiresInSeconds) {
}
