package com.ekusys.exam.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ekusys.exam.auth.dto.AuthTokens;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.common.security.JwtTokenProvider;
import com.ekusys.exam.common.security.LoginUser;
import com.ekusys.exam.repository.entity.User;
import com.ekusys.exam.repository.mapper.UserMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private UserMapper userMapper;

    @Mock
    private RefreshTokenSessionService refreshTokenSessionService;

    @Mock
    private PasswordEncoder passwordEncoder;

    private AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(
            authenticationManager,
            jwtTokenProvider,
            userMapper,
            refreshTokenSessionService,
            passwordEncoder
        );
    }

    @Test
    void refreshRotatesCurrentTokenAtomically() {
        Instant expiresAt = Instant.now().plusSeconds(600);
        LoginUser tokenUser = LoginUser.builder().userId(7L).username("student").build();
        User user = enabledUser();
        when(jwtTokenProvider.isRefreshToken("refresh-token")).thenReturn(true);
        when(jwtTokenProvider.parseLoginUser("refresh-token")).thenReturn(tokenUser);
        when(jwtTokenProvider.getTokenId("refresh-token")).thenReturn("old-jti");
        when(userMapper.selectById(7L)).thenReturn(user);
        when(userMapper.selectRoleCodes(7L)).thenReturn(List.of("STUDENT"));
        when(jwtTokenProvider.createAccessToken(org.mockito.ArgumentMatchers.any(LoginUser.class)))
            .thenReturn("new-access-token");
        when(jwtTokenProvider.createRefreshToken(
            org.mockito.ArgumentMatchers.any(LoginUser.class),
            org.mockito.ArgumentMatchers.anyString()
        )).thenReturn("new-refresh-token");
        when(jwtTokenProvider.getExpiration("new-refresh-token")).thenReturn(expiresAt);
        when(refreshTokenSessionService.rotate(
            org.mockito.ArgumentMatchers.eq(7L),
            org.mockito.ArgumentMatchers.eq("old-jti"),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq(expiresAt)
        )).thenReturn(true);

        AuthTokens tokens = service.refresh("refresh-token");

        assertThat(tokens.getAccessToken()).isEqualTo("new-access-token");
        assertThat(tokens.getRefreshToken()).isEqualTo("new-refresh-token");
        verify(refreshTokenSessionService).rotate(
            org.mockito.ArgumentMatchers.eq(7L),
            org.mockito.ArgumentMatchers.eq("old-jti"),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq(expiresAt)
        );
    }

    @Test
    void refreshRejectsTokenAlreadyConsumedByAnotherRequest() {
        Instant expiresAt = Instant.now().plusSeconds(600);
        LoginUser tokenUser = LoginUser.builder().userId(7L).username("student").build();
        when(jwtTokenProvider.isRefreshToken("refresh-token")).thenReturn(true);
        when(jwtTokenProvider.parseLoginUser("refresh-token")).thenReturn(tokenUser);
        when(jwtTokenProvider.getTokenId("refresh-token")).thenReturn("old-jti");
        when(userMapper.selectById(7L)).thenReturn(enabledUser());
        when(userMapper.selectRoleCodes(7L)).thenReturn(List.of("STUDENT"));
        when(jwtTokenProvider.createAccessToken(org.mockito.ArgumentMatchers.any(LoginUser.class)))
            .thenReturn("new-access-token");
        when(jwtTokenProvider.createRefreshToken(
            org.mockito.ArgumentMatchers.any(LoginUser.class),
            org.mockito.ArgumentMatchers.anyString()
        )).thenReturn("new-refresh-token");
        when(jwtTokenProvider.getExpiration("new-refresh-token")).thenReturn(expiresAt);
        when(refreshTokenSessionService.rotate(
            org.mockito.ArgumentMatchers.eq(7L),
            org.mockito.ArgumentMatchers.eq("old-jti"),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq(expiresAt)
        )).thenReturn(false);

        assertThatThrownBy(() -> service.refresh("refresh-token"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("刷新令牌已失效，请重新登录");
    }

    @Test
    void logoutRevokesOnlyPresentedToken() {
        LoginUser tokenUser = LoginUser.builder().userId(7L).username("student").build();
        when(jwtTokenProvider.isRefreshToken("refresh-token")).thenReturn(true);
        when(jwtTokenProvider.parseLoginUser("refresh-token")).thenReturn(tokenUser);
        when(jwtTokenProvider.getTokenId("refresh-token")).thenReturn("current-jti");

        service.logout("refresh-token");

        verify(refreshTokenSessionService).revoke(7L, "current-jti");
    }

    private User enabledUser() {
        User user = new User();
        user.setId(7L);
        user.setUsername("student");
        user.setEnabled(true);
        user.setTokenVersion(3L);
        return user;
    }
}
