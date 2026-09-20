package com.ekusys.exam.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ekusys.exam.repository.entity.Question;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface QuestionMapper extends BaseMapper<Question> {
}
