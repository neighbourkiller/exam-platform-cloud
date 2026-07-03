package com.ekusys.exam.common.security;

import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

public final class SecurityUtils {
    private SecurityUtils() {
    }

    public static Long getCurrentUserId() {
        LoginUser user = getCurrentUser();
        return user == null ? null : user.userId();
    }

    public static String getCurrentUsername() {
        LoginUser user = getCurrentUser();
        return user == null ? null : user.username();
    }

    public static List<String> getCurrentRoles() {
        LoginUser user = getCurrentUser();
        return user == null ? List.of() : user.roles();
    }

    public static LoginUser getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return null;
        }
        Number uid = jwt.getClaim("uid");
        List<String> roles = jwt.getClaimAsStringList("roles");
        Number tokenVersion = jwt.getClaim("tokenVersion");
        return new LoginUser(uid == null ? null : uid.longValue(), jwt.getSubject(),
            roles == null ? List.of() : roles, tokenVersion == null ? 0L : tokenVersion.longValue());
    }
}
