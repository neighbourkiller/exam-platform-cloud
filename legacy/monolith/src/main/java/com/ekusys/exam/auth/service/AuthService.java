package com.ekusys.exam.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ekusys.exam.auth.dto.AuthTokens;
import com.ekusys.exam.auth.dto.ChangePasswordRequest;
import com.ekusys.exam.auth.dto.LoginRequest;
import com.ekusys.exam.auth.dto.MeResponse;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.common.security.JwtTokenProvider;
import com.ekusys.exam.common.security.LoginUser;
import com.ekusys.exam.common.security.SecurityUtils;
import com.ekusys.exam.repository.entity.StudentProfile;
import com.ekusys.exam.repository.entity.User;
import com.ekusys.exam.repository.mapper.StudentProfileMapper;
import com.ekusys.exam.repository.mapper.UserMapper;
import io.jsonwebtoken.JwtException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final UserMapper userMapper;
    private final StudentProfileMapper studentProfileMapper;
    private final RefreshTokenSessionService refreshTokenSessionService;
    private final PasswordEncoder passwordEncoder;

    public AuthService(AuthenticationManager authenticationManager,
                       JwtTokenProvider jwtTokenProvider,
                       UserMapper userMapper,
                       StudentProfileMapper studentProfileMapper,
                       RefreshTokenSessionService refreshTokenSessionService,
                       PasswordEncoder passwordEncoder) {
        this.authenticationManager = authenticationManager;
        this.jwtTokenProvider = jwtTokenProvider;
        this.userMapper = userMapper;
        this.studentProfileMapper = studentProfileMapper;
        this.refreshTokenSessionService = refreshTokenSessionService;
        this.passwordEncoder = passwordEncoder;
    }

    public AuthTokens login(LoginRequest request) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
            );
        } catch (AuthenticationException ex) {
            BusinessException businessException = findBusinessException(ex);
            if (businessException != null) {
                throw businessException;
            }
            if (ex instanceof DisabledException) {
                throw new BusinessException("账号已被禁用，请联系管理员");
            }
            throw new BusinessException("用户名或密码错误");
        }
        LoginUser user = (LoginUser) authentication.getPrincipal();
        log.info("User login success: userId={}, username={}", user.getUserId(), user.getUsername());
        return issueTokens(user);
    }

    public AuthTokens refresh(String refreshToken) {
        LoginUser tokenUser;
        String tokenId;
        try {
            if (!jwtTokenProvider.isRefreshToken(refreshToken)) {
                throw new BusinessException("无效的刷新令牌");
            }
            tokenUser = jwtTokenProvider.parseLoginUser(refreshToken);
            tokenId = jwtTokenProvider.getTokenId(refreshToken);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new BusinessException("无效的刷新令牌");
        }
        if (!refreshTokenSessionService.isActive(tokenUser.getUserId(), tokenId)) {
            throw new BusinessException("刷新令牌已失效，请重新登录");
        }

        User user = userMapper.selectById(tokenUser.getUserId());
        if (user == null || Boolean.FALSE.equals(user.getEnabled())) {
            refreshTokenSessionService.revoke(tokenUser.getUserId());
            throw new BusinessException("用户状态异常，请重新登录");
        }
        LoginUser latestUser = LoginUser.builder()
            .userId(user.getId())
            .username(user.getUsername())
            .enabled(Boolean.TRUE.equals(user.getEnabled()))
            .roles(userMapper.selectRoleCodes(user.getId()))
            .tokenVersion(user.getTokenVersion() == null ? 0L : user.getTokenVersion())
            .build();
        log.info("Refresh token success: userId={}, username={}", latestUser.getUserId(), latestUser.getUsername());
        return issueTokens(latestUser);
    }

    public MeResponse me() {
        LoginUser current = SecurityUtils.getCurrentUser();
        if (current == null) {
            throw new BusinessException("未登录");
        }
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getId, current.getUserId()));
        StudentProfile profile = studentProfileMapper.selectOne(
            new LambdaQueryWrapper<StudentProfile>().eq(StudentProfile::getUserId, current.getUserId())
        );
        return MeResponse.builder()
            .userId(current.getUserId())
            .username(current.getUsername())
            .realName(user == null ? current.getUsername() : user.getRealName())
            .studentNo(profile == null ? null : profile.getStudentNo())
            .enrollmentYear(profile == null ? null : profile.getEnrollmentYear())
            .roles(current.getRoles())
            .build();
    }

    @Transactional
    public void changePassword(ChangePasswordRequest request) {
        LoginUser current = SecurityUtils.getCurrentUser();
        if (current == null) {
            throw new BusinessException("未登录");
        }
        User user = userMapper.selectById(current.getUserId());
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        if (!passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
            throw new BusinessException("旧密码错误");
        }
        if (request.getOldPassword().equals(request.getNewPassword())) {
            throw new BusinessException("新密码不能与旧密码相同");
        }
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        user.setTokenVersion((user.getTokenVersion() == null ? 0L : user.getTokenVersion()) + 1);
        userMapper.updateById(user);
        refreshTokenSessionService.revoke(current.getUserId());
        log.info("User changed password: userId={}, username={}", current.getUserId(), current.getUsername());
    }

    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        try {
            if (!jwtTokenProvider.isRefreshToken(refreshToken)) {
                return;
            }
            LoginUser tokenUser = jwtTokenProvider.parseLoginUser(refreshToken);
            refreshTokenSessionService.revoke(tokenUser.getUserId());
        } catch (JwtException | IllegalArgumentException ignored) {
            // Ignore invalid refresh token on logout; cookie cleanup happens at controller layer.
        }
    }

    private AuthTokens issueTokens(LoginUser user) {
        String accessToken = jwtTokenProvider.createAccessToken(user);
        String refreshTokenId = UUID.randomUUID().toString();
        String refreshToken = jwtTokenProvider.createRefreshToken(user, refreshTokenId);
        refreshTokenSessionService.store(user.getUserId(), refreshTokenId, jwtTokenProvider.getExpiration(refreshToken));
        return AuthTokens.builder()
            .accessToken(accessToken)
            .refreshToken(refreshToken)
            .tokenType("Bearer")
            .roles(user.getRoles())
            .build();
    }

    private BusinessException findBusinessException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof BusinessException businessException) {
                return businessException;
            }
            current = current.getCause();
        }
        return null;
    }
}

