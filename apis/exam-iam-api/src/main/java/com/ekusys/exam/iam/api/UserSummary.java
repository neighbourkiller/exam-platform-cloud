package com.ekusys.exam.iam.api;

import java.util.List;

public record UserSummary(Long id, String username, String realName, boolean enabled, List<String> roles) {
}
