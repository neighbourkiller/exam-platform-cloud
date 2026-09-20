package com.ekusys.exam.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ekusys.exam.auth.config.AuthCookieProperties;
import com.ekusys.exam.auth.controller.AuthController;
import com.ekusys.exam.auth.dto.AuthTokens;
import com.ekusys.exam.auth.dto.MeResponse;
import com.ekusys.exam.auth.service.AuthCookieService;
import com.ekusys.exam.auth.service.AuthService;
import com.ekusys.exam.common.exception.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private AuthService authService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AuthCookieProperties properties = new AuthCookieProperties();
        AuthCookieService authCookieService = new AuthCookieService(properties);
        AuthController controller = new AuthController(authService, authCookieService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void loginShouldReturnAuthPayloadAndSetRefreshCookie() throws Exception {
        when(authService.login(any())).thenReturn(AuthTokens.builder()
            .accessToken("access-token")
            .refreshToken("refresh-token")
            .tokenType("Bearer")
            .roles(List.of("STUDENT"))
            .build());

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of(
                    "username", "alice",
                    "password", "secret"
                ))))
            .andExpect(status().isOk())
            .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("exam_refresh_token=refresh-token")))
            .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("HttpOnly")))
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.accessToken").value("access-token"))
            .andExpect(jsonPath("$.data.refreshToken").doesNotExist());
    }

    @Test
    void refreshShouldReadCookieAndRotateRefreshCookie() throws Exception {
        when(authService.refresh("refresh-token")).thenReturn(AuthTokens.builder()
            .accessToken("new-access-token")
            .refreshToken("new-refresh-token")
            .tokenType("Bearer")
            .roles(List.of("TEACHER"))
            .build());

        mockMvc.perform(post("/api/v1/auth/refresh")
                .cookie(new jakarta.servlet.http.Cookie("exam_refresh_token", "refresh-token")))
            .andExpect(status().isOk())
            .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("exam_refresh_token=new-refresh-token")))
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.accessToken").value("new-access-token"))
            .andExpect(jsonPath("$.data.refreshToken").doesNotExist());
    }

    @Test
    void refreshShouldFailWhenCookieMissing() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.message").value("缺少刷新令牌"));
    }

    @Test
    void logoutShouldRevokeCookieAndSession() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                .cookie(new jakarta.servlet.http.Cookie("exam_refresh_token", "refresh-token")))
            .andExpect(status().isOk())
            .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")))
            .andExpect(jsonPath("$.success").value(true));

        verify(authService).logout("refresh-token");
    }

    @Test
    void meShouldReturnCurrentUserProfile() throws Exception {
        when(authService.me()).thenReturn(MeResponse.builder()
            .userId(1001L)
            .username("alice")
            .realName("Alice")
            .studentNo("S2026001")
            .enrollmentYear("2026")
            .roles(List.of("STUDENT"))
            .build());

        mockMvc.perform(get("/api/v1/auth/me"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.userId").value(1001))
            .andExpect(jsonPath("$.data.username").value("alice"))
            .andExpect(jsonPath("$.data.realName").value("Alice"))
            .andExpect(jsonPath("$.data.studentNo").value("S2026001"))
            .andExpect(jsonPath("$.data.enrollmentYear").value("2026"));
    }

    @Test
    void changePasswordShouldClearRefreshCookie() throws Exception {
        mockMvc.perform(post("/api/v1/auth/change-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of(
                    "oldPassword", "old-password",
                    "newPassword", "new-password"
                ))))
            .andExpect(status().isOk())
            .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")))
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.message").value("密码修改成功，请重新登录"));

        verify(authService).changePassword(any());
    }
}
