package com.ekusys.exam.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ekusys.exam.auth.dto.ChangePasswordRequest;
import com.ekusys.exam.auth.dto.AuthTokens;
import com.ekusys.exam.auth.dto.LoginRequest;
import com.ekusys.exam.auth.dto.MeResponse;
import com.ekusys.exam.auth.service.AuthService;
import com.ekusys.exam.auth.service.RefreshTokenSessionService;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.common.security.JwtTokenProvider;
import com.ekusys.exam.common.security.LoginUser;
import com.ekusys.exam.repository.entity.StudentProfile;
import com.ekusys.exam.repository.entity.User;
import com.ekusys.exam.repository.mapper.StudentProfileMapper;
import com.ekusys.exam.repository.mapper.UserMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
    private StudentProfileMapper studentProfileMapper;

    @Mock
    private RefreshTokenSessionService refreshTokenSessionService;

    @Mock
    private PasswordEncoder passwordEncoder;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(authenticationManager, jwtTokenProvider, userMapper, studentProfileMapper, refreshTokenSessionService, passwordEncoder);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void loginShouldStoreRefreshSession() {
        LoginUser loginUser = LoginUser.builder()
            .userId(1001L)
            .username("alice")
            .enabled(true)
            .roles(List.of("STUDENT"))
            .build();
        Authentication authentication = new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities());
        when(authenticationManager.authenticate(any())).thenReturn(authentication);
        when(jwtTokenProvider.createAccessToken(loginUser)).thenReturn("access-token");
        when(jwtTokenProvider.createRefreshToken(eq(loginUser), any())).thenReturn("refresh-token");
        when(jwtTokenProvider.getExpiration("refresh-token")).thenReturn(Instant.parse("2026-04-05T00:00:00Z"));

        LoginRequest request = new LoginRequest();
        request.setUsername("alice");
        request.setPassword("secret");
        AuthTokens response = authService.login(request);

        assertEquals("access-token", response.getAccessToken());
        assertEquals("refresh-token", response.getRefreshToken());
        verify(refreshTokenSessionService).store(eq(1001L), any(), eq(Instant.parse("2026-04-05T00:00:00Z")));
    }

    @Test
    void loginShouldKeepDisabledAccountMessage() {
        LoginRequest request = loginRequest("alice", "secret");
        BusinessException disabled = new BusinessException("账号已被禁用，请联系管理员");
        when(authenticationManager.authenticate(any()))
            .thenThrow(new InternalAuthenticationServiceException(disabled.getMessage(), disabled));

        BusinessException exception = assertThrows(BusinessException.class, () -> authService.login(request));

        assertEquals("账号已被禁用，请联系管理员", exception.getMessage());
    }

    @Test
    void loginShouldHideBadCredentialDetails() {
        LoginRequest request = loginRequest("alice", "bad-password");
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("Bad credentials"));

        BusinessException exception = assertThrows(BusinessException.class, () -> authService.login(request));

        assertEquals("用户名或密码错误", exception.getMessage());
    }

    @Test
    void meShouldReturnStudentProfileFields() {
        LoginUser current = mockCurrentUser();
        User user = user(current.getUserId(), current.getUsername(), "encoded-password");
        user.setRealName("Alice Zhang");
        StudentProfile profile = new StudentProfile();
        profile.setUserId(current.getUserId());
        profile.setStudentNo("S2026001");
        profile.setEnrollmentYear("2026");

        when(userMapper.selectOne(any())).thenReturn(user);
        when(studentProfileMapper.selectOne(any())).thenReturn(profile);

        MeResponse response = authService.me();

        assertEquals("Alice Zhang", response.getRealName());
        assertEquals("alice", response.getUsername());
        assertEquals("S2026001", response.getStudentNo());
        assertEquals("2026", response.getEnrollmentYear());
    }

    @Test
    void refreshShouldRejectInactiveSession() {
        LoginUser tokenUser = LoginUser.builder()
            .userId(1001L)
            .username("alice")
            .roles(List.of("STUDENT"))
            .enabled(true)
            .build();
        when(jwtTokenProvider.isRefreshToken("refresh-token")).thenReturn(true);
        when(jwtTokenProvider.parseLoginUser("refresh-token")).thenReturn(tokenUser);
        when(jwtTokenProvider.getTokenId("refresh-token")).thenReturn("jti-1");
        when(refreshTokenSessionService.isActive(1001L, "jti-1")).thenReturn(false);

        assertThrows(BusinessException.class, () -> authService.refresh("refresh-token"));
    }

    @Test
    void refreshShouldReloadUserAndRotateToken() {
        LoginUser tokenUser = LoginUser.builder()
            .userId(1001L)
            .username("alice")
            .roles(List.of("STUDENT"))
            .enabled(true)
            .build();
        User user = new User();
        user.setId(1001L);
        user.setUsername("alice");
        user.setEnabled(true);

        when(jwtTokenProvider.isRefreshToken("refresh-token")).thenReturn(true);
        when(jwtTokenProvider.parseLoginUser("refresh-token")).thenReturn(tokenUser);
        when(jwtTokenProvider.getTokenId("refresh-token")).thenReturn("jti-1");
        when(refreshTokenSessionService.isActive(1001L, "jti-1")).thenReturn(true);
        when(userMapper.selectById(1001L)).thenReturn(user);
        when(userMapper.selectRoleCodes(1001L)).thenReturn(List.of("STUDENT", "ADMIN"));
        when(jwtTokenProvider.createAccessToken(any(LoginUser.class))).thenReturn("new-access-token");
        when(jwtTokenProvider.createRefreshToken(any(LoginUser.class), any())).thenReturn("new-refresh-token");
        when(jwtTokenProvider.getExpiration("new-refresh-token")).thenReturn(Instant.parse("2026-04-06T00:00:00Z"));

        AuthTokens response = authService.refresh("refresh-token");

        assertEquals("new-access-token", response.getAccessToken());
        assertEquals(List.of("STUDENT", "ADMIN"), response.getRoles());
        verify(refreshTokenSessionService).store(eq(1001L), any(), eq(Instant.parse("2026-04-06T00:00:00Z")));
    }

    @Test
    void logoutShouldRevokeRefreshSessionWhenTokenValid() {
        LoginUser tokenUser = LoginUser.builder()
            .userId(1001L)
            .username("alice")
            .roles(List.of("STUDENT"))
            .enabled(true)
            .build();
        when(jwtTokenProvider.isRefreshToken("refresh-token")).thenReturn(true);
        when(jwtTokenProvider.parseLoginUser("refresh-token")).thenReturn(tokenUser);

        authService.logout("refresh-token");

        verify(refreshTokenSessionService).revoke(1001L);
    }

    @Test
    void logoutShouldIgnoreInvalidRefreshToken() {
        when(jwtTokenProvider.isRefreshToken("refresh-token")).thenReturn(false);

        authService.logout("refresh-token");

        verify(refreshTokenSessionService, never()).revoke(any());
    }

    @Test
    void changePasswordShouldUpdateEncodedPassword() {
        LoginUser current = mockCurrentUser();
        User user = user(current.getUserId(), current.getUsername(), "encoded-old");
        when(userMapper.selectById(current.getUserId())).thenReturn(user);
        when(passwordEncoder.matches("old-password", "encoded-old")).thenReturn(true);
        when(passwordEncoder.encode("new-password")).thenReturn("encoded-new");

        authService.changePassword(changePasswordRequest("old-password", "new-password"));

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(userCaptor.capture());
        assertEquals("encoded-new", userCaptor.getValue().getPassword());
        assertEquals(1L, userCaptor.getValue().getTokenVersion());
    }

    @Test
    void changePasswordShouldRejectWrongOldPassword() {
        LoginUser current = mockCurrentUser();
        User user = user(current.getUserId(), current.getUsername(), "encoded-old");
        when(userMapper.selectById(current.getUserId())).thenReturn(user);
        when(passwordEncoder.matches("bad-password", "encoded-old")).thenReturn(false);

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> authService.changePassword(changePasswordRequest("bad-password", "new-password"))
        );

        assertEquals("旧密码错误", exception.getMessage());
        verify(userMapper, never()).updateById(any(User.class));
        verify(refreshTokenSessionService, never()).revoke(any());
    }

    @Test
    void changePasswordShouldRejectSamePassword() {
        LoginUser current = mockCurrentUser();
        User user = user(current.getUserId(), current.getUsername(), "encoded-old");
        when(userMapper.selectById(current.getUserId())).thenReturn(user);
        when(passwordEncoder.matches("same-password", "encoded-old")).thenReturn(true);

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> authService.changePassword(changePasswordRequest("same-password", "same-password"))
        );

        assertEquals("新密码不能与旧密码相同", exception.getMessage());
        verify(passwordEncoder, never()).encode(any());
        verify(userMapper, never()).updateById(any(User.class));
        verify(refreshTokenSessionService, never()).revoke(any());
    }

    @Test
    void changePasswordShouldRevokeRefreshTokensAfterUpdate() {
        LoginUser current = mockCurrentUser();
        User user = user(current.getUserId(), current.getUsername(), "encoded-old");
        when(userMapper.selectById(current.getUserId())).thenReturn(user);
        when(passwordEncoder.matches("old-password", "encoded-old")).thenReturn(true);
        when(passwordEncoder.encode("new-password")).thenReturn("encoded-new");

        authService.changePassword(changePasswordRequest("old-password", "new-password"));

        verify(refreshTokenSessionService).revoke(current.getUserId());
    }

    private LoginUser mockCurrentUser() {
        LoginUser loginUser = LoginUser.builder()
            .userId(1001L)
            .username("alice")
            .roles(List.of("STUDENT"))
            .enabled(true)
            .build();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities())
        );
        return loginUser;
    }

    private User user(Long id, String username, String password) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setPassword(password);
        user.setEnabled(true);
        user.setTokenVersion(0L);
        return user;
    }

    private ChangePasswordRequest changePasswordRequest(String oldPassword, String newPassword) {
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setOldPassword(oldPassword);
        request.setNewPassword(newPassword);
        return request;
    }

    private LoginRequest loginRequest(String username, String password) {
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword(password);
        return request;
    }
}
