package com.ekusys.exam.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ekusys.exam.repository.entity.User;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    @Select("select r.code from sys_role r inner join sys_user_role ur on ur.role_id = r.id where ur.user_id = #{userId}")
    List<String> selectRoleCodes(@Param("userId") Long userId);

    @Select("select u.id from sys_user u inner join sys_user_role ur on ur.user_id=u.id inner join sys_role r on r.id=ur.role_id where r.code=#{roleCode} and u.enabled=1 order by u.username,u.id")
    List<Long> selectIdsByRoleCode(@Param("roleCode") String roleCode);

    @Select("select * from sys_user where username=#{username} limit 1")
    User selectByUsername(@Param("username") String username);
}
