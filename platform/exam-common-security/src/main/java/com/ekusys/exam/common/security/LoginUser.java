package com.ekusys.exam.common.security;

import java.util.List;

public record LoginUser(Long userId, String username, List<String> roles, Long tokenVersion) {
}
