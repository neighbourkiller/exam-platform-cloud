package com.ekusys.exam.common.security;

import com.ekusys.exam.repository.entity.User;
import com.ekusys.exam.repository.mapper.UserMapper;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;
    private final UserMapper userMapper;

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider, UserMapper userMapper) {
        this.tokenProvider = tokenProvider;
        this.userMapper = userMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (auth != null && auth.startsWith("Bearer ")) {
            String token = auth.substring(7);
            try {
                if (!tokenProvider.isAccessToken(token)) {
                    SecurityContextHolder.clearContext();
                    filterChain.doFilter(request, response);
                    return;
                }
                LoginUser user = tokenProvider.parseLoginUser(token);
                if (!isCurrentAccessToken(user)) {
                    SecurityContextHolder.clearContext();
                    filterChain.doFilter(request, response);
                    return;
                }
                UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException | IllegalArgumentException ignored) {
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }

    private boolean isCurrentAccessToken(LoginUser loginUser) {
        if (loginUser == null || loginUser.getUserId() == null) {
            return false;
        }
        User user = userMapper.selectById(loginUser.getUserId());
        if (user == null || Boolean.FALSE.equals(user.getEnabled())) {
            return false;
        }
        long currentVersion = user.getTokenVersion() == null ? 0L : user.getTokenVersion();
        long tokenVersion = loginUser.getTokenVersion() == null ? 0L : loginUser.getTokenVersion();
        return currentVersion == tokenVersion;
    }
}
