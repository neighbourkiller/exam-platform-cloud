package com.ekusys.exam.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ekusys.exam.repository.entity.Submission;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SubmissionMapper extends BaseMapper<Submission> {
}
