package com.ekusys.exam.auth.controller;

import com.ekusys.exam.auth.dto.AuthResponse;
import com.ekusys.exam.auth.dto.AuthTokens;
import com.ekusys.exam.auth.dto.ChangePasswordRequest;
import com.ekusys.exam.auth.dto.LoginRequest;
import com.ekusys.exam.auth.dto.MeResponse;
import com.ekusys.exam.auth.service.AuthCookieService;
import com.ekusys.exam.auth.service.AuthService;
import com.ekusys.exam.common.api.ApiResponse;
import com.ekusys.exam.common.exception.BusinessException;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final AuthCookieService authCookieService;

    public AuthController(AuthService authService, AuthCookieService authCookieService) {
        this.authService = authService;
        this.authCookieService = authCookieService;
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        AuthTokens tokens = authService.login(request);
        authCookieService.writeRefreshToken(response, tokens.getRefreshToken());
        return ApiResponse.ok(AuthResponse.from(tokens));
    }

    @PostMapping("/refresh")
    public ApiResponse<AuthResponse> refresh(HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = authCookieService.extractRefreshToken(request)
            .orElseThrow(() -> new BusinessException("缺少刷新令牌"));
        try {
            AuthTokens tokens = authService.refresh(refreshToken);
            authCookieService.writeRefreshToken(response, tokens.getRefreshToken());
            return ApiResponse.ok(AuthResponse.from(tokens));
        } catch (BusinessException ex) {
            authCookieService.clearRefreshToken(response);
            throw ex;
        }
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        authCookieService.extractRefreshToken(request).ifPresent(authService::logout);
        authCookieService.clearRefreshToken(response);
        return ApiResponse.ok("退出成功", null);
    }

    @GetMapping("/me")
    public ApiResponse<MeResponse> me() {
        return ApiResponse.ok(authService.me());
    }

    @PostMapping("/change-password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                            HttpServletResponse response) {
        authService.changePassword(request);
        authCookieService.clearRefreshToken(response);
        return ApiResponse.ok("密码修改成功，请重新登录", null);
    }
}
