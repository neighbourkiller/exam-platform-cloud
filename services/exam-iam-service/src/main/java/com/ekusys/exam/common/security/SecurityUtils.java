package com.ekusys.exam.common.security;

import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static Long getCurrentUserId() {
        LoginUser user = getCurrentUser();
        return user == null ? null : user.getUserId();
    }

    public static String getCurrentUsername() {
        LoginUser user = getCurrentUser();
        return user == null ? null : user.getUsername();
    }

    public static List<String> getCurrentRoles() {
        LoginUser user = getCurrentUser();
        return user == null ? List.of() : user.getRoles();
    }

    public static LoginUser getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof LoginUser loginUser)) {
            return null;
        }
        return loginUser;
    }
}
