package com.ekusys.exam.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.common.security.LoginUser;
import com.ekusys.exam.repository.entity.User;
import com.ekusys.exam.repository.mapper.UserMapper;
import java.util.List;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserMapper userMapper;

    public CustomUserDetailsService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null) {
            throw new UsernameNotFoundException("用户不存在");
        }
        if (Boolean.FALSE.equals(user.getEnabled())) {
            throw new BusinessException("账号已被禁用，请联系管理员");
        }
        List<String> roles = userMapper.selectRoleCodes(user.getId());
        return LoginUser.builder()
            .userId(user.getId())
            .username(user.getUsername())
            .password(user.getPassword())
            .enabled(Boolean.TRUE.equals(user.getEnabled()))
            .roles(roles)
            .tokenVersion(user.getTokenVersion() == null ? 0L : user.getTokenVersion())
            .build();
    }
}
