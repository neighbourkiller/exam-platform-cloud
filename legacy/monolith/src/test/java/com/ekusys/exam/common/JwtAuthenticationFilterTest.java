package com.ekusys.exam.common;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ekusys.exam.common.security.JwtAuthenticationFilter;
import com.ekusys.exam.common.security.JwtTokenProvider;
import com.ekusys.exam.common.security.LoginUser;
import com.ekusys.exam.repository.entity.User;
import com.ekusys.exam.repository.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private UserMapper userMapper;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldIgnoreRefreshTokenInAuthorizationHeader() throws Exception {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtTokenProvider, userMapper);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer refresh-token");

        when(jwtTokenProvider.isAccessToken("refresh-token")).thenReturn(false);

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void shouldAuthenticateAccessToken() throws Exception {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtTokenProvider, userMapper);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer access-token");
        LoginUser loginUser = LoginUser.builder()
            .userId(1001L)
            .username("alice")
            .enabled(true)
            .roles(java.util.List.of("STUDENT"))
            .tokenVersion(2L)
            .build();

        when(jwtTokenProvider.isAccessToken("access-token")).thenReturn(true);
        when(jwtTokenProvider.parseLoginUser("access-token")).thenReturn(loginUser);
        when(userMapper.selectById(1001L)).thenReturn(user(1001L, true, 2L));

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        verify(jwtTokenProvider).parseLoginUser("access-token");
    }

    @Test
    void shouldRejectStaleAccessTokenVersion() throws Exception {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtTokenProvider, userMapper);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer access-token");
        LoginUser loginUser = LoginUser.builder()
            .userId(1001L)
            .username("alice")
            .enabled(true)
            .roles(java.util.List.of("STUDENT"))
            .tokenVersion(1L)
            .build();

        when(jwtTokenProvider.isAccessToken("access-token")).thenReturn(true);
        when(jwtTokenProvider.parseLoginUser("access-token")).thenReturn(loginUser);
        when(userMapper.selectById(1001L)).thenReturn(user(1001L, true, 2L));

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    private User user(Long id, boolean enabled, Long tokenVersion) {
        User user = new User();
        user.setId(id);
        user.setEnabled(enabled);
        user.setTokenVersion(tokenVersion);
        return user;
    }
}
