package com.ekusys.exam.iam.controller;

import com.ekusys.exam.common.api.ApiResponse;
import com.ekusys.exam.iam.api.UserBatchRequest;
import com.ekusys.exam.iam.api.UserSummary;
import com.ekusys.exam.repository.entity.User;
import com.ekusys.exam.repository.mapper.UserMapper;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/users")
public class InternalUserController {
    private final UserMapper userMapper;

    public InternalUserController(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @GetMapping("/{id}")
    public ApiResponse<UserSummary> get(@PathVariable Long id) {
        return ApiResponse.ok(toSummary(userMapper.selectById(id)));
    }

    @PostMapping("/batch")
    public ApiResponse<List<UserSummary>> batch(@RequestBody UserBatchRequest request) {
        List<Long> ids = request == null || request.userIds() == null ? List.of() : request.userIds();
        if (ids.isEmpty()) {
            return ApiResponse.ok(List.of());
        }
        Map<Long, User> users = userMapper.selectBatchIds(ids).stream()
            .collect(Collectors.toMap(User::getId, Function.identity()));
        return ApiResponse.ok(ids.stream().map(users::get).filter(java.util.Objects::nonNull)
            .map(this::toSummary).toList());
    }

    @GetMapping("/by-role")
    public ApiResponse<List<UserSummary>> byRole(@RequestParam String role) {
        List<Long> ids = userMapper.selectIdsByRoleCode(role);
        return batch(new UserBatchRequest(ids));
    }

    private UserSummary toSummary(User user) {
        if (user == null) {
            return null;
        }
        return new UserSummary(user.getId(), user.getUsername(), user.getRealName(),
            Boolean.TRUE.equals(user.getEnabled()), userMapper.selectRoleCodes(user.getId()));
    }
}
