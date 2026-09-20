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
}
